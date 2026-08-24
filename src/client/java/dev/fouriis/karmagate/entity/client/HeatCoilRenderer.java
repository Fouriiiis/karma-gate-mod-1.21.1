package dev.fouriis.karmagate.entity.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.karmagate.HeatCoilBlock;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasSpriteModel;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.ShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.Shaders;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

/** 3-D atlas-model adaptation of RegionGateGraphics' RegionGate_Heater. */
public final class HeatCoilRenderer implements BlockEntityRenderer<HeatCoilBlockEntity> {
    private static final float PIXELS_PER_BLOCK = 20.0f;
    private static final float HEATER_MODEL_DEPTH_PIXELS = 3.0f;

    // Tilt the heater 45 degrees around its LOCAL X axis. FACING still
    // controls horizontal yaw; this tilt rotates only the local Y/Z plane.
    private static final float HEATER_SIDEWAYS_TILT_DEGREES = -45.0f;

    // Run after libMod's conventional priority-1000 post effects. Recapturing
    // immediately before every coil prevents one grabtex shader from restoring
    // a stale scene over another, and also composes overlapping heat coils.
    private static final int HEAT_DISTORTION_PRIORITY = 1007;
    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise-hq.png");
    private static final Identifier GRAB_TEXTURE = Identifier.of("librainworldmc", "grabtex");

    // palette1 pixels (2,0) and (1,0), retained from RegionGateGraphics.
    private static final Rgb BLACK_COLOR = new Rgb(19.0f / 255.0f, 0.0f, 17.0f / 255.0f);
    private static final Rgb FOG_COLOR = new Rgb(107.0f / 255.0f, 171.0f / 255.0f, 165.0f / 255.0f);

    private static FAtlasSpriteModel heaterModel;
    private static ModelBounds heaterModelBounds;

    public HeatCoilRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(HeatCoilBlockEntity heater, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int light, int overlay) {
        FAtlasSpriteModel model = getHeaterModel();
        if (model == null || model.element().textureIdentifier == null) return;

        ModelBounds bounds = getHeaterModelBounds(model);

        float heat = MathHelper.clamp(heater.getInterpolatedHeaterHeat(tickDelta), 0.0f, 1.0f);
        Rgb hotColor = heaterColor(heat);
        Rgb shadeTarget = mix(BLACK_COLOR, FOG_COLOR, MathHelper.lerp(heat, 0.3f, 0.8f));
        Rgb shadeColor = mix(hotColor, shadeTarget, MathHelper.lerp(heat, 0.8f, 0.1f));
        Direction facing = heater.getCachedState().get(HeatCoilBlock.FACING);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumer vertices = consumers.getBuffer(
                RenderLayer.getEntityCutoutNoCull(model.element().textureIdentifier));

        renderHeaterModel(vertices, matrix, facing, model, bounds, hotColor, shadeColor);

        queueHeatDistortion(heater, tickDelta);
    }

    private static void renderHeaterModel(VertexConsumer vertices, Matrix4f matrix, Direction facing,
                                          FAtlasSpriteModel model, ModelBounds bounds,
                                          Rgb hotColor, Rgb shadeColor) {
        for (FAtlasSpriteModel.Quad quad : model.quads()) {
            // The sprite model's +Z face becomes the heater's upward face. Its
            // lower face retains the C# shade color; edge extrusion is blended
            // between them so the generated silhouette reads as solid.
            float upward = MathHelper.clamp(quad.normalZ() * 0.5f + 0.5f, 0.0f, 1.0f);
            Rgb color = mix(shadeColor, hotColor, upward);
            emitModelVertex(vertices, matrix, facing, bounds, quad, quad.a(), color);
            emitModelVertex(vertices, matrix, facing, bounds, quad, quad.b(), color);
            emitModelVertex(vertices, matrix, facing, bounds, quad, quad.c(), color);
            emitModelVertex(vertices, matrix, facing, bounds, quad, quad.d(), color);
        }
    }

