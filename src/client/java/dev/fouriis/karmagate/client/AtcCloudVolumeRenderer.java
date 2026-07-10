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
    private static final Identifier DISTRIBUTION_NOISE = Identifier.of("librainworldmc", "textures/rainworld/palettes/noise-hq.png");
    private static final Identifier CLOUD_DETAIL = Identifier.of("karma-gate-mod", "clouds/cloudstexture.png");

    private static final RenderLayer CLOUD_1_LAYER = layer("karma_atc_cloud_volume_1", CLOUD_1, 256);
    private static final RenderLayer CLOUD_2_LAYER = layer("karma_atc_cloud_volume_2", CLOUD_2, 256);
    private static final RenderLayer CLOUD_3_LAYER = layer("karma_atc_cloud_volume_3", CLOUD_3, 256);
    private static final RenderLayer[] CLOUD_LAYERS = { CLOUD_1_LAYER, CLOUD_2_LAYER, CLOUD_3_LAYER };

    private static final int FULL_BRIGHT = LightmapTextureManager.pack(15, 15);

    private static final float CLOUD_BOTTOM_Y = 1185.0f;
    private static final float CLOUD_TOP_Y = 1350.0f;
    private static final float CLOUD_HEIGHT = CLOUD_TOP_Y - CLOUD_BOTTOM_Y;

    private static final int BAND_TILE_RADIUS = 32;
    private static final float CLOUD_PROFILE_ASPECT = 700.0f / 150.0f;
    private static final float BAND_TILE_LENGTH = CLOUD_HEIGHT * CLOUD_PROFILE_ASPECT;
    private static final float BAND_TILE_OVERLAP = 2.0f;
    private static final float BAND_WIDTH = 100.0f;

    private static Object anchoredWorld;
    private static float anchoredBandCenterZ = Float.NaN;

    private AtcCloudVolumeRenderer() {}

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

        List<CloudBank> banks = buildVisibleBanks(camPos);
        if (banks.isEmpty()) return;
        banks.sort(Comparator.<CloudBank>comparingDouble(bank -> bank.distanceSq(camPos)).reversed());

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        try {
            for (CloudBank bank : banks) {
                ShaderProgram program = AtcCloudShaders.PROGRAM;
                program.addSampler("Sampler1", mc.getTextureManager().getTexture(DISTRIBUTION_NOISE));
                program.addSampler("Sampler2", mc.getTextureManager().getTexture(CLOUD_DETAIL));
                program.bind();
                uploadBankUniforms(program, bank, camPos, view, worldTime, light);

                int color = MathHelper.clamp((int) (255.0f * light), 120, 255);
                VertexConsumer vc = immediate.getBuffer(CLOUD_LAYERS[bank.textureIndex]);
                emitBox(vc, bank, color, bank.alphaByte());
                immediate.draw(CLOUD_LAYERS[bank.textureIndex]);
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
        }
    }

    private static List<CloudBank> buildVisibleBanks(Vec3d camPos) {
        List<CloudBank> banks = new ArrayList<>(BAND_TILE_RADIUS * 2 + 1);
        float halfWidth = (BAND_TILE_LENGTH + BAND_TILE_OVERLAP) * 0.5f;
        float halfHeight = CLOUD_HEIGHT * 0.5f;
        float halfDepth = BAND_WIDTH * 0.5f;
        int centerTile = MathHelper.floor(camPos.x / BAND_TILE_LENGTH);
        float centerY = CLOUD_BOTTOM_Y + halfHeight;
        if (Float.isNaN(anchoredBandCenterZ)) {
            anchoredBandCenterZ = MathHelper.floor(camPos.z / BAND_WIDTH) * BAND_WIDTH + halfDepth;
        }
        float centerZ = anchoredBandCenterZ;

        for (int tile = centerTile - BAND_TILE_RADIUS; tile <= centerTile + BAND_TILE_RADIUS; tile++) {
            float centerX = (tile + 0.5f) * BAND_TILE_LENGTH;
            int textureIndex = Math.floorMod(tile, CLOUD_LAYERS.length);
            banks.add(new CloudBank(
                    centerX - halfWidth,
                    centerY - halfHeight,
                    centerZ - halfDepth,
                    centerX + halfWidth,
                    centerY + halfHeight,
                    centerZ + halfDepth,
                    centerX,
                    centerY,
                    centerZ,
                    halfWidth,
                    halfHeight,
                    halfDepth,
                    1.0f,
                    0.0f,
                    0.0f,
                    1.0f,
                    textureIndex,
                    0.0f,
                    0.0f,
                    1.0f
            ));
        }
        return banks;
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
                                           float light) {
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
                             float alpha) {
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
