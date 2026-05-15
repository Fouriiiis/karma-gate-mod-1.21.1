package dev.fouriis.karmagate.room;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Stores room geometry under the world save's Map directory.
 */
public final class RoomGeometryStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MAP_DIRECTORY = "Map";
    private static final String FILE_EXTENSION = ".json";

    private RoomGeometryStorage() {
    }

    public static RoomData saveRoom(MinecraftServer server, RoomData room) {
        RoomGeometry geometry = room.geometry();
        if (geometry == null || geometry.isEmpty()) {
            ServerWorld world = server.getWorld(World.OVERWORLD);
            if (world != null) {
                geometry = RoomGeometryBuilder.build(world, room);
            } else {
                geometry = RoomGeometry.empty();
            }
        }

        RoomData storedRoom = room.withGeometry(geometry);
        writeRoomFile(server, storedRoom);
        return storedRoom;
    }

    public static Optional<RoomData> loadRoom(MinecraftServer server, RoomData room) {
        Path file = roomFile(server, room.name());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        try {
            String json = Files.readString(file);
            RoomFile roomFile = GSON.fromJson(json, RoomFile.class);
            if (roomFile == null) {
                return Optional.empty();
            }

            return Optional.of(roomFile.toRoomData());
        } catch (IOException | JsonParseException e) {
            KarmaGateMod.LOGGER.warn("Failed to load room geometry from {}", file, e);
            return Optional.empty();
        }
    }

    public static void deleteRoom(MinecraftServer server, String roomName) {
        Path file = roomFile(server, roomName);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            KarmaGateMod.LOGGER.warn("Failed to delete room geometry file {}", file, e);
        }
    }

    public static RoomData loadOrCreateRoom(MinecraftServer server, RoomData room) {
        return loadRoom(server, room)
            .orElseGet(() -> saveRoom(server, room));
    }

    private static void writeRoomFile(MinecraftServer server, RoomData room) {
        Path file = roomFile(server, room.name());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(RoomFile.fromRoomData(room)));
        } catch (IOException e) {
            KarmaGateMod.LOGGER.warn("Failed to write room geometry to {}", file, e);
        }
    }

    private static Path roomFile(MinecraftServer server, String roomName) {
        return server.getSavePath(WorldSavePath.ROOT)
            .resolve(MAP_DIRECTORY)
            .resolve(roomName + FILE_EXTENSION);
    }

    private record RoomFile(
        String name,
        int x1,
        int y1,
        int z1,
        int x2,
        int y2,
        int z2,
        String dangerType,
        RoomGeometry geometry
    ) {
        private static RoomFile fromRoomData(RoomData room) {
            BlockPos corner1 = room.corner1();
            BlockPos corner2 = room.corner2();
            return new RoomFile(
                room.name(),
                corner1.getX(), corner1.getY(), corner1.getZ(),
                corner2.getX(), corner2.getY(), corner2.getZ(),
                room.dangerType().name(),
                room.geometry() == null ? RoomGeometry.empty() : room.geometry()
            );
        }

        private RoomData toRoomData() {
            return RoomData.of(
                name,
                x1, y1, z1,
                x2, y2, z2,
                DangerType.fromSerialized(dangerType),
                geometry == null ? RoomGeometry.empty() : geometry
            );
        }
    }
}