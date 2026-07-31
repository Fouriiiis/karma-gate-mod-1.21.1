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
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;

public final class AtcCloseCloudVolumeRenderer {
    private static final Identifier DETAIL_TEXTURE =
            Identifier.of("karma-gate-mod", "clouds/cloudstexture.png");
    private static final Identifier NOISE_TEXTURE =
            Identifier.of("karma-gate-mod", "clouds/noise-hq.png");
    private static final long CLOUD_TIME_WRAP_TICKS = 24_000L;
    private static final RenderLayer INTERIOR_FOG_LAYER = RenderLayer.of(
            "atc_close_cloud_interior_fog",
            VertexFormats.POSITION_COLOR,
            VertexFormat.DrawMode.TRIANGLES,
            256,
            false,
            true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(new RenderPhase.ShaderProgram(GameRenderer::getPositionColorProgram))
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .build(false)
    );

    private static ShaderProgram cachedProgram;
    private static Uniforms cachedUniforms;
    private static long lastDebugLogSecond = Long.MIN_VALUE;
    private static long lastFogUpdateNanos;
    private static float smoothedInteriorFog;
    private static final ArrayList<VisibleTile> VISIBLE_TILES = new ArrayList<>(32);
    private static final Comparator<VisibleTile> BACK_TO_FRONT =
            (left, right) -> Float.compare(right.distance, left.distance);

    private AtcCloseCloudVolumeRenderer() {
    }

