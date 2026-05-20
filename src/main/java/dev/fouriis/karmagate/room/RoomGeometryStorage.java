package dev.fouriis.karmagate.room;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Stores room geometry under the world save's Map directory.
 *
 * New file format:
 * {
 *   "v": 2,
 *   "n": "roomName",
 *   "c": [x1, y1, z1, x2, y2, z2],
 *   "d": "DANGER_TYPE",
 *   "g": "gzip+base64 RoomGeometry json"
 * }
 *
 * Old files with the previous verbose "geometry" object still load.
 * If loadOrCreateRoom loads an old file, it rewrites it in the compact format.
 */
public final class RoomGeometryStorage {

    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private static final int COMPACT_FILE_VERSION = 2;
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
        return readRoomFile(server, room.name()).map(LoadedRoom::room);
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
        Optional<LoadedRoom> loaded = readRoomFile(server, room.name());
        if (loaded.isPresent()) {
            LoadedRoom loadedRoom = loaded.get();

            /*
             * Auto-migrate old verbose room JSON to the compact compressed format.
             * This means old 50+ MB files shrink after they are loaded once through
             * this method.
             */
            if (!loadedRoom.compact()) {
                writeRoomFile(server, loadedRoom.room());
            }

            return loadedRoom.room();
        }

        return saveRoom(server, room);
    }

    private static Optional<LoadedRoom> readRoomFile(MinecraftServer server, String roomName) {
        Path file = roomFile(server, roomName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (isCompactRoomFile(root)) {
                CompactRoomFile roomFile = GSON.fromJson(root, CompactRoomFile.class);
                if (roomFile == null) {
                    return Optional.empty();
                }

                return Optional.of(new LoadedRoom(roomFile.toRoomData(), true));
            }

            LegacyRoomFile roomFile = GSON.fromJson(root, LegacyRoomFile.class);
            if (roomFile == null) {
                return Optional.empty();
            }

            return Optional.of(new LoadedRoom(roomFile.toRoomData(), false));
        } catch (IOException | JsonParseException | IllegalStateException | IllegalArgumentException e) {
            KarmaGateMod.LOGGER.warn("Failed to load room geometry from {}", file, e);
            return Optional.empty();
        }
    }

    private static boolean isCompactRoomFile(JsonObject root) {
        return root.has("v")
            && root.has("n")
            && root.has("c")
            && root.has("d")
            && root.has("g");
    }

    private static void writeRoomFile(MinecraftServer server, RoomData room) {
        Path file = roomFile(server, room.name());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                file,
                GSON.toJson(CompactRoomFile.fromRoomData(room)),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            KarmaGateMod.LOGGER.warn("Failed to write room geometry to {}", file, e);
        }
    }

    private static Path roomFile(MinecraftServer server, String roomName) {
        return server.getSavePath(WorldSavePath.ROOT)
            .resolve(MAP_DIRECTORY)
            .resolve(roomName + FILE_EXTENSION);
    }

    private static String encodeGeometry(RoomGeometry geometry) throws IOException {
        RoomGeometry storedGeometry = geometry == null ? RoomGeometry.empty() : geometry;
        String geometryJson = GSON.toJson(storedGeometry);

        ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutput = new GZIPOutputStream(byteOutput)) {
            gzipOutput.write(geometryJson.getBytes(StandardCharsets.UTF_8));
        }

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(byteOutput.toByteArray());
    }

    private static RoomGeometry decodeGeometry(String encodedGeometry) throws IOException {
        if (encodedGeometry == null || encodedGeometry.isBlank()) {
            return RoomGeometry.empty();
        }

        byte[] compressedBytes = Base64.getUrlDecoder().decode(encodedGeometry);

        String geometryJson;
        try (GZIPInputStream gzipInput = new GZIPInputStream(new ByteArrayInputStream(compressedBytes))) {
            geometryJson = new String(gzipInput.readAllBytes(), StandardCharsets.UTF_8);
        }

        RoomGeometry geometry = GSON.fromJson(geometryJson, RoomGeometry.class);
        return geometry == null ? RoomGeometry.empty() : geometry;
    }

    private record LoadedRoom(RoomData room, boolean compact) {
    }

    private record CompactRoomFile(
        int v,
        String n,
        int[] c,
        String d,
        String g
    ) {
        private static CompactRoomFile fromRoomData(RoomData room) throws IOException {
            BlockPos corner1 = room.corner1();
            BlockPos corner2 = room.corner2();

            return new CompactRoomFile(
                COMPACT_FILE_VERSION,
                room.name(),
                new int[] {
                    corner1.getX(), corner1.getY(), corner1.getZ(),
                    corner2.getX(), corner2.getY(), corner2.getZ()
                },
                room.dangerType().name(),
                encodeGeometry(room.geometry())
            );
        }

        private RoomData toRoomData() throws IOException {
            if (c == null || c.length < 6) {
                throw new JsonParseException("Invalid compact room corner data");
            }

            return RoomData.of(
                n,
                c[0], c[1], c[2],
                c[3], c[4], c[5],
                DangerType.fromSerialized(d),
                decodeGeometry(g)
            );
        }
    }

    /**
     * Legacy verbose format. Kept only so existing room JSON files still load.
     */
    private record LegacyRoomFile(
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