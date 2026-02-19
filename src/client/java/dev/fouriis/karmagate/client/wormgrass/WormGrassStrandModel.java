package dev.fouriis.karmagate.client.wormgrass;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

/**
 * Wormgrass strand rendering - creates curved, segmented strands that bend
 * smoothly from base to tip, matching Rain World's visual style.
 *
 * The cap and eye at the tip rotate to align with the strand's tangent direction.
 */
public final class WormGrassStrandModel {

    /**
     * Segment count is a major perf lever because each segment emits 2 quads.
     * 3 keeps the silhouette while cutting vertices ~40% vs 5.
     */
    private static final int SEGMENTS = 3;

    /**
     * Avoid per-strand allocations (this was allocating 4 float arrays per strand).
     * ThreadLocal keeps it render-thread safe.
     */
    private static final ThreadLocal<float[]> TL_SEG_X = ThreadLocal.withInitial(() -> new float[SEGMENTS + 1]);
    private static final ThreadLocal<float[]> TL_SEG_Y = ThreadLocal.withInitial(() -> new float[SEGMENTS + 1]);
    private static final ThreadLocal<float[]> TL_SEG_Z = ThreadLocal.withInitial(() -> new float[SEGMENTS + 1]);
    private static final ThreadLocal<float[]> TL_SEG_W = ThreadLocal.withInitial(() -> new float[SEGMENTS + 1]);

    /**
     * Very cheap far-LOD: a single vertical billboard quad rotated around Y to face the camera.
     * This is intentionally minimal: no cap, no eye.
     */
    public static void emitBillboardY(
            VertexConsumer vc,
            Matrix4f posMat,
            float x, float y, float z,
            float camX, float camZ,
            float halfWidth,
            float height,
            int light,
            float r, float g, float b
    ) {
        // Y-axis billboard (faces camera horizontally)
        float dx = camX - x;
        float dz = camZ - z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4f) {
            dx = 1f;
            dz = 0f;
            len = 1f;
        }
        dx /= len;
        dz /= len;

        // Right vector perpendicular on XZ plane
        float rx = -dz;
        float rz = dx;

        float x0 = x - rx * halfWidth;
        float z0 = z - rz * halfWidth;
        float x1 = x + rx * halfWidth;
        float z1 = z + rz * halfWidth;

        float y0 = y;
        float y1 = y + height;

        float a = 1.0f;
        // Normal roughly faces camera
        float nx = dx;
        float ny = 0f;
        float nz = dz;

