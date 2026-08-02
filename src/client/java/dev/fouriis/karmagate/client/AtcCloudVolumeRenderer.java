package dev.fouriis.karmagate.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.fouriis.karmagate.mixin.client.GameRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import java.util.List;

import static net.minecraft.client.render.RenderPhase.COLOR_MASK;
import static net.minecraft.client.render.RenderPhase.DISABLE_CULLING;
import static net.minecraft.client.render.RenderPhase.ENABLE_LIGHTMAP;
import static net.minecraft.client.render.RenderPhase.LEQUAL_DEPTH_TEST;
import static net.minecraft.client.render.RenderPhase.TRANSLUCENT_TRANSPARENCY;

/**
 * Above-cloud renderer split into two paths:
 * distant Rain World-style billboard rings, and raymarched close cloud volumes.
 */
public final class AtcCloudVolumeRenderer {
    private static final Identifier CLOUD_1 = Identifier.of("karma-gate-mod", "clouds/clouds1.png");
    private static final Identifier CLOUD_2 = Identifier.of("karma-gate-mod", "clouds/clouds2.png");
    private static final Identifier CLOUD_3 = Identifier.of("karma-gate-mod", "clouds/clouds3.png");
    private static final Identifier DISTRIBUTION_NOISE =
            Identifier.of("karma-gate-mod", "textures/hologram/noise.png");
    private static final Identifier CLOUD_DETAIL =
            Identifier.of("karma-gate-mod", "clouds/cloudstexture.png");

    private static final RenderLayer[] CLOUD_LAYERS = {
            billboardLayer("karma_atc_cloud_billboard_1", CLOUD_1),
            billboardLayer("karma_atc_cloud_billboard_2", CLOUD_2),
            billboardLayer("karma_atc_cloud_billboard_3", CLOUD_3)
    };

    private static final int FULL_BRIGHT = LightmapTextureManager.pack(15, 15);
    public static final float CLOUD_ASPECT = 700.0f / 150.0f;
    private static final int DISTANT_RING_SEGMENTS = 96;
    private static final long CLOUD_TIME_WRAP_TICKS = 24_000L;

    public static final TuningValue CLOUD_BOTTOM_Y =
            new TuningValue("Cloud Bottom Y", 957.0f, 400.0f, 1800.0f, false);
    public static final TuningValue CLOUD_TOP_Y =
            new TuningValue("Cloud Top Y", 1350.0f, 500.0f, 2200.0f, false, true);
    public static final TuningValue CLOSE_LAYER_COUNT =
            new TuningValue("Close Radius Layers", 7.0f, 1.0f, 12.0f, true);
    public static final TuningValue CLOUD_BAND_SPACING =
            new TuningValue("Close Radius Spacing", 450.0f, 40.0f, 4000.0f, false);
    public static final TuningValue CLOSE_GRADIENT_LAYER_SPACING =
            new TuningValue("Close Gradient Layer Spacing (blocks)", 30.0f, 1.0f, 4000.0f, false);
    public static final TuningValue DISTANT_LAYER_COUNT =
            new TuningValue("Distant Layers", 44.0f, 4.0f, 128.0f, true);
    public static final TuningValue DISTANT_MAX_DISTANCE =
            new TuningValue("Distant Max Distance", 140_000.0f, 20_000.0f, 500_000.0f, false);
    public static final TuningValue DISTANT_WIDTH_SCALE =
            new TuningValue("Distant Asset Width Scale", 1.0f, 0.25f, 4.0f, false);
    public static final TuningValue DISTANT_OPACITY =
            new TuningValue("Distant Opacity", 0.82f, 0.05f, 1.0f, false);
    public static final TuningValue CLOSE_CLOUD_MOTION_SCALE =
            new TuningValue("Cloud Motion Scale", 0.01f, 0.0f, 1.0f, false);
    public static final TuningValue CLOUD_NORTH_SPEED =
            new TuningValue("Cloud North Speed", 2.0f, 0.0f, 20.0f, false);
    public static final TuningValue CLOSE_VOLUME_RESOLUTION =
            new TuningValue("Close Voxel Native Resolution", 700.0f, 700.0f, 700.0f, true);
    public static final TuningValue CLOSE_VOLUME_ISO_LEVEL =
            new TuningValue("Close Volume Iso Level", 0.38f, 0.15f, 0.80f, false, true);
    public static final TuningValue CLOSE_VOLUME_BREAKUP =
            new TuningValue("Close Voxel Breakup", 0.035f, 0.0f, 0.35f, false, true);
    public static final TuningValue CLOSE_VOLUME_WARP =
            new TuningValue("Close Voxel Horizontal Warp", 0.14f, 0.0f, 0.40f, false, true);
    public static final TuningValue CLOSE_VOXEL_NOISE_INFLUENCE =
            new TuningValue("Close Voxel Noise Influence", 1.75f, 0.0f, 4.0f, false, true);
    public static final TuningValue CLOSE_VOXEL_ROUNDING =
            new TuningValue("Close Voxel Rounding", 0.35f, 0.0f, 1.0f, false, true);
    public static final TuningValue CLOSE_VOLUME_DEPTH_SCALE =
            new TuningValue("Close Voxel Depth Scale", 1.0f, 1.0f, 1.0f, false);
    public static final TuningValue CLOSE_TILE_WIDTH_X =
            new TuningValue("Close Tile Width X", 1800.0f, 128.0f, 4000.0f, false);
    public static final TuningValue CLOSE_TILE_WIDTH_Z =
            new TuningValue("Close Tile Width Z", 1800.0f, 128.0f, 4000.0f, false);
    public static final TuningValue CLOSE_HANDOFF_FADE_WIDTH =
            new TuningValue("Close Handoff Fade Width", 0.0f, 0.0f, 8000.0f, false);
    public static final TuningValue CLOSE_VOLUME_OPACITY =
            new TuningValue("Close Volume Opacity", 1.0f, 0.05f, 1.0f, false);
    public static final TuningValue COWBOY_EASTER_EGG_X =
            new TuningValue("Cowboy X", 0.0f, -100000.0f, 100000.0f, false);
    public static final TuningValue COWBOY_EASTER_EGG_Z =
            new TuningValue("Cowboy Z", 0.0f, -100000.0f, 100000.0f, false);

