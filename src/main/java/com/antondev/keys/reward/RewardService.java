package com.antondev.keys.reward;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.config.*;
import com.antondev.keys.model.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.*;
import org.bukkit.entity.Player;

public final class RewardService {
    public static final List<KeyTier> HIGHEST_FIRST = List.of(KeyTier.LEGENDARY, KeyTier.EPIC, KeyTier.RARE, KeyTier.BASIC);
    private final PlexonKeys plugin;
    private final Map<UUID, long[]> cooldowns = new HashMap<>();
    public RewardService(PlexonKeys plugin) { this.plugin = plugin; }
    public boolean eligible(Player player, Activity activity) {
        Settings s = plugin.settings();
        return s.enabled() && s.tasks().get(activity).enabled() && player.hasPermission("plexonkeys.earn")
                && s.gameModes().contains(player.getGameMode()) && s.allowsWorld(player.getWorld());
    }
    public void perform(Player player, Activity activity) {
        if (!eligible(player, activity)) return;
        Settings s = plugin.settings();
        long cooldown = s.tasks().get(activity).cooldownMillis();
        if (cooldown > 0) {
            long now = System.nanoTime() / 1_000_000;
            long[] last = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new long[Activity.values().length]);
            if (last[activity.ordinal()] != 0 && now - last[activity.ordinal()] < cooldown) return;
            last[activity.ordinal()] = now;
        }
        boolean awarded = false;
        for (KeyTier tier : HIGHEST_FIRST) {
            Settings.Category category = s.categories().get(tier);
            if (!category.enabled() || (!category.permission().isBlank() && !player.hasPermission(category.permission()))) continue;
            if (plugin.data().balance(player.getUniqueId(), tier) >= s.cap()) continue;
            if (!DropRoller.wins(category.chances().get(activity), () -> ThreadLocalRandom.current().nextDouble())) continue;
            plugin.data().remember(player.getUniqueId(), player.getName());
            if (plugin.data().credit(player.getUniqueId(), tier, 1, s.cap()) == 0) continue;
            awarded = true;
            acquired(player, activity, category, s);
            if (s.highestOnly()) break;
        }
        if (awarded) plugin.menus().refreshPlayer(player);
    }
    private void acquired(Player player, Activity activity, Settings.Category category, Settings settings) {
        TagResolver[] tags = {Text.value("player", player.getName()), Text.component("category", Text.parse(category.display())),
                Text.value("activity", settings.tasks().get(activity).display()), Text.value("amount", 1),
                Text.value("balance", plugin.data().balance(player.getUniqueId(), category.tier()))};
        if (settings.yaml().getBoolean("notifications.personal-chat")) settings.text().send(player, "earned", tags);
        if (category.announce()) Bukkit.broadcast(Text.parse(category.announcement(), tags));
        int xp = category.xpEnabled() ? category.xp() : 0;
        double money = category.moneyEnabled() ? category.money() : 0;
        if (xp > 0) player.giveExp(xp);
        if (money > 0 && !plugin.economy().deposit(player, money)) {
            money = 0;
            settings.text().send(player, "money-failed");
        }
        if (money > 0 || xp > 0) settings.text().send(player, "bonus", Text.value("money", String.format(Locale.ROOT, "%.2f", money)), Text.value("xp", xp));
        String sound = settings.yaml().getString("notifications.sound", "");
        if (!sound.isBlank()) player.playSound(player.getLocation(), sound, SoundCategory.MASTER,
                (float) settings.yaml().getDouble("notifications.sound-volume"), (float) settings.yaml().getDouble("notifications.sound-pitch"));
    }
    public void forget(UUID player) { cooldowns.remove(player); }
    public void clearCooldowns() { cooldowns.clear(); }
}