    public static void render(float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ShaderProgram program = AtcCloseCloudShaders.PROGRAM;
        if (mc.world == null || camera == null || program == null) {
            return;
        }

        Vec3d camPos = camera.getPos();
        float altitudeVisibility = AtcCloudVolumeRenderer.cloudLayerVisibility((float) camPos.y);
        if (altitudeVisibility <= 0.003f) {
            return;
        }

        AtcCloseCloudVolumeCache.ensureLoaded(mc.getResourceManager());
        AtcCloseCloudVolumeCache cache = AtcCloseCloudVolumeCache.get();
        if (cache == null) {
            return;
        }

        float cloudHeight = Math.max(
                1.0f,
                AtcCloudVolumeRenderer.CLOUD_TOP_Y.value() - AtcCloudVolumeRenderer.CLOUD_BOTTOM_Y.value()
        );
        float tileWorldWidth = AtcCloudVolumeRenderer.CLOSE_TILE_WIDTH_X.value();
        float tileWorldDepth = AtcCloudVolumeRenderer.CLOSE_TILE_WIDTH_Z.value();
        float tileSpacingX = Math.max(64.0f, tileWorldWidth);
        float tileSpacingZ = Math.max(64.0f, tileWorldDepth);
        float closeRadius = closeRadius();
        float halfTileWidth = tileWorldWidth * 0.5f;
        float halfTileDepth = tileWorldDepth * 0.5f;
        double northOffset = northOffset(mc, tickDelta);
        float interiorFog = updateInteriorFog(cameraImmersion(
                cache,
                camPos,
                tileSpacingX,
                tileSpacingZ,
                tileWorldWidth,
                tileWorldDepth,
                cloudHeight,
                northOffset
        ));

        // Tile origins stay locked to the world grid. Include one half-tile
        // beyond the close radius so geometry crossing the circular boundary
        // is available for the fragment shader's exact radial cutoff.
        int minTileX = (int) Math.floor(
                (camPos.x - closeRadius - halfTileWidth) / tileSpacingX
        );
        int maxTileX = (int) Math.ceil(
                (camPos.x + closeRadius + halfTileWidth) / tileSpacingX
        );
        int minTileZ = (int) Math.floor(
                (camPos.z - northOffset - closeRadius - halfTileDepth) / tileSpacingZ
        );
        int maxTileZ = (int) Math.ceil(
                (camPos.z - northOffset + closeRadius + halfTileDepth) / tileSpacingZ
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
        // Frustum#setPosition supplies the translation itself. Feeding it the
        // translated world view as well moved the frustum twice and culled
        // tiles while portions of their bounds were still on screen.
        Matrix4f frustumView = new Matrix4f()
                .rotation(camera.getRotation())
                .transpose();
        Frustum frustum = new Frustum(frustumView, projection);
        frustum.setPosition(camPos.x, camPos.y, camPos.z);

        RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);
        RenderSystem.setShader(() -> program);
        RenderSystem.setShaderTexture(0, DETAIL_TEXTURE);
        RenderSystem.setShaderTexture(1, NOISE_TEXTURE);
        program.addSampler("Sampler0", mc.getTextureManager().getTexture(DETAIL_TEXTURE));
        program.addSampler("Sampler1", mc.getTextureManager().getTexture(NOISE_TEXTURE));
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();

        AtcSkyRenderer.CloudPalette palette = AtcSkyRenderer.cloudPalette(tickDelta);
        float time = cloudAnimationTime(mc, tickDelta) * AtcCloudVolumeRenderer.CLOSE_CLOUD_MOTION_SCALE.value();
        float light = dayLight(mc, camPos, tickDelta);
        Matrix4f identityModel = new Matrix4f(RenderSystem.getModelViewMatrix());
        int visibleTileCount = 0;

        try {
            program.bind();
            Uniforms uniforms = uniforms(program);
            setUniformMat4(uniforms.viewMat, view);
            setUniform1f(uniforms.time, time);
            setUniform1f(uniforms.light, light);
            setUniform1f(uniforms.opacity, AtcCloudVolumeRenderer.CLOSE_VOLUME_OPACITY.value() * altitudeVisibility);
            setUniform1f(uniforms.volumeDensity, AtcCloudVolumeRenderer.CLOSE_VOLUME_DENSITY.value());
            setUniform1f(uniforms.edgeFalloff, AtcCloudVolumeRenderer.CLOSE_VOLUME_EDGE_FALLOFF.value());
            setUniform1f(uniforms.depthPrepass, 0.0f);
            setUniform1f(uniforms.firstRadius, closeRadius);
            setUniform1f(uniforms.fadeWidth, handoffFadeWidth(tileSpacingX, tileSpacingZ));
            setUniform2f(uniforms.cameraXZ, (float) camPos.x, (float) camPos.z);
            setUniform3f(uniforms.cameraPos, (float) camPos.x, (float) camPos.y, (float) camPos.z);
            Vector3f atmosphere = palette.atmosphere();
            Vector3f multiply = palette.multiply();
            setUniform3f(uniforms.atmosphereColor, atmosphere.x, atmosphere.y, atmosphere.z);
            setUniform3f(uniforms.cloudMultiply, multiply.x, multiply.y, multiply.z);

            BufferRenderer.resetCurrentVertexBuffer();
            VISIBLE_TILES.clear();
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    Tile tile = tile(
                            tileX,
                            tileZ,
                            tileSpacingX,
                            tileSpacingZ,
                            tileWorldWidth,
                            tileWorldDepth,
                            northOffset
                    );
                    float nearest = nearestDistanceToTile((float) camPos.x, (float) camPos.z, tile);
                    if (nearest > closeRadius) {
                        continue;
                    }
                    Box bounds = new Box(
                            tile.x - tile.halfWidth,
                            AtcCloudVolumeRenderer.CLOUD_BOTTOM_Y.value(),
                            tile.z - tile.halfDepth,
                            tile.x + tile.halfWidth,
                            AtcCloudVolumeRenderer.CLOUD_TOP_Y.value(),
                            tile.z + tile.halfDepth
                    );
                    if (!frustum.isVisible(bounds)) {
                        continue;
                    }

                    float centerDistance = MathHelper.sqrt(
                            (tile.x - (float) camPos.x) * (tile.x - (float) camPos.x)
                                    + (tile.z - (float) camPos.z) * (tile.z - (float) camPos.z)
                    );
                    int lod = centerDistance <= closeRadius * 0.35f ? 0 : 1;
                    AtcCloseCloudVolumeCache.Entry entry = cache.entry(
                            lod,
                            tile.front,
                            tile.side,
                            tile.variant
                    );
                    if (entry == null || entry.buffer() == null || entry.mesh().triangleCount() <= 0) {
                        continue;
                    }

                    VISIBLE_TILES.add(new VisibleTile(tile, entry, centerDistance));
                }
            }

