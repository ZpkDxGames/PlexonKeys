package com.antondev.keys.data;

import com.antondev.keys.model.KeyTier;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;
import java.util.UUID;

/** One transactional database. Every save writes only changed rows, in bounded JDBC batches. */
public final class SqliteStore {
    public static final int SCHEMA_VERSION = 2;

    private final Path file;

    public SqliteStore(Path file) { this.file = file.toAbsolutePath(); }

    public Path migrationBackupPath() {
        return file.resolveSibling(file.getFileName() + ".pre-v2.bak");
    }

    private Connection connect() throws SQLException {
        // Explicit driver construction works when a plugin classloader is not visible to DriverManager.
        Connection connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file, new Properties());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("PRAGMA synchronous=FULL");
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    public MemoryStore load() throws Exception {
        Files.createDirectories(file.getParent());
        int existingVersion;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("PRAGMA quick_check")) {
                if (!r.next() || !"ok".equals(r.getString(1))) {
                    throw new SQLException("SQLite integrity check failed; database was not overwritten");
                }
            }
            try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
                existingVersion = r.next() ? r.getInt(1) : 0;
            }
            if (existingVersion > SCHEMA_VERSION) {
                throw new SQLException("This database belongs to a newer PlexonKeys version");
            }
        }

        // Schema 1 is the production Phase 2 database. Preserve an immutable pre-migration copy once.
        if (existingVersion == 1 && Files.exists(file)) {
            Path backup = migrationBackupPath();
            if (!Files.exists(backup)) Files.copy(file, backup);
        }

        MemoryStore memory = new MemoryStore();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=DELETE");
            c.setAutoCommit(false);
            try {
                s.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, basic INTEGER NOT NULL CHECK(basic >= 0), rare INTEGER NOT NULL CHECK(rare >= 0), epic INTEGER NOT NULL CHECK(epic >= 0), legendary INTEGER NOT NULL CHECK(legendary >= 0)) WITHOUT ROWID");
                s.execute("CREATE TABLE IF NOT EXISTS placed_blocks (world TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(world, position)) WITHOUT ROWID");
                s.execute("CREATE TABLE IF NOT EXISTS consume_replay (transaction_id TEXT PRIMARY KEY, player TEXT NOT NULL, tier TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount > 0), created_at INTEGER NOT NULL) WITHOUT ROWID");
                s.execute("CREATE INDEX IF NOT EXISTS idx_consume_replay_created_at ON consume_replay(created_at)");
                pruneConsumeReplays(c, System.currentTimeMillis());
                s.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                c.commit();
            } catch (Exception error) {
                try { c.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                throw error;
            }

            try (ResultSet r = s.executeQuery("SELECT * FROM players")) {
                while (r.next()) {
                    memory.loadAccount(new MemoryStore.Account(
                            UUID.fromString(r.getString("uuid")), r.getString("name"),
                            r.getLong("basic"), r.getLong("rare"), r.getLong("epic"), r.getLong("legendary")));
                }
            }
            try (ResultSet r = s.executeQuery("SELECT world, position FROM placed_blocks")) {
                while (r.next()) memory.loadBlock(new MemoryStore.Position(UUID.fromString(r.getString(1)), r.getLong(2)));
            }
            try (ResultSet r = s.executeQuery(
                    "SELECT transaction_id, player, tier, amount, created_at FROM consume_replay ORDER BY created_at ASC, transaction_id ASC")) {
                while (r.next()) {
                    memory.loadConsumeReplay(new MemoryStore.ConsumeReplay(
                            r.getString("transaction_id"),
                            UUID.fromString(r.getString("player")),
                            KeyTier.valueOf(r.getString("tier")),
                            r.getLong("amount"),
                            r.getLong("created_at")));
                }
            }
        }
        return memory;
    }

    public void save(MemoryStore.Snapshot snapshot) throws SQLException {
        if (snapshot.empty()) return;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement players = c.prepareStatement(
                         "INSERT INTO players VALUES(?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, basic=excluded.basic, rare=excluded.rare, epic=excluded.epic, legendary=excluded.legendary");
                 PreparedStatement put = c.prepareStatement("INSERT OR IGNORE INTO placed_blocks VALUES(?,?)");
                 PreparedStatement remove = c.prepareStatement("DELETE FROM placed_blocks WHERE world=? AND position=?");
                 PreparedStatement replay = c.prepareStatement(
                         "INSERT OR IGNORE INTO consume_replay(transaction_id,player,tier,amount,created_at) VALUES(?,?,?,?,?)");
                 PreparedStatement replayLookup = c.prepareStatement(
                         "SELECT player,tier,amount,created_at FROM consume_replay WHERE transaction_id=?")) {
                int count = 0;
                for (var row : snapshot.accounts().values()) {
                    var a = row.value();
                    players.setString(1, a.player().toString());
                    players.setString(2, a.name());
                    players.setLong(3, a.basic());
                    players.setLong(4, a.rare());
                    players.setLong(5, a.epic());
                    players.setLong(6, a.legendary());
                    players.addBatch();
                    if (++count % 1000 == 0) players.executeBatch();
                }
                players.executeBatch();

                count = 0;
                for (var entry : snapshot.blocks().entrySet()) {
                    PreparedStatement query = entry.getValue().value() ? put : remove;
                    query.setString(1, entry.getKey().world().toString());
                    query.setLong(2, entry.getKey().packed());
                    query.addBatch();
                    if (++count % 1000 == 0) {
                        put.executeBatch();
                        remove.executeBatch();
                    }
                }
                put.executeBatch();
                remove.executeBatch();

                for (var row : snapshot.consumes().values()) {
                    MemoryStore.ConsumeReplay value = row.value();
                    replay.setString(1, value.transactionId());
                    replay.setString(2, value.player().toString());
                    replay.setString(3, value.tier().name());
                    replay.setLong(4, value.amount());
                    replay.setLong(5, value.createdAtEpochMillis());
                    if (replay.executeUpdate() == 0) {
                        replayLookup.setString(1, value.transactionId());
                        try (ResultSet existing = replayLookup.executeQuery()) {
                            if (!existing.next()
                                    || !existing.getString("player").equals(value.player().toString())
                                    || !existing.getString("tier").equals(value.tier().name())
                                    || existing.getLong("amount") != value.amount()
                                    || existing.getLong("created_at") != value.createdAtEpochMillis()) {
                                throw new SQLException("Conflicting durable consume transactionId: " + value.transactionId());
                            }
                        }
                    }
                }

                pruneConsumeReplays(c, System.currentTimeMillis());
                c.commit();
            } catch (Exception error) {
                try { c.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                if (error instanceof SQLException sql) throw sql;
                throw new SQLException("Could not save PlexonKeys state", error);
            }
        }
    }

    private static void pruneConsumeReplays(Connection connection, long now) throws SQLException {
        long cutoff = Math.max(0L, now - MemoryStore.CONSUME_REPLAY_RETENTION_MILLIS);
        try (PreparedStatement expired = connection.prepareStatement("DELETE FROM consume_replay WHERE created_at < ?")) {
            expired.setLong(1, cutoff);
            expired.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "DELETE FROM consume_replay WHERE transaction_id IN ("
                            + "SELECT transaction_id FROM consume_replay ORDER BY created_at DESC, transaction_id DESC "
                            + "LIMIT -1 OFFSET " + MemoryStore.MAX_CONSUME_REPLAY_RECORDS + ")");
        }
    }
}
