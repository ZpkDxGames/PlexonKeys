package com.antondev.keys.reward;

import com.antondev.keys.model.KeyTier;
import java.util.*;
import org.bukkit.inventory.ItemStack;

/** Plan on copies, in O(categories * inventory slots), irrespective of the number of virtual keys. */
public final class InventoryDelivery {
    public record Plan(ItemStack[] contents, Map<KeyTier, Long> delivered) {
        public long total() { return delivered.values().stream().mapToLong(Long::longValue).sum(); }
    }
    private InventoryDelivery() {}
    public static Plan plan(ItemStack[] contents, int inventoryMax, Map<KeyTier, ItemStack> templates, Map<KeyTier, Long> requested) {
        if (inventoryMax < 1) throw new IllegalArgumentException("Invalid inventory stack size");
        ItemStack[] working = Arrays.stream(contents).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        var delivered = new EnumMap<KeyTier, Long>(KeyTier.class);
        for (KeyTier tier : RewardService.HIGHEST_FIRST) {
            long remaining = requested.getOrDefault(tier, 0L), initial = remaining;
            ItemStack template = templates.get(tier);
            if (remaining <= 0 || template == null || template.getType().isAir()) continue;
            int max = Math.max(1, Math.min(inventoryMax, template.getMaxStackSize()));
            for (ItemStack slot : working) {
                if (remaining <= 0) break;
                if (slot == null || slot.getType().isAir() || !slot.isSimilar(template)) continue;
                int add = (int) Math.min(remaining, Math.max(0, max - slot.getAmount()));
                slot.setAmount(slot.getAmount() + add); remaining -= add;
            }
            for (int index = 0; index < working.length && remaining > 0; index++) {
                ItemStack slot = working[index];
                if (slot != null && !slot.getType().isAir()) continue;
                int add = (int) Math.min(remaining, max);
                working[index] = template.clone(); working[index].setAmount(add); remaining -= add;
            }
            if (remaining < initial) delivered.put(tier, initial - remaining);
        }
        return new Plan(working, Map.copyOf(delivered));
    }
}
