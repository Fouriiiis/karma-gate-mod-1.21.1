// dev/fouriis/karmagate/client/AtcSkyRenderer.java
package dev.fouriis.karmagate.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;

import static net.minecraft.client.render.RenderPhase.*;

public final class AtcSkyRenderer {
    private static final Identifier DAY   = Identifier.of("karma-gate-mod", "sky/atc_sky.png");
    private static final Identifier NIGHT = Identifier.of("karma-gate-mod", "sky/atc_nightsky.png");
    private static final Identifier DUSK  = Identifier.of("karma-gate-mod", "sky/atc_dusksky.png");
    private static final Vector3f CLOUD_ATMOSPHERE_DAY = new Vector3f(0.16078432f, 0.23137255f, 27f / 85f);
    private static final Vector3f CLOUD_ATMOSPHERE_DUSK = new Vector3f(44f / 85f, 0.3254902f, 0.40784314f);
    private static final Vector3f CLOUD_ATMOSPHERE_NIGHT = new Vector3f(0.04882353f, 0.0527451f, 0.06843138f);
    private static final Vector3f CLOUD_MULTIPLY_DAY = new Vector3f(1.0f, 1.0f, 1.0f);
    private static final Vector3f CLOUD_MULTIPLY_DUSK = new Vector3f(1.0f, 0.79f, 0.47f);
    private static final Vector3f CLOUD_MULTIPLY_NIGHT = new Vector3f(4f / 51f, 12f / 85f, 18f / 85f);
    private static final Vector3f DEFAULT_FOG_COLOR = new Vector3f(0.55f, 0.66f, 0.78f);

    private static final int GRID = 64;
    @SuppressWarnings("unused")
    private static final int FULLBRIGHT = LightmapTextureManager.pack(15, 15);

    private AtcSkyRenderer() {}

    public static void renderSkybox(Matrix4f modelView, Matrix4f projection, float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || camera == null) return;

        // ---- height-based visibility from camera Y ----
        float camY = (float) camera.getPos().y;
        float heightVis = AtcCloudVolumeRenderer.aboveCloudsVisibility(camY);
        if (heightVis <= 0.001f) return; // entirely hidden

        SkyWeights weights = skyWeights(mc, tickDelta);
        float wDay = weights.day();
        float wNg = weights.night();
        float wDk = weights.dusk();
        Vector3f textureGrade = authoredTextureMultiply(weights);

        // Clear potential black sky fog before drawing our full-screen mesh
        BackgroundRenderer.clearFog();

