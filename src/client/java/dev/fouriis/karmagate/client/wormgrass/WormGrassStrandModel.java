package dev.fouriis.karmagate.client.wormgrass;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

/**
 * Wormgrass strand rendering - creates curved, segmented strands that bend
 * smoothly from base to tip, matching Rain World's visual style.
 */
public final class WormGrassStrandModel {

    // Number of segments for the curved body
    private static final int SEGMENTS = 5;

    /**
     * Emit a curved strand that bends toward a target position.
     * The entire body curves smoothly, with the tip pointing along the curve tangent.
     * 
     * @param tipOffsetX Horizontal offset of tip from base (X)
     * @param tipOffsetZ Horizontal offset of tip from base (Z)
     */
    public static void emitCurvedStrand(
            VertexConsumer vc,
            Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float tipOffsetX, float tipOffsetZ,
            float width,
            float height,
            int light,
            float r, float g, float b,
            float eyeOpenT
    ) {
        // Generate curved segment positions using quadratic bezier-like interpolation
        // Base -> Control point (straight up) -> Tip
        float[] segX = new float[SEGMENTS + 1];
        float[] segY = new float[SEGMENTS + 1];
        float[] segZ = new float[SEGMENTS + 1];
        float[] segWidth = new float[SEGMENTS + 1];
        
        // Control point is above the base, creating a natural droop/curve
        float ctrlX = baseX + tipOffsetX * 0.25f;
        float ctrlY = baseY + height * 0.65f;
        float ctrlZ = baseZ + tipOffsetZ * 0.25f;
        
        float tipX = baseX + tipOffsetX;
        float tipY = baseY + height;
        float tipZ = baseZ + tipOffsetZ;
        
        for (int i = 0; i <= SEGMENTS; i++) {
            float t = i / (float) SEGMENTS;
            
            // Quadratic bezier: P = (1-t)²*P0 + 2*(1-t)*t*P1 + t²*P2
            float mt = 1f - t;
            float mt2 = mt * mt;
            float t2 = t * t;
            float twoMtT = 2f * mt * t;
            
            segX[i] = mt2 * baseX + twoMtT * ctrlX + t2 * tipX;
            segY[i] = mt2 * baseY + twoMtT * ctrlY + t2 * tipY;
            segZ[i] = mt2 * baseZ + twoMtT * ctrlZ + t2 * tipZ;
            
            // Width tapers from base to tip
            float taperT = smoothstep(t);
            segWidth[i] = width * MathHelper.lerp(taperT, 1.0f, 0.3f);
        }
        
        // Emit body segments as connected quads (X-cross pattern)
        for (int i = 0; i < SEGMENTS; i++) {
            float t = (i + 0.5f) / (float) SEGMENTS;
            
            // Calculate segment direction for proper quad orientation
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
            
            // Two perpendicular directions for X-cross
            // First: perpendicular in XZ plane
            float perpX1, perpZ1;
            float xzLen = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (xzLen > 0.001f) {
                perpX1 = -dirZ / xzLen;
                perpZ1 = dirX / xzLen;
            } else {
                perpX1 = 1f;
                perpZ1 = 0f;
            }
            
            // Second: rotated 90 degrees around the direction
            float perpX2 = perpZ1;
            float perpZ2 = -perpX1;
            
            // Emit two quads forming an X cross-section
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
        
        // Calculate tip tangent direction for proper tip orientation
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
        
        // Emit pointed tip oriented along the tangent
        float tipWidth2 = segWidth[SEGMENTS] * 0.65f;
        float tipLength = width * 0.7f;
        emitPointedTip(vc, posMat,
                segX[SEGMENTS], segY[SEGMENTS], segZ[SEGMENTS],
                tipDirX, tipDirY, tipDirZ,
                tipWidth2, tipLength,
                light, r, g, b);
        
        // Emit eye if awake
        if (eyeOpenT > 0f) {
            float open = MathHelper.clamp(eyeOpenT, 0f, 1f);
            float eyeWidth = width * MathHelper.lerp(open, 0.22f, 0.50f);
            float eyeHeight = Math.max(0.02f, width * MathHelper.lerp(open, 0.12f, 0.30f));
            
            // Position eye on the upper part of the curve, but not at the very tip
            float eyeT = 0.78f;
            int eyeSeg = (int) (eyeT * SEGMENTS);
            float eyeLocalT = (eyeT * SEGMENTS) - eyeSeg;
            eyeSeg = Math.min(eyeSeg, SEGMENTS - 1);
            
            float eyeX = MathHelper.lerp(eyeLocalT, segX[eyeSeg], segX[eyeSeg + 1]);
            float eyeY = MathHelper.lerp(eyeLocalT, segY[eyeSeg], segY[eyeSeg + 1]);
            float eyeZ = MathHelper.lerp(eyeLocalT, segZ[eyeSeg], segZ[eyeSeg + 1]);
            
            // Eye direction should face outward from the curve tangent
            float eyeDirX = segX[eyeSeg + 1] - segX[eyeSeg];
            float eyeDirZ = segZ[eyeSeg + 1] - segZ[eyeSeg];
            float eyeYaw = (float) Math.atan2(eyeDirZ, eyeDirX);
            
            float eyeR = 0.20f;
            float eyeG = 0.00f;
            float eyeB = 1.00f;
            
            emitEyeQuads(vc, posMat,
                    eyeX, eyeY, eyeZ,
                    eyeWidth, eyeHeight,
                    eyeYaw,
                    light, eyeR, eyeG, eyeB);
        }
    }
    
    /**
     * Emit a segment quad between two points with specified width at each end.
     */
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
        
        // Four corners of the quad
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
        
        // Normal pointing perpendicular to the quad
        float nx = -perpZ;
        float ny = 0f;
        float nz = perpX;
        
        float a = 1.0f;
        
        // Emit quad (counter-clockwise winding)
        vertex(vc, posMat, ax, ay, az, r, g, b, a, 0f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, bx, by, bz, r, g, b, a, 1f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, cx, cy, cz, r, g, b, a, 1f, 0f, light, nx, ny, nz);
        vertex(vc, posMat, dx, dy, dz, r, g, b, a, 0f, 0f, light, nx, ny, nz);
    }
    