            VISIBLE_TILES.sort(BACK_TO_FRONT);

            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(false, false, false, false);
            setUniform1f(uniforms.depthPrepass, 1.0f);
            for (VisibleTile visible : VISIBLE_TILES) {
                drawTile(visible, uniforms, program, identityModel, projection,
                        tileWorldWidth, cloudHeight, tileWorldDepth);
            }

            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            setUniform1f(uniforms.depthPrepass, 0.0f);
            for (VisibleTile visible : VISIBLE_TILES) {
                drawTile(visible, uniforms, program, identityModel, projection,
                        tileWorldWidth, cloudHeight, tileWorldDepth);
                visibleTileCount++;
            }
            debugVisibleTileCount(mc, visibleTileCount);
        } finally {
            BufferRenderer.resetCurrentVertexBuffer();
            RenderSystem.disableBlend();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            modelViewStack.set(savedModelView);
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorter.BY_DISTANCE);
        }
        drawInteriorFog(mc, interiorFog, palette, light);
    }

    private static void drawTile(VisibleTile visible,
                                 Uniforms uniforms,
                                 ShaderProgram program,
                                 Matrix4f model,
                                 Matrix4f projection,
                                 float tileWidth,
                                 float cloudHeight,
                                 float tileDepth) {
        Tile tile = visible.tile;
        setUniform3f(
                uniforms.tileOrigin,
                tile.x,
                AtcCloudVolumeRenderer.CLOUD_BOTTOM_Y.value(),
                tile.z
        );
        setUniform3f(uniforms.tileScale, tileWidth, cloudHeight, tileDepth);
        setUniform2f(uniforms.tileYawSinCos, 0.0f, 1.0f);
        VertexBuffer vertexBuffer = visible.entry.buffer();
        program.bind();
        vertexBuffer.bind();
        vertexBuffer.draw(model, projection, program);
        VertexBuffer.unbind();
    }

    public static float closeRadius() {
        return AtcCloudVolumeRenderer.CLOUD_BAND_SPACING.value()
                * (AtcCloudVolumeRenderer.CLOSE_LAYER_COUNT.intValue() + 0.5f);
    }

    private static float handoffFadeWidth(float tileSpacingX, float tileSpacingZ) {
        float configured = AtcCloudVolumeRenderer.CLOSE_HANDOFF_FADE_WIDTH.value();
        if (configured <= 0.0f) {
            return Math.max(tileSpacingX, tileSpacingZ);
        }
        return configured;
    }

    private static Tile tile(int tileX,
                             int tileZ,
                             float spacingX,
                             float spacingZ,
                             float tileWidth,
                             float tileDepth,
                             double northOffset) {
        long seed = hash(tileX, tileZ);
        int front = profileIndex(samplerHash(tileX, tileZ, 0));
        int side = profileIndex(samplerHash(tileX, tileZ, 1));
        int variant = (int) Math.floorMod(seed >>> 16, AtcCloseCloudVolumeBuilder.VARIANTS_PER_PAIR);
        float x = tileX * spacingX;
        float z = (float) (tileZ * (double) spacingZ + northOffset);
        return new Tile(
                x,
                z,
                front,
                side,
                variant,
                tileWidth * 0.5f,
                tileDepth * 0.5f
        );
    }

    private static float nearestDistanceToTile(float cameraX, float cameraZ, Tile tile) {
        float dx = Math.max(Math.abs(tile.x - cameraX) - tile.halfWidth, 0.0f);
        float dz = Math.max(Math.abs(tile.z - cameraZ) - tile.halfDepth, 0.0f);
        return MathHelper.sqrt(dx * dx + dz * dz);
    }

    private static float cameraImmersion(AtcCloseCloudVolumeCache cache,
                                         Vec3d camera,
                                         float spacingX,
                                         float spacingZ,
                                         float tileWidth,
                                         float tileDepth,
                                         float cloudHeight,
                                         double northOffset) {
        float localY = ((float) camera.y - AtcCloudVolumeRenderer.CLOUD_BOTTOM_Y.value()) / cloudHeight;
        if (localY < 0.0f || localY > 1.0f) {
            return 0.0f;
        }
        int tileX = MathHelper.floor(camera.x / spacingX + 0.5);
        int tileZ = MathHelper.floor((camera.z - northOffset) / spacingZ + 0.5);
        Tile tile = tile(tileX, tileZ, spacingX, spacingZ, tileWidth, tileDepth, northOffset);
        AtcCloseCloudVolumeCache.Entry entry = cache.entry(0, tile.front, tile.side, tile.variant);
        if (entry == null || entry.mesh() == null) {
            return 0.0f;
        }
        float localX = ((float) camera.x - tile.x) / tileWidth + 0.5f;
        float localZ = ((float) camera.z - tile.z) / tileDepth + 0.5f;
        float density = entry.mesh().sampleDensity(localX, localY, localZ);
        float iso = AtcCloudVolumeRenderer.CLOSE_VOLUME_ISO_LEVEL.value();
        return smoothstep(iso - 0.07f, iso + 0.24f, density);
    }

    private static double northOffset(MinecraftClient mc, float tickDelta) {
        double worldTicks = mc.world.getTime() + tickDelta;
        double blocksPerTick = AtcCloudVolumeRenderer.CLOUD_NORTH_SPEED.value() / 20.0;
        // Minecraft north is negative Z.
        return -worldTicks * blocksPerTick;
    }

    private static float updateInteriorFog(float target) {
        long now = System.nanoTime();
        float seconds = lastFogUpdateNanos == 0L
                ? 1.0f / 60.0f
                : MathHelper.clamp((now - lastFogUpdateNanos) / 1_000_000_000.0f, 0.0f, 0.1f);
        lastFogUpdateNanos = now;
        float response = 1.0f - (float) Math.exp(-seconds * 7.5f);
        smoothedInteriorFog += (target - smoothedInteriorFog) * response;
        return smoothedInteriorFog;
    }

    private static void drawInteriorFog(MinecraftClient mc,
                                        float immersion,
                                        AtcSkyRenderer.CloudPalette palette,
                                        float light) {
        float opacity = immersion * AtcCloudVolumeRenderer.CLOSE_INTERIOR_FOG_OPACITY.value();
        if (opacity <= 0.003f) {
            return;
        }
        Vector3f atmosphere = palette.atmosphere();
        Vector3f multiply = palette.multiply();
        float brightness = MathHelper.lerp(MathHelper.clamp(light, 0.0f, 1.0f), 0.72f, 1.0f);
        int red = colorByte(MathHelper.lerp(0.30f, 0.76f, atmosphere.x) * MathHelper.lerp(0.25f, 1.0f, multiply.x) * brightness);
        int green = colorByte(MathHelper.lerp(0.30f, 0.79f, atmosphere.y) * MathHelper.lerp(0.25f, 1.0f, multiply.y) * brightness);
        int blue = colorByte(MathHelper.lerp(0.30f, 0.84f, atmosphere.z) * MathHelper.lerp(0.25f, 1.0f, multiply.z) * brightness);
        int alpha = colorByte(opacity);

        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        Matrix4f savedModelView = new Matrix4f(modelViewStack);
        try {
            modelViewStack.identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().identity(), VertexSorter.BY_DISTANCE);
            VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
            VertexConsumer vertices = immediate.getBuffer(INTERIOR_FOG_LAYER);
            vertices.vertex(-1.0f, -1.0f, 0.0f).color(red, green, blue, alpha);
            vertices.vertex(1.0f, -1.0f, 0.0f).color(red, green, blue, alpha);
            vertices.vertex(1.0f, 1.0f, 0.0f).color(red, green, blue, alpha);
            vertices.vertex(1.0f, 1.0f, 0.0f).color(red, green, blue, alpha);
            vertices.vertex(-1.0f, 1.0f, 0.0f).color(red, green, blue, alpha);
            vertices.vertex(-1.0f, -1.0f, 0.0f).color(red, green, blue, alpha);
            immediate.draw(INTERIOR_FOG_LAYER);
        } finally {
            modelViewStack.set(savedModelView);
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorter.BY_DISTANCE);
        }
    }

    private static int colorByte(float value) {
        return MathHelper.clamp(Math.round(value * 255.0f), 0, 255);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = MathHelper.clamp((value - edge0) / Math.max(edge1 - edge0, 0.0001f), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static Matrix4f viewMatrix(Camera camera) {
        Vec3d camPos = camera.getPos();
        return new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
    }

    private static float dynamicFovRadians(MinecraftClient mc, Camera camera, float tickDelta) {
        double fov = ((GameRendererAccessor) mc.gameRenderer)
                .karmaGate$invokeGetFov(camera, tickDelta, true);
        return (float) Math.toRadians(fov);
    }

    private static Matrix4f cloudProjection(MinecraftClient mc, float fovRadians, float aspect) {
        float far = Math.max(128.0f, (float) mc.options.getClampedViewDistance() * 16.0f) * 100.0f;
        far = Math.max(far, AtcCloudVolumeRenderer.DISTANT_MAX_DISTANCE.value() * 1.1f);
        return new Matrix4f().setPerspective(fovRadians, aspect, 0.05f, far);
    }

    private static float dayLight(MinecraftClient mc, Vec3d camPos, float tickDelta) {
        Vec3d sky = mc.world.getSkyColor(camPos, tickDelta);
        float r = MathHelper.clamp((float) sky.x, 0.0f, 1.0f);
        float g = MathHelper.clamp((float) sky.y, 0.0f, 1.0f);
        float b = MathHelper.clamp((float) sky.z, 0.0f, 1.0f);
        float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        return MathHelper.clamp(0.52f + 0.55f * luma, 0.52f, 1.0f);
    }

    private static float cloudAnimationTime(MinecraftClient mc, float tickDelta) {
        long wrappedTicks = Math.floorMod(mc.world.getTime(), CLOUD_TIME_WRAP_TICKS);
        return wrappedTicks + tickDelta;
    }

    private static void debugVisibleTileCount(MinecraftClient mc, int count) {
        if (!mc.getDebugHud().shouldShowDebugHud()) {
            return;
        }
        long second = System.currentTimeMillis() / 1000L;
        if (second != lastDebugLogSecond) {
            lastDebugLogSecond = second;
            dev.fouriis.karmagate.KarmaGateMod.LOGGER.info("Visible close cloud tiles: {}", count);
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
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setUniform2f(GlUniform uniform, float x, float y) {
        if (uniform != null) {
            uniform.set(x, y);
        }
    }

    private static void setUniform3f(GlUniform uniform, float x, float y, float z) {
        if (uniform != null) {
            uniform.set(x, y, z);
        }
    }

    private static void setUniformMat4(GlUniform uniform, Matrix4f value) {
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static long hash(int x, int z) {
        long h = 1469598103934665603L;
        h = (h ^ x) * 1099511628211L;
        h = (h ^ z) * 1099511628211L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    private static long samplerHash(int tileX, int tileZ, int axis) {
        long h = 1469598103934665603L;
        h = (h ^ tileX) * 1099511628211L;
        h = (h ^ tileZ) * 1099511628211L;
        h = (h ^ (axis * 0x5bd1e995)) * 1099511628211L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    private static int profileIndex(long seed) {
        return (int) Math.floorMod(seed, AtcCloseCloudVolumeBuilder.PROFILE_COUNT);
    }

    private record Tile(float x,
                        float z,
                        int front,
                        int side,
                        int variant,
                        float halfWidth,
                        float halfDepth) {
    }

    private record VisibleTile(Tile tile,
                               AtcCloseCloudVolumeCache.Entry entry,
                               float distance) {
    }

    private static final class Uniforms {
        private final GlUniform viewMat;
        private final GlUniform time;
        private final GlUniform light;
        private final GlUniform opacity;
        private final GlUniform volumeDensity;
        private final GlUniform edgeFalloff;
        private final GlUniform depthPrepass;
        private final GlUniform firstRadius;
        private final GlUniform fadeWidth;
        private final GlUniform cameraXZ;
        private final GlUniform cameraPos;
        private final GlUniform tileOrigin;
        private final GlUniform tileScale;
        private final GlUniform tileYawSinCos;
        private final GlUniform atmosphereColor;
        private final GlUniform cloudMultiply;

        private Uniforms(ShaderProgram program) {
            viewMat = program.getUniform("uViewMat");
            time = program.getUniform("uTime");
            light = program.getUniform("uLight");
            opacity = program.getUniform("uOpacity");
            volumeDensity = program.getUniform("uVolumeDensity");
            edgeFalloff = program.getUniform("uEdgeFalloff");
            depthPrepass = program.getUniform("uDepthPrepass");
            firstRadius = program.getUniform("uFirstRadius");
            fadeWidth = program.getUniform("uFadeWidth");
            cameraXZ = program.getUniform("uCameraXZ");
            cameraPos = program.getUniform("uCameraPos");
            tileOrigin = program.getUniform("uTileOrigin");
            tileScale = program.getUniform("uTileScale");
            tileYawSinCos = program.getUniform("uTileYawSinCos");
            atmosphereColor = program.getUniform("uAtmosphereColor");
            cloudMultiply = program.getUniform("uCloudMultiply");
        }
    }
}
