package dev.fouriis.karmagate.hose;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FuelHoseSessionManager {
    private static final Map<UUID, Selection> SESSIONS = new ConcurrentHashMap<>();

    private FuelHoseSessionManager() {
    }

    public static void setFirstEndpoint(ServerPlayerEntity player, BlockPos start, RegistryKey<World> dimension) {
        SESSIONS.put(player.getUuid(), new Selection(dimension, start));
    }

    public static Optional<Selection> get(ServerPlayerEntity player) {
        return Optional.ofNullable(SESSIONS.get(player.getUuid()));
    }

    public static void clear(ServerPlayerEntity player) {
        SESSIONS.remove(player.getUuid());
    }

    public record Selection(RegistryKey<World> dimension, BlockPos start) {
    }
}