    private static final List<TuningValue> TUNING_VALUES = List.of(
            CLOUD_BOTTOM_Y,
            CLOUD_TOP_Y,
            CLOSE_LAYER_COUNT,
            CLOUD_BAND_SPACING,
            CLOSE_GRADIENT_LAYER_SPACING,
            CLOSE_VOLUME_RESOLUTION,
            CLOSE_VOLUME_ISO_LEVEL,
            CLOSE_VOLUME_BREAKUP,
            CLOSE_VOLUME_WARP,
            CLOSE_VOXEL_NOISE_INFLUENCE,
            CLOSE_VOXEL_ROUNDING,
            CLOSE_VOLUME_DEPTH_SCALE,
            CLOSE_TILE_WIDTH_X,
            CLOSE_TILE_WIDTH_Z,
            CLOSE_HANDOFF_FADE_WIDTH,
            CLOSE_VOLUME_OPACITY,
            DISTANT_LAYER_COUNT,
            DISTANT_MAX_DISTANCE,
            DISTANT_WIDTH_SCALE,
            DISTANT_OPACITY,
            CLOSE_CLOUD_MOTION_SCALE,
            CLOUD_NORTH_SPEED,
            COWBOY_EASTER_EGG_X,
            COWBOY_EASTER_EGG_Z
    );

    private static ShaderProgram cachedProgram;
    private static BillboardUniforms cachedUniforms;

    private AtcCloudVolumeRenderer() {
    }

    public static List<TuningValue> tuningValues() {
        return TUNING_VALUES;
    }

    public static float cloudBottomY() {
        return CLOUD_BOTTOM_Y.value();
    }

    public static float aboveCloudsVisibility(float cameraY) {
        return smoothstep(cloudBottomY(), cloudBottomY() + 20.0f, cameraY);
    }

    public static float cloudLayerVisibility(float cameraY) {
        return smoothstep(cloudBottomY() - 20.0f, cloudBottomY(), cameraY);
    }

    public static float closeCloudVolumeVisibility(float cameraY) {
        return cloudLayerVisibility(cameraY);
    }

    public static float distantStructureCloudCutY() {
        return cloudBottomY();
    }

    public static void renderDistantCloudLayer(float tickDelta, Camera camera) {
        renderDistantBillboards(tickDelta, camera);
    }

