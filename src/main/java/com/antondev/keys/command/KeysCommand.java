package com.antondev.keys.command;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.config.Text;
import com.antondev.keys.model.*;
import java.util.*;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class KeysCommand implements CommandExecutor, TabCompleter {
    private final PlexonKeys plugin;
    public KeysCommand(PlexonKeys plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equals("keysadmin")) return admin(sender, args);
        if (!sender.hasPermission("plexonkeys.use")) { text().send(sender, "no-permission"); return true; }
        if (!(sender instanceof Player player)) { text().send(sender, "players-only"); return true; }
        if (args.length == 0) plugin.menus().openPlayer(player);
        else if (args.length == 1 && args[0].equalsIgnoreCase("admin")) plugin.menus().openAdmin(player);
        else if (args.length == 2 && args[0].equalsIgnoreCase("claim")) {
            if (args[1].equalsIgnoreCase("all")) plugin.claims().claim(player, null, false);
            else {
                KeyTier tier = tier(sender, args[1]); if (tier != null) plugin.claims().claim(player, tier, false);
            }
        } else text().send(sender, "usage");
        return true;
    }
    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plexonkeys.admin")) { text().send(sender, "no-permission"); return true; }
        if (args.length == 0) {
            if (sender instanceof Player player) plugin.menus().openAdmin(player); else text().send(sender, "admin-help");
            return true;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "help" -> text().send(sender, "admin-help");
                case "reload" -> plugin.reloadFor(sender);
                case "save" -> plugin.saveData(sender);
                case "status" -> text().send(sender, "status", plugin.statusTags());
                case "setitem" -> {
                    if (args.length != 2) { text().send(sender, "admin-help"); break; }
                    if (!(sender instanceof Player player)) { text().send(sender, "players-only"); break; }
                    KeyTier tier = tier(sender, args[1]); if (tier == null) break;
                    plugin.configuration().capture(tier, player.getInventory().getItemInMainHand()); plugin.settingsChanged();
                    text().send(sender, "item-captured", Text.component("category", Text.parse(plugin.settings().categories().get(tier).display())));
                }
                case "chance" -> {
                    if (args.length != 4) { text().send(sender, "admin-help"); break; }
                    KeyTier tier = tier(sender, args[1]); if (tier == null) break;
                    Activity activity = Activity.parse(args[2]);
                    change(sender, "categories." + tier.id() + ".chances." + activity.id(), args[3]);
                }
                case "set" -> {
                    if (args.length < 3) { text().send(sender, "admin-help"); break; }
                    change(sender, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                }
                case "give", "take", "setbalance" -> changeBalance(sender, args);
                case "balance" -> {
                    if (args.length != 2) { text().send(sender, "admin-help"); break; }
                    UUID id = resolve(sender, args[1]); if (id == null) break;
                    var account = plugin.data().account(id);
                    text().send(sender, "balance", Text.value("player", account.name().isBlank() ? id : account.name()), Text.value("basic", account.basic()),
                            Text.value("rare", account.rare()), Text.value("epic", account.epic()), Text.value("legendary", account.legendary()));
                }
                default -> text().send(sender, "admin-help");
            }
        } catch (Exception error) { plugin.configError(sender, error); }
        return true;
    }
    private void change(CommandSender sender, String path, String value) throws Exception {
        plugin.configuration().set(path, value); plugin.settingsChanged(); text().send(sender, "setting-saved", Text.value("path", path));
    }
    private void changeBalance(CommandSender sender, String[] args) {
        if (args.length != 4) { text().send(sender, "admin-help"); return; }
        UUID id = resolve(sender, args[1]); if (id == null) return;
        KeyTier tier = tier(sender, args[2]); if (tier == null) return;
        long amount;
        long min = args[0].equalsIgnoreCase("setbalance") ? 0 : 1;
        try { amount = Long.parseLong(args[3]); }
        catch (NumberFormatException ignored) { invalidAmount(sender, min); return; }
        if (amount < min || amount > plugin.settings().cap()) { invalidAmount(sender, min); return; }
        long current = plugin.data().balance(id, tier);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> plugin.data().credit(id, tier, amount, plugin.settings().cap());
            case "take" -> plugin.data().set(id, tier, Math.max(0, current - amount));
            case "setbalance" -> plugin.data().set(id, tier, amount);
            default -> throw new IllegalArgumentException("Unknown balance operation");
        }
        // Administrative grants are balance corrections. They never trigger activity bonuses or announcements.
        var account = plugin.data().account(id);
        text().send(sender, "balance-changed", Text.value("player", account.name().isBlank() ? id : account.name()),
                Text.component("category", Text.parse(plugin.settings().categories().get(tier).display())), Text.value("amount", account.amount(tier)));
        Player online = Bukkit.getPlayer(id); if (online != null) plugin.menus().refreshPlayer(online);
    }
    private void invalidAmount(CommandSender sender, long min) { text().send(sender, "invalid-amount", Text.value("min", min), Text.value("max", plugin.settings().cap())); }
    private UUID resolve(CommandSender sender, String value) {
        Player online = Bukkit.getPlayerExact(value);
        if (online != null) { plugin.data().remember(online.getUniqueId(), online.getName()); return online.getUniqueId(); }
        UUID id = plugin.data().findPlayer(value).orElse(null);
        if (id == null) text().send(sender, "player-not-found");
        return id;
    }
    private KeyTier tier(CommandSender sender, String input) {
        try { return KeyTier.parse(input); }
        catch (IllegalArgumentException ignored) { text().send(sender, "invalid-category"); return null; }
    }
    private Text text() { return plugin.settings().text(); }
    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        boolean admin = command.getName().equals("keysadmin");
        if (admin && !sender.hasPermission("plexonkeys.admin")) return List.of();
        if (!admin && !sender.hasPermission("plexonkeys.use")) return List.of();
        if (args.length == 1) options.addAll(admin ? List.of("help", "setitem", "chance", "give", "take", "setbalance", "balance", "set", "reload", "save", "status") : List.of("claim"));
        if (!admin && args.length == 1 && sender.hasPermission("plexonkeys.admin")) options.add("admin");
        if (args.length == 2 && ((!admin && args[0].equalsIgnoreCase("claim")) || (admin && Set.of("setitem", "chance").contains(args[0].toLowerCase(Locale.ROOT))))) {
            Arrays.stream(KeyTier.values()).map(KeyTier::id).forEach(options::add); if (!admin) options.add("all");
        }
        if (admin && args.length == 2 && Set.of("give", "take", "setbalance", "balance").contains(args[0].toLowerCase(Locale.ROOT)))
            Stream.concat(Bukkit.getOnlinePlayers().stream().map(Player::getName), plugin.data().names().stream()).distinct().limit(1000).forEach(options::add);
        if (admin && args.length == 2 && args[0].equalsIgnoreCase("set")) plugin.settings().yaml().getKeys(true).stream()
                .filter(key -> !plugin.settings().yaml().isConfigurationSection(key) && !key.equals("config-version") && !key.endsWith(".base64")).forEach(options::add);
        if (admin && args.length == 3 && args[0].equalsIgnoreCase("chance")) Arrays.stream(Activity.values()).map(Activity::id).forEach(options::add);
        if (admin && args.length == 3 && Set.of("give", "take", "setbalance").contains(args[0].toLowerCase(Locale.ROOT))) Arrays.stream(KeyTier.values()).map(KeyTier::id).forEach(options::add);
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
