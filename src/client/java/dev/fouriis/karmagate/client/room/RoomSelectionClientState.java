package dev.fouriis.karmagate.client.room;

import dev.fouriis.karmagate.network.RoomSelectionSyncPayload;
import net.minecraft.util.math.BlockPos;

/**
 * Client-side view of the local player's room selection.
 */
public final class RoomSelectionClientState {

    private static BlockPos corner1;
    private static BlockPos corner2;

    private RoomSelectionClientState() {}

    public static void applySync(RoomSelectionSyncPayload payload) {
        corner1 = payload.hasCorner1() ? new BlockPos(payload.x1(), payload.y1(), payload.z1()) : null;
        corner2 = payload.hasCorner2() ? new BlockPos(payload.x2(), payload.y2(), payload.z2()) : null;
    }

    public static BlockPos getCorner1() {
        return corner1;
    }

    public static BlockPos getCorner2() {
        return corner2;
    }

    public static boolean isComplete() {
        return corner1 != null && corner2 != null;
    }
}
