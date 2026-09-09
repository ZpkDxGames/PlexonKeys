package com.antondev.keys.integration.core.runtime;

import com.antondev.keys.integration.core.CoreBridge;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoreRuntimeBridgeFactory {
    private static final String IMPLEMENTATION = "com.antondev.keys.integration.core.runtime.PlexonCore2RuntimeBridge";

    private CoreRuntimeBridgeFactory() {}

    public static CoreRuntimeBridge resolve(JavaPlugin plugin, CoreBridge core) {
        if (core == null || !core.installed() || !core.compatible()) return unavailable("PlexonCore is unavailable");
        String api = core.apiVersion();
        if (api == null || !api.startsWith("2.")) return unavailable("Core API " + api + " has no Runtime 2 gateway");
        try {
            Class<?> type = Class.forName(IMPLEMENTATION, true, CoreRuntimeBridgeFactory.class.getClassLoader());
            return (CoreRuntimeBridge) type.getConstructor(JavaPlugin.class).newInstance(plugin);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            plugin.getLogger().log(Level.WARNING, "PlexonCore 2 Runtime could not be resolved; local block listeners remain available.", cause);
            return unavailable("Core Runtime API service is unavailable");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "PlexonCore 2 Runtime could not link safely; local block listeners remain available.", exception);
            return unavailable("Core Runtime API linkage is unavailable");
        }
    }

    private static CoreRuntimeBridge unavailable(String detail) {
        return new CoreRuntimeBridge() {
            @Override public boolean available() { return false; }
            @Override public String detail() { return detail; }
            @Override public AutoCloseable subscribeBlocks(Set<Material> materials, Consumer<BlockFact> consumer) {
                throw new IllegalStateException(detail);
            }
            @Override public Origin origin(UUID worldId, int x, int y, int z) { return Origin.UNKNOWN; }
        };
    }
}
