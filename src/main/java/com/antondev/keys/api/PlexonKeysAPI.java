package com.antondev.keys.api;

import com.antondev.keys.model.KeyTier;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * Stable Bukkit service API for PlexonKeys.
 *
 * <p>All methods must be invoked on the primary server thread in 1.2.x.
 * Returned maps are immutable and returned ItemStacks are defensive copies.</p>
 */
public interface PlexonKeysAPI {
    long balance(UUID playerId, KeyTier tier);

    Map<KeyTier, Long> balances(UUID playerId);

    long grant(UUID playerId, KeyTier tier, long amount, KeySource source);

    long take(UUID playerId, KeyTier tier, long amount, KeySource source);

    Optional<ItemStack> keyTemplate(KeyTier tier);

    boolean isTierEnabled(KeyTier tier);
}