    /**
     * Emit a pointed tip oriented along a direction vector.
     */
    private static void emitPointedTip(
            VertexConsumer vc, Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float dirX, float dirY, float dirZ,
            float width, float length,
            int light,
            float r, float g, float b
    ) {
        // Tip point
        float ptX = baseX + dirX * length;
        float ptY = baseY + dirY * length;
        float ptZ = baseZ + dirZ * length;
        
        // Perpendicular directions for the base
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
        
        float hw = width * 0.5f;
        
        // Emit two triangular faces (forming a 4-sided pyramid tip)
        // Using quads with the tip point duplicated
        
        // Face 1
        float a1x = baseX - perpX1 * hw;
        float a1z = baseZ - perpZ1 * hw;
        float b1x = baseX + perpX1 * hw;
        float b1z = baseZ + perpZ1 * hw;
        
        vertex(vc, posMat, a1x, baseY, a1z, r, g, b, 1f, 0f, 1f, light, -perpZ1, 0.5f, perpX1);
        vertex(vc, posMat, b1x, baseY, b1z, r, g, b, 1f, 1f, 1f, light, -perpZ1, 0.5f, perpX1);
        vertex(vc, posMat, ptX, ptY, ptZ, r, g, b, 1f, 0.5f, 0f, light, -perpZ1, 0.5f, perpX1);
        vertex(vc, posMat, ptX, ptY, ptZ, r, g, b, 1f, 0.5f, 0f, light, -perpZ1, 0.5f, perpX1);
        
        // Face 2 (rotated 90 degrees)
        float a2x = baseX - perpX2 * hw;
        float a2z = baseZ - perpZ2 * hw;
        float b2x = baseX + perpX2 * hw;
        float b2z = baseZ + perpZ2 * hw;
        
        vertex(vc, posMat, a2x, baseY, a2z, r, g, b, 1f, 0f, 1f, light, -perpZ2, 0.5f, perpX2);
        vertex(vc, posMat, b2x, baseY, b2z, r, g, b, 1f, 1f, 1f, light, -perpZ2, 0.5f, perpX2);
        vertex(vc, posMat, ptX, ptY, ptZ, r, g, b, 1f, 0.5f, 0f, light, -perpZ2, 0.5f, perpX2);
        vertex(vc, posMat, ptX, ptY, ptZ, r, g, b, 1f, 0.5f, 0f, light, -perpZ2, 0.5f, perpX2);
    }
    
    /**
     * Emit eye quads (X-cross pattern for visibility from all angles).
     */
    private static void emitEyeQuads(
            VertexConsumer vc, Matrix4f posMat,
            float x, float y, float z,
            float width, float height,
            float yaw,
            int light,
            float r, float g, float b
    ) {
        // Two quads at 90-degree angles
        emitVerticalQuad(vc, posMat, x, y - height * 0.5f, z, width, height, yaw, light, r, g, b);
        emitVerticalQuad(vc, posMat, x, y - height * 0.5f, z, width, height, 
                yaw + (float)(Math.PI * 0.5), light, r, g, b);
    }
    
    /**
     * Simple vertical quad centered at a position.
     */
    private static void emitVerticalQuad(
            VertexConsumer vc,
            Matrix4f posMat,
            float baseX, float baseY, float baseZ,
            float width,
            float height,
            float yaw,
            int light,
            float r, float g, float b
    ) {
        float dx = MathHelper.cos(yaw);
        float dz = MathHelper.sin(yaw);

        float hx = dx * (width * 0.5f);
        float hz = dz * (width * 0.5f);

        float y0 = baseY;
        float y1 = baseY + height;

        float x0 = baseX - hx, z0 = baseZ - hz;
        float x1 = baseX + hx, z1 = baseZ + hz;

        float a = 1.0f;
        float nx = -dz, ny = 0f, nz = dx;

        vertex(vc, posMat, x0, y0, z0, r, g, b, a, 0f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, x1, y0, z1, r, g, b, a, 1f, 1f, light, nx, ny, nz);
        vertex(vc, posMat, x1, y1, z1, r, g, b, a, 1f, 0f, light, nx, ny, nz);
        vertex(vc, posMat, x0, y1, z0, r, g, b, a, 0f, 0f, light, nx, ny, nz);
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

    // ========================================================================
    // Legacy methods for compatibility - delegate to new curved strand
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
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, tipOffsetX, tipOffsetZ,
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
        // Dormant = no offset, no eye
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, 0f, 0f,
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
        // Awake but no lean = small random offset based on yaw
        float offsetX = MathHelper.cos(yawRadians) * 0.05f;
        float offsetZ = MathHelper.sin(yawRadians) * 0.05f;
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, offsetX, offsetZ,
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
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, tipOffsetX, tipOffsetZ,
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
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, tipOffsetX, tipOffsetZ,
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
        emitCurvedStrand(vc, posMat, baseX, baseY, baseZ, tipOffsetX, tipOffsetZ,
                width, height, light, r, g, b, 0f);
    }

    private WormGrassStrandModel() {}
}
