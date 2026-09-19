package com.antondev.keys.data;

import com.antondev.keys.data.MemoryStore.*;
import com.antondev.keys.model.KeyTier;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * PlexonKeys schema-3 SQLite store. One lifecycle-owned writer connection serializes checkpoints and
 * critical transaction/journal rows; startup reads use short-lived read connections only.
 */
public class SqliteStore implements AutoCloseable {
    public static final int SCHEMA_VERSION = 3;

    private final Path file;
    private final Object writerLock = new Object();
    private Connection writer;
    private volatile boolean writerHealthy = true;
    private volatile String writerDetail = "not-open";

    public SqliteStore(Path file) { this.file = file.toAbsolutePath(); }

    public Path migrationBackupPath() {
        return file.resolveSibling(file.getFileName() + ".pre-v3.bak");
    }

    private Connection connect() throws SQLException {
        Connection connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file, new Properties());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    public MemoryStore load() throws Exception {
        Files.createDirectories(file.getParent());
        int existingVersion;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("SQLite integrity check failed; database was not overwritten");
                }
            }
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                existingVersion = result.next() ? result.getInt(1) : 0;
            }
            if (existingVersion > SCHEMA_VERSION) {
                throw new SQLException("This database belongs to a newer PlexonKeys version");
            }
        }

        if (existingVersion > 0 && existingVersion < SCHEMA_VERSION && Files.exists(file)) {
            Path backup = migrationBackupPath();
            if (!Files.exists(backup)) Files.copy(file, backup);
        }

        migrateSchema();
        MemoryStore memory = readState();
        ensureWriter();
        return memory;
    }

    private void migrateSchema() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, basic INTEGER NOT NULL CHECK(basic >= 0), rare INTEGER NOT NULL CHECK(rare >= 0), epic INTEGER NOT NULL CHECK(epic >= 0), legendary INTEGER NOT NULL CHECK(legendary >= 0)) WITHOUT ROWID");
                statement.execute("CREATE TABLE IF NOT EXISTS placed_blocks (world TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(world, position)) WITHOUT ROWID");
                // Preserved compatibility table: schema-2 consume replay remains readable and restart-idempotent.
                statement.execute("CREATE TABLE IF NOT EXISTS consume_replay (transaction_id TEXT PRIMARY KEY, player TEXT NOT NULL, tier TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount > 0), created_at INTEGER NOT NULL) WITHOUT ROWID");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_consume_replay_created_at ON consume_replay(created_at)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS key_transactions (
                          transaction_id TEXT PRIMARY KEY,
                          kind TEXT NOT NULL,
                          player TEXT NOT NULL,
                          tier TEXT NOT NULL,
                          requested INTEGER NOT NULL CHECK(requested > 0),
                          applied INTEGER NOT NULL CHECK(applied >= 0),
                          source TEXT NOT NULL,
                          outcome TEXT NOT NULL,
                          result_balance INTEGER NOT NULL CHECK(result_balance >= 0),
                          created_at INTEGER NOT NULL,
                          updated_at INTEGER NOT NULL
                        ) WITHOUT ROWID
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_key_transactions_player ON key_transactions(player, created_at)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS key_claims (
                          transaction_id TEXT PRIMARY KEY,
                          player TEXT NOT NULL,
                          basic INTEGER NOT NULL CHECK(basic >= 0),
                          rare INTEGER NOT NULL CHECK(rare >= 0),
                          epic INTEGER NOT NULL CHECK(epic >= 0),
                          legendary INTEGER NOT NULL CHECK(legendary >= 0),
                          delivery_data TEXT NOT NULL,
                          state TEXT NOT NULL,
                          before_fingerprint TEXT NOT NULL,
                          after_fingerprint TEXT NOT NULL,
                          created_at INTEGER NOT NULL,
                          updated_at INTEGER NOT NULL,
                          detail TEXT NOT NULL
                        ) WITHOUT ROWID
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_key_claims_player_state ON key_claims(player, state, created_at)");
                pruneConsumeReplays(connection, System.currentTimeMillis());
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                connection.commit();
            } catch (Exception error) {
                try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                if (error instanceof SQLException sql) throw sql;
                throw new SQLException("Could not migrate PlexonKeys database schema", error);
            }
        }
    }

    private MemoryStore readState() throws SQLException {
        MemoryStore memory = new MemoryStore();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT * FROM players")) {
                while (result.next()) {
                    memory.loadAccount(new Account(
                            UUID.fromString(result.getString("uuid")), result.getString("name"),
                            result.getLong("basic"), result.getLong("rare"),
                            result.getLong("epic"), result.getLong("legendary")));
                }
            }
            try (ResultSet result = statement.executeQuery("SELECT world, position FROM placed_blocks")) {
                while (result.next()) {
                    memory.loadBlock(new Position(UUID.fromString(result.getString(1)), result.getLong(2)));
                }
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT transaction_id, player, tier, amount, created_at FROM consume_replay ORDER BY created_at ASC, transaction_id ASC")) {
                while (result.next()) {
                    memory.loadConsumeReplay(new ConsumeReplay(
                            result.getString("transaction_id"),
                            UUID.fromString(result.getString("player")),
                            KeyTier.valueOf(result.getString("tier")),
                            result.getLong("amount"),
                            result.getLong("created_at")));
                }
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT transaction_id,kind,player,tier,requested,applied,source,outcome,result_balance,created_at,updated_at FROM key_transactions")) {
                while (result.next()) {
                    memory.loadTransaction(new KeyTransaction(
                            result.getString("transaction_id"),
                            TransactionKind.valueOf(result.getString("kind")),
                            UUID.fromString(result.getString("player")),
                            KeyTier.valueOf(result.getString("tier")),
                            result.getLong("requested"),
                            result.getLong("applied"),
                            result.getString("source"),
                            TransactionOutcome.valueOf(result.getString("outcome")),
                            result.getLong("result_balance"),
                            result.getLong("created_at"),
                            result.getLong("updated_at")));
                }
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT transaction_id,player,basic,rare,epic,legendary,delivery_data,state,before_fingerprint,after_fingerprint,created_at,updated_at,detail FROM key_claims")) {
                while (result.next()) {
                    EnumMap<KeyTier, Long> amounts = new EnumMap<>(KeyTier.class);
                    putPositive(amounts, KeyTier.BASIC, result.getLong("basic"));
                    putPositive(amounts, KeyTier.RARE, result.getLong("rare"));
                    putPositive(amounts, KeyTier.EPIC, result.getLong("epic"));
                    putPositive(amounts, KeyTier.LEGENDARY, result.getLong("legendary"));
                    memory.loadClaim(new ClaimRecord(
                            result.getString("transaction_id"),
                            UUID.fromString(result.getString("player")),
                            amounts,
                            result.getString("delivery_data"),
                            ClaimState.valueOf(result.getString("state")),
                            result.getString("before_fingerprint"),
                            result.getString("after_fingerprint"),
                            result.getLong("created_at"),
                            result.getLong("updated_at"),
                            result.getString("detail")));
                }
            }
        }
        return memory;
    }

    private static void putPositive(Map<KeyTier, Long> amounts, KeyTier tier, long value) {
        if (value > 0) amounts.put(tier, value);
    }

    private void ensureWriter() throws SQLException {
        synchronized (writerLock) {
            if (writer != null && !writer.isClosed()) return;
            writer = connect();
            try (Statement statement = writer.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=10000");
                statement.execute("PRAGMA synchronous=FULL");
            }
            writer.setAutoCommit(false);
            writerHealthy = true;
            writerDetail = "ready";
        }
    }

    public void save(MemoryStore.Snapshot snapshot) throws SQLException {
        if (snapshot.empty()) return;
        synchronized (writerLock) {
            ensureWriter();
            try {
                savePlayers(writer, snapshot);
                saveBlocks(writer, snapshot);
                saveLegacyConsumes(writer, snapshot);
                saveTransactions(writer, snapshot);
                saveClaims(writer, snapshot);
                pruneConsumeReplays(writer, System.currentTimeMillis());
                writer.commit();
                writerHealthy = true;
                writerDetail = "ready";
            } catch (Exception error) {
                writerHealthy = false;
                writerDetail = error.getClass().getSimpleName() + ": " + Objects.toString(error.getMessage(), "");
                try { writer.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                if (error instanceof SQLException sql) throw sql;
                throw new SQLException("Could not save PlexonKeys state", error);
            }
        }
    }

    private static void savePlayers(Connection connection, MemoryStore.Snapshot snapshot) throws SQLException {
        try (PreparedStatement players = connection.prepareStatement(
                "INSERT INTO players VALUES(?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, basic=excluded.basic, rare=excluded.rare, epic=excluded.epic, legendary=excluded.legendary")) {
            int count = 0;
            for (var row : snapshot.accounts().values()) {
                Account account = row.value();
                players.setString(1, account.player().toString());
                players.setString(2, account.name());
                players.setLong(3, account.basic());
                players.setLong(4, account.rare());
                players.setLong(5, account.epic());
                players.setLong(6, account.legendary());
                players.addBatch();
                if (++count % 1000 == 0) players.executeBatch();
            }
            players.executeBatch();
        }
    }

    private static void saveBlocks(Connection connection, MemoryStore.Snapshot snapshot) throws SQLException {
        try (PreparedStatement put = connection.prepareStatement("INSERT OR IGNORE INTO placed_blocks VALUES(?,?)");
             PreparedStatement remove = connection.prepareStatement("DELETE FROM placed_blocks WHERE world=? AND position=?")) {
            int count = 0;
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
        }
    }

    private static void saveLegacyConsumes(Connection connection, MemoryStore.Snapshot snapshot) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                     "INSERT OR IGNORE INTO consume_replay(transaction_id,player,tier,amount,created_at) VALUES(?,?,?,?,?)");
             PreparedStatement lookup = connection.prepareStatement(
                     "SELECT player,tier,amount,created_at FROM consume_replay WHERE transaction_id=?")) {
            for (var row : snapshot.consumes().values()) {
                ConsumeReplay value = row.value();
                insert.setString(1, value.transactionId());
                insert.setString(2, value.player().toString());
                insert.setString(3, value.tier().name());
                insert.setLong(4, value.amount());
                insert.setLong(5, value.createdAtEpochMillis());
                if (insert.executeUpdate() == 0) {
                    lookup.setString(1, value.transactionId());
                    try (ResultSet existing = lookup.executeQuery()) {
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
        }
    }

    private static void saveTransactions(Connection connection, MemoryStore.Snapshot snapshot) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                     "INSERT OR IGNORE INTO key_transactions(transaction_id,kind,player,tier,requested,applied,source,outcome,result_balance,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)");
             PreparedStatement lookup = connection.prepareStatement(
                     "SELECT kind,player,tier,requested,applied,source,outcome,result_balance,created_at,updated_at FROM key_transactions WHERE transaction_id=?")) {
            for (var row : snapshot.transactions().values()) {
                KeyTransaction value = row.value();
                insert.setString(1, value.transactionId());
                insert.setString(2, value.kind().name());
                insert.setString(3, value.player().toString());
                insert.setString(4, value.tier().name());
                insert.setLong(5, value.requested());
                insert.setLong(6, value.applied());
                insert.setString(7, value.source());
                insert.setString(8, value.outcome().name());
                insert.setLong(9, value.resultBalance());
                insert.setLong(10, value.createdAtEpochMillis());
                insert.setLong(11, value.updatedAtEpochMillis());
                if (insert.executeUpdate() == 0) {
                    lookup.setString(1, value.transactionId());
                    try (ResultSet existing = lookup.executeQuery()) {
                        if (!existing.next() || !sameTransaction(existing, value)) {
                            throw new SQLException("Conflicting durable key transactionId: " + value.transactionId());
                        }
                    }
                }
            }
        }
    }

    private static boolean sameTransaction(ResultSet result, KeyTransaction value) throws SQLException {
        return result.getString("kind").equals(value.kind().name())
                && result.getString("player").equals(value.player().toString())
                && result.getString("tier").equals(value.tier().name())
                && result.getLong("requested") == value.requested()
                && result.getLong("applied") == value.applied()
                && result.getString("source").equals(value.source())
                && result.getString("outcome").equals(value.outcome().name())
                && result.getLong("result_balance") == value.resultBalance()
                && result.getLong("created_at") == value.createdAtEpochMillis()
                && result.getLong("updated_at") == value.updatedAtEpochMillis();
    }

    private static void saveClaims(Connection connection, MemoryStore.Snapshot snapshot) throws SQLException {
        try (PreparedStatement lookup = connection.prepareStatement(
                     "SELECT player,basic,rare,epic,legendary,delivery_data,created_at FROM key_claims WHERE transaction_id=?");
             PreparedStatement upsert = connection.prepareStatement(
                     """
                     INSERT INTO key_claims(transaction_id,player,basic,rare,epic,legendary,delivery_data,state,before_fingerprint,after_fingerprint,created_at,updated_at,detail)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                     ON CONFLICT(transaction_id) DO UPDATE SET
                       state=excluded.state,
                       before_fingerprint=excluded.before_fingerprint,
                       after_fingerprint=excluded.after_fingerprint,
                       updated_at=excluded.updated_at,
                       detail=excluded.detail
                     """)) {
            for (var row : snapshot.claims().values()) {
                ClaimRecord value = row.value();
                lookup.setString(1, value.transactionId());
                try (ResultSet existing = lookup.executeQuery()) {
                    if (existing.next() && !sameClaimIdentity(existing, value)) {
                        throw new SQLException("Conflicting durable claim transactionId: " + value.transactionId());
                    }
                }
                upsert.setString(1, value.transactionId());
                upsert.setString(2, value.player().toString());
                upsert.setLong(3, value.amounts().getOrDefault(KeyTier.BASIC, 0L));
                upsert.setLong(4, value.amounts().getOrDefault(KeyTier.RARE, 0L));
                upsert.setLong(5, value.amounts().getOrDefault(KeyTier.EPIC, 0L));
                upsert.setLong(6, value.amounts().getOrDefault(KeyTier.LEGENDARY, 0L));
                upsert.setString(7, value.deliveryData());
                upsert.setString(8, value.state().name());
                upsert.setString(9, value.beforeFingerprint());
                upsert.setString(10, value.afterFingerprint());
                upsert.setLong(11, value.createdAtEpochMillis());
                upsert.setLong(12, value.updatedAtEpochMillis());
                upsert.setString(13, value.detail());
                upsert.executeUpdate();
            }
        }
    }

    private static boolean sameClaimIdentity(ResultSet result, ClaimRecord value) throws SQLException {
        return result.getString("player").equals(value.player().toString())
                && result.getLong("basic") == value.amounts().getOrDefault(KeyTier.BASIC, 0L)
                && result.getLong("rare") == value.amounts().getOrDefault(KeyTier.RARE, 0L)
                && result.getLong("epic") == value.amounts().getOrDefault(KeyTier.EPIC, 0L)
                && result.getLong("legendary") == value.amounts().getOrDefault(KeyTier.LEGENDARY, 0L)
                && result.getString("delivery_data").equals(value.deliveryData())
                && result.getLong("created_at") == value.createdAtEpochMillis();
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

    public boolean writerHealthy() { return writerHealthy; }
    public String writerDetail() { return writerDetail; }

    @Override public void close() {
        synchronized (writerLock) {
            if (writer == null) return;
            try {
                writer.close();
            } catch (SQLException ignored) {
                writerHealthy = false;
                writerDetail = "close-failed";
            } finally {
                writer = null;
            }
        }
    }
}
