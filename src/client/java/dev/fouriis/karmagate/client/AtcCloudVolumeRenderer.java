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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static net.minecraft.client.render.RenderPhase.COLOR_MASK;
import static net.minecraft.client.render.RenderPhase.DISABLE_CULLING;
import static net.minecraft.client.render.RenderPhase.ENABLE_LIGHTMAP;
import static net.minecraft.client.render.RenderPhase.LEQUAL_DEPTH_TEST;
import static net.minecraft.client.render.RenderPhase.TRANSLUCENT_TRANSPARENCY;

/**
 * Vertical, volumetric Rain World AboveCloudsView cloud banks.
 *
 * The source cloud sprites are side profiles. Each bank is therefore rendered as
 * a vertical sprite slice with real depth, not as a flat horizontal sheet.
 */
public final class AtcCloudVolumeRenderer {
    private static final Identifier CLOUD_1 = Identifier.of("karma-gate-mod", "clouds/clouds1.png");
    private static final Identifier CLOUD_2 = Identifier.of("karma-gate-mod", "clouds/clouds2.png");
    private static final Identifier CLOUD_3 = Identifier.of("karma-gate-mod", "clouds/clouds3.png");
    private static final Identifier DISTANT_CLOUDS = Identifier.of("karma-gate-mod", "clouds/distantclouds.png");
    private static final Identifier DISTRIBUTION_NOISE = Identifier.of("karma-gate-mod", "textures/hologram/noise.png");
    private static final Identifier CLOUD_DETAIL = Identifier.of("karma-gate-mod", "clouds/cloudstexture.png");

    private static final RenderLayer CLOUD_1_LAYER = layer("karma_atc_cloud_volume_1", CLOUD_1, 256);
    private static final RenderLayer CLOUD_2_LAYER = layer("karma_atc_cloud_volume_2", CLOUD_2, 256);
    private static final RenderLayer CLOUD_3_LAYER = layer("karma_atc_cloud_volume_3", CLOUD_3, 256);
    private static final RenderLayer[] CLOUD_LAYERS = { CLOUD_1_LAYER, CLOUD_2_LAYER, CLOUD_3_LAYER };
    private static final RenderLayer DISTANT_DECK_LAYER = distantLayer("karma_atc_cloud_distant_deck", CLOUD_DETAIL, 65536);
    private static final RenderLayer DISTANT_HORIZON_LAYER = distantLayer("karma_atc_cloud_distant_horizon", DISTANT_CLOUDS, 65536);

    private static final int FULL_BRIGHT = LightmapTextureManager.pack(15, 15);

    public static final TuningValue CLOUD_BOTTOM_Y = new TuningValue("Cloud Bottom Y", 957.0f, 400.0f, 1800.0f, false);
    public static final TuningValue CLOUD_TOP_Y = new TuningValue("Cloud Top Y", 1350.0f, 500.0f, 2200.0f, false);
    public static final TuningValue DISTANT_DECK_OFFSET = new TuningValue("Distant Deck Offset", -4.0f, -200.0f, 200.0f, false);
//
    public static final TuningValue BAND_ROW_RADIUS = new TuningValue("Band Row Radius", 30.0f, 1.0f, 80.0f, true);
    public static final TuningValue BAND_TILE_RADIUS_NEAR = new TuningValue("Tile Radius Near", 5.0f, 0.0f, 16.0f, true);
    public static final TuningValue BAND_TILE_RADIUS_MID = new TuningValue("Tile Radius Mid", 3.0f, 0.0f, 12.0f, true);
    public static final TuningValue BAND_TILE_RADIUS_FAR = new TuningValue("Tile Radius Far", 2.0f, 0.0f, 8.0f, true);
    public static final TuningValue IN_CLOUD_ROW_RADIUS = new TuningValue("Inside Row Radius", 3.0f, 1.0f, 16.0f, true);
    public static final TuningValue IN_CLOUD_TILE_RADIUS_NEAR = new TuningValue("Inside Tile Near", 1.0f, 0.0f, 8.0f, true);
    private static final int IN_CLOUD_TILE_RADIUS_MID = 0;
    private static final int IN_CLOUD_TILE_RADIUS_FAR = 0;
    private static final float CLOUD_PROFILE_ASPECT = 700.0f / 150.0f;
    public static final TuningValue BAND_TILE_SPACING_SCALE = new TuningValue("Tile Spacing Scale", 0.70f, 0.20f, 2.00f, false);
    public static final TuningValue BAND_TILE_RENDER_SCALE = new TuningValue("Tile Render Scale", 1.35f, 0.40f, 3.00f, false);
    public static final TuningValue BAND_ROW_SPACING = new TuningValue("Band Row Spacing", 70.0f, 10.0f, 400.0f, false);
    public static final TuningValue BAND_WIDTH = new TuningValue("Band Width", 220.0f, 20.0f, 800.0f, false);
    public static final TuningValue CLOUD_Y_RATIO = new TuningValue("Cloud Y Ratio", 0.75f, 0.10f, 1.50f, false); 
    private static final float CLOUD_DETAIL_ASPECT = 397.0f / 212.0f;
    public static final TuningValue DISTANT_DECK_UV_SCALE = new TuningValue("Distant Deck UV Scale", 0.0015f, 0.0001f, 0.0100f, false);
    private static final float DISTANT_DECK_UV_STRETCH = CLOUD_PROFILE_ASPECT / CLOUD_DETAIL_ASPECT;

