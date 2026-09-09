package com.antondev.keys.data;

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.logging.*;

/** One bounded database worker with revision-aware save coalescing. */
public final class DataSaver implements AutoCloseable {
    public record Result(int players, int blocks, long milliseconds) {}

    public record Metrics(
            boolean inFlight,
            boolean saveRequested,
            long requestedRevision,
            long acknowledgedRevision,
            long lastSnapshotMilliseconds,
            long lastDatabaseMilliseconds,
            long lastSaveMilliseconds,
            long p95SaveMilliseconds,
            int lastPlayers,
            int lastBlocks,
            long maximumDirtyCount,
            int maximumSnapshotRecords,
            long lastSuccessfulSaveEpochMillis,
            long saveFailures) {}

    private final SqliteStore database;
    private final MemoryStore memory;
    private final Logger logger;
    private final ThreadPoolExecutor worker;
    private final long[] recentSaveDurations = new long[32];
    private CompletableFuture<Result> pending;
    private long requestedRevision = -1;
    private volatile long activeTarget = -1;
    private volatile long acknowledgedRevision = -1;
    private volatile long lastSnapshotMilliseconds;
    private volatile long lastDatabaseMilliseconds;
    private volatile long lastSaveMilliseconds;
    private volatile int lastPlayers;
    private volatile int lastBlocks;
    private volatile long saveFailures;
    private volatile int maximumSnapshotRecords = 4096;
    private volatile int shutdownTimeoutSeconds = 15;
    private volatile long lastSuccessfulSaveEpochMillis;
    private long maximumDirtyCount;
    private int recentSaveCount;
    private int recentSaveCursor;
    private boolean closing;

    public DataSaver(SqliteStore database, MemoryStore memory, Logger logger) {
        this.database = database;
        this.memory = memory;
        this.logger = logger;
        worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(runnable, "PlexonKeys-database");
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void configure(int maximumSnapshotRecords, int shutdownTimeoutSeconds) {
        if (maximumSnapshotRecords < 64 || maximumSnapshotRecords > 1_000_000) {
            throw new IllegalArgumentException("maximumSnapshotRecords must be between 64 and 1000000");
        }
        if (shutdownTimeoutSeconds < 1 || shutdownTimeoutSeconds > 300) {
            throw new IllegalArgumentException("shutdownTimeoutSeconds must be between 1 and 300");
        }
        this.maximumSnapshotRecords = maximumSnapshotRecords;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    /**
     * Request persistence through the current memory revision. Repeated requests extend the same in-flight
     * worker pass instead of adding executor tasks, so queue depth cannot grow with checkpoint frequency.
     * Each caller still receives its own completion handle to preserve the 1.2 manual-save contract.
     */
    public synchronized CompletableFuture<Result> save() {
        if (closing) return CompletableFuture.failedFuture(new IllegalStateException("DataSaver is closing"));
        int dirty = memory.dirtyCount();
        maximumDirtyCount = Math.max(maximumDirtyCount, dirty);
        if (dirty == 0 && (pending == null || pending.isDone())) {
            return CompletableFuture.completedFuture(new Result(0, 0, 0));
        }
        long current = memory.revision();
        if (pending != null && !pending.isDone()) {
            if (current > requestedRevision) requestedRevision = current;
            return pending.thenApply(result -> result);
        }
        requestedRevision = current;
        try {
            pending = CompletableFuture.supplyAsync(this::drainRequestedRevisions, worker);
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(error);
        }
        return pending;
    }

    private Result drainRequestedRevisions() {
        long totalStart = System.nanoTime();
        int players = 0;
        int blocks = 0;
        try {
            while (true) {
                long target;
                synchronized (this) {
                    target = requestedRevision;
                    activeTarget = target;
                }

                long snapshotStart = System.nanoTime();
                MemoryStore.Snapshot snapshot = memory.snapshot(maximumSnapshotRecords, target);
                lastSnapshotMilliseconds = nanosToMillis(System.nanoTime() - snapshotStart);

                if (snapshot.empty()) {
                    synchronized (this) {
                        acknowledgedRevision = Math.max(acknowledgedRevision, target);
                        if (requestedRevision <= target) break;
                    }
                    continue;
                }

                long databaseStart = System.nanoTime();
                try {
                    database.save(snapshot);
                } catch (Exception error) {
                    saveFailures++;
                    logger.log(Level.SEVERE, "PlexonKeys data save failed. Changes remain in memory for the next save.", error);
                    throw new CompletionException(error);
                } finally {
                    lastDatabaseMilliseconds = nanosToMillis(System.nanoTime() - databaseStart);
                }
                memory.acknowledge(snapshot);
                players += snapshot.accounts().size();
                blocks += snapshot.blocks().size();
            }

            lastPlayers = players;
            lastBlocks = blocks;
            lastSaveMilliseconds = nanosToMillis(System.nanoTime() - totalStart);
            lastSuccessfulSaveEpochMillis = System.currentTimeMillis();
            recordSaveDuration(lastSaveMilliseconds);
            return new Result(players, blocks, lastSaveMilliseconds);
        } finally {
            activeTarget = -1;
        }
    }

    private synchronized void recordSaveDuration(long milliseconds) {
        recentSaveDurations[recentSaveCursor] = milliseconds;
        recentSaveCursor = (recentSaveCursor + 1) % recentSaveDurations.length;
        if (recentSaveCount < recentSaveDurations.length) recentSaveCount++;
    }

    public synchronized Metrics metrics() {
        boolean inFlight = pending != null && !pending.isDone();
        return new Metrics(
                inFlight,
                inFlight && requestedRevision > activeTarget,
                requestedRevision,
                acknowledgedRevision,
                lastSnapshotMilliseconds,
                lastDatabaseMilliseconds,
                lastSaveMilliseconds,
                p95SaveMilliseconds(),
                lastPlayers,
                lastBlocks,
                maximumDirtyCount,
                maximumSnapshotRecords,
                lastSuccessfulSaveEpochMillis,
                saveFailures);
    }

    private long p95SaveMilliseconds() {
        if (recentSaveCount == 0) return 0;
        long[] copy = Arrays.copyOf(recentSaveDurations, recentSaveCount);
        Arrays.sort(copy);
        int index = Math.max(0, (int) Math.ceil(copy.length * 0.95d) - 1);
        return copy[index];
    }

    private static long nanosToMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    @Override public void close() {
        CompletableFuture<Result> finalSave = save();
        int timeout = shutdownTimeoutSeconds;
        try {
            Result result = finalSave.get(timeout, TimeUnit.SECONDS);
            logger.info("Saved " + result.players() + " player records and " + result.blocks()
                    + " block changes in " + result.milliseconds() + "ms.");
        } catch (TimeoutException error) {
            throw new IllegalStateException("PlexonKeys final database save exceeded " + timeout + " seconds", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for PlexonKeys final database save", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("PlexonKeys final database save failed", error.getCause());
        } finally {
            synchronized (this) { closing = true; }
            worker.shutdown();
            try {
                if (!worker.awaitTermination(timeout, TimeUnit.SECONDS)) worker.shutdownNow();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                worker.shutdownNow();
            }
        }
    }
}
