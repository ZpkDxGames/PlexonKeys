package com.antondev.keys.api;

import com.antondev.keys.model.KeyTier;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * Stable Bukkit service API for PlexonKeys.
 *
 * <p>All methods must be invoked on the primary server thread. Returned maps are immutable and returned
 * ItemStacks are defensive copies. The 1.2/1.3 method surface is preserved for PlexonCrates compatibility.</p>
 */
public interface PlexonKeysAPI {
    long balance(UUID playerId, KeyTier tier);

    Map<KeyTier, Long> balances(UUID playerId);

    long grant(UUID playerId, KeyTier tier, long amount, KeySource source);

    long take(UUID playerId, KeyTier tier, long amount, KeySource source);

    Optional<ItemStack> keyTemplate(KeyTier tier);

    boolean isTierEnabled(KeyTier tier);

    /** Immutable definitions keyed by the authoritative stable ID. */
    Map<String, KeyDefinitionView> keyDefinitions();

    Optional<KeyDefinitionView> resolveKeyDefinition(String keyId);

    /**
     * Idempotent exact-once virtual-key consumption. A transaction ID is retained in a bounded in-memory
     * replay guard for this process lifetime; duplicate requests never debit twice.
     */
    KeyConsumeResult consumeKey(UUID playerId, String keyId, long amount, String transactionId);

    /** Resolve a physical item through exact Bukkit metadata/components equality, never through display text. */
    Optional<String> identifyPhysicalKey(ItemStack item);
}
