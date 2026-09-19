package com.antondev.keys.listener;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.activity.ProvenancePolicy;
import com.antondev.keys.model.Activity;
import java.util.Set;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

/** Fishing and mob acquisition with conservative current-Paper provenance classification. */
public final class ActivityListener implements Listener {
    private static final Set<String> NATURAL_MOB_ORIGINS = Set.of(
            "NATURAL", "PATROL", "RAID", "REINFORCEMENTS");
    private static final Set<String> SPAWNER_MOB_ORIGINS = Set.of("SPAWNER", "TRIAL_SPAWNER");

    private final PlexonKeys plugin;
    private final ProvenancePolicy provenancePolicy;

    public ActivityListener(PlexonKeys plugin) {
        this.plugin = plugin;
        this.provenancePolicy = new ProvenancePolicy(plugin);
    }

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

        ProvenancePolicy.Origin origin = classify(entity);
        if (!provenancePolicy.evaluate(Activity.MOBS, origin).eligible()) return;
        plugin.rewards().tryPerform(player, Activity.MOBS);
    }

    ProvenancePolicy.Origin classify(LivingEntity entity) {
        String reason = entity.getEntitySpawnReason().name();
        if (SPAWNER_MOB_ORIGINS.contains(reason)) return ProvenancePolicy.Origin.EXTERNAL_SPAWNER;
        if (NATURAL_MOB_ORIGINS.contains(reason)) return ProvenancePolicy.Origin.NATURAL;
        return ProvenancePolicy.Origin.UNKNOWN;
    }
}
