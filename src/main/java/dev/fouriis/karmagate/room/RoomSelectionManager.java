package dev.fouriis.karmagate.room;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player room selections on the server.
 */
public final class RoomSelectionManager {
    private static final Map<UUID, RoomSelection> SELECTIONS = new ConcurrentHashMap<>();

    private RoomSelectionManager() {}

    public static RoomSelection getSelection(ServerPlayerEntity player) {
        return SELECTIONS.get(player.getUuid());
    }

    public static RoomSelection setCorner1(ServerPlayerEntity player, BlockPos pos) {
        UUID id = player.getUuid();
        RoomSelection selection = SELECTIONS.getOrDefault(id, new RoomSelection(null, null));
        selection = selection.withCorner1(pos.toImmutable());
        SELECTIONS.put(id, selection);
        return selection;
    }

    public static RoomSelection setCorner2(ServerPlayerEntity player, BlockPos pos) {
        UUID id = player.getUuid();
        RoomSelection selection = SELECTIONS.getOrDefault(id, new RoomSelection(null, null));
        selection = selection.withCorner2(pos.toImmutable());
        SELECTIONS.put(id, selection);
        return selection;
    }

    public static void clearSelection(ServerPlayerEntity player) {
        SELECTIONS.remove(player.getUuid());
    }
}
