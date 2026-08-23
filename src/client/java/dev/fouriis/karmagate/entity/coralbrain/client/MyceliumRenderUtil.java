package dev.fouriis.karmagate.entity.coralbrain.client;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Shared renderer for Rain World style mycelium:
 * - camera-facing thin ribbon
 * - optional Catmull-Rom smoothing (subdivides each segment)
 * - taper that never hits 0 at base (prevents invisibility)
 * - optional 2-pass (soft halo + core)
 * - RW-like color: base red -> pale body -> BRIGHT BLUE TIP (only last few %)
 */
public final class MyceliumRenderUtil {

    private MyceliumRenderUtil() {}

    /**
     * Direct 3D equivalent of {@code CoralBrain.Mycelium.DrawSprites} and
     * {@code UpdateColor}: a constant half-pixel ribbon, owner color grading
     * toward the dark teal neural color, with only the final vertices blue.
     */
    public static void renderRainWorldMycelium(VertexConsumer vc, Matrix4f matrix,
                                                Vec3d[] points, Vec3d camera,
                                                int rootR, int rootG, int rootB,
                                                int alpha, int light) {
        if (points == null || points.length < 2) return;
        final float halfWidth = 0.5f / 20.0f;
        final int neuralR = 26, neuralG = 77, neuralB = 72;
        Vec3d previousSide = new Vec3d(1, 0, 0);

        for (int i = 0; i < points.length - 1; i++) {
            Vec3d a = points[i], b = points[i + 1];
            if (a == null || b == null) continue;
            Vec3d tangent = b.subtract(a);
            if (tangent.lengthSquared() < 1.0e-10) continue;
            tangent = tangent.normalize();
            Vec3d view = camera.subtract(a.add(b).multiply(0.5));
            if (view.lengthSquared() < 1.0e-10) view = new Vec3d(0, 0, 1);
            else view = view.normalize();
            Vec3d side = tangent.crossProduct(view);
            if (side.lengthSquared() < 1.0e-10) side = previousSide;
            else side = side.normalize();
            if (side.dotProduct(previousSide) < 0) side = side.negate();
            previousSide = side;

            float u0 = i / (float) (points.length - 1);
            float u1 = (i + 1) / (float) (points.length - 1);
            int r0 = lerpi(rootR, neuralR, u0);
            int g0 = lerpi(rootG, neuralG, u0);
            int b0 = lerpi(rootB, neuralB, u0);
            int r1 = lerpi(rootR, neuralR, u1);
            int g1 = lerpi(rootG, neuralG, u1);
            int b1 = lerpi(rootB, neuralB, u1);

            float tipStart = Math.max(0.82f, 1.0f - 1.5f / (points.length - 1));
            float tip0 = smoothstep(tipStart, 1.0f, u0);
            float tip1 = smoothstep(tipStart, 1.0f, u1);
            r0 = lerpi(r0, 0, tip0); g0 = lerpi(g0, 0, tip0); b0 = lerpi(b0, 255, tip0);
            r1 = lerpi(r1, 0, tip1); g1 = lerpi(g1, 0, tip1); b1 = lerpi(b1, 255, tip1);

            emitRibbonQuadDoubleSidedGradient(vc, matrix, a, b, side,
                    halfWidth, halfWidth,
                    r0, g0, b0, alpha, r1, g1, b1, alpha, light);
        }
    }

