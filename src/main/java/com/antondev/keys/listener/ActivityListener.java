package com.antondev.keys.listener;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.model.Activity;
import org.bukkit.Tag;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

public final class ActivityListener implements Listener {
    private final PlexonKeys plugin;
    public ActivityListener(PlexonKeys plugin) { this.plugin = plugin; }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        // Always consume provenance, including creative breaks and worlds with rewards disabled.
        if (plugin.data().unmark(TrackingListener.position(event.getBlock()))) return;
        var s = plugin.settings();
        if (!s.allowsWorld(event.getBlock().getWorld())) return;
        var type = event.getBlock().getType();
        boolean log = s.loggingMaterials().isEmpty() ? Tag.LOGS.isTagged(type) : s.loggingMaterials().contains(type);
        Activity activity = log ? Activity.LOGGING : Activity.MINING;
        if (!log && !s.miningMaterials().isEmpty() && !s.miningMaterials().contains(type)) return;
        if (!plugin.rewards().eligible(event.getPlayer(), activity)) return;
        if (s.requireDrops() && (!event.isDropItems() || !event.getBlock().isPreferredTool(event.getPlayer().getInventory().getItemInMainHand()))) return;
        plugin.rewards().perform(event.getPlayer(), activity);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item)) return;
        if (!plugin.settings().allowsWorld(event.getHook().getWorld())) return;
        if (plugin.settings().openWater() && !event.getHook().isInOpenWater()) return;
        plugin.rewards().perform(event.getPlayer(), Activity.FISHING);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void killed(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || entity instanceof ArmorStand) return;
        Player player = entity.getKiller();
        if (player == null) return;
        var s = plugin.settings();
        if (!s.allowsWorld(entity.getWorld())) return;
        if (!s.mobs().isEmpty() && !s.mobs().contains(entity.getType())) return;
        if (!s.spawnReasons().isEmpty() && !s.spawnReasons().contains(entity.getEntitySpawnReason())) return;
        plugin.rewards().perform(player, Activity.MOBS);
    }
}
