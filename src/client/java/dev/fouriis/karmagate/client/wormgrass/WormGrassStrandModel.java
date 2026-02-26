package dev.fouriis.karmagate.client.wormgrass;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.*;

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
    public static final int SEGMENTS = 3;

    public static final int SEGMENTS_MID = 2;

    /**
     * Avoid per-strand allocations (this was allocating 4 float arrays per strand).
     * ThreadLocal keeps it render-thread safe.
     */
    private static final ThreadLocal<float[]> TL_SEG_X = ThreadLocal.withInitial(() -> new float[SEGMENTS + 1]);
    private static final ThreadLocal<float[]> TL_SEG_Y = ThreadLocal.withInitial(() -> new float[SEGMENTS + 1]);
    private static final ThreadLocal<float[]> TL_SEG_Z = ThreadLocal.withInitial(() -> new float[SEGMENTS + 1]);
    private static final ThreadLocal<float[]> TL_SEG_W = ThreadLocal.withInitial(() -> new float[SEGMENTS + 1]);

    /**
     * Ring corner cache: 4 corners per ring, 3 floats (x,y,z) each = 12 floats per ring.
     * Layout: ring[i] starts at index i*12. Corner order: +p2, -p2, +p1, -p1 (matches the 4 faces).
     * Sized for max segments used (SEGMENTS+1 rings).
     */
    private static final ThreadLocal<float[]> TL_RING = ThreadLocal.withInitial(() -> new float[(SEGMENTS + 1) * 12]);

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
            float lodLevel,
            int segments,
            float camX, float camZ
    ) {
        if (lodLevel > 3f) {
            emitPotatoBillboard(vc, posMat, baseX, baseY, baseZ, camX, camZ, width * 3.0f, height, light, r, g, b);
            return;
        }

        float[] segX = TL_SEG_X.get();
        float[] segY = TL_SEG_Y.get();
        float[] segZ = TL_SEG_Z.get();
        float[] segWidth = TL_SEG_W.get();

        float controlPointX = baseX + tipOffsetX * 0.25f;
        float controlPointY = baseY + height * 0.65f + tipOffsetY * 0.25f;
        float controlPointZ = baseZ + tipOffsetZ * 0.25f;

        float tipX = baseX + tipOffsetX;
        float tipY = baseY + height + tipOffsetY;
        float tipZ = baseZ + tipOffsetZ;

        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments;

            float oneMinusT = 1f - t;
            float oneMinusTSquared = oneMinusT * oneMinusT;
            float tSquared = t * t;
            float twoOneMinusTT = 2f * oneMinusT * t;

            segX[i] = oneMinusTSquared * baseX + twoOneMinusTT * controlPointX + tSquared * tipX;
            segY[i] = oneMinusTSquared * baseY + twoOneMinusTT * controlPointY + tSquared * tipY;
            segZ[i] = oneMinusTSquared * baseZ + twoOneMinusTT * controlPointZ + tSquared * tipZ;

            float taperT = smoothstep(t);
            float tipWidthFloor = 0.45f;
            segWidth[i] = width * MathHelper.lerp(taperT, 1.0f, tipWidthFloor);
        }

        float initialDirX = segX[1] - segX[0];
        float initialDirY = segY[1] - segY[0];
        float initialDirZ = segZ[1] - segZ[0];
        float initialDirLen = (float) Math.sqrt(initialDirX * initialDirX + initialDirY * initialDirY + initialDirZ * initialDirZ);

        if (initialDirLen > 0.0001f) {
            initialDirX /= initialDirLen;
            initialDirY /= initialDirLen;
            initialDirZ /= initialDirLen;
        } else {
            initialDirY = 1f;
        }

        float refUpX = 0f, refUpY = 1f, refUpZ = 0f;
        if (Math.abs(initialDirY) > 0.99f) {
            refUpX = 1f;
            refUpY = 0f;
        }

        float perp1X = refUpY * initialDirZ - refUpZ * initialDirY;
        float perp1Y = refUpZ * initialDirX - refUpX * initialDirZ;
        float perp1Z = refUpX * initialDirY - refUpY * initialDirX;
        float perp1Len = (float) Math.sqrt(perp1X * perp1X + perp1Y * perp1Y + perp1Z * perp1Z);

        if (perp1Len > 0.0001f) {
            perp1X /= perp1Len;
            perp1Y /= perp1Len;
            perp1Z /= perp1Len;
        }

        float perp2X = initialDirY * perp1Z - initialDirZ * perp1Y;
        float perp2Y = initialDirZ * perp1X - initialDirX * perp1Z;
        float perp2Z = initialDirX * perp1Y - initialDirY * perp1X;
        float perp2Len = (float) Math.sqrt(perp2X * perp2X + perp2Y * perp2Y + perp2Z * perp2Z);

        if (perp2Len > 0.0001f) {
            perp2X /= perp2Len;
            perp2Y /= perp2Len;
            perp2Z /= perp2Len;
        }

        if (lodLevel <= 1f) {
            float[] ring = TL_RING.get();

            float prevDirX = initialDirX, prevDirY = initialDirY, prevDirZ = initialDirZ;

            for (int i = 0; i <= segments; i++) {
                if (i > 0) {
                    float curDirX, curDirY, curDirZ;
                    if (i < segments) {
                        curDirX = segX[i + 1] - segX[i];
                        curDirY = segY[i + 1] - segY[i];
                        curDirZ = segZ[i + 1] - segZ[i];
                    } else {
                        curDirX = segX[i] - segX[i - 1];
                        curDirY = segY[i] - segY[i - 1];
                        curDirZ = segZ[i] - segZ[i - 1];
                    }
                    float curLen = (float) Math.sqrt(curDirX * curDirX + curDirY * curDirY + curDirZ * curDirZ);

                    if (curLen > 0.0001f) {
                        curDirX /= curLen;
                        curDirY /= curLen;
                        curDirZ /= curLen;
                    }

                    float bisectorX = curDirX + prevDirX;
                    float bisectorY = curDirY + prevDirY;
                    float bisectorZ = curDirZ + prevDirZ;
                    float bisectorLen = (float) Math.sqrt(bisectorX * bisectorX + bisectorY * bisectorY + bisectorZ * bisectorZ);

                    if (bisectorLen > 0.0001f) {
                        bisectorX /= bisectorLen;
                        bisectorY /= bisectorLen;
                        bisectorZ /= bisectorLen;

                        float dot1 = 2f * (perp1X * bisectorX + perp1Y * bisectorY + perp1Z * bisectorZ);
                        perp1X -= dot1 * bisectorX;
                        perp1Y -= dot1 * bisectorY;
                        perp1Z -= dot1 * bisectorZ;

                        float dot2 = 2f * (perp2X * bisectorX + perp2Y * bisectorY + perp2Z * bisectorZ);
                        perp2X -= dot2 * bisectorX;
                        perp2Y -= dot2 * bisectorY;
                        perp2Z -= dot2 * bisectorZ;
                    }

                    prevDirX = curDirX;
                    prevDirY = curDirY;
                    prevDirZ = curDirZ;
                }

                float ringCentreX = segX[i];
                float ringCentreY = segY[i];
                float ringCentreZ = segZ[i];
                float halfWidth = segWidth[i] * 0.5f;

                int ringBase = i * 12;
                ring[ringBase]      = ringCentreX + perp2X * halfWidth;
                ring[ringBase + 1]  = ringCentreY + perp2Y * halfWidth;
                ring[ringBase + 2]  = ringCentreZ + perp2Z * halfWidth;

                ring[ringBase + 3]  = ringCentreX - perp2X * halfWidth;
                ring[ringBase + 4]  = ringCentreY - perp2Y * halfWidth;
                ring[ringBase + 5]  = ringCentreZ - perp2Z * halfWidth;

                ring[ringBase + 6]  = ringCentreX + perp1X * halfWidth;
                ring[ringBase + 7]  = ringCentreY + perp1Y * halfWidth;
                ring[ringBase + 8]  = ringCentreZ + perp1Z * halfWidth;

                ring[ringBase + 9]  = ringCentreX - perp1X * halfWidth;
                ring[ringBase + 10] = ringCentreY - perp1Y * halfWidth;
                ring[ringBase + 11] = ringCentreZ - perp1Z * halfWidth;
            }

            for (int i = 0; i < segments; i++) {
                int bottomRingBase = i * 12;
                int topRingBase = (i + 1) * 12;

                float bottomCentreX = segX[i];
                float bottomCentreY = segY[i];
                float bottomCentreZ = segZ[i];
                float topCentreX = segX[i + 1];
                float topCentreY = segY[i + 1];
                float topCentreZ = segZ[i + 1];

                float cornerBottomAx = ring[bottomRingBase]     + ring[bottomRingBase + 6]  - bottomCentreX;
                float cornerBottomAy = ring[bottomRingBase + 1] + ring[bottomRingBase + 7]  - bottomCentreY;
                float cornerBottomAz = ring[bottomRingBase + 2] + ring[bottomRingBase + 8]  - bottomCentreZ;

                float cornerBottomBx = ring[bottomRingBase + 3] + ring[bottomRingBase + 6]  - bottomCentreX;
                float cornerBottomBy = ring[bottomRingBase + 4] + ring[bottomRingBase + 7]  - bottomCentreY;
                float cornerBottomBz = ring[bottomRingBase + 5] + ring[bottomRingBase + 8]  - bottomCentreZ;

                float cornerBottomCx = ring[bottomRingBase + 3] + ring[bottomRingBase + 9]  - bottomCentreX;
                float cornerBottomCy = ring[bottomRingBase + 4] + ring[bottomRingBase + 10] - bottomCentreY;
                float cornerBottomCz = ring[bottomRingBase + 5] + ring[bottomRingBase + 11] - bottomCentreZ;

                float cornerBottomDx = ring[bottomRingBase]     + ring[bottomRingBase + 9]  - bottomCentreX;
                float cornerBottomDy = ring[bottomRingBase + 1] + ring[bottomRingBase + 10] - bottomCentreY;
                float cornerBottomDz = ring[bottomRingBase + 2] + ring[bottomRingBase + 11] - bottomCentreZ;

                float cornerTopAx = ring[topRingBase]     + ring[topRingBase + 6]  - topCentreX;
                float cornerTopAy = ring[topRingBase + 1] + ring[topRingBase + 7]  - topCentreY;
                float cornerTopAz = ring[topRingBase + 2] + ring[topRingBase + 8]  - topCentreZ;

                float cornerTopBx = ring[topRingBase + 3] + ring[topRingBase + 6]  - topCentreX;
                float cornerTopBy = ring[topRingBase + 4] + ring[topRingBase + 7]  - topCentreY;
                float cornerTopBz = ring[topRingBase + 5] + ring[topRingBase + 8]  - topCentreZ;

                float cornerTopCx = ring[topRingBase + 3] + ring[topRingBase + 9]  - topCentreX;
                float cornerTopCy = ring[topRingBase + 4] + ring[topRingBase + 10] - topCentreY;
                float cornerTopCz = ring[topRingBase + 5] + ring[topRingBase + 11] - topCentreZ;

                float cornerTopDx = ring[topRingBase]     + ring[topRingBase + 9]  - topCentreX;
                float cornerTopDy = ring[topRingBase + 1] + ring[topRingBase + 10] - topCentreY;
                float cornerTopDz = ring[topRingBase + 2] + ring[topRingBase + 11] - topCentreZ;

                float alpha = 1f;

                float facePosPerpNormalX = ring[bottomRingBase]     - bottomCentreX;
                float facePosPerpNormalY = ring[bottomRingBase + 1] - bottomCentreY;
                float facePosPerpNormalZ = ring[bottomRingBase + 2] - bottomCentreZ;
                float facePosPerpNormalLen = (float) Math.sqrt(facePosPerpNormalX * facePosPerpNormalX + facePosPerpNormalY * facePosPerpNormalY + facePosPerpNormalZ * facePosPerpNormalZ);
                if (facePosPerpNormalLen > 1e-5f) {
                    facePosPerpNormalX /= facePosPerpNormalLen;
                    facePosPerpNormalY /= facePosPerpNormalLen;
                    facePosPerpNormalZ /= facePosPerpNormalLen;
                }

                vertex(vc, posMat, cornerBottomDx, cornerBottomDy, cornerBottomDz, r, g, b, alpha, 0f, 1f, light, facePosPerpNormalX, facePosPerpNormalY, facePosPerpNormalZ);
                vertex(vc, posMat, cornerBottomAx, cornerBottomAy, cornerBottomAz, r, g, b, alpha, 1f, 1f, light, facePosPerpNormalX, facePosPerpNormalY, facePosPerpNormalZ);
                vertex(vc, posMat, cornerTopAx,    cornerTopAy,    cornerTopAz,    r, g, b, alpha, 1f, 0f, light, facePosPerpNormalX, facePosPerpNormalY, facePosPerpNormalZ);
                vertex(vc, posMat, cornerTopDx,    cornerTopDy,    cornerTopDz,    r, g, b, alpha, 0f, 0f, light, facePosPerpNormalX, facePosPerpNormalY, facePosPerpNormalZ);

                float faceNegPerpNormalX = ring[bottomRingBase + 3] - bottomCentreX;
                float faceNegPerpNormalY = ring[bottomRingBase + 4] - bottomCentreY;
                float faceNegPerpNormalZ = ring[bottomRingBase + 5] - bottomCentreZ;
                float faceNegPerpNormalLen = (float) Math.sqrt(faceNegPerpNormalX * faceNegPerpNormalX + faceNegPerpNormalY * faceNegPerpNormalY + faceNegPerpNormalZ * faceNegPerpNormalZ);
                if (faceNegPerpNormalLen > 1e-5f) {
                    faceNegPerpNormalX /= faceNegPerpNormalLen;
                    faceNegPerpNormalY /= faceNegPerpNormalLen;
                    faceNegPerpNormalZ /= faceNegPerpNormalLen;
                }

                vertex(vc, posMat, cornerBottomCx, cornerBottomCy, cornerBottomCz, r, g, b, alpha, 0f, 1f, light, faceNegPerpNormalX, faceNegPerpNormalY, faceNegPerpNormalZ);
                vertex(vc, posMat, cornerBottomBx, cornerBottomBy, cornerBottomBz, r, g, b, alpha, 1f, 1f, light, faceNegPerpNormalX, faceNegPerpNormalY, faceNegPerpNormalZ);
                vertex(vc, posMat, cornerTopBx,    cornerTopBy,    cornerTopBz,    r, g, b, alpha, 1f, 0f, light, faceNegPerpNormalX, faceNegPerpNormalY, faceNegPerpNormalZ);
                vertex(vc, posMat, cornerTopCx,    cornerTopCy,    cornerTopCz,    r, g, b, alpha, 0f, 0f, light, faceNegPerpNormalX, faceNegPerpNormalY, faceNegPerpNormalZ);

                float facePosPerp1NormalX = ring[bottomRingBase + 6] - bottomCentreX;
                float facePosPerp1NormalY = ring[bottomRingBase + 7] - bottomCentreY;
                float facePosPerp1NormalZ = ring[bottomRingBase + 8] - bottomCentreZ;
                float facePosPerp1NormalLen = (float) Math.sqrt(facePosPerp1NormalX * facePosPerp1NormalX + facePosPerp1NormalY * facePosPerp1NormalY + facePosPerp1NormalZ * facePosPerp1NormalZ);
                if (facePosPerp1NormalLen > 1e-5f) {
                    facePosPerp1NormalX /= facePosPerp1NormalLen;
                    facePosPerp1NormalY /= facePosPerp1NormalLen;
                    facePosPerp1NormalZ /= facePosPerp1NormalLen;
                }

                vertex(vc, posMat, cornerBottomBx, cornerBottomBy, cornerBottomBz, r, g, b, alpha, 0f, 1f, light, facePosPerp1NormalX, facePosPerp1NormalY, facePosPerp1NormalZ);
                vertex(vc, posMat, cornerBottomAx, cornerBottomAy, cornerBottomAz, r, g, b, alpha, 1f, 1f, light, facePosPerp1NormalX, facePosPerp1NormalY, facePosPerp1NormalZ);
                vertex(vc, posMat, cornerTopAx,    cornerTopAy,    cornerTopAz,    r, g, b, alpha, 1f, 0f, light, facePosPerp1NormalX, facePosPerp1NormalY, facePosPerp1NormalZ);
                vertex(vc, posMat, cornerTopBx,    cornerTopBy,    cornerTopBz,    r, g, b, alpha, 0f, 0f, light, facePosPerp1NormalX, facePosPerp1NormalY, facePosPerp1NormalZ);

                float faceNegPerp1NormalX = ring[bottomRingBase + 9]  - bottomCentreX;
                float faceNegPerp1NormalY = ring[bottomRingBase + 10] - bottomCentreY;
                float faceNegPerp1NormalZ = ring[bottomRingBase + 11] - bottomCentreZ;
                float faceNegPerp1NormalLen = (float) Math.sqrt(faceNegPerp1NormalX * faceNegPerp1NormalX + faceNegPerp1NormalY * faceNegPerp1NormalY + faceNegPerp1NormalZ * faceNegPerp1NormalZ);
                if (faceNegPerp1NormalLen > 1e-5f) {
                    faceNegPerp1NormalX /= faceNegPerp1NormalLen;
                    faceNegPerp1NormalY /= faceNegPerp1NormalLen;
                    faceNegPerp1NormalZ /= faceNegPerp1NormalLen;
                }

                vertex(vc, posMat, cornerBottomDx, cornerBottomDy, cornerBottomDz, r, g, b, alpha, 0f, 1f, light, faceNegPerp1NormalX, faceNegPerp1NormalY, faceNegPerp1NormalZ);
                vertex(vc, posMat, cornerBottomCx, cornerBottomCy, cornerBottomCz, r, g, b, alpha, 1f, 1f, light, faceNegPerp1NormalX, faceNegPerp1NormalY, faceNegPerp1NormalZ);
                vertex(vc, posMat, cornerTopCx,    cornerTopCy,    cornerTopCz,    r, g, b, alpha, 1f, 0f, light, faceNegPerp1NormalX, faceNegPerp1NormalY, faceNegPerp1NormalZ);
                vertex(vc, posMat, cornerTopDx,    cornerTopDy,    cornerTopDz,    r, g, b, alpha, 0f, 0f, light, faceNegPerp1NormalX, faceNegPerp1NormalY, faceNegPerp1NormalZ);
            }

            int lastRingBase = segments * 12;
            float lastRingCentreX = segX[segments];
            float lastRingCentreY = segY[segments];
            float lastRingCentreZ = segZ[segments];

            float capPerp1X = ring[lastRingBase + 6] - lastRingCentreX;
            float capPerp1Y = ring[lastRingBase + 7] - lastRingCentreY;
            float capPerp1Z = ring[lastRingBase + 8] - lastRingCentreZ;
            float capPerp1Len = (float) Math.sqrt(capPerp1X * capPerp1X + capPerp1Y * capPerp1Y + capPerp1Z * capPerp1Z);
            if (capPerp1Len > 1e-5f) {
                capPerp1X /= capPerp1Len;
                capPerp1Y /= capPerp1Len;
                capPerp1Z /= capPerp1Len;
            }

            float capPerp2X = ring[lastRingBase]     - lastRingCentreX;
            float capPerp2Y = ring[lastRingBase + 1] - lastRingCentreY;
            float capPerp2Z = ring[lastRingBase + 2] - lastRingCentreZ;
            float capPerp2Len = (float) Math.sqrt(capPerp2X * capPerp2X + capPerp2Y * capPerp2Y + capPerp2Z * capPerp2Z);
            if (capPerp2Len > 1e-5f) {
                capPerp2X /= capPerp2Len;
                capPerp2Y /= capPerp2Len;
                capPerp2Z /= capPerp2Len;
            }

            emitTangentAlignedQuad(vc, posMat,
                    lastRingCentreX, lastRingCentreY + 0.00001f, lastRingCentreZ,
                    segWidth[segments], segWidth[segments],
                    capPerp1X, capPerp1Y, capPerp1Z,
                    capPerp2X, capPerp2Y, capPerp2Z,
                    light, r, g, b
            );

        } else if (lodLevel <= 2f) {
            float prevDirX = initialDirX, prevDirY = initialDirY, prevDirZ = initialDirZ;

            float bottomPerp1X = perp1X, bottomPerp1Y = perp1Y, bottomPerp1Z = perp1Z;
            float bottomPerp2X = perp2X, bottomPerp2Y = perp2Y, bottomPerp2Z = perp2Z;

            float bottomHalfWidth = segWidth[0] * 0.5f;
            float bottomMidPerp1X = segX[0] + bottomPerp1X * bottomHalfWidth;
            float bottomMidPerp1Y = segY[0] + bottomPerp1Y * bottomHalfWidth;
            float bottomMidPerp1Z = segZ[0] + bottomPerp1Z * bottomHalfWidth;

            float bottomMidNegPerp1X = segX[0] - bottomPerp1X * bottomHalfWidth;
            float bottomMidNegPerp1Y = segY[0] - bottomPerp1Y * bottomHalfWidth;
            float bottomMidNegPerp1Z = segZ[0] - bottomPerp1Z * bottomHalfWidth;

            float bottomMidPerp2X = segX[0] + bottomPerp2X * bottomHalfWidth;
            float bottomMidPerp2Y = segY[0] + bottomPerp2Y * bottomHalfWidth;
            float bottomMidPerp2Z = segZ[0] + bottomPerp2Z * bottomHalfWidth;

            float bottomMidNegPerp2X = segX[0] - bottomPerp2X * bottomHalfWidth;
            float bottomMidNegPerp2Y = segY[0] - bottomPerp2Y * bottomHalfWidth;
            float bottomMidNegPerp2Z = segZ[0] - bottomPerp2Z * bottomHalfWidth;

            for (int i = 0; i < segments; i++) {
                if (i > 0) {
                    float curDirX = segX[i + 1] - segX[i];
                    float curDirY = segY[i + 1] - segY[i];
                    float curDirZ = segZ[i + 1] - segZ[i];
                    float curLen = (float) Math.sqrt(curDirX * curDirX + curDirY * curDirY + curDirZ * curDirZ);

                    if (curLen > 0.0001f) {
                        curDirX /= curLen;
                        curDirY /= curLen;
                        curDirZ /= curLen;
                    }

                    float bisectorX = curDirX + prevDirX;
                    float bisectorY = curDirY + prevDirY;
                    float bisectorZ = curDirZ + prevDirZ;
                    float bisectorLen = (float) Math.sqrt(bisectorX * bisectorX + bisectorY * bisectorY + bisectorZ * bisectorZ);

                    if (bisectorLen > 0.0001f) {
                        bisectorX /= bisectorLen;
                        bisectorY /= bisectorLen;
                        bisectorZ /= bisectorLen;

                        float dot1 = 2f * (perp1X * bisectorX + perp1Y * bisectorY + perp1Z * bisectorZ);
                        perp1X -= dot1 * bisectorX;
                        perp1Y -= dot1 * bisectorY;
                        perp1Z -= dot1 * bisectorZ;

                        float dot2 = 2f * (perp2X * bisectorX + perp2Y * bisectorY + perp2Z * bisectorZ);
                        perp2X -= dot2 * bisectorX;
                        perp2Y -= dot2 * bisectorY;
                        perp2Z -= dot2 * bisectorZ;
                    }

                    prevDirX = curDirX;
                    prevDirY = curDirY;
                    prevDirZ = curDirZ;

                    bottomMidPerp1X = segX[i] + perp1X * segWidth[i] * 0.5f;
                    bottomMidPerp1Y = segY[i] + perp1Y * segWidth[i] * 0.5f;
                    bottomMidPerp1Z = segZ[i] + perp1Z * segWidth[i] * 0.5f;

                    bottomMidNegPerp1X = segX[i] - perp1X * segWidth[i] * 0.5f;
                    bottomMidNegPerp1Y = segY[i] - perp1Y * segWidth[i] * 0.5f;
                    bottomMidNegPerp1Z = segZ[i] - perp1Z * segWidth[i] * 0.5f;

                    bottomMidPerp2X = segX[i] + perp2X * segWidth[i] * 0.5f;
                    bottomMidPerp2Y = segY[i] + perp2Y * segWidth[i] * 0.5f;
                    bottomMidPerp2Z = segZ[i] + perp2Z * segWidth[i] * 0.5f;

                    bottomMidNegPerp2X = segX[i] - perp2X * segWidth[i] * 0.5f;
                    bottomMidNegPerp2Y = segY[i] - perp2Y * segWidth[i] * 0.5f;
                    bottomMidNegPerp2Z = segZ[i] - perp2Z * segWidth[i] * 0.5f;
                }

                float nextDirX, nextDirY, nextDirZ;
                if (i + 1 < segments) {
                    nextDirX = segX[i + 2] - segX[i + 1];
                    nextDirY = segY[i + 2] - segY[i + 1];
                    nextDirZ = segZ[i + 2] - segZ[i + 1];
                } else {
                    nextDirX = segX[i + 1] - segX[i];
                    nextDirY = segY[i + 1] - segY[i];
                    nextDirZ = segZ[i + 1] - segZ[i];
                }
                float nextDirLen = (float) Math.sqrt(nextDirX * nextDirX + nextDirY * nextDirY + nextDirZ * nextDirZ);
                if (nextDirLen > 0.0001f) {
                    nextDirX /= nextDirLen;
                    nextDirY /= nextDirLen;
                    nextDirZ /= nextDirLen;
                }

                float currentDirX = segX[i + 1] - segX[i];
                float currentDirY = segY[i + 1] - segY[i];
                float currentDirZ = segZ[i + 1] - segZ[i];
                float currentDirLen = (float) Math.sqrt(currentDirX * currentDirX + currentDirY * currentDirY + currentDirZ * currentDirZ);
                if (currentDirLen > 0.0001f) {
                    currentDirX /= currentDirLen;
                    currentDirY /= currentDirLen;
                    currentDirZ /= currentDirLen;
                }

                float topPerp1X = perp1X, topPerp1Y = perp1Y, topPerp1Z = perp1Z;
                float topPerp2X = perp2X, topPerp2Y = perp2Y, topPerp2Z = perp2Z;

                float topBisectorX = nextDirX + currentDirX;
                float topBisectorY = nextDirY + currentDirY;
                float topBisectorZ = nextDirZ + currentDirZ;
                float topBisectorLen = (float) Math.sqrt(topBisectorX * topBisectorX + topBisectorY * topBisectorY + topBisectorZ * topBisectorZ);

                if (topBisectorLen > 0.0001f) {
                    topBisectorX /= topBisectorLen;
                    topBisectorY /= topBisectorLen;
                    topBisectorZ /= topBisectorLen;

                    float topDot1 = 2f * (topPerp1X * topBisectorX + topPerp1Y * topBisectorY + topPerp1Z * topBisectorZ);
                    topPerp1X -= topDot1 * topBisectorX;
                    topPerp1Y -= topDot1 * topBisectorY;
                    topPerp1Z -= topDot1 * topBisectorZ;

                    float topDot2 = 2f * (topPerp2X * topBisectorX + topPerp2Y * topBisectorY + topPerp2Z * topBisectorZ);
                    topPerp2X -= topDot2 * topBisectorX;
                    topPerp2Y -= topDot2 * topBisectorY;
                    topPerp2Z -= topDot2 * topBisectorZ;
                }

                float topHalfWidth = segWidth[i + 1] * 0.5f;
                float topMidPerp1X = segX[i + 1] + topPerp1X * topHalfWidth;
                float topMidPerp1Y = segY[i + 1] + topPerp1Y * topHalfWidth;
                float topMidPerp1Z = segZ[i + 1] + topPerp1Z * topHalfWidth;

                float topMidNegPerp1X = segX[i + 1] - topPerp1X * topHalfWidth;
                float topMidNegPerp1Y = segY[i + 1] - topPerp1Y * topHalfWidth;
                float topMidNegPerp1Z = segZ[i + 1] - topPerp1Z * topHalfWidth;

                float topMidPerp2X = segX[i + 1] + topPerp2X * topHalfWidth;
                float topMidPerp2Y = segY[i + 1] + topPerp2Y * topHalfWidth;
                float topMidPerp2Z = segZ[i + 1] + topPerp2Z * topHalfWidth;

                float topMidNegPerp2X = segX[i + 1] - topPerp2X * topHalfWidth;
                float topMidNegPerp2Y = segY[i + 1] - topPerp2Y * topHalfWidth;
                float topMidNegPerp2Z = segZ[i + 1] - topPerp2Z * topHalfWidth;

                float axisX = currentDirX;
                float axisY = currentDirY;
                float axisZ = currentDirZ;

                float alpha = 1f;

                vertex(vc, posMat, bottomMidNegPerp1X, bottomMidNegPerp1Y, bottomMidNegPerp1Z, r, g, b, alpha, 0f, 1f, light, axisX, axisY, axisZ);
                vertex(vc, posMat, bottomMidPerp1X, bottomMidPerp1Y, bottomMidPerp1Z, r, g, b, alpha, 1f, 1f, light, axisX, axisY, axisZ);
                vertex(vc, posMat, topMidPerp1X, topMidPerp1Y, topMidPerp1Z, r, g, b, alpha, 1f, 0f, light, axisX, axisY, axisZ);
                vertex(vc, posMat, topMidNegPerp1X, topMidNegPerp1Y, topMidNegPerp1Z, r, g, b, alpha, 0f, 0f, light, axisX, axisY, axisZ);

                vertex(vc, posMat, bottomMidNegPerp2X, bottomMidNegPerp2Y, bottomMidNegPerp2Z, r, g, b, alpha, 0f, 1f, light, axisX, axisY, axisZ);
                vertex(vc, posMat, bottomMidPerp2X, bottomMidPerp2Y, bottomMidPerp2Z, r, g, b, alpha, 1f, 1f, light, axisX, axisY, axisZ);
                vertex(vc, posMat, topMidPerp2X, topMidPerp2Y, topMidPerp2Z, r, g, b, alpha, 1f, 0f, light, axisX, axisY, axisZ);
                vertex(vc, posMat, topMidNegPerp2X, topMidNegPerp2Y, topMidNegPerp2Z, r, g, b, alpha, 0f, 0f, light, axisX, axisY, axisZ);

                bottomMidPerp1X = topMidPerp1X;
                bottomMidPerp1Y = topMidPerp1Y;
                bottomMidPerp1Z = topMidPerp1Z;

                bottomMidNegPerp1X = topMidNegPerp1X;
                bottomMidNegPerp1Y = topMidNegPerp1Y;
                bottomMidNegPerp1Z = topMidNegPerp1Z;

                bottomMidPerp2X = topMidPerp2X;
                bottomMidPerp2Y = topMidPerp2Y;
                bottomMidPerp2Z = topMidPerp2Z;

                bottomMidNegPerp2X = topMidNegPerp2X;
                bottomMidNegPerp2Y = topMidNegPerp2Y;
                bottomMidNegPerp2Z = topMidNegPerp2Z;

                perp1X = topPerp1X;
                perp1Y = topPerp1Y;
                perp1Z = topPerp1Z;

                perp2X = topPerp2X;
                perp2Y = topPerp2Y;
                perp2Z = topPerp2Z;

                prevDirX = currentDirX;
                prevDirY = currentDirY;
                prevDirZ = currentDirZ;
            }

            emitTangentAlignedQuad(vc, posMat,
                    segX[segments], segY[segments] + 0.00001f, segZ[segments],
                    segWidth[segments], segWidth[segments],
                    perp1X, perp1Y, perp1Z,
                    perp2X, perp2Y, perp2Z,
                    light, r, g, b
            );

        } else {
            float baseMidPerp1X = segX[0] + perp1X * segWidth[0] * 0.5f;
            float baseMidPerp1Y = segY[0] + perp1Y * segWidth[0] * 0.5f;
            float baseMidPerp1Z = segZ[0] + perp1Z * segWidth[0] * 0.5f;

            float baseMidNegPerp1X = segX[0] - perp1X * segWidth[0] * 0.5f;
            float baseMidNegPerp1Y = segY[0] - perp1Y * segWidth[0] * 0.5f;
            float baseMidNegPerp1Z = segZ[0] - perp1Z * segWidth[0] * 0.5f;

            float baseMidPerp2X = segX[0] + perp2X * segWidth[0] * 0.5f;
            float baseMidPerp2Y = segY[0] + perp2Y * segWidth[0] * 0.5f;
            float baseMidPerp2Z = segZ[0] + perp2Z * segWidth[0] * 0.5f;

            float baseMidNegPerp2X = segX[0] - perp2X * segWidth[0] * 0.5f;
            float baseMidNegPerp2Y = segY[0] - perp2Y * segWidth[0] * 0.5f;
            float baseMidNegPerp2Z = segZ[0] - perp2Z * segWidth[0] * 0.5f;

            float tipMidPerp1X = segX[segments] + perp1X * segWidth[segments] * 0.5f;
            float tipMidPerp1Y = segY[segments] + perp1Y * segWidth[segments] * 0.5f;
            float tipMidPerp1Z = segZ[segments] + perp1Z * segWidth[segments] * 0.5f;

            float tipMidNegPerp1X = segX[segments] - perp1X * segWidth[segments] * 0.5f;
            float tipMidNegPerp1Y = segY[segments] - perp1Y * segWidth[segments] * 0.5f;
            float tipMidNegPerp1Z = segZ[segments] - perp1Z * segWidth[segments] * 0.5f;

            float tipMidPerp2X = segX[segments] + perp2X * segWidth[segments] * 0.5f;
            float tipMidPerp2Y = segY[segments] + perp2Y * segWidth[segments] * 0.5f;
            float tipMidPerp2Z = segZ[segments] + perp2Z * segWidth[segments] * 0.5f;

            float tipMidNegPerp2X = segX[segments] - perp2X * segWidth[segments] * 0.5f;
            float tipMidNegPerp2Y = segY[segments] - perp2Y * segWidth[segments] * 0.5f;
            float tipMidNegPerp2Z = segZ[segments] - perp2Z * segWidth[segments] * 0.5f;

            float strandAxisX = tipX - baseX;
            float strandAxisY = tipY - baseY;
            float strandAxisZ = tipZ - baseZ;
            float strandAxisLen = (float) Math.sqrt(strandAxisX * strandAxisX + strandAxisY * strandAxisY + strandAxisZ * strandAxisZ);
            if (strandAxisLen > 0.0001f) {
                strandAxisX /= strandAxisLen;
                strandAxisY /= strandAxisLen;
                strandAxisZ /= strandAxisLen;
            }

            float alpha = 1f;

            vertex(vc, posMat, baseMidNegPerp1X, baseMidNegPerp1Y, baseMidNegPerp1Z, r, g, b, alpha, 0f, 1f, light, strandAxisX, strandAxisY, strandAxisZ);
            vertex(vc, posMat, baseMidPerp1X, baseMidPerp1Y, baseMidPerp1Z, r, g, b, alpha, 1f, 1f, light, strandAxisX, strandAxisY, strandAxisZ);
            vertex(vc, posMat, tipMidPerp1X, tipMidPerp1Y, tipMidPerp1Z, r, g, b, alpha, 1f, 0f, light, strandAxisX, strandAxisY, strandAxisZ);
            vertex(vc, posMat, tipMidNegPerp1X, tipMidNegPerp1Y, tipMidNegPerp1Z, r, g, b, alpha, 0f, 0f, light, strandAxisX, strandAxisY, strandAxisZ);

            vertex(vc, posMat, baseMidNegPerp2X, baseMidNegPerp2Y, baseMidNegPerp2Z, r, g, b, alpha, 0f, 1f, light, strandAxisX, strandAxisY, strandAxisZ);
            vertex(vc, posMat, baseMidPerp2X, baseMidPerp2Y, baseMidPerp2Z, r, g, b, alpha, 1f, 1f, light, strandAxisX, strandAxisY, strandAxisZ);
            vertex(vc, posMat, tipMidPerp2X, tipMidPerp2Y, tipMidPerp2Z, r, g, b, alpha, 1f, 0f, light, strandAxisX, strandAxisY, strandAxisZ);
            vertex(vc, posMat, tipMidNegPerp2X, tipMidNegPerp2Y, tipMidNegPerp2Z, r, g, b, alpha, 0f, 0f, light, strandAxisX, strandAxisY, strandAxisZ);

            emitTangentAlignedQuad(vc, posMat,
                    segX[segments], segY[segments] + 0.00001f, segZ[segments],
                    segWidth[segments], segWidth[segments],
                    perp1X, perp1Y, perp1Z,
                    perp2X, perp2Y, perp2Z,
                    light, r, g, b
            );
        }
    }

    private static void emitPotatoBillboard(
            VertexConsumer vc,
            Matrix4f posMat,
            float x, float y, float z,
            float camX, float camZ,
            float halfWidth,
            float height,
            int light,
            float r, float g, float b
    ) {
        float dirToCamX = camX - x;
        float dirToCamZ = camZ - z;
        float distToCam = (float) Math.sqrt(dirToCamX * dirToCamX + dirToCamZ * dirToCamZ);

        if (distToCam < 1.0e-4f) {
            dirToCamX = 1f;
            dirToCamZ = 0f;
        } else {
            dirToCamX /= distToCam;
            dirToCamZ /= distToCam;
        }

        float rightX = -dirToCamZ;
        float rightZ = dirToCamX;

        float leftX = x - rightX * halfWidth;
        float leftZ = z - rightZ * halfWidth;
        float rightEdgeX = x + rightX * halfWidth;
        float rightEdgeZ = z + rightZ * halfWidth;

        float bottomY = y;
        float topY = y + height;

        float normalX = dirToCamX;
        float normalY = 0f;
        float normalZ = dirToCamZ;
        float alpha = 1f;

        vertex(vc, posMat, leftX, bottomY, leftZ, r, g, b, alpha, 0f, 1f, light, normalX, normalY, normalZ);
        vertex(vc, posMat, rightEdgeX, bottomY, rightEdgeZ, r, g, b, alpha, 1f, 1f, light, normalX, normalY, normalZ);
        vertex(vc, posMat, rightEdgeX, topY, rightEdgeZ, r, g, b, alpha, 1f, 0f, light, normalX, normalY, normalZ);
        vertex(vc, posMat, leftX, topY, leftZ, r, g, b, alpha, 0f, 0f, light, normalX, normalY, normalZ);
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
            float perpX, float perpY, float perpZ,
            int light,
            float r, float g, float b
    ) {
        float hw0 = w0 * 0.5f;
        float hw1 = w1 * 0.5f;

        float ax = x0 - perpX * hw0;
        float ay = y0 - perpY * hw0;
        float az = z0 - perpZ * hw0;

        float bx = x0 + perpX * hw0;
        float by = y0 + perpY * hw0;
        float bz = z0 + perpZ * hw0;

        float cx = x1 + perpX * hw1;
        float cy = y1 + perpY * hw1;
        float cz = z1 + perpZ * hw1;

        float dx = x1 - perpX * hw1;
        float dy = y1 - perpY * hw1;
        float dz = z1 - perpZ * hw1;

        float a = 1.0f;

        vertex(vc, posMat, ax, ay, az, r, g, b, a, 0f, 1f, light, perpX, perpY, perpZ);
        vertex(vc, posMat, bx, by, bz, r, g, b, a, 1f, 1f, light, perpX, perpY, perpZ);
        vertex(vc, posMat, cx, cy, cz, r, g, b, a, 1f, 0f, light, perpX, perpY, perpZ);
        vertex(vc, posMat, dx, dy, dz, r, g, b, a, 0f, 0f, light, perpX, perpY, perpZ);
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
    public static long hashFromPos(float x, float y, float z) {
        long xi = (long)Math.floor(x * 8.0);
        long yi = (long)Math.floor(y * 8.0);
        long zi = (long)Math.floor(z * 8.0);
        long h = xi * 0x9E3779B97F4A7C15L ^ yi * 0xC2B2AE3D27D4EB4FL ^ zi * 0x165667B19E3779F9L;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }

    public static float randSigned(long h) {
        // [-1..+1]
        return (((h >>> 40) & 0xFFFFFFL) / (float)0x7FFFFFL) - 1.0f;
    }
}
