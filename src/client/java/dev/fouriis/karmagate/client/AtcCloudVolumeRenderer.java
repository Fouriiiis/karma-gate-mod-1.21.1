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
    private static final Identifier DISTRIBUTION_NOISE = Identifier.of("librainworldmc", "textures/rainworld/palettes/noise-hq.png");
    private static final Identifier CLOUD_DETAIL = Identifier.of("karma-gate-mod", "clouds/cloudstexture.png");

    private static final RenderLayer CLOUD_1_LAYER = layer("karma_atc_cloud_volume_1", CLOUD_1, 256);
    private static final RenderLayer CLOUD_2_LAYER = layer("karma_atc_cloud_volume_2", CLOUD_2, 256);
    private static final RenderLayer CLOUD_3_LAYER = layer("karma_atc_cloud_volume_3", CLOUD_3, 256);
    private static final RenderLayer[] CLOUD_LAYERS = { CLOUD_1_LAYER, CLOUD_2_LAYER, CLOUD_3_LAYER };
    private static final RenderLayer DISTANT_DECK_LAYER = distantLayer("karma_atc_cloud_distant_deck", CLOUD_DETAIL, 65536);
    private static final RenderLayer DISTANT_HORIZON_LAYER = distantLayer("karma_atc_cloud_distant_horizon", DISTANT_CLOUDS, 65536);

    private static final int FULL_BRIGHT = LightmapTextureManager.pack(15, 15);

    private static final float CLOUD_BOTTOM_Y = 840.0f;
    private static final float CLOUD_TOP_Y = 1078.0f;
    private static final float CLOUD_HEIGHT = CLOUD_TOP_Y - CLOUD_BOTTOM_Y;
    private static final float DISTANT_DECK_Y = CLOUD_BOTTOM_Y - 4.0f;
//1078
    private static final int BAND_ROW_RADIUS = 30;
    private static final int BAND_TILE_RADIUS_NEAR = 5;
    private static final int BAND_TILE_RADIUS_MID = 3;
    private static final int BAND_TILE_RADIUS_FAR = 2;
    private static final int IN_CLOUD_ROW_RADIUS = 3;
    private static final int IN_CLOUD_TILE_RADIUS_NEAR = 1;
    private static final int IN_CLOUD_TILE_RADIUS_MID = 0;
    private static final int IN_CLOUD_TILE_RADIUS_FAR = 0;
    private static final float CLOUD_PROFILE_ASPECT = 700.0f / 150.0f;
    private static final float BAND_TILE_LENGTH = CLOUD_HEIGHT * CLOUD_PROFILE_ASPECT;
    private static final float BAND_TILE_SPACING = BAND_TILE_LENGTH * 0.70f;
    private static final float BAND_TILE_RENDER_LENGTH = BAND_TILE_LENGTH * 1.35f;
    private static final float BAND_ROW_SPACING = 70.0f;
    private static final float BAND_WIDTH = 220.0f;
    private static final float CLOUD_Y_RATIO = 0.75f; 
    private static final float CLOUD_DETAIL_ASPECT = 397.0f / 212.0f;
    private static final float DISTANT_DECK_UV_SCALE = 0.0015f;
    private static final float DISTANT_DECK_UV_STRETCH = CLOUD_PROFILE_ASPECT / CLOUD_DETAIL_ASPECT;
    private static final float DISTANT_DECK_UV_LONG_AXIS = DISTANT_DECK_UV_SCALE / DISTANT_DECK_UV_STRETCH;
    private static final float DISTANT_DECK_UV_SHORT_AXIS = DISTANT_DECK_UV_SCALE * 0.86f;

    private static final float DISTANT_START = 1000.0f;
    private static final float DISTANT_FADE_START = 850.0f;
    private static final float DISTANT_FADE_END = 1300.0f;
    private static final float NEAR_VOLUME_FADE_START = 650.0f;
    private static final float NEAR_VOLUME_FADE_END = 2000.0f;
    private static final float IN_CLOUD_FADE_START = 220.0f;
    private static final float IN_CLOUD_FADE_END = 520.0f;
    private static final float DISTANT_END = 140000.0f;
    private static final int DISTANT_DECK_RINGS = 12;
    private static final int DISTANT_RING_SEGMENTS = 96;
    private static final int DISTANT_HORIZON_LAYERS = 6;
    private static final float DISTANT_HORIZON_MIN_RADIUS = 1400.0f;
    private static final float DISTANT_HORIZON_MAX_RADIUS = 12000.0f;

    private static Object anchoredWorld;
    private static float anchoredBandCenterZ = Float.NaN;
    private static final List<CloudBank> VISIBLE_BANKS = new ArrayList<>(384);