    public static void renderVolumeClouds(float tickDelta, Camera camera) {
        renderCloseVolumeTiles(tickDelta, camera);
    }

    public static void renderLate(float tickDelta, Camera camera) {
        renderDistantCloudLayer(tickDelta, camera);
        renderVolumeClouds(tickDelta, camera);
    }

    private static void renderCloseVolumeTiles(float tickDelta, Camera camera) {
        AtcCloseCloudVolumeRenderer.render(tickDelta, camera);
    }

    private static void renderDistantBillboards(float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ShaderProgram program = AtcCloudShaders.PROGRAM;
        if (mc.world == null || camera == null || program == null) {
            return;
        }

        Vec3d camPos = camera.getPos();
        float altitudeVisibility = cloudLayerVisibility((float) camPos.y);
        if (altitudeVisibility <= 0.003f) {
            return;
        }

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
        RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);

        MatrixStack bobStack = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(bobStack, tickDelta);
        }
        bobStack.peek().getPositionMatrix().mul(viewMatrix(camera));
        Matrix4f view = new Matrix4f(bobStack.peek().getPositionMatrix());

        float time = cloudAnimationTime(mc, tickDelta) * CLOSE_CLOUD_MOTION_SCALE.value();
        float light = dayLight(mc, camPos, tickDelta);
        AtcSkyRenderer.CloudPalette palette = AtcSkyRenderer.cloudPalette(tickDelta);

        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        try {
            bindFrame(program, mc, view, time, light, palette);
            drawDistantCloudRings(
                    mc.getBufferBuilders().getEntityVertexConsumers(),
                    program,
                    camPos,
                    altitudeVisibility
            );
        } finally {
            modelViewStack.set(savedModelView);
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorter.BY_DISTANCE);
        }
    }

    private static void drawDistantCloudRings(VertexConsumerProvider.Immediate immediate,
                                             ShaderProgram program,
                                             Vec3d camPos,
                                             float altitudeVisibility) {
        int ringCount = DISTANT_LAYER_COUNT.intValue();
        int closeLayerCount = CLOSE_LAYER_COUNT.intValue();
        float spacing = CLOUD_BAND_SPACING.value();
        float firstRadius = spacing * (closeLayerCount + 0.5f);
        float lastRadius = Math.max(DISTANT_MAX_DISTANCE.value(), firstRadius + spacing);
        float bottomY = CLOUD_BOTTOM_Y.value();
        float topY = Math.max(CLOUD_TOP_Y.value(), bottomY + 1.0f);
        float bandHeight = topY - bottomY;

        for (int ringIndex = ringCount - 1; ringIndex >= 0; ringIndex--) {
            float t = ringCount <= 1 ? 0.0f : ringIndex / (float) (ringCount - 1);
            int csharpLayerIndex = MathHelper.clamp(Math.round(t * 10.0f), 0, 10);
            Billboard billboard = distantBillboard(t, csharpLayerIndex, altitudeVisibility);
            float radius = MathHelper.lerp((float) Math.pow(t, 1.5), firstRadius, lastRadius);

            int textureIndex = Math.min((int) (hash01(ringIndex, 307) * CLOUD_LAYERS.length), CLOUD_LAYERS.length - 1);
            int atmosphereDepth = colorByte(billboard.atmosphereDepth());
            int phase = colorByte(hash01(ringIndex, 211));
            int flattening = colorByte(billboard.shaderFlattening());
            int alpha = colorByte(billboard.alpha());
            if (alpha <= 0) {
                continue;
            }

            float csharpTileWidth = Math.max(
                    1.0f,
                    bandHeight * CLOUD_ASPECT
                            * billboard.widthScale()
                            / Math.max(billboard.spriteHeightScale(), 0.001f)
            );
            float circumference = 2.0f * (float) Math.PI * radius;
            float repeatCount = circumference / csharpTileWidth;
            float seamAngle = hash01(ringIndex, 419) * 2.0f * (float) Math.PI;

            program.bind();
            VertexConsumer vc = immediate.getBuffer(CLOUD_LAYERS[textureIndex]);
            emitCloudRing(vc, (float) camPos.x, (float) camPos.z, radius, bottomY, topY,
                    atmosphereDepth, phase, flattening, alpha, repeatCount, seamAngle);
            immediate.draw(CLOUD_LAYERS[textureIndex]);
        }
    }

    private static Billboard distantBillboard(float t, int logicalLayerIndex, float altitudeVisibility) {
        float spriteHeightScale = MathHelper.lerp(t, 0.30f, 0.01f);
        if (logicalLayerIndex == 8) {
            spriteHeightScale *= 1.5f;
        }
        float shaderFlattening = MathHelper.lerp(0.5f, spriteHeightScale, 1.0f);
        float atmosphereDepth = MathHelper.lerp(t, 0.75f, 0.95f);
        float alpha = DISTANT_OPACITY.value()
                * MathHelper.lerp(t, 1.0f, 0.72f)
                * altitudeVisibility;
        return new Billboard(DISTANT_WIDTH_SCALE.value(), spriteHeightScale, shaderFlattening, atmosphereDepth, alpha);
    }

    private static void emitCloudRing(VertexConsumer vc,
                                      float centerX,
                                      float centerZ,
                                      float radius,
                                      float bottomY,
                                      float topY,
                                      int depth,
                                      int phase,
                                      int flattening,
                                      int alpha,
                                      float repeatCount,
                                      float seamAngle) {
        float angleStep = 2.0f * (float) Math.PI / DISTANT_RING_SEGMENTS;
        float uStep = repeatCount / (float) DISTANT_RING_SEGMENTS;

        for (int segment = 0; segment < DISTANT_RING_SEGMENTS; segment++) {
            float angle0 = seamAngle + segment * angleStep;
            float angle1 = seamAngle + (segment + 1) * angleStep;
            float x0 = centerX + MathHelper.sin(angle0) * radius;
            float z0 = centerZ - MathHelper.cos(angle0) * radius;
            float x1 = centerX + MathHelper.sin(angle1) * radius;
            float z1 = centerZ - MathHelper.cos(angle1) * radius;
            float u0 = segment * uStep;
            float u1 = (segment + 1) * uStep;

            vc.vertex(x0, bottomY, z0).color(depth, phase, flattening, alpha).texture(u0, 1.0f).light(FULL_BRIGHT);
            vc.vertex(x0, topY, z0).color(depth, phase, flattening, alpha).texture(u0, 0.0f).light(FULL_BRIGHT);
            vc.vertex(x1, topY, z1).color(depth, phase, flattening, alpha).texture(u1, 0.0f).light(FULL_BRIGHT);
            vc.vertex(x1, bottomY, z1).color(depth, phase, flattening, alpha).texture(u1, 1.0f).light(FULL_BRIGHT);
        }
    }

    private static void bindFrame(ShaderProgram program,
                                  MinecraftClient mc,
                                  Matrix4f view,
                                  float time,
                                  float light,
                                  AtcSkyRenderer.CloudPalette palette) {
        RenderSystem.setShaderTexture(1, DISTRIBUTION_NOISE);
        RenderSystem.setShaderTexture(2, CLOUD_DETAIL);
        program.addSampler("Sampler1", mc.getTextureManager().getTexture(DISTRIBUTION_NOISE));
        program.addSampler("Sampler2", mc.getTextureManager().getTexture(CLOUD_DETAIL));
        program.bind();

        BillboardUniforms uniforms = billboardUniforms(program);
        setUniformMat4(uniforms.viewMat, view);
        setUniform1f(uniforms.time, time);
        setUniform1f(uniforms.light, light);
        setUniform1f(uniforms.distantStyle, 1.0f);
        Vector3f atmosphere = palette.atmosphere();
        Vector3f multiply = palette.multiply();
        setUniform3f(uniforms.atmosphereColor, atmosphere.x, atmosphere.y, atmosphere.z);
        setUniform3f(uniforms.cloudMultiply, multiply.x, multiply.y, multiply.z);
    }

    private static BillboardUniforms billboardUniforms(ShaderProgram program) {
        if (cachedProgram != program || cachedUniforms == null) {
            cachedProgram = program;
            cachedUniforms = new BillboardUniforms(program);
        }
        return cachedUniforms;
    }

    private static Matrix4f viewMatrix(Camera camera) {
        Vec3d camPos = camera.getPos();
        return new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
    }

    private static float dynamicFovRadians(MinecraftClient mc, Camera camera, float tickDelta) {
        double fov = ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeGetFov(camera, tickDelta, true);
        return (float) Math.toRadians(fov);
    }

    private static Matrix4f cloudProjection(MinecraftClient mc, float fovRadians, float aspect) {
        float far = Math.max(128.0f, (float) mc.options.getClampedViewDistance() * 16.0f) * 100.0f;
        far = Math.max(far, DISTANT_MAX_DISTANCE.value() * 1.1f);
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

    private static int colorByte(float value) {
        return MathHelper.clamp((int) (value * 255.0f), 0, 255);
    }

    private static float hash01(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        h ^= h >> 16;
        return (h & 0x00FFFFFF) / (float) 0x01000000;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = MathHelper.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static RenderLayer billboardLayer(String name, Identifier texture) {
        RenderLayer.MultiPhaseParameters params = RenderLayer.MultiPhaseParameters.builder()
                .program(AtcCloudShaders.phase())
                .texture(new RenderPhase.Texture(texture, false, false))
                .transparency(TRANSLUCENT_TRANSPARENCY)
                .cull(DISABLE_CULLING)
                .lightmap(ENABLE_LIGHTMAP)
                .depthTest(LEQUAL_DEPTH_TEST)
                .writeMaskState(COLOR_MASK)
                .build(false);

        return RenderLayer.of(
                name,
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
                VertexFormat.DrawMode.QUADS,
                32 * 1024,
                false,
                true,
                params
        );
    }

    private static void setUniform1f(GlUniform uniform, float value) {
        if (uniform != null) {
            uniform.set(value);
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

    private record Billboard(float widthScale,
                             float spriteHeightScale,
                             float shaderFlattening,
                             float atmosphereDepth,
                             float alpha) {
    }

    private static final class BillboardUniforms {
        private final GlUniform viewMat;
        private final GlUniform time;
        private final GlUniform light;
        private final GlUniform distantStyle;
        private final GlUniform atmosphereColor;
        private final GlUniform cloudMultiply;

        private BillboardUniforms(ShaderProgram program) {
            viewMat = program.getUniform("uViewMat");
            time = program.getUniform("uTime");
            light = program.getUniform("uLight");
            distantStyle = program.getUniform("uDistantStyle");
            atmosphereColor = program.getUniform("uAtmosphereColor");
            cloudMultiply = program.getUniform("uCloudMultiply");
        }
    }

    public static final class TuningValue {
        private final String label;
        private final float defaultValue;
        private final float min;
        private final float max;
        private final boolean integer;
        private final boolean rebuildCloseCache;
        private float value;

        private TuningValue(String label, float defaultValue, float min, float max, boolean integer) {
            this(label, defaultValue, min, max, integer, false);
        }

        private TuningValue(String label,
                            float defaultValue,
                            float min,
                            float max,
                            boolean integer,
                            boolean rebuildCloseCache) {
            this.label = label;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.integer = integer;
            this.rebuildCloseCache = rebuildCloseCache;
            this.value = defaultValue;
        }

        public String label() {
            return label;
        }

        public float defaultValue() {
            return defaultValue;
        }

        public float min() {
            return min;
        }

        public float max() {
            return max;
        }

        public boolean integer() {
            return integer;
        }

        public float value() {
            return value;
        }

        public int intValue() {
            return MathHelper.clamp(Math.round(value), Math.round(min), Math.round(max));
        }

        public void set(float value) {
            float next = integer
                    ? MathHelper.clamp(Math.round(value), Math.round(min), Math.round(max))
                    : MathHelper.clamp(value, min, max);
            if (this.value != next) {
                this.value = next;
                if (rebuildCloseCache) {
                    AtcCloseCloudVolumeCache.invalidate();
                }
            }
        }

        public void reset() {
            set(defaultValue);
        }

        public String formattedValue() {
            return format(value);
        }

        public String formattedDefault() {
            return format(defaultValue);
        }

        private String format(float value) {
            if (integer) {
                return Integer.toString(Math.round(value));
            }
            if (Math.abs(value) < 0.01f && value != 0.0f) {
                return String.format(java.util.Locale.ROOT, "%.6f", value);
            }
            if (Math.abs(value) < 1.0f) {
                return String.format(java.util.Locale.ROOT, "%.4f", value);
            }
            return String.format(java.util.Locale.ROOT, "%.2f", value);
        }
    }
}
