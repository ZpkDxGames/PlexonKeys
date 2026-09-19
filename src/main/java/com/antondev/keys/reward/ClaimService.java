package com.antondev.keys.reward;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.config.Text;
import com.antondev.keys.data.MemoryStore.*;
import com.antondev.keys.event.PlexonKeyClaimedEvent;
import com.antondev.keys.model.KeyTier;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Non-blocking physical-key claim state machine. SQLite durability always precedes physical delivery, while
 * crash reconciliation explicitly distinguishes proven pre-delivery, proven delivered, and ambiguous state.
 */
public final class ClaimService {
    private final PlexonKeys plugin;
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();

    public ClaimService(PlexonKeys plugin) { this.plugin = Objects.requireNonNull(plugin, "plugin"); }

    public void claim(Player player, KeyTier only, boolean one) {
        if (!Bukkit.isPrimaryThread()) {
            runMain(() -> claim(player, only, one));
            return;
        }
        Text text = plugin.settings().text();
        if (!player.hasPermission("plexonkeys.use")) {
            text.send(player, "no-permission");
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!claiming.add(playerId)) return;

        try {
            if (only != null && !plugin.settings().categories().get(only).claimable()) {
                text.send(player, "not-claimable");
                claiming.remove(playerId);
                return;
            }
            Map<KeyTier, Long> balances = plugin.balances().balances(playerId);
            EnumMap<KeyTier, Long> requested = new EnumMap<>(KeyTier.class);
            EnumMap<KeyTier, ItemStack> templates = new EnumMap<>(KeyTier.class);
            for (KeyTier tier : KeyTier.values()) {
                if (only != null && tier != only) continue;
                if (!plugin.settings().categories().get(tier).claimable()) continue;
                long amount = Math.min(balances.getOrDefault(tier, 0L), one ? 1L : Long.MAX_VALUE);
                if (amount > 0) requested.put(tier, amount);
                templates.put(tier, plugin.settings().categories().get(tier).itemCopy());
            }
            if (requested.isEmpty()) {
                text.send(player, "no-keys");
                claiming.remove(playerId);
                return;
            }

            ItemStack[] before = cloneContents(player.getInventory().getStorageContents());
            InventoryDelivery.Plan plan = InventoryDelivery.plan(
                    before, player.getInventory().getMaxStackSize(), templates, requested);
            if (plan.total() == 0) {
                text.send(player, "inventory-full");
                claiming.remove(playerId);
                return;
            }

            String transactionId = UUID.randomUUID().toString();
            String deliveryData = ClaimPayload.encode(plan.delivered(), templates);
            plugin.transactions().reserveClaim(playerId, plan.delivered(), deliveryData, transactionId)
                    .whenComplete((reserved, error) -> {
                        if (error != null) {
                            compensateBeforeDelivery(playerId, transactionId, "reservation persistence failed", error);
                            return;
                        }
                        runMain(() -> afterReserved(playerId, transactionId));
                    });
        } catch (RuntimeException error) {
            claiming.remove(playerId);
            plugin.getLogger().log(Level.SEVERE, "Could not plan physical key claim for " + playerId, error);
            text.send(player, "save-failed");
        }
    }

