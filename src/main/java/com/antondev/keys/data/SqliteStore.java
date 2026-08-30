package com.antondev.keys.data;

import java.nio.file.*;
import java.sql.*;
import java.util.UUID;

/** One transactional database. Every save writes only changed rows, in bounded JDBC batches. */
public final class SqliteStore {
    private final Path file;
    public SqliteStore(Path file) { this.file = file.toAbsolutePath(); }
    private Connection connect() throws SQLException {
        // Explicit driver construction works when a plugin classloader is not visible to DriverManager.
        Connection connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file, new java.util.Properties());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("PRAGMA synchronous=FULL");
        } catch (SQLException error) { connection.close(); throw error; }
        return connection;
    }
    public MemoryStore load() throws Exception {
        Files.createDirectories(file.getParent());
        MemoryStore memory = new MemoryStore();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("PRAGMA quick_check")) {
                if (!r.next() || !"ok".equals(r.getString(1))) throw new SQLException("SQLite integrity check failed; database was not overwritten");
            }
            try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
                if (r.next() && r.getInt(1) > 1) throw new SQLException("This database belongs to a newer PlexonKeys version");
            }
            s.execute("PRAGMA journal_mode=DELETE");
            s.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, basic INTEGER NOT NULL CHECK(basic >= 0), rare INTEGER NOT NULL CHECK(rare >= 0), epic INTEGER NOT NULL CHECK(epic >= 0), legendary INTEGER NOT NULL CHECK(legendary >= 0)) WITHOUT ROWID");
            s.execute("CREATE TABLE IF NOT EXISTS placed_blocks (world TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(world, position)) WITHOUT ROWID");
            s.execute("PRAGMA user_version=1");
            try (ResultSet r = s.executeQuery("SELECT * FROM players")) {
                while (r.next()) memory.loadAccount(new MemoryStore.Account(UUID.fromString(r.getString("uuid")), r.getString("name"),
                        r.getLong("basic"), r.getLong("rare"), r.getLong("epic"), r.getLong("legendary")));
            }
            try (ResultSet r = s.executeQuery("SELECT world, position FROM placed_blocks")) {
                while (r.next()) memory.loadBlock(new MemoryStore.Position(UUID.fromString(r.getString(1)), r.getLong(2)));
            }
        }
        return memory;
    }
    public void save(MemoryStore.Snapshot snapshot) throws SQLException {
        if (snapshot.empty()) return;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement players = c.prepareStatement("INSERT INTO players VALUES(?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, basic=excluded.basic, rare=excluded.rare, epic=excluded.epic, legendary=excluded.legendary");
                 PreparedStatement put = c.prepareStatement("INSERT OR IGNORE INTO placed_blocks VALUES(?,?)");
                 PreparedStatement remove = c.prepareStatement("DELETE FROM placed_blocks WHERE world=? AND position=?")) {
                int count = 0;
                for (var row : snapshot.accounts().values()) {
                    var a = row.value();
                    players.setString(1, a.player().toString()); players.setString(2, a.name());
                    players.setLong(3, a.basic()); players.setLong(4, a.rare()); players.setLong(5, a.epic()); players.setLong(6, a.legendary());
                    players.addBatch();
                    if (++count % 1000 == 0) players.executeBatch();
                }
                players.executeBatch(); count = 0;
                for (var entry : snapshot.blocks().entrySet()) {
                    PreparedStatement query = entry.getValue().value() ? put : remove;
                    query.setString(1, entry.getKey().world().toString()); query.setLong(2, entry.getKey().packed()); query.addBatch();
                    if (++count % 1000 == 0) { put.executeBatch(); remove.executeBatch(); }
                }
                put.executeBatch(); remove.executeBatch();
                c.commit();
            } catch (Exception error) {
                try { c.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                throw error;
            }
        }
    }
}