    /**
     * Render one strand as a smooth hair ribbon with RW-ish coloring:
     * - base: red (attachment)
     * - body: pale/greyish
     * - tip: very bright blue ONLY at the extreme end
     *
     * @param midPos  where base->mid finishes (0..1). After that it's mostly mid until the tip ramp.
     * @param tipLen  how much of the end is "tip ramp" (0..1). Typical 0.06..0.12.
     * @param tipPow  sharpness of the tip ramp. Higher = bluer only at the very end. Typical 3..7.
     */
    public static void renderHairRibbonGradient(VertexConsumer vc, Matrix4f mat,
                                                Vec3d[] ptsLocal, Vec3d camLocal,
                                                float baseWidth,
                                                int baseR, int baseG, int baseB,
                                                int midR, int midG, int midB,
                                                int tipR, int tipG, int tipB,
                                                float midPos,
                                                float tipLen,
                                                float tipPow,
                                                int baseAlpha,
                                                int light,
                                                boolean twoPass,
                                                int smoothSteps,
                                                long stableSeed) {
        if (ptsLocal == null || ptsLocal.length < 2) return;

        // stable per-strand width variance
        float widthJitter = 1.0f;
        if (stableSeed != 0) {
            Random rng = new Random(stableSeed);
            widthJitter = 0.85f + rng.nextFloat() * 0.35f; // 0.85..1.20
        }
        baseWidth *= widthJitter;

        // total arclength (for taper + gradient)
        double totalLen = 0.0;
        for (int i = 0; i < ptsLocal.length - 1; i++) {
            Vec3d A = ptsLocal[i], Bp = ptsLocal[i + 1];
            if (A == null || Bp == null) continue;
            totalLen += Bp.subtract(A).length();
        }
        if (totalLen < 1e-8) return;

        Vec3d sidePrev = new Vec3d(1, 0, 0);
        double s = 0.0;

        if (smoothSteps > 0 && ptsLocal.length >= 4) {
            for (int i = 0; i < ptsLocal.length - 1; i++) {
                Vec3d p0 = ptsLocal[Math.max(0, i - 1)];
                Vec3d p1 = ptsLocal[i];
                Vec3d p2 = ptsLocal[i + 1];
                Vec3d p3 = ptsLocal[Math.min(ptsLocal.length - 1, i + 2)];
                if (p1 == null || p2 == null) continue;

                Vec3d prev = p1;
                for (int step = 1; step <= smoothSteps; step++) {
                    double t = (double) step / (double) smoothSteps;
                    Vec3d cur = catmullRom(p0, p1, p2, p3, t);

                    double segLen = cur.subtract(prev).length();
                    if (segLen > 1e-8) {
                        sidePrev = drawSegmentGradientTipCap(
                                vc, mat, prev, cur, camLocal,
                                baseWidth, s, s + segLen, totalLen,
                                baseR, baseG, baseB,
                                midR, midG, midB,
                                tipR, tipG, tipB,
                                midPos, tipLen, tipPow,
                                baseAlpha, light, twoPass,
                                sidePrev
                        );
                        s += segLen;
                    }
                    prev = cur;
                }
            }
        } else {
            for (int i = 0; i < ptsLocal.length - 1; i++) {
                Vec3d A = ptsLocal[i];
                Vec3d Bp = ptsLocal[i + 1];
                if (A == null || Bp == null) continue;

                double segLen = Bp.subtract(A).length();
                if (segLen < 1e-8) continue;

                sidePrev = drawSegmentGradientTipCap(
                        vc, mat, A, Bp, camLocal,
                        baseWidth, s, s + segLen, totalLen,
                        baseR, baseG, baseB,
                        midR, midG, midB,
                        tipR, tipG, tipB,
                        midPos, tipLen, tipPow,
                        baseAlpha, light, twoPass,
                        sidePrev
                );

                s += segLen;
            }
        }
    }

