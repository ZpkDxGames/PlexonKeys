package com.antondev.keys.service;

import com.antondev.keys.api.*;
import com.antondev.keys.data.DataSaver;
import com.antondev.keys.data.MemoryStore;
import com.antondev.keys.data.MemoryStore.*;
import com.antondev.keys.model.KeyTier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * Per-player ordered critical-mutation coordinator. Memory mutation is cheap and synchronized; durability is
 * delegated to the one bounded DataSaver writer. No caller waits synchronously for SQLite.
 */
public final class KeyTransactionCoordinator implements AutoCloseable {
    private static final int MAX_PENDING = 256;

    public record Metrics(
            int pending,
            int highWaterMark,
            int activePlayers,
            long oldestPendingMillis,
            long lastCriticalCommitEpochMillis,
            long failedPersistence,
            long retries,
            boolean closed) {}

    private final MemoryStore memory;
    private final DataSaver saver;
    private final Object gate = new Object();
    private final Map<UUID, CompletableFuture<Void>> tails = new HashMap<>();
    private final Map<UUID, Long> pendingSince = new HashMap<>();
    private final Map<UUID, String> activeKinds = new HashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger highWater = new AtomicInteger();
    private final AtomicLong lastCommit = new AtomicLong();
    private final AtomicLong persistenceFailures = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private volatile boolean closed;

