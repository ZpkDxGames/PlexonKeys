package com.antondev.keys;

import com.antondev.keys.data.*;
import com.antondev.keys.data.MemoryStore.*;
import com.antondev.keys.model.KeyTier;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqliteStoreTest {
    @TempDir Path directory;
    @Test void balancesPlacementsRemovalsAndNamesSurviveReopening() throws Exception {
        var db = new SqliteStore(directory.resolve("keys.db")); var memory = db.load();
        UUID id = UUID.randomUUID(), world = UUID.randomUUID(); var pos = Position.of(world, -12, -64, 91);
        memory.remember(id, "Tonim"); memory.set(id, KeyTier.LEGENDARY, 17); memory.mark(pos);
        db.save(memory.snapshot());
        var loaded = db.load();
        assertEquals("Tonim", loaded.account(id).name()); assertEquals(17, loaded.balance(id, KeyTier.LEGENDARY));
        assertTrue(loaded.artificial(pos)); assertEquals(0, loaded.dirtyCount());
        loaded.unmark(pos); loaded.set(id, KeyTier.LEGENDARY, 0); db.save(loaded.snapshot());
        assertFalse(db.load().artificial(pos)); assertEquals(0, db.load().balance(id, KeyTier.LEGENDARY));
    }
    @Test void sqlFailureRollsBackEveryRow() throws Exception {
        var db = new SqliteStore(directory.resolve("keys.db")); var memory = db.load();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); memory.set(a, KeyTier.BASIC, 7); db.save(memory.snapshot());
        Snapshot corrupt = new Snapshot(Map.of(a, new Versioned<>(2, new Account(a, "A", 99, 0, 0, 0)),
                b, new Versioned<>(3, new Account(b, "B", -1, 0, 0, 0))), Map.of());
        assertThrows(SQLException.class, () -> db.save(corrupt)); assertEquals(7, db.load().balance(a, KeyTier.BASIC));
    }
    @Test void corruptDatabaseIsNotSilentlyReplaced() throws Exception {
        Path path = directory.resolve("keys.db"); byte[] original = "This is not a database".getBytes(); Files.write(path, original);
        assertThrows(Exception.class, () -> new SqliteStore(path).load()); assertArrayEquals(original, Files.readAllBytes(path));
    }
    @Test void newerSchemaIsRejected() throws Exception {
        Path path = directory.resolve("keys.db"); var db = new SqliteStore(path); db.load();
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + path, new Properties()); var s = c.createStatement()) { s.execute("PRAGMA user_version=999"); }
        assertThrows(SQLException.class, db::load);
    }
    @Test void shutdownSavesChangesMadeWhileEarlierSaveWasInFlight() throws Exception {
        var db = spy(new SqliteStore(directory.resolve("keys.db"))); var memory = db.load(); UUID id = UUID.randomUUID();
        memory.set(id, KeyTier.RARE, 1);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        doAnswer(invocation -> { started.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)); return invocation.callRealMethod(); }).when(db).save(any());
        var saver = new DataSaver(db, memory, Logger.getAnonymousLogger());
        try {
            var first = saver.save(); assertTrue(started.await(5, TimeUnit.SECONDS));
            memory.set(id, KeyTier.RARE, 12); finish.countDown(); first.get(5, TimeUnit.SECONDS);
        } finally { finish.countDown(); saver.close(); }
        assertEquals(12, db.load().balance(id, KeyTier.RARE)); assertEquals(0, memory.dirtyCount());
    }
    @Test void failedSaveKeepsDirtyDataForRetry() throws Exception {
        var db = spy(new SqliteStore(directory.resolve("keys.db"))); var memory = db.load(); UUID id = UUID.randomUUID();
        memory.set(id, KeyTier.EPIC, 4);
        doThrow(new SQLException("simulated disk failure")).doCallRealMethod().when(db).save(any());
        var saver = new DataSaver(db, memory, Logger.getAnonymousLogger());
        try {
            assertThrows(ExecutionException.class, () -> saver.save().get(5, TimeUnit.SECONDS)); assertEquals(1, memory.dirtyCount());
            saver.save().get(5, TimeUnit.SECONDS); assertEquals(0, memory.dirtyCount()); assertEquals(4, db.load().balance(id, KeyTier.EPIC));
        } finally { saver.close(); }
    }
    @Test void secondManualSaveIncludesChangesAfterFirstSnapshot() throws Exception {
        var db = spy(new SqliteStore(directory.resolve("keys.db"))); var memory = db.load(); UUID id = UUID.randomUUID();
        memory.set(id, KeyTier.BASIC, 1);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        doAnswer(invocation -> { started.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)); return invocation.callRealMethod(); }).when(db).save(any());
        var saver = new DataSaver(db, memory, Logger.getAnonymousLogger());
        try {
            var first = saver.save(); assertTrue(started.await(5, TimeUnit.SECONDS)); memory.set(id, KeyTier.BASIC, 2);
            var second = saver.save(); assertNotSame(first, second); finish.countDown(); second.get(5, TimeUnit.SECONDS);
            assertEquals(2, db.load().balance(id, KeyTier.BASIC));
        } finally { finish.countDown(); saver.close(); }
    }
}
