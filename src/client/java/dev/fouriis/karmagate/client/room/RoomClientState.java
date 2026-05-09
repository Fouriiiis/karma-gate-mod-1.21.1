package dev.fouriis.karmagate.client.room;

import dev.fouriis.karmagate.network.RoomSyncPayload;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side cache of rooms synced from the server.
 */
public final class RoomClientState {

    public record RoomEntry(String name, BlockPos min, BlockPos max, Box bounds) {}

    private static final List<RoomEntry> ROOMS = new ArrayList<>();

    private RoomClientState() {}

    public static void clearRooms() {
        ROOMS.clear();
    }

    public static void addRoom(RoomEntry room) {
        ROOMS.add(room);
    }

    public static List<RoomEntry> getRooms() {
        return Collections.unmodifiableList(ROOMS);
    }

    public static void applySync(RoomSyncPayload payload) {
        clearRooms();
        for (RoomSyncPayload.RoomEntry entry : payload.rooms()) {
            BlockPos corner1 = new BlockPos(entry.x1(), entry.y1(), entry.z1());
            BlockPos corner2 = new BlockPos(entry.x2(), entry.y2(), entry.z2());
            BlockPos min = new BlockPos(
                Math.min(corner1.getX(), corner2.getX()),
                Math.min(corner1.getY(), corner2.getY()),
                Math.min(corner1.getZ(), corner2.getZ())
            );
            BlockPos max = new BlockPos(
                Math.max(corner1.getX(), corner2.getX()),
                Math.max(corner1.getY(), corner2.getY()),
                Math.max(corner1.getZ(), corner2.getZ())
            );
            Box bounds = new Box(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1
            );
            addRoom(new RoomEntry(entry.name(), min, max, bounds));
        }
    }
}
