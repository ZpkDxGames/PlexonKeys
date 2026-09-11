package com.antondev.keys.reward;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.activity.ProvenancePolicy;
import com.antondev.keys.api.AcquisitionPreview;
import com.antondev.keys.config.*;
import com.antondev.keys.model.*;
import com.antondev.keys.service.KeyBalanceService;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** High-frequency reward path. Configuration-derived roll data is rebuilt only when config revision changes. */
public final class RewardService {
    public static final List<KeyTier> HIGHEST_FIRST = List.of(KeyTier.LEGENDARY, KeyTier.EPIC, KeyTier.RARE, KeyTier.BASIC);

    public record Metrics(
            double seconds,
            long eligibleEvents,
            long ineligibleEvents,
            long rolls,
            long cooldownRejects,
            long capRejects,
            long permissionRejects,
            Map<KeyTier, Long> wins) {
        public double eligiblePerSecond() { return rate(eligibleEvents); }
        public double ineligiblePerSecond() { return rate(ineligibleEvents); }
        public double rollsPerSecond() { return rate(rolls); }
        public double cooldownRejectsPerSecond() { return rate(cooldownRejects); }
        public double capRejectsPerSecond() { return rate(capRejects); }
        public double permissionRejectsPerSecond() { return rate(permissionRejects); }
        private double rate(long value) { return seconds <= 0 ? 0.0 : value / seconds; }
    }

    private record Candidate(KeyTier tier, Settings.Category category, double chance, Component display) {}
    private record ActivityPlan(boolean enabled, long cooldownNanos, String display, List<Candidate> candidates) {}
    private record RewardRuntime(
            Settings settings,
            Map<Activity, ActivityPlan> plans,
            boolean personalChat,
            String sound,
            float soundVolume,
            float soundPitch) {}

    private final PlexonKeys plugin;
    private final ProvenancePolicy provenancePolicy;
    private final Map<UUID, long[]> cooldowns = new HashMap<>();
    private final long metricsStartNanos = System.nanoTime();
    private final long[] wins = new long[KeyTier.values().length];
    private volatile RewardRuntime runtime;
    private volatile long runtimeRevision = Long.MIN_VALUE;
    private long eligibleEvents;
    private long ineligibleEvents;
    private long rolls;
    private long cooldownRejects;
    private long capRejects;
    private long permissionRejects;

    public RewardService(PlexonKeys plugin) {
        this.plugin = plugin;
        this.provenancePolicy = new ProvenancePolicy(plugin);
        rebuild();
    }

    /** Compatibility helper; perform/tryPerform remains the single authoritative eligibility path. */
    public boolean eligible(Player player, Activity activity) {
        RewardRuntime current = current();
        ActivityPlan plan = current.plans().get(activity);
        return current.settings().enabled() && plan.enabled() && !plan.candidates().isEmpty()
                && player.hasPermission("plexonkeys.earn")
                && current.settings().gameModes().contains(player.getGameMode())
                && current.settings().allowsWorld(player.getWorld());
    }

    public boolean activityEnabled(Activity activity) {
        RewardRuntime current = current();
        ActivityPlan plan = current.plans().get(activity);
        return current.settings().enabled() && plan.enabled() && !plan.candidates().isEmpty();
    }

    public void perform(Player player, Activity activity) {
        tryPerform(player, activity);
    }

    /** Validate once, roll pre-indexed candidates, and return whether at least one balance changed. */
    public boolean tryPerform(Player player, Activity activity) {
        RewardRuntime current = current();
        Settings settings = current.settings();
        ActivityPlan plan = current.plans().get(activity);
        if (!settings.enabled() || !plan.enabled() || plan.candidates().isEmpty()) {
            ineligibleEvents++;
            return false;
        }
        if (!player.hasPermission("plexonkeys.earn")) {
            ineligibleEvents++;
            permissionRejects++;
            return false;
        }
        if (!settings.gameModes().contains(player.getGameMode()) || !settings.allowsWorld(player.getWorld())) {
            ineligibleEvents++;
            return false;
        }

        if (plan.cooldownNanos() > 0) {
            long now = System.nanoTime();
            long[] last = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new long[Activity.values().length]);
            long previous = last[activity.ordinal()];
            if (previous != 0 && now - previous < plan.cooldownNanos()) {
                ineligibleEvents++;
                cooldownRejects++;
                return false;
            }
            last[activity.ordinal()] = now;
        }

