package com.antondev.keys.reward;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.config.Text;
import com.antondev.keys.event.PlexonKeyClaimedEvent;
import com.antondev.keys.model.KeyTier;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ClaimService {
    private final PlexonKeys plugin;
    private final Set<UUID> claiming = new HashSet<>();

    public ClaimService(PlexonKeys plugin) { this.plugin = plugin; }

    public void claim(Player player, KeyTier only, boolean one) {
        Text text = plugin.settings().text();
        if (!player.hasPermission("plexonkeys.use")) {
            text.send(player, "no-permission");
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!claiming.add(playerId)) return;

        try {
            Map<KeyTier, Long> balances = plugin.balances().balances(playerId);
            var amounts = new EnumMap<KeyTier, Long>(KeyTier.class);
            var templates = new EnumMap<KeyTier, ItemStack>(KeyTier.class);
            boolean any = false;
            for (KeyTier tier : KeyTier.values()) {
                if (only != null && tier != only) continue;
                long requested = Math.min(balances.getOrDefault(tier, 0L), one ? 1L : Long.MAX_VALUE);
                amounts.put(tier, requested);
                templates.put(tier, plugin.settings().categories().get(tier).itemCopy());
                any |= requested > 0;
            }
            if (!any) {
                text.send(player, "no-keys");
                return;
            }

            // One defensive baseline clone is retained for rollback. InventoryDelivery is copy-on-write,
            // so it no longer performs a second deep clone of every untouched storage slot.
            ItemStack[] before = Arrays.stream(player.getInventory().getStorageContents())
                    .map(item -> item == null ? null : item.clone())
                    .toArray(ItemStack[]::new);
            var plan = InventoryDelivery.plan(before, player.getInventory().getMaxStackSize(), templates, amounts);
            if (plan.total() == 0) {
                text.send(player, "inventory-full");
                return;
            }
            if (!plugin.balances().debitForClaim(playerId, plan.delivered())) return;

            // Physical inventory delivery is irreversible across a process crash. Commit the virtual debit
            // through the existing bounded database worker before the item can become visible to the player.
            try {
                plugin.persistCriticalState("physical key claim debit");
            } catch (RuntimeException persistenceError) {
                plugin.balances().silentRestore(playerId, plan.delivered());
                try {
                    plugin.persistCriticalState("physical key claim debit rollback");
                } catch (RuntimeException rollbackError) {
                    persistenceError.addSuppressed(rollbackError);
                }
                plugin.getLogger().log(Level.SEVERE,
                        "Could not durably reserve physical key claim for " + playerId + "; no key was delivered", persistenceError);
                text.send(player, "save-failed");
                return;
            }

            try {
                player.getInventory().setStorageContents(plan.contents());
            } catch (RuntimeException error) {
                // Delivery did not complete. Restore virtual state and make that compensation durable before
                // returning so a restart cannot strand the already-persisted claim debit.
                plugin.balances().silentRestore(playerId, plan.delivered());
                try {
                    plugin.persistCriticalState("physical key claim delivery rollback");
                } catch (RuntimeException persistenceError) {
                    error.addSuppressed(persistenceError);
                }
                try {
                    player.getInventory().setStorageContents(before);
                } catch (RuntimeException restore) {
                    error.addSuppressed(restore);
                }
                plugin.getLogger().log(Level.SEVERE, "Could not deliver keys to " + playerId, error);
                text.send(player, "inventory-full");
                return;
            }

            // The virtual debit was durably committed before physical inventory mutation. Preserve 1.2's
            // one-event-per-tier contract only after both sides of the local claim have completed.
            publishClaimEvents(player, plan.delivered());

            long remaining = plugin.balances().balances(playerId).values().stream().mapToLong(Long::longValue).sum();
            text.send(player, "claimed", Text.value("amount", plan.total()), Text.value("remaining", remaining));
            plugin.menus().refreshPlayer(player);
        } finally {
            claiming.remove(playerId);
        }
    }

    private void publishClaimEvents(Player player, Map<KeyTier, Long> delivered) {
        String transactionId = UUID.randomUUID().toString();
        for (KeyTier tier : RewardService.HIGHEST_FIRST) {
            long amount = delivered.getOrDefault(tier, 0L);
            if (amount <= 0) continue;
            String eventId = transactionId + ":" + tier.id();
            try {
                Bukkit.getPluginManager().callEvent(new PlexonKeyClaimedEvent(
                        player, tier, amount, "player-claim", eventId, transactionId));
            } catch (RuntimeException error) {
                // Delivery already committed. Listener failure must never trigger rollback or duplicate physical keys.
                plugin.getLogger().log(Level.WARNING,
                        "A PlexonKeyClaimedEvent listener failed after key delivery committed", error);
            }
        }
    }
}
