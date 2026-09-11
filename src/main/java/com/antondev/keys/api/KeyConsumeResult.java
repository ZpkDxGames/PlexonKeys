package com.antondev.keys.api;

import java.util.Objects;

/** Result of an idempotent authoritative virtual-key consume request. */
public record KeyConsumeResult(
        Status status,
        String transactionId,
        String keyId,
        long requested,
        long consumed,
        long balance) {
    public enum Status { SUCCESS, INSUFFICIENT, DUPLICATE }

    public KeyConsumeResult {
        Objects.requireNonNull(status, "status");
        if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("transactionId must not be blank");
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("keyId must not be blank");
        if (requested <= 0) throw new IllegalArgumentException("requested must be positive");
        if (consumed < 0 || balance < 0) throw new IllegalArgumentException("consumed/balance must not be negative");
    }

    public boolean success() { return status == Status.SUCCESS; }
}
