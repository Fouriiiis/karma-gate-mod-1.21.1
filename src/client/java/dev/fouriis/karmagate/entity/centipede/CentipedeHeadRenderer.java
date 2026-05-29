package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/**
 * Renderer for centipede head segments.
 * Uses applyRotations() to orient the head model along the chain direction,
 * including full pitch/yaw/roll for wall and ceiling crawling.
 * Mirrors C# CentipedeGraphics.RotatAtChunk() + bodyRotations for surface alignment.
 */
public class CentipedeHeadRenderer extends GeoEntityRenderer<CentipedeHeadEntity> {

    public CentipedeHeadRenderer(EntityRendererFactory.Context context) {
        super(context, new CentipedeHeadModel());
        this.shadowRadius = 0.3f;
        this.withScale(0.5f);
    }

    @Override
    public Color getRenderColor(CentipedeHeadEntity animatable, float partialTick, int packedLight) {
        return Color.ofOpaque(CentipedeRenderColorHelper.getRenderColor(animatable, partialTick));
    }

    @Override
    public void render(CentipedeHeadEntity entity, float yaw, float tickDelta,
                       MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.translate(0f, 0.25f, 0f);
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
        // getChainDirection already returns the outward-facing direction for each head:
        //   front head (idx 0):   seg[0] - seg[1]   → points forward
        //   rear head  (idx N-1): seg[N-1] - seg[N-2] → points backward
        // Both point AWAY from the body, which is correct for both head models.
        Vec3d dir = getChainDirection(entity, partialTick);

        // Compute the interpolated surface normal for roll, averaged with neighbors
        Vector3f surfaceUp = getSmoothedSurfaceNormal(entity, partialTick);

        // Build a full orientation from chain forward direction + surface normal (up)
        Vector3f forward = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        if (forward.lengthSquared() < 0.001f) forward.set(0, 0, 1);
        forward.normalize();

        // Orthogonalize: right = forward x surfaceUp, then recompute up = right x forward
        Vector3f right = new Vector3f();
        forward.cross(surfaceUp, right);
        if (right.lengthSquared() < 0.001f) {
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

        // Scale heads for small centipede variants (getHeadScaleFactor() defaults to 1.0)
        CentipedeController parentCtrl = entity.getParentCentipede();
        if (parentCtrl != null) {
            float hs = parentCtrl.getHeadScaleFactor();
            if (hs != 1.0f) poseStack.scale(hs, hs, hs);
        }
    }

    /**
     * Average this head's interpolated surface normal with its adjacent segment for smooth roll.
     */
    private Vector3f getSmoothedSurfaceNormal(CentipedeHeadEntity entity, float partialTick) {
        float snX = MathHelper.lerp(partialTick, entity.prevSurfaceNormalX, entity.surfaceNormalX);
        float snY = MathHelper.lerp(partialTick, entity.prevSurfaceNormalY, entity.surfaceNormalY);
        float snZ = MathHelper.lerp(partialTick, entity.prevSurfaceNormalZ, entity.surfaceNormalZ);

        CentipedeController parent = entity.getParentCentipede();
        CentipedeSegmentEntity[] segs = parent != null ? parent.getSegments() : null;
        int idx = entity.getSegmentIndex();

        if (segs != null && idx >= 0 && idx < segs.length) {
            // Heads only have one neighbor (the adjacent body segment)
            int neighborIdx = (idx == 0) ? 1 : idx - 1;
            if (neighborIdx >= 0 && neighborIdx < segs.length && segs[neighborIdx] != null) {
                snX += MathHelper.lerp(partialTick, segs[neighborIdx].prevSurfaceNormalX, segs[neighborIdx].surfaceNormalX);
                snY += MathHelper.lerp(partialTick, segs[neighborIdx].prevSurfaceNormalY, segs[neighborIdx].surfaceNormalY);
                snZ += MathHelper.lerp(partialTick, segs[neighborIdx].prevSurfaceNormalZ, segs[neighborIdx].surfaceNormalZ);
                snX /= 2f;
                snY /= 2f;
                snZ /= 2f;
            }
        }

        Vector3f result = new Vector3f(snX, snY, snZ);
        if (result.lengthSquared() < 0.001f) result.set(0, 1, 0);
        result.normalize();
        return result;
    }

    /**
     * Build a quaternion from three orthonormal axes (right=X, up=Y, forward=Z).
     */
    private Quaternionf quatFromAxes(Vector3f right, Vector3f up, Vector3f forward) {
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
     * Get the direction this head should face (toward the adjacent body segment).
     */
    private Vec3d getChainDirection(CentipedeHeadEntity entity, float tickDelta) {
        CentipedeController parent = entity.getParentCentipede();
        if (parent == null) return new Vec3d(0, 0, 1);

        CentipedeSegmentEntity[] segs = parent.getSegments();
        if (segs == null) return new Vec3d(0, 0, 1);

        int idx = entity.getSegmentIndex();
        if (idx < 0 || idx >= segs.length) return new Vec3d(0, 0, 1);

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
        return Identifier.of("karma-gate-mod", "textures/entity/centipede.png");
    }
}
