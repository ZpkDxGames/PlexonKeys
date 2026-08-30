package com.antondev.keys.config;

import com.antondev.keys.model.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.ItemStack;

/** Parsed once per edit/reload. All frequent eligibility checks use sets and primitive values. */
public record Settings(YamlConfiguration yaml, Text text, boolean enabled, long cap, int checkpointSeconds,
        Set<String> worlds, Set<String> excludedWorlds, Set<GameMode> gameModes, boolean highestOnly,
        Map<KeyTier, Category> categories, Map<Activity, Task> tasks,
        boolean trackGrowth, boolean trackFormation, boolean openWater, boolean requireDrops,
        Set<Material> miningMaterials, Set<Material> loggingMaterials, Set<EntityType> mobs, Set<SpawnReason> spawnReasons) {
    public record Category(KeyTier tier, boolean enabled, String display, String permission, Map<Activity, Double> chances,
            boolean announce, String announcement, boolean moneyEnabled, double money, boolean xpEnabled, int xp, ItemStack item) {
        public ItemStack itemCopy() { return item.clone(); }
    }
    public record Task(boolean enabled, long cooldownMillis, String display) {}
    public boolean allowsWorld(World world) {
        String name = world.getName().toLowerCase(Locale.ROOT);
        return (worlds.isEmpty() || worlds.contains(name)) && !excludedWorlds.contains(name);
    }
    public static Settings parse(YamlConfiguration c) {
        if (c.getInt("config-version") != 1) throw new IllegalArgumentException("Unsupported config-version");
        validateTypes(c);
        long cap = integer(c, "settings.max-virtual-per-category", 1, 1_000_000_000);
        int checkpoint = (int) integer(c, "storage.checkpoint-seconds", 0, 86400);
        if (checkpoint > 0 && checkpoint < 60) throw new IllegalArgumentException("storage.checkpoint-seconds must be 0 or at least 60");
        var games = enums(c, "settings.allowed-game-modes", GameMode.class);
        if (games.isEmpty()) throw new IllegalArgumentException("settings.allowed-game-modes cannot be empty");
        String mode = c.getString("settings.roll-mode", "INDEPENDENT").toUpperCase(Locale.ROOT);
        if (!Set.of("INDEPENDENT", "HIGHEST_ONLY").contains(mode)) throw new IllegalArgumentException("Unknown settings.roll-mode");
        Map<KeyTier, Category> categories = new EnumMap<>(KeyTier.class);
        for (KeyTier tier : KeyTier.values()) {
            String path = "categories." + tier.id();
            var chances = new EnumMap<Activity, Double>(Activity.class);
            for (Activity task : Activity.values()) chances.put(task, number(c, path + ".chances." + task.id(), 0, 100));
            double money = number(c, path + ".bonus.money.amount", 0, 1_000_000_000);
            int xp = (int) integer(c, path + ".bonus.xp.points", 0, 1_000_000);
            categories.put(tier, new Category(tier, c.getBoolean(path + ".enabled"), c.getString(path + ".display-name"),
                    c.getString(path + ".permission", ""), Map.copyOf(chances), c.getBoolean(path + ".announce.enabled"),
                    c.getString(path + ".announce.message", ""), c.getBoolean(path + ".bonus.money.enabled"), money,
                    c.getBoolean(path + ".bonus.xp.enabled"), xp, Items.key(c, path + ".item")));
        }
        var tasks = new EnumMap<Activity, Task>(Activity.class);
        for (Activity task : Activity.values()) {
            String path = "activities." + task.id();
            tasks.put(task, new Task(c.getBoolean(path + ".enabled"), integer(c, path + ".cooldown-ms", 0, 3_600_000), c.getString(path + ".display-name", task.id())));
        }
        int size = (int) integer(c, "gui.player.size", 27, 54);
        if (size % 9 != 0) throw new IllegalArgumentException("gui.player.size must be a multiple of 9");
        var used = new HashSet<Integer>();
        for (KeyTier tier : KeyTier.values()) {
            for (String key : List.of("icon-slot", "claim-slot")) validateSlot(c, "categories." + tier.id() + ".menu." + key, size, used);
        }
        for (String button : List.of("summary", "claim-all", "close", "admin")) validateSlot(c, "gui.player." + button + ".slot", size, used);
        integer(c, "gui.admin.prompt-timeout-seconds", 10, 600);
        number(c, "notifications.sound-volume", 0, 10); number(c, "notifications.sound-pitch", 0, 2);
        String sound = c.getString("notifications.sound", "");
        if (!sound.isBlank() && NamespacedKey.fromString(sound) == null) throw new IllegalArgumentException("notifications.sound must be a valid namespaced sound key");
        return new Settings(c, new Text(c), c.getBoolean("settings.enabled"), cap, checkpoint,
                lower(c.getStringList("settings.worlds")), lower(c.getStringList("settings.excluded-worlds")), games, mode.equals("HIGHEST_ONLY"),
                Map.copyOf(categories), Map.copyOf(tasks), c.getBoolean("tracking.exclude-grown-blocks"), c.getBoolean("tracking.exclude-formed-blocks"),
                c.getBoolean("activities.fishing.require-open-water"), c.getBoolean("activities.mining.require-drops"),
                materials(c, "activities.mining.materials"), materials(c, "activities.logging.materials"),
                enums(c, "activities.mobs.types", EntityType.class), enums(c, "activities.mobs.allowed-spawn-reasons", SpawnReason.class));
    }
    private static void validateTypes(YamlConfiguration c) {
        if (c.getDefaults() == null) return;
        for (String key : c.getDefaults().getKeys(true)) {
            Object expected = c.getDefaults().get(key), actual = c.get(key);
            if (c.getDefaults().isConfigurationSection(key)) {
                if (!c.isConfigurationSection(key)) throw new IllegalArgumentException(key + ": expected a configuration section");
                continue;
            }
            boolean valid = expected instanceof Number ? actual instanceof Number : expected instanceof List<?> ? actual instanceof List<?> : expected.getClass().isInstance(actual);
            if (!valid) throw new IllegalArgumentException(key + ": expected " + expected.getClass().getSimpleName());
            if (actual instanceof List<?> list && list.stream().anyMatch(value -> !(value instanceof String))) throw new IllegalArgumentException(key + ": list values must be strings");
            if (key.endsWith(".material") && actual instanceof String s) Items.material(s);
            if (actual instanceof String text && (key.startsWith("messages.") || key.endsWith(".name") || key.endsWith(".display-name") || key.endsWith("title") || key.endsWith(".message"))) Text.parse(text);
            if (key.endsWith(".lore") && actual instanceof List<?> lines) for (Object line : lines) Text.parse((String) line);
        }
    }
    private static void validateSlot(YamlConfiguration c, String path, int size, Set<Integer> used) {
        int slot = (int) integer(c, path, 0, size - 1);
        if (!used.add(slot)) throw new IllegalArgumentException("Overlapping menu slot: " + path + " = " + slot);
    }
    private static long integer(YamlConfiguration c, String key, long min, long max) {
        double value = number(c, key, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException(key + " must be a whole number");
        return (long) value;
    }
    private static double number(YamlConfiguration c, String key, double min, double max) {
        Object raw = c.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " must be numeric");
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value;
    }
    private static Set<String> lower(List<String> values) { return values.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private static Set<Material> materials(YamlConfiguration c, String path) {
        var values = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList(path)) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) throw new IllegalArgumentException(path + ": invalid block " + name);
            values.add(material);
        }
        return Set.copyOf(values);
    }
    private static <E extends Enum<E>> Set<E> enums(YamlConfiguration c, String key, Class<E> type) {
        Set<E> set = EnumSet.noneOf(type);
        for (String value : c.getStringList(key)) {
            try { set.add(Enum.valueOf(type, value.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException(key + ": unknown value " + value); }
        }
        return Set.copyOf(set);
    }
}