        eligibleEvents++;
        boolean awarded = false;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Candidate candidate : plan.candidates()) {
            Settings.Category category = candidate.category();
            if (!category.permission().isBlank() && !player.hasPermission(category.permission())) {
                permissionRejects++;
                continue;
            }
            rolls++;
            if (candidate.chance() < 100.0d && random.nextDouble(100.0d) >= candidate.chance()) continue;

            KeyBalanceService.GrantResult result = plugin.balances().grantResult(
                    player, candidate.tier(), 1, "activity:" + activity.id());
            if (result.credited() == 0) {
                capRejects++;
                continue;
            }

            wins[candidate.tier().ordinal()]++;
            awarded = true;
            acquired(player, plan, candidate, current, result);
            if (settings.highestOnly()) break;
        }
        if (awarded) plugin.menus().refreshPlayer(player);
        return awarded;
    }

    /**
     * Evaluate the same immutable runtime plan without RNG, cooldown mutation, balance mutation, feedback, events,
     * persistence dirties, or item delivery. This is safe for administrative diagnostics on the primary thread.
     */
    public AcquisitionPreview preview(Player player, KeyTier tier, Activity activity, ProvenancePolicy.Origin origin) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(origin, "origin");
        RewardRuntime current = current();
        Settings settings = current.settings();
        Settings.Category category = settings.categories().get(tier);
        ActivityPlan plan = current.plans().get(activity);
        double chance = category.chances().getOrDefault(activity, 0.0d);
        long balance = plugin.balances().balance(player.getUniqueId(), tier);

        if (!settings.enabled()) return preview(tier, activity, origin, false, chance, balance, "PLUGIN_DISABLED");
        if (!plan.enabled()) return preview(tier, activity, origin, false, chance, balance, "ACTIVITY_DISABLED");
        if (!category.enabled() || chance <= 0.0d) return preview(tier, activity, origin, false, chance, balance, "NO_ACQUISITION_RULE");
        ProvenancePolicy.Decision provenance = provenancePolicy.evaluate(activity, origin);
        if (!provenance.eligible()) return preview(tier, activity, origin, false, chance, balance, provenance.reason());
        if (!player.hasPermission("plexonkeys.earn")) return preview(tier, activity, origin, false, chance, balance, "NO_EARN_PERMISSION");
        if (!settings.gameModes().contains(player.getGameMode())) return preview(tier, activity, origin, false, chance, balance, "GAME_MODE_REJECTED");
        if (!settings.allowsWorld(player.getWorld())) return preview(tier, activity, origin, false, chance, balance, "WORLD_REJECTED");
        if (!category.permission().isBlank() && !player.hasPermission(category.permission())) {
            return preview(tier, activity, origin, false, chance, balance, "KEY_PERMISSION_REJECTED");
        }
        if (balance >= settings.cap()) return preview(tier, activity, origin, false, chance, balance, "BALANCE_CAP");
        if (plan.cooldownNanos() > 0) {
            long[] last = cooldowns.get(player.getUniqueId());
            if (last != null) {
                long previous = last[activity.ordinal()];
                if (previous != 0 && System.nanoTime() - previous < plan.cooldownNanos()) {
                    return preview(tier, activity, origin, false, chance, balance, "COOLDOWN_ACTIVE");
                }
            }
        }
        return preview(tier, activity, origin, true, chance, balance, "ELIGIBLE_ROLL");
    }

    private static AcquisitionPreview preview(KeyTier tier, Activity activity, ProvenancePolicy.Origin origin,
            boolean eligible, double chance, long balance, String reason) {
        return new AcquisitionPreview(tier.id(), activity, origin.name(), eligible, chance, balance, reason);
    }

    private void acquired(
            Player player,
            ActivityPlan plan,
            Candidate candidate,
            RewardRuntime runtime,
            KeyBalanceService.GrantResult result) {
        Settings.Category category = candidate.category();
        TagResolver[] tags = {
                Text.value("player", player.getName()),
                Text.component("category", candidate.display()),
                Text.value("activity", plan.display()),
                Text.value("amount", result.credited()),
                Text.value("balance", result.balance())
        };
        if (runtime.personalChat()) runtime.settings().text().send(player, "earned", tags);
        if (category.announce()) Bukkit.broadcast(Text.parse(category.announcement(), tags));

        int xp = category.xpEnabled() ? category.xp() : 0;
        double money = category.moneyEnabled() ? category.money() : 0;
        if (xp > 0) player.giveExp(xp);
        if (money > 0 && !plugin.economy().deposit(player, money)) {
            money = 0;
            runtime.settings().text().send(player, "money-failed");
        }
        if (money > 0 || xp > 0) {
            runtime.settings().text().send(player, "bonus",
                    Text.value("money", String.format(Locale.ROOT, "%.2f", money)), Text.value("xp", xp));
        }
        if (!runtime.sound().isBlank()) {
            player.playSound(player.getLocation(), runtime.sound(), SoundCategory.MASTER,
                    runtime.soundVolume(), runtime.soundPitch());
        }
    }

    public void reload() {
        runtimeRevision = Long.MIN_VALUE;
        rebuild();
    }

    private RewardRuntime current() {
        long revision = plugin.configuration().revision();
        RewardRuntime existing = runtime;
        if (existing == null || runtimeRevision != revision) return rebuild();
        return existing;
    }

    private synchronized RewardRuntime rebuild() {
        long revision = plugin.configuration().revision();
        if (runtime != null && runtimeRevision == revision) return runtime;
        Settings settings = plugin.settings();
        EnumMap<Activity, ActivityPlan> plans = new EnumMap<>(Activity.class);
        for (Activity activity : Activity.values()) {
            Settings.Task task = settings.tasks().get(activity);
            ArrayList<Candidate> candidates = new ArrayList<>();
            for (KeyTier tier : HIGHEST_FIRST) {
                Settings.Category category = settings.categories().get(tier);
                if (!category.enabled()) continue;
                double chance = validatedChance(category.chances().get(activity));
                if (chance <= 0.0d) continue;
                candidates.add(new Candidate(tier, category, chance, Text.parse(category.display())));
            }
            plans.put(activity, new ActivityPlan(
                    task.enabled(), Math.multiplyExact(task.cooldownMillis(), 1_000_000L), task.display(), List.copyOf(candidates)));
        }
        RewardRuntime next = new RewardRuntime(
                settings,
                Map.copyOf(plans),
                settings.yaml().getBoolean("notifications.personal-chat"),
                settings.yaml().getString("notifications.sound", ""),
                (float) settings.yaml().getDouble("notifications.sound-volume"),
                (float) settings.yaml().getDouble("notifications.sound-pitch"));
        runtime = next;
        runtimeRevision = revision;
        return next;
    }

    private static double validatedChance(double percent) {
        if (!Double.isFinite(percent) || percent < 0.0d || percent > 100.0d) {
            throw new IllegalArgumentException("Invalid percentage: " + percent);
        }
        return percent;
    }

    public Metrics metrics() {
        double seconds = Math.max(0.001d, (System.nanoTime() - metricsStartNanos) / 1_000_000_000.0d);
        EnumMap<KeyTier, Long> byTier = new EnumMap<>(KeyTier.class);
        for (KeyTier tier : KeyTier.values()) byTier.put(tier, wins[tier.ordinal()]);
        return new Metrics(seconds, eligibleEvents, ineligibleEvents, rolls, cooldownRejects, capRejects,
                permissionRejects, Map.copyOf(byTier));
    }

    public void forget(UUID player) { cooldowns.remove(player); }
    public void clearCooldowns() { cooldowns.clear(); }
}