    private static void emitModelVertex(VertexConsumer vertices, Matrix4f matrix, Direction facing,
                                        ModelBounds bounds, FAtlasSpriteModel.Quad quad,
                                        FAtlasSpriteModel.Vertex vertex, Rgb color) {
        // FACING remains the only horizontal rotation. The extra 45 degrees is
        // applied around the model's local X axis, so the heater tilts through
        // its Y/Z plane rather than rotating horizontally.
        float yaw = facing.asRotation() * MathHelper.RADIANS_PER_DEGREE;
        float yawCos = MathHelper.cos(yaw);
        float yawSin = MathHelper.sin(yaw);
        float tilt = HEATER_SIDEWAYS_TILT_DEGREES * MathHelper.RADIANS_PER_DEGREE;
        float tiltCos = MathHelper.cos(tilt);
        float tiltSin = MathHelper.sin(tilt);

        // Centre all three model axes around the actual generated geometry. In
        // this renderer atlas X -> local X, atlas Y -> local Z, and extrusion Z
        // -> local Y. With no additional base-height offset, the transformed
        // model's centre is exactly the block centre at (0.5, 0.5, 0.5).
        float localX = (vertex.x() - bounds.centerX()) / PIXELS_PER_BLOCK;
        float localY = (vertex.z() - bounds.centerZ()) / PIXELS_PER_BLOCK;
        float localZ = (vertex.y() - bounds.centerY()) / PIXELS_PER_BLOCK;

        // Sideways tilt around the model's local X axis. This changes Y/Z
        // while leaving local X unchanged.
        // LOCAL X-AXIS ROTATION: X stays fixed, Y/Z rotate.
        float tiltedX = localX;
        float tiltedY = localY * tiltCos - localZ * tiltSin;
        float tiltedZ = localY * tiltSin + localZ * tiltCos;

        // Then orient that tilted model horizontally according to block FACING.
        float rotatedX = tiltedX * yawCos - tiltedZ * yawSin;
        float rotatedZ = tiltedX * yawSin + tiltedZ * yawCos;

        // FAtlasSpriteModel normals use atlas X/Y plus extrusion Z, so remap
        // them into the same local X/Y/Z basis and apply the identical rotations.
        float localNormalX = quad.normalX();
        float localNormalY = quad.normalZ();
        float localNormalZ = quad.normalY();
        // Apply the same LOCAL X-axis rotation to the normal.
        float tiltedNormalX = localNormalX;
        float tiltedNormalY = localNormalY * tiltCos - localNormalZ * tiltSin;
        float tiltedNormalZ = localNormalY * tiltSin + localNormalZ * tiltCos;
        float normalX = tiltedNormalX * yawCos - tiltedNormalZ * yawSin;
        float normalZ = tiltedNormalX * yawSin + tiltedNormalZ * yawCos;

        vertices.vertex(matrix,
                        0.5f + rotatedX,
                        0.5f + tiltedY,
                        0.5f + rotatedZ)
                .color(color.r, color.g, color.b, 1.0f)
                .texture(vertex.u(), vertex.v())
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(FULL_BRIGHT)
                .normal(normalX, tiltedNormalY, normalZ);
    }

    private static void queueHeatDistortion(HeatCoilBlockEntity heater, float tickDelta) {
        float targetHeat = MathHelper.clamp(
                heater.isGateManaged() ? heater.getGateTargetHeat() : heater.getVisualHeat(),
                0.0f, 1.0f);
        float alpha = heater.isGateManaged()
                ? heater.getGateDistortionAlpha()
                : sCurve(targetHeat, 1.5f);
        if (targetHeat <= 0.0f || alpha <= 0.0f || heater.getWorld() == null) return;
        float factor = inverseLerp(0.15f, 0.8f, targetHeat);
        float halfWidth = 0.5f * (16.0f * MathHelper.lerp(factor, 10.0f, 15.0f)) / PIXELS_PER_BLOCK;
        float halfHeight = 0.5f * (16.0f * MathHelper.lerp(factor, 15.0f, 30.0f)) / PIXELS_PER_BLOCK;
        float rain = (heater.getWorld().getTime() + tickDelta) / 100.0f;
        double centerX = heater.getPos().getX() + 0.5;
        double centerY = heater.getPos().getY() + 0.5 + 2.0f * factor;
        double centerZ = heater.getPos().getZ() + 0.5;

        RenderUtils.drawCameraFacingBillboardOffset(() -> {
                    CoreShaderRenderer.bindShader$HeatDistortion(NOISE_TEXTURE, GRAB_TEXTURE, false);
                    if (Shaders.HEAT_DISTORTION != null && Shaders.HEAT_DISTORTION.getProgram() != null) {
                        ShaderRenderer.setUniformF(Shaders.HEAT_DISTORTION.getProgram(), "u_RAIN", rain);
                    }
                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                },
                centerX, centerY, centerZ,
                halfWidth, halfHeight,
                // C# centers the distortion at heaterPositions[k] + (0, 40*f).
                // A camera-relative push introduces parallax and moves it off
                // the heater whenever the block is away from screen center.
                0.0f,
                0.0f, 0.0f, 0.0f,
                1.0f, 1.0f, 1.0f, alpha, FULL_BRIGHT,
                true, HEAT_DISTORTION_PRIORITY);
    }

    private static FAtlasSpriteModel getHeaterModel() {
        if (heaterModel != null) return heaterModel;
        try {
            heaterModel = LibrainworldmcClient.getAtlasManager()
                    .getModelWithName("RegionGate_Heater", HEATER_MODEL_DEPTH_PIXELS);
            heaterModelBounds = null;
        } catch (IllegalStateException ignored) {
            // libMod atlas initialization can briefly lag the first rendered frame.
        }
        return heaterModel;
    }

