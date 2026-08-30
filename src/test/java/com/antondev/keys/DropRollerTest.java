package com.antondev.keys;

import com.antondev.keys.reward.DropRoller;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DropRollerTest {
    @Test void percentageBoundariesAreExactAndZeroDoesNotRoll() {
        AtomicInteger rolls = new AtomicInteger();
        assertFalse(DropRoller.wins(0, () -> { rolls.incrementAndGet(); return 0; }));
        assertTrue(DropRoller.wins(100, () -> { rolls.incrementAndGet(); return 0.999; }));
        assertEquals(0, rolls.get());
        assertTrue(DropRoller.wins(1, () -> 0.009999)); assertFalse(DropRoller.wins(1, () -> 0.01));
        assertThrows(IllegalArgumentException.class, () -> DropRoller.wins(Double.NaN, () -> 0));
    }
    @Test void rareFractionalPercentagesRemainFractional() {
        Random random = new Random(20260830); int wins = 0;
        for (int n = 0; n < 1_000_000; n++) if (DropRoller.wins(0.05, random::nextDouble)) wins++;
        assertTrue(wins >= 400 && wins <= 600, "0.05% should average 500 per million; got " + wins);
    }
}
