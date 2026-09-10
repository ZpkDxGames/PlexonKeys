package com.antondev.keys.service;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.api.KeyConsumeResult;
import com.antondev.keys.api.KeySource;
import com.antondev.keys.data.MemoryStore;
import com.antondev.keys.event.PlexonKeyConsumedEvent;
import com.antondev.keys.event.PlexonKeyEarnedEvent;
import com.antondev.keys.model.KeyTier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Central domain service for every virtual-key balance mutation. */
public final class KeyBalanceService {
    public record GrantResult(long credited, long balance) {}
    private record ConsumeRecord(UUID playerId, KeyTier tier, long amount, KeyConsumeResult result) {}

    private static final int MAX_CONSUME_REPLAY_GUARD = 4096;

    private final PlexonKeys plugin;
    private final MemoryStore data;
    private final LinkedHashMap<String, ConsumeRecord> recentConsumes = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ConsumeRecord> eldest) {
            return size() > MAX_CONSUME_REPLAY_GUARD;
        }
    };

    public KeyBalanceService(PlexonKeys plugin, MemoryStore data) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.data = Objects.requireNonNull(data, "data");
    }

    public long balance(UUID playerId, KeyTier tier) {
        return data.balance(requirePlayerId(playerId), Objects.requireNonNull(tier, "tier"));
    }

    public Map<KeyTier, Long> balances(UUID playerId) {
        return data.balances(requirePlayerId(playerId));
    }

    /** Gameplay acquisition path. Fires exactly one earned event when a positive credit commits. */
    public long grant(Player player, KeyTier tier, long amount, String source) {
        return grantResult(player, tier, amount, source).credited();
    }

    /** Gameplay hot path with the committed post-mutation balance returned without a second store read. */
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

    /** Public/API acquisition path. Offline balances are supported; Bukkit earned events require an online Player. */
    public long grant(UUID playerId, KeyTier tier, long amount, KeySource source) {
        UUID id = requirePlayerId(playerId);
        Player online = Bukkit.getPlayer(id);
        if (online != null) return grant(online, tier, amount, Objects.requireNonNull(source, "source").id());
        validatePositive(amount);
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        return data.credit(id, keyTier, amount, plugin.settings().cap());
    }

    /** Administrative acquisition path. */
    public long grantAdmin(UUID playerId, KeyTier tier, long amount) {
        UUID id = requirePlayerId(playerId);
        Player online = Bukkit.getPlayer(id);
        if (online != null) return grant(online, tier, amount, KeySource.ADMIN.id());
        validatePositive(amount);
        return data.credit(id, Objects.requireNonNull(tier, "tier"), amount, plugin.settings().cap());
    }

    /** Removal path used by API/admin operations. Never emits an earned event. */
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
     * Exact-once virtual consume boundary for crate/opening integrations. The bounded replay guard is process-local;
     * callers that require crash-spanning reservation semantics must keep their own durable transaction journal.
     */
    public synchronized KeyConsumeResult consume(UUID playerId, KeyTier tier, long amount, String transactionId) {
        UUID id = requirePlayerId(playerId);
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        validatePositive(amount);
        if (transactionId == null || transactionId.isBlank() || transactionId.length() > 128) {
            throw new IllegalArgumentException("transactionId must contain 1-128 characters");
        }

        ConsumeRecord previous = recentConsumes.get(transactionId);
        if (previous != null) {
            if (!previous.playerId().equals(id) || previous.tier() != keyTier || previous.amount() != amount) {
                throw new IllegalArgumentException("transactionId was already used for a different consume request");
            }
            KeyConsumeResult prior = previous.result();
            return new KeyConsumeResult(KeyConsumeResult.Status.DUPLICATE, transactionId, keyTier.id(), amount,
                    0, data.balance(id, keyTier));
        }

        long before = data.balance(id, keyTier);
        if (before < amount || !data.debit(id, Map.of(keyTier, amount))) {
            KeyConsumeResult result = new KeyConsumeResult(KeyConsumeResult.Status.INSUFFICIENT, transactionId,
                    keyTier.id(), amount, 0, data.balance(id, keyTier));
            recentConsumes.put(transactionId, new ConsumeRecord(id, keyTier, amount, result));
            return result;
        }

        long current = data.balance(id, keyTier);
        KeyConsumeResult result = new KeyConsumeResult(KeyConsumeResult.Status.SUCCESS, transactionId,
                keyTier.id(), amount, amount, current);
        recentConsumes.put(transactionId, new ConsumeRecord(id, keyTier, amount, result));
        publishConsumed(id, keyTier, amount, transactionId);
        return result;
    }

    public synchronized int consumeReplayGuardSize() { return recentConsumes.size(); }

    /** Atomic pre-delivery claim debit. Never emits an earned event. */
    public boolean debitForClaim(UUID playerId, Map<KeyTier, Long> amounts) {
        return data.debit(requirePlayerId(playerId), Objects.requireNonNull(amounts, "amounts"));
    }

    /** Silent recovery used only when physical claim delivery failed after debit. */
    public void silentRestore(UUID playerId, Map<KeyTier, Long> amounts) {
        data.restore(requirePlayerId(playerId), Objects.requireNonNull(amounts, "amounts"));
    }

    private void publishEarned(Player player, KeyTier tier, long amount, String source) {
        PlexonKeyEarnedEvent event = new PlexonKeyEarnedEvent(
                player, tier, amount, source, UUID.randomUUID().toString());
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "A PlexonKeyEarnedEvent listener failed after key credit committed", error);
        }
    }

    private void publishConsumed(UUID playerId, KeyTier tier, long amount, String transactionId) {
        try {
            Bukkit.getPluginManager().callEvent(new PlexonKeyConsumedEvent(playerId, tier, amount, transactionId));
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "A PlexonKeyConsumedEvent listener failed after key debit committed", error);
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
