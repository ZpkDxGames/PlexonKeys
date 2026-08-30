package com.antondev.keys.data;

import java.util.concurrent.*;
import java.util.logging.*;

public final class DataSaver implements AutoCloseable {
    public record Result(int players, int blocks, long milliseconds) {}
    private final SqliteStore database;
    private final MemoryStore memory;
    private final Logger logger;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PlexonKeys-database"); thread.setDaemon(false); return thread;
    });
    private CompletableFuture<Result> pending;
    private long requestedRevision = -1;
    public DataSaver(SqliteStore database, MemoryStore memory, Logger logger) {
        this.database = database; this.memory = memory; this.logger = logger;
    }
    public synchronized CompletableFuture<Result> save() {
        long current = memory.revision();
        if (pending != null && !pending.isDone() && requestedRevision >= current) return pending;
        requestedRevision = current;
        pending = enqueue();
        return pending;
    }
    private CompletableFuture<Result> enqueue() {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            var snapshot = memory.snapshot();
            try {
                database.save(snapshot);
                memory.acknowledge(snapshot);
                return new Result(snapshot.accounts().size(), snapshot.blocks().size(), (System.nanoTime() - start) / 1_000_000);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "PlexonKeys data save failed. Changes remain in memory for the next save.", e);
                throw new CompletionException(e);
            }
        }, worker);
    }
    @Override public void close() {
        // Always queue a FINAL snapshot after any in-flight save; never reuse an older pending snapshot here.
        try {
            var result = enqueue().join();
            logger.info("Saved " + result.players() + " player records and " + result.blocks() + " block changes in " + result.milliseconds() + "ms.");
        } finally { worker.shutdown(); }
    }
}
