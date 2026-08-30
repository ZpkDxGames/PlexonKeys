package com.antondev.keys;

import com.antondev.keys.gui.KeyMenu;
import com.antondev.keys.model.KeyTier;
import org.bukkit.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClaimAndMenuTest extends PluginTestBase {
    private void grant(KeyTier tier, long count) { plugin.data().set(player.getUniqueId(), tier, count); }
    private long balance(KeyTier tier) { return plugin.data().balance(player.getUniqueId(), tier); }
    private long items(KeyTier tier) {
        ItemStack key = plugin.settings().categories().get(tier).itemCopy(); long amount = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) if (stack != null && stack.isSimilar(key)) amount += stack.getAmount();
        return amount;
    }
    @Test void playerMenuHasFourIndependentClaimPanels() {
        player.performCommand("keys"); var inventory = player.getOpenInventory().getTopInventory();
        assertInstanceOf(KeyMenu.class, inventory.getHolder()); assertEquals(54, inventory.getSize());
        for (KeyTier tier : KeyTier.values()) assertNotNull(inventory.getItem(plugin.settings().yaml().getInt("categories." + tier.id() + ".menu.claim-slot")));
    }
    @Test void fullInventoryLeavesEveryKeyVirtual() {
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        grant(KeyTier.BASIC, 100); plugin.claims().claim(player, null, false);
        assertEquals(100, balance(KeyTier.BASIC)); assertEquals(0, items(KeyTier.BASIC));
    }
    @Test void partialCapacityClaimsOnlyWhatFitsAndPreservesMetadata() {
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        ItemStack partial = plugin.settings().categories().get(KeyTier.BASIC).itemCopy(); partial.setAmount(60);
        player.getInventory().setItem(0, partial); player.getInventory().setItem(1, null);
        grant(KeyTier.BASIC, 100); plugin.claims().claim(player, KeyTier.BASIC, false);
        assertEquals(32, balance(KeyTier.BASIC)); assertEquals(128, items(KeyTier.BASIC));
        assertTrue(player.getInventory().getItem(0).isSimilar(plugin.settings().categories().get(KeyTier.BASIC).itemCopy()));
    }
    @Test void claimAllTransfersEveryCategoryAndNeverPaysBonuses() throws Exception {
        plugin.configuration().update(c -> { for (KeyTier tier : KeyTier.values()) { c.set("categories." + tier.id() + ".bonus.xp.enabled", true); c.set("categories." + tier.id() + ".bonus.xp.points", 50); }});
        for (KeyTier tier : KeyTier.values()) grant(tier, 2);
        int before = player.getTotalExperience(); player.performCommand("keys claim all");
        for (KeyTier tier : KeyTier.values()) { assertEquals(0, balance(tier)); assertEquals(2, items(tier)); }
        assertEquals(before, player.getTotalExperience());
    }
    @Test void rapidClicksCannotDuplicateKeys() {
        grant(KeyTier.BASIC, 3); player.performCommand("keys");
        click(19); click(19); server.getScheduler().performOneTick();
        assertEquals(0, balance(KeyTier.BASIC)); assertEquals(3, items(KeyTier.BASIC));
    }
    @Test void rightClickClaimsExactlyOne() {
        grant(KeyTier.BASIC, 3); player.performCommand("keys");
        click(ClickType.RIGHT, 19); server.getScheduler().performOneTick();
        assertEquals(2, balance(KeyTier.BASIC)); assertEquals(1, items(KeyTier.BASIC));
    }
    @Test void shiftAndNumberKeyClicksCannotExtractIcons() {
        grant(KeyTier.BASIC, 2); player.performCommand("keys");
        var shift = click(ClickType.SHIFT_LEFT, 10);
        assertTrue(shift.isCancelled()); server.getScheduler().performOneTick();
        assertEquals(2, balance(KeyTier.BASIC)); assertEquals(0, items(KeyTier.BASIC)); assertTrue(player.getInventory().isEmpty());
        var number = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 10, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 0);
        server.getPluginManager().callEvent(number); assertTrue(number.isCancelled());
    }
    @Test void permissionsAreRecheckedAfterMenuOpened() {
        player.setOp(true); player.performCommand("keysadmin");
        click(31); player.setOp(false); server.getScheduler().performOneTick();
        assertTrue(plugin.settings().enabled());
    }
    @Test void disabledCategoriesRemainClaimable() throws Exception {
        grant(KeyTier.LEGENDARY, 2); plugin.configuration().set("categories.legendary.enabled", "false");
        plugin.claims().claim(player, KeyTier.LEGENDARY, false);
        assertEquals(0, balance(KeyTier.LEGENDARY)); assertEquals(2, items(KeyTier.LEGENDARY));
    }
    @Test void staleClickAfterClosingMenuDoesNothing() {
        grant(KeyTier.BASIC, 2); player.performCommand("keys"); click(19); player.closeInventory();
        server.getScheduler().performOneTick(); assertEquals(2, balance(KeyTier.BASIC)); assertEquals(0, items(KeyTier.BASIC));
    }
    @Test void menuSlotsNeverBecomePhysicalKeyLore() {
        grant(KeyTier.RARE, 1); player.performCommand("keys"); plugin.claims().claim(player, KeyTier.RARE, false);
        ItemStack key = player.getInventory().getItem(0);
        assertEquals(plugin.settings().categories().get(KeyTier.RARE).itemCopy().getItemMeta(), key.getItemMeta());
    }
    @Test void unstackableKeyTemplatesRespectTheirCustomStackLimit() {
        ItemStack key = plugin.settings().categories().get(KeyTier.RARE).itemCopy(); key.editMeta(meta -> meta.setMaxStackSize(1));
        var plan = com.antondev.keys.reward.InventoryDelivery.plan(new ItemStack[2], 64, java.util.Map.of(KeyTier.RARE, key), java.util.Map.of(KeyTier.RARE, 1000000L));
        assertEquals(2, plan.total()); assertEquals(1, plan.contents()[0].getAmount()); assertEquals(1, plan.contents()[1].getAmount());
        assertEquals(key.getItemMeta(), plan.contents()[0].getItemMeta());
    }
}
