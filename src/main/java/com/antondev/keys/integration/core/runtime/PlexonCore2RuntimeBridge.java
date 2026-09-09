package com.antondev.keys.integration.core.runtime;

import com.zpkdxgames.plexoncore.api.PlexonCoreAPI;
import com.zpkdxgames.plexoncore.context.BlockOrigin;
import com.zpkdxgames.plexoncore.event.CoreBlockSubscription;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Loaded reflectively only when the installed Core advertises API 2. */
public final class PlexonCore2RuntimeBridge implements CoreRuntimeBridge {
    private final PlexonCoreAPI core;

    public PlexonCore2RuntimeBridge(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        RegisteredServiceProvider<PlexonCoreAPI> registration = Bukkit.getServicesManager().getRegistration(PlexonCoreAPI.class);
        if (registration == null) throw new IllegalStateException("PlexonCore API service is not registered");
        core = registration.getProvider();
        if (!core.supportsApi(2, 0)) throw new IllegalStateException("PlexonCore does not advertise API 2.0");
    }

    @Override public boolean available() { return true; }
    @Override public String detail() { return "Core API " + core.version().apiVersion() + " block runtime ready"; }

    @Override
    public AutoCloseable subscribeBlocks(Set<Material> materials, Consumer<BlockFact> consumer) {
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(consumer, "consumer");
        if (materials.isEmpty()) throw new IllegalArgumentException("Core block route cannot be empty");
        CoreBlockSubscription subscription = CoreBlockSubscription.builder()
                .materials(materials)
                .requiresNaturalOrigin(true)
                .build();
        return core.events().subscribeBlockBreak("keys", subscription, context -> consumer.accept(new BlockFact(
                context.eventId(), context.playerId(), context.worldId(), context.worldName(),
                context.x(), context.y(), context.z(), context.material(), map(context.origin()), context.dropItems())));
    }

    @Override
    public Origin origin(UUID worldId, int x, int y, int z) {
        return map(core.blockOrigins().origin(worldId, x, y, z));
    }

    private static Origin map(BlockOrigin origin) {
        if (origin == null) return Origin.UNKNOWN;
        return switch (origin) {
            case NATURAL -> Origin.NATURAL;
            case PLAYER_PLACED -> Origin.ARTIFICIAL;
            case UNKNOWN -> Origin.UNKNOWN;
        };
    }
}
