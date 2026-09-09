package com.antondev.keys.activity;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.model.Activity;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;

/** Source-independent mining/logging reward policy shared by local and Core runtime acquisition. */
public final class BlockActivityProcessor {
    private final PlexonKeys plugin;

    public BlockActivityProcessor(PlexonKeys plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    /** Returns the exact 1.3 mining/logging classification, or null when the material is not eligible. */
    public Activity classify(Material type) {
        Objects.requireNonNull(type, "type");
        var settings = plugin.settings();
        boolean logging = settings.loggingMaterials().isEmpty()
                ? Tag.LOGS.isTagged(type)
                : settings.loggingMaterials().contains(type);
        if (logging) return Activity.LOGGING;
        if (!settings.miningMaterials().isEmpty() && !settings.miningMaterials().contains(type)) return null;
        return Activity.MINING;
    }

    /**
     * Completes an already classified block reward attempt. UNKNOWN and artificial origin fail closed.
     * Player-specific permission/world/game-mode/cooldown/cap eligibility remains RewardService-owned.
     */
    public boolean tryPerform(Player player, Activity activity, Origin origin, boolean dropsEnabled, boolean preferredTool) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(origin, "origin");
        if (origin != Origin.NATURAL) return false;
        if (plugin.settings().requireDrops() && (!dropsEnabled || !preferredTool)) return false;
        plugin.rewards().tryPerform(player, activity);
        return true;
    }

    public enum Origin {
        NATURAL,
        ARTIFICIAL,
        UNKNOWN
    }
}
