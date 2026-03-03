package dev.fouriis.karmagate.entity.garbworm;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasManager;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Renderer for the Garbage Worm — exact port of C# GarbageWormGraphics.
 *
 * Matches C# GarbageWormGraphics.DrawSprites:
 * <ul>
 *   <li>Sequential segment processing with per-segment local sine wave</li>
 *   <li>Body mesh with C#-matching vertex placement (num7 overlap offset)</li>
 *   <li>Retraction blending toward root with downward displacement</li>
 *   <li>Head sprite (dark, palette.blackColor)</li>
 *   <li>Eyes (white normally, red when angry)</li>
 * </ul>
 *
 * Chain simulation replaces C# Tentacle physics for body arching.
 * Per-entity animation state (sinWave, swallowArray) is cached client-side.
 */
public class GarbageWormRenderer extends EntityRenderer<GarbageWormEntity> {

    // ── Atlas sprites ─────────────────────────────────────────────────
    private static FAtlasManager atlasManager;
    private static FAtlasElement WORM_EYE;
    private static FAtlasElement WORM_HEAD;

    // ── Rendering constants ───────────────────────────────────────────
    /** Number of body segments (C#: (int)(15 * Lerp(bodySize,1,0.5)), =15 at bodySize=1). */
    private static final int NUM_SEGMENTS = 15;

    /** Tentacle total length in blocks for chain sim (must match entity). */
    private static final float CHAIN_LENGTH = 8.0f;

    /**
     * Pixel-to-block conversion factor.
     * Rain World: 20 pixels per tile. Entity uses 1 tile = 1 block.
     * So 1 pixel = 1/20 block = 0.05 blocks.
     */
    private static final float PX = 1f / 20f;

    /** Head sprite world size in blocks. */
    private static final float HEAD_SIZE_BLOCKS = 0.35f;

    /** Eye sprite world size in blocks. */
    private static final float EYE_SIZE_BLOCKS = 0.12f;

    // Body + head color: near-black (C#: palette.blackColor)
    private static final int BODY_R = 15, BODY_G = 15, BODY_B = 15, BODY_A = 255;

    // Fixed normal for flat shading (prevents one-side-gray artifacts)
    private static final float NX = 0f, NY = 1f, NZ = 0f;

    // ── Per-entity client state ───────────────────────────────────────
    private final Map<Integer, WormAnimState> animStates = new HashMap<>();

    /**
     * Animation state cached per worm entity ID.
     * Mirrors C# GarbageWormGraphics fields + chain simulation for body arching.
     */
    private static class WormAnimState {
        // C# GarbageWormGraphics fields
        float sinWave = 0f;
        float numberOfWavesOnBody = 1.8f;
        float sinSpeed = 1f / 60f;
        float lastExtended = 1f;
        float extended = 1f;
        float[] swallowArray = new float[NUM_SEGMENTS];
        int lastAge = -1;

        // Chain simulation (replaces C# Tentacle.tChunks physics)
        Vec3d[] chainPos = new Vec3d[NUM_SEGMENTS];
        Vec3d[] lastChainPos = new Vec3d[NUM_SEGMENTS];
        Vec3d[] chainVel = new Vec3d[NUM_SEGMENTS];
        boolean chainInitialized = false;
        boolean wasRetracted = false;

        WormAnimState() {
            for (int i = 0; i < NUM_SEGMENTS; i++) {
                chainPos[i] = Vec3d.ZERO;
                lastChainPos[i] = Vec3d.ZERO;
                chainVel[i] = Vec3d.ZERO;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    public GarbageWormRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    public Identifier getTexture(GarbageWormEntity entity) {
        return Identifier.ofVanilla("textures/misc/white.png");
    }

    // ════════════════════════════════════════════════════════════════════
    //  render()  —  C# GarbageWormGraphics.DrawSprites
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void render(GarbageWormEntity entity, float yaw, float tickDelta,
                       MatrixStack matrices, VertexConsumerProvider consumers, int light) {

        // ── Lazy atlas init ──
        if (atlasManager == null) {
            atlasManager = LibrainworldmcClient.getAtlasManager();
            WORM_EYE  = atlasManager.getElementWithName("WormEye");
            WORM_HEAD = atlasManager.getElementWithName("WormHead");
        }
        if (WORM_EYE == null || WORM_HEAD == null) return;

        // ── Read entity state ──
        Vec3d rootPos  = entity.getRootPos();
        float ext      = entity.getExtended();
        float stress   = entity.getStress();
        boolean angry  = entity.isShowAngry();
        int atkCtr     = entity.getAttackCtr();
        float bodySize = entity.getBodySizeValue();
        if (bodySize < 0.01f) bodySize = 1f;

        // Head pos with tick interpolation
        Vec3d headPos = new Vec3d(
                MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()),
                MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()),
                MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ())
        );

