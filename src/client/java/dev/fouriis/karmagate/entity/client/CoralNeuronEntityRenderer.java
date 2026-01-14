package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.CoralNeuronEntity;
import dev.fouriis.karmagate.Mycelium;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Stem: twisted X-strip quads (your existing).
 * Mycelia: smooth hair-ribbons via MyceliumRenderUtil.
 */
public class CoralNeuronEntityRenderer extends EntityRenderer<CoralNeuronEntity> {

    private static final Identifier WHITE_TEX = Identifier.of("minecraft", "textures/misc/white.png");

    public CoralNeuronEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0f;
    }

    @Override
    public void render(CoralNeuronEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {

        Vec3d[] pts = entity.getPointsLocalCopy();
        if (pts == null || pts.length < 2) return;

        int firstIdx = -1;
        for (int j = 0; j < pts.length - 1; j++) {
            if (pts[j] == null || pts[j + 1] == null) continue;
            if (pts[j + 1].subtract(pts[j]).length() > 1e-6) { firstIdx = j; break; }
        }
        if (firstIdx == -1) return;

        Matrix4f mat = matrices.peek().getPositionMatrix();

        // ---------------------------
        // 1) Stem (unchanged)
        // ---------------------------
        VertexConsumer vcStem = vertexConsumers.getBuffer(RenderLayer.getLightning());

        float baseWidth = 0.1f;
        int r = 255, g = 60, blue = 80;
        int a = 255;

        final float TWIST_PER_BLOCK = (float) (Math.PI);
        final float TWIST_NOISE_AMP = 0.0f;

        Vec3d tPrev = pts[firstIdx + 1].subtract(pts[firstIdx]);
        double tPrevLen = tPrev.length();
        if (tPrevLen < 1e-6) return;
        tPrev = tPrev.multiply(1.0 / tPrevLen);

        Vec3d up = new Vec3d(0, 1, 0);
        if (Math.abs(tPrev.dotProduct(up)) > 0.98) up = new Vec3d(1, 0, 0);

        Vec3d nPrev = tPrev.crossProduct(up);
        double nLen = nPrev.length();
        if (nLen < 1e-6) return;
        nPrev = nPrev.multiply(1.0 / nLen);

        Vec3d bPrev = tPrev.crossProduct(nPrev);
        double bLen = bPrev.length();
        if (bLen < 1e-6) return;
        bPrev = bPrev.multiply(1.0 / bLen);

        double totalLen = 0.0;
        for (int i = 0; i < pts.length - 1; i++) {
            if (pts[i] == null || pts[i + 1] == null) continue;
            totalLen += pts[i + 1].subtract(pts[i]).length();
        }
        totalLen = Math.max(totalLen, 1e-6);

        double s = 0.0;

        for (int i = 0; i < pts.length - 1; i++) {
            Vec3d A = pts[i];
            Vec3d B = pts[i + 1];
            if (A == null || B == null) continue;

            Vec3d seg = B.subtract(A);
            double segLen = seg.length();
            if (segLen < 1e-6) continue;

            Vec3d t = seg.multiply(1.0 / segLen);

            Frame transported = parallelTransportFrame(tPrev, t, nPrev, bPrev);
            Vec3d nBase = transported.n;
            Vec3d bBase2 = transported.b;

            double s0 = s;
            double s1 = s + segLen;

            float twist0 = (float) (s0 * TWIST_PER_BLOCK) + twistNoise(entity, s0) * TWIST_NOISE_AMP;
            float twist1 = (float) (s1 * TWIST_PER_BLOCK) + twistNoise(entity, s1) * TWIST_NOISE_AMP;

            Vec3d s1_0 = rotateAroundAxis(nBase, t, twist0);
            Vec3d s1_1 = rotateAroundAxis(nBase, t, twist1);

            Vec3d s2_0 = rotateAroundAxis(bBase2, t, twist0);
            Vec3d s2_1 = rotateAroundAxis(bBase2, t, twist1);

            float w0 = baseWidth * taper01((float) (s0 / totalLen));
            float w1 = baseWidth * taper01((float) (s1 / totalLen));

            emitTwistedQuadDoubleSided(vcStem, mat, A, B, s1_0, s1_1, w0, w1, r, g, blue, a);
            emitTwistedQuadDoubleSided(vcStem, mat, A, B, s2_0, s2_1, w0, w1, r, g, blue, a);

            s += segLen;
            tPrev = t;
            nPrev = nBase;
            bPrev = bBase2;
        }

        // ---------------------------
        // 2) Mycelia (fixed)
        // ---------------------------
        Vec3d camWorld = this.dispatcher.camera.getPos();
        Vec3d camLocal = camWorld.subtract(entity.getPos());

        // Use a non-translucent (solid) render layer so mycelia render opaque
        VertexConsumer vcMyc = vertexConsumers.getBuffer(RenderLayer.getEntitySolid(WHITE_TEX));

        // Mycelia: red base -> pale -> bright blue tip
        // Mycelia: RW look — red base, pale body, BLUE ONLY ON EXTREME TIP
        float hairWidth = 0.016f;
        // Fully opaque now that we use a solid render layer
        int hairAlpha = 255;

        int baseR = 255, baseG = 60, baseB = 80;
        int midR  = 220, midG  = 230, midB  = 245;
        int tipR  = 60,  tipG  = 120, tipB  = 255;

        float midPos = 0.55f;   // base->mid ends around here
        float tipLen = 0.08f;   // last 8% becomes blue
        float tipPow = 5.0f;    // sharpness: higher = bluer only at end

        List<Mycelium> strands = entity.getMycelia();
        if (strands != null && !strands.isEmpty()) {
            for (Mycelium m : strands) {
                if (m == null) continue;
                Vec3d[] mPts = m.samplePoints(tickDelta);

                long seed = ((long) entity.getId() * 1315423911L) + (long) m.index * 2654435761L;

                int glowLight = LightmapTextureManager.MAX_LIGHT_COORDINATE;


MyceliumRenderUtil.renderHairRibbonGradient(
        vcMyc, mat,
        mPts, camLocal,
        hairWidth,
        baseR, baseG, baseB,
        midR, midG, midB,
        tipR, tipG, tipB,
        midPos,
        tipLen,
        tipPow,
        hairAlpha,
        glowLight,  // <-- KEY CHANGE (was: light)
        true,
        3,
        seed
);
            }
        }

        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    public Identifier getTexture(CoralNeuronEntity entity) {
        return WHITE_TEX;
    }

    @Override
    public boolean shouldRender(CoralNeuronEntity entity, Frustum frustum, double x, double y, double z) {
        // Disable frustum culling for CoralNeuron entities so long stems and mycelia
        // are rendered even if partially or fully outside the camera frustum.
        return true;
    }

    // ---------------------------
    // Stem helpers (unchanged)
    // ---------------------------

    private static float taper01(float x) {
        x = clampf(x, 0f, 1f);
        float tip = 0.08f;
        float a = smoothstep(0f, tip, x);
        float b = 1f - smoothstep(1f - tip, 1f, x);
        return a * b;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clampf((x - e0) / Math.max(1e-6f, (e1 - e0)), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clampf(float x, float lo, float hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static float twistNoise(CoralNeuronEntity e, double s) {
        float seed = e.getId() * 0.173f;
        return (float) Math.sin(s * 0.7 + seed);
    }

    private static void emitTwistedQuadDoubleSided(VertexConsumer vc, Matrix4f mat,
                                                  Vec3d p0, Vec3d p1,
                                                  Vec3d s0, Vec3d s1,
                                                  float w0, float w1,
                                                  int r, int g, int b, int a) {

        if (w0 <= 1e-6f && w1 <= 1e-6f) return;

        float p0x = (float) p0.x, p0y = (float) p0.y, p0z = (float) p0.z;
        float p1x = (float) p1.x, p1y = (float) p1.y, p1z = (float) p1.z;

        float s0x = (float) s0.x, s0y = (float) s0.y, s0z = (float) s0.z;
        float s1x = (float) s1.x, s1y = (float) s1.y, s1z = (float) s1.z;

        float a0x = p0x - s0x * w0;
        float a0y = p0y - s0y * w0;
        float a0z = p0z - s0z * w0;

        float a1x = p0x + s0x * w0;
        float a1y = p0y + s0y * w0;
        float a1z = p0z + s0z * w0;

        float b1x = p1x + s1x * w1;
        float b1y = p1y + s1y * w1;
        float b1z = p1z + s1z * w1;

        float b0x = p1x - s1x * w1;
        float b0y = p1y - s1y * w1;
        float b0z = p1z - s1z * w1;

        v(vc, mat, a0x, a0y, a0z, r, g, b, a);
        v(vc, mat, a1x, a1y, a1z, r, g, b, a);
        v(vc, mat, b1x, b1y, b1z, r, g, b, a);

        v(vc, mat, a0x, a0y, a0z, r, g, b, a);
        v(vc, mat, b1x, b1y, b1z, r, g, b, a);
        v(vc, mat, b0x, b0y, b0z, r, g, b, a);

        v(vc, mat, a0x, a0y, a0z, r, g, b, a);
        v(vc, mat, b1x, b1y, b1z, r, g, b, a);
        v(vc, mat, a1x, a1y, a1z, r, g, b, a);

        v(vc, mat, a0x, a0y, a0z, r, g, b, a);
        v(vc, mat, b0x, b0y, b0z, r, g, b, a);
        v(vc, mat, b1x, b1y, b1z, r, g, b, a);
    }

    private static void v(VertexConsumer vc, Matrix4f mat,
                          float x, float y, float z,
                          int r, int g, int b, int a) {
        vc.vertex(mat, x, y, z).color(r, g, b, a);
    }

    // ---------------------------
    // Frame math
    // ---------------------------

    private record Frame(Vec3d n, Vec3d b) {}

    private static Frame parallelTransportFrame(Vec3d tPrev, Vec3d t, Vec3d nPrev, Vec3d bPrev) {
        double dot = clamp(tPrev.dotProduct(t), -1.0, 1.0);
        if (dot > 0.9999) return new Frame(nPrev, bPrev);

        Vec3d axis = tPrev.crossProduct(t);
        double axisLen = axis.length();

        if (axisLen < 1e-9) {
            Vec3d up = new Vec3d(0, 1, 0);
            if (Math.abs(t.dotProduct(up)) > 0.98) up = new Vec3d(1, 0, 0);

            Vec3d n = t.crossProduct(up);
            double nLen = n.length();
            if (nLen < 1e-9) n = new Vec3d(1, 0, 0);
            else n = n.multiply(1.0 / nLen);

            Vec3d b = t.crossProduct(n);
            double bLen = b.length();
            if (bLen < 1e-9) b = new Vec3d(0, 0, 1);
            else b = b.multiply(1.0 / bLen);

            return new Frame(n, b);
        }

        axis = axis.multiply(1.0 / axisLen);
        double angle = Math.acos(dot);

        Vec3d n = rotateAroundAxis(nPrev, axis, angle);
        Vec3d b = rotateAroundAxis(bPrev, axis, angle);

        n = n.subtract(t.multiply(n.dotProduct(t)));
        double nLen2 = n.length();
        if (nLen2 > 1e-9) n = n.multiply(1.0 / nLen2);

        b = t.crossProduct(n);
        double bLen2 = b.length();
        if (bLen2 > 1e-9) b = b.multiply(1.0 / bLen2);

        return new Frame(n, b);
    }

    private static Vec3d rotateAroundAxis(Vec3d v, Vec3d axisUnit, double angle) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);

        Vec3d term1 = v.multiply(c);
        Vec3d term2 = axisUnit.crossProduct(v).multiply(s);
        Vec3d term3 = axisUnit.multiply(axisUnit.dotProduct(v) * (1.0 - c));
        return term1.add(term2).add(term3);
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