    private static Vec3d drawSegmentGradientTipCap(VertexConsumer vc, Matrix4f mat,
                                               Vec3d A, Vec3d B, Vec3d camLocal,
                                               float baseWidth,
                                               double s0, double s1, double totalLen,
                                               int baseR, int baseG, int baseB,
                                               int midR, int midG, int midB,
                                               int tipR, int tipG, int tipB,
                                               float midPos,
                                               float tipLen,
                                               float tipPow,
                                               int baseAlpha,
                                               int light,
                                               boolean twoPass,
                                               Vec3d sidePrev) {

    Vec3d seg = B.subtract(A);
    double segLen = seg.length();
    if (segLen < 1e-8) return sidePrev;

    Vec3d t = seg.multiply(1.0 / segLen);

    // camera-facing side
    Vec3d mid = A.add(B).multiply(0.5);
    Vec3d view = camLocal.subtract(mid);
    double vLen = view.length();
    if (vLen > 1e-8) view = view.multiply(1.0 / vLen);
    else view = new Vec3d(0, 0, 1);

    Vec3d side = t.crossProduct(view);
    double sideLen = side.length();

    if (sideLen < 1e-6) {
        Vec3d up = new Vec3d(0, 1, 0);
        if (Math.abs(t.dotProduct(up)) > 0.98) up = new Vec3d(1, 0, 0);
        side = t.crossProduct(up);
        sideLen = side.length();
        if (sideLen < 1e-6) return sidePrev;
        side = side.multiply(1.0 / sideLen);
    } else {
        side = side.multiply(1.0 / sideLen);
        if (side.dotProduct(sidePrev) < 0.0) side = side.multiply(-1.0);
    }

    float u0 = (float) (s0 / totalLen);
    float u1 = (float) (s1 / totalLen);

    // taper factor (base never 0, tip fades)
    float f0 = hairFade(u0);
    float f1 = hairFade(u1);

    // ===== TIP WIDTH BOOST (this is the fix) =====
    // Make the very end *thicker*, matching RW’s chunky blue tip.
    // Uses the same tipMask region as the blue color cap.
    float tipMask0 = tipMask(u0, tipLen, tipPow);
    float tipMask1 = tipMask(u1, tipLen, tipPow);

    // How much thicker the tip gets:
    // 1.0 = no change; 2.0 = twice as thick at the very tip.
    final float TIP_WIDTH_MULT = 2.4f;

    // Ease-in so it grows only near the end.
    float tipW0 = 1.0f + (TIP_WIDTH_MULT - 1.0f) * tipMask0;
    float tipW1 = 1.0f + (TIP_WIDTH_MULT - 1.0f) * tipMask1;

    float w0 = baseWidth * f0 * tipW0;
    float w1 = baseWidth * f1 * tipW1;

    // alpha: keep it visible, but don't explode thickness by making it too opaque
    int a0 = clampi((int) (baseAlpha * f0), 0, 255);
    int a1 = clampi((int) (baseAlpha * f1), 0, 255);

    // Color: base->mid, then hold Mid, then sharp Mid->Tip only at end.
    int[] c0 = gradientBaseMidTipCap(u0,
            baseR, baseG, baseB,
            midR, midG, midB,
            tipR, tipG, tipB,
            midPos, tipLen, tipPow);

    int[] c1 = gradientBaseMidTipCap(u1,
            baseR, baseG, baseB,
            midR, midG, midB,
            tipR, tipG, tipB,
            midPos, tipLen, tipPow);

    if (twoPass) {
        // Soft halo, slightly stronger near tip
        int haloA0 = clampi((int) (a0 * (0.18f + 0.22f * tipMask0)), 0, 255);
        int haloA1 = clampi((int) (a1 * (0.18f + 0.22f * tipMask1)), 0, 255);

        emitRibbonQuadDoubleSidedGradient(vc, mat, A, B, side,
                w0 * 1.55f, w1 * 1.55f,
                c0[0], c0[1], c0[2], haloA0,
                c1[0], c1[1], c1[2], haloA1,
                light);

        // Tip pop: blue only, and now ALSO uses the thickened widths (so it looks chunky)
        int tipPopA0 = clampi((int) (a0 * 0.90f * tipMask0), 0, 255);
        int tipPopA1 = clampi((int) (a1 * 0.90f * tipMask1), 0, 255);

        if (tipPopA0 > 0 || tipPopA1 > 0) {
            emitRibbonQuadDoubleSidedGradient(vc, mat, A, B, side,
                    w0 * 1.10f, w1 * 1.10f,
                    tipR, tipG, tipB, tipPopA0,
                    tipR, tipG, tipB, tipPopA1,
                    light);
        }
    }

    // Core pass
    emitRibbonQuadDoubleSidedGradient(vc, mat, A, B, side,
            w0, w1,
            c0[0], c0[1], c0[2], a0,
            c1[0], c1[1], c1[2], a1,
            light);

    return side;
}


    /**
     * Base never reaches 0.
     */
    private static float hairFade(float x) {
        x = clampf(x, 0f, 1f);

        float baseFloor = 0.22f;
        float baseIn = smoothstep(0.02f, 0.10f, x);
        float tipOut = 1f - smoothstep(0.72f, 1.00f, x);
        float body = (float) Math.pow(1f - x, 0.60);

        float shaped = baseIn * tipOut * body;
        return baseFloor + (1f - baseFloor) * shaped;
    }

    /**
     * Base->Mid until midPos, then hold Mid, then sharp Mid->Tip only in last tipLen.
     */
    private static int[] gradientBaseMidTipCap(float u,
                                               int br, int bg, int bb,
                                               int mr, int mg, int mb,
                                               int tr, int tg, int tb,
                                               float midPos,
                                               float tipLen,
                                               float tipPow) {
        u = clampf(u, 0f, 1f);
        midPos = clampf(midPos, 0.02f, 0.95f);
        tipLen = clampf(tipLen, 0.01f, 0.35f);

        // Stage 1: base -> mid
        if (u <= midPos) {
            float t = u / midPos;
            // slight ease so red stays stronger near the base
            t = t * t;
            return new int[]{
                    lerpi(br, mr, t),
                    lerpi(bg, mg, t),
                    lerpi(bb, mb, t)
            };
        }

        // Stage 2 + 3: hold mid, then tip-cap ramp
        float mask = tipMask(u, tipLen, tipPow);
        return new int[]{
                lerpi(mr, tr, mask),
                lerpi(mg, tg, mask),
                lerpi(mb, tb, mask)
        };
    }

