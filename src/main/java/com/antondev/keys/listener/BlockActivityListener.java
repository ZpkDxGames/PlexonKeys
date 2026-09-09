package com.antondev.keys.listener;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.activity.BlockActivityProcessor;
import com.antondev.keys.activity.BlockActivityProcessor.Origin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/** Legacy/local BlockBreak acquisition. Core Runtime mode unregisters this listener entirely. */
public final class BlockActivityListener implements Listener {
    private final PlexonKeys plugin;
    private final BlockActivityProcessor processor;

    public BlockActivityListener(PlexonKeys plugin, BlockActivityProcessor processor) {
        this.plugin = plugin;
        this.processor = processor;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        // Preserve 1.3 provenance consumption before reward classification.
        if (plugin.data().unmark(TrackingListener.position(event.getBlock()))) {
            plugin.pressureSaveProbe(1);
            return;
        }

        var activity = processor.classify(event.getBlock().getType());
        if (activity == null) return;
        boolean preferred = !plugin.settings().requireDrops()
                || event.getBlock().isPreferredTool(event.getPlayer().getInventory().getItemInMainHand());
        processor.tryPerform(event.getPlayer(), activity, Origin.NATURAL, event.isDropItems(), preferred);
    }
}
