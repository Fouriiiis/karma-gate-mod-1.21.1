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
 * Renderer for the Garbage Worm.
 *
 * Matches C# GarbageWormGraphics:
 * <ul>
 *   <li>Body: dark tube from root to head with sinusoidal wave</li>
 *   <li>Head: "WormHead" sprite (dark, like palette.blackColor)</li>
 *   <li>Eyes: "WormEye" sprites (white, red when angry)</li>
 * </ul>
 *
 * Tentacle segment positions are computed in the renderer (camera-facing ribbon).
 * Per-entity animation state (sinWave, swallowArray) is cached.
 */
public class GarbageWormRenderer extends EntityRenderer<GarbageWormEntity> {

    // ── Atlas sprites ─────────────────────────────────────────────────
    private static FAtlasManager atlasManager;
    private static FAtlasElement WORM_EYE;
    private static FAtlasElement WORM_HEAD;

    // ── Rendering constants ───────────────────────────────────────────
    /** Number of segments for the body ribbon. C#: ~15 chunks. */
    private static final int NUM_SEGMENTS = 15;

    /**
     * C#: stretchedRad = 2 * Lerp(bodySize,1,0.5).
     * 2px / 20px = 0.10 blocks radius → total width 0.20 blocks at bodySize=1.
     */
    private static final float BASE_BODY_RADIUS_BLOCKS = 0.10f;

    /** C#: 11px sine amplitude / 20px = 0.55 blocks. */
    private static final float SINE_AMPLITUDE_BLOCKS = 0.55f;

    /** Head sprite world size (fits ~0.35 blocks). */
    private static final float HEAD_SIZE_BLOCKS = 0.35f;

    /** Eye sprite world size. */
    private static final float EYE_SIZE_BLOCKS = 0.12f;

    /** Eye forward offset from head centre (5px / 20). */
    private static final float EYE_FORWARD_OFFSET = 0.25f;

    /** Eye lateral offset from head centre (3px / 20). */
    private static final float EYE_LATERAL_OFFSET = 0.15f;

    // Body + head color: near-black (C#: palette.blackColor)
    private static final int BODY_R = 15, BODY_G = 15, BODY_B = 15, BODY_A = 255;

    // Normal used for flat shading (same trick as NeuronSwarmerRenderer)
    private static final float NX = 0f, NY = 1f, NZ = 0f;

    // ── Per-entity client state ───────────────────────────────────────
    private final Map<Integer, WormAnimState> animStates = new HashMap<>();

    /**
     * Animation state cached per worm entity ID.
     */
    private static class WormAnimState {
        float sinWave = 0f;
        float numberOfWavesOnBody = 1.8f;
        float sinSpeed = 1f / 60f;
        float lastExtended = 1f;
        float extended = 1f;
        float[] swallowArray = new float[NUM_SEGMENTS];
        int lastAge = -1;
    }

    // ════════════════════════════════════════════════════════════════════
    public GarbageWormRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    public Identifier getTexture(GarbageWormEntity entity) {
        // Not used directly — we fetch atlas textures in render().
        return Identifier.ofVanilla("textures/misc/white.png");
    }

    // ════════════════════════════════════════════════════════════════════
    //  render()
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

        // Skip rendering when fully retracted.
        if (ext <= 0f) return;

        // ── Update animation state ──
        WormAnimState anim = animStates.computeIfAbsent(entity.getId(), k -> new WormAnimState());
        if (anim.lastAge != entity.age) {
            anim.lastAge = entity.age;
            tickAnimState(anim, ext, stress, atkCtr);
        }

        float interpExtended = MathHelper.lerp(tickDelta, anim.lastExtended, anim.extended);

        // ── Camera position ──
        Camera camera = this.dispatcher.camera;
        Vec3d cameraPos = camera.getPos();

