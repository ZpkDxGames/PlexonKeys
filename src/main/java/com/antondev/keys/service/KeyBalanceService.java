package com.antondev.keys.service;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.api.KeySource;
import com.antondev.keys.data.MemoryStore;
import com.antondev.keys.event.PlexonKeyEarnedEvent;
import com.antondev.keys.model.KeyTier;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Central domain service for every virtual-key balance mutation. */
public final class KeyBalanceService {
    private final PlexonKeys plugin;
    private final MemoryStore data;

    public KeyBalanceService(PlexonKeys plugin, MemoryStore data) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.data = Objects.requireNonNull(data, "data");
    }

    public long balance(UUID playerId, KeyTier tier) {
        return data.balance(requirePlayerId(playerId), Objects.requireNonNull(tier, "tier"));
    }

    public Map<KeyTier, Long> balances(UUID playerId) {
        UUID id = requirePlayerId(playerId);
        EnumMap<KeyTier, Long> result = new EnumMap<>(KeyTier.class);
        for (KeyTier tier : KeyTier.values()) result.put(tier, data.balance(id, tier));
        return Map.copyOf(result);
    }

    /** Gameplay acquisition path. Fires exactly one earned event when a positive credit commits. */
    public long grant(Player player, KeyTier tier, long amount, String source) {
        Objects.requireNonNull(player, "player");
        data.remember(player.getUniqueId(), player.getName());
        return grant(player.getUniqueId(), tier, amount, source, player, true);
    }

    /** Public/API acquisition path. Offline balances are supported; Bukkit earned events require an online Player. */
    public long grant(UUID playerId, KeyTier tier, long amount, KeySource source) {
        UUID id = requirePlayerId(playerId);
        Player online = Bukkit.getPlayer(id);
        if (online != null) data.remember(id, online.getName());
        return grant(id, tier, amount, Objects.requireNonNull(source, "source").id(), online, true);
    }

    /** Administrative acquisition path. */
    public long grantAdmin(UUID playerId, KeyTier tier, long amount) {
        UUID id = requirePlayerId(playerId);
        Player online = Bukkit.getPlayer(id);
        if (online != null) data.remember(id, online.getName());
        return grant(id, tier, amount, KeySource.ADMIN.id(), online, true);
    }

    private long grant(UUID playerId, KeyTier tier, long amount, String source, Player eventPlayer, boolean emit) {
        validatePositive(amount);
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
        long credited = data.credit(playerId, keyTier, amount, plugin.settings().cap());
        if (credited > 0 && emit && eventPlayer != null) publishEarned(eventPlayer, keyTier, credited, source);
        return credited;
    }

    /** Removal path used by API/admin operations. Never emits an earned event. */
    public long take(UUID playerId, KeyTier tier, long amount) {
        validatePositive(amount);
        UUID id = requirePlayerId(playerId);
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        long current = data.balance(id, keyTier);
        long removed = Math.min(current, amount);
        if (removed > 0) data.set(id, keyTier, current - removed);
        return removed;
    }

    /** Administrative correction path. Never emits an earned event. */
    public void correct(UUID playerId, KeyTier tier, long amount) {
        if (amount < 0 || amount > plugin.settings().cap()) {
            throw new IllegalArgumentException("Amount must be between 0 and " + plugin.settings().cap());
        }
        data.set(requirePlayerId(playerId), Objects.requireNonNull(tier, "tier"), amount);
    }

    /** Atomic pre-delivery claim debit. Never emits an earned event. */
    public boolean debitForClaim(UUID playerId, Map<KeyTier, Long> amounts) {
        return data.debit(requirePlayerId(playerId), Objects.requireNonNull(amounts, "amounts"));
    }

    /** Silent recovery used only when physical claim delivery failed after debit. */
    public void silentRestore(UUID playerId, Map<KeyTier, Long> amounts) {
        UUID id = requirePlayerId(playerId);
        Objects.requireNonNull(amounts, "amounts").forEach((tier, amount) -> {
            if (amount != null && amount > 0) data.credit(id, tier, amount, MemoryStore.HARD_LIMIT);
        });
    }

    private void publishEarned(Player player, KeyTier tier, long amount, String source) {
        PlexonKeyEarnedEvent event = new PlexonKeyEarnedEvent(
                player, tier, amount, source, UUID.randomUUID().toString());
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException error) {
            // State is already committed. Listener failure must never undo or duplicate the acquisition.
            plugin.getLogger().log(Level.WARNING, "A PlexonKeyEarnedEvent listener failed after key credit committed", error);
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
