package com.antondev.keys.listener;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.data.MemoryStore.Position;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;

/** Tracks only event-touched coordinates. Never scans a world or performs database I/O in an event. */
public final class TrackingListener implements Listener {
    private final PlexonKeys plugin;
    private final NamespacedKey carried;
    public TrackingListener(PlexonKeys plugin) { this.plugin = plugin; carried = new NamespacedKey(plugin, "artificial_block"); }
    public static Position position(Block block) { return Position.of(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (!event.canBuild()) return;
        if (event instanceof BlockMultiPlaceEvent multi) multi.getReplacedBlockStates().forEach(state -> mark(state.getBlock()));
        else mark(event.getBlockPlaced());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent event) { move(event.getBlocks(), event.getDirection()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent event) {
        // Paper supplies the movement direction for nonempty retract events (already opposite the piston facing).
        move(event.getBlocks(), event.getDirection());
    }
    private void move(List<Block> blocks, BlockFace direction) {
        Map<Position, Position> moves = new HashMap<>();
        for (Block block : blocks) {
            if (block.getPistonMoveReaction() == PistonMoveReaction.BREAK) plugin.data().unmark(position(block));
            else moves.put(position(block), position(block.getRelative(direction)));
        }
        plugin.data().move(moves);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent event) {
        Position pos = position(event.getBlock());
        Entity entity = event.getEntity();
        if (entity instanceof FallingBlock || entity instanceof Enderman) {
            if (event.getTo().isAir()) {
                boolean artificial = plugin.data().unmark(pos);
                entity.getPersistentDataContainer().set(carried, PersistentDataType.BYTE, (byte) (artificial ? 1 : 0));
            } else {
                Byte flag = entity.getPersistentDataContainer().get(carried, PersistentDataType.BYTE);
                plugin.data().unmark(pos);
                // Unknown externally spawned falling blocks are conservatively excluded.
                if (flag == null || flag == 1) plugin.data().mark(pos);
                entity.getPersistentDataContainer().remove(carried);
            }
        } else if (event.getTo().isAir()) plugin.data().unmark(pos);
        else plugin.data().mark(pos);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void grow(StructureGrowEvent event) { event.getBlocks().forEach(this::grown); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fertilize(BlockFertilizeEvent event) { event.getBlocks().forEach(this::grown); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockGrow(BlockGrowEvent event) { grown(event.getNewState()); }
    private void grown(BlockState state) {
        if (state.getType().isAir() || !plugin.settings().trackGrowth()) plugin.data().unmark(position(state.getBlock()));
        else mark(state.getBlock());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void form(BlockFormEvent event) { if (plugin.settings().trackFormation()) mark(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spread(BlockSpreadEvent event) {
        // Preserve artificial provenance through grass/sculk/mushroom conversions as well.
        if (plugin.settings().trackFormation() || plugin.data().artificial(position(event.getSource()))) mark(event.getBlock());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent event) { event.blockList().forEach(this::remove); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplosion(EntityExplodeEvent event) { event.blockList().forEach(this::remove); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void burn(BlockBurnEvent event) { remove(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void decay(LeavesDecayEvent event) { remove(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fade(BlockFadeEvent event) { if (event.getNewState().getType().isAir()) remove(event.getBlock()); }
    private void mark(Block block) { plugin.data().mark(position(block)); }
    private void remove(Block block) { plugin.data().unmark(position(block)); }
}
