package com.antondev.keys.integration.core.runtime;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Material;

/** Core-2 runtime boundary. This interface contains no PlexonCore classes and is safe in standalone mode. */
public interface CoreRuntimeBridge extends AutoCloseable {
    boolean available();
    String detail();
    AutoCloseable subscribeBlocks(Set<Material> materials, Consumer<BlockFact> consumer) throws Exception;
    Origin origin(UUID worldId, int x, int y, int z);
    @Override default void close() {}

    record BlockFact(long eventId, UUID playerId, UUID worldId, String worldName,
                     int x, int y, int z, Material material, Origin origin, boolean dropItems) {}

    enum Origin {
        NATURAL,
        ARTIFICIAL,
        UNKNOWN
    }
}
