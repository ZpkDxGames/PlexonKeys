package com.antondev.keys;

import com.antondev.keys.api.KeyConsumeResult;
import com.antondev.keys.api.PlexonKeysApiImpl;
import com.antondev.keys.model.KeyTier;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class Phase2ApiAndBalanceTest extends PluginTestBase {
    @Test void exactOnceConsumeNeverDebitsTwice() {
        plugin.balances().grantAdmin(player.getUniqueId(), KeyTier.BASIC, 5);
        KeyConsumeResult first = plugin.balances().consume(player.getUniqueId(), KeyTier.BASIC, 2, "crate-open-1");
        assertEquals(KeyConsumeResult.Status.SUCCESS, first.status());
        assertEquals(2, first.consumed());
        assertEquals(3, first.balance());

        KeyConsumeResult duplicate = plugin.balances().consume(player.getUniqueId(), KeyTier.BASIC, 2, "crate-open-1");
        assertEquals(KeyConsumeResult.Status.DUPLICATE, duplicate.status());
        assertEquals(0, duplicate.consumed());
        assertEquals(3, plugin.balances().balance(player.getUniqueId(), KeyTier.BASIC));
    }

    @Test void insufficientConsumeIsIdempotentlyRejected() {
        plugin.balances().grantAdmin(player.getUniqueId(), KeyTier.RARE, 1);
        KeyConsumeResult first = plugin.balances().consume(player.getUniqueId(), KeyTier.RARE, 2, "crate-open-2");
        assertEquals(KeyConsumeResult.Status.INSUFFICIENT, first.status());
        assertEquals(1, plugin.balances().balance(player.getUniqueId(), KeyTier.RARE));
        assertEquals(KeyConsumeResult.Status.DUPLICATE,
                plugin.balances().consume(player.getUniqueId(), KeyTier.RARE, 2, "crate-open-2").status());
        assertEquals(1, plugin.balances().balance(player.getUniqueId(), KeyTier.RARE));
    }

    @Test void transactionIdCannotBeReusedForDifferentRequest() {
        plugin.balances().grantAdmin(player.getUniqueId(), KeyTier.BASIC, 5);
        plugin.balances().consume(player.getUniqueId(), KeyTier.BASIC, 1, "same-id");
        assertThrows(IllegalArgumentException.class,
                () -> plugin.balances().consume(player.getUniqueId(), KeyTier.BASIC, 2, "same-id"));
    }

    @Test void definitionsExposeStableIdsAndImmutableViews() {
        var api = new PlexonKeysApiImpl(plugin, plugin.balances());
        var definitions = api.keyDefinitions();
        assertEquals(4, definitions.size());
        assertTrue(definitions.containsKey("basic"));
        assertEquals("basic", definitions.get("basic").id());
        assertThrows(UnsupportedOperationException.class, () -> definitions.clear());
    }

    @Test void physicalIdentityUsesExactItemMetadataNotDisplayTextAlone() {
        var api = new PlexonKeysApiImpl(plugin, plugin.balances());
        var template = api.keyTemplate(KeyTier.BASIC).orElseThrow();
        assertEquals("basic", api.identifyPhysicalKey(template).orElseThrow());

        var spoof = template.clone();
        var meta = spoof.getItemMeta();
        meta.displayName(Component.text("definitely-spoofed-key"));
        spoof.setItemMeta(meta);
        assertTrue(api.identifyPhysicalKey(spoof).isEmpty());
    }
}
