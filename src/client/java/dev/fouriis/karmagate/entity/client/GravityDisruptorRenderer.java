package dev.fouriis.karmagate.entity.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.gravity.GravityDisruptorBlock;
import dev.fouriis.karmagate.entity.gravity.GravityDisruptorBlockEntity;
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
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Renders the animated interior and libMod GravityDisruptor sphere for the
 * authored 13-block-wide casing model.
 */
public final class GravityDisruptorRenderer
        implements BlockEntityRenderer<GravityDisruptorBlockEntity> {
    private static final float PIXELS_PER_BLOCK = 20.0f;
    private static final float PANEL_WIDTH = 15.0f / PIXELS_PER_BLOCK;
    private static final float PANEL_LENGTH = 50.0f / PIXELS_PER_BLOCK;
    private static final float PANEL_CENTER_RADIUS = 52.5f / PIXELS_PER_BLOCK;
    private static final float PANEL_HALF_DEPTH = 2.5f / PIXELS_PER_BLOCK;
    private static final float EFFECT_DEPTH_OFFSET = 4.5f;
    private static final float INFLUENCE_RADIUS = 300.0f / PIXELS_PER_BLOCK;
    private static final float SPHERE_BOX_HALF_EXTENT =
            INFLUENCE_RADIUS / 1.7320508f;

    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    private static final int PANEL_PRIORITY = 940;
    private static final int PARTICLE_PRIORITY = 945;
    private static final int DISTORTION_PRIORITY = 1006;
    private static final Identifier GRAB_TEXTURE =
            Identifier.of("librainworldmc", "grabtex");

    public GravityDisruptorRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(GravityDisruptorBlockEntity disruptor, float tickDelta,
                       MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider consumers,
                       int light, int overlay) {
        if (disruptor.getWorld() == null) return;

        Direction facing = disruptor.getCachedState().get(GravityDisruptorBlock.FACING);
        Vec3d blockCenter = Vec3d.ofCenter(disruptor.getPos());
        Vec3d panelCenter = blockCenter.add(
                facing.getOffsetX() * EFFECT_DEPTH_OFFSET,
                0.0,
                facing.getOffsetZ() * EFFECT_DEPTH_OFFSET);
        float[] brightness = new float[GravityDisruptorBlockEntity.PANEL_COUNT];
        for (int i = 0; i < brightness.length; i++) {
            brightness[i] = disruptor.getPanelValue(i, tickDelta);
        }

        // The panels are late world geometry so their unlit blue is included
        // in the grab texture distorted by this and subsequent libMod effects.
        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(
                camera -> renderPanels(camera, panelCenter, facing, brightness), false), PANEL_PRIORITY);

        Vec3d[] particleOffsets = new Vec3d[GravityDisruptorBlockEntity.PARTICLE_COUNT];
        for (int i = 0; i < particleOffsets.length; i++) {
            particleOffsets[i] = disruptor.getParticleOffset(i, tickDelta);
        }
        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(
                camera -> renderParticles(camera, blockCenter, particleOffsets), false), PARTICLE_PRIORITY);

        queueDistortion(blockCenter, disruptor.getWorld().getTime() + tickDelta);
    }

    private static void renderPanels(Camera camera, Vec3d center, Direction facing,
                                     float[] brightness) {
        Vec3d cameraPos = camera.getPos();
        Matrix4f view = RenderUtils.getCameraMatrix(camera);
        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        // The supplied model's default face lies in XY. Its horizontal axis is
        // X for north/south placements and Z for east/west placements.
        double axisX = facing.getAxis() == Direction.Axis.Z ? 1.0 : 0.0;
        double axisZ = facing.getAxis() == Direction.Axis.X ? 1.0 : 0.0;
        double depthX = facing.getOffsetX();
        double depthZ = facing.getOffsetZ();

        for (int i = 0; i < GravityDisruptorBlockEntity.PANEL_COUNT; i++) {
            float angle = i / (float) GravityDisruptorBlockEntity.PANEL_COUNT * MathHelper.TAU;
            double radialHorizontal = MathHelper.sin(angle);
            double radialY = MathHelper.cos(angle);
            double tangentHorizontal = MathHelper.cos(angle);
            double tangentY = -MathHelper.sin(angle);

            Vec3d radial = new Vec3d(axisX * radialHorizontal, radialY, axisZ * radialHorizontal);
            Vec3d tangent = new Vec3d(axisX * tangentHorizontal, tangentY, axisZ * tangentHorizontal);
            Vec3d depth = new Vec3d(depthX, 0.0, depthZ);
            Vec3d panelCenter = center.add(radial.multiply(PANEL_CENTER_RADIUS));
            appendPanelBox(buffer, view, cameraPos, panelCenter, radial, tangent, depth,
                    MathHelper.clamp(brightness[i], 0.0f, 1.0f));
        }

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA,
                GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void appendPanelBox(BufferBuilder buffer, Matrix4f view, Vec3d cameraPos,
                                       Vec3d center, Vec3d radial, Vec3d tangent, Vec3d depth,
                                       float brightness) {
        float halfWidth = PANEL_WIDTH * 0.5f;
        float halfLength = PANEL_LENGTH * 0.5f;
        Vec3d[] vertices = new Vec3d[8];
        int at = 0;
        for (int z = -1; z <= 1; z += 2) {
            for (int r = -1; r <= 1; r += 2) {
                for (int t = -1; t <= 1; t += 2) {
                    vertices[at++] = center
                            .add(radial.multiply(r * halfLength))
                            .add(tangent.multiply(t * halfWidth))
                            .add(depth.multiply(z * PANEL_HALF_DEPTH));
                }
            }
        }

        int[][] faces = {
                {0, 1, 3, 2}, {4, 6, 7, 5},
                {0, 4, 5, 1}, {2, 3, 7, 6},
                {0, 2, 6, 4}, {1, 5, 7, 3}
        };
        for (int face = 0; face < faces.length; face++) {
            float shade = face == 0 ? 1.0f : face == 1 ? 0.8f : 0.48f;
            for (int index : faces[face]) {
                Vec3d point = vertices[index].subtract(cameraPos);
                buffer.vertex(view, (float) point.x, (float) point.y, (float) point.z)
                        .color(0.0f, 12.0f / 255.0f, brightness * shade, 0.93333334f);
            }
        }
    }

    /** Draws the reference's 20 unscaled one-pixel white orbiting specks. */
    private static void renderParticles(Camera camera, Vec3d center, Vec3d[] offsets) {
        Vec3d cameraPos = camera.getPos();
        Quaternionf cameraRotation = new Quaternionf(camera.getRotation());
        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f).rotate(cameraRotation);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f).rotate(cameraRotation);
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f).rotate(cameraRotation);
        ArrayList<VisibleSpeck> visible = new ArrayList<>(offsets.length);

        for (Vec3d offset : offsets) {
            Vec3d position = center.add(offset);
            Vec3d fromCamera = position.subtract(cameraPos);
            float depth = (float) (fromCamera.x * forward.x
                    + fromCamera.y * forward.y + fromCamera.z * forward.z);
            if (depth > 0.01f) visible.add(new VisibleSpeck(position, depth));
        }
        if (visible.isEmpty()) return;
        visible.sort(Comparator.comparingDouble(VisibleSpeck::depth).reversed());

        MinecraftClient client = MinecraftClient.getInstance();
        int framebufferHeight = Math.max(1, client.getFramebuffer().textureHeight);
        float projectionScale = Math.abs(RenderSystem.getProjectionMatrix().m11());
        if (projectionScale < 0.0001f) projectionScale = 1.0f;
        Matrix4f view = RenderUtils.getCameraMatrix(camera);
        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (VisibleSpeck speck : visible) {
            // A half extent of depth/(height*projectionScale) projects to half
            // a framebuffer pixel, preserving the source's unscaled 1x1 sprite.
            float halfSize = Math.max(0.0001f,
                    speck.depth / (framebufferHeight * projectionScale));
            Vec3d relative = speck.position.subtract(cameraPos);
            appendSpeckVertex(buffer, view, relative, right, up, -halfSize, -halfSize);
            appendSpeckVertex(buffer, view, relative, right, up, halfSize, -halfSize);
            appendSpeckVertex(buffer, view, relative, right, up, halfSize, halfSize);
            appendSpeckVertex(buffer, view, relative, right, up, -halfSize, halfSize);
        }

        var built = buffer.endNullable();
        if (built == null) return;
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void appendSpeckVertex(BufferBuilder buffer, Matrix4f view, Vec3d center,
                                          Vector3f right, Vector3f up,
                                          float horizontal, float vertical) {
        float x = (float) center.x + right.x * horizontal + up.x * vertical;
        float y = (float) center.y + right.y * horizontal + up.y * vertical;
        float z = (float) center.z + right.z * horizontal + up.z * vertical;
        buffer.vertex(view, x, y, z).color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void queueDistortion(Vec3d center, float sourceTime) {
        if (Shaders.GRAVITY_DISRUPTOR == null
                || Shaders.GRAVITY_DISRUPTOR.getProgram() == null) return;

        float halfSize = stabilizedSphereHalfSize(center);
        if (halfSize <= 0.0f) return;
        Box sphereBounds = new Box(
                center.x - SPHERE_BOX_HALF_EXTENT,
                center.y - SPHERE_BOX_HALF_EXTENT,
                center.z - SPHERE_BOX_HALF_EXTENT,
                center.x + SPHERE_BOX_HALF_EXTENT,
                center.y + SPHERE_BOX_HALF_EXTENT,
                center.z + SPHERE_BOX_HALF_EXTENT);
        float rain = sourceTime / 40.0f;

        RenderUtils.drawCameraFacingBillboardFitSphere(
                () -> bindDistortion(rain),
                center.x, center.y, center.z,
                sphereBounds,
                halfSize, halfSize,
                0.0f,
                1.0f, 1.0f, 1.0f, 1.0f, FULL_BRIGHT,
                true, DISTORTION_PRIORITY);
    }

    /**
     * Compensates for FitSphere moving its quad to the sphere surface and then
     * adding corner clearance. The resulting screen radius matches the actual
     * perspective projection of the reference's 15-block influence sphere.
     */
    private static float stabilizedSphereHalfSize(Vec3d center) {
        double distance = MinecraftClient.getInstance().gameRenderer.getCamera()
                .getPos().distanceTo(center);
        if (distance <= INFLUENCE_RADIUS + 0.01) return 0.0f;

        double projectedRatio = INFLUENCE_RADIUS
                / Math.sqrt(distance * distance - INFLUENCE_RADIUS * INFLUENCE_RADIUS);
        double compensated = projectedRatio * (distance - INFLUENCE_RADIUS)
                / (1.0 + Math.sqrt(2.0) * projectedRatio);
        return MathHelper.clamp((float) compensated, 0.01f, INFLUENCE_RADIUS);
    }

    @SuppressWarnings("deprecation")
    private static void bindDistortion(float rain) {
        CoreShaderRenderer.bindShader$GravityDisruptor(GRAB_TEXTURE, false);
        ShaderRenderer.setUniformF(Shaders.GRAVITY_DISRUPTOR.getProgram(), "u_RAIN", rain);
        MinecraftClient client = MinecraftClient.getInstance();
        ShaderRenderer.setUniformF(Shaders.GRAVITY_DISRUPTOR.getProgram(), "u_screenSize",
                client.getFramebuffer().textureWidth, client.getFramebuffer().textureHeight);
        RenderSystem.setShaderTexture(7, GRAB_TEXTURE);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    @Override
    public boolean rendersOutsideBoundingBox(GravityDisruptorBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 256;
    }

    private record VisibleSpeck(Vec3d position, float depth) {
    }
}
