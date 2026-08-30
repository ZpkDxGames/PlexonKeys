package com.antondev.keys;

import com.antondev.keys.listener.TrackingListener;
import com.antondev.keys.model.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityAndTrackingTest extends PluginTestBase {
    private long basic() { return plugin.data().balance(player.getUniqueId(), KeyTier.BASIC); }
    private Block block(Material material, int x) { Block b = player.getWorld().getBlockAt(x, 70, 0); b.setType(material); return b; }
    @Test void naturalMiningAndLoggingAreSeparateActivities() throws Exception {
        deterministic(); plugin.configuration().set("categories.basic.chances.logging", "0");
        server.getPluginManager().callEvent(new BlockBreakEvent(block(Material.STONE, 1), player)); assertEquals(1, basic());
        server.getPluginManager().callEvent(new BlockBreakEvent(block(Material.OAK_LOG, 2), player)); assertEquals(1, basic());
    }
    @Test void placedBlocksNeverAwardEvenAfterRewardSettingsChange() throws Exception {
        deterministic(); plugin.configuration().set("settings.enabled", "false");
        Block b = block(Material.STONE, 3);
        server.getPluginManager().callEvent(new BlockPlaceEvent(b, b.getState(), b.getRelative(BlockFace.DOWN), new ItemStack(Material.STONE), player, true, EquipmentSlot.HAND));
        assertTrue(plugin.data().artificial(TrackingListener.position(b)));
        plugin.configuration().set("settings.enabled", "true");
        server.getPluginManager().callEvent(new BlockBreakEvent(b, player)); assertEquals(0, basic());
        assertFalse(plugin.data().artificial(TrackingListener.position(b)));
    }
    @Test void cancelledBreakPreservesProvenanceAndPaysNothing() throws Exception {
        deterministic(); Block b = block(Material.STONE, 4); plugin.data().mark(TrackingListener.position(b));
        BlockBreakEvent event = new BlockBreakEvent(b, player); event.setCancelled(true); server.getPluginManager().callEvent(event);
        assertEquals(0, basic()); assertTrue(plugin.data().artificial(TrackingListener.position(b)));
    }
    @Test void cancelledPlacementDoesNotTaintNaturalBlock() throws Exception {
        deterministic(); Block b = block(Material.STONE, 5);
        var event = new BlockPlaceEvent(b, b.getState(), b.getRelative(BlockFace.DOWN), new ItemStack(Material.STONE), player, true, EquipmentSlot.HAND);
        event.setCancelled(true); server.getPluginManager().callEvent(event);
        assertFalse(plugin.data().artificial(TrackingListener.position(b)));
    }
    @Test void creativeAndExcludedWorldActionsCannotEarn() throws Exception {
        deterministic(); player.setGameMode(GameMode.CREATIVE); plugin.rewards().perform(player, Activity.MINING); assertEquals(0, basic());
        player.setGameMode(GameMode.SURVIVAL);
        plugin.configuration().set("settings.excluded-worlds", "[" + player.getWorld().getName().toUpperCase(java.util.Locale.ROOT) + "]");
        plugin.rewards().perform(player, Activity.MINING); assertEquals(0, basic());
    }
    @Test void disabledEarningPermissionPreventsDrop() throws Exception {
        deterministic(); player.addAttachment(plugin, "plexonkeys.earn", false);
        plugin.rewards().perform(player, Activity.MINING); assertEquals(0, basic());
    }
    @Test void independentRollsCanAwardFourButHighestModeAwardsOne() throws Exception {
        deterministic(); plugin.configuration().update(c -> { for (KeyTier tier : KeyTier.values()) c.set("categories." + tier.id() + ".chances.mining", 100); });
        plugin.rewards().perform(player, Activity.MINING);
        for (KeyTier tier : KeyTier.values()) assertEquals(1, plugin.data().balance(player.getUniqueId(), tier));
        plugin.configuration().set("settings.roll-mode", "HIGHEST_ONLY"); plugin.rewards().perform(player, Activity.MINING);
        assertEquals(2, plugin.data().balance(player.getUniqueId(), KeyTier.LEGENDARY)); assertEquals(1, basic());
    }
    @Test void xpPaidOnceOnAcquisitionAndCashFailureDoesNotRemoveKey() throws Exception {
        deterministic(); plugin.configuration().update(c -> {
            c.set("categories.basic.bonus.xp.enabled", true); c.set("categories.basic.bonus.xp.points", 15);
            c.set("categories.basic.bonus.money.enabled", true); c.set("categories.basic.bonus.money.amount", 1.0);
        });
        int before = player.getTotalExperience(); plugin.rewards().perform(player, Activity.MINING);
        assertEquals(1, basic()); assertEquals(before + 15, player.getTotalExperience());
        plugin.claims().claim(player, KeyTier.BASIC, false); assertEquals(before + 15, player.getTotalExperience());
    }
    @Test void fishingNeedsLootAndOpenWaterAndRespectsCooldown() throws Exception {
        deterministic(); FishHook hook = mock(FishHook.class); Item loot = mock(Item.class);
        when(hook.getWorld()).thenReturn(player.getWorld());
        when(hook.isInOpenWater()).thenReturn(false);
        server.getPluginManager().callEvent(new PlayerFishEvent(player, loot, hook, PlayerFishEvent.State.CAUGHT_FISH)); assertEquals(0, basic());
        when(hook.isInOpenWater()).thenReturn(true);
        server.getPluginManager().callEvent(new PlayerFishEvent(player, null, hook, PlayerFishEvent.State.FISHING)); assertEquals(0, basic());
        server.getPluginManager().callEvent(new PlayerFishEvent(player, loot, hook, PlayerFishEvent.State.CAUGHT_FISH)); assertEquals(1, basic());
        server.getPluginManager().callEvent(new PlayerFishEvent(player, loot, hook, PlayerFishEvent.State.CAUGHT_FISH)); assertEquals(1, basic());
    }
    @Test void naturalMobKillsQualifyButSpawnerAndPlayerDeathsDoNot() throws Exception {
        deterministic(); LivingEntity mob = mock(LivingEntity.class);
        when(mob.getWorld()).thenReturn(player.getWorld());
        when(mob.getKiller()).thenReturn(player); when(mob.getType()).thenReturn(EntityType.ZOMBIE);
        when(mob.getEntitySpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.SPAWNER);
        server.getPluginManager().callEvent(new EntityDeathEvent(mob, mock(org.bukkit.damage.DamageSource.class), java.util.List.of())); assertEquals(0, basic());
        when(mob.getEntitySpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.NATURAL);
        server.getPluginManager().callEvent(new EntityDeathEvent(mob, mock(org.bukkit.damage.DamageSource.class), java.util.List.of())); assertEquals(1, basic());
        server.getPluginManager().callEvent(new EntityDeathEvent(player, mock(org.bukkit.damage.DamageSource.class), java.util.List.of())); assertEquals(1, basic());
    }
    @Test void pistonExtensionAndRetractionCarryPlacedFlag() {
        Block a = spy(block(Material.STONE, 10)), b = spy(block(Material.STONE, 11)), piston = block(Material.PISTON, 9);
        doReturn(PistonMoveReaction.MOVE).when(a).getPistonMoveReaction(); doReturn(PistonMoveReaction.MOVE).when(b).getPistonMoveReaction();
        plugin.data().mark(TrackingListener.position(a));
        server.getPluginManager().callEvent(new BlockPistonExtendEvent(piston, java.util.List.of(a, b), BlockFace.EAST));
        assertFalse(plugin.data().artificial(TrackingListener.position(a))); assertTrue(plugin.data().artificial(TrackingListener.position(b)));
        server.getPluginManager().callEvent(new BlockPistonRetractEvent(piston, java.util.List.of(b), BlockFace.WEST));
        assertTrue(plugin.data().artificial(TrackingListener.position(a))); assertFalse(plugin.data().artificial(TrackingListener.position(b)));
    }
    @Test void generatedStoneIsExcludedAndCancelledFormationIsIgnored() {
        Block a = block(Material.STONE, 20), b = block(Material.STONE, 21);
        server.getPluginManager().callEvent(new BlockFormEvent(a, a.getState()));
        BlockFormEvent cancelled = new BlockFormEvent(b, b.getState()); cancelled.setCancelled(true); server.getPluginManager().callEvent(cancelled);
        assertTrue(plugin.data().artificial(TrackingListener.position(a))); assertFalse(plugin.data().artificial(TrackingListener.position(b)));
    }
}
