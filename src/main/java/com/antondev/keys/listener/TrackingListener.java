package com.antondev.keys.listener;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.data.MemoryStore.Position;
import com.antondev.keys.integration.core.runtime.CoreRuntimeBridge;
import com.antondev.keys.integration.core.runtime.CoreRuntimeBridgeFactory;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;

/** Tracks only Keys-specific/legacy event-touched coordinates; Core owns ordinary placement in Runtime mode. */
public final class TrackingListener implements Listener {
    private final PlexonKeys plugin;
    private final NamespacedKey carried;
    private final CoreRuntimeBridge runtime;

    public TrackingListener(PlexonKeys plugin) {
        this.plugin = plugin;
        carried = new NamespacedKey(plugin, "artificial_block");
        runtime = CoreRuntimeBridgeFactory.resolve(plugin, plugin.core());
    }

    public static Position position(Block block) {
        return Position.of(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (!event.canBuild()) return;
        if (event instanceof BlockMultiPlaceEvent multi) {
            // Core 2.0 tracks the primary placed block; keep the full multi-place set as a conservative overlay.
            ArrayList<Position> positions = new ArrayList<>(multi.getReplacedBlockStates().size());
            for (BlockState state : multi.getReplacedBlockStates()) positions.add(position(state.getBlock()));
            plugin.pressureSaveProbe(plugin.data().markAll(positions));
        } else {
            if (plugin.coreBlocksOwned()) return;
            plugin.data().mark(position(event.getBlockPlaced()));
            plugin.pressureSaveProbe(1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent event) { move(event.getBlocks(), event.getDirection()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent event) {
        // Paper supplies the movement direction for nonempty retract events (already opposite piston facing).
        move(event.getBlocks(), event.getDirection());
    }

    private void move(List<Block> blocks, BlockFace direction) {
        Map<Position, Position> moves = new HashMap<>(Math.max(4, blocks.size() * 2));
        ArrayList<Position> removed = new ArrayList<>();
        for (Block block : blocks) {
            Position source = position(block);
            if (block.getPistonMoveReaction() == PistonMoveReaction.BREAK) removed.add(source);
            else moves.put(source, position(block.getRelative(direction)));
        }
        int mutations = 0;
        if (!removed.isEmpty()) mutations += plugin.data().unmarkAll(removed);
        if (!moves.isEmpty()) {
            plugin.data().move(moves);
            mutations += moves.size() * 2;
        }
        plugin.pressureSaveProbe(mutations);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent event) {
        Position pos = position(event.getBlock());
        Entity entity = event.getEntity();
        if (entity instanceof FallingBlock || entity instanceof Enderman) {
            if (event.getTo().isAir()) {
                boolean artificial = plugin.data().unmark(pos);
                if (!artificial && plugin.coreBlocksOwned()) artificial = coreOrigin(event.getBlock()) == CoreRuntimeBridge.Origin.ARTIFICIAL;
                entity.getPersistentDataContainer().set(carried, PersistentDataType.BYTE, (byte) (artificial ? 1 : 0));
            } else {
                Byte flag = entity.getPersistentDataContainer().get(carried, PersistentDataType.BYTE);
                plugin.data().unmark(pos);
                // Unknown externally spawned falling blocks are conservatively excluded.
                if (flag == null || flag == 1) plugin.data().mark(pos);
                entity.getPersistentDataContainer().remove(carried);
            }
        } else if (event.getTo().isAir()) {
            plugin.data().unmark(pos);
        } else {
            plugin.data().mark(pos);
        }
        plugin.pressureSaveProbe(1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void grow(StructureGrowEvent event) { mutateGrowth(event.getBlocks()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fertilize(BlockFertilizeEvent event) { mutateGrowth(event.getBlocks()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockGrow(BlockGrowEvent event) { mutateGrowth(List.of(event.getNewState())); }

    private void mutateGrowth(List<BlockState> states) {
        boolean excludeGrowth = plugin.settings().trackGrowth();
        ArrayList<Position> mark = excludeGrowth ? new ArrayList<>(states.size()) : null;
        ArrayList<Position> unmark = new ArrayList<>();
        for (BlockState state : states) {
            Position pos = position(state.getBlock());
            if (!excludeGrowth || state.getType().isAir()) unmark.add(pos);
            else mark.add(pos);
        }
        int mutations = 0;
        if (!unmark.isEmpty()) mutations += plugin.data().unmarkAll(unmark);
        if (mark != null && !mark.isEmpty()) mutations += plugin.data().markAll(mark);
        plugin.pressureSaveProbe(mutations);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void form(BlockFormEvent event) {
        if (!plugin.settings().trackFormation()) return;
        plugin.data().mark(position(event.getBlock()));
        plugin.pressureSaveProbe(1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spread(BlockSpreadEvent event) {
        boolean artificialSource = plugin.data().artificial(position(event.getSource()));
        if (!artificialSource && plugin.coreBlocksOwned()) artificialSource = coreOrigin(event.getSource()) == CoreRuntimeBridge.Origin.ARTIFICIAL;
        // Preserve artificial provenance through grass/sculk/mushroom conversions as well.
        if (plugin.settings().trackFormation() || artificialSource) {
            plugin.data().mark(position(event.getBlock()));
            plugin.pressureSaveProbe(1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent event) { removeAll(event.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplosion(EntityExplodeEvent event) { removeAll(event.blockList()); }

    private void removeAll(List<Block> blocks) {
        if (blocks.isEmpty()) return;
        ArrayList<Position> positions = new ArrayList<>(blocks.size());
        for (Block block : blocks) positions.add(position(block));
        plugin.pressureSaveProbe(plugin.data().unmarkAll(positions));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void burn(BlockBurnEvent event) {
        if (plugin.data().unmark(position(event.getBlock()))) plugin.pressureSaveProbe(1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void decay(LeavesDecayEvent event) {
        if (plugin.data().unmark(position(event.getBlock()))) plugin.pressureSaveProbe(1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fade(BlockFadeEvent event) {
        if (event.getNewState().getType().isAir() && plugin.data().unmark(position(event.getBlock()))) {
            plugin.pressureSaveProbe(1);
        }
    }

    private CoreRuntimeBridge.Origin coreOrigin(Block block) {
        return runtime.origin(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
