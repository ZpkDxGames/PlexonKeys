package com.antondev.keys.event;

import com.antondev.keys.api.KeySource;
import com.antondev.keys.model.KeyTier;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** UUID-safe post-commit event for a durable external grant. */
public final class PlexonKeyGrantedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final KeyTier tier;
    private final long amount;
    private final KeySource source;
    private final String transactionId;
    private final long resultingBalance;

    public PlexonKeyGrantedEvent(UUID playerId, KeyTier tier, long amount, KeySource source,
                                 String transactionId, long resultingBalance) {
        super(false);
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.tier = Objects.requireNonNull(tier, "tier");
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        this.amount = amount;
        this.source = Objects.requireNonNull(source, "source");
        if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("transactionId must not be blank");
        this.transactionId = transactionId;
        if (resultingBalance < 0) throw new IllegalArgumentException("resultingBalance must not be negative");
        this.resultingBalance = resultingBalance;
    }

    public UUID playerId() { return playerId; }
    public KeyTier tier() { return tier; }
    public long amount() { return amount; }
    public KeySource source() { return source; }
    public String transactionId() { return transactionId; }
    public long resultingBalance() { return resultingBalance; }
    public Optional<Player> onlinePlayer() { return Optional.ofNullable(Bukkit.getPlayer(playerId)); }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