        // ── Update animation state ──
        WormAnimState anim = animStates.computeIfAbsent(entity.getId(), k -> new WormAnimState());
        if (anim.lastAge != entity.age) {
            anim.lastAge = entity.age;
            tickAnimState(anim, ext, stress, atkCtr);
            tickChainSim(anim, rootPos,
                    new Vec3d(entity.getX(), entity.getY(), entity.getZ()), ext, bodySize);
        }

        // C#: num = Lerp(lastExtended, extended, timeStacker)
        float num = MathHelper.lerp(tickDelta, anim.lastExtended, anim.extended);
        // Skip rendering when fully retracted
        if (num <= 0f) return;

        // ── Camera ──
        Camera camera = this.dispatcher.camera;
        Vec3d cameraPos = camera.getPos();

        // Matrix: undo entity offset, work in camera-relative absolute space
        matrices.push();
        Vec3d entityCamRel = new Vec3d(
                MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()) - cameraPos.x,
                MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()) - cameraPos.y,
                MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ()) - cameraPos.z
        );
        matrices.translate(-entityCamRel.x, -entityCamRel.y, -entityCamRel.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // ── Render layers ──
        VertexConsumer bodyVc = consumers.getBuffer(RenderLayer.getEntityTranslucent(WORM_HEAD.textureIdentifier));
        VertexConsumer eyeVc  = consumers.getBuffer(RenderLayer.getEntityTranslucent(WORM_EYE.textureIdentifier));
        int fullLight = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        // ═══════════════════════════════════════════════════════════════
        //  C# DrawSprites — sequential segment processing
        //
        //  Key: each segment's sine wave uses the perpendicular of the
        //  LOCAL direction from the previous processed position ('vector')
        //  to the current chunk position ('a'). After sine modification,
        //  'a' becomes the new 'vector' for the next iteration.
        // ═══════════════════════════════════════════════════════════════

        // C#: vector = bodyChunks[1].pos + new Vector2(0f, -30f - 100f * (1f - num))
        // bodyChunks[1].pos = rootPos.  Offsets in pixels → blocks via PX.
        Vec3d startPos = rootPos.add(0, (-30.0 - 100.0 * (1.0 - num)) * PX, 0);
        Vec3d prevPosCR = startPos.subtract(cameraPos);

        // C#: num2 = 4f  (initial prev radius, in pixels)
        float prevRadius = 4f * PX;

        // C#: stretchedRad = 2 * Lerp(bodySize, 1, 0.5)  (base chunk radius, constant for all chunks)
        float baseRadius = 2f * MathHelper.lerp(0.5f, bodySize, 1f) * PX;

        // Interpolated sinWave for smooth rendering between ticks
        float interpSinWave = MathHelper.lerp(tickDelta,
                anim.sinWave - anim.sinSpeed, anim.sinWave);

        // Head segment info (filled during last iteration)
        Vec3d headSegPos = null;
        Vec3d headSegDir = null;
        Vec3d headSegPerp = null;

        for (int i = 0; i < NUM_SEGMENTS; i++) {
            // C#: a = Lerp(tChunks[i].lastPos, tChunks[i].pos, timeStacker)
            Vec3d chunkPos = lerpVec(anim.lastChainPos[i], anim.chainPos[i], tickDelta);

            // C#: num3 = (float)i / (float)(tChunks.Length - 1)
            float num3 = (float) i / (float) (NUM_SEGMENTS - 1);

            // ── Retraction factor ──
            // C#: num4 = Pow(Max(1 - num3 - num, 0), 1.5)
            float num4 = (float) Math.pow(Math.max(1f - num3 - num, 0f), 1.5f);
            if (num < 0.2f) {
                // C#: num4 = Min(1, num4 + InverseLerp(0.2, 0, num))
                num4 = Math.min(1f, num4 + inverseLerp(0.2f, 0f, num));
            }

            // C#: a = Lerp(a, bodyChunks[1].pos, num4) + new Vector2(0f, -100f * Pow(num4, 0.5f))
            chunkPos = lerpVec(chunkPos, rootPos, num4);
            chunkPos = chunkPos.add(0, -100.0 * Math.pow(num4, 0.5) * PX, 0);

            // Convert to camera-relative
            Vec3d aCR = chunkPos.subtract(cameraPos);

            // ── Sine wave ──
            // C#: perpendicular of (a - vector).normalized  [2D → camera-facing in 3D]
            Vec3d localDir = aCR.subtract(prevPosCR);
            if (localDir.lengthSquared() < 1e-10) localDir = new Vec3d(0, 1, 0);
            Vec3d localDirNorm = localDir.normalize();

            // Camera-facing perpendicular (replaces C# Custom.PerpendicularVector in 2D)
            Vec3d toCam = aCR.multiply(-1); // in CR space, camera is at origin
            if (toCam.lengthSquared() < 1e-8) toCam = new Vec3d(0, 0, 1);
            Vec3d sinePerp = localDirNorm.crossProduct(toCam.normalize());
            if (sinePerp.lengthSquared() < 1e-8) sinePerp = localDirNorm.crossProduct(new Vec3d(1, 0, 0));
            if (sinePerp.lengthSquared() > 1e-8) sinePerp = sinePerp.normalize();
            else sinePerp = new Vec3d(1, 0, 0);

            // C#: num5 = Sin((Lerp(sinWave-sinSpeed, sinWave, ts) + num3 * numberOfWavesOnBody) * PI * 2)
            float num5 = (float) Math.sin(
                    (interpSinWave + num3 * anim.numberOfWavesOnBody) * Math.PI * 2.0);

            // C#: a += PerpendicularVector(...) * num5 * 11f * Pow(Max(0, Sin(num3*PI)), 0.75) * num
            float sineAmp = 11f * PX * (float) Math.pow(
                    Math.max(0f, (float) Math.sin(num3 * Math.PI)), 0.75f) * num;
            aCR = aCR.add(sinePerp.multiply(num5 * sineAmp));

            // ── Post-sine direction & perpendicular (for mesh) ──
            // C#: normalized = (a - vector).normalized
            Vec3d segDir = aCR.subtract(prevPosCR);
            if (segDir.lengthSquared() < 1e-10) segDir = new Vec3d(0, 1, 0);
            Vec3d segDirNorm = segDir.normalize();

            // C#: vector2 = PerpendicularVector(normalized)  [camera-facing in 3D]
            Vec3d toCam2 = aCR.multiply(-1);
            if (toCam2.lengthSquared() < 1e-8) toCam2 = new Vec3d(0, 0, 1);
            Vec3d meshPerp = segDirNorm.crossProduct(toCam2.normalize());
            if (meshPerp.lengthSquared() < 1e-8) meshPerp = segDirNorm.crossProduct(new Vec3d(1, 0, 0));
            if (meshPerp.lengthSquared() > 1e-8) meshPerp = meshPerp.normalize();
            else meshPerp = new Vec3d(1, 0, 0);

            // ── Head/eyes info for last segment ──
            if (i == NUM_SEGMENTS - 1) {
                headSegPos = aCR;
                headSegDir = segDirNorm;
                headSegPerp = meshPerp;
            }

            // ── Body mesh vertices (C#: TriangleMesh quad strip) ──

            // C#: num7 = Vector2.Distance(a, vector) / 7f
            float segDist = (float) segDir.length();
            float num7 = segDist / 7.0f;

            // C#: num8 = worm.tentacle.tChunks[i].stretchedRad + swallowArray[i] * 5f
            float num8 = baseRadius + anim.swallowArray[i] * 5f * PX;

            // C# vertex placement:
            // v0: vector - vector2 * (num8+num2)*0.5 + normalized * num7
            // v1: vector + vector2 * (num8+num2)*0.5 + normalized * num7
            // v2: a      - vector2 * num8            - normalized * num7
            // v3: a      + vector2 * num8            - normalized * num7
            float avgWidth = (num8 + prevRadius) * 0.5f;
            Vec3d v0 = prevPosCR.subtract(meshPerp.multiply(avgWidth)).add(segDirNorm.multiply(num7));
            Vec3d v1 = prevPosCR.add(meshPerp.multiply(avgWidth)).add(segDirNorm.multiply(num7));
            Vec3d v2 = aCR.subtract(meshPerp.multiply(num8)).subtract(segDirNorm.multiply(num7));
            Vec3d v3 = aCR.add(meshPerp.multiply(num8)).subtract(segDirNorm.multiply(num7));

            emitQuad(bodyVc, matrix, v0, v1, v3, v2,
                    0f, 1f, 1f, 0f,
                    BODY_R, BODY_G, BODY_B, BODY_A, fullLight);

            // C#: num2 = num8; vector = a;
            prevRadius = num8;
            prevPosCR = aCR;
        }

        // ═══════════════════════════════════════════════════════════════
        //  Head sprite  (C#: sprites[2] "WormHead", color = palette.blackColor)
        //  C#: sLeaser.sprites[2].scale = Lerp(bodySize, 1, 0.5)
        // ═══════════════════════════════════════════════════════════════
        if (headSegPos != null) {
            float headScale = HEAD_SIZE_BLOCKS * MathHelper.lerp(0.5f, bodySize, 1f);
            float halfSize = headScale * 0.5f;

            Vec3d hbl = headSegPos.subtract(headSegPerp.multiply(halfSize)).subtract(headSegDir.multiply(halfSize));
            Vec3d hbr = headSegPos.add(headSegPerp.multiply(halfSize)).subtract(headSegDir.multiply(halfSize));
            Vec3d htr = headSegPos.add(headSegPerp.multiply(halfSize)).add(headSegDir.multiply(halfSize));
            Vec3d htl = headSegPos.subtract(headSegPerp.multiply(halfSize)).add(headSegDir.multiply(halfSize));

            emitQuad(bodyVc, matrix, hbl, hbr, htr, htl,
                    0f, 1f, 1f, 0f,
                    BODY_R, BODY_G, BODY_B, BODY_A, fullLight);

            // ═══════════════════════════════════════════════════════════
            //  Eyes  (C#: sprites[0] & sprites[3] "WormEye")
            //  Color: white normally, red (1,0,0) when angry.
            //
            //  C#: eye pos = a + normalized*5*bodySize ± vector2*3*Lerp(bodySize,1,0.75)*f
            //  The 'f' factor is a 2D perspective trick (cos of body angle). In 3D,
            //  camera provides natural perspective, so we use f=1.
            // ═══════════════════════════════════════════════════════════
            int eyeR = angry ? 255 : 255;
            int eyeG = angry ?   0 : 255;
            int eyeB = angry ?   0 : 255;

            // C#: forward offset = normalized * 5 * bodySize
            float fwdOff = 5f * bodySize * PX;
            // C#: lateral offset = vector2 * 3 * Lerp(bodySize, 1, 0.75) * f
            float latOff = 3f * MathHelper.lerp(0.75f, bodySize, 1f) * PX;
            float eyeHalf = EYE_SIZE_BLOCKS * 0.5f;

            Vec3d eyeCenter = headSegPos.add(headSegDir.multiply(fwdOff));

            // Left eye
            Vec3d eyeL = eyeCenter.add(headSegPerp.multiply(latOff));
            emitBillboardQuad(eyeVc, matrix, eyeL, headSegPerp, headSegDir, eyeHalf,
                    eyeR, eyeG, eyeB, 255, fullLight);

            // Right eye
            Vec3d eyeRPos = eyeCenter.subtract(headSegPerp.multiply(latOff));
            emitBillboardQuad(eyeVc, matrix, eyeRPos, headSegPerp, headSegDir, eyeHalf,
                    eyeR, eyeG, eyeB, 255, fullLight);
        }

        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, consumers, light);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Animation tick  (C#: GarbageWormGraphics.Update)
    // ════════════════════════════════════════════════════════════════════

    private void tickAnimState(WormAnimState anim, float ext, float stress, int atkCtr) {
        anim.lastExtended = anim.extended;
        anim.extended = ext;

        // Track retraction so chain sim reinitialises on re-emergence
        if (ext <= 0f) {
            anim.wasRetracted = true;
        }

        // C#: numberOfWavesOnBody & sinSpeed adjust based on stress and attack state
        if (atkCtr < 20) {
            // C#: numberOfWavesOnBody = Lerp(numberOfWavesOnBody, Lerp(1.8, 3.4, stress), 0.1)
            anim.numberOfWavesOnBody = MathHelper.lerp(0.1f, anim.numberOfWavesOnBody,
                    MathHelper.lerp(stress, 1.8f, 3.4f));
            // C#: sinSpeed = Lerp(sinSpeed, Lerp(1/60, 0.05, stress), 0.05)
            anim.sinSpeed = MathHelper.lerp(0.05f, anim.sinSpeed,
                    MathHelper.lerp(stress, 1f / 60f, 0.05f));
        } else {
            // C#: numberOfWavesOnBody = Lerp(numberOfWavesOnBody, 5, 0.01)
            anim.numberOfWavesOnBody = MathHelper.lerp(0.01f, anim.numberOfWavesOnBody, 5f);
            // C#: sinSpeed = Lerp(sinSpeed, 0.05, 0.1)
            anim.sinSpeed = MathHelper.lerp(0.1f, anim.sinSpeed, 0.05f);
        }

        anim.sinWave += anim.sinSpeed;
        if (anim.sinWave > 1f) anim.sinWave -= 1f;

        // C#: swallow bulge (attackCounter > 40 && attackCounter < 190)
        if (atkCtr > 40 && atkCtr < 190 && Math.random() < 1.0 / 30.0) {
            anim.swallowArray[anim.swallowArray.length - 1] =
                    (float) Math.pow(Math.random(), 0.5);
        }
        // C#: propagate swallow from tip toward root (1/3 chance per tick)
        if (Math.random() < 1.0 / 3.0) {
            for (int i = 0; i < anim.swallowArray.length - 1; i++) {
                anim.swallowArray[i] = MathHelper.lerp(0.7f, anim.swallowArray[i],
                        anim.swallowArray[i + 1]);
            }
        }
        // C#: decay last element toward 0
        anim.swallowArray[anim.swallowArray.length - 1] =
                MathHelper.lerp(0.7f, anim.swallowArray[anim.swallowArray.length - 1], 0f);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Chain simulation  (replaces C# Tentacle physics for body arching)
    //
    //  Simulates a chain of connected segments from root to head.
    //  Gravity creates natural arching/drooping. Distance constraints
    //  prevent stretching. Called once per game tick; positions
    //  interpolated between ticks for smooth rendering.
    // ════════════════════════════════════════════════════════════════════

    private void tickChainSim(WormAnimState anim, Vec3d rootPos, Vec3d headPos,
                          float extended, float bodySize) {
    float totalLength = CHAIN_LENGTH * bodySize * Math.max(extended, 0.1f);
    float segLength = totalLength / (NUM_SEGMENTS - 1);

    // Copy current → last for interpolation
    for (int i = 0; i < NUM_SEGMENTS; i++) {
        anim.lastChainPos[i] = anim.chainPos[i];
    }

    // (Re)initialise when first created or after retraction
    if (!anim.chainInitialized || anim.wasRetracted) {
        for (int i = 0; i < NUM_SEGMENTS; i++) {
            float t = (float) i / (NUM_SEGMENTS - 1);
            anim.chainPos[i] = lerpVec(rootPos, headPos, t);
            anim.lastChainPos[i] = anim.chainPos[i];
            anim.chainVel[i] = Vec3d.ZERO;
        }
        anim.chainInitialized = true;
        anim.wasRetracted = false;
        return;
    }

    // ── Apply forces and integrate ──
    for (int i = 1; i < NUM_SEGMENTS; i++) {
        float t = (float) i / (NUM_SEGMENTS - 1);

        // ✅ WEIGHTLESS: remove gravity entirely
        // (delete / comment out your segGravity add)

        // Keep the nice smoothing stiffness (this is not "weight"; it's shape)
        if (i >= 2) {
            Vec3d toBack = anim.chainPos[i - 2].subtract(anim.chainPos[i]);
            double dist = toBack.length();
            if (dist > 0.001) {
                float strength = MathHelper.lerp(t, 0.1f, 0.025f);
                anim.chainVel[i] = anim.chainVel[i].add(toBack.normalize().multiply(strength));
            }
        }

        // ✅ Slightly stronger pull toward ideal line so it doesn't “float” off
        Vec3d idealPos = lerpVec(rootPos, headPos, t);
        Vec3d toIdeal = idealPos.subtract(anim.chainPos[i]);
        anim.chainVel[i] = anim.chainVel[i].add(toIdeal.multiply(0.008)); // was 0.002

        // Damping (fine to keep)
        float damping = MathHelper.lerp((float) Math.pow(t, 0.5f), 0.80f, 0.95f);
        anim.chainVel[i] = anim.chainVel[i].multiply(damping);

        // Integrate
        anim.chainPos[i] = anim.chainPos[i].add(anim.chainVel[i]);
    }

    // ── Pin root ──
    anim.chainPos[0] = rootPos;
    anim.chainVel[0] = Vec3d.ZERO;

    // ── Pull last segment toward head (tracks head closely) ──
    Vec3d pullDir = headPos.subtract(anim.chainPos[NUM_SEGMENTS - 1]);
    anim.chainPos[NUM_SEGMENTS - 1] = anim.chainPos[NUM_SEGMENTS - 1].add(pullDir.multiply(0.7));
    anim.chainVel[NUM_SEGMENTS - 1] = anim.chainVel[NUM_SEGMENTS - 1].add(pullDir.multiply(0.15));

    // ── Distance constraints (Jakobsen-style, enforce exact length) ──
    // ✅ IMPORTANT: correct BOTH stretching and compression
    for (int iter = 0; iter < 6; iter++) { // slightly more iterations = stiffer
        anim.chainPos[0] = rootPos;

        for (int i = 0; i < NUM_SEGMENTS - 1; i++) {
            Vec3d p1 = anim.chainPos[i];
            Vec3d p2 = anim.chainPos[i + 1];

            Vec3d delta = p2.subtract(p1);
            double dist = delta.length();
            if (dist < 1e-6) continue;

            // amount to move along delta so that dist becomes segLength
            double diff = (dist - segLength) / dist;

            // split correction: root is pinned, others share
            Vec3d correction = delta.multiply(0.5 * diff);

            if (i > 0) {
                anim.chainPos[i] = anim.chainPos[i].add(correction);
            }
            anim.chainPos[i + 1] = anim.chainPos[i + 1].subtract(correction);
        }

        // re-pin root each iter
        anim.chainPos[0] = rootPos;
    }
}

    // ════════════════════════════════════════════════════════════════════
    //  Geometry helpers
    // ════════════════════════════════════════════════════════════════════

    private static void emitQuad(VertexConsumer vc, Matrix4f matrix,
                                 Vec3d bl, Vec3d br, Vec3d tr, Vec3d tl,
                                 float u0, float u1, float v0, float v1,
                                 int r, int g, int b, int a, int light) {
        putVertex(vc, matrix, bl, u0, v1, r, g, b, a, light);
        putVertex(vc, matrix, br, u1, v1, r, g, b, a, light);
        putVertex(vc, matrix, tr, u1, v0, r, g, b, a, light);
        putVertex(vc, matrix, tl, u0, v0, r, g, b, a, light);
    }

    private static void emitBillboardQuad(VertexConsumer vc, Matrix4f matrix,
                                          Vec3d center, Vec3d right, Vec3d up, float halfSize,
                                          int r, int g, int b, int a, int light) {
        Vec3d bl = center.subtract(right.multiply(halfSize)).subtract(up.multiply(halfSize));
        Vec3d br = center.add(right.multiply(halfSize)).subtract(up.multiply(halfSize));
        Vec3d tr = center.add(right.multiply(halfSize)).add(up.multiply(halfSize));
        Vec3d tl = center.subtract(right.multiply(halfSize)).add(up.multiply(halfSize));

        putVertex(vc, matrix, bl, 0f, 1f, r, g, b, a, light);
        putVertex(vc, matrix, br, 1f, 1f, r, g, b, a, light);
        putVertex(vc, matrix, tr, 1f, 0f, r, g, b, a, light);
        putVertex(vc, matrix, tl, 0f, 0f, r, g, b, a, light);
    }

    private static void putVertex(VertexConsumer vc, Matrix4f matrix, Vec3d p,
                                  float u, float v, int r, int g, int b, int a, int light) {
        vc.vertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, a)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(NX, NY, NZ);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Math helpers
    // ════════════════════════════════════════════════════════════════════

    private static Vec3d lerpVec(Vec3d a, Vec3d b, float t) {
        return new Vec3d(
                MathHelper.lerp(t, a.x, b.x),
                MathHelper.lerp(t, a.y, b.y),
                MathHelper.lerp(t, a.z, b.z)
        );
    }

    private static Vec3d lerpVec(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                MathHelper.lerp(t, a.x, b.x),
                MathHelper.lerp(t, a.y, b.y),
                MathHelper.lerp(t, a.z, b.z)
        );
    }

    private static float inverseLerp(float a, float b, float v) {
        if (Math.abs(b - a) < 1e-8f) return 0f;
        float t = (v - a) / (b - a);
        return MathHelper.clamp(t, 0f, 1f);
    }
}
