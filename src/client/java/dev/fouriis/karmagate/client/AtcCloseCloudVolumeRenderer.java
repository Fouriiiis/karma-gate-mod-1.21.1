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
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;

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
    // Tile pattern coordinates select four profiles from shared world-grid
    // lines; neighbouring tiles consequently reference the same profile on
    // their common edge.
    private static final ArrayList<Tile> VISIBLE_TILES = new ArrayList<>(32);
    private static final ArrayList<Tile> TILE_POOL = new ArrayList<>(32);
    private static final Comparator<Tile> FAR_TO_NEAR = (left, right) ->
            Float.compare(right.distanceSquared, left.distanceSquared);
    private static final float VIEW_CULL_MARGIN_RADIANS = (float) Math.toRadians(2.0);
    private static int tilePoolCursor;

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
        float horizontalHalfFov = (float) Math.atan(
                Math.tan(fovRadians * 0.5f) * aspect
        ) + VIEW_CULL_MARGIN_RADIANS;
        Vector3f cameraForward = new Vector3f(0.0f, 0.0f, -1.0f)
                .rotate(camera.getRotation());
        float horizontalForwardLength = MathHelper.sqrt(
                cameraForward.x * cameraForward.x
                        + cameraForward.z * cameraForward.z
        );
        float forwardX = horizontalForwardLength > 0.05f
                ? cameraForward.x / horizontalForwardLength
                : 0.0f;
        float forwardZ = horizontalForwardLength > 0.05f
                ? cameraForward.z / horizontalForwardLength
                : 0.0f;
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
                northOffset,
                forwardX,
                forwardZ,
                Math.min(horizontalHalfFov, (float) Math.PI),
                horizontalForwardLength > 0.05f
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
        AbstractTexture profileTexture0 = mc.getTextureManager().getTexture(PROFILE_TEXTURES[0]);
        AbstractTexture profileTexture1 = mc.getTextureManager().getTexture(PROFILE_TEXTURES[1]);
        AbstractTexture profileTexture2 = mc.getTextureManager().getTexture(PROFILE_TEXTURES[2]);

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
            setUniform1f(
                    uniforms.gradientLayerSpacing,
                    AtcCloudVolumeRenderer.CLOSE_GRADIENT_LAYER_SPACING.value()
            );
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
            float noiseInfluence = AtcCloudVolumeRenderer.CLOSE_VOXEL_NOISE_INFLUENCE.value();
            setUniform1f(
                    uniforms.warp,
                    AtcCloudVolumeRenderer.CLOSE_VOLUME_WARP.value() * noiseInfluence
            );
            setUniform1f(uniforms.noiseInfluence, noiseInfluence);
            setUniform3f(uniforms.tileScale, tileWidth, cloudHeight, tileDepth);
            setUniform2f(uniforms.profileOffset, 0.0f, 0.0f);
            setUniform2f(
                    uniforms.warpPhase,
                    AtcCloseCloudVolumeBuilder.warpPhaseX(0, 0, 0),
                    AtcCloseCloudVolumeBuilder.warpPhaseZ(0, 0, 0)
            );

            BufferRenderer.resetCurrentVertexBuffer();
            int boundSouth = -1;
            int boundWest = -1;
            int boundNorth = -1;
            int boundEast = -1;
            int boundGridX = -1;
            int boundGridY = -1;
            int boundGridZ = -1;
            for (Tile tile : VISIBLE_TILES) {
                AtcCloseCloudVolumeCache.Entry entry = cache.entry(
                        0,
                        tile.patternX,
                        tile.patternZ,
                        0
                );
                if (entry == null || entry.buffer() == null || entry.buffer().isClosed()) {
                    continue;
                }
                if (boundSouth != entry.southProfile()) {
                    boundSouth = entry.southProfile();
                    bindProfileSampler(
                            program,
                            2,
                            "Sampler2",
                            boundSouth,
                            profileTexture0,
                            profileTexture1,
                            profileTexture2
                    );
                }
                if (boundWest != entry.westProfile()) {
                    boundWest = entry.westProfile();
                    bindProfileSampler(
                            program,
                            3,
                            "Sampler3",
                            boundWest,
                            profileTexture0,
                            profileTexture1,
                            profileTexture2
                    );
                }
                if (boundNorth != entry.northProfile()) {
                    boundNorth = entry.northProfile();
                    bindProfileSampler(
                            program,
                            4,
                            "Sampler4",
                            boundNorth,
                            profileTexture0,
                            profileTexture1,
                            profileTexture2
                    );
                }
                if (boundEast != entry.eastProfile()) {
                    boundEast = entry.eastProfile();
                    bindProfileSampler(
                            program,
                            5,
                            "Sampler5",
                            boundEast,
                            profileTexture0,
                            profileTexture1,
                            profileTexture2
                    );
                }
                setUniform3f(uniforms.tileOrigin, tile.x, cloudBottom, tile.z);
                if (boundGridX != entry.gridSizeX()
                        || boundGridY != entry.gridSizeY()
                        || boundGridZ != entry.gridSizeZ()) {
                    boundGridX = entry.gridSizeX();
                    boundGridY = entry.gridSizeY();
                    boundGridZ = entry.gridSizeZ();
                    setUniform3f(
                            uniforms.voxelGrid,
                            boundGridX,
                            boundGridY,
                            boundGridZ
                    );
                }
                VertexBuffer buffer = entry.buffer();
                buffer.bind();
                buffer.draw(identityModel, projection, program);
            }
            VertexBuffer.unbind();
            debugVisibleTileCount(mc, VISIBLE_TILES.size());
        } finally {
            VertexBuffer.unbind();
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
        TILE_POOL.clear();
        tilePoolCursor = 0;
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
                                            double northOffset,
                                            float forwardX,
                                            float forwardZ,
                                            float horizontalHalfFov,
                                            boolean useHorizontalViewCull) {
        VISIBLE_TILES.clear();
        tilePoolCursor = 0;
        float cameraX = (float) camera.x;
        float cameraZ = (float) camera.z;
        float closeRadiusSquared = closeRadius * closeRadius;
        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                float tileWorldX = tileX * spacingX;
                float tileWorldZ = (float) (tileZ * (double) spacingZ + northOffset);
                float nearestDistanceSquared = nearestDistanceSquaredToTile(
                        cameraX,
                        cameraZ,
                        tileWorldX,
                        tileWorldZ,
                        tileWidth,
                        tileDepth
                );
                if (nearestDistanceSquared > closeRadiusSquared) {
                    continue;
                }
                boolean containsCamera = contains(
                        camera,
                        tileWorldX,
                        tileWorldZ,
                        tileWidth,
                        tileDepth,
                        cloudBottom,
                        cloudHeight
                );
                if (!containsCamera
                        && useHorizontalViewCull
                        && !intersectsHorizontalView(
                                cameraX,
                                cameraZ,
                                tileWorldX,
                                tileWorldZ,
                                tileWidth,
                                tileDepth,
                                forwardX,
                                forwardZ,
                                horizontalHalfFov
                        )) {
                    continue;
                }
                if (!containsCamera) {
                    Box bounds = new Box(
                            tileWorldX - tileWidth * 0.5f,
                            cloudBottom,
                            tileWorldZ - tileDepth * 0.5f,
                            tileWorldX + tileWidth * 0.5f,
                            cloudBottom + cloudHeight,
                            tileWorldZ + tileDepth * 0.5f
                    );
                    if (!frustum.isVisible(bounds)) {
                        continue;
                    }
                }
                float centerX = tileWorldX - cameraX;
                float centerZ = tileWorldZ - cameraZ;
                VISIBLE_TILES.add(acquireTile(
                        tileWorldX,
                        tileWorldZ,
                        Math.floorMod(tileX, AtcCloseCloudVolumeCache.LINE_PERIOD),
                        Math.floorMod(tileZ, AtcCloseCloudVolumeCache.LINE_PERIOD),
                        centerX * centerX + centerZ * centerZ
                ));
            }
        }
        VISIBLE_TILES.sort(FAR_TO_NEAR);
    }

    private static Tile acquireTile(float x,
                                    float z,
                                    int patternX,
                                    int patternZ,
                                    float distanceSquared) {
        Tile tile;
        if (tilePoolCursor < TILE_POOL.size()) {
            tile = TILE_POOL.get(tilePoolCursor);
        } else {
            tile = new Tile();
            TILE_POOL.add(tile);
        }
        tilePoolCursor++;
        tile.set(x, z, patternX, patternZ, distanceSquared);
        return tile;
    }

    private static float nearestDistanceSquaredToTile(float cameraX,
                                                      float cameraZ,
                                                      float tileX,
                                                      float tileZ,
                                                      float tileWidth,
                                                      float tileDepth) {
        float dx = Math.max(Math.abs(tileX - cameraX) - tileWidth * 0.5f, 0.0f);
        float dz = Math.max(Math.abs(tileZ - cameraZ) - tileDepth * 0.5f, 0.0f);
        return dx * dx + dz * dz;
    }

    private static boolean contains(Vec3d camera,
                                    float tileX,
                                    float tileZ,
                                    float tileWidth,
                                    float tileDepth,
                                    float cloudBottom,
                                    float cloudHeight) {
        return Math.abs((float) camera.x - tileX) <= tileWidth * 0.5f
                && Math.abs((float) camera.z - tileZ) <= tileDepth * 0.5f
                && camera.y >= cloudBottom
                && camera.y <= cloudBottom + cloudHeight;
    }

    /**
     * Conservative XZ view-cone test. The tile's circumscribed circle makes
     * this safe when all four corners are outside but the view passes through
     * the middle of a large tile.
     */
    private static boolean intersectsHorizontalView(float cameraX,
                                                    float cameraZ,
                                                    float tileX,
                                                    float tileZ,
                                                    float tileWidth,
                                                    float tileDepth,
                                                    float forwardX,
                                                    float forwardZ,
                                                    float halfFov) {
        float dx = tileX - cameraX;
        float dz = tileZ - cameraZ;
        float distanceSquared = dx * dx + dz * dz;
        float halfWidth = tileWidth * 0.5f;
        float halfDepth = tileDepth * 0.5f;
        float radiusSquared = halfWidth * halfWidth + halfDepth * halfDepth;
        if (distanceSquared <= radiusSquared) {
            return true;
        }
        float distance = MathHelper.sqrt(distanceSquared);
        float angularRadius = (float) Math.asin(Math.min(
                1.0f,
                MathHelper.sqrt(radiusSquared) / distance
        ));
        float allowedAngle = Math.min((float) Math.PI, halfFov + angularRadius);
        float directionDot = (dx * forwardX + dz * forwardZ) / distance;
        return directionDot >= MathHelper.cos(allowedAngle);
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

    private static void bindProfileSampler(ShaderProgram program,
                                           int textureUnit,
                                           String sampler,
                                           int profile,
                                           AbstractTexture profile0,
                                           AbstractTexture profile1,
                                           AbstractTexture profile2) {
        RenderSystem.setShaderTexture(textureUnit, PROFILE_TEXTURES[profile]);
        AbstractTexture texture = switch (profile) {
            case 0 -> profile0;
            case 1 -> profile1;
            default -> profile2;
        };
        program.addSampler(sampler, texture);
    }

    private static final class Tile {
        private float x;
        private float z;
        private int patternX;
        private int patternZ;
        private float distanceSquared;

        private void set(float x,
                         float z,
                         int patternX,
                         int patternZ,
                         float distanceSquared) {
            this.x = x;
            this.z = z;
            this.patternX = patternX;
            this.patternZ = patternZ;
            this.distanceSquared = distanceSquared;
        }
    }

    private static final class Uniforms {
        private final GlUniform viewMat;
        private final GlUniform time;
        private final GlUniform light;
        private final GlUniform opacity;
        private final GlUniform firstRadius;
        private final GlUniform fadeWidth;
        private final GlUniform gradientLayerSpacing;
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
            gradientLayerSpacing = program.getUniform("uGradientLayerSpacing");
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
