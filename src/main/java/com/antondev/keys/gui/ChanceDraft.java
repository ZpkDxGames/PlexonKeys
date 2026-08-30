package com.antondev.keys.gui;

import com.antondev.keys.model.Activity;
import java.math.BigDecimal;

/** One menu's unsaved percentage. No settings, files, or repeating tasks are touched while adjusting it. */
final class ChanceDraft {
    private static final BigDecimal MAX = BigDecimal.valueOf(100);
    final Activity activity;
    final KeyMenu previous;
    private final BigDecimal original;
    private BigDecimal value;

    ChanceDraft(Activity activity, double saved, KeyMenu previous) {
        this.activity = activity; this.previous = previous;
        original = BigDecimal.valueOf(saved); value = original;
    }
    void adjust(BigDecimal amount) { value = canonical(value.add(amount).max(BigDecimal.ZERO).min(MAX)); }
    void never() { value = BigDecimal.ZERO; }
    void always() { value = MAX; }
    void reset() { value = original; }
    void exact(String input) {
        BigDecimal parsed = new BigDecimal(input);
        double number = parsed.doubleValue();
        if (parsed.signum() < 0 || parsed.compareTo(MAX) > 0 || !Double.isFinite(number)
                || (parsed.signum() > 0 && number == 0))
            throw new IllegalArgumentException("Enter a representable percentage from 0 to 100");
        value = canonical(parsed);
    }
    boolean changed() { return value.compareTo(original) != 0; }
    String valueText() { return format(value); }
    String originalText() { return format(original); }
    // Settings and drop rolls use doubles. Normalize only after decimal arithmetic so the preview
    // matches the value that can actually be saved, without accumulating binary addition errors.
    private static BigDecimal canonical(BigDecimal value) { return BigDecimal.valueOf(value.doubleValue()); }
    private static String format(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
