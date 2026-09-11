package com.antondev.keys.config;

import com.antondev.keys.model.KeyTier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Parses and validates candidate configuration before changing active settings. Runtime application is
 * completed separately by PlexonKeys; until that succeeds, the previous active state remains available for
 * rollback. Internal edits may also restore their exact previous disk contents if runtime application fails.
 */
public final class Configuration {
    public record ActiveSnapshot(Settings settings, String diskContents) {}
    private record PendingChange(ActiveSnapshot previous, boolean restoreDisk) {}

    private final Path file;
    private final YamlConfiguration defaults;
    private Settings settings;
    private long revision;
    private String diskContents;
    private PendingChange pendingChange;

    public Configuration(JavaPlugin plugin) throws Exception {
        file = plugin.getDataFolder().toPath().resolve("config.yml");
        try (var stream = Objects.requireNonNull(plugin.getResource("config.yml")); var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            defaults = new YamlConfiguration(); defaults.load(reader);
        }
    }

    /** External reload: preserve the edited file if runtime application fails, but restore last-good settings. */
    public Settings reload() throws Exception {
        String contents = Files.readString(file, StandardCharsets.UTF_8);
        var config = new YamlConfiguration();
        config.loadFromString(contents); fillDefaults(config);
        Settings next = Settings.parse(config);
        commitCandidate(next, contents, false);
        return next;
    }

    public Settings settings() { return settings; }
    public long revision() { return revision; }
    public boolean hasPendingRuntimeChange() { return pendingChange != null; }

    /** Mark the most recent validated candidate as successfully applied to all runtime subsystems. */
    public void runtimeApplied() { pendingChange = null; }

    /**
     * Restore the last runtime-accepted settings after subsystem application failed. Revision remains
     * monotonic so stale GUI sessions/runtime caches can never become valid again. For internal edits, the
     * exact previous config.yml is restored atomically; for an external reload the administrator's edited
     * file is intentionally left untouched so it can be corrected and reloaded later.
     */
    public boolean rollbackRuntimeCandidate() throws Exception {
        PendingChange pending = pendingChange;
        if (pending == null) return false;
        pendingChange = null;
        settings = pending.previous().settings();
        diskContents = pending.previous().diskContents();
        revision++;
        if (pending.restoreDisk()) writeAtomically(diskContents);
        return true;
    }

    public Settings update(Consumer<YamlConfiguration> changes) throws Exception {
        if (!Objects.equals(diskContents, Files.readString(file, StandardCharsets.UTF_8)))
            throw new IllegalStateException("config.yml changed on disk. Run /keysadmin reload before editing in-game");
        var copy = new YamlConfiguration(); copy.loadFromString(settings.yaml().saveToString());
        changes.accept(copy);
        fillDefaults(copy);
        Settings next = Settings.parse(copy);
        String output = copy.saveToString();
        writeAtomically(output);
        commitCandidate(next, output, true);
        return next;
    }

    private void commitCandidate(Settings next, String contents, boolean restoreDisk) {
        ActiveSnapshot previous = pendingChange == null
                ? (settings == null ? null : new ActiveSnapshot(settings, diskContents))
                : pendingChange.previous();
        boolean shouldRestoreDisk = restoreDisk || pendingChange != null && pendingChange.restoreDisk();
        settings = Objects.requireNonNull(next, "next");
        diskContents = Objects.requireNonNull(contents, "contents");
        revision++;
        pendingChange = previous == null ? null : new PendingChange(previous, shouldRestoreDisk);
    }

    private void writeAtomically(String output) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "config-", ".tmp");
        try {
            Files.writeString(temp, output, StandardCharsets.UTF_8);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private void fillDefaults(YamlConfiguration config) {
        config.setDefaults(defaults); config.options().copyDefaults(true);
        // Bukkit getters with an explicit fallback do not consult inherited defaults. Materialize
        // missing values in memory so a pre-upgrade config renders new GUI items correctly too.
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                if (!config.contains(key, true)) config.createSection(key);
                else if (!config.isConfigurationSection(key)) throw new IllegalArgumentException(key + ": expected a configuration section");
            } else if (!config.contains(key, true)) config.set(key, defaults.get(key));
        }
        // Phase 3 migration: old production configs used checkpoint-seconds: 0 to mean shutdown-only.
        // The new explicit switch makes safe checkpointing the inherited behavior without rewriting disk on reload.
        if (!config.contains("storage.checkpoints.enabled", true)) config.set("storage.checkpoints.enabled", true);
    }

    public Settings capture(KeyTier tier, org.bukkit.inventory.ItemStack item) throws Exception {
        String encoded = Items.capture(item);
        return update(c -> { c.set("categories." + tier.id() + ".item.mode", "CAPTURED"); c.set("categories." + tier.id() + ".item.base64", encoded); });
    }

    public Settings set(String path, String input) throws Exception {
        Object current = settings.yaml().get(path);
        if (current == null || settings.yaml().isConfigurationSection(path) || path.equals("config-version") || path.endsWith(".base64"))
            throw new IllegalArgumentException("Choose an editable setting; use setitem for captured key data");
        Object value;
        if (current instanceof String) value = input.equals("\"\"") || input.equals("''") ? "" : input;
        else {
            var valueConfig = new YamlConfiguration(); valueConfig.loadFromString("value: " + input);
            if (valueConfig.getKeys(false).size() != 1) throw new IllegalArgumentException("Enter one value only");
            value = valueConfig.get("value");
        }
        if (value == null) throw new IllegalArgumentException("A value is required");
        Object replacement = value;
        return update(c -> c.set(path, replacement));
    }
}
