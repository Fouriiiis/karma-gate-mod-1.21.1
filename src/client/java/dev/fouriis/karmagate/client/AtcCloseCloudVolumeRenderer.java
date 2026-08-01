package dev.fouriis.karmagate.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.fouriis.karmagate.mixin.client.GameRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import java.util.ArrayList;

/** Draws cached native-resolution voxel cloud meshes on a stable world grid. */
public final class AtcCloseCloudVolumeRenderer {
    private static final Identifier[] PROFILE_TEXTURES = {
            Identifier.of("karma-gate-mod", "clouds/clouds1.png"),
            Identifier.of("karma-gate-mod", "clouds/clouds2.png"),
            Identifier.of("karma-gate-mod", "clouds/clouds3.png")
    };
    private static final Identifier DETAIL_TEXTURE =
            Identifier.of("karma-gate-mod", "clouds/cloudstexture.png");
    private static final Identifier NOISE_TEXTURE =
            Identifier.of("karma-gate-mod", "clouds/noise-hq.png");
    private static final long CLOUD_TIME_WRAP_TICKS = 24_000L;
    private static final ArrayList<Tile> VISIBLE_TILES = new ArrayList<>(32);

    private static ShaderProgram cachedProgram;
    private static Uniforms cachedUniforms;
    private static long lastDebugLogSecond = Long.MIN_VALUE;

    private AtcCloseCloudVolumeRenderer() {
    }

    public static void render(float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ShaderProgram program = AtcCloseCloudShaders.PROGRAM;
        if (mc.world == null || camera == null || program == null) {
            return;
        }
        AtcCloseCloudVolumeCache.ensureLoaded(mc.getResourceManager());
        AtcCloseCloudVolumeCache cache = AtcCloseCloudVolumeCache.get();
        if (cache == null) {
            return;
        }

        Vec3d cameraPosition = camera.getPos();
        float altitudeVisibility = AtcCloudVolumeRenderer.cloudLayerVisibility(
                (float) cameraPosition.y
        );
        if (altitudeVisibility <= 0.003f) {
            return;
        }

        float cloudBottom = AtcCloudVolumeRenderer.CLOUD_BOTTOM_Y.value();
        float cloudHeight = Math.max(
                1.0f,
                AtcCloudVolumeRenderer.CLOUD_TOP_Y.value() - cloudBottom
        );
        float tileWidth = AtcCloudVolumeRenderer.CLOSE_TILE_WIDTH_X.value();
        float tileDepth = AtcCloudVolumeRenderer.CLOSE_TILE_WIDTH_Z.value();
        float spacingX = Math.max(64.0f, tileWidth);
        float spacingZ = Math.max(64.0f, tileDepth);
        float closeRadius = closeRadius();
        double northOffset = northOffset(mc, tickDelta);

        int minTileX = (int) Math.floor(
                (cameraPosition.x - closeRadius - tileWidth * 0.5f) / spacingX
        );
        int maxTileX = (int) Math.ceil(
                (cameraPosition.x + closeRadius + tileWidth * 0.5f) / spacingX
        );
        int minTileZ = (int) Math.floor(
                (cameraPosition.z - northOffset - closeRadius - tileDepth * 0.5f) / spacingZ
        );
        int maxTileZ = (int) Math.ceil(
                (cameraPosition.z - northOffset + closeRadius + tileDepth * 0.5f) / spacingZ
        );

        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        Matrix4f savedModelView = new Matrix4f(modelViewStack);
        modelViewStack.identity();
        RenderSystem.applyModelViewMatrix();

        float fovRadians = dynamicFovRadians(mc, camera, tickDelta);
        float aspect = (float) mc.getWindow().getFramebufferWidth()
                / Math.max(1, mc.getWindow().getFramebufferHeight());
        Matrix4f projection = cloudProjection(mc, fovRadians, aspect);
        Matrix4f view = viewMatrix(camera);
        Matrix4f frustumView = new Matrix4f().rotation(camera.getRotation()).transpose();
        Frustum frustum = new Frustum(frustumView, projection);
        frustum.setPosition(cameraPosition.x, cameraPosition.y, cameraPosition.z);

        collectVisibleTiles(
                cameraPosition,
                frustum,
                minTileX,
                maxTileX,
                minTileZ,
                maxTileZ,
                spacingX,
                spacingZ,
                tileWidth,
                tileDepth,
                cloudBottom,
                cloudHeight,
                closeRadius,
                northOffset
        );

        RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);
        RenderSystem.setShader(() -> program);
        RenderSystem.setShaderTexture(0, DETAIL_TEXTURE);
        RenderSystem.setShaderTexture(1, NOISE_TEXTURE);
        program.addSampler("Sampler0", mc.getTextureManager().getTexture(DETAIL_TEXTURE));
        program.addSampler("Sampler1", mc.getTextureManager().getTexture(NOISE_TEXTURE));
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();

