package com.antondev.keys;

import com.antondev.keys.activity.ProvenancePolicy;
import com.antondev.keys.api.AcquisitionPreview;
import com.antondev.keys.model.Activity;
import com.antondev.keys.model.KeyTier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class Phase2ProvenanceAndDryRunTest extends PluginTestBase {
    @Test void conservativeProvenanceRejectsUnknownAndSpawnerOriginsByDefault() {
        ProvenancePolicy policy = new ProvenancePolicy(plugin);
        assertTrue(policy.evaluate(Activity.MOBS, ProvenancePolicy.Origin.NATURAL).eligible());
        assertFalse(policy.evaluate(Activity.MOBS, ProvenancePolicy.Origin.PLEXON_SPAWNERS).eligible());
        assertFalse(policy.evaluate(Activity.MOBS, ProvenancePolicy.Origin.EXTERNAL_SPAWNER).eligible());
        assertFalse(policy.evaluate(Activity.MOBS, ProvenancePolicy.Origin.UNKNOWN).eligible());
        assertFalse(policy.evaluate(Activity.MINING, ProvenancePolicy.Origin.PLAYER_PLACED).eligible());
    }

    @Test void optionalUnknownMobOriginRequiresExplicitOptIn() throws Exception {
        ProvenancePolicy policy = new ProvenancePolicy(plugin);
        assertFalse(policy.evaluate(Activity.MOBS, ProvenancePolicy.Origin.UNKNOWN).eligible());
        plugin.configuration().update(c -> c.set("integrations.spawners.allow-unknown-origin", true));
        plugin.settingsChanged();
        assertTrue(policy.evaluate(Activity.MOBS, ProvenancePolicy.Origin.UNKNOWN).eligible());
    }

    @Test void dryRunNeverMutatesBalanceOrCooldown() throws Exception {
        deterministic();
        long before = plugin.balances().balance(player.getUniqueId(), KeyTier.BASIC);
        AcquisitionPreview first = plugin.rewards().preview(
                player, KeyTier.BASIC, Activity.MINING, ProvenancePolicy.Origin.NATURAL);
        AcquisitionPreview second = plugin.rewards().preview(
                player, KeyTier.BASIC, Activity.MINING, ProvenancePolicy.Origin.NATURAL);
        assertTrue(first.eligible());
        assertTrue(second.eligible(), "read-only preview must not start the activity cooldown");
        assertEquals(100.0d, first.chancePercent());
        assertEquals(before, plugin.balances().balance(player.getUniqueId(), KeyTier.BASIC));
    }

    @Test void dryRunExplainsProvenanceRejectionWithoutGranting() throws Exception {
        deterministic();
        AcquisitionPreview preview = plugin.rewards().preview(
                player, KeyTier.BASIC, Activity.MINING, ProvenancePolicy.Origin.PLAYER_PLACED);
        assertFalse(preview.eligible());
        assertEquals("PROVENANCE_PLAYER_PLACED", preview.reason());
        assertEquals(0, plugin.balances().balance(player.getUniqueId(), KeyTier.BASIC));
    }
}
