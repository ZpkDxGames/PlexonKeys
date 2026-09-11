package com.antondev.keys.integration.spawners;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional, linkage-safe adapter to the PlexonSpawners 3.x Bukkit service API. */
public final class SpawnerProvenanceBridge {
    private static final AtomicReference<String> LAST_STATUS = new AtomicReference<>("NOT_RESOLVED");

    private final JavaPlugin owner;
    private Object provider;
    private Method isSpawnerOrigin;
    private Method getOriginSpawnerId;

    public SpawnerProvenanceBridge(JavaPlugin owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        resolve();
    }

    public boolean isPlexonSpawnerOrigin(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (provider == null || isSpawnerOrigin == null) return false;
        try {
            Object result = isSpawnerOrigin.invoke(provider, entity);
            boolean origin = Boolean.TRUE.equals(result);
            LAST_STATUS.set(origin ? "READY:PLEXON_ORIGIN" : "READY");
            return origin;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            LAST_STATUS.set("ERROR:" + error.getClass().getSimpleName());
            return false;
        }
    }

    public Optional<UUID> originSpawnerId(Entity entity) {
        if (provider == null || getOriginSpawnerId == null) return Optional.empty();
        try {
            Object result = getOriginSpawnerId.invoke(provider, entity);
            if (result instanceof Optional<?> optional && optional.orElse(null) instanceof UUID id) return Optional.of(id);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            LAST_STATUS.set("ERROR:" + error.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    public static String status() { return LAST_STATUS.get(); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void resolve() {
        Plugin plugin = owner.getServer().getPluginManager().getPlugin("PlexonSpawners");
        if (plugin == null) {
            LAST_STATUS.set("ABSENT");
            return;
        }
        if (!plugin.isEnabled()) {
            LAST_STATUS.set("DISABLED");
            return;
        }
        try {
            Class<?> apiType = Class.forName("com.plexon.spawners.api.PlexonSpawnersApi", false, plugin.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = owner.getServer().getServicesManager().getRegistration((Class) apiType);
            if (registration == null) {
                LAST_STATUS.set("API_UNREGISTERED");
                return;
            }
            provider = registration.getProvider();
            isSpawnerOrigin = apiType.getMethod("isSpawnerOrigin", Entity.class);
            getOriginSpawnerId = apiType.getMethod("getOriginSpawnerId", Entity.class);
            LAST_STATUS.set("READY");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            provider = null;
            isSpawnerOrigin = null;
            getOriginSpawnerId = null;
            LAST_STATUS.set("INCOMPATIBLE:" + error.getClass().getSimpleName());
        }
    }
}
