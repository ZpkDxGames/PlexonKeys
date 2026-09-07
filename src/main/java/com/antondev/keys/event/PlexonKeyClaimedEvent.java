package com.antondev.keys.event;

import com.antondev.keys.model.KeyTier;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Post-success event fired after virtual debit and physical key delivery both commit. */
public final class PlexonKeyClaimedEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final KeyTier tier;
    private final long amount;
    private final String source;
    private final String eventId;
    private final String transactionId;

    public PlexonKeyClaimedEvent(
            Player player,
            KeyTier tier,
            long amount,
            String source,
            String eventId,
            String transactionId) {
        super(Objects.requireNonNull(player, "player"));
        this.tier = Objects.requireNonNull(tier, "tier");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        this.amount = amount;
        this.source = requireText(source, "source");
        this.eventId = requireText(eventId, "eventId");
        this.transactionId = requireText(transactionId, "transactionId");
    }

    public Player player() { return getPlayer(); }
    public String category() { return tier.id(); }
    public String getCategory() { return category(); }
    public KeyTier tier() { return tier; }
    public KeyTier getTier() { return tier; }
    public long amount() { return amount; }
    public long getAmount() { return amount; }
    public String source() { return source; }
    public String getSource() { return source; }
    public String eventId() { return eventId; }
    public String getEventId() { return eventId; }
    public String transactionId() { return transactionId; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
