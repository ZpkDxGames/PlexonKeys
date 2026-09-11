package com.antondev.keys.activity;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.model.Activity;
import java.util.Locale;
import java.util.Objects;

/** Conservative provenance policy shared by live acquisition and non-granting diagnostics. */
public final class ProvenancePolicy {
    public enum Origin {
        NATURAL,
        PLAYER_PLACED,
        PLEXON_SPAWNERS,
        EXTERNAL_SPAWNER,
        UNKNOWN,
        DISABLED;

        public static Origin parse(String value) {
            Objects.requireNonNull(value, "value");
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record Decision(boolean eligible, String reason) {}

    private final PlexonKeys plugin;

    public ProvenancePolicy(PlexonKeys plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public Decision evaluate(Activity activity, Origin origin) {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(origin, "origin");
        if (origin == Origin.NATURAL) return new Decision(true, "NATURAL");
        if (activity != Activity.MOBS) return new Decision(false, "PROVENANCE_" + origin.name());

        return switch (origin) {
            case PLEXON_SPAWNERS -> configured("integrations.spawners.allow-plexon-origin", origin);
            case EXTERNAL_SPAWNER -> configured("integrations.spawners.allow-external-spawner-origin", origin);
            case UNKNOWN -> configured("integrations.spawners.allow-unknown-origin", origin);
            case PLAYER_PLACED, DISABLED -> new Decision(false, "PROVENANCE_" + origin.name());
            case NATURAL -> new Decision(true, "NATURAL");
        };
    }

    private Decision configured(String path, Origin origin) {
        boolean allowed = plugin.settings().yaml().getBoolean(path, false);
        return new Decision(allowed, allowed ? "OPT_IN_" + origin.name() : "PROVENANCE_" + origin.name());
    }
}