    public KeyTransactionCoordinator(MemoryStore memory, DataSaver saver) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.saver = Objects.requireNonNull(saver, "saver");
    }

    public CompletionStage<KeyConsumeResult> consume(
            UUID playerId, KeyTier tier, long amount, String transactionId) {
        return submit(playerId, "CONSUME", () -> {
            CriticalMutation mutation = memory.criticalConsume(playerId, tier, amount, transactionId);
            if (mutation.duplicate()) retries.incrementAndGet();
            KeyTransaction transaction = mutation.transaction();
            if (mutation.duplicate() && !memory.transactionDirty(transactionId)) {
                return CompletableFuture.completedFuture(consumeResult(transaction, true, false));
            }
            return saver.save().handle((saved, error) -> {
                if (error != null) {
                    persistenceFailures.incrementAndGet();
                    return consumeResult(transaction, mutation.duplicate(), true);
                }
                lastCommit.set(System.currentTimeMillis());
                return consumeResult(transaction, mutation.duplicate(), false);
            });
        });
    }

    public CompletionStage<KeyGrantResult> grant(
            UUID playerId, KeyTier tier, long amount, long cap, KeySource source, String transactionId) {
        Objects.requireNonNull(source, "source");
        return submit(playerId, "EXTERNAL_GRANT", () -> {
            CriticalMutation mutation = memory.criticalGrant(
                    playerId, tier, amount, cap, source.id(), transactionId);
            if (mutation.duplicate()) retries.incrementAndGet();
            KeyTransaction transaction = mutation.transaction();
            if (mutation.duplicate() && !memory.transactionDirty(transactionId)) {
                return CompletableFuture.completedFuture(grantResult(transaction, true, false));
            }
            return saver.save().handle((saved, error) -> {
                if (error != null) {
                    persistenceFailures.incrementAndGet();
                    return grantResult(transaction, mutation.duplicate(), true);
                }
                lastCommit.set(System.currentTimeMillis());
                return grantResult(transaction, mutation.duplicate(), false);
            });
        });
    }

    public CompletionStage<ClaimRecord> reserveClaim(
            UUID playerId, Map<KeyTier, Long> amounts, String deliveryData, String transactionId) {
        return submit(playerId, "CLAIM_RESERVE", () -> {
            ClaimRecord claim = memory.reserveClaim(playerId, amounts, deliveryData, transactionId);
            return saver.save().thenApply(saved -> {
                lastCommit.set(System.currentTimeMillis());
                return claim;
            });
        });
    }

    public CompletionStage<ClaimRecord> updateClaim(
            UUID playerId, String transactionId, ClaimState state,
            String beforeFingerprint, String afterFingerprint, String detail) {
        return submit(playerId, "CLAIM_" + state.name(), () -> {
            ClaimRecord claim = memory.updateClaim(
                    transactionId, state, beforeFingerprint, afterFingerprint, detail);
            return saver.save().thenApply(saved -> {
                lastCommit.set(System.currentTimeMillis());
                return claim;
            });
        });
    }

    public CompletionStage<ClaimRecord> refundClaim(UUID playerId, String transactionId, String detail) {
        return submit(playerId, "CLAIM_REFUND", () -> {
            ClaimRecord claim = memory.refundClaim(transactionId, detail);
            return saver.save().thenApply(saved -> {
                lastCommit.set(System.currentTimeMillis());
                return claim;
            });
        });
    }

    public Optional<KeyTransactionView> status(String transactionId) {
        return memory.transaction(transactionId).map(transaction -> {
            KeyTransactionView.State state;
            if (memory.transactionDirty(transactionId)) {
                state = KeyTransactionView.State.PERSISTENCE_UNCERTAIN;
            } else if (transaction.outcome() == TransactionOutcome.INSUFFICIENT) {
                state = KeyTransactionView.State.COMMITTED_INSUFFICIENT;
            } else {
                state = KeyTransactionView.State.COMMITTED_SUCCESS;
            }
            return new KeyTransactionView(
                    transaction.transactionId(),
                    transaction.kind() == TransactionKind.CONSUME
                            ? KeyTransactionView.Kind.CONSUME : KeyTransactionView.Kind.EXTERNAL_GRANT,
                    transaction.player(),
                    transaction.tier().id(),
                    transaction.requested(),
                    transaction.applied(),
                    transaction.source(),
                    state,
                    transaction.resultBalance(),
                    transaction.createdAtEpochMillis(),
                    state == KeyTransactionView.State.PERSISTENCE_UNCERTAIN ? "dirty/unconfirmed persistence" : "");
        });
    }

    public Metrics metrics() {
        long oldest = 0;
        synchronized (gate) {
            long now = System.currentTimeMillis();
            for (long since : pendingSince.values()) oldest = Math.max(oldest, Math.max(0L, now - since));
            return new Metrics(pending.get(), highWater.get(), activeKinds.size(), oldest,
                    lastCommit.get(), persistenceFailures.get(), retries.get(), closed);
        }
    }

    private <T> CompletionStage<T> submit(
            UUID playerId, String kind, Supplier<? extends CompletionStage<T>> action) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(action, "action");
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("PlexonKeys transaction coordinator is closed"));
        int queued = pending.incrementAndGet();
        if (queued > MAX_PENDING) {
            pending.decrementAndGet();
            return CompletableFuture.failedFuture(new RejectedExecutionException("PlexonKeys critical transaction queue is full"));
        }
        highWater.accumulateAndGet(queued, Math::max);

        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void> tail;
        synchronized (gate) {
            pendingSince.putIfAbsent(playerId, System.currentTimeMillis());
            CompletableFuture<Void> predecessor = tails.getOrDefault(playerId, CompletableFuture.completedFuture(null));
            tail = predecessor.handle((ignored, priorFailure) -> null)
                    .thenCompose(ignored -> {
                        synchronized (gate) { activeKinds.put(playerId, kind); }
                        CompletionStage<T> stage;
                        try {
                            stage = Objects.requireNonNull(action.get(), "transaction stage");
                        } catch (Throwable error) {
                            stage = CompletableFuture.failedFuture(error);
                        }
                        return stage.handle((value, error) -> {
                            if (error == null) result.complete(value);
                            else result.completeExceptionally(unwrap(error));
                            return (Void) null;
                        }).toCompletableFuture();
                    });
            tails.put(playerId, tail);
        }
        tail.whenComplete((ignored, error) -> {
            pending.decrementAndGet();
            synchronized (gate) {
                activeKinds.remove(playerId);
                if (tails.get(playerId) == tail) {
                    tails.remove(playerId);
                    pendingSince.remove(playerId);
                }
            }
        });
        return result;
    }

    private static KeyConsumeResult consumeResult(KeyTransaction transaction, boolean duplicate, boolean uncertain) {
        KeyConsumeResult.Status status;
        if (uncertain) status = KeyConsumeResult.Status.PERSISTENCE_UNCERTAIN;
        else if (duplicate) status = KeyConsumeResult.Status.DUPLICATE;
        else if (transaction.outcome() == TransactionOutcome.INSUFFICIENT) status = KeyConsumeResult.Status.INSUFFICIENT;
        else status = KeyConsumeResult.Status.SUCCESS;
        return new KeyConsumeResult(status, transaction.transactionId(), transaction.tier().id(),
                transaction.requested(), transaction.applied(), transaction.resultBalance());
    }

    private static KeyGrantResult grantResult(KeyTransaction transaction, boolean duplicate, boolean uncertain) {
        KeyGrantResult.Status status = uncertain ? KeyGrantResult.Status.PERSISTENCE_UNCERTAIN
                : duplicate ? KeyGrantResult.Status.DUPLICATE : KeyGrantResult.Status.SUCCESS;
        return new KeyGrantResult(status, transaction.transactionId(), transaction.tier().id(),
                transaction.requested(), transaction.applied(), transaction.resultBalance());
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException || error instanceof ExecutionException) {
            return error.getCause() == null ? error : error.getCause();
        }
        return error;
    }

    @Override public void close() { closed = true; }
}
