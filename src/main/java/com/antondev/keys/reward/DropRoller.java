package com.antondev.keys.reward;

import java.util.function.DoubleSupplier;

public final class DropRoller {
    private DropRoller() {}
    public static boolean wins(double percent, DoubleSupplier random) {
        if (!Double.isFinite(percent) || percent < 0 || percent > 100) throw new IllegalArgumentException("Invalid percentage");
        if (percent == 0) return false;
        return percent == 100 || random.getAsDouble() < percent / 100.0;
    }
}
