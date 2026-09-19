package com.antondev.keys.api;

import java.util.Objects;
import java.util.UUID;

/** Immutable public view of a critical PlexonKeys transaction. */
public record KeyTransactionView(
        String transactionId,
        Kind kind,
        UUID playerId,
        String keyId,
        long requested,
        long applied,
        String source,
        State state,
        long resultBalance,
        long createdAtEpochMillis,
        String detail) {
    public enum Kind { CONSUME, EXTERNAL_GRANT }
    public enum State { COMMITTED_SUCCESS, COMMITTED_INSUFFICIENT, PERSISTENCE_UNCERTAIN }

    public KeyTransactionView {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(keyId, "keyId");
        source = source == null ? "" : source;
        Objects.requireNonNull(state, "state");
        detail = detail == null ? "" : detail;
    }
}
