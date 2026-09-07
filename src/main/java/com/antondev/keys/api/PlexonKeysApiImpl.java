package com.antondev.keys.api;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.model.KeyTier;
import com.antondev.keys.service.KeyBalanceService;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** Internal implementation registered through Bukkit ServicesManager. */
public final class PlexonKeysApiImpl implements PlexonKeysAPI {
    private final PlexonKeys plugin;
    private final KeyBalanceService balances;

    public PlexonKeysApiImpl(PlexonKeys plugin, KeyBalanceService balances) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    @Override public long balance(UUID playerId, KeyTier tier) {
        requirePrimaryThread();
        return balances.balance(playerId, tier);
    }

    @Override public Map<KeyTier, Long> balances(UUID playerId) {
        requirePrimaryThread();
        return balances.balances(playerId);
    }

    @Override public long grant(UUID playerId, KeyTier tier, long amount, KeySource source) {
        requirePrimaryThread();
        return balances.grant(playerId, tier, amount, source);
    }

    @Override public long take(UUID playerId, KeyTier tier, long amount, KeySource source) {
        requirePrimaryThread();
        Objects.requireNonNull(source, "source");
        return balances.take(playerId, tier, amount);
    }

    @Override public Optional<ItemStack> keyTemplate(KeyTier tier) {
        requirePrimaryThread();
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        ItemStack template = plugin.settings().categories().get(keyTier).itemCopy();
        return Optional.ofNullable(template == null ? null : template.clone());
    }

    @Override public boolean isTierEnabled(KeyTier tier) {
        requirePrimaryThread();
        return plugin.settings().categories().get(Objects.requireNonNull(tier, "tier")).enabled();
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("PlexonKeysAPI 1.2.x must be called from the primary server thread");
        }
    }
}