    /** Startup/login recovery for the one-active-claim-per-player contract. */
    public void recover(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            runMain(() -> recover(player));
            return;
        }
        UUID playerId = player.getUniqueId();
        List<ClaimRecord> unresolved = plugin.data().unresolvedClaims(playerId);
        if (unresolved.isEmpty() || !claiming.add(playerId)) return;
        recoverNext(player, unresolved, 0);
    }

    private void recoverNext(Player player, List<ClaimRecord> claims, int index) {
        if (index >= claims.size()) {
            claiming.remove(player.getUniqueId());
            plugin.menus().refreshPlayer(player);
            return;
        }
        ClaimRecord claim = claims.get(index);
        CompletionStage<?> stage;
        switch (claim.state()) {
            case RESERVED -> stage = plugin.transactions().refundClaim(
                    claim.player(), claim.transactionId(), "startup recovery: reserved before delivery");
            case DELIVERY_PENDING -> {
                String current = InventoryFingerprint.of(player.getInventory().getStorageContents());
                if (current.equals(claim.beforeFingerprint())) {
                    stage = plugin.transactions().refundClaim(
                            claim.player(), claim.transactionId(), "startup recovery: proven pre-delivery inventory");
                } else if (current.equals(claim.afterFingerprint())) {
                    stage = plugin.transactions().updateClaim(
                            claim.player(), claim.transactionId(), ClaimState.COMPLETED,
                            null, null, "startup recovery: exact delivered inventory fingerprint");
                } else {
                    stage = plugin.transactions().updateClaim(
                            claim.player(), claim.transactionId(), ClaimState.UNCERTAIN,
                            null, null, "startup recovery: inventory does not prove pre/post delivery state");
                }
            }
            case UNCERTAIN -> {
                // Explicitly fail closed. An operator can inspect this journal entry; never auto-refund.
                stage = CompletableFuture.completedFuture(claim);
            }
            case COMPLETED, REFUNDED -> stage = CompletableFuture.completedFuture(claim);
            default -> stage = CompletableFuture.failedFuture(new IllegalStateException("Unknown claim state"));
        }
        stage.whenComplete((ignored, error) -> runMain(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Claim recovery failed for " + claim.transactionId(), unwrap(error));
            } else if (claim.state() == ClaimState.DELIVERY_PENDING
                    && plugin.data().claim(claim.transactionId()).map(ClaimRecord::state).orElse(ClaimState.UNCERTAIN)
                    == ClaimState.COMPLETED) {
                publishClaimEvents(player, claim.amounts(), claim.transactionId());
            }
            recoverNext(player, claims, index + 1);
        }));
    }

    private void afterReserved(UUID playerId, String transactionId) {
        Player player = Bukkit.getPlayer(playerId);
        ClaimRecord reserved = plugin.data().claim(transactionId).orElse(null);
        if (player == null || reserved == null || reserved.state() != ClaimState.RESERVED
                || !player.hasPermission("plexonkeys.use")
                || reserved.amounts().keySet().stream().anyMatch(tier -> !plugin.settings().categories().get(tier).claimable())) {
            safeRefund(playerId, transactionId, "pre-delivery revalidation failed");
            return;
        }

        Map<KeyTier, ItemStack> templates;
        try {
            templates = ClaimPayload.templates(reserved.deliveryData());
        } catch (RuntimeException error) {
            safeRefund(playerId, transactionId, "stored delivery payload is invalid");
            return;
        }
        ItemStack[] before = cloneContents(player.getInventory().getStorageContents());
        InventoryDelivery.Plan plan = InventoryDelivery.plan(
                before, player.getInventory().getMaxStackSize(), templates, reserved.amounts());
        if (!plan.delivered().equals(reserved.amounts())) {
            safeRefund(playerId, transactionId, "inventory capacity changed before delivery");
            return;
        }

        String beforeFingerprint = InventoryFingerprint.of(before);
        String afterFingerprint = InventoryFingerprint.of(plan.contents());
        plugin.transactions().updateClaim(playerId, transactionId, ClaimState.DELIVERY_PENDING,
                        beforeFingerprint, afterFingerprint, "")
                .whenComplete((pending, error) -> {
                    if (error != null) {
                        // No physical mutation happened yet, so durable compensation is safe.
                        compensateBeforeDelivery(playerId, transactionId,
                                "delivery-pending persistence failed", error);
                        return;
                    }
                    runMain(() -> deliver(playerId, transactionId, before, plan.contents()));
                });
    }

    private void deliver(UUID playerId, String transactionId, ItemStack[] before, ItemStack[] after) {
        Player player = Bukkit.getPlayer(playerId);
        ClaimRecord claim = plugin.data().claim(transactionId).orElse(null);
        if (player == null || claim == null || claim.state() != ClaimState.DELIVERY_PENDING) {
            safeRefund(playerId, transactionId, "player unavailable before physical delivery");
            return;
        }

        String currentFingerprint = InventoryFingerprint.of(player.getInventory().getStorageContents());
        if (!currentFingerprint.equals(claim.beforeFingerprint())) {
            safeRefund(playerId, transactionId, "inventory changed while delivery was pending");
            return;
        }

        try {
            player.getInventory().setStorageContents(after);
        } catch (RuntimeException deliveryError) {
            reconcileDeliveryException(player, claim, deliveryError);
            return;
        }

        String observed = InventoryFingerprint.of(player.getInventory().getStorageContents());
        if (!observed.equals(claim.afterFingerprint())) {
            markUncertain(playerId, transactionId,
                    "post-delivery inventory fingerprint did not match planned result");
            return;
        }

        plugin.transactions().updateClaim(playerId, transactionId, ClaimState.COMPLETED,
                        null, null, "")
                .whenComplete((completed, error) -> {
                    if (error != null) {
                        // Physical delivery already occurred. Never compensate this failure with virtual keys.
                        plugin.getLogger().log(Level.SEVERE,
                                "Physical key delivery completed but COMPLETED journal persistence is unresolved for "
                                        + transactionId, unwrap(error));
                        runMain(() -> finish(playerId, false, 0L));
                        return;
                    }
                    runMain(() -> {
                        Player online = Bukkit.getPlayer(playerId);
                        if (online != null) {
                            publishClaimEvents(online, completed.amounts(), transactionId);
                            long remaining = plugin.balances().balances(playerId).values().stream()
                                    .mapToLong(Long::longValue).sum();
                            plugin.settings().text().send(online, "claimed",
                                    Text.value("amount", completed.amounts().values().stream().mapToLong(Long::longValue).sum()),
                                    Text.value("remaining", remaining));
                            plugin.menus().refreshPlayer(online);
                        }
                        finish(playerId, true, 0L);
                    });
                });
    }

    private void reconcileDeliveryException(Player player, ClaimRecord claim, RuntimeException error) {
        String observed = InventoryFingerprint.of(player.getInventory().getStorageContents());
        if (observed.equals(claim.beforeFingerprint())) {
            plugin.getLogger().log(Level.WARNING,
                    "Physical delivery threw before inventory mutation; refunding " + claim.transactionId(), error);
            safeRefund(claim.player(), claim.transactionId(), "delivery exception with proven pre-delivery inventory");
        } else if (observed.equals(claim.afterFingerprint())) {
            plugin.getLogger().log(Level.WARNING,
                    "Physical delivery threw but exact planned inventory is present; completing " + claim.transactionId(), error);
            plugin.transactions().updateClaim(claim.player(), claim.transactionId(), ClaimState.COMPLETED,
                            null, null, "delivery threw after exact planned inventory became visible")
                    .whenComplete((completed, persistError) -> runMain(() -> {
                        if (persistError == null && player.isOnline()) {
                            publishClaimEvents(player, completed.amounts(), claim.transactionId());
                        }
                        finish(claim.player(), persistError == null, 0L);
                    }));
        } else {
            plugin.getLogger().log(Level.SEVERE,
                    "Physical delivery outcome is ambiguous; claim will not be auto-refunded " + claim.transactionId(), error);
            markUncertain(claim.player(), claim.transactionId(), "delivery exception with ambiguous inventory state");
        }
    }

    private void compensateBeforeDelivery(UUID playerId, String transactionId, String reason, Throwable cause) {
        plugin.getLogger().log(Level.SEVERE,
                "Physical claim did not reach delivery and requires durable compensation: " + transactionId,
                unwrap(cause));
        if (plugin.data().claim(transactionId).isEmpty()) {
            runMain(() -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) plugin.settings().text().send(player, "save-failed");
                finish(playerId, false, 0L);
            });
            return;
        }
        safeRefund(playerId, transactionId, reason);
    }

    private void safeRefund(UUID playerId, String transactionId, String reason) {
        ClaimRecord current = plugin.data().claim(transactionId).orElse(null);
        if (current == null) {
            finish(playerId, false, 0L);
            return;
        }
        if (current.state() == ClaimState.DELIVERY_PENDING) {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                String observed = InventoryFingerprint.of(online.getInventory().getStorageContents());
                if (!current.beforeFingerprint().isBlank() && !observed.equals(current.beforeFingerprint())) {
                    markUncertain(playerId, transactionId,
                            reason + "; inventory no longer proves pre-delivery state");
                    return;
                }
            }
        }
        plugin.transactions().refundClaim(playerId, transactionId, reason)
                .whenComplete((refunded, error) -> runMain(() -> {
                    Player online = Bukkit.getPlayer(playerId);
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not persist safe claim refund " + transactionId, unwrap(error));
                        if (online != null) plugin.settings().text().send(online, "save-failed");
                    } else if (online != null) {
                        plugin.menus().refreshPlayer(online);
                    }
                    finish(playerId, error == null, 0L);
                }));
    }

    private void markUncertain(UUID playerId, String transactionId, String reason) {
        plugin.transactions().updateClaim(playerId, transactionId, ClaimState.UNCERTAIN,
                        null, null, reason)
                .whenComplete((ignored, error) -> runMain(() -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not persist UNCERTAIN claim state " + transactionId, unwrap(error));
                    }
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) plugin.settings().text().send(player, "save-failed");
                    finish(playerId, false, 0L);
                }));
    }

    private void finish(UUID playerId, boolean success, long ignored) {
        claiming.remove(playerId);
    }

    private void publishClaimEvents(Player player, Map<KeyTier, Long> delivered, String transactionId) {
        for (KeyTier tier : RewardService.HIGHEST_FIRST) {
            long amount = delivered.getOrDefault(tier, 0L);
            if (amount <= 0) continue;
            String eventId = transactionId + ":" + tier.id();
            try {
                Bukkit.getPluginManager().callEvent(new PlexonKeyClaimedEvent(
                        player, tier, amount, "player-claim", eventId, transactionId));
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING,
                        "A PlexonKeyClaimedEvent listener failed after key delivery committed", error);
            }
        }
    }

    private static ItemStack[] cloneContents(ItemStack[] source) {
        return Arrays.stream(source).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }

    private void runMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule PlexonKeys claim callback", error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException || error instanceof ExecutionException) {
            return error.getCause() == null ? error : error.getCause();
        }
        return error;
    }
}
