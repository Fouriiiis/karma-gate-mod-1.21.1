package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/**
 * Renderer for centipede body segments.
 * Uses applyRotations() to orient the body model along the chain direction,
 * including full pitch/yaw/roll for wall and ceiling crawling.
 * Mirrors C# CentipedeGraphics.RotatAtChunk() + bodyRotations for surface alignment.
 */
public class CentipedeBodyRenderer extends GeoEntityRenderer<CentipedeBodyEntity> {

    public CentipedeBodyRenderer(EntityRendererFactory.Context context) {
        super(context, new CentipedeBodyModel());
        this.shadowRadius = 0.25f;
        this.withScale(0.5f);
    }

    @Override
    public Color getRenderColor(CentipedeBodyEntity animatable, float partialTick, int packedLight) {
        CentipedeController parent = animatable.getParentCentipede();
        if (parent != null) {
            return Color.ofOpaque(parent.getShellColorRGB());
        }
        return Color.WHITE;
    }

    @Override
    public void render(CentipedeBodyEntity entity, float yaw, float tickDelta,
                       MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.translate(0f, 0.25f, 0f);
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);

        // Render legs after the GeckoLib model
        matrices.push();
        CentipedeLegRenderer.renderLegs(entity, matrices, vertexConsumers, light, tickDelta);
        matrices.pop();

        // Render wings for Centiwing parents
        CentipedeController parent = entity.getParentCentipede();
        if (parent != null && parent.hasWings()) {
            matrices.push();
            CentiwingWingRenderer.renderWings(entity, matrices, vertexConsumers, light, tickDelta);
            matrices.pop();
        }
    }

    @Override
    protected void applyRotations(CentipedeBodyEntity entity, MatrixStack poseStack,
                                   float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
        // Compute chain direction for this body segment (average of neighbors)
        Vec3d dir = getChainDirection(entity, partialTick);

        // Compute the interpolated surface normal for roll
        float snX = MathHelper.lerp(partialTick, entity.prevSurfaceNormalX, entity.surfaceNormalX);
        float snY = MathHelper.lerp(partialTick, entity.prevSurfaceNormalY, entity.surfaceNormalY);
        float snZ = MathHelper.lerp(partialTick, entity.prevSurfaceNormalZ, entity.surfaceNormalZ);
        Vector3f surfaceUp = new Vector3f(snX, snY, snZ);
        if (surfaceUp.lengthSquared() < 0.001f) surfaceUp.set(0, 1, 0);
        surfaceUp.normalize();

        // Build a full orientation from chain forward direction + surface normal (up)
        // Forward = chain direction (normalized)
        Vector3f forward = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        if (forward.lengthSquared() < 0.001f) forward.set(0, 0, 1);
        forward.normalize();

        // Orthogonalize: right = forward x surfaceUp, then recompute up = right x forward
        Vector3f right = new Vector3f();
        forward.cross(surfaceUp, right);
        if (right.lengthSquared() < 0.001f) {
            // forward and surfaceUp are parallel; pick an arbitrary perpendicular
            Vector3f arbitrary = Math.abs(forward.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
            forward.cross(arbitrary, right);
        }
        right.normalize();

        Vector3f up = new Vector3f();
        right.cross(forward, up);
        up.normalize();

        // Build rotation quaternion from the orthonormal basis [right, up, -forward]
        // GeckoLib convention: model faces -Z, so we use -forward
        Quaternionf rotation = quatFromAxes(right, up, new Vector3f(-forward.x, -forward.y, -forward.z));
        poseStack.multiply(rotation);

        // Scale body segments per C# body chunk radius profile, using the parent's
        // size-dependent formula via the CentipedeController interface
        CentipedeController parentCtrl = entity.getParentCentipede();
        if (parentCtrl != null) {
            float radius = parentCtrl.computeSegmentRadius(entity.getSegmentIndex());
            float scaleFactor = radius / parentCtrl.getMaxRadius();
            poseStack.scale(scaleFactor, scaleFactor, scaleFactor);
        }
    }

    /**
     * Build a quaternion from three orthonormal axes (right=X, up=Y, forward=Z).
     */
    private Quaternionf quatFromAxes(Vector3f right, Vector3f up, Vector3f forward) {
        // Rotation matrix to quaternion conversion
        float m00 = right.x, m01 = up.x, m02 = forward.x;
        float m10 = right.y, m11 = up.y, m12 = forward.y;
        float m20 = right.z, m21 = up.z, m22 = forward.z;

        float trace = m00 + m11 + m22;
        float w, x, y, z;
        if (trace > 0) {
            float s = (float) Math.sqrt(trace + 1.0f) * 2f;
            w = 0.25f * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1.0f + m00 - m11 - m22) * 2f;
            w = (m21 - m12) / s;
            x = 0.25f * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1.0f + m11 - m00 - m22) * 2f;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25f * s;
            z = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1.0f + m22 - m00 - m11) * 2f;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25f * s;
        }
        return new Quaternionf(x, y, z, w).normalize();
    }

    /**
     * Get the direction this body segment faces — average of the direction from the
     * previous segment to this one, and from this one to the next segment.
     */
    private Vec3d getChainDirection(CentipedeBodyEntity entity, float tickDelta) {
        CentipedeController parent = entity.getParentCentipede();
        if (parent == null) return new Vec3d(0, 0, 1);

        CentipedeSegmentEntity[] segs = parent.getSegments();
        if (segs == null) return new Vec3d(0, 0, 1);

        int idx = entity.getSegmentIndex();
        if (idx < 0 || idx >= segs.length) return new Vec3d(0, 0, 1);
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
        return Identifier.of("karma-gate-mod", "textures/entity/centipede.png");
    }
}