    private static ModelBounds getHeaterModelBounds(FAtlasSpriteModel model) {
        if (heaterModelBounds != null) return heaterModelBounds;

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        for (FAtlasSpriteModel.Quad quad : model.quads()) {
            FAtlasSpriteModel.Vertex a = quad.a();
            FAtlasSpriteModel.Vertex b = quad.b();
            FAtlasSpriteModel.Vertex c = quad.c();
            FAtlasSpriteModel.Vertex d = quad.d();

            minX = Math.min(minX, Math.min(Math.min(a.x(), b.x()), Math.min(c.x(), d.x())));
            minY = Math.min(minY, Math.min(Math.min(a.y(), b.y()), Math.min(c.y(), d.y())));
            minZ = Math.min(minZ, Math.min(Math.min(a.z(), b.z()), Math.min(c.z(), d.z())));
            maxX = Math.max(maxX, Math.max(Math.max(a.x(), b.x()), Math.max(c.x(), d.x())));
            maxY = Math.max(maxY, Math.max(Math.max(a.y(), b.y()), Math.max(c.y(), d.y())));
            maxZ = Math.max(maxZ, Math.max(Math.max(a.z(), b.z()), Math.max(c.z(), d.z())));
        }

        // Defensive fallback for an unexpectedly empty model. In normal use the
        // quad-derived bounds are preferred because they describe the real geometry.
        if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(minZ)
                || !Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)) {
            minX = 0.0f;
            minY = 0.0f;
            minZ = -model.depth() * 0.5f;
            maxX = model.width();
            maxY = model.height();
            maxZ = model.depth() * 0.5f;
        }

        heaterModelBounds = new ModelBounds(
                (minX + maxX) * 0.5f,
                (minY + maxY) * 0.5f,
                (minZ + maxZ) * 0.5f,
                (maxX - minX) * 0.5f,
                (maxY - minY) * 0.5f,
                (maxZ - minZ) * 0.5f);
        return heaterModelBounds;
    }

    private static Rgb heaterColor(float heat) {
        Rgb result = mix(BLACK_COLOR, new Rgb(1.0f, 0.0f, 0.0f), inverseLerp(0.0f, 0.3f, heat));
        if (heat > 0.3f) {
            float value = inverseLerp(0.3f, 1.0f, heat);
            result = hslToRgb(value * 0.16f, 1.0f, 0.5f + 0.2f * value);
        }
        return result;
    }

    private static Rgb hslToRgb(float hue, float saturation, float lightness) {
        float maximum = lightness <= 0.5f
                ? lightness * (1.0f + saturation)
                : lightness + saturation - lightness * saturation;
        if (maximum <= 0.0f) return new Rgb(lightness, lightness, lightness);
        float minimum = lightness + lightness - maximum;
        float range = (maximum - minimum) / maximum;
        float sectorValue = hue * 6.0f;
        int sector = (int) sectorValue;
        float fraction = sectorValue - sector;
        float rise = maximum * range * fraction;
        float up = minimum + rise;
        float down = maximum - rise;
        return switch (sector) {
            case 0 -> new Rgb(maximum, up, minimum);
            case 1 -> new Rgb(down, maximum, minimum);
            case 2 -> new Rgb(minimum, maximum, up);
            case 3 -> new Rgb(minimum, down, maximum);
            case 4 -> new Rgb(up, minimum, maximum);
            default -> new Rgb(maximum, minimum, down);
        };
    }

    private static float inverseLerp(float a, float b, float value) {
        return MathHelper.clamp((value - a) / (b - a), 0.0f, 1.0f);
    }

    private static float sCurve(float x, float k) {
        x = MathHelper.clamp(x, 0.0f, 1.0f) * 2.0f - 1.0f;
        if (x < 0.0f) {
            x = Math.abs(1.0f + x);
            return k * x / (k - x + 1.0f) * 0.5f;
        }
        k = -1.0f - k;
        return 0.5f + k * x / (k - x + 1.0f) * 0.5f;
    }

    private static Rgb mix(Rgb a, Rgb b, float amount) {
        return new Rgb(MathHelper.lerp(amount, a.r, b.r),
                MathHelper.lerp(amount, a.g, b.g), MathHelper.lerp(amount, a.b, b.b));
    }

    private record ModelBounds(float centerX, float centerY, float centerZ,
                               float halfWidth, float halfHeight, float halfDepth) {
    }

    private record Rgb(float r, float g, float b) {
    }

    @Override
    public boolean rendersOutsideBoundingBox(HeatCoilBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 256;
    }
}
