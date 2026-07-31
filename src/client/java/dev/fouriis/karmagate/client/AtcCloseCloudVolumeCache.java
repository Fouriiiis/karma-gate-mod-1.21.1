package dev.fouriis.karmagate.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class AtcCloseCloudVolumeCache implements AutoCloseable {
    private static final Identifier[] PROFILE_IDS = {
            Identifier.of("karma-gate-mod", "clouds/clouds1.png"),
            Identifier.of("karma-gate-mod", "clouds/clouds2.png"),
            Identifier.of("karma-gate-mod", "clouds/clouds3.png")
    };
    private static final String[] PROFILE_NAMES = {"clouds1", "clouds2", "clouds3"};
    private static final Identifier NOISE_ID =
            Identifier.of("karma-gate-mod", "clouds/noise-hq.png");
    private static final int LOD_COUNT = 2;
    private static final int FULL_BRIGHT = LightmapTextureManager.pack(15, 15);

    private static AtcCloseCloudVolumeCache active;
    private static long activeSettingsKey = Long.MIN_VALUE;
    private static ResourceManager lastResourceManager;

    private final Entry[] entries;
    private final Stats stats;

    private AtcCloseCloudVolumeCache(Entry[] entries, Stats stats) {
        this.entries = entries;
        this.stats = stats;
    }

    public static AtcCloseCloudVolumeCache get() {
        return active;
    }

    public static void closeActive() {
        AtcCloseCloudVolumeCache previous = active;
        active = null;
        activeSettingsKey = Long.MIN_VALUE;
        if (previous != null) {
            previous.close();
        }
    }

    public static void invalidate() {
        activeSettingsKey = Long.MIN_VALUE;
    }

    public static void ensureLoaded(ResourceManager manager) {
        if (manager == null) {
            return;
        }
        lastResourceManager = manager;
        long key = settingsKey();
        if (active != null && activeSettingsKey == key) {
            return;
        }
        reload(manager);
    }

    public static void reload(ResourceManager manager) {
        if (manager == null) {
            return;
        }
        lastResourceManager = manager;
        long key = settingsKey();
        long start = System.nanoTime();
        try {
            AtcCloudProfile[] profiles = new AtcCloudProfile[PROFILE_IDS.length];
            for (int i = 0; i < PROFILE_IDS.length; i++) {
                profiles[i] = AtcCloudProfile.load(manager, PROFILE_IDS[i], PROFILE_NAMES[i]);
            }
            KarmaGateMod.LOGGER.info("Loading close cloud horizontal warp noise from {}", NOISE_ID);
            AtcCloudNoiseMap noise = AtcCloudNoiseMap.load(manager, NOISE_ID);
            long profileDone = System.nanoTime();

            AtcCloseCloudVolumeBuilder builder = new AtcCloseCloudVolumeBuilder();
            ArrayList<MeshBuild> builtMeshes = new ArrayList<>();
            int baseResolution = AtcCloudVolumeRenderer.CLOSE_VOLUME_RESOLUTION.intValue();
            for (int lod = 0; lod < LOD_COUNT; lod++) {
                int resolution = lod == 0
                        ? baseResolution
                        : Math.max(40, Math.round(baseResolution * 0.50f));
                for (int front = 0; front < profiles.length; front++) {
                    for (int side = 0; side < profiles.length; side++) {
                        for (int variant = 0; variant < AtcCloseCloudVolumeBuilder.VARIANTS_PER_PAIR; variant++) {
                            AtcCloudMesh mesh = builder.build(
                                    profiles[front],
                                    profiles[side],
                                    noise,
                                    front,
                                    side,
                                    variant,
                                    resolution,
                                    AtcCloudVolumeRenderer.CLOSE_VOLUME_ISO_LEVEL.value(),
                                    AtcCloudVolumeRenderer.CLOSE_VOLUME_BREAKUP.value(),
                                    AtcCloudVolumeRenderer.CLOSE_VOLUME_WARP.value(),
                                    AtcCloudVolumeRenderer.CLOSE_VOLUME_DEPTH_SCALE.value()
                            );
                            builtMeshes.add(new MeshBuild(lod, front, side, variant, mesh));
                        }
                    }
                }
            }
            long meshDone = System.nanoTime();

            Entry[] entries = new Entry[builtMeshes.size()];
            int totalVertices = 0;
            int totalTriangles = 0;
            for (int i = 0; i < builtMeshes.size(); i++) {
                MeshBuild build = builtMeshes.get(i);
                VertexBuffer buffer = upload(build.mesh);
                entries[i] = new Entry(
                        build.lod,
                        build.front,
                        build.side,
                        build.variant,
                        build.mesh,
                        buffer
                );
                totalVertices += build.mesh.vertexCount();
                totalTriangles += build.mesh.triangleCount();
            }
            long uploadDone = System.nanoTime();

            AtcCloseCloudVolumeCache replacement = new AtcCloseCloudVolumeCache(
                    entries,
                    new Stats(
                            nanosToMillis(profileDone - start),
                            nanosToMillis(meshDone - profileDone),
                            nanosToMillis(uploadDone - meshDone),
                            entries.length,
                            totalVertices,
                            totalTriangles
                    )
            );
            AtcCloseCloudVolumeCache previous = active;
            active = replacement;
            activeSettingsKey = key;
            if (previous != null) {
                previous.close();
            }
            replacement.log();
        } catch (Exception ex) {
            KarmaGateMod.LOGGER.error("Failed to rebuild close cloud volume cache", ex);
        }
    }

    public static void reloadLastManager() {
        if (lastResourceManager != null) {
            reload(lastResourceManager);
        } else {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                reload(client.getResourceManager());
            }
        }
    }

    public Entry entry(int lod, int front, int side, int variant) {
        int variantCount = AtcCloseCloudVolumeBuilder.VARIANTS_PER_PAIR;
        int idx = (((lod * PROFILE_IDS.length + front) * PROFILE_IDS.length + side)
                * variantCount + variant);
        if (idx < 0 || idx >= entries.length) {
            return null;
        }
        return entries[idx];
    }

    @Override
    public void close() {
        for (Entry entry : entries) {
            if (entry != null && entry.buffer != null) {
                try {
                    entry.buffer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void log() {
        int sampleVertices = entries.length > 0 ? entries[0].mesh.vertexCount() : 0;
        int sampleTriangles = entries.length > 0 ? entries[0].mesh.triangleCount() : 0;
        KarmaGateMod.LOGGER.info(
                "Close cloud cache built: profile analysis {} ms, mesh generation {} ms, mesh count {}, first mesh {} vertices / {} triangles, total cached vertices {}, total cached triangles {}, GPU upload {} ms",
                stats.profileMs,
                stats.meshMs,
                stats.meshCount,
                sampleVertices,
                sampleTriangles,
                stats.totalVertices,
                stats.totalTriangles,
                stats.uploadMs
        );
    }

    private static VertexBuffer upload(AtcCloudMesh mesh) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(
                VertexFormat.DrawMode.TRIANGLES,
                VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
        );
        float[] positions = mesh.positions;
        float[] normals = mesh.normals;
        float[] heights = mesh.heights;
        float[] shades = mesh.shades;
        int[] indices = mesh.indices;
        for (int index : indices) {
            int p = index * 3;
            int n = index * 3;
            float height = heights[index];
            float profileShade = shades[index];
            int vertexTint = Math.round((0.88f + height * 0.12f) * 255.0f);
            buffer.vertex(positions[p], positions[p + 1], positions[p + 2])
                    .color(vertexTint, vertexTint, vertexTint, 255)
                    .texture(height, profileShade)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(FULL_BRIGHT)
                    .normal(normals[n], normals[n + 1], normals[n + 2]);
        }

        BuiltBuffer built = buffer.end();
        VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        BufferRenderer.resetCurrentVertexBuffer();
        vertexBuffer.bind();
        vertexBuffer.upload(built);
        VertexBuffer.unbind();
        try {
            built.close();
        } catch (Exception ignored) {
        }
        BufferRenderer.resetCurrentVertexBuffer();
        return vertexBuffer;
    }

    private static long settingsKey() {
        long h = 1469598103934665603L;
        h = mix(h, AtcCloudVolumeRenderer.CLOSE_VOLUME_RESOLUTION.intValue());
        h = mix(h, Float.floatToIntBits(AtcCloudVolumeRenderer.CLOSE_VOLUME_ISO_LEVEL.value()));
        h = mix(h, Float.floatToIntBits(AtcCloudVolumeRenderer.CLOSE_VOLUME_BREAKUP.value()));
        h = mix(h, Float.floatToIntBits(AtcCloudVolumeRenderer.CLOSE_VOLUME_WARP.value()));
        h = mix(h, Float.floatToIntBits(AtcCloudVolumeRenderer.CLOSE_VOLUME_DEPTH_SCALE.value()));
        return h;
    }

    private static long mix(long h, int value) {
        h ^= value;
        return h * 1099511628211L;
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, Math.round(nanos / 1_000_000.0));
    }

    public record Entry(int lod,
                        int front,
                        int side,
                        int variant,
                        AtcCloudMesh mesh,
                        VertexBuffer buffer) {
    }

    private record MeshBuild(int lod,
                             int front,
                             int side,
                             int variant,
                             AtcCloudMesh mesh) {
    }

    private record Stats(long profileMs,
                         long meshMs,
                         long uploadMs,
                         int meshCount,
                         int totalVertices,
                         int totalTriangles) {
    }
}