    /**
     * Returns 0 for most of the strand, rises to 1 only in the last tipLen fraction.
     * tipPow controls sharpness (bigger = more concentrated at the very end).
     */
    private static float tipMask(float u, float tipLen, float tipPow) {
        float start = 1f - tipLen;
        if (u <= start) return 0f;
        float t = (u - start) / Math.max(1e-6f, tipLen);
        t = clampf(t, 0f, 1f);
        // sharpen
        t = (float) Math.pow(t, tipPow);
        // smooth the transition a bit
        return smoothstep(0f, 1f, t);
    }

    private static int lerpi(int a, int b, float t) {
        t = clampf(t, 0f, 1f);
        return clampi((int) (a + (b - a) * t), 0, 255);
    }

    private static void emitRibbonQuadDoubleSidedGradient(VertexConsumer vc, Matrix4f mat,
                                                         Vec3d p0, Vec3d p1,
                                                         Vec3d sideUnit,
                                                         float w0, float w1,
                                                         int r0, int g0, int b0, int a0,
                                                         int r1, int g1, int b1, int a1,
                                                         int light) {
        if (w0 <= 1e-6f && w1 <= 1e-6f) return;

        float p0x = (float) p0.x, p0y = (float) p0.y, p0z = (float) p0.z;
        float p1x = (float) p1.x, p1y = (float) p1.y, p1z = (float) p1.z;

        float sx = (float) sideUnit.x, sy = (float) sideUnit.y, sz = (float) sideUnit.z;

        float aLx = p0x - sx * w0, aLy = p0y - sy * w0, aLz = p0z - sz * w0;
        float aRx = p0x + sx * w0, aRy = p0y + sy * w0, aRz = p0z + sz * w0;

        float bRx = p1x + sx * w1, bRy = p1y + sy * w1, bRz = p1z + sz * w1;
        float bLx = p1x - sx * w1, bLy = p1y - sy * w1, bLz = p1z - sz * w1;

        // Front
        v(vc, mat, aLx, aLy, aLz, r0, g0, b0, a0, light, 0f, 0f);
        v(vc, mat, aRx, aRy, aRz, r0, g0, b0, a0, light, 1f, 0f);
        v(vc, mat, bRx, bRy, bRz, r1, g1, b1, a1, light, 1f, 1f);

        v(vc, mat, aLx, aLy, aLz, r0, g0, b0, a0, light, 0f, 0f);
        v(vc, mat, bRx, bRy, bRz, r1, g1, b1, a1, light, 1f, 1f);
        v(vc, mat, bLx, bLy, bLz, r1, g1, b1, a1, light, 0f, 1f);

        // Back
        v(vc, mat, aLx, aLy, aLz, r0, g0, b0, a0, light, 0f, 0f);
        v(vc, mat, bRx, bRy, bRz, r1, g1, b1, a1, light, 1f, 1f);
        v(vc, mat, aRx, aRy, aRz, r0, g0, b0, a0, light, 1f, 0f);

        v(vc, mat, aLx, aLy, aLz, r0, g0, b0, a0, light, 0f, 0f);
        v(vc, mat, bLx, bLy, bLz, r1, g1, b1, a1, light, 0f, 1f);
        v(vc, mat, bRx, bRy, bRz, r1, g1, b1, a1, light, 1f, 1f);
    }

    private static void v(VertexConsumer vc, Matrix4f mat,
                          float x, float y, float z,
                          int r, int g, int b, int a,
                          int light,
                          float u, float v) {
        vc.vertex(mat, x, y, z)
                .color(r, g, b, a)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(0f, 1f, 0f);
    }

    // Catmull-Rom spline (uniform)
    private static Vec3d catmullRom(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;

        Vec3d a = p1.multiply(2.0);
        Vec3d b = p2.subtract(p0).multiply(t);
        Vec3d c = p0.multiply(2.0).subtract(p1.multiply(5.0)).add(p2.multiply(4.0)).subtract(p3).multiply(t2);
        Vec3d d = p3.add(p1.multiply(3.0)).subtract(p2.multiply(3.0)).subtract(p0).multiply(t3);
        return a.add(b).add(c).add(d).multiply(0.5);
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clampf((x - e0) / Math.max(1e-6f, (e1 - e0)), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clampf(float x, float lo, float hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static int clampi(int x, int lo, int hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
