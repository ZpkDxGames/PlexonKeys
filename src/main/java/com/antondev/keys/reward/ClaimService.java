package com.antondev.keys.reward;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.config.Text;
import com.antondev.keys.model.KeyTier;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ClaimService {
    private final PlexonKeys plugin;
    private final Set<UUID> claiming = new HashSet<>();
    public ClaimService(PlexonKeys plugin) { this.plugin = plugin; }
    public void claim(Player player, KeyTier only, boolean one) {
        Text text = plugin.settings().text();
        if (!player.hasPermission("plexonkeys.use")) { text.send(player, "no-permission"); return; }
        if (!claiming.add(player.getUniqueId())) return;
        try {
            var amounts = new EnumMap<KeyTier, Long>(KeyTier.class);
            var templates = new EnumMap<KeyTier, ItemStack>(KeyTier.class);
            for (KeyTier tier : KeyTier.values()) if (only == null || tier == only) {
                amounts.put(tier, Math.min(plugin.data().balance(player.getUniqueId(), tier), one ? 1 : Long.MAX_VALUE));
                templates.put(tier, plugin.settings().categories().get(tier).itemCopy());
            }
            if (amounts.values().stream().allMatch(amount -> amount == 0)) { text.send(player, "no-keys"); return; }
            ItemStack[] before = Arrays.stream(player.getInventory().getStorageContents()).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
            var plan = InventoryDelivery.plan(before, player.getInventory().getMaxStackSize(), templates, amounts);
            if (plan.total() == 0) { text.send(player, "inventory-full"); return; }
            if (!plugin.data().debit(player.getUniqueId(), plan.delivered())) return;
            try { player.getInventory().setStorageContents(plan.contents()); }
            catch (RuntimeException error) {
                // Restore both sides if an inventory implementation rejects the write.
                plan.delivered().forEach((tier, count) -> plugin.data().credit(player.getUniqueId(), tier, count, com.antondev.keys.data.MemoryStore.HARD_LIMIT));
                try { player.getInventory().setStorageContents(before); } catch (RuntimeException restore) { error.addSuppressed(restore); }
                plugin.getLogger().log(Level.SEVERE, "Could not deliver keys to " + player.getUniqueId(), error);
                text.send(player, "inventory-full"); return;
            }
            long remaining = Arrays.stream(KeyTier.values()).mapToLong(tier -> plugin.data().balance(player.getUniqueId(), tier)).sum();
            text.send(player, "claimed", Text.value("amount", plan.total()), Text.value("remaining", remaining));
            plugin.menus().refreshPlayer(player);
        } finally { claiming.remove(player.getUniqueId()); }
    }
}
