package com.antondev.keys;

import com.antondev.keys.data.DataSaver;
import com.antondev.keys.data.MemoryStore;
import com.antondev.keys.data.SqliteStore;
import com.antondev.keys.model.KeyTier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Phase3PersistenceReliabilityTest {
    @TempDir Path directory;

    @Test
    void schemaOneMigratesWithBackupWithoutRewritingAuthoritativeRows() throws Exception {
        Path path = directory.resolve("plexonkeys.db");
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        MemoryStore.Position position = MemoryStore.Position.of(world, 12, 70, -31);
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + path.toAbsolutePath(), new Properties());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, basic INTEGER NOT NULL CHECK(basic >= 0), rare INTEGER NOT NULL CHECK(rare >= 0), epic INTEGER NOT NULL CHECK(epic >= 0), legendary INTEGER NOT NULL CHECK(legendary >= 0)) WITHOUT ROWID");
            s.execute("CREATE TABLE placed_blocks (world TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(world, position)) WITHOUT ROWID");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO players VALUES(?,?,?,?,?,?)")) {
                p.setString(1, player.toString()); p.setString(2, "Tonim"); p.setLong(3, 7);
                p.setLong(4, 6); p.setLong(5, 5); p.setLong(6, 4); p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO placed_blocks VALUES(?,?)")) {
                p.setString(1, world.toString()); p.setLong(2, position.packed()); p.executeUpdate();
            }
            s.execute("PRAGMA user_version=1");
        }
        byte[] schemaOne = Files.readAllBytes(path);

        SqliteStore database = new SqliteStore(path);
        MemoryStore loaded = database.load();
        assertArrayEquals(schemaOne, Files.readAllBytes(database.migrationBackupPath()));
        assertEquals(7, loaded.balance(player, KeyTier.BASIC));
        assertEquals(4, loaded.balance(player, KeyTier.LEGENDARY));
        assertTrue(loaded.artificial(position));
        assertEquals(0, loaded.dirtyCount());
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + path.toAbsolutePath(), new Properties());
             Statement s = c.createStatement();
             var version = s.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(SqliteStore.SCHEMA_VERSION, version.getInt(1));
        }
        assertTrue(database.load().artificial(position), "idempotent migration must keep placed-block provenance");
    }

    @Test
    void consumeDebitAndReplayRecordAreSelectedInTheSameBoundedSnapshot() {
        MemoryStore memory = new MemoryStore();
        UUID player = UUID.randomUUID();
        memory.set(player, KeyTier.RARE, 5);
        memory.acknowledge(memory.snapshot());

        MemoryStore.ConsumeMutation mutation = memory.consume(player, KeyTier.RARE, 2, "txn-paired");
        assertTrue(mutation.success());
        MemoryStore.Snapshot snapshot = memory.snapshot(64, memory.revision());
        assertTrue(snapshot.accounts().containsKey(player));
        assertTrue(snapshot.consumes().containsKey("txn-paired"));
        assertEquals(snapshot.accounts().get(player).version(), snapshot.consumes().get("txn-paired").version());
    }

    @Test
    void durableConsumeStateSurvivesRestart() throws Exception {
        Path path = directory.resolve("keys-restart.db");
        SqliteStore database = new SqliteStore(path);
        MemoryStore memory = database.load();
        UUID player = UUID.randomUUID();
        memory.set(player, KeyTier.EPIC, 3);
        DataSaver saver = new DataSaver(database, memory, Logger.getAnonymousLogger());
        try {
            saver.saveAndWait();
            MemoryStore.ConsumeMutation mutation = memory.consume(player, KeyTier.EPIC, 1, "txn-restart");
            assertTrue(mutation.success());
            saver.saveAndWait();
        } finally {
            saver.close();
        }

        MemoryStore reloaded = database.load();
        assertEquals(2, reloaded.balance(player, KeyTier.EPIC));
        assertTrue(reloaded.consumeReplay("txn-restart").isPresent());
        assertEquals(0, reloaded.dirtyCount());
    }

    @Test
    void exactDuplicateNeverDebitsTwiceAndConflictFailsClosed() {
        MemoryStore memory = new MemoryStore();
        UUID player = UUID.randomUUID();
        memory.set(player, KeyTier.BASIC, 10);
        memory.acknowledge(memory.snapshot());
        assertTrue(memory.consume(player, KeyTier.BASIC, 4, "same-id").success());
        long revision = memory.revision();

        MemoryStore.ConsumeMutation duplicate = memory.consume(player, KeyTier.BASIC, 4, "same-id");
        assertTrue(duplicate.duplicate());
        assertEquals(6, memory.balance(player, KeyTier.BASIC));
        assertEquals(revision, memory.revision(), "duplicate reuse must not create another dirty revision");
        assertThrows(IllegalArgumentException.class,
                () -> memory.consume(player, KeyTier.BASIC, 3, "same-id"));
        assertEquals(6, memory.balance(player, KeyTier.BASIC));
    }

    @Test
    void failedWriteKeepsConsumeDebitAndReplayDirtyForRetry() throws Exception {
        Path path = directory.resolve("keys-failure.db");
        SqliteStore real = new SqliteStore(path);
        MemoryStore memory = real.load();
        UUID player = UUID.randomUUID();
        memory.set(player, KeyTier.LEGENDARY, 2);
        MemoryStore.Snapshot baseline = memory.snapshot();
        real.save(baseline);
        memory.acknowledge(baseline);

        SqliteStore database = spy(new SqliteStore(path));
        doThrow(new SQLException("simulated persistence failure")).doCallRealMethod().when(database).save(any());
        DataSaver saver = new DataSaver(database, memory, Logger.getAnonymousLogger());
        try {
            memory.consume(player, KeyTier.LEGENDARY, 1, "txn-retry");
            assertThrows(IllegalStateException.class, saver::saveAndWait);
            assertEquals(1, memory.dirtyAccounts());
            assertEquals(1, memory.dirtyConsumes());
            assertTrue(memory.consumeReplayDirty("txn-retry"));
            assertTrue(saver.metrics().lastFailedSaveEpochMillis() > 0);

            saver.saveAndWait();
            assertEquals(0, memory.dirtyCount());
            MemoryStore reloaded = real.load();
            assertEquals(1, reloaded.balance(player, KeyTier.LEGENDARY));
            assertTrue(reloaded.consumeReplay("txn-retry").isPresent());
        } finally {
            saver.close();
        }
    }

    @Test
    void cleanStateDoesNotWriteAndWorkerQueueRemainsBounded() throws Exception {
        Path path = directory.resolve("keys-clean.db");
        SqliteStore database = spy(new SqliteStore(path));
        MemoryStore memory = database.load();
        DataSaver saver = new DataSaver(database, memory, Logger.getAnonymousLogger());
        try {
            clearInvocations(database);
            DataSaver.Result result = saver.saveAndWait();
            assertEquals(0, result.players());
            verify(database, never()).save(any());
            assertEquals(1, saver.metrics().queueCapacity());
            assertTrue(saver.metrics().queueDepth() <= saver.metrics().queueCapacity());
            assertTrue(saver.metrics().queueHighWaterMark() <= saver.metrics().queueCapacity());
        } finally {
            saver.close();
        }
    }

    @Test
    void replayMemoryHasHardBoundEvenWhenDatabaseIsUnavailable() {
        MemoryStore memory = new MemoryStore();
        UUID player = UUID.randomUUID();
        for (int i = 0; i <= MemoryStore.MAX_CONSUME_REPLAY_RECORDS; i++) {
            memory.consume(player, KeyTier.BASIC, 1, "bounded-" + i);
        }
        assertEquals(MemoryStore.MAX_CONSUME_REPLAY_RECORDS + 1, memory.consumeReplayCount());
        assertThrows(IllegalStateException.class,
                () -> memory.consume(player, KeyTier.BASIC, 1, "bounded-overflow"));
    }

    @Test
    void sourceContractsKeepPersistenceOutOfHotBlockEventsAndCheckpointSingleton() throws Exception {
        String plugin = source("src/main/java/com/antondev/keys/PlexonKeys.java");
        String tracking = source("src/main/java/com/antondev/keys/listener/TrackingListener.java");
        String blocks = source("src/main/java/com/antondev/keys/listener/BlockActivityListener.java");
        String claims = source("src/main/java/com/antondev/keys/reward/ClaimService.java");
        String balances = source("src/main/java/com/antondev/keys/service/KeyBalanceService.java");
        String policy = source("src/main/java/com/antondev/keys/activity/ProvenancePolicy.java");

        assertEquals(1, occurrences(plugin, "runTaskTimer("));
        assertTrue(plugin.contains("checkpoint.cancel()"));
        assertTrue(plugin.contains("if (settings().checkpointsEnabled())"));
        assertTrue(plugin.contains("saver.saveAndWait()"));
        for (String listener : new String[]{tracking, blocks}) {
            assertFalse(listener.contains("saveAndWait"));
            assertFalse(listener.contains("SqliteStore"));
            assertFalse(listener.contains("java.nio.file"));
            assertFalse(listener.contains("Files."));
        }
        assertTrue(claims.indexOf("debitForClaim") < claims.indexOf("persistCriticalState(\"physical key claim debit\")"));
        assertTrue(claims.indexOf("persistCriticalState(\"physical key claim debit\")") < claims.indexOf("setStorageContents(plan.contents())"));
        assertTrue(balances.contains("data.consume(id, keyTier, amount, transactionId)"));
        assertTrue(balances.contains("persistCriticalState(\"consume \" + transactionId)"));
        assertFalse(balances.contains("LinkedHashMap<String, ConsumeRecord>"));
        assertTrue(policy.contains("getBoolean(path, false)"), "UNKNOWN/spawner opt-ins must remain fail-closed by default");
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static int occurrences(String input, String needle) {
        int count = 0;
        for (int index = 0; (index = input.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }
}
