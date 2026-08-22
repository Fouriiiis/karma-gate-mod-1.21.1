package dev.fouriis.karmagate.entity.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.karmagate.BatteryMeterBlock;
import dev.fouriis.karmagate.entity.karmagate.BatteryMeterBlockEntity;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.concurrent.ThreadLocalRandom;

/** World-space reproduction of RegionGateGraphics' electric battery sprite. */
public final class BatteryMeterRenderer implements BlockEntityRenderer<BatteryMeterBlockEntity> {
    private static final float PIXELS_PER_BLOCK = 20.0f;
    private static final float SOURCE_MAX_WIDTH = 420.0f;
    private static final float SOURCE_HEIGHT = 8.0f;
    private static final float SURFACE_OFFSET = 0.002f;
    private static final float LIGHT_RECESS = 3.0f / 16.0f;
    private static final float MODEL_RIGHT_OFFSET = (16.8f - 8.0f) / 16.0f;
    private static final float MODEL_VERTICAL_OFFSET = -8.0f / 16.0f;
    private static final int METER_PRIORITY = 990;

    // palette1 blackColor, used by the other RegionGateGraphics adaptations.
    private static final Rgb BLACK_COLOR = new Rgb(19.0f / 255.0f, 0.0f, 17.0f / 255.0f);

    public BatteryMeterRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(BatteryMeterBlockEntity meter, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int light, int overlay) {
        // Render the supplied 22x2 model here instead of through the chunk
        // mesh. This renderer advertises its oversized bounds below, keeping
        // the casing visible when its one-block placement anchor is offscreen.
        var blockRenderManager = MinecraftClient.getInstance().getBlockRenderManager();
        var state = meter.getCachedState();
        matrices.push();
        // renderBlockAsEntity cannot be used here: it explicitly returns for
        // INVISIBLE blocks. Render the already-baked quads directly so the
        // casing remains BE-only and is not duplicated in the chunk mesh.
        blockRenderManager.getModelRenderer().render(
                matrices.peek(),
                consumers.getBuffer(RenderLayers.getEntityBlockLayer(state, false)),
                state,
                blockRenderManager.getModel(state),
                1.0f,
                1.0f,
                1.0f,
                light,
                overlay);
        matrices.pop();

        if (meter.getWorld() == null || !meter.shouldRenderMeter()) return;

        float charge = MathHelper.clamp(meter.getInterpolatedBattery(tickDelta), 0.0f, 1.0f);
        boolean changing = meter.isBatteryChanging();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // C#: 420*batteryLeft - Random.value*5 while the battery is running.
        float widthPixels = SOURCE_MAX_WIDTH * charge
                - (changing ? random.nextFloat() * 5.0f : 0.0f);
        float width = Math.max(0.0f, widthPixels / PIXELS_PER_BLOCK);
        if (width <= 0.0001f) return;

        // RoomPalette.darkness dims the source color before its 50% blend to
        // blackColor. Minecraft's combined local light is the closest 3-D
        // equivalent and preserves the bright-room appearance of palette1.
        float localLight = meter.getWorld().getLightLevel(meter.getPos()) / 15.0f;
        float darkness = 1.0f - MathHelper.clamp(localLight, 0.0f, 1.0f);
        float activity = changing ? 1.0f : 0.0f;
        float hue = 0.03f + random.nextFloat() * (0.035f * activity + 0.025f);
        float lightness = (0.5f + random.nextFloat() * 0.2f * activity)
                * MathHelper.lerp(darkness, 1.0f, 0.25f);
        Rgb color = mix(hslToRgb(hue, 1.0f, lightness), BLACK_COLOR, 0.5f);

        Direction facing = meter.getCachedState().get(BatteryMeterBlock.FACING);
        Direction modelRight = facing.rotateYClockwise();
        // Preserve the supplied model's authored origin: its visible centre is
        // x=16.8, y=0 and its front face is z=8 model units. Rotate that local
        // offset with FACING, then recess the live strip three model units so
        // it sits inside the supplied casing instead of in front of it.
        Vec3d center = Vec3d.ofCenter(meter.getPos()).add(
                modelRight.getOffsetX() * MODEL_RIGHT_OFFSET
                        + facing.getOffsetX() * (SURFACE_OFFSET - LIGHT_RECESS),
                MODEL_VERTICAL_OFFSET,
                modelRight.getOffsetZ() * MODEL_RIGHT_OFFSET
                        + facing.getOffsetZ() * (SURFACE_OFFSET - LIGHT_RECESS));
        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(
                camera -> drawMeter(camera, center, facing, width, color), false), METER_PRIORITY);
    }

    private static void drawMeter(Camera camera, Vec3d center, Direction facing,
                                  float width, Rgb color) {
        Vec3d relative = center.subtract(camera.getPos());
        Matrix4f view = RenderUtils.getCameraMatrix(camera);
        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        float halfWidth = width * 0.5f;
        float halfHeight = SOURCE_HEIGHT / PIXELS_PER_BLOCK * 0.5f;
        float horizontalX = facing.getAxis() == Direction.Axis.Z ? halfWidth : 0.0f;
        float horizontalZ = facing.getAxis() == Direction.Axis.X ? halfWidth : 0.0f;
        float x = (float) relative.x;
        float y = (float) relative.y;
        float z = (float) relative.z;

        appendVertex(buffer, view, x - horizontalX, y - halfHeight, z - horizontalZ, color);
        appendVertex(buffer, view, x + horizontalX, y - halfHeight, z + horizontalZ, color);
        appendVertex(buffer, view, x + horizontalX, y + halfHeight, z + horizontalZ, color);
        appendVertex(buffer, view, x - horizontalX, y + halfHeight, z - horizontalZ, color);

        var built = buffer.endNullable();
        if (built == null) return;

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA,
                GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void appendVertex(BufferBuilder buffer, Matrix4f view,
                                     float x, float y, float z, Rgb color) {
        // RegionGateGraphics uses alpha=14/15 as CustomDepth's virtual layer,
        // while the shader still outputs the opaque pixel texture. This 3-D
        // version gets that occlusion from Minecraft's real depth buffer.
        buffer.vertex(view, x, y, z)
                .color(color.r, color.g, color.b, 1.0f);
    }

    private static Rgb mix(Rgb from, Rgb to, float amount) {
        return new Rgb(
                MathHelper.lerp(amount, from.r, to.r),
                MathHelper.lerp(amount, from.g, to.g),
                MathHelper.lerp(amount, from.b, to.b));
    }

    private static Rgb hslToRgb(float hue, float saturation, float lightness) {
        hue = hue - (float) Math.floor(hue);
        float chroma = (1.0f - Math.abs(2.0f * lightness - 1.0f)) * saturation;
        float section = hue * 6.0f;
        float x = chroma * (1.0f - Math.abs(section % 2.0f - 1.0f));
        float r;
        float g;
        float b;
        if (section < 1.0f) {
            r = chroma; g = x; b = 0.0f;
        } else if (section < 2.0f) {
            r = x; g = chroma; b = 0.0f;
        } else if (section < 3.0f) {
            r = 0.0f; g = chroma; b = x;
        } else if (section < 4.0f) {
            r = 0.0f; g = x; b = chroma;
        } else if (section < 5.0f) {
            r = x; g = 0.0f; b = chroma;
        } else {
            r = chroma; g = 0.0f; b = x;
        }
        float match = lightness - chroma * 0.5f;
        return new Rgb(r + match, g + match, b + match);
    }

    @Override
    public boolean rendersOutsideBoundingBox(BatteryMeterBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 256;
    }

    private record Rgb(float r, float g, float b) {
    }
}
