package com.antondev.keys.diagnostics;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.integration.papi.OptionalPlaceholderIntegration;
import com.antondev.keys.model.KeyTier;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;

/** Low-overhead PlexonKeys diagnostics. No database query or global entity scan is performed here. */
public final class Phase2Diagnostics {
    private Phase2Diagnostics() {}

    public static void append(PlexonKeys plugin, CommandSender sender) {
        long enabled = Arrays.stream(KeyTier.values()).filter(t -> plugin.settings().categories().get(t).enabled()).count();
        String ids = Arrays.stream(KeyTier.values()).map(KeyTier::id).collect(Collectors.joining(","));
        sender.sendMessage("§8[PlexonKeys 2.1] §7definitions=§f" + KeyTier.values().length
                + " §7enabled=§f" + enabled + " §7ids=§f" + ids);
        sender.sendMessage("§8[PlexonKeys 2.1] §7config-schema=§f2 §7physical-identity=§fEXACT_COMPONENTS"
                + " §7PlaceholderAPI=§f" + OptionalPlaceholderIntegration.status()
                + " §7PlexonCrates=§fRETIRED §7mob-spawner-authority=§fPAPER/WILDSTACKER");
    }
}
