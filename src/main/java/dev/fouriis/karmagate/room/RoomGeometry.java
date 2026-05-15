package dev.fouriis.karmagate.room;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.List;

/**
 * Baked room geometry that can be stored on disk and synced to clients.
 */
public record RoomGeometry(List<FaceData> faces, List<LineData> lines, List<PipeLinkData> pipeLinks) {

    private static final Gson GSON = new GsonBuilder().create();

    public static RoomGeometry empty() {
        return new RoomGeometry(List.of(), List.of(), List.of());
    }

    public static RoomGeometry fromJson(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }

        RoomGeometry geometry = GSON.fromJson(json, RoomGeometry.class);
        if (geometry == null) {
            return empty();
        }

        return geometry.normalize();
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public byte[] toCompressedBytes() {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            gzip.finish();
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to compress room geometry", e);
        }
    }

    public static RoomGeometry fromCompressedBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return empty();
        }

        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return fromJson(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decompress room geometry", e);
        }
    }

    public boolean isEmpty() {
        return faces.isEmpty() && lines.isEmpty() && pipeLinks.isEmpty();
    }

    private RoomGeometry normalize() {
        return new RoomGeometry(
            faces == null ? List.of() : List.copyOf(faces),
            lines == null ? List.of() : List.copyOf(lines),
            pipeLinks == null ? List.of() : List.copyOf(pipeLinks)
        );
    }

    public record FaceData(String axis, int plane, int a0, int b0, int a1, int b1, int normalSign) {
    }

    public record LineData(int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    public record PipeLinkData(
        int startX,
        int startY,
        int startZ,
        int endX,
        int endY,
        int endZ,
        String startDirection,
        String endDirection
    ) {
    }
}