//1372
    private AtcCloudVolumeRenderer() {}

    public static float distantStructureCloudCutY() {
        return DISTANT_DECK_Y;
    }

    public static void renderDistantCloudLayer(float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || camera == null || AtcCloudShaders.DISTANT_PROGRAM == null) return;

        Vec3d camPos = camera.getPos();
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(extendedProjection(mc, camera, tickDelta), VertexSorter.BY_DISTANCE);

        MatrixStack bobStack = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(bobStack, tickDelta);
        }
        bobStack.peek().getPositionMatrix().mul(viewMatrix(camera));
        Matrix4f view = new Matrix4f(bobStack.peek().getPositionMatrix());

        float light = dayLight(mc, camPos, tickDelta);
        AtcSkyRenderer.CloudPalette palette = AtcSkyRenderer.cloudPalette(tickDelta);
        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        try {
            renderDistantClouds(immediate, camPos, view, light, palette);
        } finally {
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
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(extendedProjection(mc, camera, tickDelta), VertexSorter.BY_DISTANCE);

        MatrixStack bobStack = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(bobStack, tickDelta);
        }
        bobStack.peek().getPositionMatrix().mul(viewMatrix(camera));
        Matrix4f view = new Matrix4f(bobStack.peek().getPositionMatrix());

        float worldTime = (mc.world.getTime() + tickDelta) * 0.0125f;
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
            renderVolumeCloudBanks(immediate, mc, banks, camPos, view, worldTime, light, palette);
        } finally {
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
        RenderSystem.setProjectionMatrix(extendedProjection(mc, camera, tickDelta), VertexSorter.BY_DISTANCE);

        MatrixStack bobStack = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(bobStack, tickDelta);
        }
        bobStack.peek().getPositionMatrix().mul(viewMatrix(camera));
        Matrix4f view = new Matrix4f(bobStack.peek().getPositionMatrix());

        float worldTime = (mc.world.getTime() + tickDelta) * 0.0125f;
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
            renderDistantClouds(immediate, camPos, view, light, palette);
            renderVolumeCloudBanks(immediate, mc, banks, camPos, view, worldTime, light, palette);
        } finally {
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
                                               AtcSkyRenderer.CloudPalette palette) {
        ShaderProgram volumeProgram = AtcCloudShaders.PROGRAM;
        volumeProgram.addSampler("Sampler1", mc.getTextureManager().getTexture(DISTRIBUTION_NOISE));
        volumeProgram.addSampler("Sampler2", mc.getTextureManager().getTexture(CLOUD_DETAIL));
        for (CloudBank bank : banks) {
            volumeProgram.bind();
            uploadBankUniforms(volumeProgram, bank, camPos, view, worldTime, light, palette);

            int color = MathHelper.clamp((int) (255.0f * light), 120, 255);
            VertexConsumer vc = immediate.getBuffer(CLOUD_LAYERS[bank.textureIndex]);
            emitBox(vc, bank, color, bank.alphaByte());
            immediate.draw(CLOUD_LAYERS[bank.textureIndex]);
        }
    }

    private static List<CloudBank> buildVisibleBanks(Vec3d camPos) {
        boolean inCloudLayer = false;
        int rowRadius = inCloudLayer ? IN_CLOUD_ROW_RADIUS : BAND_ROW_RADIUS;
        float fadeStart = inCloudLayer ? IN_CLOUD_FADE_START : NEAR_VOLUME_FADE_START;
        float fadeEnd = inCloudLayer ? IN_CLOUD_FADE_END : NEAR_VOLUME_FADE_END;
        float alphaScale = inCloudLayer ? 0.54f : 0.62f;
        List<CloudBank> banks = VISIBLE_BANKS;
        banks.clear();
        float visualCloudHeight = CLOUD_HEIGHT * CLOUD_Y_RATIO;

        float halfWidth = BAND_TILE_RENDER_LENGTH * 0.5f;
        float halfHeight = visualCloudHeight * 0.5f;
        float halfDepth = BAND_WIDTH * 0.5f;

        // Bottom-anchored version: cloud base stays at CLOUD_BOTTOM_Y
        if (Float.isNaN(anchoredBandCenterZ)) {
            anchoredBandCenterZ = MathHelper.floor(camPos.z / BAND_ROW_SPACING) * BAND_ROW_SPACING;
        }

        int centerRow = MathHelper.floor(((float) camPos.z - anchoredBandCenterZ) / BAND_ROW_SPACING);
        for (int row = centerRow - rowRadius; row <= centerRow + rowRadius; row++) {
            float rowCenterZ = anchoredBandCenterZ + row * BAND_ROW_SPACING;
            float rowDistance = Math.abs(rowCenterZ - (float) camPos.z);
            if (rowDistance - halfDepth * 1.25f > fadeEnd) {
                continue;
            }
            int tileRadius = tileRadiusForDistance(rowDistance, inCloudLayer);
            float rowOffset = (hash01(row, 17) - 0.5f) * BAND_TILE_SPACING;
            int centerTile = MathHelper.floor(((float) camPos.x - rowOffset) / BAND_TILE_SPACING);

            for (int tile = centerTile - tileRadius; tile <= centerTile + tileRadius; tile++) {
                float seed = hash01(tile, row);
                float jitterX = (hash01(tile, row * 13) - 0.5f) * BAND_TILE_SPACING * 0.35f;
                float jitterZ = (hash01(row, tile * 19) - 0.5f) * BAND_ROW_SPACING * 0.75f;
                float jitterY = (hash01(tile * 7, row * 11) - 0.5f) * 10.0f;
                float heightScale = MathHelper.lerp(hash01(tile * 3, row * 5), 0.88f, 1.12f);
                float widthScale = MathHelper.lerp(hash01(tile * 5, row * 3), 0.90f, 1.20f);
                float depthScale = MathHelper.lerp(hash01(tile * 11, row * 7), 0.85f, 1.25f);
                float inCloudBulk = inCloudLayer ? 1.35f : 1.0f;
                float localHalfWidth = halfWidth * widthScale * inCloudBulk;
                float localHalfHeight = halfHeight * heightScale;
                float localHalfDepth = halfDepth * depthScale * (inCloudLayer ? 1.55f : 1.0f);
                float centerX = rowOffset + (tile + 0.5f) * BAND_TILE_SPACING + jitterX;
                float centerY = CLOUD_BOTTOM_Y + localHalfHeight + jitterY;
                float centerZ = rowCenterZ + jitterZ;
                float rowLod = MathHelper.clamp(Math.abs(centerZ - (float) camPos.z) / (rowRadius * BAND_ROW_SPACING), 0.0f, 1.0f);
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
                        densityScale
                ));
            }
        }
        return banks;
    }

    private static int tileRadiusForDistance(float rowDistance, boolean inCloudLayer) {
        if (inCloudLayer) {
            if (rowDistance <= BAND_ROW_SPACING * 2.0f) return IN_CLOUD_TILE_RADIUS_NEAR;
            if (rowDistance <= BAND_ROW_SPACING * 4.0f) return IN_CLOUD_TILE_RADIUS_MID;
            return IN_CLOUD_TILE_RADIUS_FAR;
        }
        if (rowDistance <= BAND_ROW_SPACING * 3.5f) return BAND_TILE_RADIUS_NEAR;
        if (rowDistance <= BAND_ROW_SPACING * 8.5f) return BAND_TILE_RADIUS_MID;
        return BAND_TILE_RADIUS_FAR;
    }

    private static int stepCountForDistance(float horizontalDistance, boolean inCloudLayer) {
        if (inCloudLayer) {
            if (horizontalDistance <= 180.0f) return 6;
            if (horizontalDistance <= 360.0f) return 5;
            return 4;
        }
        if (horizontalDistance <= 360.0f) return 8;
        if (horizontalDistance <= 700.0f) return 6;
        return 4;
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
                                            float light,
                                            AtcSkyRenderer.CloudPalette palette) {
        if (AtcCloudShaders.DISTANT_PROGRAM == null) return;

        int color = MathHelper.clamp((int) (225.0f * light), 112, 220);
        AtcCloudShaders.DISTANT_PROGRAM.bind();
        setUniformMat4(AtcCloudShaders.DISTANT_PROGRAM, "uViewMat", view);
        uploadPaletteUniforms(AtcCloudShaders.DISTANT_PROGRAM, palette);

        VertexConsumer deckVc = immediate.getBuffer(DISTANT_DECK_LAYER);
        emitDistantDeck(deckVc, camPos, color, 255);
        immediate.draw(DISTANT_DECK_LAYER);

        AtcCloudShaders.DISTANT_PROGRAM.bind();
        setUniformMat4(AtcCloudShaders.DISTANT_PROGRAM, "uViewMat", view);
        uploadPaletteUniforms(AtcCloudShaders.DISTANT_PROGRAM, palette);

        VertexConsumer horizonVc = immediate.getBuffer(DISTANT_HORIZON_LAYER);
        emitDistantHorizonRibbons(horizonVc, camPos, color, 118);
        immediate.draw(DISTANT_HORIZON_LAYER);
    }

    private static void emitDistantDeck(VertexConsumer vc, Vec3d camPos, int color, int alpha) {
        float centerX = (float) camPos.x;
        float centerZ = (float) camPos.z;
        float y = DISTANT_DECK_Y;

        for (int ring = 0; ring < DISTANT_DECK_RINGS; ring++) {
            float t0 = ring / (float) DISTANT_DECK_RINGS;
            float t1 = (ring + 1) / (float) DISTANT_DECK_RINGS;
            float r0 = MathHelper.lerp(t0 * t0, DISTANT_START, DISTANT_END);
            float r1 = MathHelper.lerp(t1 * t1, DISTANT_START, DISTANT_END);

            for (int seg = 0; seg < DISTANT_RING_SEGMENTS; seg++) {
                float a0 = (float) (seg * Math.PI * 2.0 / DISTANT_RING_SEGMENTS);
                float a1 = (float) ((seg + 1) * Math.PI * 2.0 / DISTANT_RING_SEGMENTS);

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
                        alpha1);
            }
        }
    }

    private static void emitDistantHorizonRibbons(VertexConsumer vc, Vec3d camPos, int color, int baseAlpha) {
        float centerX = (float) camPos.x;
        float centerZ = (float) camPos.z;

        for (int layer = DISTANT_HORIZON_LAYERS - 1; layer >= 0; layer--) {
            float t = layer / (float) (DISTANT_HORIZON_LAYERS - 1);
            float radius = MathHelper.lerp((float) Math.pow(t, 1.5f), DISTANT_HORIZON_MIN_RADIUS, DISTANT_HORIZON_MAX_RADIUS);
            float yRatio = MathHelper.lerp(t, 0.30f, 0.01f);
            float height = CLOUD_HEIGHT * yRatio;
            float y0 = CLOUD_BOTTOM_Y;
            float y1 = CLOUD_BOTTOM_Y + height;
            int alpha = MathHelper.clamp((int) (baseAlpha * MathHelper.lerp(t, 0.85f, 0.35f)), 0, 255);
            float uRepeats = MathHelper.lerp(t, 10.0f, 24.0f);
            float uOffset = hash01(layer, 91) * 10.0f;

            for (int seg = 0; seg < DISTANT_RING_SEGMENTS; seg++) {
                float a0 = (float) (seg * Math.PI * 2.0 / DISTANT_RING_SEGMENTS);
                float a1 = (float) ((seg + 1) * Math.PI * 2.0 / DISTANT_RING_SEGMENTS);
                float x0 = centerX + MathHelper.cos(a0) * radius;
                float z0 = centerZ + MathHelper.sin(a0) * radius;
                float x1 = centerX + MathHelper.cos(a1) * radius;
                float z1 = centerZ + MathHelper.sin(a1) * radius;
                float u0 = uOffset + (seg / (float) DISTANT_RING_SEGMENTS) * uRepeats;
                float u1 = uOffset + ((seg + 1) / (float) DISTANT_RING_SEGMENTS) * uRepeats;

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
        float fadeIn = smoothstep(DISTANT_FADE_START, DISTANT_FADE_END, radius);
        float fadeOut = 1.0f - smoothstep(DISTANT_END * 0.75f, DISTANT_END, radius);
        return MathHelper.clamp((int) (alpha * fadeIn * Math.max(0.25f, fadeOut)), 0, 255);
    }

    private static void emitQuadWorldUvAlpha(VertexConsumer vc,
                                             float x0, float y0, float z0,
                                             float x1, float y1, float z1,
                                             float x2, float y2, float z2,
                                             float x3, float y3, float z3,
                                             int color,
                                             int innerAlpha,
                                             int outerAlpha) {
        emitDeckVertex(vc, x0, y0, z0, color, innerAlpha);
        emitDeckVertex(vc, x1, y1, z1, color, innerAlpha);
        emitDeckVertex(vc, x2, y2, z2, color, outerAlpha);
        emitDeckVertex(vc, x3, y3, z3, color, outerAlpha);
    }

    private static void emitDeckVertex(VertexConsumer vc, float x, float y, float z, int color, int alpha) {
        float u = (x + z * 0.18f) * DISTANT_DECK_UV_LONG_AXIS;
        float v = (z - x * 0.06f) * DISTANT_DECK_UV_SHORT_AXIS;
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

    private static void emitBox(VertexConsumer vc, CloudBank bank, int color, int alpha) {
        float x0 = bank.minX;
        float y0 = bank.minY;
        float z0 = bank.minZ;
        float x1 = bank.maxX;
        float y1 = bank.maxY;
        float z1 = bank.maxZ;

        emitQuad(vc, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, color, alpha);
        emitQuad(vc, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, color, alpha);
        emitQuad(vc, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, color, alpha);
        emitQuad(vc, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, color, alpha);
        emitQuad(vc, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, color, alpha);
        emitQuad(vc, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, color, alpha);
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

    private static void uploadBankUniforms(ShaderProgram program,
                                           CloudBank bank,
                                           Vec3d camPos,
                                           Matrix4f view,
                                           float worldTime,
                                           float light,
                                           AtcSkyRenderer.CloudPalette palette) {
        setUniformMat4(program, "uViewMat", view);
        setUniform3f(program, "uCameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setUniform3f(program, "uBoxMin", bank.minX, bank.minY, bank.minZ);
        setUniform3f(program, "uBoxMax", bank.maxX, bank.maxY, bank.maxZ);
        setUniform3f(program, "uProfileCenter", bank.centerX, bank.centerY, bank.centerZ);
        setUniform3f(program, "uProfileHalfSize", bank.halfWidth, bank.halfHeight, bank.halfDepth);
        setUniform3f(program, "uProfileRight", bank.rightX, bank.rightZ, 0.0f);
        setUniform3f(program, "uProfileForward", bank.forwardX, bank.forwardZ, 0.0f);
        setUniform1f(program, "uTime", worldTime);
        setUniform1f(program, "uSeed", bank.seed);
        setUniform1f(program, "uAlphaScale", bank.alpha);
        setUniform1f(program, "uLight", light);
        setUniform1f(program, "uLayerDepth", bank.layerDepth);
        setUniform1f(program, "uDistanceTint", distanceTint(bank, camPos));
        setUniform1f(program, "uStepCount", bank.stepCount);
        setUniform1f(program, "uDensityScale", bank.densityScale);
        uploadPaletteUniforms(program, palette);
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

    private static Matrix4f viewMatrix(Camera camera) {
        Vec3d camPos = camera.getPos();
        return new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
    }

    private static Matrix4f extendedProjection(MinecraftClient mc, Camera camera, float tickDelta) {
        double dynFovDeg = ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeGetFov(camera, tickDelta, true);
        float fovRad = (float) Math.toRadians(dynFovDeg);
        float aspect = (float) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());
        float far = (float) (mc.options.getClampedViewDistance() * 16.0 * 100.0);
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
                             float densityScale) {
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
