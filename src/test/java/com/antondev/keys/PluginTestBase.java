package com.antondev.keys;

import com.antondev.keys.model.*;
import org.bukkit.GameMode;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(RequiredCoverage.class)
abstract class PluginTestBase {
    protected ServerMock server;
    protected PlexonKeys plugin;
    protected PlayerMock player;
    @BeforeEach void start() {
        server = MockBukkit.mock(); plugin = MockBukkit.load(PlexonKeys.class);
        assertTrue(plugin.isEnabled(), "Plugin must start successfully against the Paper 26.2 mock");
        player = server.addPlayer("Tonim"); player.setGameMode(GameMode.SURVIVAL);
    }
    @AfterEach void stop() { MockBukkit.unmock(); }
    protected org.bukkit.event.inventory.InventoryClickEvent click(int slot) { return click(org.bukkit.event.inventory.ClickType.LEFT, slot); }
    protected org.bukkit.event.inventory.InventoryClickEvent click(org.bukkit.event.inventory.ClickType type, int slot) {
        var event = new org.bukkit.event.inventory.InventoryClickEvent(player.getOpenInventory(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                slot, type, type.isShiftClick() ? org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY : org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event); return event;
    }
    protected void deterministic() throws Exception {
        plugin.configuration().update(c -> {
            for (KeyTier tier : KeyTier.values()) {
                for (Activity activity : Activity.values()) c.set("categories." + tier.id() + ".chances." + activity.id(), tier == KeyTier.BASIC ? 100 : 0);
                c.set("categories." + tier.id() + ".announce.enabled", false);
            }
            c.set("notifications.sound", ""); c.set("activities.mining.require-drops", false);
        });
        plugin.settingsChanged();
    }
}
