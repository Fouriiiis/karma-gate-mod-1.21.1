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
import net.minecraft.util.math.MathHelper;

public final class AtcCloseCloudVolumeCache implements AutoCloseable {
    private static final Identifier[] PROFILE_IDS = {
            Identifier.of("karma-gate-mod", "clouds/clouds1.png"),
            Identifier.of("karma-gate-mod", "clouds/clouds2.png"),
            Identifier.of("karma-gate-mod", "clouds/clouds3.png")
    };
    private static final String[] PROFILE_NAMES = {"clouds1", "clouds2", "clouds3"};
    private static final Identifier NOISE_ID =
            Identifier.of("karma-gate-mod", "clouds/noise-hq.png");
    private static final int LOD_COUNT = 1;
    private static final int FULL_BRIGHT = LightmapTextureManager.pack(15, 15);
    static final int LINE_PERIOD = 3;
    private static final int[] X_LINE_PROFILES = {0, 2, 1};
    private static final int[] Z_LINE_PROFILES = {1, 0, 2};

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
        Entry[] pendingEntries = null;
        try {
            AtcCloudProfile[] profiles = new AtcCloudProfile[PROFILE_IDS.length];
            for (int i = 0; i < PROFILE_IDS.length; i++) {
                profiles[i] = AtcCloudProfile.load(manager, PROFILE_IDS[i], PROFILE_NAMES[i]);
            }
            KarmaGateMod.LOGGER.info("Loading close cloud horizontal warp noise from {}", NOISE_ID);
            AtcCloudNoiseMap noise = AtcCloudNoiseMap.load(manager, NOISE_ID);
            long profileDone = System.nanoTime();

            int variants = AtcCloseCloudVolumeBuilder.VARIANTS_PER_PAIR;
            int patternCount = LINE_PERIOD * LINE_PERIOD * variants;
            Entry[] entries = new Entry[
                    LOD_COUNT * LINE_PERIOD * LINE_PERIOD * variants
            ];
            pendingEntries = entries;
            long meshNanos = 0L;
            long uploadNanos = 0L;
            int totalVertices = 0;
            int totalTriangles = 0;
            for (int job = 0; job < patternCount; job++) {
                int patternX = job / (LINE_PERIOD * variants);
                int patternZ = (job / variants) % LINE_PERIOD;
                int variant = job % variants;
                int west = xLineProfile(patternX);
                int east = xLineProfile(patternX + 1);
                int south = zLineProfile(patternZ);
                int north = zLineProfile(patternZ + 1);
                long meshStart = System.nanoTime();
                AtcCloudMesh mesh = new AtcCloseCloudVolumeBuilder().build(
                        profiles[south],
                        profiles[north],
                        profiles[west],
                        profiles[east],
                        noise,
                        variant,
                        AtcCloudVolumeRenderer.CLOSE_VOLUME_RESOLUTION.intValue(),
                        AtcCloudVolumeRenderer.CLOSE_VOLUME_ISO_LEVEL.value(),
                        AtcCloudVolumeRenderer.CLOSE_VOLUME_BREAKUP.value(),
                        AtcCloudVolumeRenderer.CLOSE_VOLUME_WARP.value(),
                        AtcCloudVolumeRenderer.CLOSE_VOXEL_NOISE_INFLUENCE.value(),
                        AtcCloudVolumeRenderer.CLOSE_VOXEL_ROUNDING.value(),
                        AtcCloudVolumeRenderer.CLOSE_VOLUME_DEPTH_SCALE.value()
                );
                meshNanos += System.nanoTime() - meshStart;
                int vertexCount = mesh.vertexCount();
                int triangleCount = mesh.triangleCount();
                int gridSizeX = mesh.gridSizeX();
                int gridSizeY = mesh.gridSizeY();
                int gridSizeZ = mesh.gridSizeZ();
                long uploadStart = System.nanoTime();
                VertexBuffer buffer = upload(mesh);
                uploadNanos += System.nanoTime() - uploadStart;
                int entryIndex = (((0 * LINE_PERIOD + patternX)
                        * LINE_PERIOD + patternZ) * variants + variant);
                entries[entryIndex] = new Entry(
                        0,
                        patternX,
                        patternZ,
                        variant,
                        south,
                        north,
                        west,
                        east,
                        vertexCount,
                        triangleCount,
                        gridSizeX,
                        gridSizeY,
                        gridSizeZ,
                        buffer
                );
                mesh = null;
                totalVertices += vertexCount;
                totalTriangles += triangleCount;
            }

            AtcCloseCloudVolumeCache replacement = new AtcCloseCloudVolumeCache(
                    entries,
                    new Stats(
                            nanosToMillis(profileDone - start),
                            nanosToMillis(meshNanos),
                            nanosToMillis(uploadNanos),
                            patternCount,
                            totalVertices,
                            totalTriangles
                    )
            );
            AtcCloseCloudVolumeCache previous = active;
            active = replacement;
            activeSettingsKey = key;
            pendingEntries = null;
            if (previous != null) {
                previous.close();
            }
            replacement.log();
        } catch (OutOfMemoryError error) {
            closeEntries(pendingEntries);
            activeSettingsKey = Long.MIN_VALUE;
            KarmaGateMod.LOGGER.error(
                    "Not enough heap to rebuild close cloud meshes at resolution {}; keeping the previous cache and skipping close clouds if none exists",
                    AtcCloudVolumeRenderer.CLOSE_VOLUME_RESOLUTION.intValue(),
                    error
            );
        } catch (Exception ex) {
            closeEntries(pendingEntries);
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

    public Entry entry(int lod, int patternX, int patternZ, int variant) {
        int variantCount = AtcCloseCloudVolumeBuilder.VARIANTS_PER_PAIR;
        int idx = (((lod * LINE_PERIOD + patternX) * LINE_PERIOD + patternZ)
                * variantCount + variant);
        if (idx < 0 || idx >= entries.length) {
            return null;
        }
        return entries[idx];
    }

    @Override
    public void close() {
        closeEntries(entries);
    }

    private static void closeEntries(Entry[] entries) {
        if (entries == null) {
            return;
        }
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
        Entry sample = null;
        for (Entry entry : entries) {
            if (entry != null) {
                sample = entry;
                break;
            }
        }
        int sampleVertices = sample != null ? sample.vertexCount : 0;
        int sampleTriangles = sample != null ? sample.triangleCount : 0;
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
        float[] thicknesses = mesh.thicknesses;
        int[] indices = mesh.indices;
        for (int index : indices) {
            int p = index * 3;
            int n = index * 3;
            float height = heights[index];
            float profileShade = shades[index];
            int interiorThickness = Math.round(MathHelper.clamp(thicknesses[index], 0.0f, 1.0f) * 255.0f);
            int vertexTint = Math.round((0.88f + height * 0.12f) * 255.0f);
            buffer.vertex(positions[p], positions[p + 1], positions[p + 2])
                    .color(vertexTint, vertexTint, vertexTint, interiorThickness)
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
        h = mix(h, Float.floatToIntBits(AtcCloudVolumeRenderer.CLOSE_VOLUME_ISO_LEVEL.value()));
        h = mix(h, AtcCloudVolumeRenderer.CLOSE_VOLUME_RESOLUTION.intValue());
        h = mix(h, Float.floatToIntBits(AtcCloudVolumeRenderer.CLOSE_VOLUME_BREAKUP.value()));
        h = mix(h, Float.floatToIntBits(AtcCloudVolumeRenderer.CLOSE_VOLUME_WARP.value()));
        h = mix(h, Float.floatToIntBits(AtcCloudVolumeRenderer.CLOSE_VOXEL_NOISE_INFLUENCE.value()));
        h = mix(h, Float.floatToIntBits(AtcCloudVolumeRenderer.CLOSE_VOXEL_ROUNDING.value()));
        return h;
    }

    private static long mix(long h, int value) {
        h ^= value;
        return h * 1099511628211L;
    }

    static int xLineProfile(int lineX) {
        return X_LINE_PROFILES[Math.floorMod(lineX, LINE_PERIOD)];
    }

    static int zLineProfile(int lineZ) {
        return Z_LINE_PROFILES[Math.floorMod(lineZ, LINE_PERIOD)];
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, Math.round(nanos / 1_000_000.0));
    }

    public record Entry(int lod,
                        int patternX,
                        int patternZ,
                        int variant,
                        int southProfile,
                        int northProfile,
                        int westProfile,
                        int eastProfile,
                        int vertexCount,
                        int triangleCount,
                        int gridSizeX,
                        int gridSizeY,
                        int gridSizeZ,
                        VertexBuffer buffer) {
    }

    private record Stats(long profileMs,
                         long meshMs,
                         long uploadMs,
                         int meshCount,
                         int totalVertices,
                         int totalTriangles) {
    }
}
