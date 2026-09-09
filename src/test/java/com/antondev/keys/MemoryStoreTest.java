package com.antondev.keys;

import com.antondev.keys.data.MemoryStore;
import com.antondev.keys.data.MemoryStore.Position;
import com.antondev.keys.model.KeyTier;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {
    @Test void creditCapsWithoutOverflowAndDebitsAtomically() {
        var store = new MemoryStore(); var id = UUID.randomUUID();
        assertEquals(10, store.credit(id, KeyTier.BASIC, 50, 10));
        assertEquals(0, store.credit(id, KeyTier.BASIC, 10, 5));
        assertFalse(store.debit(id, Map.of(KeyTier.BASIC, 2L, KeyTier.RARE, 1L)));
        assertEquals(10, store.balance(id, KeyTier.BASIC));
        assertTrue(store.debit(id, Map.of(KeyTier.BASIC, 10L)));
        assertEquals(0, store.balance(id, KeyTier.BASIC));
        assertThrows(IllegalArgumentException.class, () -> store.credit(id, KeyTier.BASIC, Long.MAX_VALUE, MemoryStore.HARD_LIMIT));
        assertThrows(IllegalArgumentException.class, () -> store.set(id, KeyTier.BASIC, -1));
    }

    @Test void multiTierDebitAndRestoreEachUseOneAccountVersion() {
        var store = new MemoryStore(); var id = UUID.randomUUID();
        store.set(id, KeyTier.BASIC, 10); store.set(id, KeyTier.RARE, 5); store.acknowledge(store.snapshot());
        long beforeDebit = store.revision();
        assertTrue(store.debit(id, Map.of(KeyTier.BASIC, 4L, KeyTier.RARE, 2L)));
        assertEquals(beforeDebit + 1, store.revision());
        assertEquals(6, store.balance(id, KeyTier.BASIC)); assertEquals(3, store.balance(id, KeyTier.RARE));
        store.acknowledge(store.snapshot());
        long beforeRestore = store.revision();
        store.restore(id, Map.of(KeyTier.BASIC, 4L, KeyTier.RARE, 2L));
        assertEquals(beforeRestore + 1, store.revision());
        assertEquals(10, store.balance(id, KeyTier.BASIC)); assertEquals(5, store.balance(id, KeyTier.RARE));
    }

    @Test void creditCanRefreshNameAndBalanceInOneMutation() {
        var store = new MemoryStore(); var id = UUID.randomUUID();
        long before = store.revision();
        var mutation = store.credit(id, "Tonim", KeyTier.EPIC, 3, 100);
        assertEquals(before + 1, store.revision());
        assertEquals(3, mutation.delta()); assertEquals(3, mutation.current());
        assertEquals(id, store.findPlayer("tonim").orElseThrow());
    }

    @Test void acknowledgementCannotEraseEditsMadeDuringSave() {
        var store = new MemoryStore(); var id = UUID.randomUUID(); var pos = Position.of(UUID.randomUUID(), -4, -64, 100);
        store.credit(id, KeyTier.RARE, 2, 100); store.mark(pos);
        var old = store.snapshot();
        store.credit(id, KeyTier.RARE, 3, 100); store.unmark(pos);
        store.acknowledge(old);
        assertEquals(2, store.dirtyCount());
        var current = store.snapshot();
        assertEquals(5, current.accounts().get(id).value().rare());
        assertFalse(current.blocks().get(pos).value());
        store.acknowledge(current); assertEquals(0, store.dirtyCount());
    }

    @Test void boundedSnapshotsDrainWithoutLosingDirtyVersions() {
        var store = new MemoryStore(); UUID world = UUID.randomUUID();
        List<Position> positions = new ArrayList<>();
        for (int x = 0; x < 7; x++) positions.add(Position.of(world, x, 64, 0));
        assertEquals(7, store.markAll(positions));
        long target = store.revision();
        int saved = 0;
        while (store.dirtyCount() > 0) {
            var batch = store.snapshot(2, target);
            assertTrue(batch.size() <= 2); assertFalse(batch.empty());
            saved += batch.size(); store.acknowledge(batch);
        }
        assertEquals(7, saved); assertEquals(7, store.blockCount());
    }

    @Test void naturalBreaksDoNotCreateDirtyRecords() {
        var store = new MemoryStore(); var pos = Position.of(UUID.randomUUID(), 0, 70, 0);
        assertFalse(store.unmark(pos)); assertEquals(0, store.dirtyCount());
    }

    @Test void pistonMovementPreservesAdjacentProvenanceBothDirections() {
        var store = new MemoryStore(); var world = UUID.randomUUID();
        var a = Position.of(world, 1, 60, 0); var b = Position.of(world, 2, 60, 0); var c = Position.of(world, 3, 60, 0);
        store.mark(a);
        store.move(Map.of(a, b, b, c));
        assertFalse(store.artificial(a)); assertTrue(store.artificial(b)); assertFalse(store.artificial(c));
        store.move(Map.of(b, a, c, b));
        assertTrue(store.artificial(a)); assertFalse(store.artificial(b)); assertEquals(1, store.blockCount());
    }

    @Test void coordinatesDoNotCollideAtNegativeHeightsOrAcrossWorlds() {
        UUID world = UUID.randomUUID();
        Set<Position> positions = new HashSet<>();
        for (int x : new int[]{-30_000_000, -1, 0, 1, 30_000_000})
            for (int y : new int[]{-2048, -64, -1, 0, 320, 2047})
                for (int z : new int[]{-30_000_000, -1, 0, 1, 30_000_000}) assertTrue(positions.add(Position.of(world, x, y, z)));
        assertNotEquals(Position.of(world, 0, 0, 0), Position.of(UUID.randomUUID(), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Position.of(world, 0, 2048, 0));
    }

    @Test void knownPlayersResolveLocallyAndNamesCanChange() {
        var store = new MemoryStore(); var id = UUID.randomUUID();
        store.remember(id, "Tonim"); store.set(id, KeyTier.EPIC, 3); store.remember(id, "NewName");
        assertTrue(store.findPlayer("Tonim").isEmpty()); assertEquals(id, store.findPlayer("newname").orElseThrow());
        assertEquals(id, store.findPlayer(id.toString()).orElseThrow()); assertEquals(3, store.balance(id, KeyTier.EPIC));
        assertEquals(List.of("NewName"), store.names()); assertSame(store.names(), store.names());
    }
}