        vertex(vc, posMat, x0, y0, z0, r, g, b, a, 0f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, x1, y0, z1, r, g, b, a, 1f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, x1, y1, z1, r, g, b, a, 1f, 0f, light, nx, ny, nz);
        vertex(vc, posMat, x0, y1, z0, r, g, b, a, 0f, 0f, light, nx, ny, nz);
    }

    public static void emitCurvedStrand(
            VertexConsumer vc,
            Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float tipOffsetX, float tipOffsetY, float tipOffsetZ,
            float width,
            float height,
            int light,
            float r, float g, float b,
            float eyeOpenT
    ) {
        float[] segX = TL_SEG_X.get();
        float[] segY = TL_SEG_Y.get();
        float[] segZ = TL_SEG_Z.get();
        float[] segWidth = TL_SEG_W.get();

        // Control point above base (gives natural curve)
        float ctrlX = baseX + tipOffsetX * 0.25f;
        float ctrlY = baseY + height * 0.65f + tipOffsetY * 0.25f;
        float ctrlZ = baseZ + tipOffsetZ * 0.25f;

        float tipX = baseX + tipOffsetX;
        float tipY = baseY + height + tipOffsetY;
        float tipZ = baseZ + tipOffsetZ;

        for (int i = 0; i <= SEGMENTS; i++) {
            float t = i / (float) SEGMENTS;

            float mt = 1f - t;
            float mt2 = mt * mt;
            float t2 = t * t;
            float twoMtT = 2f * mt * t;

            segX[i] = mt2 * baseX + twoMtT * ctrlX + t2 * tipX;
            segY[i] = mt2 * baseY + twoMtT * ctrlY + t2 * tipY;
            segZ[i] = mt2 * baseZ + twoMtT * ctrlZ + t2 * tipZ;

            // Taper, but DO NOT shrink too hard at the very end (prevents needle tips)
            float taperT = smoothstep(t);
            float tipFloor = 0.45f;
            segWidth[i] = width * MathHelper.lerp(taperT, 1.0f, tipFloor);
        }

        // Body segments as connected X-cross quads
        for (int i = 0; i < SEGMENTS; i++) {
            float dirX = segX[i + 1] - segX[i];
            float dirY = segY[i + 1] - segY[i];
            float dirZ = segZ[i + 1] - segZ[i];
            float dirLen = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            if (dirLen > 0.0001f) {
                dirX /= dirLen;
                dirY /= dirLen;
                dirZ /= dirLen;
            } else {
                dirX = 0; dirY = 1; dirZ = 0;
            }

            float perpX1, perpZ1;
            float xzLen = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (xzLen > 0.001f) {
                perpX1 = -dirZ / xzLen;
                perpZ1 = dirX / xzLen;
            } else {
                perpX1 = 1f;
                perpZ1 = 0f;
            }

            float perpX2 = perpZ1;
            float perpZ2 = -perpX1;

            emitSegmentQuad(vc, posMat,
                    segX[i], segY[i], segZ[i], segWidth[i],
                    segX[i + 1], segY[i + 1], segZ[i + 1], segWidth[i + 1],
                    perpX1, perpZ1,
                    light, r, g, b);

            emitSegmentQuad(vc, posMat,
                    segX[i], segY[i], segZ[i], segWidth[i],
                    segX[i + 1], segY[i + 1], segZ[i + 1], segWidth[i + 1],
                    perpX2, perpZ2,
                    light, r, g, b);
        }

        // Tip tangent direction (for placing cap/eye)
        float tipDirX = segX[SEGMENTS] - segX[SEGMENTS - 1];
        float tipDirY = segY[SEGMENTS] - segY[SEGMENTS - 1];
        float tipDirZ = segZ[SEGMENTS] - segZ[SEGMENTS - 1];
        float tipDirLen = (float) Math.sqrt(tipDirX * tipDirX + tipDirY * tipDirY + tipDirZ * tipDirZ);
        if (tipDirLen > 0.0001f) {
            tipDirX /= tipDirLen;
            tipDirY /= tipDirLen;
            tipDirZ /= tipDirLen;
        } else {
            tipDirX = 0; tipDirY = 1; tipDirZ = 0;
        }

        // Build orthonormal basis from tangent direction
        // tangent = tipDir, we need two perpendicular vectors
        float bupX = 0f, bupY = 1f, bupZ = 0f;
        // If tangent is nearly vertical, use a different up vector
        if (Math.abs(tipDirY) > 0.99f) {
            bupX = 1f; bupY = 0f; bupZ = 0f;
        }
        
        // perpA = up cross tangent (normalized)
        float perpAx = bupY * tipDirZ - bupZ * tipDirY;
        float perpAy = bupZ * tipDirX - bupX * tipDirZ;
        float perpAz = bupX * tipDirY - bupY * tipDirX;
        float perpALen = (float) Math.sqrt(perpAx * perpAx + perpAy * perpAy + perpAz * perpAz);
        if (perpALen > 0.0001f) {
            perpAx /= perpALen;
            perpAy /= perpALen;
            perpAz /= perpALen;
        }
        
        // perpB = tangent cross perpA (normalized)
        float perpBx = tipDirY * perpAz - tipDirZ * perpAy;
        float perpBy = tipDirZ * perpAx - tipDirX * perpAz;
        float perpBz = tipDirX * perpAy - tipDirY * perpAx;
        float perpBLen = (float) Math.sqrt(perpBx * perpBx + perpBy * perpBy + perpBz * perpBz);
        if (perpBLen > 0.0001f) {
            perpBx /= perpBLen;
            perpBy /= perpBLen;
            perpBz /= perpBLen;
        }

        // --------------------------------------------------------------------
        // BLUNT CAP - two crossed quads oriented perpendicular to tangent
        // --------------------------------------------------------------------
        float tipW = segWidth[SEGMENTS];
        float capSize = tipW * 0.9f;

        // Move cap slightly forward along tangent
        float capX = segX[SEGMENTS] + tipDirX * (tipW * 0.05f);
        float capY = segY[SEGMENTS] + tipDirY * (tipW * 0.05f);
        float capZ = segZ[SEGMENTS] + tipDirZ * (tipW * 0.05f);

        // Emit cap as two crossed quads perpendicular to tangent
        emitTangentAlignedQuad(vc, posMat, capX, capY, capZ,
                capSize, capSize,
                perpAx, perpAy, perpAz,
                perpBx, perpBy, perpBz,
                light, r, g, b);
        emitTangentAlignedQuad(vc, posMat, capX, capY, capZ,
                capSize, capSize,
                perpBx, perpBy, perpBz,
                -perpAx, -perpAy, -perpAz,
                light, r, g, b);

        // --------------------------------------------------------------------
        // EYE AT THE TIP - oriented perpendicular to tangent
        // --------------------------------------------------------------------
        if (eyeOpenT > 0f) {
            float open = MathHelper.clamp(eyeOpenT, 0f, 1f);

            float eyeSize = tipW * MathHelper.lerp(open, 0.35f, 0.70f);

            // Place eye slightly behind the cap along tangent
            float eyeBack = tipW * 0.08f;
            float eyeX = segX[SEGMENTS] - tipDirX * eyeBack;
            float eyeY = segY[SEGMENTS] - tipDirY * eyeBack;
            float eyeZ = segZ[SEGMENTS] - tipDirZ * eyeBack;

            // Offset to one side so it's visible
            float side = (randSigned(hashFromPos(segX[SEGMENTS], segY[SEGMENTS], segZ[SEGMENTS])) >= 0f) ? 1f : -1f;
            eyeX += perpAx * (tipW * 0.15f) * side;
            eyeY += perpAy * (tipW * 0.15f) * side;
            eyeZ += perpAz * (tipW * 0.15f) * side;

            float eyeR = 0.20f;
            float eyeG = 0.00f;
            float eyeB = 1.00f;

            // Eye as crossed quads perpendicular to tangent
            emitTangentAlignedQuad(vc, posMat, eyeX, eyeY, eyeZ,
                    eyeSize, eyeSize,
                    perpAx, perpAy, perpAz,
                    perpBx, perpBy, perpBz,
                    light, eyeR, eyeG, eyeB);
            emitTangentAlignedQuad(vc, posMat, eyeX, eyeY, eyeZ,
                    eyeSize, eyeSize,
                    perpBx, perpBy, perpBz,
                    -perpAx, -perpAy, -perpAz,
                    light, eyeR, eyeG, eyeB);
        }
    }

    /**
     * Emit a quad centered at (x,y,z), oriented in the plane defined by two perpendicular vectors.
     * This allows the quad to rotate with the strand's tangent direction.
     */
    private static void emitTangentAlignedQuad(
            VertexConsumer vc, Matrix4f posMat,
            float x, float y, float z,
            float width, float height,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ,
            int light,
            float r, float g, float b
    ) {
        float hw = width * 0.5f;
        float hh = height * 0.5f;

        // Four corners: center +/- right*hw +/- up*hh
        float x0 = x - rightX * hw - upX * hh;
        float y0 = y - rightY * hw - upY * hh;
        float z0 = z - rightZ * hw - upZ * hh;

        float x1 = x + rightX * hw - upX * hh;
        float y1 = y + rightY * hw - upY * hh;
        float z1 = z + rightZ * hw - upZ * hh;

        float x2 = x + rightX * hw + upX * hh;
        float y2 = y + rightY * hw + upY * hh;
        float z2 = z + rightZ * hw + upZ * hh;

        float x3 = x - rightX * hw + upX * hh;
        float y3 = y - rightY * hw + upY * hh;
        float z3 = z - rightZ * hw + upZ * hh;

        // Normal = right cross up
        float nx = rightY * upZ - rightZ * upY;
        float ny = rightZ * upX - rightX * upZ;
        float nz = rightX * upY - rightY * upX;

        float a = 1.0f;

        vertex(vc, posMat, x0, y0, z0, r, g, b, a, 0f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, x1, y1, z1, r, g, b, a, 1f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, x2, y2, z2, r, g, b, a, 1f, 0f, light, nx, ny, nz);
        vertex(vc, posMat, x3, y3, z3, r, g, b, a, 0f, 0f, light, nx, ny, nz);
    }

    private static void emitSegmentQuad(
            VertexConsumer vc, Matrix4f posMat,
            float x0, float y0, float z0, float w0,
            float x1, float y1, float z1, float w1,
            float perpX, float perpZ,
            int light,
            float r, float g, float b
    ) {
        float hw0 = w0 * 0.5f;
        float hw1 = w1 * 0.5f;

        float ax = x0 - perpX * hw0;
        float ay = y0;
        float az = z0 - perpZ * hw0;

        float bx = x0 + perpX * hw0;
        float by = y0;
        float bz = z0 + perpZ * hw0;

        float cx = x1 + perpX * hw1;
        float cy = y1;
        float cz = z1 + perpZ * hw1;

        float dx = x1 - perpX * hw1;
        float dy = y1;
        float dz = z1 - perpZ * hw1;

        float nx = -perpZ;
        float ny = 0f;
        float nz = perpX;

        float a = 1.0f;

        vertex(vc, posMat, ax, ay, az, r, g, b, a, 0f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, bx, by, bz, r, g, b, a, 1f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, cx, cy, cz, r, g, b, a, 1f, 0f, light, nx, ny, nz);
        vertex(vc, posMat, dx, dy, dz, r, g, b, a, 0f, 0f, light, nx, ny, nz);
    }

    private static void vertex(
            VertexConsumer vc,
            Matrix4f posMat,
            float x, float y, float z,
            float r, float g, float b, float a,
            float u, float v,
            int light,
            float nx, float ny, float nz
    ) {
        vc.vertex(posMat, x, y, z)
                .color(r, g, b, a)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);
    }

    private static float smoothstep(float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // Tiny deterministic helper so eye chooses a consistent side per strand
    private static long hashFromPos(float x, float y, float z) {
        long xi = (long)Math.floor(x * 8.0);
        long yi = (long)Math.floor(y * 8.0);
        long zi = (long)Math.floor(z * 8.0);
        long h = xi * 0x9E3779B97F4A7C15L ^ yi * 0xC2B2AE3D27D4EB4FL ^ zi * 0x165667B19E3779F9L;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }

    private static float randSigned(long h) {
        // [-1..+1]
        return (((h >>> 40) & 0xFFFFFFL) / (float)0x7FFFFFL) - 1.0f;
    }

    // ========================================================================
    // Legacy methods for compatibility
    // ========================================================================

    public static void emitAwakeStrandLashX(
            VertexConsumer vc,
            Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float tipOffsetX, float tipOffsetZ,
            float width,
            float height,
            float yawRadians,
            int light,
            float r, float g, float b,
            float eyeOpenT
    ) {
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, tipOffsetX, 0f, tipOffsetZ,
                width, height, light, r, g, b, eyeOpenT);
    }

    public static void emitDormantStrandX(
            VertexConsumer vc,
            Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float width,
            float height,
            float yawRadians,
            int light,
            float r, float g, float b
    ) {
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, 0f, 0f, 0f,
                width, height, light, r, g, b, 0f);
    }

    public static void emitAwakeStrandX(
            VertexConsumer vc,
            Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float width,
            float height,
            float yawRadians,
            int light,
            float r, float g, float b,
            float eyeOpenT
    ) {
        float offsetX = MathHelper.cos(yawRadians) * 0.05f;
        float offsetZ = MathHelper.sin(yawRadians) * 0.05f;
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, offsetX, 0f, offsetZ,
                width, height, light, r, g, b, eyeOpenT);
    }

    public static void emitAwakeStrandLeanX(
            VertexConsumer vc,
            Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float tipOffsetX, float tipOffsetZ,
            float width,
            float height,
            float yawRadians,
            int light,
            float r, float g, float b,
            float eyeOpenT
    ) {
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, tipOffsetX, 0f, tipOffsetZ,
                width, height, light, r, g, b, eyeOpenT);
    }

    public static void emitDormantStrandLeanX(
            VertexConsumer vc,
            Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float tipOffsetX, float tipOffsetZ,
            float width,
            float height,
            float yawRadians,
            int light,
            float r, float g, float b
    ) {
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, tipOffsetX, 0f, tipOffsetZ,
                width, height, light, r, g, b, 0f);
    }

    public static void emitDormantStrandLashX(
            VertexConsumer vc,
            Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float tipOffsetX, float tipOffsetZ,
            float width,
            float height,
            float yawRadians,
            int light,
            float r, float g, float b
    ) {
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, tipOffsetX, 0f, tipOffsetZ,
                width, height, light, r, g, b, 0f);
    }

    private WormGrassStrandModel() {}
}