        // ---- build bobbed VIEW like distant structures do (so they stay in sync) ----
        Vec3d camPos = camera.getPos();
        Matrix4f view = new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        MatrixStack bobStack = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((dev.fouriis.karmagate.mixin.client.GameRendererAccessor) mc.gameRenderer)
                    .karmaGate$invokeBobView(bobStack, tickDelta);
        }
        bobStack.peek().getPositionMatrix().mul(view);

        // Bobbed view matrix and its inverse (for transforming view rays to world)
        Matrix4f viewBob    = new Matrix4f(bobStack.peek().getPositionMatrix());
        Matrix4f invViewBob = new Matrix4f(viewBob).invert();

        // ---- identity MVP so we can emit clip-space vertices directly ----
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        Matrix4f savedMV = new Matrix4f(mvStack);

        mvStack.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().identity(), VertexSorter.BY_DISTANCE);

        // Clip->view inverse projection (to make view-space rays)
        Matrix4f invProj = new Matrix4f(projection).invert();

        // Multiply each layer’s alpha by heightVis
        // The night panorama already contains its final dark grade. Retain the
        // warm C# dusk tint, but return to neutral as the night asset takes over.
        if (wDay > 0.004f)  drawSkyGrid(DAY,   wDay * heightVis, textureGrade, invProj, invViewBob);
        if (wDk  > 0.004f)  drawSkyGrid(DUSK,  wDk  * heightVis, textureGrade, invProj, invViewBob);
        if (wNg  > 0.004f)  drawSkyGrid(NIGHT, wNg  * heightVis, textureGrade, invProj, invViewBob);

        // Restore matrices
        mvStack.set(savedMV);
        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
    }

    private static void drawSkyGrid(Identifier tex, float alpha, Vector3f textureGrade,
                                    Matrix4f invProj, Matrix4f invViewBob) {
        MinecraftClient mc = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = immediate.getBuffer(skyLayer(tex));

        // opacity driven uniformly since POSITION_TEXTURE has no vertex color
        float clampedA = MathHelper.clamp(alpha, 0f, 1f);
        RenderSystem.setShaderColor(textureGrade.x, textureGrade.y, textureGrade.z, clampedA);

        for (int iy = 0; iy < GRID; iy++) {
            float y0 = -1f + 2f * (iy    / (float)GRID);
            float y1 = -1f + 2f * ((iy+1)/ (float)GRID);

            for (int ix = 0; ix < GRID; ix++) {
                float x0 = -1f + 2f * (ix    / (float)GRID);
                float x1 = -1f + 2f * ((ix+1)/ (float)GRID);

                Vec2 uva = dirToUV(unprojectRay(x0, y0, invProj, invViewBob));
                Vec2 uvb = dirToUV(unprojectRay(x1, y0, invProj, invViewBob));
                Vec2 uvc = dirToUV(unprojectRay(x1, y1, invProj, invViewBob));
                Vec2 uvd = dirToUV(unprojectRay(x0, y1, invProj, invViewBob));

                float umax = Math.max(Math.max(uva.x, uvb.x), Math.max(uvc.x, uvd.x));
                float umin = Math.min(Math.min(uva.x, uvb.x), Math.min(uvc.x, uvd.x));
                if (umax - umin > 0.5f) {
                    if (uva.x < 0.5f) uva.x += 1f;
                    if (uvb.x < 0.5f) uvb.x += 1f;
                    if (uvc.x < 0.5f) uvc.x += 1f;
                    if (uvd.x < 0.5f) uvd.x += 1f;
                }

                // Tri 1
                vc.vertex(x0, y0, 0f).texture(uva.x, uva.y);
                vc.vertex(x1, y0, 0f).texture(uvb.x, uvb.y);
                vc.vertex(x1, y1, 0f).texture(uvc.x, uvc.y);
                // Tri 2
                vc.vertex(x1, y1, 0f).texture(uvc.x, uvc.y);
                vc.vertex(x0, y1, 0f).texture(uvd.x, uvd.y);
                vc.vertex(x0, y0, 0f).texture(uva.x, uva.y);
            }
        }

        immediate.draw();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private static RenderLayer skyLayer(Identifier texture) {
        return RenderLayer.of(
                "atc_sky_screen_mesh",
                VertexFormats.POSITION_TEXTURE,
                VertexFormat.DrawMode.TRIANGLES,
                32768,
                false,
                true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(POSITION_TEXTURE_PROGRAM)
                        .texture(new RenderPhase.Texture(texture, false, false))
                        .transparency(TRANSLUCENT_TRANSPARENCY)
                        .cull(DISABLE_CULLING)
                        .depthTest(ALWAYS_DEPTH_TEST)   // always behind the world
                        .writeMaskState(COLOR_MASK)     // don't write depth
                        .build(false)
        );
    }

    private static Vector3f unprojectRay(float ndcX, float ndcY, Matrix4f invProj, Matrix4f invViewBob) {
        // clip -> view
        Vector4f p = new Vector4f(ndcX, ndcY, 1f, 1f).mul(invProj);
        Vector3f dirView = new Vector3f(p.x / p.w, p.y / p.w, p.z / p.w).normalize();

        // view (bobbing included) -> world (direction-only transform)
        return dirView.mulDirection(invViewBob).normalize();
    }

    private static Vec2 dirToUV(Vector3f d) {
        float yaw = (float)Math.atan2(d.z, d.x);
        float pitch = (float)Math.asin(MathHelper.clamp(d.y, -1f, 1f));
        float u = (yaw / (2f * (float)Math.PI)) + 0.5f;
        float v = 0.5f - (pitch / (float)Math.PI);
        if (u < 0f) u += 1f; else if (u >= 1f) u -= 1f;
        if (v < 0f) v = 0f; else if (v > 1f) v = 1f;
        return new Vec2(u, v);
    }

    private static float remapClamp(float v, float lo, float hi) {
        if (hi == lo) return 0f;
        float t = (v - lo) / (hi - lo);
        return MathHelper.clamp(t, 0f, 1f);
    }
    private static float smooth01(float t) { return t * t * (3f - 2f * t); }

    public static CloudPalette cloudPalette(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            return new CloudPalette(new Vector3f(CLOUD_ATMOSPHERE_DAY), new Vector3f(CLOUD_MULTIPLY_DAY));
        }

        SkyWeights weights = skyWeights(mc, tickDelta);
        // AboveCloudsView animates this independently from _MultiplyColor:
        // (41,59,81) -> (132,83,104) -> (12,13,17). Keep it independent from
        // Minecraft biome fog so the authored cloud palette remains exact.
        Vector3f atmosphere = blendCloudColor(
                CLOUD_ATMOSPHERE_DAY,
                CLOUD_ATMOSPHERE_DUSK,
                CLOUD_ATMOSPHERE_NIGHT,
                weights.day(),
                weights.dusk(),
                weights.night()
        );

        Vector3f multiply = blendCloudColor(
                CLOUD_MULTIPLY_DAY,
                CLOUD_MULTIPLY_DUSK,
                CLOUD_MULTIPLY_NIGHT,
                weights.day(),
                weights.dusk(),
                weights.night()
        );
        return new CloudPalette(atmosphere, multiply);
    }

    /** Dusk-only grade for authored sprites whose night colouring is baked in. */
    static Vector3f authoredTextureMultiply(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            return new Vector3f(1.0f, 1.0f, 1.0f);
        }
        return authoredTextureMultiply(skyWeights(mc, tickDelta));
    }

    /** Night-stage interpolation used by AboveCloudsView's Five Pebbles lightning. */
    static float nightWeight(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? 0.0f : skyWeights(mc, tickDelta).night();
    }

    /** Java equivalent of AboveCloudsView Fog's active RoomPalette.skyColor. */
    static Vector3f fogColor(float tickDelta) {
        return biomeFogColor(MinecraftClient.getInstance(), tickDelta);
    }

    /** Raw fog colour authored by the biome at the active camera position. */
    static Vector3f currentBiomeFogColor() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            return new Vector3f(DEFAULT_FOG_COLOR);
        }
        Vec3d pos = mc.gameRenderer.getCamera().getPos();
        RegistryEntry<Biome> biome = mc.world.getBiome(BlockPos.ofFloored(pos));
        return colorFromRgb(biome.value().getEffects().getFogColor());
    }

    private static Vector3f authoredTextureMultiply(SkyWeights weights) {
        float dusk = weights.dusk();
        return new Vector3f(
                MathHelper.lerp(dusk, 1.0f, CLOUD_MULTIPLY_DUSK.x),
                MathHelper.lerp(dusk, 1.0f, CLOUD_MULTIPLY_DUSK.y),
                MathHelper.lerp(dusk, 1.0f, CLOUD_MULTIPLY_DUSK.z)
        );
    }

    private static Vector3f biomeFogColor(MinecraftClient mc, float tickDelta) {
        if (mc.world == null) {
            return new Vector3f(DEFAULT_FOG_COLOR);
        }
        Vec3d pos = mc.gameRenderer.getCamera().getPos();
        Vector3f biomeFog = currentBiomeFogColor();
        Vec3d sky = mc.world.getSkyColor(pos, tickDelta);
        Vector3f skyFog = new Vector3f(
                MathHelper.clamp((float) sky.x, 0.0f, 1.0f),
                MathHelper.clamp((float) sky.y, 0.0f, 1.0f),
                MathHelper.clamp((float) sky.z, 0.0f, 1.0f)
        );
        return mixCloudColor(biomeFog, skyFog, 0.28f);
    }

    private static Vector3f colorFromRgb(int rgb) {
        return new Vector3f(
                ((rgb >> 16) & 255) / 255.0f,
                ((rgb >> 8) & 255) / 255.0f,
                (rgb & 255) / 255.0f
        );
    }

    private static SkyWeights skyWeights(MinecraftClient mc, float tickDelta) {
        float angle = mc.world.getSkyAngle(tickDelta);
        float sunHeight = MathHelper.cos(angle * ((float)Math.PI * 2f));
        float dayRamp = smooth01(remapClamp(sunHeight, 0.00f, 0.35f));
        float nightRamp = smooth01(remapClamp(-sunHeight, 0.00f, 0.35f));
        float duskCore = smooth01(1f - Math.max(dayRamp, nightRamp));
        float sum = dayRamp + nightRamp + duskCore;
        if (sum <= 0.0f) {
            return new SkyWeights(0.0f, 1.0f, 0.0f);
        }
        return new SkyWeights(dayRamp / sum, duskCore / sum, nightRamp / sum);
    }

    private static Vector3f blendCloudColor(Vector3f day, Vector3f dusk, Vector3f night,
                                            float wDay, float wDusk, float wNight) {
        return new Vector3f(
                day.x * wDay + dusk.x * wDusk + night.x * wNight,
                day.y * wDay + dusk.y * wDusk + night.y * wNight,
                day.z * wDay + dusk.z * wDusk + night.z * wNight
        );
    }

    private static Vector3f mixCloudColor(Vector3f a, Vector3f b, float t) {
        return new Vector3f(
                MathHelper.lerp(t, a.x, b.x),
                MathHelper.lerp(t, a.y, b.y),
                MathHelper.lerp(t, a.z, b.z)
        );
    }

    public record CloudPalette(Vector3f atmosphere, Vector3f multiply) {}

    private record SkyWeights(float day, float dusk, float night) {}

    private static final class Vec2 { float x, y; Vec2(float x, float y){this.x=x; this.y=y;} }
}
