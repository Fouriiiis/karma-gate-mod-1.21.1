package dev.fouriis.karmagate.client.swarmer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.KarmaGateMod;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Renders neuron swarmers as glowing billboard particles.
 *
 * This version uses Rain World's SSOracleSwarmer.DrawSprites color mapping:
 *  - Body color matches RW main sprite mapping (HSL2RGB with specific hue/lightness curves).
 *  - Glow layer uses RW "sprite[4]" mapping (different saturation/lightness curve).
 */
public class NeuronSwarmerRenderer {
    private static final Identifier NEURON_TEXTURE = Identifier.of(KarmaGateMod.MOD_ID, "textures/particle/neuron.png");

    // Size of the swarmer sprite in blocks
    private static final float SPRITE_SIZE = 0.25f;

    // Glow effect intensity
    private static final float GLOW_INTENSITY = 1.0f;

    // Glow radius multiplier for the outer glow layer
    private static final float GLOW_SCALE = 1.2f;

    // If you later add RW-style "dark" logic, flip this (or compute per-swarmer).
    private static final boolean DARK_MODE = false;

    private static boolean initialized = false;

    /**
     * Registers the renderer with Fabric's world render events.
     */
    public static void register() {
        if (initialized) return;
        initialized = true;

        // Render after translucent blocks for proper blending
        WorldRenderEvents.AFTER_TRANSLUCENT.register(NeuronSwarmerRenderer::render);
    }

    /**
     * Main render method called each frame.
     */
    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        List<NeuronSwarmer> swarmers = NeuronSwarmerManager.getInstance().getAllSwarmers();
        if (swarmers.isEmpty()) return;

        MatrixStack matrices = context.matrixStack();
        Camera camera = context.camera();
        float tickDelta = context.tickCounter().getTickDelta(true);

        Vec3d cameraPos = camera.getPos();

