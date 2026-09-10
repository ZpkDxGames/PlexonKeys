package com.antondev.keys.event;

import com.antondev.keys.model.KeyTier;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Synchronous post-commit event for a successful authoritative virtual-key consume. */
public final class PlexonKeyConsumedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final KeyTier tier;
    private final long amount;
    private final String transactionId;

    public PlexonKeyConsumedEvent(UUID playerId, KeyTier tier, long amount, String transactionId) {
        super(false);
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.tier = Objects.requireNonNull(tier, "tier");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("transactionId must not be blank");
        this.amount = amount;
        this.transactionId = transactionId;
    }

    public UUID playerId() { return playerId; }
    public KeyTier tier() { return tier; }
    public long amount() { return amount; }
    public String transactionId() { return transactionId; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
