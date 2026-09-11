package com.antondev.keys;

import com.antondev.keys.model.KeyTier;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Phase3CheckpointRuntimeTest extends PluginTestBase {
    @Test
    void legacyZeroMigratesToSafeIntervalAndReloadKeepsOneScheduler() throws Exception {
        plugin.configuration().update(c -> c.set("storage.checkpoint-seconds", 0));
        plugin.settingsChanged();

        assertTrue(plugin.settings().checkpointsEnabled());
        assertEquals(60, plugin.settings().checkpointSeconds());
        assertEquals(1, pluginOwnedPendingTasks());

        plugin.settingsChanged();
        assertEquals(1, pluginOwnedPendingTasks(), "reload must cancel the old checkpoint before creating another");
    }

    @Test
    void checkpointCanOnlyBeDisabledByExplicitSwitch() throws Exception {
        plugin.configuration().update(c -> c.set("storage.checkpoints.enabled", false));
        plugin.settingsChanged();

        assertFalse(plugin.settings().checkpointsEnabled());
        assertEquals(0, pluginOwnedPendingTasks());
    }

    @Test
    void periodicCheckpointPersistsDirtyBalanceWithoutPerEventIo() throws Exception {
        plugin.data().set(player.getUniqueId(), KeyTier.RARE, 9);
        assertTrue(plugin.data().dirtyCount() > 0);

        server.getScheduler().performTicks(plugin.settings().checkpointSeconds() * 20L);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (plugin.data().dirtyCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);

        assertEquals(0, plugin.data().dirtyCount(), "scheduled checkpoint should acknowledge dirty state");
    }

    private long pluginOwnedPendingTasks() {
        return Bukkit.getScheduler().getPendingTasks().stream()
                .filter(task -> task.getOwner().equals(plugin))
                .count();
    }
}