    public static final TuningValue DISTANT_START = new TuningValue("Distant Start", 1000.0f, 0.0f, 10000.0f, false);
    public static final TuningValue DISTANT_FADE_START = new TuningValue("Distant Fade Start", 850.0f, 0.0f, 10000.0f, false);
    public static final TuningValue DISTANT_FADE_END = new TuningValue("Distant Fade End", 1300.0f, 0.0f, 20000.0f, false);
    public static final TuningValue NEAR_VOLUME_FADE_START = new TuningValue("Near Fade Start", 650.0f, 0.0f, 5000.0f, false);
    public static final TuningValue NEAR_VOLUME_FADE_END = new TuningValue("Near Fade End", 2000.0f, 100.0f, 12000.0f, false);
    public static final TuningValue IN_CLOUD_FADE_START = new TuningValue("Inside Fade Start", 220.0f, 0.0f, 3000.0f, false);
    public static final TuningValue IN_CLOUD_FADE_END = new TuningValue("Inside Fade End", 520.0f, 0.0f, 5000.0f, false);
    public static final TuningValue DISTANT_END = new TuningValue("Distant End", 140000.0f, 2000.0f, 200000.0f, false);
    public static final TuningValue DISTANT_DECK_RINGS = new TuningValue("Distant Deck Rings", 12.0f, 1.0f, 32.0f, true);
    public static final TuningValue DISTANT_RING_SEGMENTS = new TuningValue("Distant Ring Segments", 96.0f, 8.0f, 256.0f, true);
    public static final TuningValue DISTANT_HORIZON_LAYERS = new TuningValue("Horizon Layers", 6.0f, 1.0f, 16.0f, true);
    public static final TuningValue DISTANT_HORIZON_MIN_RADIUS = new TuningValue("Horizon Min Radius", 1400.0f, 100.0f, 20000.0f, false);
    public static final TuningValue DISTANT_HORIZON_MAX_RADIUS = new TuningValue("Horizon Max Radius", 12000.0f, 100.0f, 80000.0f, false);
    private static final long CLOUD_TIME_WRAP_TICKS = 24_000L;
    public static final TuningValue CLOSE_CLOUD_MOTION_SCALE = new TuningValue("Close Motion Scale", 0.01f, 0.0f, 1.0f, false);
    public static final TuningValue DISTANT_DECK_MOTION_SCALE = new TuningValue("Deck Motion Scale", 0.10f, 0.0f, 2.0f, false);
    public static final TuningValue DISTANT_DECK_U_DRIFT = new TuningValue("Deck U Drift", 0.0009f, -0.0200f, 0.0200f, false);
    public static final TuningValue DISTANT_DECK_V_DRIFT = new TuningValue("Deck V Drift", -0.00045f, -0.0200f, 0.0200f, false);
    public static final TuningValue COWBOY_EASTER_EGG_X = new TuningValue("Cowboy X", 0.0f, -100000.0f, 100000.0f, false);
    public static final TuningValue COWBOY_EASTER_EGG_Z = new TuningValue("Cowboy Z", 0.0f, -100000.0f, 100000.0f, false);

    private static final List<TuningValue> TUNING_VALUES = List.of(
            CLOUD_BOTTOM_Y, CLOUD_TOP_Y, DISTANT_DECK_OFFSET,
            CLOUD_Y_RATIO, BAND_ROW_SPACING, BAND_WIDTH,
            BAND_TILE_SPACING_SCALE, BAND_TILE_RENDER_SCALE,
            BAND_ROW_RADIUS, BAND_TILE_RADIUS_NEAR, BAND_TILE_RADIUS_MID, BAND_TILE_RADIUS_FAR,
            IN_CLOUD_ROW_RADIUS, IN_CLOUD_TILE_RADIUS_NEAR,
            NEAR_VOLUME_FADE_START, NEAR_VOLUME_FADE_END, IN_CLOUD_FADE_START, IN_CLOUD_FADE_END,
            DISTANT_START, DISTANT_FADE_START, DISTANT_FADE_END, DISTANT_END,
            DISTANT_DECK_RINGS, DISTANT_RING_SEGMENTS, DISTANT_HORIZON_LAYERS,
            DISTANT_HORIZON_MIN_RADIUS, DISTANT_HORIZON_MAX_RADIUS, DISTANT_DECK_UV_SCALE,
            CLOSE_CLOUD_MOTION_SCALE, DISTANT_DECK_MOTION_SCALE, DISTANT_DECK_U_DRIFT, DISTANT_DECK_V_DRIFT,
            COWBOY_EASTER_EGG_X, COWBOY_EASTER_EGG_Z
    );

    private static Object anchoredWorld;
    private static float anchoredBandCenterZ = Float.NaN;
    private static final List<CloudBank> VISIBLE_BANKS = new ArrayList<>(384);
    private static ShaderProgram cachedVolumeProgram;
    private static VolumeUniforms cachedVolumeUniforms;
//1372
    private AtcCloudVolumeRenderer() {}

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

    private static float cloudHeight() {
        return Math.max(1.0f, CLOUD_TOP_Y.value() - CLOUD_BOTTOM_Y.value());
    }

    private static float distantDeckY() {
        return CLOUD_BOTTOM_Y.value() + DISTANT_DECK_OFFSET.value();
    }

    private static float bandTileLength() {
        return cloudHeight() * CLOUD_PROFILE_ASPECT;
    }

