package dev.fouriis.karmagate.entity.lizard;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

public class AbstractLizardRenderer<T extends AbstractLizardEntity> extends EntityRenderer<T> {
    private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");

    public AbstractLizardRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.0f;
    }

    @Override
    public void render(T entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        LizardPoseSnapshot pose = entity.getRenderPose(tickDelta);
        if (pose == null || pose == LizardPoseSnapshot.EMPTY || pose.body().length == 0) {
            return;
        }

        Vec3d origin = new Vec3d(
                MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()),
                MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()),
                MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ())
        );

        int bodyColor = entity.getBreed().bodyColorRgb();
        int limbColor = entity.getBreed().limbColorRgb();
        int tailColor = darken(bodyColor, 0.8f);

        matrices.push();
        renderLine(matrices, vertexConsumers, pose.body()[0].subtract(origin), pose.head().subtract(origin), bodyColor);

        for (int i = 0; i < pose.body().length - 1; i++) {
            renderLine(matrices, vertexConsumers, pose.body()[i].subtract(origin), pose.body()[i + 1].subtract(origin), bodyColor);
        }
        for (int i = 0; i < pose.tail().length; i++) {
            Vec3d start = (i == 0 ? pose.body()[pose.body().length - 1] : pose.tail()[i - 1]).subtract(origin);
            Vec3d end = pose.tail()[i].subtract(origin);
            renderLine(matrices, vertexConsumers, start, end, tailColor);
        }
        for (LizardPoseSnapshot.LegPose leg : pose.legs()) {
            renderLine(matrices, vertexConsumers, leg.attach().subtract(origin), leg.knee().subtract(origin), limbColor);
            renderLine(matrices, vertexConsumers, leg.knee().subtract(origin), leg.foot().subtract(origin), limbColor);
            renderCross(matrices, vertexConsumers, leg.foot().subtract(origin), 0.05, limbColor);
        }
        renderCross(matrices, vertexConsumers, pose.head().subtract(origin), 0.07, bodyColor);
        matrices.pop();
    }

    @Override
    public Identifier getTexture(T entity) {
        return WHITE;
    }

    private static void renderLine(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Vec3d start, Vec3d end, int color) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.LINES);
        Vec3d dir = end.subtract(start);
        float nx = 0.0f;
        float ny = 1.0f;
        float nz = 0.0f;
        if (dir.lengthSquared() > 1.0e-6) {
            Vec3d normal = dir.normalize();
            nx = (float) normal.x;
            ny = (float) normal.y;
            nz = (float) normal.z;
        }
        vc.vertex(mat, (float) start.x, (float) start.y, (float) start.z).color(red(color), green(color), blue(color), 255).normal(nx, ny, nz);
        vc.vertex(mat, (float) end.x, (float) end.y, (float) end.z).color(red(color), green(color), blue(color), 255).normal(nx, ny, nz);
    }

    private static void renderCross(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Vec3d center, double radius, int color) {
        renderLine(matrices, vertexConsumers, center.add(-radius, 0.0, 0.0), center.add(radius, 0.0, 0.0), color);
        renderLine(matrices, vertexConsumers, center.add(0.0, -radius, 0.0), center.add(0.0, radius, 0.0), color);
        renderLine(matrices, vertexConsumers, center.add(0.0, 0.0, -radius), center.add(0.0, 0.0, radius), color);
    }

    private static int darken(int color, float factor) {
        int r = Math.max(0, Math.min(255, (int) (red(color) * factor)));
        int g = Math.max(0, Math.min(255, (int) (green(color) * factor)));
        int b = Math.max(0, Math.min(255, (int) (blue(color) * factor)));
        return (r << 16) | (g << 8) | b;
    }

    private static int red(int color) {
        return (color >> 16) & 255;
    }

    private static int green(int color) {
        return (color >> 8) & 255;
    }

    private static int blue(int color) {
        return color & 255;
    }
}