        // Set up rendering state for additive glow blending
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);

        // Enable depth writing so swarmers write to depth buffer
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull(); // particles visible from both sides

        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, NEURON_TEXTURE);

        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_TEXTURE_COLOR
        );

        // Get camera rotation for fallback orientation
        float cameraYaw = camera.getYaw();
        float cameraPitch = camera.getPitch();

        matrices.push();

        // Translate to camera-relative origin
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        for (NeuronSwarmer swarmer : swarmers) {
            // Outer glow layer first (larger, more transparent)
            renderSwarmerGlow(buffer, matrix, swarmer, cameraPos, cameraYaw, cameraPitch, tickDelta);
            // Core (smaller, brighter)
            renderSwarmer(buffer, matrix, swarmer, cameraPos, cameraYaw, cameraPitch, tickDelta);
        }

        matrices.pop();

        // Draw the buffer
        BuiltBuffer builtBuffer = buffer.endNullable();
        if (builtBuffer != null) {
            BufferRenderer.drawWithGlobalProgram(builtBuffer);
        }

        // Restore render state
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
    }

    /**
     * Renders a single swarmer core as two crossed quads oriented to direction of travel.
     */
    private static void renderSwarmer(
            BufferBuilder buffer,
            Matrix4f matrix,
            NeuronSwarmer swarmer,
            Vec3d cameraPos,
            float cameraYaw,
            float cameraPitch,
            float tickDelta) {

        // Interpolate position
        double x = MathHelper.lerp(tickDelta, swarmer.lastPosition.x, swarmer.position.x);
        double y = MathHelper.lerp(tickDelta, swarmer.lastPosition.y, swarmer.position.y);
        double z = MathHelper.lerp(tickDelta, swarmer.lastPosition.z, swarmer.position.z);

        // Interpolate rotation for visual effect
        float rotation = MathHelper.lerp(tickDelta, swarmer.lastRotation, swarmer.rotation);

        // === Rain World accurate BODY color ===
        float[] rgb = calculateColorRW_Body(swarmer.colorX, swarmer.colorY, DARK_MODE);
        int r = (int) (rgb[0] * 255);
        int g = (int) (rgb[1] * 255);
        int b = (int) (rgb[2] * 255);
        int a = (int) (GLOW_INTENSITY * 255);

        // --- ORIENT TO DIRECTION OF TRAVEL (NO BILLBOARDING) ---

        Vec3d forward = swarmer.direction;
        if (forward.lengthSquared() < 1e-8) {
            double yawRad = Math.toRadians(-cameraYaw);
            forward = new Vec3d(Math.cos(yawRad), 0.0, Math.sin(yawRad));
        }
        forward = forward.normalize();

        // Choose a reference up that's not parallel to forward
        Vec3d refUp = new Vec3d(0.0, 1.0, 0.0);
        if (Math.abs(forward.dotProduct(refUp)) > 0.95) {
            refUp = new Vec3d(1.0, 0.0, 0.0);
        }

        // A base perpendicular vector we can rotate around forward to make the "X"
        Vec3d side = forward.crossProduct(refUp).normalize();

        float size = SPRITE_SIZE * (1.0f + 0.1f * (float) Math.sin(rotation * Math.PI * 2));

        // Two crossed planes: rotate the perpendicular vector 0° and 90° around forward.
        double[] angles = new double[]{0.0, Math.PI / 2.0};

        for (double ang : angles) {
            Vec3d sideRot = rotateAroundAxis(side, forward, ang).normalize();

            // Quad axes (both scaled by size):
            Vec3d uAxis = sideRot.multiply(size);
            Vec3d vAxis = forward.multiply(size);

            float ux = (float) uAxis.x;
            float uy = (float) uAxis.y;
            float uz = (float) uAxis.z;

            float vx = (float) vAxis.x;
            float vy = (float) vAxis.y;
            float vz = (float) vAxis.z;

            // Bottom-left
            buffer.vertex(matrix,
                            (float) (x - ux - vx), (float) (y - uy - vy), (float) (z - uz - vz))
                    .texture(0, 1)
                    .color(r, g, b, a);

            // Bottom-right
            buffer.vertex(matrix,
                            (float) (x + ux - vx), (float) (y + uy - vy), (float) (z + uz - vz))
                    .texture(1, 1)
                    .color(r, g, b, a);

            // Top-right
            buffer.vertex(matrix,
                            (float) (x + ux + vx), (float) (y + uy + vy), (float) (z + uz + vz))
                    .texture(1, 0)
                    .color(r, g, b, a);

            // Top-left
            buffer.vertex(matrix,
                            (float) (x - ux + vx), (float) (y - uy + vy), (float) (z - uz + vz))
                    .texture(0, 0)
                    .color(r, g, b, a);
        }
    }

    /**
     * Renders the outer glow layer of a swarmer (larger, more transparent).
     * Uses RW's "sprite[4]" color curve for closer visual parity.
     */
    private static void renderSwarmerGlow(
            BufferBuilder buffer,
            Matrix4f matrix,
            NeuronSwarmer swarmer,
            Vec3d cameraPos,
            float cameraYaw,
            float cameraPitch,
            float tickDelta) {

        // Interpolate position
        double x = MathHelper.lerp(tickDelta, swarmer.lastPosition.x, swarmer.position.x);
        double y = MathHelper.lerp(tickDelta, swarmer.lastPosition.y, swarmer.position.y);
        double z = MathHelper.lerp(tickDelta, swarmer.lastPosition.z, swarmer.position.z);

        // Interpolate rotation for visual effect
        float rotation = MathHelper.lerp(tickDelta, swarmer.lastRotation, swarmer.rotation);

        // === Rain World accurate "sprite[4]" color ===
        float[] rgb = calculateColorRW_Sprite4(swarmer.colorX, swarmer.colorY, DARK_MODE);
        int r = (int) (rgb[0] * 255);
        int g = (int) (rgb[1] * 255);
        int b = (int) (rgb[2] * 255);
        int a = (int) (0.3f * 255); // Lower alpha for glow

        // --- ORIENT TO DIRECTION OF TRAVEL (NO BILLBOARDING) ---
        Vec3d forward = swarmer.direction;
        if (forward.lengthSquared() < 1e-8) {
            double yawRad = Math.toRadians(-cameraYaw);
            forward = new Vec3d(Math.cos(yawRad), 0.0, Math.sin(yawRad));
        }
        forward = forward.normalize();

        Vec3d refUp = new Vec3d(0.0, 1.0, 0.0);
        if (Math.abs(forward.dotProduct(refUp)) > 0.95) {
            refUp = new Vec3d(1.0, 0.0, 0.0);
        }

        Vec3d side = forward.crossProduct(refUp).normalize();

        // Larger size for glow
        float size = SPRITE_SIZE * GLOW_SCALE;

        // Pulsing effect
        float pulse = 1.0f + 0.2f * (float) Math.sin(rotation * Math.PI * 2);
        size *= pulse;

        double[] angles = new double[]{0.0, Math.PI / 2.0};

        for (double ang : angles) {
            Vec3d sideRot = rotateAroundAxis(side, forward, ang).normalize();

            Vec3d uAxis = sideRot.multiply(size);
            Vec3d vAxis = forward.multiply(size);

            float ux = (float) uAxis.x;
            float uy = (float) uAxis.y;
            float uz = (float) uAxis.z;

            float vx = (float) vAxis.x;
            float vy = (float) vAxis.y;
            float vz = (float) vAxis.z;

            buffer.vertex(matrix, (float) (x - ux - vx), (float) (y - uy - vy), (float) (z - uz - vz))
                    .texture(0, 1)
                    .color(r, g, b, a);

            buffer.vertex(matrix, (float) (x + ux - vx), (float) (y + uy - vy), (float) (z + uz - vz))
                    .texture(1, 1)
                    .color(r, g, b, a);

            buffer.vertex(matrix, (float) (x + ux + vx), (float) (y + uy + vy), (float) (z + uz + vz))
                    .texture(1, 0)
                    .color(r, g, b, a);

            buffer.vertex(matrix, (float) (x - ux + vx), (float) (y - uy + vy), (float) (z - uz + vz))
                    .texture(0, 0)
                    .color(r, g, b, a);
        }
    }

    // ======================================================================
    // Rain World accurate color mapping (SSOracleSwarmer.DrawSprites)
    // ======================================================================

    /**
     * Matches RW body color mapping:
     *  - normal: HSL2RGB( hueNormal(colorX), 1, 0.5 + 0.5*colorY )
     *  - dark:   HSL2RGB( hueDark(colorX),   1, Lerp(0.1,0.5,colorY) )
     */
    private static float[] calculateColorRW_Body(float colorX, float colorY, boolean dark) {
        float h;
        float s = 1.0f;
        float l;

        if (!dark) {
            h = rwHueNormal(colorX);
            l = 0.5f + 0.5f * colorY;
        } else {
            h = rwHueDark(colorX);
            l = lerp(0.1f, 0.5f, colorY);
        }
        return hslToRgb(h, s, l);
    }

    /**
     * Matches RW sprite[4] color mapping:
     *  - normal: HSL2RGB( hueNormal(colorX), 1-colorY, Lerp(0.8+0.2*InverseLerp(0.4,0.1,colorX), 0.35, colorY^2) )
     *  - dark:   HSL2RGB( hueDark(colorX),   1,        Lerp(0.75,0.9,colorY) )
     */
    private static float[] calculateColorRW_Sprite4(float colorX, float colorY, boolean dark) {
        float h;
        float s;
        float l;

        if (!dark) {
            h = rwHueNormal(colorX);
            s = 1.0f - colorY;

            float a = 0.8f + 0.2f * inverseLerp(0.4f, 0.1f, colorX);
            float t = colorY * colorY;
            l = lerp(a, 0.35f, t);
        } else {
            h = rwHueDark(colorX);
            s = 1.0f;
            l = lerp(0.75f, 0.9f, colorY);
        }

        return hslToRgb(h, s, l);
    }

    private static float rwHueNormal(float colorX) {
        // (color.x < 0.5) ? LerpMap(x,0..0.5, 4/9..2/3)
        //                : LerpMap(x,0.5..1, 2/3..0.99722224)
        if (colorX < 0.5f) {
            return lerpMap(colorX, 0.0f, 0.5f, 4f / 9f, 2f / 3f);
        }
        return lerpMap(colorX, 0.5f, 1.0f, 2f / 3f, 0.99722224f);
    }

    private static float rwHueDark(float colorX) {
        // (color.x <= 0.5) ? (2/3) : LerpMap(x,0.5..1, 2/3..0.99722224)
        if (colorX <= 0.5f) return 2f / 3f;
        return lerpMap(colorX, 0.5f, 1.0f, 2f / 3f, 0.99722224f);
    }

    // ======================================================================
    // HSL utilities (same structure you had, just reused by RW mapping)
    // ======================================================================

    /**
     * Converts HSL to RGB.
     * h, s, l are in [0..1].
     */
    private static float[] hslToRgb(float h, float s, float l) {
        float r, g, b;

        if (s == 0) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hueToRgb(p, q, h + 1f / 3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f / 3f);
        }

        return new float[]{r, g, b};
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6f) return p + (q - p) * 6 * t;
        if (t < 1f / 2f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6;
        return p;
    }

    // ======================================================================
    // Math helpers
    // ======================================================================

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float inverseLerp(float a, float b, float v) {
        if (Math.abs(b - a) < 1e-8f) return 0f;
        float t = (v - a) / (b - a);
        return MathHelper.clamp(t, 0f, 1f);
    }

    private static float lerpMap(float v, float inMin, float inMax, float outMin, float outMax) {
        float t = inverseLerp(inMin, inMax, v);
        return lerp(outMin, outMax, t);
    }

    private static Vec3d rotateAroundAxis(Vec3d v, Vec3d axisUnit, double angleRad) {
        // Rodrigues' rotation formula; axisUnit must be normalized
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);

        Vec3d term1 = v.multiply(cos);
        Vec3d term2 = axisUnit.crossProduct(v).multiply(sin);
        Vec3d term3 = axisUnit.multiply(axisUnit.dotProduct(v) * (1.0 - cos));

        return term1.add(term2).add(term3);
    }
}