    private static float bandTileSpacing() {
        return bandTileLength() * BAND_TILE_SPACING_SCALE.value();
    }

    private static float bandTileRenderLength() {
        return bandTileLength() * BAND_TILE_RENDER_SCALE.value();
    }

    private static float distantDeckUvLongAxis() {
        return DISTANT_DECK_UV_SCALE.value() / DISTANT_DECK_UV_STRETCH;
    }

    private static float distantDeckUvShortAxis() {
        return DISTANT_DECK_UV_SCALE.value() * 0.86f;
    }

    public static float distantStructureCloudCutY() {
        return distantDeckY();
    }

    public static void renderDistantCloudLayer(float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || camera == null || AtcCloudShaders.DISTANT_PROGRAM == null) return;

        Vec3d camPos = camera.getPos();
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        Matrix4f savedModelView = new Matrix4f(mvStack);
        mvStack.identity();
        RenderSystem.applyModelViewMatrix();
        Matrix4f projection = cloudProjection(mc, camera, tickDelta);
        RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);

        MatrixStack bobStack = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(bobStack, tickDelta);
        }
        bobStack.peek().getPositionMatrix().mul(viewMatrix(camera));
        Matrix4f view = new Matrix4f(bobStack.peek().getPositionMatrix());

        float light = dayLight(mc, camPos, tickDelta);
        float worldTime = cloudAnimationTime(mc, tickDelta);
        float altitudeVisibility = cloudLayerVisibility((float) camPos.y);
        AtcSkyRenderer.CloudPalette palette = AtcSkyRenderer.cloudPalette(tickDelta);
        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        try {
            renderDistantClouds(immediate, camPos, view, worldTime, light, palette, altitudeVisibility);
        } finally {
            mvStack.set(savedModelView);
            mvStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
        }
    }

    public static void renderVolumeClouds(float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || camera == null || AtcCloudShaders.PROGRAM == null) return;
        if (anchoredWorld != mc.world) {
            anchoredWorld = mc.world;
            anchoredBandCenterZ = Float.NaN;
        }

        Vec3d camPos = camera.getPos();
        float volumeVisibility = closeCloudVolumeVisibility((float) camPos.y);
        if (volumeVisibility <= 0.003f) {
            return;
        }
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        Matrix4f savedModelView = new Matrix4f(mvStack);
        mvStack.identity();
        RenderSystem.applyModelViewMatrix();
        Matrix4f projection = cloudProjection(mc, camera, tickDelta);
        RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);

        MatrixStack bobStack = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(bobStack, tickDelta);
        }
        bobStack.peek().getPositionMatrix().mul(viewMatrix(camera));
        Matrix4f view = new Matrix4f(bobStack.peek().getPositionMatrix());

        float worldTime = cloudAnimationTime(mc, tickDelta);
        float light = dayLight(mc, camPos, tickDelta);
        AtcSkyRenderer.CloudPalette palette = AtcSkyRenderer.cloudPalette(tickDelta);

        List<CloudBank> banks = buildVisibleBanks(camPos);
        if (banks.size() > 1) {
            banks.sort(Comparator.<CloudBank>comparingDouble(bank -> bank.distanceSq(camPos)).reversed());
        }

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        try {
            renderVolumeCloudBanks(immediate, mc, banks, camPos, view, worldTime, light, palette, volumeVisibility);
        } finally {
            mvStack.set(savedModelView);
            mvStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
        }
    }

    public static void renderLate(float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || camera == null || AtcCloudShaders.PROGRAM == null) return;
        if (anchoredWorld != mc.world) {
            anchoredWorld = mc.world;
            anchoredBandCenterZ = Float.NaN;
        }

        Vec3d camPos = camera.getPos();
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        Matrix4f savedModelView = new Matrix4f(mvStack);
        mvStack.identity();
        RenderSystem.applyModelViewMatrix();
        Matrix4f projection = cloudProjection(mc, camera, tickDelta);
        RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);

        MatrixStack bobStack = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(bobStack, tickDelta);
        }
        bobStack.peek().getPositionMatrix().mul(viewMatrix(camera));
        Matrix4f view = new Matrix4f(bobStack.peek().getPositionMatrix());

        float worldTime = cloudAnimationTime(mc, tickDelta);
        float light = dayLight(mc, camPos, tickDelta);
        float distantVisibility = cloudLayerVisibility((float) camPos.y);
        float volumeVisibility = closeCloudVolumeVisibility((float) camPos.y);
        AtcSkyRenderer.CloudPalette palette = AtcSkyRenderer.cloudPalette(tickDelta);

        List<CloudBank> banks = volumeVisibility <= 0.003f ? List.of() : buildVisibleBanks(camPos);
        if (banks.size() > 1) {
            banks.sort(Comparator.<CloudBank>comparingDouble(bank -> bank.distanceSq(camPos)).reversed());
        }

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        try {
            renderDistantClouds(immediate, camPos, view, worldTime, light, palette, distantVisibility);
            renderVolumeCloudBanks(immediate, mc, banks, camPos, view, worldTime, light, palette, volumeVisibility);
        } finally {
            mvStack.set(savedModelView);
            mvStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
        }
    }

    private static void renderVolumeCloudBanks(VertexConsumerProvider.Immediate immediate,
                                               MinecraftClient mc,
                                               List<CloudBank> banks,
                                               Vec3d camPos,
                                               Matrix4f view,
                                               float worldTime,
                                               float light,
                                               AtcSkyRenderer.CloudPalette palette,
                                               float altitudeVisibility) {
        if (altitudeVisibility <= 0.003f) {
            return;
        }
        ShaderProgram volumeProgram = AtcCloudShaders.PROGRAM;
        RenderSystem.setShaderTexture(1, DISTRIBUTION_NOISE);
        RenderSystem.setShaderTexture(2, CLOUD_DETAIL);
        volumeProgram.addSampler("Sampler1", mc.getTextureManager().getTexture(DISTRIBUTION_NOISE));
        volumeProgram.addSampler("Sampler2", mc.getTextureManager().getTexture(CLOUD_DETAIL));
        int color = MathHelper.clamp((int) (255.0f * light), 120, 255);
        volumeProgram.bind();
        uploadFrameUniforms(volumeProgram, camPos, view, light, palette);
        for (CloudBank bank : banks) {
            volumeProgram.bind();
            uploadBankUniforms(volumeProgram, bank, camPos, worldTime);

            int alpha = bank.alphaByte();
            alpha = MathHelper.clamp((int) (alpha * altitudeVisibility), 0, 255);
            if (alpha <= 0) {
                continue;
            }
            VertexConsumer vc = immediate.getBuffer(CLOUD_LAYERS[bank.textureIndex]);
            emitBox(vc, bank, camPos, color, alpha);
            immediate.draw(CLOUD_LAYERS[bank.textureIndex]);
        }
    }

    private static List<CloudBank> buildVisibleBanks(Vec3d camPos) {
        boolean inCloudLayer = false;
        int rowRadius = inCloudLayer ? IN_CLOUD_ROW_RADIUS.intValue() : BAND_ROW_RADIUS.intValue();
        float fadeStart = inCloudLayer ? IN_CLOUD_FADE_START.value() : NEAR_VOLUME_FADE_START.value();
        float fadeEnd = inCloudLayer ? IN_CLOUD_FADE_END.value() : NEAR_VOLUME_FADE_END.value();
        float alphaScale = inCloudLayer ? 0.54f : 0.62f;
        List<CloudBank> banks = VISIBLE_BANKS;
        banks.clear();
        float visualCloudHeight = cloudHeight() * CLOUD_Y_RATIO.value();

        float halfWidth = bandTileRenderLength() * 0.5f;
        float halfHeight = visualCloudHeight * 0.5f;
        float halfDepth = BAND_WIDTH.value() * 0.5f;

        // Bottom-anchored version: cloud base stays at CLOUD_BOTTOM_Y
        if (Float.isNaN(anchoredBandCenterZ)) {
            anchoredBandCenterZ = MathHelper.floor(camPos.z / BAND_ROW_SPACING.value()) * BAND_ROW_SPACING.value();
        }

        int centerRow = MathHelper.floor(((float) camPos.z - anchoredBandCenterZ) / BAND_ROW_SPACING.value());
        for (int row = centerRow - rowRadius; row <= centerRow + rowRadius; row++) {
            float rowCenterZ = anchoredBandCenterZ + row * BAND_ROW_SPACING.value();
            float rowDistance = Math.abs(rowCenterZ - (float) camPos.z);
            if (rowDistance - halfDepth * 1.25f > fadeEnd) {
                continue;
            }
            int tileRadius = tileRadiusForDistance(rowDistance, inCloudLayer);
            float tileSpacing = bandTileSpacing();
            float rowOffset = (hash01(row, 17) - 0.5f) * tileSpacing;
            int centerTile = MathHelper.floor(((float) camPos.x - rowOffset) / tileSpacing);

            for (int tile = centerTile - tileRadius; tile <= centerTile + tileRadius; tile++) {
                float seed = hash01(tile, row);
                float jitterX = (hash01(tile, row * 13) - 0.5f) * tileSpacing * 0.35f;
                float jitterZ = (hash01(row, tile * 19) - 0.5f) * BAND_ROW_SPACING.value() * 0.75f;
                float jitterY = (hash01(tile * 7, row * 11) - 0.5f) * 10.0f;
                float heightScale = MathHelper.lerp(hash01(tile * 3, row * 5), 0.88f, 1.12f);
                float widthScale = MathHelper.lerp(hash01(tile * 5, row * 3), 0.90f, 1.20f);
                float depthScale = MathHelper.lerp(hash01(tile * 11, row * 7), 0.85f, 1.25f);
                float motionScale = 1.0f;
                float motionOffset = 0.0f;
                float inCloudBulk = inCloudLayer ? 1.35f : 1.0f;
                float localHalfWidth = halfWidth * widthScale * inCloudBulk;
                float localHalfHeight = halfHeight * heightScale;
                float localHalfDepth = halfDepth * depthScale * (inCloudLayer ? 1.55f : 1.0f);
                float centerX = rowOffset + (tile + 0.5f) * tileSpacing + jitterX;
                float centerY = cloudBottomY() + localHalfHeight + jitterY;
                float centerZ = rowCenterZ + jitterZ;
                float rowLod = MathHelper.clamp(
                        Math.abs(centerZ - (float) camPos.z) / (rowRadius * BAND_ROW_SPACING.value()),
                        0.0f,
                        1.0f
                );
                float horizontalDist = horizontalDistanceToBox(camPos, centerX, centerZ, localHalfWidth, localHalfDepth);
                float nearFade = 1.0f - smoothstep(fadeStart, fadeEnd, horizontalDist);
                if (nearFade <= 0.01f) {
                    continue;
                }
                float alpha = alphaScale * MathHelper.lerp(rowLod, 1.0f, 0.55f) * nearFade;
                int stepCount = stepCountForDistance(horizontalDist, inCloudLayer);
                float densityScale = 12.0f / stepCount;
                int textureIndex = Math.floorMod(tile + row * 2, CLOUD_LAYERS.length);
                banks.add(new CloudBank(
                        centerX - localHalfWidth,
                        centerY - localHalfHeight,
                        centerZ - localHalfDepth,
                        centerX + localHalfWidth,
                        centerY + localHalfHeight,
                        centerZ + localHalfDepth,
                        centerX,
                        centerY,
                        centerZ,
                        localHalfWidth,
                        localHalfHeight,
                        localHalfDepth,
                        1.0f,
                        0.0f,
                        0.0f,
                        1.0f,
                        textureIndex,
                        seed,
                        rowLod,
                        alpha,
                        stepCount,
                        densityScale,
                        motionScale,
                        motionOffset
                ));
            }
        }
        return banks;
    }

    private static int tileRadiusForDistance(float rowDistance, boolean inCloudLayer) {
        if (inCloudLayer) {
            if (rowDistance <= BAND_ROW_SPACING.value() * 2.0f) return IN_CLOUD_TILE_RADIUS_NEAR.intValue();
            if (rowDistance <= BAND_ROW_SPACING.value() * 4.0f) return IN_CLOUD_TILE_RADIUS_MID;
            return IN_CLOUD_TILE_RADIUS_FAR;
        }
        if (rowDistance <= BAND_ROW_SPACING.value() * 3.5f) return BAND_TILE_RADIUS_NEAR.intValue();
        if (rowDistance <= BAND_ROW_SPACING.value() * 8.5f) return BAND_TILE_RADIUS_MID.intValue();
        return BAND_TILE_RADIUS_FAR.intValue();
    }

    private static int stepCountForDistance(float horizontalDistance, boolean inCloudLayer) {
        if (inCloudLayer) {
            if (horizontalDistance <= 180.0f) return 5;
            if (horizontalDistance <= 360.0f) return 4;
            return 3;
        }
        if (horizontalDistance <= 360.0f) return 5;
        if (horizontalDistance <= 700.0f) return 4;
        return 3;
    }

    private static float horizontalDistanceToBox(Vec3d camPos,
                                                 float centerX,
                                                 float centerZ,
                                                 float halfWidth,
                                                 float halfDepth) {
        float dx = Math.max(Math.abs((float) camPos.x - centerX) - halfWidth, 0.0f);
        float dz = Math.max(Math.abs((float) camPos.z - centerZ) - halfDepth, 0.0f);
        return MathHelper.sqrt(dx * dx + dz * dz);
    }

    private static float hash01(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        h ^= h >> 16;
        return (h & 0x00FFFFFF) / (float) 0x01000000;
    }

    private static void renderDistantClouds(VertexConsumerProvider.Immediate immediate,
                                            Vec3d camPos,
                                            Matrix4f view,
                                            float worldTime,
                                            float light,
                                            AtcSkyRenderer.CloudPalette palette,
                                            float altitudeVisibility) {
        if (AtcCloudShaders.DISTANT_PROGRAM == null) return;
        if (altitudeVisibility <= 0.003f) return;

        int color = MathHelper.clamp((int) (225.0f * light), 112, 220);
        int deckAlpha = MathHelper.clamp((int) (255.0f * altitudeVisibility), 0, 255);
        int horizonAlpha = MathHelper.clamp((int) (118.0f * altitudeVisibility), 0, 255);
        AtcCloudShaders.DISTANT_PROGRAM.bind();
        setUniformMat4(AtcCloudShaders.DISTANT_PROGRAM, "uViewMat", view);
        uploadPaletteUniforms(AtcCloudShaders.DISTANT_PROGRAM, palette);

        VertexConsumer deckVc = immediate.getBuffer(DISTANT_DECK_LAYER);
        emitDistantDeck(deckVc, camPos, worldTime, color, deckAlpha);
        immediate.draw(DISTANT_DECK_LAYER);

        AtcCloudShaders.DISTANT_PROGRAM.bind();
        setUniformMat4(AtcCloudShaders.DISTANT_PROGRAM, "uViewMat", view);
        uploadPaletteUniforms(AtcCloudShaders.DISTANT_PROGRAM, palette);

        VertexConsumer horizonVc = immediate.getBuffer(DISTANT_HORIZON_LAYER);
        emitDistantHorizonRibbons(horizonVc, camPos, color, horizonAlpha);
        immediate.draw(DISTANT_HORIZON_LAYER);
    }

    private static void emitDistantDeck(VertexConsumer vc, Vec3d camPos, float worldTime, int color, int alpha) {
        float centerX = (float) camPos.x;
        float centerZ = (float) camPos.z;
        float y = distantDeckY();

        int ringCount = Math.max(1, DISTANT_DECK_RINGS.intValue());
        int segmentCount = Math.max(8, DISTANT_RING_SEGMENTS.intValue());
        for (int ring = 0; ring < ringCount; ring++) {
            float t0 = ring / (float) ringCount;
            float t1 = (ring + 1) / (float) ringCount;
            float r0 = MathHelper.lerp(t0 * t0, DISTANT_START.value(), DISTANT_END.value());
            float r1 = MathHelper.lerp(t1 * t1, DISTANT_START.value(), DISTANT_END.value());

            for (int seg = 0; seg < segmentCount; seg++) {
                float a0 = (float) (seg * Math.PI * 2.0 / segmentCount);
                float a1 = (float) ((seg + 1) * Math.PI * 2.0 / segmentCount);

                float x00 = centerX + MathHelper.cos(a0) * r0;
                float z00 = centerZ + MathHelper.sin(a0) * r0;
                float x01 = centerX + MathHelper.cos(a1) * r0;
                float z01 = centerZ + MathHelper.sin(a1) * r0;
                float x11 = centerX + MathHelper.cos(a1) * r1;
                float z11 = centerZ + MathHelper.sin(a1) * r1;
                float x10 = centerX + MathHelper.cos(a0) * r1;
                float z10 = centerZ + MathHelper.sin(a0) * r1;

                int alpha0 = distantDeckAlpha(alpha, r0);
                int alpha1 = distantDeckAlpha(alpha, r1);
                emitQuadWorldUvAlpha(vc,
                        x00, y, z00,
                        x01, y, z01,
                        x11, y, z11,
                        x10, y, z10,
                        color,
                        alpha0,
                        alpha1,
                        worldTime);
            }
        }
    }

    private static void emitDistantHorizonRibbons(VertexConsumer vc, Vec3d camPos, int color, int baseAlpha) {
        float centerX = (float) camPos.x;
        float centerZ = (float) camPos.z;

        int layerCount = Math.max(1, DISTANT_HORIZON_LAYERS.intValue());
        int segmentCount = Math.max(8, DISTANT_RING_SEGMENTS.intValue());
        for (int layer = layerCount - 1; layer >= 0; layer--) {
            float t = layerCount <= 1 ? 0.0f : layer / (float) (layerCount - 1);
            float radius = MathHelper.lerp((float) Math.pow(t, 1.5f), DISTANT_HORIZON_MIN_RADIUS.value(), DISTANT_HORIZON_MAX_RADIUS.value());
            float yRatio = MathHelper.lerp(t, 0.30f, 0.01f);
            float height = cloudHeight() * yRatio;
            float y0 = cloudBottomY();
            float y1 = cloudBottomY() + height;
            int alpha = MathHelper.clamp((int) (baseAlpha * MathHelper.lerp(t, 0.85f, 0.35f)), 0, 255);
            if (alpha <= 0) {
                continue;
            }
            float uRepeats = MathHelper.lerp(t, 10.0f, 24.0f);
            float uOffset = hash01(layer, 91) * 10.0f;

            for (int seg = 0; seg < segmentCount; seg++) {
                float a0 = (float) (seg * Math.PI * 2.0 / segmentCount);
                float a1 = (float) ((seg + 1) * Math.PI * 2.0 / segmentCount);
                float x0 = centerX + MathHelper.cos(a0) * radius;
                float z0 = centerZ + MathHelper.sin(a0) * radius;
                float x1 = centerX + MathHelper.cos(a1) * radius;
                float z1 = centerZ + MathHelper.sin(a1) * radius;
                float u0 = uOffset + (seg / (float) segmentCount) * uRepeats;
                float u1 = uOffset + ((seg + 1) / (float) segmentCount) * uRepeats;

                emitVerticalQuadUv(vc,
                        x0, y0, z0,
                        x1, y0, z1,
                        x1, y1, z1,
                        x0, y1, z0,
                        u0, u1,
                        color,
                        alpha);
            }
        }
    }

    private static int distantDeckAlpha(int alpha, float radius) {
        float fadeIn = smoothstep(DISTANT_FADE_START.value(), DISTANT_FADE_END.value(), radius);
        float fadeOut = 1.0f - smoothstep(DISTANT_END.value() * 0.75f, DISTANT_END.value(), radius);
        return MathHelper.clamp((int) (alpha * fadeIn * Math.max(0.25f, fadeOut)), 0, 255);
    }

    private static void emitQuadWorldUvAlpha(VertexConsumer vc,
                                             float x0, float y0, float z0,
                                             float x1, float y1, float z1,
                                             float x2, float y2, float z2,
                                             float x3, float y3, float z3,
                                             int color,
                                             int innerAlpha,
                                             int outerAlpha,
                                             float worldTime) {
        emitDeckVertex(vc, x0, y0, z0, color, innerAlpha, worldTime);
        emitDeckVertex(vc, x1, y1, z1, color, innerAlpha, worldTime);
        emitDeckVertex(vc, x2, y2, z2, color, outerAlpha, worldTime);
        emitDeckVertex(vc, x3, y3, z3, color, outerAlpha, worldTime);
    }

    private static void emitDeckVertex(VertexConsumer vc, float x, float y, float z, int color, int alpha, float worldTime) {
        float driftTime = worldTime * DISTANT_DECK_MOTION_SCALE.value();
        float u = (x + z * 0.18f) * distantDeckUvLongAxis() + driftTime * DISTANT_DECK_U_DRIFT.value();
        float v = (z - x * 0.06f) * distantDeckUvShortAxis() + driftTime * DISTANT_DECK_V_DRIFT.value();
        vc.vertex(x, y, z).color(color, color, color, alpha).texture(u, v).light(FULL_BRIGHT);
    }

    private static void emitVerticalQuadUv(VertexConsumer vc,
                                           float x0, float y0, float z0,
                                           float x1, float y1, float z1,
                                           float x2, float y2, float z2,
                                           float x3, float y3, float z3,
                                           float u0, float u1,
                                           int color, int alpha) {
        vc.vertex(x0, y0, z0).color(color, color, color, alpha).texture(u0, 1.0f).light(FULL_BRIGHT);
        vc.vertex(x1, y1, z1).color(color, color, color, alpha).texture(u1, 1.0f).light(FULL_BRIGHT);
        vc.vertex(x2, y2, z2).color(color, color, color, alpha).texture(u1, 0.0f).light(FULL_BRIGHT);
        vc.vertex(x3, y3, z3).color(color, color, color, alpha).texture(u0, 0.0f).light(FULL_BRIGHT);
    }

    private static void emitBox(VertexConsumer vc, CloudBank bank, Vec3d camPos, int color, int alpha) {
        float x0 = bank.minX;
        float y0 = bank.minY;
        float z0 = bank.minZ;
        float x1 = bank.maxX;
        float y1 = bank.maxY;
        float z1 = bank.maxZ;

        // A ray only needs the box face it exits through. For axes where the
        // camera is within the box slab, either face can be the exit face.
        if (camPos.z >= z0) {
            emitQuad(vc, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, color, alpha);
        }
        if (camPos.z <= z1) {
            emitQuad(vc, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, color, alpha);
        }
        if (camPos.x >= x0) {
            emitQuad(vc, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, color, alpha);
        }
        if (camPos.x <= x1) {
            emitQuad(vc, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, color, alpha);
        }
        if (camPos.y <= y1) {
            emitQuad(vc, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, color, alpha);
        }
        if (camPos.y >= y0) {
            emitQuad(vc, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, color, alpha);
        }
    }

    private static void emitQuad(VertexConsumer vc,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 int color, int alpha) {
        vc.vertex(x0, y0, z0).color(color, color, color, alpha).texture(0.0f, 1.0f).light(FULL_BRIGHT);
        vc.vertex(x1, y1, z1).color(color, color, color, alpha).texture(1.0f, 1.0f).light(FULL_BRIGHT);
        vc.vertex(x2, y2, z2).color(color, color, color, alpha).texture(1.0f, 0.0f).light(FULL_BRIGHT);
        vc.vertex(x3, y3, z3).color(color, color, color, alpha).texture(0.0f, 0.0f).light(FULL_BRIGHT);
    }

    private static void uploadFrameUniforms(ShaderProgram program,
                                            Vec3d camPos,
                                            Matrix4f view,
                                            float light,
                                            AtcSkyRenderer.CloudPalette palette) {
        VolumeUniforms uniforms = volumeUniforms(program);
        setUniformMat4(uniforms.viewMat, view);
        setUniform3f(uniforms.cameraPos, (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setUniform1f(uniforms.light, light);
        Vector3f atmosphere = palette.atmosphere();
        Vector3f multiply = palette.multiply();
        setUniform3f(uniforms.atmosphereColor, atmosphere.x, atmosphere.y, atmosphere.z);
        setUniform3f(uniforms.cloudMultiply, multiply.x, multiply.y, multiply.z);
    }

    private static void uploadBankUniforms(ShaderProgram program,
                                           CloudBank bank,
                                           Vec3d camPos,
                                           float worldTime) {
        VolumeUniforms uniforms = volumeUniforms(program);
        setUniform3f(uniforms.boxMin, bank.minX, bank.minY, bank.minZ);
        setUniform3f(uniforms.boxMax, bank.maxX, bank.maxY, bank.maxZ);
        setUniform3f(uniforms.profileCenter, bank.centerX, bank.centerY, bank.centerZ);
        setUniform3f(uniforms.profileHalfSize, bank.halfWidth, bank.halfHeight, bank.halfDepth);
        setUniform3f(uniforms.profileRight, bank.rightX, bank.rightZ, 0.0f);
        setUniform3f(uniforms.profileForward, bank.forwardX, bank.forwardZ, 0.0f);
        setUniform1f(uniforms.time, worldTime * CLOSE_CLOUD_MOTION_SCALE.value() * bank.motionScale + bank.motionOffset);
        setUniform1f(uniforms.seed, bank.seed);
        setUniform1f(uniforms.alphaScale, bank.alpha);
        setUniform1f(uniforms.layerDepth, bank.layerDepth);
        setUniform1f(uniforms.distanceTint, distanceTint(bank, camPos));
        setUniform1f(uniforms.stepCount, bank.stepCount);
        setUniform1f(uniforms.densityScale, bank.densityScale);
    }

    private static VolumeUniforms volumeUniforms(ShaderProgram program) {
        if (cachedVolumeProgram != program || cachedVolumeUniforms == null) {
            cachedVolumeProgram = program;
            cachedVolumeUniforms = new VolumeUniforms(program);
        }
        return cachedVolumeUniforms;
    }

    private static void uploadPaletteUniforms(ShaderProgram program, AtcSkyRenderer.CloudPalette palette) {
        Vector3f atmosphere = palette.atmosphere();
        Vector3f multiply = palette.multiply();
        setUniform3f(program, "uAtmosphereColor", atmosphere.x, atmosphere.y, atmosphere.z);
        setUniform3f(program, "uCloudMultiply", multiply.x, multiply.y, multiply.z);
    }

    private static void setUniform1f(ShaderProgram program, String name, float value) {
        GlUniform uniform = program.getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void setUniform3f(ShaderProgram program, String name, float x, float y, float z) {
        GlUniform uniform = program.getUniform(name);
        if (uniform != null) uniform.set(x, y, z);
    }

    private static void setUniformMat4(ShaderProgram program, String name, Matrix4f value) {
        GlUniform uniform = program.getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void setUniform1f(GlUniform uniform, float value) {
        if (uniform != null) uniform.set(value);
    }

    private static void setUniform3f(GlUniform uniform, float x, float y, float z) {
        if (uniform != null) uniform.set(x, y, z);
    }

    private static void setUniformMat4(GlUniform uniform, Matrix4f value) {
        if (uniform != null) uniform.set(value);
    }

    private static Matrix4f viewMatrix(Camera camera) {
        Vec3d camPos = camera.getPos();
        return new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
    }

    private static Matrix4f cloudProjection(MinecraftClient mc, Camera camera, float tickDelta) {
        double dynFovDeg = ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeGetFov(camera, tickDelta, true);
        float fovRad = (float) Math.toRadians(dynFovDeg);
        float aspect = (float) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());
        float far = Math.max(128.0f, (float) mc.options.getClampedViewDistance() * 16.0f) * 100.0f;
        return new Matrix4f().setPerspective(fovRad, aspect, 0.0001f, far);
    }

    private static float dayLight(MinecraftClient mc, Vec3d camPos, float tickDelta) {
        if (mc.world == null) return 1.0f;
        Vec3d sky = mc.world.getSkyColor(camPos, tickDelta);
        float r = MathHelper.clamp((float) sky.x, 0.0f, 1.0f);
        float g = MathHelper.clamp((float) sky.y, 0.0f, 1.0f);
        float b = MathHelper.clamp((float) sky.z, 0.0f, 1.0f);
        float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        return MathHelper.clamp(0.50f + 0.58f * luma, 0.50f, 1.0f);
    }

    private static float cloudAnimationTime(MinecraftClient mc, float tickDelta) {
        if (mc.world == null) return tickDelta;
        long wrappedTicks = Math.floorMod(mc.world.getTime(), CLOUD_TIME_WRAP_TICKS);
        return wrappedTicks + tickDelta;
    }

    private static float distanceTint(CloudBank bank, Vec3d camPos) {
        return smoothstep(900_000.0f, 24_000_000.0f, (float) bank.distanceSq(camPos));
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = MathHelper.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static RenderLayer layer(String name, Identifier texture, int expectedBufferSize) {
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
                expectedBufferSize,
                false,
                true,
                params
        );
    }

    private static RenderLayer distantLayer(String name, Identifier texture, int expectedBufferSize) {
        RenderLayer.MultiPhaseParameters params = RenderLayer.MultiPhaseParameters.builder()
                .program(AtcCloudShaders.distantPhase())
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
                expectedBufferSize,
                false,
                true,
                params
        );
    }

    private static final class VolumeUniforms {
        private final GlUniform viewMat;
        private final GlUniform cameraPos;
        private final GlUniform boxMin;
        private final GlUniform boxMax;
        private final GlUniform profileCenter;
        private final GlUniform profileHalfSize;
        private final GlUniform profileRight;
        private final GlUniform profileForward;
        private final GlUniform time;
        private final GlUniform seed;
        private final GlUniform alphaScale;
        private final GlUniform light;
        private final GlUniform layerDepth;
        private final GlUniform distanceTint;
        private final GlUniform stepCount;
        private final GlUniform densityScale;
        private final GlUniform atmosphereColor;
        private final GlUniform cloudMultiply;

        private VolumeUniforms(ShaderProgram program) {
            viewMat = program.getUniform("uViewMat");
            cameraPos = program.getUniform("uCameraPos");
            boxMin = program.getUniform("uBoxMin");
            boxMax = program.getUniform("uBoxMax");
            profileCenter = program.getUniform("uProfileCenter");
            profileHalfSize = program.getUniform("uProfileHalfSize");
            profileRight = program.getUniform("uProfileRight");
            profileForward = program.getUniform("uProfileForward");
            time = program.getUniform("uTime");
            seed = program.getUniform("uSeed");
            alphaScale = program.getUniform("uAlphaScale");
            light = program.getUniform("uLight");
            layerDepth = program.getUniform("uLayerDepth");
            distanceTint = program.getUniform("uDistanceTint");
            stepCount = program.getUniform("uStepCount");
            densityScale = program.getUniform("uDensityScale");
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
        private float value;

        private TuningValue(String label, float defaultValue, float min, float max, boolean integer) {
            this.label = label;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.integer = integer;
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
            this.value = integer
                    ? MathHelper.clamp(Math.round(value), Math.round(min), Math.round(max))
                    : MathHelper.clamp(value, min, max);
        }

        public void reset() {
            value = defaultValue;
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

    private record CloudBank(float minX,
                             float minY,
                             float minZ,
                             float maxX,
                             float maxY,
                             float maxZ,
                             float centerX,
                             float centerY,
                             float centerZ,
                             float halfWidth,
                             float halfHeight,
                             float halfDepth,
                             float rightX,
                             float rightZ,
                             float forwardX,
                             float forwardZ,
                             int textureIndex,
                             float seed,
                             float layerDepth,
                             float alpha,
                             int stepCount,
                             float densityScale,
                             float motionScale,
                             float motionOffset) {
        int alphaByte() {
            return MathHelper.clamp((int) (alpha * 255.0f), 0, 255);
        }

        double distanceSq(Vec3d camPos) {
            double dx = centerX - camPos.x;
            double dy = centerY - camPos.y;
            double dz = centerZ - camPos.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
