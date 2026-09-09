package com.antondev.keys;

import com.antondev.keys.gui.KeyMenu;
import com.antondev.keys.model.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChanceEditorTest extends PluginTestBase {
    private static final String PATH = "categories.legendary.chances.mining";
    private Path file() { return plugin.getDataFolder().toPath().resolve("config.yml"); }
    private void open() { player.setOp(true); player.performCommand("keysadmin chances legendary mining"); }
    private String name(Player who, int slot) {
        return PlainTextComponentSerializer.plainText().serialize(who.getOpenInventory().getTopInventory().getItem(slot).getItemMeta().displayName());
    }
    private String draft() { return name(player, 22); }
    private double saved() { return plugin.settings().categories().get(KeyTier.LEGENDARY).chances().get(Activity.MINING); }
    private void press(int slot) { assertTrue(click(slot).isCancelled()); server.getScheduler().performOneTick(); }
    private void press(Player who, int slot) {
        var event = new InventoryClickEvent(who.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event); assertTrue(event.isCancelled()); server.getScheduler().performOneTick();
    }
    private AsyncChatEvent input(String value) {
        var event = mock(AsyncChatEvent.class);
        when(event.getPlayer()).thenReturn(player); when(event.originalMessage()).thenReturn(Component.text(value));
        plugin.menus().chat(event); verify(event).setCancelled(true);
        return event;
    }
    private void enter(String value) { input(value); server.getScheduler().performOneTick(); }
    private boolean hasMenu(Player who) {
        var top = who.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof KeyMenu;
    }

    @Test void mainMenuShortcutReachesAllSixteenCategoryActivityPairs() {
        player.setOp(true); player.performCommand("keysadmin"); press(22);
        int column = 1;
        for (KeyTier tier : KeyTier.values()) {
            for (Activity activity : Activity.values()) {
                press(column + 9 * (activity.ordinal() + 1));
                String context = name(player, 4);
                assertTrue(context.toLowerCase(Locale.ROOT).contains(tier.id()), context);
                assertTrue(context.contains(plugin.settings().tasks().get(activity).display()), context);
                press(45); // Cancel returns to the same overview.
            }
            column += 2;
        }
        assertTrue(player.getInventory().isEmpty());
    }
    @Test void categoryButtonsOpenTheEditorAndApplyOnlyTheSelectedChance() throws Exception {
        player.setOp(true); player.performCommand("keysadmin chances legendary");
        var before = plugin.settings().categories();
        press(28); assertEquals("0.003%", draft()); press(33); press(49);
        assertEquals(0.004, saved());
        for (KeyTier tier : KeyTier.values()) for (Activity activity : Activity.values())
            if (tier != KeyTier.LEGENDARY || activity != Activity.MINING)
                assertEquals(before.get(tier).chances().get(activity), plugin.settings().categories().get(tier).chances().get(activity));
        // Back on the category page: opening its chance again sees the committed value.
        press(28); assertEquals("0.004%", draft());
        plugin.configuration().reload(); assertEquals(0.004, saved());
        assertEquals(0.004, YamlConfiguration.loadConfiguration(file().toFile()).getDouble(PATH));
    }
    @Test void decimalAdjustmentsNeverWriteUntilApplyAndUnchangedApplyDoesNotWrite() throws Exception {
        long scheduledBefore = server.getScheduler().getPendingTasks().stream().filter(task -> task.getOwner().equals(plugin)).count();
        open(); String before = Files.readString(file()); long revision = plugin.configuration().revision();
        for (int i = 0; i < 10; i++) press(33);
        assertEquals("0.013%", draft()); assertEquals(0.003, saved());
        assertEquals(before, Files.readString(file())); assertEquals(revision, plugin.configuration().revision());
        for (int i = 0; i < 10; i++) press(15);
        assertEquals("0.003%", draft()); press(49);
        assertEquals(before, Files.readString(file())); assertEquals(revision, plugin.configuration().revision());
        long scheduledAfter = server.getScheduler().getPendingTasks().stream().filter(task -> task.getOwner().equals(plugin)).count();
        assertEquals(scheduledBefore, scheduledAfter, "Unchanged chance Apply must not schedule extra plugin work");
    }
    @Test void cancelAndClosingDiscardDrafts() throws Exception {
        open(); String before = Files.readString(file()); press(33); press(45);
        open(); assertEquals("0.003%", draft()); press(29); player.closeInventory();
        open(); assertEquals("0.003%", draft()); assertEquals(before, Files.readString(file()));
    }
    @Test void resetRestoresTheSavedPercentageWithoutCommitting() throws Exception {
        open(); String before = Files.readString(file());
        press(42); assertEquals("100%", draft()); press(40); assertEquals("0.003%", draft());
        press(38); assertEquals("0%", draft()); press(40); assertEquals("0.003%", draft());
        assertEquals(before, Files.readString(file()));
    }
    @Test void limitsClampAndZeroAndHundredCanBeSavedWithoutEnablingDrops() throws Exception {
        plugin.configuration().update(c -> {
            c.set("settings.enabled", false); c.set("categories.legendary.enabled", false); c.set("activities.mining.enabled", false);
        });
        plugin.settingsChanged(); open();
        press(42); press(29); assertEquals("100%", draft());
        press(38); press(11); assertEquals("0%", draft()); press(49); assertEquals(0, saved());
        open(); press(42); press(49); plugin.configuration().reload(); assertEquals(100, saved());
        assertFalse(plugin.settings().enabled()); assertFalse(plugin.settings().categories().get(KeyTier.LEGENDARY).enabled());
        assertFalse(plugin.settings().tasks().get(Activity.MINING).enabled());
    }
    @Test void exactInputIsPrivateAndStagedAndChatCancelPreservesTheDraft() throws Exception {
        open(); String before = Files.readString(file()); press(33); press(53); enter("0.00007");
        assertEquals("0.00007%", draft()); assertEquals(before, Files.readString(file())); assertEquals(0.003, saved());
        press(53); enter("cancel"); assertEquals("0.00007%", draft());
        press(49); plugin.configuration().reload(); assertEquals(0.00007, saved());
    }
    @Test void malformedOrOutOfRangeExactInputCannotEraseTheDraft() throws Exception {
        open(); press(33); String before = Files.readString(file());
        for (String invalid : List.of("NaN", "Infinity", "-0.001", "101", "1e-10000", "1e10000", "0.1 0.2", "")) {
            press(53); enter(invalid); assertEquals("0.004%", draft(), invalid);
        }
        assertEquals(0.003, saved()); assertEquals(before, Files.readString(file()));
    }
    @Test void pendingApplyRejectsAStaleConfigurationRevision() throws Exception {
        open(); press(33); click(49);
        plugin.configuration().set(PATH, "0.25");
        server.getScheduler().performOneTick();
        assertEquals(0.25, saved()); assertFalse(hasMenu(player));
    }
    @Test void queuedExactInputCannotReopenOrApplyAfterReload() throws Exception {
        open(); press(53); input("0.1");
        plugin.configuration().set(PATH, "0.25"); plugin.settingsChanged();
        server.getScheduler().performOneTick(); assertEquals(0.25, saved()); assertFalse(hasMenu(player));
    }
    @Test void queuedExactInputCannotReplaceANewerMenu() {
        open(); press(53); input("0.1"); player.performCommand("keysadmin");
        var newer = player.getOpenInventory().getTopInventory();
        server.getScheduler().performOneTick();
        assertSame(newer, player.getOpenInventory().getTopInventory()); assertEquals(0.003, saved());
    }
    @Test void eachAdminHasTheirOwnDraftAndSavingInvalidatesOtherMenus() {
        open(); press(33);
        Player other = server.addPlayer("SecondAdmin"); other.setOp(true); other.performCommand("keysadmin chances legendary mining");
        assertEquals("0.003%", name(other, 22)); press(other, 29);
        assertEquals("10.003%", name(other, 22)); assertEquals("0.004%", draft());
        press(other, 49); assertEquals(10.003, saved()); assertFalse(hasMenu(player));
        open(); assertEquals("10.003%", draft());
    }
    @Test void nonAdminsCannotOpenTheOverviewOrEditor() {
        player.setOp(false); player.performCommand("keysadmin chances"); assertFalse(hasMenu(player));
        plugin.menus().openChances(player); assertFalse(hasMenu(player));
        plugin.menus().openChanceEditor(player, KeyTier.LEGENDARY, Activity.MINING); assertFalse(hasMenu(player));
    }
    @Test void permissionRevocationBeforeApplyPreventsSaving() throws Exception {
        open(); press(33); String before = Files.readString(file());
        click(49); player.setOp(false); server.getScheduler().performOneTick();
        assertEquals(0.003, saved()); assertEquals(before, Files.readString(file())); assertFalse(hasMenu(player));
    }
    @Test void unusualInventoryClicksCannotAdjustOrExtractEditorItems() {
        open();
        for (ClickType type : List.of(ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.MIDDLE, ClickType.DROP, ClickType.DOUBLE_CLICK)) {
            assertTrue(click(type, 33).isCancelled()); server.getScheduler().performOneTick();
        }
        var hotbar = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 33, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 0);
        server.getPluginManager().callEvent(hotbar); assertTrue(hotbar.isCancelled());
        assertTrue(click(54).isCancelled()); server.getScheduler().performOneTick();
        assertEquals("0.003%", draft()); assertTrue(player.getInventory().isEmpty());
        assertTrue(click(ClickType.RIGHT, 33).isCancelled()); server.getScheduler().performOneTick(); assertEquals("0.004%", draft());
    }
    @Test void externalFileEditsArePreservedAndARejectedSaveKeepsTheDraft() throws Exception {
        open(); press(33);
        var edited = YamlConfiguration.loadConfiguration(file().toFile()); edited.set(PATH, 0.25); edited.save(file().toFile());
        String disk = Files.readString(file()); press(49);
        assertEquals(disk, Files.readString(file())); assertEquals("0.004%", draft()); assertEquals(0.003, saved());
        assertTrue(plugin.reloadFor(player)); open(); assertEquals("0.25%", draft());
    }
    @Test void oldConfigurationGetsNewDefaultsWithoutReplacingCustomChancesOrLabels() throws Exception {
        var legacy = YamlConfiguration.loadConfiguration(file().toFile());
        for (String path : List.of("gui.admin.chances-title", "gui.admin.chances", "gui.admin.chance-category", "gui.admin.chance-editor",
                "messages.chance-saved", "messages.chance-prompt", "messages.chance-invalid")) legacy.set(path, null);
        legacy.set(PATH, 0.00009); legacy.set("gui.admin.chance.name", "<aqua>Custom <value>%</aqua>"); legacy.save(file().toFile());
        plugin.configuration().reload(); plugin.settingsChanged(); open(); assertEquals("0.00009%", draft());
        press(33); press(49); plugin.configuration().reload();
        assertEquals(0.00109, saved());
        assertEquals("<aqua>Custom <value>%</aqua>", plugin.settings().yaml().getString("gui.admin.chance.name"));
        assertTrue(YamlConfiguration.loadConfiguration(file().toFile()).isConfigurationSection("gui.admin.chance-editor"));
    }
    @Test void editorMaterialsAndLabelsRenderMiniMessageIncludingActivityNames() throws Exception {
        plugin.configuration().update(c -> {
            c.set("activities.mining.display-name", "<gradient:#00AAFF:#FF00AA>Mining</gradient>");
            c.set("gui.admin.chance-editor.apply.material", "DIAMOND");
            c.set("gui.admin.chance-editor.apply.name", "<gradient:#00AAFF:#FF00AA>Save <value>%</gradient>");
        });
        plugin.settingsChanged(); open();
        assertEquals("Legendary • Mining", name(player, 4)); assertEquals("Save 0.003%", name(player, 49));
        assertEquals(Material.DIAMOND, player.getOpenInventory().getTopInventory().getItem(49).getType());
    }
    @Test void shortcutHasPermissionAwareCompletionAndOriginalChanceCommandStillWorks() {
        player.setOp(true); var command = plugin.getCommand("keysadmin");
        assertTrue(command.tabComplete(player, "keysadmin", new String[] {"chance"}).contains("chances"));
        assertEquals(List.of("legendary"), command.tabComplete(player, "keysadmin", new String[] {"chances", "leg"}));
        assertEquals(List.of("mining"), command.tabComplete(player, "keysadmin", new String[] {"chances", "legendary", "mi"}));
        player.performCommand("keysadmin chance legendary mining 0.123"); assertEquals(0.123, saved());
        player.setOp(false); assertTrue(command.tabComplete(player, "keysadmin", new String[] {"chances", ""}).isEmpty());
    }
}
