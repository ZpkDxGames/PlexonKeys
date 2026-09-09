package com.antondev.keys.reward;

import com.antondev.keys.model.KeyTier;
import java.util.*;
import org.bukkit.inventory.ItemStack;

/** Plan stack-based delivery in O(categories * inventory slots), independent of virtual balance size. */
public final class InventoryDelivery {
    public record Plan(ItemStack[] contents, Map<KeyTier, Long> delivered) {
        public long total() { return delivered.values().stream().mapToLong(Long::longValue).sum(); }
    }

    private InventoryDelivery() {}

    /**
     * Copy-on-write inventory planning. The input array and every input ItemStack remain untouched; only
     * stacks that actually receive keys are cloned. Untouched slots are safely shared with the plan.
     */
    public static Plan plan(
            ItemStack[] contents,
            int inventoryMax,
            Map<KeyTier, ItemStack> templates,
            Map<KeyTier, Long> requested) {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(requested, "requested");
        if (inventoryMax < 1) throw new IllegalArgumentException("Invalid inventory stack size");

        ItemStack[] working = Arrays.copyOf(contents, contents.length);
        boolean[] copied = new boolean[working.length];
        var delivered = new EnumMap<KeyTier, Long>(KeyTier.class);

        for (KeyTier tier : RewardService.HIGHEST_FIRST) {
            long remaining = requested.getOrDefault(tier, 0L);
            long initial = remaining;
            ItemStack template = templates.get(tier);
            if (remaining <= 0 || template == null || template.getType().isAir()) continue;
            int max = Math.max(1, Math.min(inventoryMax, template.getMaxStackSize()));

            for (int index = 0; index < working.length && remaining > 0; index++) {
                ItemStack slot = working[index];
                if (slot == null || slot.getType().isAir() || !slot.isSimilar(template)) continue;
                int room = Math.max(0, max - slot.getAmount());
                if (room == 0) continue;
                int add = (int) Math.min(remaining, room);
                if (!copied[index]) {
                    slot = slot.clone();
                    working[index] = slot;
                    copied[index] = true;
                }
                slot.setAmount(slot.getAmount() + add);
                remaining -= add;
            }

            for (int index = 0; index < working.length && remaining > 0; index++) {
                ItemStack slot = working[index];
                if (slot != null && !slot.getType().isAir()) continue;
                int add = (int) Math.min(remaining, max);
                ItemStack created = template.clone();
                created.setAmount(add);
                working[index] = created;
                copied[index] = true;
                remaining -= add;
            }

            if (remaining < initial) delivered.put(tier, initial - remaining);
        }
        return new Plan(working, Map.copyOf(delivered));
    }
}
