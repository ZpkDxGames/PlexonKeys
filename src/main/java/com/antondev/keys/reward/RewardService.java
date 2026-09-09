package com.antondev.keys.reward;

import com.antondev.keys.PlexonKeys;
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
    private static final int CHANCE_SCALE = 100_000; // 0.001 percentage-point precision.

    private record Candidate(KeyTier tier, Settings.Category category, int threshold, Component display) {}
    private record ActivityPlan(boolean enabled, long cooldownNanos, String display, List<Candidate> candidates) {}
    private record RewardRuntime(
            Settings settings,
            Map<Activity, ActivityPlan> plans,
            boolean personalChat,
            String sound,
            float soundVolume,
            float soundPitch) {}

    private final PlexonKeys plugin;
    private final Map<UUID, long[]> cooldowns = new HashMap<>();
    private volatile RewardRuntime runtime;
    private volatile long runtimeRevision = Long.MIN_VALUE;

    public RewardService(PlexonKeys plugin) {
        this.plugin = plugin;
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
        if (!settings.enabled() || !plan.enabled() || plan.candidates().isEmpty()) return false;
        if (!player.hasPermission("plexonkeys.earn")) return false;
        if (!settings.gameModes().contains(player.getGameMode()) || !settings.allowsWorld(player.getWorld())) return false;

        if (plan.cooldownNanos() > 0) {
            long now = System.nanoTime();
            long[] last = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new long[Activity.values().length]);
            long previous = last[activity.ordinal()];
            if (previous != 0 && now - previous < plan.cooldownNanos()) return false;
            last[activity.ordinal()] = now;
        }

        boolean awarded = false;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Candidate candidate : plan.candidates()) {
            Settings.Category category = candidate.category();
            if (!category.permission().isBlank() && !player.hasPermission(category.permission())) continue;
            if (candidate.threshold() < CHANCE_SCALE && random.nextInt(CHANCE_SCALE) >= candidate.threshold()) continue;

            KeyBalanceService.GrantResult result = plugin.balances().grantResult(
                    player, candidate.tier(), 1, "activity:" + activity.id());
            if (result.credited() == 0) continue;

            awarded = true;
            acquired(player, plan, candidate, current, result);
            if (settings.highestOnly()) break;
        }
        if (awarded) plugin.menus().refreshPlayer(player);
        return awarded;
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
                int threshold = chanceThreshold(category.chances().get(activity));
                if (threshold <= 0) continue;
                candidates.add(new Candidate(tier, category, threshold, Text.parse(category.display())));
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

    private static int chanceThreshold(double percent) {
        if (!Double.isFinite(percent) || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Invalid percentage: " + percent);
        }
        return (int) Math.round(percent * 1000.0d);
    }

    public void forget(UUID player) { cooldowns.remove(player); }
    public void clearCooldowns() { cooldowns.clear(); }
}
