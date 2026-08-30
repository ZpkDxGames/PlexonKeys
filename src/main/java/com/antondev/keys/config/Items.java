package com.antondev.keys.config;

import java.util.*;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public final class Items {
    private Items() {}
    public static Material material(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) throw new IllegalArgumentException("Invalid item material: " + name);
        return material;
    }
    public static ItemStack fromConfig(ConfigurationSection config, String path, TagResolver... tags) {
        ItemStack item = new ItemStack(material(config.getString(path + ".material", "PAPER")));
        return decorate(item, config, path, tags);
    }
    public static ItemStack decorate(ItemStack source, ConfigurationSection config, String path, TagResolver... tags) {
        ItemStack item = source.clone(); item.setAmount(1);
        item.editMeta(meta -> {
            meta.displayName(Text.parse(config.getString(path + ".name", ""), tags).decoration(TextDecoration.ITALIC, false));
            meta.lore(config.getStringList(path + ".lore").stream().map(line -> Text.parse(line, tags).decoration(TextDecoration.ITALIC, false)).toList());
            if (config.contains(path + ".glow")) meta.setEnchantmentGlintOverride(config.getBoolean(path + ".glow"));
        });
        return item;
    }
    public static ItemStack key(ConfigurationSection config, String path) {
        String mode = config.getString(path + ".mode", "CONFIG");
        if (mode.equalsIgnoreCase("CAPTURED")) {
            String data = config.getString(path + ".base64", "");
            if (data.isBlank() || data.length() > 2_000_000) throw new IllegalArgumentException(path + ": capture a valid item using /keysadmin setitem");
            try {
                ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
                if (item == null || item.getType().isAir()) throw new IllegalArgumentException("empty captured item");
                // One virtual key is one physical item. Never alter any metadata/data components on delivery.
                item.setAmount(1);
                return item;
            } catch (Exception error) { throw new IllegalArgumentException(path + ": invalid captured item data", error); }
        }
        if (!mode.equalsIgnoreCase("CONFIG")) throw new IllegalArgumentException(path + ".mode must be CONFIG or CAPTURED");
        return fromConfig(config, path);
    }
    public static String capture(ItemStack held) {
        if (held == null || held.getType().isAir()) throw new IllegalArgumentException("Hold a key item in your main hand first");
        ItemStack copy = held.clone(); copy.setAmount(1);
        String encoded = Base64.getEncoder().encodeToString(copy.serializeAsBytes());
        if (encoded.length() > 2_000_000) throw new IllegalArgumentException("This item is too large to use as a key template");
        return encoded;
    }
}
