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
 * Renderer for centipede body segments.
 * Uses applyRotations() to orient the body model along the chain direction.
 */
public class CentipedeBodyRenderer extends GeoEntityRenderer<CentipedeBodyEntity> {

    public CentipedeBodyRenderer(EntityRendererFactory.Context context) {
        super(context, new CentipedeBodyModel());
        this.shadowRadius = 0.25f;
        this.withScale(0.5f);
    }

    @Override
    public void render(CentipedeBodyEntity entity, float yaw, float tickDelta,
                       MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);

        // Render legs after the GeckoLib model
        matrices.push();
        CentipedeLegRenderer.renderLegs(entity, matrices, vertexConsumers, light, tickDelta);
        matrices.pop();
    }

    @Override
    protected void applyRotations(CentipedeBodyEntity entity, MatrixStack poseStack,
                                   float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
        // Compute chain direction for this body segment (average of neighbors)
        Vec3d dir = getChainDirection(entity, partialTick);

        // Compute yaw from horizontal direction
        float yaw = (float) (Math.atan2(-dir.x, dir.z) * (180.0 / Math.PI));

        // Apply yaw (GeckoLib convention: 180 - yaw)
        poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - yaw));

        // Apply pitch from vertical component
        float pitch = (float) (Math.asin(MathHelper.clamp(dir.y, -1, 1)) * (180.0 / Math.PI));
        poseStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-pitch));
    }

    /**
     * Get the direction this body segment faces — average of the direction from the
     * previous segment to this one, and from this one to the next segment.
     */
    private Vec3d getChainDirection(CentipedeBodyEntity entity, float tickDelta) {
        RedCentipedeEntity parent = entity.getParentCentipede();
        if (parent == null) return new Vec3d(0, 0, 1);

        CentipedeSegmentEntity[] segs = parent.getSegments();
        if (segs == null) return new Vec3d(0, 0, 1);

        int idx = entity.getSegmentIndex();
        Vec3d direction = Vec3d.ZERO;
        int count = 0;

        if (idx > 0 && segs[idx - 1] != null) {
            Vec3d prevPos = lerpPos(segs[idx - 1], tickDelta);
            Vec3d thisPos = lerpPos(entity, tickDelta);
            Vec3d d = prevPos.subtract(thisPos);
            if (d.lengthSquared() > 0.001) {
                direction = direction.add(d.normalize());
                count++;
            }
        }
        if (idx < segs.length - 1 && segs[idx + 1] != null) {
            Vec3d thisPos = lerpPos(entity, tickDelta);
            Vec3d nextPos = lerpPos(segs[idx + 1], tickDelta);
            Vec3d d = thisPos.subtract(nextPos);
            if (d.lengthSquared() > 0.001) {
                direction = direction.add(d.normalize());
                count++;
            }
        }

        if (count > 0) {
            direction = direction.multiply(1.0 / count);
            if (direction.lengthSquared() > 0.001) return direction.normalize();
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
    public Identifier getTextureLocation(CentipedeBodyEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/red_centipede.png");
    }
}