        // Push matrix, translate to world origin (entity renderer already offsets by entity pos)
        matrices.push();
        // EntityRenderer adds entity-pos offset; remove it so we work in absolute world coords.
        matrices.translate(
                -MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()),
                -MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()),
                -MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ())
        );
        // Now translate from world origin to camera-relative (since the matrix already has camera offset)
        // Actually, EntityRenderer's matrix already translates so that (0,0,0) = entity pos minus camera.
        // Let's undo that and go to real-world minus camera.
        // Simpler: revert to how NeuronSwarmerRenderer works.
        // We'll emit vertices in world space and subtract camera ourselves.
        // But the matrixStack already has view translation.
        // The cleanest way: work in camera-relative space.
        // headPos and rootPos are world-space. Subtract cameraPos for camera-relative.
        matrices.pop();

        // Re-push without entity offset — work in "absolute at render origin" space
        matrices.push();
        // EntityRenderer calls translate(lerpedX - cam.x, lerpedY - cam.y, lerpedZ - cam.z)
        // then multiplies the model-view matrix. So (0,0,0) = entity pos in camera space.
        // For absolute coords, we translate by -(entity cam-relative pos).
        Vec3d entityCamRel = new Vec3d(
                MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()) - cameraPos.x,
                MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()) - cameraPos.y,
                MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ()) - cameraPos.z
        );
        matrices.translate(-entityCamRel.x, -entityCamRel.y, -entityCamRel.z);

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // Make all positions camera-relative.
        Vec3d rootCR = rootPos.subtract(cameraPos);
        Vec3d headCR = headPos.subtract(cameraPos);

        // ── Render layers ──
        VertexConsumer bodyVc = consumers.getBuffer(RenderLayer.getEntityTranslucent(WORM_HEAD.textureIdentifier));
        VertexConsumer eyeVc  = consumers.getBuffer(RenderLayer.getEntityTranslucent(WORM_EYE.textureIdentifier));

        // Use fullbright for the body (worms glow slightly in dark garbage pits)
        int fullLight = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        // ═══════════════════════════════════════════════════════════════
        //  Compute segment positions  (C#: GarbageWormGraphics.DrawSprites)
        // ═══════════════════════════════════════════════════════════════

        // Start position: root offset downward based on extension.
        // C#: vector = bodyChunks[1].pos + (0f, -30f - 100f*(1-extended))
        //              in MC blocks: ÷20 → (0, -1.5 - 5*(1-ext), 0)
        Vec3d startCR = rootCR.add(0, -1.5 - 5.0 * (1.0 - interpExtended), 0);

        Vec3d[] segPositions = new Vec3d[NUM_SEGMENTS];
        float bodyRadius = BASE_BODY_RADIUS_BLOCKS * MathHelper.lerp(bodySize, 1f, 0.5f);

        for (int i = 0; i < NUM_SEGMENTS; i++) {
            float t = (float) i / (float) (NUM_SEGMENTS - 1); // 0 at root, 1 at head

            // Base interpolation from start (below root) to head.
            Vec3d basePos = lerpVec(startCR, headCR, t);

            // C#: retraction blending — segments near root stay at root when not fully extended.
            // num4 = pow(max(1 - t - extended, 0), 1.5)
            float retractFactor = (float) Math.pow(Math.max(1.0f - t - interpExtended, 0f), 1.5f);
            if (interpExtended < 0.2f) {
                retractFactor = Math.min(1f, retractFactor + inverseLerp(0.2f, 0f, interpExtended));
            }
            // Blend toward root position when retracting.
            basePos = lerpVec(basePos, rootCR, retractFactor);
            basePos = basePos.add(0, -5.0 * Math.pow(retractFactor, 0.5), 0);

            // C#: Sine wave perpendicular to body direction.
            // Compute a stable "horizontal perpendicular" for the sine wave.
            Vec3d bodyDir = headCR.subtract(startCR);
            if (bodyDir.lengthSquared() < 1e-8) bodyDir = new Vec3d(0, 1, 0);
            bodyDir = bodyDir.normalize();

            // Perpendicular in world space (roughly horizontal).
            Vec3d sinePerp = bodyDir.crossProduct(new Vec3d(0, 0, 1));
            if (sinePerp.lengthSquared() < 1e-8) {
                sinePerp = bodyDir.crossProduct(new Vec3d(1, 0, 0));
            }
            sinePerp = sinePerp.normalize();

            // C#: sin((sinWave + t*numberOfWavesOnBody) * PI * 2)
            float interpSinWave = MathHelper.lerp(tickDelta,
                    anim.sinWave - anim.sinSpeed, anim.sinWave);
            float sineValue = (float) Math.sin(
                    (interpSinWave + t * anim.numberOfWavesOnBody) * Math.PI * 2.0
            );

            // C#: amplitude modulation: pow(max(0, sin(t*PI)), 0.75) * extended
            float amplitudeEnvelope = (float) Math.pow(
                    Math.max(0f, (float) Math.sin(t * Math.PI)), 0.75f
            ) * interpExtended;

            float amplitude = SINE_AMPLITUDE_BLOCKS * bodySize * amplitudeEnvelope;

            basePos = basePos.add(sinePerp.multiply(sineValue * amplitude));

            segPositions[i] = basePos;
        }

        // ═══════════════════════════════════════════════════════════════
        //  Draw body mesh  (C#: TriangleMesh — dark tube)
        // ═══════════════════════════════════════════════════════════════
        Vec3d prevSegPos = segPositions[0];
        float prevWidth = bodyRadius;

        for (int i = 1; i < NUM_SEGMENTS; i++) {
            Vec3d curSegPos = segPositions[i];
            float t = (float) i / (float) (NUM_SEGMENTS - 1);

            // Current width (C#: stretchedRad + swallowArray[i]*5)
            float swallowBonus = anim.swallowArray[i] * 0.25f; // 5px/20
            float curWidth = bodyRadius + swallowBonus;

            // Camera-facing perpendicular for this ribbon section.
            Vec3d segDir = curSegPos.subtract(prevSegPos);
            if (segDir.lengthSquared() < 1e-10) segDir = new Vec3d(0, 1, 0);
            segDir = segDir.normalize();

            Vec3d toCamera = cameraPos.subtract(cameraPos).add(curSegPos.multiply(-1)).multiply(-1);
            // toCamera = viewer direction toward this point (in cam-relative, it's just -curSegPos direction).
            toCamera = curSegPos.multiply(-1);
            if (toCamera.lengthSquared() < 1e-8) toCamera = new Vec3d(0, 0, 1);

            Vec3d ribbonPerp = segDir.crossProduct(toCamera.normalize());
            if (ribbonPerp.lengthSquared() < 1e-8) {
                ribbonPerp = segDir.crossProduct(new Vec3d(1, 0, 0));
            }
            if (ribbonPerp.lengthSquared() < 1e-8) {
                ribbonPerp = segDir.crossProduct(new Vec3d(0, 0, 1));
            }
            ribbonPerp = ribbonPerp.normalize();

            // C#: MoveVertice vertices form a strip quad.
            float avgWidth = (curWidth + prevWidth) * 0.5f;
            Vec3d bl = prevSegPos.subtract(ribbonPerp.multiply(avgWidth));
            Vec3d br = prevSegPos.add(ribbonPerp.multiply(avgWidth));
            Vec3d tr = curSegPos.add(ribbonPerp.multiply(curWidth));
            Vec3d tl = curSegPos.subtract(ribbonPerp.multiply(curWidth));

            // UV: map each segment to full texture (body is dark anyway).
            emitQuad(bodyVc, matrix, bl, br, tr, tl,
                    0f, 1f, 1f, 0f,
                    BODY_R, BODY_G, BODY_B, BODY_A, fullLight);

            prevSegPos = curSegPos;
            prevWidth = curWidth;
        }

        // ═══════════════════════════════════════════════════════════════
        //  Draw head sprite  (C#: sprites[2] "WormHead")
        // ═══════════════════════════════════════════════════════════════
        Vec3d headSegPos = segPositions[NUM_SEGMENTS - 1];
        Vec3d headDir;
        if (NUM_SEGMENTS >= 2) {
            headDir = headSegPos.subtract(segPositions[NUM_SEGMENTS - 2]);
            if (headDir.lengthSquared() < 1e-10) headDir = new Vec3d(0, 1, 0);
            headDir = headDir.normalize();
        } else {
            headDir = new Vec3d(0, 1, 0);
        }

        {
            // Camera-facing perpendicular for head billboard.
            Vec3d camDir = headSegPos.multiply(-1).normalize();
            if (camDir.lengthSquared() < 1e-8) camDir = new Vec3d(0, 0, 1);

            Vec3d headRight = headDir.crossProduct(camDir);
            if (headRight.lengthSquared() < 1e-8) headRight = headDir.crossProduct(new Vec3d(1, 0, 0));
            if (headRight.lengthSquared() < 1e-8) headRight = headDir.crossProduct(new Vec3d(0, 0, 1));
            headRight = headRight.normalize();

            float headScale = HEAD_SIZE_BLOCKS * MathHelper.lerp(bodySize, 1f, 0.5f);
            float halfW = headScale * 0.5f;
            float halfH = headScale * 0.5f;

            Vec3d hbl = headSegPos.subtract(headRight.multiply(halfW)).subtract(headDir.multiply(halfH));
            Vec3d hbr = headSegPos.add(headRight.multiply(halfW)).subtract(headDir.multiply(halfH));
            Vec3d htr = headSegPos.add(headRight.multiply(halfW)).add(headDir.multiply(halfH));
            Vec3d htl = headSegPos.subtract(headRight.multiply(halfW)).add(headDir.multiply(halfH));

            emitQuad(bodyVc, matrix, hbl, hbr, htr, htl,
                    0f, 1f, 1f, 0f,
                    BODY_R, BODY_G, BODY_B, BODY_A, fullLight);
        }

        // ═══════════════════════════════════════════════════════════════
        //  Draw eyes  (C#: sprites[0] & sprites[3] "WormEye")
        //  Color: white normally, red when angry.
        // ═══════════════════════════════════════════════════════════════
        {
            int eyeR = angry ? 255 : 255;
            int eyeG = angry ?   0 : 255;
            int eyeB = angry ?   0 : 255;
            int eyeA = 255;

            // Direction-dependent eye placement (C# logic).
            Vec3d camDir = headSegPos.multiply(-1).normalize();
            if (camDir.lengthSquared() < 1e-8) camDir = new Vec3d(0, 0, 1);

            Vec3d headRight = headDir.crossProduct(camDir);
            if (headRight.lengthSquared() < 1e-8) headRight = headDir.crossProduct(new Vec3d(1, 0, 0));
            if (headRight.lengthSquared() < 1e-8) headRight = headDir.crossProduct(new Vec3d(0, 0, 1));
            headRight = headRight.normalize();

            float fwdOff = EYE_FORWARD_OFFSET * bodySize;
            float latOff = EYE_LATERAL_OFFSET * MathHelper.lerp(bodySize, 1f, 0.75f);
            float eyeHalf = EYE_SIZE_BLOCKS * 0.5f;

            // Left eye
            Vec3d eyeL = headSegPos
                    .add(headDir.multiply(fwdOff))
                    .add(headRight.multiply(latOff));
            emitBillboardQuad(eyeVc, matrix, eyeL, headRight, headDir, eyeHalf,
                    eyeR, eyeG, eyeB, eyeA, fullLight);

            // Right eye
            Vec3d eyeR2 = headSegPos
                    .add(headDir.multiply(fwdOff))
                    .subtract(headRight.multiply(latOff));
            emitBillboardQuad(eyeVc, matrix, eyeR2, headRight, headDir, eyeHalf,
                    eyeR, eyeG, eyeB, eyeA, fullLight);
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

        // C#: numberOfWavesOnBody & sinSpeed adjust based on stress and attack state.
        if (atkCtr < 20) {
            anim.numberOfWavesOnBody = MathHelper.lerp(0.1f, anim.numberOfWavesOnBody,
                    MathHelper.lerp(stress, 1.8f, 3.4f));
            anim.sinSpeed = MathHelper.lerp(0.05f, anim.sinSpeed,
                    MathHelper.lerp(stress, 1f / 60f, 0.05f));
        } else {
            anim.numberOfWavesOnBody = MathHelper.lerp(0.01f, anim.numberOfWavesOnBody, 5f);
            anim.sinSpeed = MathHelper.lerp(0.1f, anim.sinSpeed, 0.05f);
        }

        anim.sinWave += anim.sinSpeed;
        if (anim.sinWave > 1f) anim.sinWave -= 1f;

        // Swallow bulge propagation during attack.
        if (atkCtr > 40 && atkCtr < 150 && Math.random() < 1.0 / 30.0) {
            anim.swallowArray[anim.swallowArray.length - 1] =
                    (float) Math.pow(Math.random(), 0.5);
        }
        if (Math.random() < 1.0 / 3.0) {
            for (int i = 0; i < anim.swallowArray.length - 1; i++) {
                anim.swallowArray[i] = MathHelper.lerp(0.7f, anim.swallowArray[i],
                        anim.swallowArray[i + 1]);
            }
        }
        anim.swallowArray[anim.swallowArray.length - 1] =
                MathHelper.lerp(0.7f, anim.swallowArray[anim.swallowArray.length - 1], 0f);
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
