package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer for centipede head segments.
 * Uses applyRotations() to orient the head model along the chain direction.
 */
public class CentipedeHeadRenderer extends GeoEntityRenderer<CentipedeHeadEntity> {

    public CentipedeHeadRenderer(EntityRendererFactory.Context context) {
        super(context, new CentipedeHeadModel());
        this.shadowRadius = 0.3f;
        this.withScale(0.5f);
    }

    @Override
    public void render(CentipedeHeadEntity entity, float yaw, float tickDelta,
                       MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);

        // Render legs after the GeckoLib model
        matrices.push();
        CentipedeLegRenderer.renderLegs(entity, matrices, vertexConsumers, light, tickDelta);
        matrices.pop();
    }

    @Override
    protected void applyRotations(CentipedeHeadEntity entity, MatrixStack poseStack,
                                   float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
        // Compute chain direction for this head segment
        Vec3d dir = getChainDirection(entity, partialTick);

        // Compute yaw from horizontal direction
        float yaw = (float) (Math.atan2(-dir.x, dir.z) * (180.0 / Math.PI));

        // Rear head should face the other way
        if (!entity.isFrontHead()) {
            yaw += 180f;
        }

        // Apply yaw (GeckoLib convention: 180 - yaw)
        poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - yaw));

        // Apply pitch from vertical component
        float pitch = (float) (Math.asin(MathHelper.clamp(dir.y, -1, 1)) * (180.0 / Math.PI));
        if (!entity.isFrontHead()) pitch = -pitch;
        poseStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-pitch));
    }

    /**
     * Get the direction this head should face (toward the adjacent body segment).
     */
    private Vec3d getChainDirection(CentipedeHeadEntity entity, float tickDelta) {
        RedCentipedeEntity parent = entity.getParentCentipede();
        if (parent == null) return new Vec3d(0, 0, 1);

        CentipedeSegmentEntity[] segs = parent.getSegments();
        if (segs == null) return new Vec3d(0, 0, 1);

        int idx = entity.getSegmentIndex();

        // Front head (index 0): direction FROM body TOWARD head = seg[0] - seg[1]
        if (idx == 0 && segs.length > 1 && segs[1] != null) {
            Vec3d thisPos = lerpPos(entity, tickDelta);
            Vec3d nextPos = lerpPos(segs[1], tickDelta);
            Vec3d d = thisPos.subtract(nextPos);
            return d.lengthSquared() > 0.001 ? d.normalize() : new Vec3d(0, 0, 1);
        }
        // Rear head (last index): direction FROM body TOWARD head = seg[N-1] - seg[N-2]
        if (idx == segs.length - 1 && idx > 0 && segs[idx - 1] != null) {
            Vec3d thisPos = lerpPos(entity, tickDelta);
            Vec3d prevPos = lerpPos(segs[idx - 1], tickDelta);
            Vec3d d = thisPos.subtract(prevPos);
            return d.lengthSquared() > 0.001 ? d.normalize() : new Vec3d(0, 0, 1);
        }

        return new Vec3d(0, 0, 1);
    }

    private Vec3d lerpPos(CentipedeSegmentEntity seg, float tickDelta) {
        return new Vec3d(
                MathHelper.lerp(tickDelta, seg.prevTickPos.x, seg.getPos().x),
                MathHelper.lerp(tickDelta, seg.prevTickPos.y, seg.getPos().y),
                MathHelper.lerp(tickDelta, seg.prevTickPos.z, seg.getPos().z)
        );
    }

    @Override
    public Identifier getTextureLocation(CentipedeHeadEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/red_centipede.png");
    }
}
