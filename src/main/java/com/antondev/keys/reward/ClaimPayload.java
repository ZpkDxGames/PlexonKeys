package com.antondev.keys.reward;

import com.antondev.keys.model.KeyTier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.inventory.ItemStack;

/** Stable text payload for durable claim recovery. */
public final class ClaimPayload {
    private ClaimPayload() {}

    public static String encode(Map<KeyTier, Long> amounts, Map<KeyTier, ItemStack> templates) {
        StringBuilder out = new StringBuilder();
        for (KeyTier tier : KeyTier.values()) {
            long amount = amounts.getOrDefault(tier, 0L);
            if (amount <= 0) continue;
            ItemStack template = Objects.requireNonNull(templates.get(tier), "template " + tier);
            ItemStack one = template.clone();
            one.setAmount(1);
            String item = Base64.getEncoder().encodeToString(one.serializeAsBytes());
            if (out.length() > 0) out.append(';');
            out.append(tier.id()).append(',').append(amount).append(',').append(item);
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Map<KeyTier, Long> amounts(String payload) {
        EnumMap<KeyTier, Long> result = new EnumMap<>(KeyTier.class);
        for (String[] row : rows(payload)) result.put(KeyTier.parse(row[0]), Long.parseLong(row[1]));
        return Map.copyOf(result);
    }

    public static Map<KeyTier, ItemStack> templates(String payload) {
        EnumMap<KeyTier, ItemStack> result = new EnumMap<>(KeyTier.class);
        for (String[] row : rows(payload)) {
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(row[2]));
            item.setAmount(1);
            result.put(KeyTier.parse(row[0]), item);
        }
        return Map.copyOf(result);
    }

    private static List<String[]> rows(String payload) {
        if (payload == null || payload.isBlank()) return List.of();
        String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        if (decoded.isBlank()) return List.of();
        List<String[]> rows = new ArrayList<>();
        for (String row : decoded.split(";")) {
            String[] parts = row.split(",", 3);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid claim payload");
            rows.add(parts);
        }
        return rows;
    }
}
