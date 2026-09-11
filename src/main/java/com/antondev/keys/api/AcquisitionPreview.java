package com.antondev.keys.api;

import com.antondev.keys.model.Activity;
import java.util.Objects;

/** Read-only acquisition evaluation. A preview never rolls RNG, changes cooldowns, or mutates balances. */
public record AcquisitionPreview(
        String keyId,
        Activity activity,
        String origin,
        boolean eligible,
        double chancePercent,
        long currentBalance,
        String reason) {
    public AcquisitionPreview {
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("keyId must not be blank");
        Objects.requireNonNull(activity, "activity");
        if (origin == null || origin.isBlank()) throw new IllegalArgumentException("origin must not be blank");
        if (!Double.isFinite(chancePercent) || chancePercent < 0 || chancePercent > 100) {
            throw new IllegalArgumentException("chancePercent must be between 0 and 100");
        }
        if (currentBalance < 0) throw new IllegalArgumentException("currentBalance must not be negative");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
