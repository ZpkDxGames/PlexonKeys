package com.antondev.keys.service;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.api.*;
import com.antondev.keys.data.MemoryStore;
import com.antondev.keys.event.*;
import com.antondev.keys.model.KeyTier;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Central domain service for virtual-key balance mutation and durable external transactions. */
public final class KeyBalanceService {
    public record GrantResult(long credited, long balance) {}

    private final PlexonKeys plugin;
    private final MemoryStore data;
    private final KeyTransactionCoordinator transactions;

    public KeyBalanceService(PlexonKeys plugin, MemoryStore data, KeyTransactionCoordinator transactions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.data = Objects.requireNonNull(data, "data");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public long balance(UUID playerId, KeyTier tier) {
        return data.balance(requirePlayerId(playerId), Objects.requireNonNull(tier, "tier"));
    }

    public Map<KeyTier, Long> balances(UUID playerId) {
        return data.balances(requirePlayerId(playerId));
    }

    /** Gameplay acquisition path. Fires exactly one earned event when a positive credit commits to memory. */
    public long grant(Player player, KeyTier tier, long amount, String source) {
        return grantResult(player, tier, amount, source).credited();
    }

    public GrantResult grantResult(Player player, KeyTier tier, long amount, String source) {
        Objects.requireNonNull(player, "player");
        validatePositive(amount);
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
        MemoryStore.BalanceMutation mutation = data.credit(
                player.getUniqueId(), player.getName(), keyTier, amount, plugin.settings().cap());
        if (mutation.delta() > 0) publishEarned(player, keyTier, mutation.delta(), source);
        return new GrantResult(mutation.delta(), mutation.current());
    }

    /**
     * Legacy ordinary grant. This is retained for source compatibility; integrations requiring a durable
     * idempotent reward must use grantAsync.
     */
    public long grant(UUID playerId, KeyTier tier, long amount, KeySource source) {
        UUID id = requirePlayerId(playerId);
        Player online = Bukkit.getPlayer(id);
        if (online != null) return grant(online, tier, amount, Objects.requireNonNull(source, "source").id());
        validatePositive(amount);
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        return data.credit(id, keyTier, amount, plugin.settings().cap());
    }

    public CompletionStage<KeyGrantResult> grantAsync(
            UUID playerId, KeyTier tier, long amount, KeySource source, String transactionId) {
        UUID id = requirePlayerId(playerId);
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        KeySource keySource = Objects.requireNonNull(source, "source");
        validatePositive(amount);
        return transactions.grant(id, keyTier, amount, plugin.settings().cap(), keySource, transactionId)
                .thenCompose(result -> {
                    if (result.status() != KeyGrantResult.Status.SUCCESS) {
                        return CompletableFuture.completedFuture(result);
                    }
                    return onMainThread(() -> {
                        publishGranted(id, keyTier, result.credited(), keySource, transactionId, result.balance());
                        Player online = Bukkit.getPlayer(id);
                        if (online != null) plugin.menus().refreshPlayer(online);
                        return result;
                    });
                });
    }

    /** Administrative acquisition path. */
    public long grantAdmin(UUID playerId, KeyTier tier, long amount) {
        UUID id = requirePlayerId(playerId);
        Player online = Bukkit.getPlayer(id);
        if (online != null) return grant(online, tier, amount, KeySource.ADMIN.id());
        validatePositive(amount);
        return data.credit(id, Objects.requireNonNull(tier, "tier"), amount, plugin.settings().cap());
    }

    /** Removal path used by legacy API/admin operations. Never emits an earned event. */
    public long take(UUID playerId, KeyTier tier, long amount) {
        validatePositive(amount);
        MemoryStore.BalanceMutation mutation = data.take(
                requirePlayerId(playerId), Objects.requireNonNull(tier, "tier"), amount);
        return -mutation.delta();
    }

    /** Administrative correction path. Never emits an earned event. */
    public void correct(UUID playerId, KeyTier tier, long amount) {
        if (amount < 0 || amount > plugin.settings().cap()) {
            throw new IllegalArgumentException("Amount must be between 0 and " + plugin.settings().cap());
        }
        data.set(requirePlayerId(playerId), Objects.requireNonNull(tier, "tier"), amount);
    }

    /**
     * Synchronous durable consume is intentionally unavailable in 2.1. Blocking the Paper primary thread
     * would violate the stable persistence contract.
     */
    @Deprecated
    public KeyConsumeResult consume(UUID playerId, KeyTier tier, long amount, String transactionId) {
        throw new IllegalStateException("Synchronous durable consume is disabled; use consumeAsync");
    }

    public CompletionStage<KeyConsumeResult> consumeAsync(
            UUID playerId, KeyTier tier, long amount, String transactionId) {
        UUID id = requirePlayerId(playerId);
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        validatePositive(amount);
        return transactions.consume(id, keyTier, amount, transactionId)
                .thenCompose(result -> {
                    if (result.status() != KeyConsumeResult.Status.SUCCESS) {
                        return CompletableFuture.completedFuture(result);
                    }
                    return onMainThread(() -> {
                        publishConsumed(id, keyTier, result.consumed(), transactionId);
                        Player online = Bukkit.getPlayer(id);
                        if (online != null) plugin.menus().refreshPlayer(online);
                        return result;
                    });
                });
    }

    public Optional<KeyTransactionView> transactionStatus(String transactionId) {
        return transactions.status(transactionId);
    }

    public int consumeReplayGuardSize() { return data.consumeReplayCount(); }

    /** Legacy claim helpers retained for migration tests; 2.1 ClaimService uses the durable journal. */
    public boolean debitForClaim(UUID playerId, Map<KeyTier, Long> amounts) {
        return data.debit(requirePlayerId(playerId), Objects.requireNonNull(amounts, "amounts"));
    }

    public void silentRestore(UUID playerId, Map<KeyTier, Long> amounts) {
        data.restore(requirePlayerId(playerId), Objects.requireNonNull(amounts, "amounts"));
    }

    private <T> CompletionStage<T> onMainThread(Supplier<T> callback) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(callback.get());
            } catch (Throwable error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    future.complete(callback.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            // Persistence already committed. A disabled plugin cannot safely publish Bukkit callbacks.
            plugin.getLogger().log(Level.WARNING,
                    "A durable PlexonKeys transaction committed but its main-thread callback could not be scheduled",
                    schedulingFailure);
            try {
                future.complete(callback.get());
            } catch (Throwable callbackFailure) {
                schedulingFailure.addSuppressed(callbackFailure);
                future.completeExceptionally(schedulingFailure);
            }
        }
        return future;
    }

