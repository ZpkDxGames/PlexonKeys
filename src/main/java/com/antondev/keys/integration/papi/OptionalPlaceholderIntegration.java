package com.antondev.keys.integration.papi;

import com.antondev.keys.PlexonKeys;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.Plugin;

/** Keeps PlaceholderAPI fully optional at runtime and exposes a compact integration diagnostic. */
public final class OptionalPlaceholderIntegration {
    private static final AtomicReference<String> STATUS = new AtomicReference<>("NOT_RESOLVED");

    private OptionalPlaceholderIntegration() {}

    public static void install(PlexonKeys plugin) {
        Plugin papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null) {
            STATUS.set("ABSENT");
            return;
        }
        if (!papi.isEnabled()) {
            STATUS.set("DISABLED");
            return;
        }
        try {
            Class<?> expansionType = Class.forName(
                    "com.antondev.keys.integration.papi.PlexonKeysExpansion", true, plugin.getClass().getClassLoader());
            Constructor<?> constructor = expansionType.getConstructor(PlexonKeys.class);
            Object expansion = constructor.newInstance(plugin);
            Method register = expansionType.getMethod("register");
            Object result = register.invoke(expansion);
            STATUS.set(Boolean.FALSE.equals(result) ? "REGISTRATION_REJECTED" : "READY");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            STATUS.set("INCOMPATIBLE:" + error.getClass().getSimpleName());
            plugin.getLogger().warning("PlaceholderAPI integration unavailable: " + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage()));
        }
    }

    public static String status() { return STATUS.get(); }
}
