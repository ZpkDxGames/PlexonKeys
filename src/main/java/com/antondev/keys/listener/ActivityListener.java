package com.antondev.keys.listener;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.model.Activity;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

/** Event families that remain local in 1.4 because Core 2 exposes no exact mob/fishing outcome parity yet. */
public final class ActivityListener implements Listener {
    private final PlexonKeys plugin;

    public ActivityListener(PlexonKeys plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item)) return;
        if (plugin.settings().openWater() && !event.getHook().isInOpenWater()) return;
        plugin.rewards().tryPerform(event.getPlayer(), Activity.FISHING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void killed(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || entity instanceof ArmorStand) return;
        Player player = entity.getKiller();
        if (player == null) return;
        var settings = plugin.settings();
        if (!settings.mobs().isEmpty() && !settings.mobs().contains(entity.getType())) return;
        if (!settings.spawnReasons().isEmpty() && !settings.spawnReasons().contains(entity.getEntitySpawnReason())) return;
        plugin.rewards().tryPerform(player, Activity.MOBS);
    }
}
