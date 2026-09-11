package com.antondev.keys.diagnostics;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.integration.papi.OptionalPlaceholderIntegration;
import com.antondev.keys.integration.spawners.SpawnerProvenanceBridge;
import com.antondev.keys.model.KeyTier;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/** Low-overhead Phase 2 diagnostics. No database query or global entity scan is performed here. */
public final class Phase2Diagnostics {
    private Phase2Diagnostics() {}

    public static void append(PlexonKeys plugin, CommandSender sender) {
        long enabled = Arrays.stream(KeyTier.values()).filter(t -> plugin.settings().categories().get(t).enabled()).count();
        String ids = Arrays.stream(KeyTier.values()).map(KeyTier::id).collect(Collectors.joining(","));
        sender.sendMessage("§8[PlexonKeys Phase2] §7definitions=§f" + KeyTier.values().length
                + " §7enabled=§f" + enabled + " §7ids=§f" + ids);
        sender.sendMessage("§8[PlexonKeys Phase2] §7config-schema=§f1 §7db-schema=§f1"
                + " §7physical-identity=§fEXACT_COMPONENTS §7consume-replay-guard=§f"
                + plugin.balances().consumeReplayGuardSize() + "/4096");
        sender.sendMessage("§8[PlexonKeys Phase2] §7PlexonSpawners=§f" + SpawnerProvenanceBridge.status()
                + " §7PlaceholderAPI=§f" + OptionalPlaceholderIntegration.status()
                + " §7PlexonCrates=§f" + pluginState(plugin, "PlexonCrates"));
    }

    private static String pluginState(PlexonKeys plugin, String name) {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin(name);
        if (dependency == null) return "ABSENT";
        return dependency.isEnabled() ? "READY:" + safeVersion(dependency) : "DISABLED";
    }

    private static String safeVersion(Plugin plugin) {
        try { return plugin.getPluginMeta().getVersion().toUpperCase(Locale.ROOT); }
        catch (RuntimeException ignored) { return "UNKNOWN"; }
    }
}