    private void publishEarned(Player player, KeyTier tier, long amount, String source) {
        PlexonKeyEarnedEvent event = new PlexonKeyEarnedEvent(
                player, tier, amount, source, UUID.randomUUID().toString());
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING,
                    "A PlexonKeyEarnedEvent listener failed after key credit committed", error);
        }
    }

    private void publishConsumed(UUID playerId, KeyTier tier, long amount, String transactionId) {
        try {
            Bukkit.getPluginManager().callEvent(new PlexonKeyConsumedEvent(playerId, tier, amount, transactionId));
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING,
                    "A PlexonKeyConsumedEvent listener failed after key debit committed", error);
        }
    }

    private void publishGranted(UUID playerId, KeyTier tier, long amount, KeySource source,
                                String transactionId, long resultingBalance) {
        try {
            Bukkit.getPluginManager().callEvent(new PlexonKeyGrantedEvent(
                    playerId, tier, amount, source, transactionId, resultingBalance));
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING,
                    "A PlexonKeyGrantedEvent listener failed after durable key grant committed", error);
        }
    }

    private static UUID requirePlayerId(UUID playerId) {
        return Objects.requireNonNull(playerId, "playerId");
    }

    private static void validatePositive(long amount) {
        if (amount <= 0 || amount > MemoryStore.HARD_LIMIT) {
            throw new IllegalArgumentException("Amount must be between 1 and " + MemoryStore.HARD_LIMIT);
        }
    }
}
