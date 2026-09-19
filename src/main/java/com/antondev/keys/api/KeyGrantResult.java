package com.antondev.keys.api;

import java.util.Objects;

/** Result of one durable idempotent external key grant. */
public record KeyGrantResult(
        Status status,
        String transactionId,
        String keyId,
        long requested,
        long credited,
        long balance) {
    public enum Status { SUCCESS, DUPLICATE, PERSISTENCE_UNCERTAIN }

    public KeyGrantResult {
        Objects.requireNonNull(status, "status");
        if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("transactionId must not be blank");
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("keyId must not be blank");
        if (requested <= 0 || credited < 0 || balance < 0) throw new IllegalArgumentException("invalid grant amounts");
    }

    public boolean success() { return status == Status.SUCCESS || status == Status.DUPLICATE; }
}
