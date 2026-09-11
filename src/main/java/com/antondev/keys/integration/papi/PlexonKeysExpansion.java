package com.antondev.keys.integration.papi;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.model.KeyTier;
import java.util.Locale;
import java.util.Objects;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** PlaceholderAPI expansion backed only by the authoritative in-memory store. */
public final class PlexonKeysExpansion extends PlaceholderExpansion {
    private final PlexonKeys plugin;

    public PlexonKeysExpansion(PlexonKeys plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override public @NotNull String getIdentifier() { return "plexonkeys"; }
    @Override public @NotNull String getAuthor() { return "ZpkDxGames"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("total")) {
            if (player == null) return "0";
            long total = plugin.balances().balances(player.getUniqueId()).values().stream().mapToLong(Long::longValue).sum();
            return Long.toString(total);
        }

        KeyTier tier = extractTier(key);
        if (tier == null) return null;
        if (key.equals(tier.id()) || key.equals("balance_" + tier.id())) {
            return player == null ? "0" : Long.toString(plugin.balances().balance(player.getUniqueId(), tier));
        }
        if (key.equals("enabled_" + tier.id())) {
            return Boolean.toString(plugin.settings().categories().get(tier).enabled());
        }
        if (key.equals("display_" + tier.id()) || key.equals(tier.id() + "_display")) {
            return plugin.settings().categories().get(tier).display();
        }
        return null;
    }

    private static KeyTier extractTier(String params) {
        for (KeyTier tier : KeyTier.values()) {
            if (params.equals(tier.id()) || params.endsWith("_" + tier.id()) || params.startsWith(tier.id() + "_")) return tier;
        }
        return null;
    }
}