        AtcSkyRenderer.CloudPalette palette = AtcSkyRenderer.cloudPalette(tickDelta);
        float time = cloudAnimationTime(mc, tickDelta)
                * AtcCloudVolumeRenderer.CLOSE_CLOUD_MOTION_SCALE.value();
        float light = dayLight(mc, cameraPosition, tickDelta);
        Matrix4f identityModel = new Matrix4f(RenderSystem.getModelViewMatrix());

        try {
            program.bind();
            Uniforms uniforms = uniforms(program);
            setUniformMat4(uniforms.viewMat, view);
            setUniform1f(uniforms.time, time);
            setUniform1f(uniforms.light, light);
            setUniform1f(
                    uniforms.opacity,
                    AtcCloudVolumeRenderer.CLOSE_VOLUME_OPACITY.value() * altitudeVisibility
            );
            setUniform1f(uniforms.firstRadius, closeRadius);
            setUniform1f(uniforms.fadeWidth, handoffFadeWidth(spacingX, spacingZ));
            setUniform2f(
                    uniforms.cameraXZ,
                    (float) cameraPosition.x,
                    (float) cameraPosition.z
            );
            setUniform3f(
                    uniforms.cameraPos,
                    (float) cameraPosition.x,
                    (float) cameraPosition.y,
                    (float) cameraPosition.z
            );
            Vector3f atmosphere = palette.atmosphere();
            Vector3f multiply = palette.multiply();
            setUniform3f(
                    uniforms.atmosphereColor,
                    atmosphere.x,
                    atmosphere.y,
                    atmosphere.z
            );
            setUniform3f(uniforms.cloudMultiply, multiply.x, multiply.y, multiply.z);
            setUniform3f(uniforms.voxelGrid, 700.0f, 150.0f, 700.0f);
            float noiseInfluence = AtcCloudVolumeRenderer.CLOSE_VOXEL_NOISE_INFLUENCE.value();
            setUniform1f(
                    uniforms.warp,
                    AtcCloudVolumeRenderer.CLOSE_VOLUME_WARP.value() * noiseInfluence
            );
            setUniform1f(uniforms.noiseInfluence, noiseInfluence);

            BufferRenderer.resetCurrentVertexBuffer();
            for (Tile tile : VISIBLE_TILES) {
                AtcCloseCloudVolumeCache.Entry entry = cache.entry(
                        0,
                        tile.front,
                        tile.side,
                        0
                );
                if (entry == null || entry.buffer() == null || entry.buffer().isClosed()) {
                    continue;
                }
                RenderSystem.setShaderTexture(2, PROFILE_TEXTURES[tile.front]);
                RenderSystem.setShaderTexture(3, PROFILE_TEXTURES[tile.side]);
                program.addSampler(
                        "Sampler2",
                        mc.getTextureManager().getTexture(PROFILE_TEXTURES[tile.front])
                );
                program.addSampler(
                        "Sampler3",
                        mc.getTextureManager().getTexture(PROFILE_TEXTURES[tile.side])
                );
                setUniform3f(uniforms.tileOrigin, tile.x, cloudBottom, tile.z);
                setUniform3f(uniforms.tileScale, tileWidth, cloudHeight, tileDepth);
                setUniform2f(uniforms.profileOffset, tile.offsetU, tile.offsetW);
                setUniform2f(
                        uniforms.warpPhase,
                        AtcCloseCloudVolumeBuilder.warpPhaseX(tile.front, tile.side, 0),
                        AtcCloseCloudVolumeBuilder.warpPhaseZ(tile.front, tile.side, 0)
                );
                VertexBuffer buffer = entry.buffer();
                program.bind();
                buffer.bind();
                buffer.draw(identityModel, projection, program);
                VertexBuffer.unbind();
            }
            debugVisibleTileCount(mc, VISIBLE_TILES.size());
        } finally {
            BufferRenderer.resetCurrentVertexBuffer();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            modelViewStack.set(savedModelView);
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorter.BY_DISTANCE);
        }
    }

    public static void close() {
        cachedProgram = null;
        cachedUniforms = null;
        VISIBLE_TILES.clear();
    }

    public static float closeRadius() {
        return AtcCloudVolumeRenderer.CLOUD_BAND_SPACING.value()
                * (AtcCloudVolumeRenderer.CLOSE_LAYER_COUNT.intValue() + 0.5f);
    }

    private static void collectVisibleTiles(Vec3d camera,
                                            Frustum frustum,
                                            int minTileX,
                                            int maxTileX,
                                            int minTileZ,
                                            int maxTileZ,
                                            float spacingX,
                                            float spacingZ,
                                            float tileWidth,
                                            float tileDepth,
                                            float cloudBottom,
                                            float cloudHeight,
                                            float closeRadius,
                                            double northOffset) {
        VISIBLE_TILES.clear();
        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                Tile tile = tile(tileX, tileZ, spacingX, spacingZ, northOffset);
                if (nearestDistanceToTile(
                        (float) camera.x,
                        (float) camera.z,
                        tile,
                        tileWidth,
                        tileDepth
                ) > closeRadius) {
                    continue;
                }
                Box bounds = new Box(
                        tile.x - tileWidth * 0.5f,
                        cloudBottom,
                        tile.z - tileDepth * 0.5f,
                        tile.x + tileWidth * 0.5f,
                        cloudBottom + cloudHeight,
                        tile.z + tileDepth * 0.5f
                );
                if (!bounds.contains(camera) && !frustum.isVisible(bounds)) {
                    continue;
                }
                VISIBLE_TILES.add(tile);
            }
        }
        VISIBLE_TILES.sort((left, right) -> {
            float leftX = left.x - (float) camera.x;
            float leftZ = left.z - (float) camera.z;
            float rightX = right.x - (float) camera.x;
            float rightZ = right.z - (float) camera.z;
            float leftDistanceSquared = leftX * leftX + leftZ * leftZ;
            float rightDistanceSquared = rightX * rightX + rightZ * rightZ;
            return Float.compare(rightDistanceSquared, leftDistanceSquared);
        });
    }

    private static Tile tile(int tileX,
                             int tileZ,
                             float spacingX,
                             float spacingZ,
                             double northOffset) {
        long seed = hash(tileX, tileZ);
        int front = profileIndex(samplerHash(tileX, tileZ, 0));
        int side = profileIndex(samplerHash(tileX, tileZ, 1));
        return new Tile(
                tileX * spacingX,
                (float) (tileZ * (double) spacingZ + northOffset),
                front,
                side,
                hashUnit(seed ^ 0x43A31D7BL),
                hashUnit(seed ^ 0x7F4A7C15L)
        );
    }

    private static float nearestDistanceToTile(float cameraX,
                                               float cameraZ,
                                               Tile tile,
                                               float tileWidth,
                                               float tileDepth) {
        float dx = Math.max(Math.abs(tile.x - cameraX) - tileWidth * 0.5f, 0.0f);
        float dz = Math.max(Math.abs(tile.z - cameraZ) - tileDepth * 0.5f, 0.0f);
        return MathHelper.sqrt(dx * dx + dz * dz);
    }

    private static float handoffFadeWidth(float spacingX, float spacingZ) {
        float configured = AtcCloudVolumeRenderer.CLOSE_HANDOFF_FADE_WIDTH.value();
        return configured <= 0.0f ? Math.max(spacingX, spacingZ) : configured;
    }

    private static double northOffset(MinecraftClient mc, float tickDelta) {
        return -(mc.world.getTime() + tickDelta)
                * (AtcCloudVolumeRenderer.CLOUD_NORTH_SPEED.value() / 20.0);
    }

    private static Matrix4f viewMatrix(Camera camera) {
        Vec3d cameraPosition = camera.getPos();
        return new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate(
                        (float) -cameraPosition.x,
                        (float) -cameraPosition.y,
                        (float) -cameraPosition.z
                );
    }

    private static float dynamicFovRadians(MinecraftClient mc,
                                           Camera camera,
                                           float tickDelta) {
        double fov = ((GameRendererAccessor) mc.gameRenderer)
                .karmaGate$invokeGetFov(camera, tickDelta, true);
        return (float) Math.toRadians(fov);
    }

    private static Matrix4f cloudProjection(MinecraftClient mc,
                                            float fovRadians,
                                            float aspect) {
        float far = Math.max(
                128.0f,
                (float) mc.options.getClampedViewDistance() * 16.0f
        ) * 100.0f;
        far = Math.max(far, AtcCloudVolumeRenderer.DISTANT_MAX_DISTANCE.value() * 1.1f);
        return new Matrix4f().setPerspective(fovRadians, aspect, 0.05f, far);
    }

    private static float dayLight(MinecraftClient mc,
                                  Vec3d cameraPosition,
                                  float tickDelta) {
        Vec3d sky = mc.world.getSkyColor(cameraPosition, tickDelta);
        float luma = 0.2126f * MathHelper.clamp((float) sky.x, 0.0f, 1.0f)
                + 0.7152f * MathHelper.clamp((float) sky.y, 0.0f, 1.0f)
                + 0.0722f * MathHelper.clamp((float) sky.z, 0.0f, 1.0f);
        return MathHelper.clamp(0.52f + 0.55f * luma, 0.52f, 1.0f);
    }

    private static float cloudAnimationTime(MinecraftClient mc, float tickDelta) {
        return Math.floorMod(mc.world.getTime(), CLOUD_TIME_WRAP_TICKS) + tickDelta;
    }

    private static void debugVisibleTileCount(MinecraftClient mc, int count) {
        if (!mc.getDebugHud().shouldShowDebugHud()) {
            return;
        }
        long second = System.currentTimeMillis() / 1000L;
        if (second != lastDebugLogSecond) {
            lastDebugLogSecond = second;
            dev.fouriis.karmagate.KarmaGateMod.LOGGER.info(
                    "Visible close voxel cloud tiles: {}",
                    count
            );
        }
    }

    private static Uniforms uniforms(ShaderProgram program) {
        if (cachedProgram != program || cachedUniforms == null) {
            cachedProgram = program;
            cachedUniforms = new Uniforms(program);
        }
        return cachedUniforms;
    }

    private static void setUniform1f(GlUniform uniform, float value) {
        if (uniform != null) uniform.set(value);
    }

    private static void setUniform2f(GlUniform uniform, float x, float y) {
        if (uniform != null) uniform.set(x, y);
    }

    private static void setUniform3f(GlUniform uniform, float x, float y, float z) {
        if (uniform != null) uniform.set(x, y, z);
    }

    private static void setUniformMat4(GlUniform uniform, Matrix4f value) {
        if (uniform != null) uniform.set(value);
    }

    private static long hash(int x, int z) {
        long h = 1469598103934665603L;
        h = (h ^ x) * 1099511628211L;
        h = (h ^ z) * 1099511628211L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static long samplerHash(int x, int z, int axis) {
        return hash(x ^ (axis * 0x5bd1e995), z + axis * 97);
    }

    private static int profileIndex(long seed) {
        return (int) Math.floorMod(seed, PROFILE_TEXTURES.length);
    }

    private static float hashUnit(long seed) {
        return (seed >>> 40 & 0xFFFFFFL) / (float) 0x01000000;
    }

    private record Tile(float x,
                        float z,
                        int front,
                        int side,
                        float offsetU,
                        float offsetW) {
    }

    private static final class Uniforms {
        private final GlUniform viewMat;
        private final GlUniform time;
        private final GlUniform light;
        private final GlUniform opacity;
        private final GlUniform firstRadius;
        private final GlUniform fadeWidth;
        private final GlUniform cameraXZ;
        private final GlUniform cameraPos;
        private final GlUniform tileOrigin;
        private final GlUniform tileScale;
        private final GlUniform profileOffset;
        private final GlUniform voxelGrid;
        private final GlUniform warp;
        private final GlUniform warpPhase;
        private final GlUniform noiseInfluence;
        private final GlUniform atmosphereColor;
        private final GlUniform cloudMultiply;

        private Uniforms(ShaderProgram program) {
            viewMat = program.getUniform("uViewMat");
            time = program.getUniform("uTime");
            light = program.getUniform("uLight");
            opacity = program.getUniform("uOpacity");
            firstRadius = program.getUniform("uFirstRadius");
            fadeWidth = program.getUniform("uFadeWidth");
            cameraXZ = program.getUniform("uCameraXZ");
            cameraPos = program.getUniform("uCameraPos");
            tileOrigin = program.getUniform("uTileOrigin");
            tileScale = program.getUniform("uTileScale");
            profileOffset = program.getUniform("uProfileOffset");
            voxelGrid = program.getUniform("uVoxelGrid");
            warp = program.getUniform("uWarp");
            warpPhase = program.getUniform("uWarpPhase");
            noiseInfluence = program.getUniform("uNoiseInfluence");
            atmosphereColor = program.getUniform("uAtmosphereColor");
            cloudMultiply = program.getUniform("uCloudMultiply");
        }
    }
}
