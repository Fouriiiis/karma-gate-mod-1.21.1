package dev.fouriis.karmagate.hologram;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.hologram.HologramProjectorBlock;
import dev.fouriis.karmagate.entity.hologram.HologramProjectorBlockEntity;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.ShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.Shaders;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/** World-space adaptation of Rain World's GateKarmaGlyph/GateHologram. */
public final class HologramProjectorRenderer
        implements BlockEntityRenderer<HologramProjectorBlockEntity> {
    private static final float PIXELS_PER_BLOCK = 20.0f;
    private static final float ANCHOR_Y_PIXELS = 112.0f;
    private static final float GLYPH_FROM_WALL_PIXELS = 3.4f;
    private static final float HOLOGRAM_THRESHOLD = 0.5f;
    private static final float EMPTY_THRESHOLD_MARGIN = 0.001f;
    private static final float GLYPH_ALPHA_SCALE = 0.9f;
    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    // GateHologram multiplies u_RAIN by 145.14 in a float shader. Wrapping the
    // clock preserves sub-tick precision in worlds with very large game times.
    private static final long SHADER_TIME_PERIOD_TICKS = 409_600L;

    // The glyph is intentionally before steam and grab-texture distortion passes,
    // allowing all of the libMod effects to compose in scene order.
    private static final int GLYPH_PRIORITY = 925;
    // LibMod's noise2.png is a mostly two-level diagonal dither: its cutoff
    // mask barely changes between full power and GateKarmaGlyph's 0.82
    // low-power fade, then pops away. noise_large is LibMod's continuous,
    // high-frequency Rain World noise and reproduces the reference's
    // progressively shrinking random cutout without bundling a local copy.
    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise_large.png");
    private static final Map<String, FAtlasElement> GLYPHS = new HashMap<>();
    private static AbstractTexture filteredNoiseTexture;
    private static AbstractTexture filteredGlyphTexture;

    public HologramProjectorRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(HologramProjectorBlockEntity glyph, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int light, int overlay) {
        if (glyph.getWorld() == null) return;
        FAtlasElement element = getGlyph(glyph.getSymbolKey());
        if (element == null || element.textureIdentifier == null) return;
        ensureUnityFiltering(element.textureIdentifier);

        float fade = MathHelper.clamp(glyph.getInterpolatedFade(tickDelta), 0.0f, 1.0f);
        if (fade <= 0.0f) return;
        int color = glyph.getInterpolatedGlyphColor(tickDelta);
        Direction facing = glyph.getCachedState().get(HologramProjectorBlock.FACING);
        double anchorX = glyph.getPos().getX() + 0.5;
        double anchorY = glyph.getPos().getY() + ANCHOR_Y_PIXELS / PIXELS_PER_BLOCK;
        double anchorZ = glyph.getPos().getZ() + 0.5;
        long wrappedTime = Math.floorMod(
                glyph.getWorld().getTime(),
                SHADER_TIME_PERIOD_TICKS);
        float rain = (wrappedTime + tickDelta) / 100.0f;

        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(camera ->
                renderGlyph(camera, facing, anchorX, anchorY, anchorZ,
                        element, fade, color, rain), false), GLYPH_PRIORITY);
    }

    private static void renderGlyph(Camera camera, Direction facing,
                                    double anchorX, double anchorY, double anchorZ,
                                    FAtlasElement element, float fade, int color, float rain) {
        float sourceWidth = positive(element.sourcePixelSize.x, element.sourceRect.width);
        float sourceHeight = positive(element.sourcePixelSize.y, element.sourceRect.height);
        float width = positive(element.sourceRect.width, sourceWidth);
        float height = positive(element.sourceRect.height, sourceHeight);

        // Futile's trimmed-sprite layout: anchor=(.5,.75), with sourceRect Y
        // measured down from the top of the original 62x149 logical image.
        float left = (-0.5f * sourceWidth + element.sourceRect.x) / PIXELS_PER_BLOCK;
        float bottom = (-0.75f * sourceHeight
                + sourceHeight - element.sourceRect.y - height) / PIXELS_PER_BLOCK;
        float right = left + width / PIXELS_PER_BLOCK;
        float top = bottom + height / PIXELS_PER_BLOCK;

        Vec3d cameraPos = camera.getPos();
        float nx = facing.getOffsetX();
        float nz = facing.getOffsetZ();
        float hx = nz;
        float hz = -nx;
        float forward = GLYPH_FROM_WALL_PIXELS / PIXELS_PER_BLOCK;
        float centerX = (float) (anchorX + nx * forward - cameraPos.x);
        float centerY = (float) (anchorY - cameraPos.y);
        float centerZ = (float) (anchorZ + nz * forward - cameraPos.z);
        Matrix4f view = new Matrix4f(RenderUtils.getCameraMatrix(camera));
        int red = (color >>> 16) & 0xFF;
        int green = (color >>> 8) & 0xFF;
        int blue = color & 0xFF;
        int alpha = Math.round(fade * GLYPH_ALPHA_SCALE * 255.0f);
        float shaderAlpha = alpha / 255.0f;
        float threshold = cutoffThreshold(fade, shaderAlpha);

        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);
        glyphVertex(buffer, view, centerX + hx * left, centerY + top, centerZ + hz * left,
                red, green, blue, alpha, 0.0f, 0.0f);
        glyphVertex(buffer, view, centerX + hx * right, centerY + top, centerZ + hz * right,
                red, green, blue, alpha, 1.0f, 0.0f);
        glyphVertex(buffer, view, centerX + hx * right, centerY + bottom, centerZ + hz * right,
                red, green, blue, alpha, 1.0f, 1.0f);
        glyphVertex(buffer, view, centerX + hx * left, centerY + bottom, centerZ + hz * left,
                red, green, blue, alpha, 0.0f, 1.0f);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        /*
         * Match GateHologram exactly: vertex alpha controls the animated noise
         * cutoff and horizontal stabilization, while surviving pixels remain
         * fully luminous. It is deliberately not a conventional opacity fade.
         */
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        boolean shaderApplied = false;
        try {
            if (Shaders.GATE_HOLOGRAM != null && Shaders.GATE_HOLOGRAM.getProgram() != null) {
                CoreShaderRenderer.bindShader$GateHologram(
                        threshold, element.textureIdentifier, NOISE_TEXTURE, false);
                ShaderRenderer.setUniformF(Shaders.GATE_HOLOGRAM.getProgram(),
                        "_Sampler0_ST", 1.0f, 1.0f, 0.0f, 0.0f);
                ShaderRenderer.setUniformF(Shaders.GATE_HOLOGRAM.getProgram(), "u_RAIN", rain);
                MinecraftClient client = MinecraftClient.getInstance();
                ShaderRenderer.setUniformF(Shaders.GATE_HOLOGRAM.getProgram(), "u_screenSize",
                        client.getFramebuffer().textureWidth * 0.5f,
                        client.getFramebuffer().textureHeight * 0.5f);
                ShaderRenderer.setUniformF(Shaders.GATE_HOLOGRAM.getProgram(),
                        "u_spriteRect", 0.0f, 0.0f, 1.0f, 1.0f);
                shaderApplied = true;
            }
        } catch (RuntimeException ignored) {
            // Resource reloads can briefly leave the program unavailable.
        }
        if (!shaderApplied) RenderSystem.setShader(GameRenderer::getParticleProgram);
        RenderSystem.setShaderTexture(0, element.textureIdentifier);
        BufferRenderer.drawWithGlobalProgram(buffer.end());

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getParticleProgram);
        RenderSystem.setShaderTexture(0, SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void glyphVertex(BufferBuilder buffer, Matrix4f matrix,
                                    float x, float y, float z, int red, int green, int blue,
                                    int alpha, float u, float v) {
        buffer.vertex(matrix, x, y, z)
                .color(red, green, blue, alpha)
                .texture(u, v)
                .light(FULL_BRIGHT);
    }

    private static FAtlasElement getGlyph(String key) {
        String name = key.endsWith(".png") ? key.substring(0, key.length() - 4) : key;
        if (GLYPHS.containsKey(name)) return GLYPHS.get(name);
        try {
            FAtlasElement element = LibrainworldmcClient.getAtlasManager().getElementWithName(name);
            if (element != null) GLYPHS.put(name, element);
            return element;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    /**
     * Unity imports _NoiseTex2 with bilinear filtering. Preserve that sampling
     * on LibMod's Rain World texture so the cutoff changes progressively.
     */
    private static void ensureUnityFiltering(Identifier glyphTextureId) {
        AbstractTexture noiseTexture = MinecraftClient.getInstance()
                .getTextureManager()
                .getTexture(NOISE_TEXTURE);
        if (noiseTexture != filteredNoiseTexture) {
            noiseTexture.setFilter(true, false);
            filteredNoiseTexture = noiseTexture;
        }

        // The isolated renderer bilinearly samples atlas alpha after applying
        // horizontal shimmer; nearest filtering makes its edges jump as UVs
        // shift from one texel to the next.
        AbstractTexture glyphTexture = MinecraftClient.getInstance()
                .getTextureManager()
                .getTexture(glyphTextureId);
        if (glyphTexture != filteredGlyphTexture) {
            glyphTexture.setFilter(true, false);
            filteredGlyphTexture = glyphTexture;
        }
    }

    private static float positive(float preferred, float fallback) {
        return preferred > 0.0f ? preferred : Math.max(fallback, 1.0f);
    }

    /**
     * GateHologram keeps a fragment when
     * {@code noise * 2 - vertexAlpha^2 <= threshold}. With the original fixed
     * 0.5 threshold, alpha zero still retains every noise value up to 0.25;
     * the renderer then stopped drawing and made that large remnant pop away.
     *
     * Keep the original cutoff at full fade, but make the surviving noise
     * interval shrink linearly to an empty interval at zero fade. The small
     * negative margin also removes texels whose noise value is exactly zero.
     */
    private static float cutoffThreshold(float fade, float shaderAlpha) {
        float fullNoiseLimit = (HOLOGRAM_THRESHOLD
                + GLYPH_ALPHA_SCALE * GLYPH_ALPHA_SCALE) * 0.5f;
        return 2.0f * fullNoiseLimit * fade
                - shaderAlpha * shaderAlpha
                - EMPTY_THRESHOLD_MARGIN * (1.0f - fade);
    }

    @Override
    public boolean rendersOutsideBoundingBox(HologramProjectorBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 256;
    }
}
