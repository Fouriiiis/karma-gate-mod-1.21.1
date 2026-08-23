package dev.fouriis.karmagate.entity.coralbrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.entity.coralbrain.CoralBrainSystem;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.brickcraftdream.librainworldmc.client.render.shader.ShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.Shaders;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/** Exact two-sprite, non-particle rendering of Rain World's {@code NeuronSpark}. */
public final class NeuronSparkRenderer {
    private static final float PIXELS_PER_BLOCK = 20.0f;
    private static final int RENDER_PRIORITY = 935;
    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    private static FAtlasElement flatLight;
    private static FAtlasElement pixel;
    private static boolean registered;

    private NeuronSparkRenderer() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        WorldRenderEvents.AFTER_ENTITIES.register(NeuronSparkRenderer::queueRender);
    }

    private static void queueRender(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(true);
        List<CoralBrainSystem.NeuronSpark> sparks = CoralBrainSystem.neuronSparks(world, tickDelta);
        if (sparks.isEmpty()) return;
        double renderTime = world.getTime() + tickDelta;
        long flickerFrame = (long) Math.floor(renderTime * 2.0);
        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(camera ->
                render(camera, sparks, renderTime, flickerFrame), false), RENDER_PRIORITY);
    }

    private static void render(Camera camera, List<CoralBrainSystem.NeuronSpark> sparks,
                               double renderTime, long flickerFrame) {
        if (!loadSprites()) return;
        Matrix4f view = new Matrix4f(RenderUtils.getCameraMatrix(camera));
        Vec3d cameraPos = camera.getPos();
        Quaternionf cameraRotation = new Quaternionf(camera.getRotation());

        BufferBuilder glowBuffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);
        int glowCount = 0;
        for (CoralBrainSystem.NeuronSpark spark : sparks) {
            float life = spark.life(renderTime);
            if (life <= 0.0f) continue;
            long frameSeed = spark.seed() ^ flickerFrame * 0x9E3779B97F4A7C15L;
            float alpha = random01(frameSeed ^ 0x243F6A8885A308D3L) * life;
            float cubic = random01(frameSeed ^ 0x13198A2E03707344L);
            float scale = (15.0f * random01(frameSeed ^ 0xA4093822299F31D0L)
                    + 15.0f * cubic * cubic * cubic) * life / 10.0f;
            if (alpha <= 0.001f || scale <= 0.001f) continue;
            float halfWidth = flatLight.sourcePixelSize.x * scale / (PIXELS_PER_BLOCK * 2.0f);
            float halfHeight = flatLight.sourcePixelSize.y * scale / (PIXELS_PER_BLOCK * 2.0f);
            emitProceduralBillboard(glowBuffer, view, cameraPos, cameraRotation,
                    spark.position(), halfWidth, halfHeight, 0.0f,
                    0, 0, 255, Math.round(alpha * 255.0f));
            glowCount++;
        }
        var glow = glowBuffer.endNullable();
        if (glow != null && glowCount > 0) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            boolean applied = false;
            try {
                if (Shaders.FLAT_LIGHT != null && Shaders.FLAT_LIGHT.getProgram() != null) {
                    Shaders.FLAT_LIGHT
                            .setRipple(false)
                            .setRipple_Both_Sides(false)
                            .setRipple_Other_Side(false)
                            .setRipple_Other_Side_Alt(false)
                            .setScreenspace(false)
                            .apply();
                    ShaderRenderer.setUniformF(Shaders.FLAT_LIGHT.getProgram(),
                            "_Sampler0_ST", 1.0f, 1.0f, 0.0f, 0.0f);
                    ShaderRenderer.setUniformF(Shaders.FLAT_LIGHT.getProgram(),
                            "u_spriteRect", 0.0f, 0.0f, 1.0f, 1.0f);
                    applied = true;
                }
            } catch (RuntimeException ignored) {
                // Resource reloads may briefly leave the generated shader unavailable.
            }
            if (applied) {
                RenderSystem.setShaderTexture(0, flatLight.textureIdentifier);
                BufferRenderer.drawWithGlobalProgram(glow);
            } else {
                glow.close();
            }
        } else if (glow != null) {
            glow.close();
        }

        BufferBuilder coreBuffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);
        int coreCount = 0;
        for (CoralBrainSystem.NeuronSpark spark : sparks) {
            float life = spark.life(renderTime);
            if (life <= 0.0f) continue;
            long frameSeed = spark.seed() ^ flickerFrame * 0x9E3779B97F4A7C15L;
            float intensity = life * life * life
                    * random01(frameSeed ^ 0x082EFA98EC4E6C89L);
            float scale = 4.0f * random01(frameSeed ^ 0x452821E638D01377L) * life;
            if (scale <= 0.001f) continue;
            float halfWidth = pixel.sourcePixelSize.x * scale / (PIXELS_PER_BLOCK * 2.0f);
            float halfHeight = pixel.sourcePixelSize.y * scale / (PIXELS_PER_BLOCK * 2.0f);
            int pale = Math.round(intensity * 255.0f);
            emitAtlasBillboard(coreBuffer, view, cameraPos, cameraRotation,
                    spark.position(), halfWidth, halfHeight, 45.0f, pale, pale, 255, 255);
            coreCount++;
        }
        var core = coreBuffer.endNullable();
        if (core != null && coreCount > 0) {
            RenderSystem.setShader(GameRenderer::getParticleProgram);
            RenderSystem.setShaderTexture(0, pixel.textureIdentifier);
            BufferRenderer.drawWithGlobalProgram(core);
        } else if (core != null) {
            core.close();
        }

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(GameRenderer::getParticleProgram);
        RenderSystem.setShaderTexture(0, SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static boolean loadSprites() {
        try {
            if (flatLight == null || flatLight.textureIdentifier == null) {
                flatLight = LibrainworldmcClient.getAtlasManager().getElementWithName("Futile_White");
            }
            if (pixel == null || pixel.textureIdentifier == null) {
                pixel = LibrainworldmcClient.getAtlasManager().getElementWithName("pixel");
            }
        } catch (IllegalStateException ignored) {
            return false;
        }
        return flatLight != null && flatLight.textureIdentifier != null
                && pixel != null && pixel.textureIdentifier != null;
    }

    private static void emitProceduralBillboard(VertexConsumer vertices, Matrix4f matrix,
                                                 Vec3d camera, Quaternionf rotation, Vec3d center,
                                                 float halfWidth, float halfHeight, float angleDegrees,
                                                 int red, int green, int blue, int alpha) {
        emitCorner(vertices, matrix, camera, rotation, center, halfWidth, halfHeight,
                1, -1, angleDegrees, red, green, blue, alpha, 1, 1);
        emitCorner(vertices, matrix, camera, rotation, center, halfWidth, halfHeight,
                1, 1, angleDegrees, red, green, blue, alpha, 1, 0);
        emitCorner(vertices, matrix, camera, rotation, center, halfWidth, halfHeight,
                -1, 1, angleDegrees, red, green, blue, alpha, 0, 0);
        emitCorner(vertices, matrix, camera, rotation, center, halfWidth, halfHeight,
                -1, -1, angleDegrees, red, green, blue, alpha, 0, 1);
    }

    private static void emitAtlasBillboard(VertexConsumer vertices, Matrix4f matrix,
                                            Vec3d camera, Quaternionf rotation, Vec3d center,
                                            float halfWidth, float halfHeight, float angleDegrees,
                                            int red, int green, int blue, int alpha) {
        emitCorner(vertices, matrix, camera, rotation, center, halfWidth, halfHeight,
                1, -1, angleDegrees, red, green, blue, alpha,
                pixel.uvBottomRight.x, pixel.uvBottomRight.y);
        emitCorner(vertices, matrix, camera, rotation, center, halfWidth, halfHeight,
                1, 1, angleDegrees, red, green, blue, alpha,
                pixel.uvTopRight.x, pixel.uvTopRight.y);
        emitCorner(vertices, matrix, camera, rotation, center, halfWidth, halfHeight,
                -1, 1, angleDegrees, red, green, blue, alpha,
                pixel.uvTopLeft.x, pixel.uvTopLeft.y);
        emitCorner(vertices, matrix, camera, rotation, center, halfWidth, halfHeight,
                -1, -1, angleDegrees, red, green, blue, alpha,
                pixel.uvBottomLeft.x, pixel.uvBottomLeft.y);
    }

    private static void emitCorner(VertexConsumer vertices, Matrix4f matrix,
                                   Vec3d camera, Quaternionf rotation, Vec3d center,
                                   float halfWidth, float halfHeight, float x, float y,
                                   float angleDegrees, int red, int green, int blue, int alpha,
                                   float u, float v) {
        Vector3f corner = new Vector3f(x * halfWidth, y * halfHeight, 0.0f)
                .rotateZ((float) Math.toRadians(angleDegrees))
                .rotate(rotation)
                .add((float) (center.x - camera.x),
                        (float) (center.y - camera.y),
                        (float) (center.z - camera.z));
        vertices.vertex(matrix, corner.x, corner.y, corner.z)
                .color(red, green, blue, alpha)
                .texture(u, v)
                .light(FULL_BRIGHT);
    }

    private static float random01(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (float) (((value >>> 40) & 0xFFFFFFL) / 16777216.0);
    }
}
