package com.antondev.keys;

import com.antondev.keys.api.*;
import com.antondev.keys.event.*;
import com.antondev.keys.model.KeyTier;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreMigrationTest extends PluginTestBase {
    private static final class Capture implements Listener {
        final List<PlexonKeyEarnedEvent> earned = new ArrayList<>();
        final List<PlexonKeyClaimedEvent> claimed = new ArrayList<>();
        @EventHandler public void earned(PlexonKeyEarnedEvent event) { earned.add(event); }
        @EventHandler public void claimed(PlexonKeyClaimedEvent event) { claimed.add(event); }
    }

    private Capture capture() {
        Capture capture = new Capture();
        server.getPluginManager().registerEvents(capture, plugin);
        return capture;
    }

    @Test void standaloneStartupStillRegistersPublicApi() {
        assertNotNull(plugin.core());
        assertFalse(plugin.core().installed());
        assertEquals("STANDALONE", plugin.core().mode());
        var registration = Bukkit.getServicesManager().getRegistration(PlexonKeysAPI.class);
        assertNotNull(registration);
        assertSame(plugin.api(), registration.getProvider());
    }

    @Test void earnedEventMatchesQuestsReflectionContract() throws Exception {
        Class<?> type = Class.forName("com.antondev.keys.event.PlexonKeyEarnedEvent");
        assertNotNull(type.getMethod("getPlayer"));
        assertNotNull(type.getMethod("category"));
        assertNotNull(type.getMethod("getCategory"));
        assertNotNull(type.getMethod("tier"));
        assertNotNull(type.getMethod("getTier"));
        assertNotNull(type.getMethod("amount"));
        assertNotNull(type.getMethod("getAmount"));
        assertNotNull(type.getMethod("source"));
        assertNotNull(type.getMethod("getSource"));
        assertNotNull(type.getMethod("eventId"));
        assertNotNull(type.getMethod("getEventId"));
        assertNotNull(type.getMethod("transactionId"));
    }

    @Test void claimedEventMatchesQuestsReflectionContract() throws Exception {
        Class<?> type = Class.forName("com.antondev.keys.event.PlexonKeyClaimedEvent");
        assertNotNull(type.getMethod("getPlayer"));
        assertNotNull(type.getMethod("category"));
        assertNotNull(type.getMethod("tier"));
        assertNotNull(type.getMethod("amount"));
        assertNotNull(type.getMethod("source"));
        assertNotNull(type.getMethod("eventId"));
        assertNotNull(type.getMethod("transactionId"));
    }

    @Test void publicGrantEmitsActualCreditedAmountOnce() throws Exception {
        Capture capture = capture();
        plugin.configuration().set("settings.max-virtual-per-category", "2");
        long credited = plugin.api().grant(player.getUniqueId(), KeyTier.BASIC, 5, KeySource.API);
        assertEquals(2, credited);
        assertEquals(2, plugin.api().balance(player.getUniqueId(), KeyTier.BASIC));
        assertEquals(1, capture.earned.size());
        PlexonKeyEarnedEvent event = capture.earned.getFirst();
        assertSame(player, event.getPlayer());
        assertEquals(KeyTier.BASIC, event.tier());
        assertEquals("basic", event.category());
        assertEquals(2, event.amount());
        assertEquals("api", event.source());
        assertFalse(event.eventId().isBlank());
    }

    @Test void capRejectionAndSilentRestoreDoNotEmitEarnedEvents() {
        Capture capture = capture();
        plugin.data().set(player.getUniqueId(), KeyTier.BASIC, plugin.settings().cap());
        assertEquals(0, plugin.balances().grant(player, KeyTier.BASIC, 1, "activity:mining"));
        assertTrue(capture.earned.isEmpty());

        plugin.data().set(player.getUniqueId(), KeyTier.BASIC, 1);
        assertTrue(plugin.balances().debitForClaim(player.getUniqueId(), Map.of(KeyTier.BASIC, 1L)));
        plugin.balances().silentRestore(player.getUniqueId(), Map.of(KeyTier.BASIC, 1L));
        assertEquals(1, plugin.balances().balance(player.getUniqueId(), KeyTier.BASIC));
        assertTrue(capture.earned.isEmpty());
    }

    @Test void claimAllUsesSharedTransactionAndUniqueTierEventIds() {
        Capture capture = capture();
        plugin.data().credit(player.getUniqueId(), KeyTier.BASIC, 3, plugin.settings().cap());
        plugin.data().credit(player.getUniqueId(), KeyTier.RARE, 2, plugin.settings().cap());
        plugin.data().credit(player.getUniqueId(), KeyTier.EPIC, 1, plugin.settings().cap());

        plugin.claims().claim(player, null, false);

        assertEquals(3, capture.claimed.size());
        Set<String> transactions = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        Map<KeyTier, Long> delivered = new EnumMap<>(KeyTier.class);
        for (PlexonKeyClaimedEvent event : capture.claimed) {
            transactions.add(event.transactionId());
            eventIds.add(event.eventId());
            delivered.put(event.tier(), event.amount());
            assertEquals("player-claim", event.source());
            assertTrue(event.eventId().startsWith(event.transactionId() + ":"));
        }
        assertEquals(Set.of("single"), transactions.stream().map(ignored -> "single").collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, transactions.size());
        assertEquals(3, eventIds.size());
        assertEquals(3L, delivered.get(KeyTier.BASIC));
        assertEquals(2L, delivered.get(KeyTier.RARE));
        assertEquals(1L, delivered.get(KeyTier.EPIC));
    }

    @Test void apiReturnsImmutableBalancesAndDefensiveTemplates() {
        Map<KeyTier, Long> balances = plugin.api().balances(player.getUniqueId());
        assertThrows(UnsupportedOperationException.class, () -> balances.put(KeyTier.BASIC, 99L));

        var first = plugin.api().keyTemplate(KeyTier.BASIC).orElseThrow();
        int original = first.getAmount();
        first.setAmount(Math.min(first.getMaxStackSize(), original + 1));
        var second = plugin.api().keyTemplate(KeyTier.BASIC).orElseThrow();
        assertEquals(original, second.getAmount());
    }

    @Test void disablingPluginUnregistersPublicApi() {
        server.getPluginManager().disablePlugin(plugin);
        assertNull(Bukkit.getServicesManager().getRegistration(PlexonKeysAPI.class));
    }
}
