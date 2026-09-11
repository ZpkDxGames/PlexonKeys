package com.antondev.keys;

import com.antondev.keys.config.Text;
import com.antondev.keys.data.SqliteStore;
import com.antondev.keys.model.KeyTier;
import java.nio.file.*;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest extends PluginTestBase {
    @Test void defaultModeUsesRecommendedPeriodicCheckpoint() {
        assertEquals(60, plugin.settings().checkpointSeconds());
        assertTrue(server.getScheduler().getPendingTasks().stream().anyMatch(task -> task.getOwner().equals(plugin)));
        assertEquals(2048, plugin.settings().yaml().getInt("storage.pressure-dirty-threshold"));
        assertEquals(4096, plugin.settings().yaml().getInt("storage.maximum-snapshot-records"));
        assertEquals(15, plugin.settings().yaml().getInt("storage.shutdown-timeout-seconds"));
        assertEquals(2, plugin.settings().yaml().getInt("performance.menu-refresh-ticks"));
    }

    @Test void invalidChanceAndOverlappingSlotsDoNotTouchActiveConfigOrFile() throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml"); String before = Files.readString(file);
        long revision = plugin.configuration().revision();
        assertThrows(IllegalArgumentException.class, () -> plugin.configuration().set("categories.basic.chances.mining", "101"));
        assertThrows(IllegalArgumentException.class, () -> plugin.configuration().set("categories.basic.chances.mining", ".NaN"));
        assertThrows(IllegalArgumentException.class, () -> plugin.configuration().set("categories.basic.menu.icon-slot", "12"));
        assertEquals(before, Files.readString(file)); assertEquals(revision, plugin.configuration().revision());
    }

    @Test void worldListsAreCaseInsensitiveAndChangesPersist() throws Exception {
        plugin.configuration().set("settings.worlds", "[" + player.getWorld().getName().toUpperCase(java.util.Locale.ROOT) + "]");
        plugin.configuration().reload(); assertTrue(plugin.settings().allowsWorld(player.getWorld()));
        assertThrows(IllegalArgumentException.class, () -> plugin.configuration().set("settings.worlds", "[123]"));
    }

    @Test void invalidYamlReloadRetainsWorkingSettings() throws Exception {
        var before = plugin.settings();
        Files.writeString(plugin.getDataFolder().toPath().resolve("config.yml"), "settings: [invalid: [broken");
        assertThrows(Exception.class, () -> plugin.configuration().reload()); assertSame(before, plugin.settings());
    }

    @Test void failedExternalRuntimeReloadRestoresLastGoodRuntimeButLeavesEditedFileForCorrection() throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        var beforeSettings = plugin.settings();
        long beforeRevision = plugin.configuration().revision();
        long beforeTasks = pluginOwnedPendingTasks();

        YamlConfiguration edited = YamlConfiguration.loadConfiguration(file.toFile());
        edited.set("core-runtime.mode", "CORE");
        edited.save(file.toFile());
        String externalCandidate = Files.readString(file);

        assertFalse(plugin.reloadFor(player), "forced Core mode must fail when the Core Runtime gateway is unavailable");
        assertSame(beforeSettings, plugin.settings(), "last runtime-accepted Settings object must be restored");
        assertTrue(plugin.configuration().revision() >= beforeRevision + 2,
                "candidate + rollback must advance revision monotonically");
        assertEquals(beforeTasks, pluginOwnedPendingTasks(), "rollback must not leak checkpoint tasks");
        assertEquals(externalCandidate, Files.readString(file),
                "external config edits must remain on disk for the administrator to correct");
        assertThrows(IllegalStateException.class,
                () -> plugin.configuration().set("settings.enabled", "false"),
                "in-game edits must refuse to overwrite the still-unapplied external file");
    }

    @Test void failedInternalRuntimeMutationRestoresSettingsDiskAndCheckpointTask() throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        String beforeDisk = Files.readString(file);
        var beforeSettings = plugin.settings();
        long beforeRevision = plugin.configuration().revision();
        long beforeTasks = pluginOwnedPendingTasks();

        assertThrows(IllegalStateException.class, () -> {
            plugin.configuration().set("core-runtime.mode", "CORE");
            plugin.settingsChanged();
        });

        assertSame(beforeSettings, plugin.settings(), "failed in-game mutation must restore last-good settings");
        assertEquals(beforeDisk, Files.readString(file), "failed in-game mutation must restore exact config.yml bytes");
        assertTrue(plugin.configuration().revision() >= beforeRevision + 2,
                "candidate + rollback must keep revision monotonic");
        assertEquals(beforeTasks, pluginOwnedPendingTasks(), "rollback must leave exactly the prior checkpoint schedule");

        plugin.configuration().set("settings.enabled", "false");
        plugin.settingsChanged();
        assertFalse(plugin.settings().enabled(), "subsequent valid edits must still apply after rollback");
    }

    @Test void promptStringsAndUntrustedPlaceholdersDoNotBecomeMiniMessageTags() {
        Component result = Text.parse("<gray>Player: <player></gray>", Text.value("player", "<red>injected</red>"));
        assertEquals("Player: <red>injected</red>", PlainTextComponentSerializer.plainText().serialize(result));
    }

    @Test void capturedItemPreservesMetadataAcrossConfigReloadAndClaim() throws Exception {
        player.setOp(true);
        ItemStack original = new ItemStack(Material.TRIPWIRE_HOOK, 7);
        original.editMeta(meta -> {
            meta.displayName(Text.parse("<gradient:#AACCFF:#FFFFFF>External Crate Key</gradient>"));
            meta.lore(List.of(Text.parse("<gold>Exact third-party lore</gold>")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(new NamespacedKey("externalcrate", "key_id"), PersistentDataType.STRING, "vault-legendary");
        });
        player.getInventory().setItemInMainHand(original);
        plugin.configuration().capture(KeyTier.LEGENDARY, player.getInventory().getItemInMainHand());
        plugin.configuration().reload();
        assertEquals(7, player.getInventory().getItemInMainHand().getAmount());
        ItemStack restored = plugin.settings().categories().get(KeyTier.LEGENDARY).itemCopy();
        assertTrue(original.isSimilar(restored)); assertEquals(1, restored.getAmount());
        player.getInventory().clear(); plugin.data().set(player.getUniqueId(), KeyTier.LEGENDARY, 1);
        plugin.claims().claim(player, KeyTier.LEGENDARY, false);
        assertTrue(original.isSimilar(player.getInventory().getItem(0)));
    }

    @Test void airCaptureCannotEraseConfiguredKey() {
        ItemStack before = plugin.settings().categories().get(KeyTier.BASIC).itemCopy();
        assertThrows(IllegalArgumentException.class, () -> plugin.configuration().capture(KeyTier.BASIC, new ItemStack(Material.AIR)));
        assertTrue(before.isSimilar(plugin.settings().categories().get(KeyTier.BASIC).itemCopy()));
    }

    @Test void shutdownActuallyWritesDatabase() throws Exception {
        plugin.data().set(player.getUniqueId(), KeyTier.EPIC, 23);
        Path db = plugin.getDataFolder().toPath().resolve("plexonkeys.db");
        server.getPluginManager().disablePlugin(plugin);
        assertEquals(23, new SqliteStore(db).load().balance(player.getUniqueId(), KeyTier.EPIC));
    }

    @Test void adminGuiToggleChangesFileImmediately() {
        player.setOp(true); player.performCommand("keysadmin"); click(31); server.getScheduler().performOneTick();
        assertFalse(plugin.settings().enabled());
        assertTrue(player.getOpenInventory().getTopInventory().getHolder() instanceof com.antondev.keys.gui.KeyMenu);
    }

    @Test void guiEditsCannotSilentlyOverwriteAnExternalFileEdit() throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        String edited = Files.readString(file) + "\n# Admin changed this file externally\n"; Files.writeString(file, edited);
        assertThrows(IllegalStateException.class, () -> plugin.configuration().set("settings.enabled", "false"));
        assertEquals(edited, Files.readString(file));
        plugin.configuration().reload(); plugin.configuration().set("settings.enabled", "false"); assertFalse(plugin.settings().enabled());
    }

    @Test void malformedSectionOrSoundCannotBecomeActive() {
        assertThrows(IllegalArgumentException.class, () -> plugin.configuration().update(c -> c.set("settings", false)));
        assertThrows(IllegalArgumentException.class, () -> plugin.configuration().set("notifications.sound", "invalid sound key"));
        assertTrue(plugin.settings().enabled());
    }

    private long pluginOwnedPendingTasks() {
        return server.getScheduler().getPendingTasks().stream().filter(task -> task.getOwner().equals(plugin)).count();
    }
}
