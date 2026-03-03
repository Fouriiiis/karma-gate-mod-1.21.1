package dev.fouriis.karmagate.client.swarmer;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasManager;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

public class NeuronSwarmerRenderer {

    private static final float BODY_MAX_DIM_BLOCKS = 0.4f;

    private static final float RW_WING_OFFSET_PX = 4.0f;
    private static final float RW_EYE_OFFSET_PX  = 2.0f;

    private static final float DEBUG_LINE_LEN_MULT = 1.2f;

    // Tiny push to avoid Z-fighting (in world units/blocks)
    private static final float Z_EPSILON = 0.0015f;

    // Force identical shading across both X planes (prevents “one side gray”)
    private static final float SHADE_NX = 0f, SHADE_NY = 1f, SHADE_NZ = 0f;
    // If you later add RW-style "dark" logic, flip this (or compute per-swarmer).
    private static final boolean DARK_MODE = false;
    private static boolean initialized = false;

    private static FAtlasManager atlasManager;
    private static FAtlasElement JETFISH_EYE_A;
    private static FAtlasElement DEER_EYE_A2;
    private static FAtlasElement JETFISH_EYE_B;

    public static void register() {
        if (initialized) return;
        initialized = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(NeuronSwarmerRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        if (atlasManager == null) {
            atlasManager = LibrainworldmcClient.getAtlasManager();
            JETFISH_EYE_A = atlasManager.getElementWithName("JetFishEyeA");
            DEER_EYE_A2   = atlasManager.getElementWithName("deerEyeA2");
            JETFISH_EYE_B = atlasManager.getElementWithName("JetFishEyeB");
        }

        if (JETFISH_EYE_A == null || DEER_EYE_A2 == null || JETFISH_EYE_B == null) return;

        List<NeuronSwarmer> swarmers = NeuronSwarmerManager.getInstance().getAllSwarmers();
        if (swarmers.isEmpty()) return;

        MatrixStack matrices = context.matrixStack();
        Camera camera = context.camera();
        float tickDelta = context.tickCounter().getTickDelta(true);
        Vec3d cameraPos = camera.getPos();
        ClientWorld world = client.world;

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        VertexConsumer bodyVc = consumers.getBuffer(RenderLayer.getEntityTranslucent(JETFISH_EYE_A.textureIdentifier));
        VertexConsumer wingVc = consumers.getBuffer(RenderLayer.getEntityTranslucent(DEER_EYE_A2.textureIdentifier));
        VertexConsumer eyeVc  = consumers.getBuffer(RenderLayer.getEntityTranslucent(JETFISH_EYE_B.textureIdentifier));
        VertexConsumer lineVc = consumers.getBuffer(RenderLayer.getLines());

        for (NeuronSwarmer sw : swarmers) {
            renderSwarmerSinglePlane(bodyVc, wingVc, eyeVc, lineVc, matrix, world, sw, tickDelta);
        }

        matrices.pop();
    }

    private static void renderSwarmerSinglePlane(
            VertexConsumer bodyVc,
            VertexConsumer wingVc,
            VertexConsumer eyeVc,
            VertexConsumer lineVc,
            Matrix4f matrix,
            ClientWorld world,
            NeuronSwarmer sw,
            float t
    ) {
        Vec3d pos = lerp(sw.lastPosition, sw.position, t);

        // FULL EMISSIVE (fullbright) for all sprites
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        // === Rain World accurate BODY color ===
        float[] bodyRgb = calculateColorRW_Body(sw.colorX, sw.colorY, DARK_MODE);
        int bodyR = (int) (bodyRgb[0] * 255);
        int bodyG = (int) (bodyRgb[1] * 255);
        int bodyB = (int) (bodyRgb[2] * 255);
        int bodyA = 255;

        // === Rain World accurate "sprite[4]" glow/eye color ===
        float[] glowRgb = calculateColorRW_Sprite4(sw.colorX, sw.colorY, DARK_MODE);
        int glowR = (int) (glowRgb[0] * 255);
        int glowG = (int) (glowRgb[1] * 255);
        int glowB = (int) (glowRgb[2] * 255);
        int glowA = 255;

        Vec3d dir = slerpUnitSafe(sw.lastDirection, sw.direction, t);
        if (dir.lengthSquared() < 1e-10) {
            Vec3d vel = sw.position.subtract(sw.lastPosition);
            dir = (vel.lengthSquared() > 1e-10) ? vel.normalize() : new Vec3d(1, 0, 0);
        }
        dir = dir.normalize();

        Vec3d lazy = slerpUnitSafe(sw.lastLazyDirection, sw.lazyDirection, t);
        if (lazy.lengthSquared() < 1e-10) lazy = dir;
        lazy = lazy.normalize();

        float rot = MathHelper.lerp(t, sw.lastRotation, sw.rotation);
        float phase = rot * (float) (Math.PI * 2.0);
        float num  = MathHelper.sin(phase);

        float pxToWorld = computePxToWorld(JETFISH_EYE_A, BODY_MAX_DIM_BLOCKS);

        // Stable frame + roll around forward (true 3D spin)
        Vec3d forward = dir;

        Vec3d worldUp = new Vec3d(0, 1, 0);
        Vec3d right0 = worldUp.crossProduct(forward);
        if (right0.lengthSquared() < 1e-10) right0 = new Vec3d(1, 0, 0).crossProduct(forward);
        right0 = right0.normalize();
        Vec3d up0 = forward.crossProduct(right0).normalize();

        double rollRad = phase;
        Vec3d right = rotateAroundAxis(right0, forward, rollRad).normalize();
        Vec3d up    = rotateAroundAxis(up0, forward, rollRad).normalize();

        Vec3d normal1 = right;
        Vec3d up2     = rotateAroundAxis(up, forward, Math.PI * 0.5).normalize();
        Vec3d normal2 = rotateAroundAxis(normal1, forward, Math.PI * 0.5).normalize();

        Vec3d spriteV = forward.multiply(-1);

        Vec3d lazyInPlane0 = projectIntoPlane(lazy, right0);
        if (lazyInPlane0.lengthSquared() < 1e-10) lazyInPlane0 = forward;
        lazyInPlane0 = lazyInPlane0.normalize();
        Vec3d lazyInPlane = rotateAroundAxis(lazyInPlane0, forward, rollRad).normalize();

        // BODY as an X
        float bodyW = JETFISH_EYE_A.sourcePixelSize.x * pxToWorld * 0.75f;
        float bodyH = JETFISH_EYE_A.sourcePixelSize.y * pxToWorld * 1.2f;

        emitCenteredQuad(bodyVc, matrix, pos, up,  spriteV, normal1, bodyW * 0.5f, bodyH * 0.5f, bodyR, bodyG, bodyB, bodyA, light);
        emitCenteredQuad(bodyVc, matrix, pos, up2, spriteV, normal2, bodyW * 0.5f, bodyH * 0.5f, bodyR, bodyG, bodyB, bodyA, light);

        // DEBUG line
        // float halfLen = (Math.max(bodyW, bodyH) * 0.5f) * DEBUG_LINE_LEN_MULT;
        // Vec3d p0 = pos.subtract(forward.multiply(halfLen));
        // Vec3d p1 = pos.add(forward.multiply(halfLen));
        // emitDebugLine(lineVc, matrix, p0, p1);

        // =========================================================
// EYE (debug RED): two hinged halves along the BLUE LINE (forward axis),
// folded 90° (uses up and up2 planes), BOTH pushed into the same corner quadrant.
// =========================================================

float eyeW = JETFISH_EYE_B.sourcePixelSize.x * pxToWorld;
float eyeH = JETFISH_EYE_B.sourcePixelSize.y * pxToWorld;

// Hinge edge midpoint lies ON the debug line through the body center
Vec3d hingeMid = pos;

// Hinge axis direction is along the line
Vec3d eyeV = spriteV; // == -forward (same as your sprites)

// Pick WHICH corner quadrant you want.
// If it ends up in the "wrong" corner, swap to one of the other three below.
//Vec3d cornerDir = up.add(up2);
Vec3d cornerDir = up.add(up2).multiply(-1);
// Vec3d cornerDir = up2.subtract(up);
// Vec3d cornerDir = up.add(up2).multiply(-1);

if (cornerDir.lengthSquared() < 1e-10) cornerDir = up;
cornerDir = cornerDir.normalize();

// Start with the two fold directions (each half lies in its own plane)
Vec3d outwardA = up.normalize().multiply(-1);
Vec3d outwardB = up2.normalize().multiply(-1);

// FORCE both halves to extend into the SAME corner quadrant
if (outwardA.dotProduct(cornerDir) < 0) outwardA = outwardA.multiply(-1);
if (outwardB.dotProduct(cornerDir) < 0) outwardB = outwardB.multiply(-1);

// Normals for tiny Z push (your shading normal is forced elsewhere)
Vec3d eyeNormalA = outwardA.crossProduct(eyeV).normalize();
if (eyeNormalA.lengthSquared() < 1e-10) eyeNormalA = normal1;

Vec3d eyeNormalB = outwardB.crossProduct(eyeV).normalize();
if (eyeNormalB.lengthSquared() < 1e-10) eyeNormalB = normal2;

// Each half is half the texture width, full height
float halfWidth = eyeW * 0.5f;
float halfH     = eyeH * 0.5f;

// Eye uses glow/sprite4 color
int eyeR = glowR, eyeG = glowG, eyeB = glowB, eyeA = glowA;

// Half A: u=[0..0.5], hinged on the line, extends into corner along outwardA
emitPivotLeftQuadUV(
    eyeVc, matrix,
    hingeMid.add(eyeNormalA.multiply(+Z_EPSILON)), // was +Z_EPSILON
    outwardA, eyeV, eyeNormalA,
    halfWidth, halfH,
    0.5f, 0.0f, // FLIP V to mirror the texture half (your RW eye halves are mirrored)
    false,
    eyeR, eyeG, eyeB, eyeA,
    light
);

emitPivotLeftQuadUV(
    eyeVc, matrix,
    hingeMid.add(eyeNormalB.multiply(-Z_EPSILON)), // was +Z_EPSILON
    outwardB, eyeV, eyeNormalB,
    halfWidth, halfH,
    0.5f, 1.0f,
    false,
    eyeR, eyeG, eyeB, eyeA,
    light
);


        // =========================================================
        // WINGS
        // - still flap
        // - texture V flipped
        // - PIVOT FIX: pivot at the *attachment point* on the body edge,
        //   not at the middle of the wing sprite.
        //
        // We compute a per-wing attach point using the body's half-width
        // along the wing's "outward" direction (wingSpriteU).
        // =========================================================
        // =========================================================
// =========================================================
// WINGS (top-left pivot)
// - pivot at TOP-LEFT corner (attachment point)
// - remove 1px gap by moving spine attach forward and slight overlap into body
// - one wing red for debugging
// - flap in UNROLLED frame then apply roll
// =========================================================
// =========================================================
// WINGS (top-left pivot at attachment corner)
// =========================================================
float wingW = DEER_EYE_A2.sourcePixelSize.x * pxToWorld;
float wingH = DEER_EYE_A2.sourcePixelSize.y * pxToWorld;

// seam fixes
final float WING_GAP_FIX_PX = 1.0f;   // pull hinge 1px closer along forward
final float WING_INSET_PX   = 0.75f;  // overlap into body along -wingSpriteU

// hinge point on the body "spine" (moved 1px toward body)
Vec3d hingeBase = pos.subtract(forward.multiply((RW_WING_OFFSET_PX - WING_GAP_FIX_PX) * pxToWorld));

// flap amplitude (UNROLLED so roll doesn't distort)
float diff0 = (float) forward.distanceTo(lazyInPlane0);
float ampDeg = lerpMapPow(diff0, 0.06f, 0.7f, 10f, 45f, 2f) * num;
double ampRad = Math.toRadians(ampDeg);

// UNROLLED frame
Vec3d planeNormal0 = right0;
Vec3d baseWingDir0 = planeNormal0.crossProduct(lazyInPlane0).normalize();

for (int j = 0; j < 2; j++) {
    double side = (j == 0) ? -1.0 : 1.0;

    // RW-style sign flip (keeps left/right wing “opposed”)
    float scaleY = (j == 0) ? (-num) : (num);
    if (Math.abs(scaleY) < 0.001f) continue;

    // flap in UNROLLED
    Vec3d wingU0 = rotateAroundAxis(baseWingDir0, planeNormal0, side * ampRad).normalize();
    Vec3d wingV0 = planeNormal0.crossProduct(wingU0).normalize();

    // apply roll rigidly
    Vec3d wingU = rotateAroundAxis(wingU0, forward, rollRad).normalize();
    Vec3d wingV = rotateAroundAxis(wingV0, forward, rollRad).normalize();
    Vec3d planeNormal = rotateAroundAxis(planeNormal0, forward, rollRad).normalize();

    // Sprite axes:
    // width goes outward from body
    Vec3d wingSpriteU = wingV.normalize();

    // "down" direction for top-left pivot:
    // start from your previous height axis and then apply sign from scaleY
    Vec3d vDown = wingU.multiply(-1).normalize(); // base down
    if (scaleY < 0) vDown = vDown.multiply(-1);   // flip down/up when scaleY negative

    float height = wingH * Math.abs(scaleY);

    // Top-left attachment point:
    // overlap slightly into the body along -U to kill the 1px seam
    Vec3d pivotTopLeft = hingeBase;

    // Wings use body color
    int wr = bodyR;
    int wg = bodyG;
    int wb = bodyB;

    emitPivotTopLeftQuad(
        wingVc, matrix,
        pivotTopLeft,
        wingSpriteU, vDown, planeNormal,
        wingW,
        height,
        false,  // flipU (mirror)
        false,  // flipV (your existing vertical flip)
        wr, wg, wb, 255,
        light
);
}
    }
/**
 * Pivot at TOP-LEFT corner.
 * Quad extends in +uDir for width and in -vDir for height (downwards).
 *
 * @param width full width (positive)
 * @param height full height (positive)
 */
private static void emitPivotTopLeftQuad(
        VertexConsumer vc,
        Matrix4f matrix,
        Vec3d pivotTopLeft,
        Vec3d uDirUnit,     // width direction (+u)
        Vec3d vDownUnit,    // DOWN direction (+vDown)
        Vec3d normalUnit,
        float width,
        float height,
        boolean flipU,
        boolean flipV,
        int r, int g, int b, int a,
        int light
) {
    Vec3d uFull = uDirUnit.multiply(width);
    Vec3d vFull = vDownUnit.multiply(height);

    Vec3d tl = pivotTopLeft;
    Vec3d tr = pivotTopLeft.add(uFull);
    Vec3d bl = pivotTopLeft.add(vFull);
    Vec3d br = pivotTopLeft.add(uFull).add(vFull);

    float nx = SHADE_NX, ny = SHADE_NY, nz = SHADE_NZ;

    float uLeft  = flipU ? 1f : 0f;
    float uRight = flipU ? 0f : 1f;

    float vTop    = flipV ? 1f : 0f;
    float vBottom = flipV ? 0f : 1f;

    putVertex(vc, matrix, bl, uLeft,  vBottom, r, g, b, a, light, nx, ny, nz);
    putVertex(vc, matrix, br, uRight, vBottom, r, g, b, a, light, nx, ny, nz);
    putVertex(vc, matrix, tr, uRight, vTop,    r, g, b, a, light, nx, ny, nz);
    putVertex(vc, matrix, tl, uLeft,  vTop,    r, g, b, a, light, nx, ny, nz);
}
    // Add this NEW helper next to your other geometry emitters:

/**
 * Like emitPivotLeftQuad, but allows custom U range (u0..u1) so you can render texture halves.
 * AnchorX=0 (hinge edge), AnchorY=0.5 (middle), so pivot is the edge midpoint.
 *
 * @param width full width (positive) extending in +uDirUnit from the hinge edge
 * @param halfH half height (can be negative to mirror like RW scale)
 * @param u0 left U in texture space
 * @param u1 right U in texture space
 */
private static void emitPivotLeftQuadUV(
        VertexConsumer vc,
        Matrix4f matrix,
        Vec3d pivotLeftMid,
        Vec3d uDirUnit,
        Vec3d vDirUnit,
        Vec3d normalUnit,
        float width,
        float halfH,
        float u0,
        float u1,
        boolean flipV,
        int r, int g, int b, int a,
        int light
) {
    Vec3d uFull = uDirUnit.multiply(width);
    Vec3d v = vDirUnit.multiply(halfH);

    Vec3d bl = pivotLeftMid.subtract(v);
    Vec3d br = pivotLeftMid.add(uFull).subtract(v);
    Vec3d tr = pivotLeftMid.add(uFull).add(v);
    Vec3d tl = pivotLeftMid.add(v);

    // Force identical shading across planes
    float nx = SHADE_NX, ny = SHADE_NY, nz = SHADE_NZ;

    float vBottom = flipV ? 0f : 1f;
    float vTop    = flipV ? 1f : 0f;

    putVertex(vc, matrix, bl, u0, vBottom, r, g, b, a, light, nx, ny, nz);
    putVertex(vc, matrix, br, u1, vBottom, r, g, b, a, light, nx, ny, nz);
    putVertex(vc, matrix, tr, u1, vTop,    r, g, b, a, light, nx, ny, nz);
    putVertex(vc, matrix, tl, u0, vTop,    r, g, b, a, light, nx, ny, nz);
}

    // =========================================================
    // Debug line emitter
    // =========================================================
    private static void emitDebugLine(VertexConsumer vc, Matrix4f matrix, Vec3d a, Vec3d b) {
        float nx = 0f, ny = 1f, nz = 0f;

        vc.vertex(matrix, (float) a.x, (float) a.y, (float) a.z)
                .color(0, 0, 255, 255)
                .normal(nx, ny, nz);

        vc.vertex(matrix, (float) b.x, (float) b.y, (float) b.z)
                .color(0, 0, 255, 255)
                .normal(nx, ny, nz);
    }

    // =========================================================
    // Geometry emitters
    // =========================================================

    private static void emitCenteredQuad(
            VertexConsumer vc,
            Matrix4f matrix,
            Vec3d center,
            Vec3d uDirUnit,
            Vec3d vDirUnit,
            Vec3d normalUnit,
            float halfW,
            float halfH,
            int r, int g, int b, int a,
            int light
    ) {
        emitCenteredQuadUV(vc, matrix, center, uDirUnit, vDirUnit, normalUnit,
                halfW, halfH,
                0f, 1f, 0f, 1f,
                r, g, b, a, light);
    }

    /**
     * Centered quad with custom UV bounds.
     * u: [u0..u1], v: [v0..v1]
     *
     * Keep original “bl/br v=1, tr/tl v=0” convention.
     */
    private static void emitCenteredQuadUV(
            VertexConsumer vc,
            Matrix4f matrix,
            Vec3d center,
            Vec3d uDirUnit,
            Vec3d vDirUnit,
            Vec3d normalUnit,
            float halfW,
            float halfH,
            float u0, float u1,
            float v0, float v1,
            int r, int g, int b, int a,
            int light
    ) {
        Vec3d u = uDirUnit.multiply(halfW);
        Vec3d v = vDirUnit.multiply(halfH);

        Vec3d bl = center.subtract(u).subtract(v);
        Vec3d br = center.add(u).subtract(v);
        Vec3d tr = center.add(u).add(v);
        Vec3d tl = center.subtract(u).add(v);

        // Force identical shading across planes
        float nx = SHADE_NX, ny = SHADE_NY, nz = SHADE_NZ;

        putVertex(vc, matrix, bl, u0, v1, r, g, b, a, light, nx, ny, nz);
        putVertex(vc, matrix, br, u1, v1, r, g, b, a, light, nx, ny, nz);
        putVertex(vc, matrix, tr, u1, v0, r, g, b, a, light, nx, ny, nz);
        putVertex(vc, matrix, tl, u0, v0, r, g, b, a, light, nx, ny, nz);
    }

    private static void emitPivotLeftQuad(
            VertexConsumer vc,
            Matrix4f matrix,
            Vec3d pivotLeftMid,
            Vec3d uDirUnit,
            Vec3d vDirUnit,
            Vec3d normalUnit,
            float width,
            float halfH,
            boolean flipV,
            int r, int g, int b, int a,
            int light
    ) {
        Vec3d uFull = uDirUnit.multiply(width);
        Vec3d v = vDirUnit.multiply(halfH);

        Vec3d bl = pivotLeftMid.subtract(v);
        Vec3d br = pivotLeftMid.add(uFull).subtract(v);
        Vec3d tr = pivotLeftMid.add(uFull).add(v);
        Vec3d tl = pivotLeftMid.add(v);

        float nx = SHADE_NX, ny = SHADE_NY, nz = SHADE_NZ;

        float vBottom = flipV ? 0f : 1f;
        float vTop    = flipV ? 1f : 0f;

        putVertex(vc, matrix, bl, 0f, vBottom, r, g, b, a, light, nx, ny, nz);
        putVertex(vc, matrix, br, 1f, vBottom, r, g, b, a, light, nx, ny, nz);
        putVertex(vc, matrix, tr, 1f, vTop,    r, g, b, a, light, nx, ny, nz);
        putVertex(vc, matrix, tl, 0f, vTop,    r, g, b, a, light, nx, ny, nz);
    }

    private static void putVertex(
            VertexConsumer vc,
            Matrix4f matrix,
            Vec3d p,
            float u,
            float v,
            int r,
            int g,
            int b,
            int a,
            int light,
            float nx,
            float ny,
            float nz
    ) {
        vc.vertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, a)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);
    }

    // =========================================================
    // Math helpers
    // =========================================================

    private static Vec3d lerp(Vec3d a, Vec3d b, float t) {
        return new Vec3d(
                MathHelper.lerp(t, a.x, b.x),
                MathHelper.lerp(t, a.y, b.y),
                MathHelper.lerp(t, a.z, b.z)
        );
    }

    private static Vec3d slerpUnitSafe(Vec3d a, Vec3d b, float t) {
        if (a == null || b == null) return (a != null) ? a : (b != null ? b : Vec3d.ZERO);

        double la = a.length();
        double lb = b.length();
        if (la < 1e-10 || lb < 1e-10) return (la > lb) ? a : b;

        Vec3d v0 = a.multiply(1.0 / la);
        Vec3d v1 = b.multiply(1.0 / lb);

        double dot = MathHelper.clamp(v0.dotProduct(v1), -1.0, 1.0);

        if (dot > 0.9995) {
            Vec3d v = lerp(v0, v1, t);
            double lv = v.length();
            return (lv > 1e-10) ? v.multiply(1.0 / lv) : v0;
        }

        if (dot < -0.9995) {
            Vec3d ortho = Math.abs(v0.x) < 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            Vec3d axis = v0.crossProduct(ortho);
            if (axis.lengthSquared() < 1e-10) axis = v0.crossProduct(new Vec3d(0, 0, 1));
            axis = axis.normalize();
            return rotateAroundAxis(v0, axis, Math.PI * t).normalize();
        }

        double omega = Math.acos(dot);
        double sinOmega = Math.sin(omega);

        double s0 = Math.sin((1.0 - t) * omega) / sinOmega;
        double s1 = Math.sin(t * omega) / sinOmega;

        return v0.multiply(s0).add(v1.multiply(s1));
    }

    private static Vec3d projectIntoPlane(Vec3d v, Vec3d planeNormalUnit) {
        return v.subtract(planeNormalUnit.multiply(v.dotProduct(planeNormalUnit)));
    }

    private static float lerpMapPow(float v, float inMin, float inMax, float outMin, float outMax, float pow) {
        float t = inverseLerp(inMin, inMax, v);
        t = MathHelper.clamp(t, 0f, 1f);
        if (pow != 1f) t = (float) Math.pow(t, pow);
        return MathHelper.lerp(t, outMin, outMax);
    }

    private static float inverseLerp(float a, float b, float v) {
        if (Math.abs(b - a) < 1e-8f) return 0f;
        return (v - a) / (b - a);
    }

    private static float computePxToWorld(FAtlasElement bodyElem, float targetMaxDimBlocks) {
        float pxW = bodyElem.sourcePixelSize.x;
        float pxH = bodyElem.sourcePixelSize.y;
        float maxPx = Math.max(pxW, pxH);
        if (maxPx < 1e-5f) return 0.01f;
        return targetMaxDimBlocks / maxPx;
    }

    private static Vec3d rotateAroundAxis(Vec3d v, Vec3d axisUnit, double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);

        Vec3d term1 = v.multiply(cos);
        Vec3d term2 = axisUnit.crossProduct(v).multiply(sin);
        Vec3d term3 = axisUnit.multiply(axisUnit.dotProduct(v) * (1.0 - cos));

        return term1.add(term2).add(term3);
    }

    // ======================================================================
    // Rain World accurate color mapping (SSOracleSwarmer.DrawSprites)
    // ======================================================================

    /**
     * Matches RW body color mapping:
     *  - normal: HSL2RGB( hueNormal(colorX), 1, 0.5 + 0.5*colorY )
     *  - dark:   HSL2RGB( hueDark(colorX),   1, Lerp(0.1,0.5,colorY) )
     */
    private static float[] calculateColorRW_Body(float colorX, float colorY, boolean dark) {
        float h;
        float s = 1.0f;
        float l;

        if (!dark) {
            h = rwHueNormal(colorX);
            l = 0.5f + 0.5f * colorY;
        } else {
            h = rwHueDark(colorX);
            l = lerpF(0.1f, 0.5f, colorY);
        }
        return hslToRgb(h, s, l);
    }

    /**
     * Matches RW sprite[4] color mapping:
     *  - normal: HSL2RGB( hueNormal(colorX), 1-colorY, Lerp(0.8+0.2*InverseLerp(0.4,0.1,colorX), 0.35, colorY^2) )
     *  - dark:   HSL2RGB( hueDark(colorX),   1,        Lerp(0.75,0.9,colorY) )
     */
    private static float[] calculateColorRW_Sprite4(float colorX, float colorY, boolean dark) {
        float h;
        float s;
        float l;

        if (!dark) {
            h = rwHueNormal(colorX);
            s = 1.0f - colorY;

            float a = 0.8f + 0.2f * inverseLerpClamped(0.4f, 0.1f, colorX);
            float t = colorY * colorY;
            l = lerpF(a, 0.35f, t);
        } else {
            h = rwHueDark(colorX);
            s = 1.0f;
            l = lerpF(0.75f, 0.9f, colorY);
        }

        return hslToRgb(h, s, l);
    }

    private static float rwHueNormal(float colorX) {
        if (colorX < 0.5f) {
            return lerpMap(colorX, 0.0f, 0.5f, 4f / 9f, 2f / 3f);
        }
        return lerpMap(colorX, 0.5f, 1.0f, 2f / 3f, 0.99722224f);
    }

    private static float rwHueDark(float colorX) {
        if (colorX <= 0.5f) return 2f / 3f;
        return lerpMap(colorX, 0.5f, 1.0f, 2f / 3f, 0.99722224f);
    }

    // ======================================================================
    // HSL utilities
    // ======================================================================

    private static float[] hslToRgb(float h, float s, float l) {
        float r, g, b;

        if (s == 0) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hueToRgb(p, q, h + 1f / 3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f / 3f);
        }

        return new float[]{r, g, b};
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6f) return p + (q - p) * 6 * t;
        if (t < 1f / 2f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6;
        return p;
    }

    private static float lerpF(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float inverseLerpClamped(float a, float b, float v) {
        if (Math.abs(b - a) < 1e-8f) return 0f;
        float t = (v - a) / (b - a);
        return MathHelper.clamp(t, 0f, 1f);
    }

    private static float lerpMap(float v, float inMin, float inMax, float outMin, float outMax) {
        float t = inverseLerpClamped(inMin, inMax, v);
        return lerpF(outMin, outMax, t);
    }
}