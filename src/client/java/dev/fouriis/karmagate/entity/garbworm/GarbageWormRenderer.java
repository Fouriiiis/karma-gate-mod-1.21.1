package dev.fouriis.karmagate.entity.garbworm;

import dev.fouriis.karmagate.entity.tentacle.RenderTentacle;
import dev.fouriis.karmagate.entity.tentacle.RenderTentacleChunk;
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
 * Renderer for the Garbage Worm — faithful port of C# {@code GarbageWormGraphics}.
 * <p>
 * Uses a {@link RenderTentacle} for per-chunk physics, then in {@code render()}
 * reproduces the C# {@code DrawSprites} logic:
 * <ul>
 *   <li>Body: dark {@code TriangleMesh} ribbon from root to head with sinusoidal wave</li>
 *   <li>Head: {@code "WormHead"} sprite billboard (dark, palette.blackColor)</li>
 *   <li>Eyes: {@code "WormEye"} sprites (white normally, red when angry)</li>
 * </ul>
 */
public class GarbageWormRenderer extends EntityRenderer<GarbageWormEntity> {

    // ── Atlas sprites ─────────────────────────────────────────────────
    private static FAtlasManager atlasManager;
    private static FAtlasElement WORM_EYE;
    private static FAtlasElement WORM_HEAD;

    // ── Rendering constants ───────────────────────────────────────────
    // C#: tentacle = 400px * bodySize, tChunks = 15 * Lerp(bodySize,1,0.5)
    private static final int NUM_CHUNKS = 15;

    // C#: chunk radius = 2px * Lerp(bodySize,1,0.5) → 2/20 = 0.10 blocks
    private static final float BASE_CHUNK_RADIUS = 0.10f;

    // C#: idealLength = 400px * bodySize → 400/20 = 20 blocks
    private static final float BASE_IDEAL_LENGTH = 20.0f;

    // C#: sine amplitude = 11px / 20 = 0.55 blocks
    private static final float SINE_AMPLITUDE = 0.55f;

    // C#: head sprite scale = Lerp(bodySize,1,0.5)
    private static final float HEAD_SIZE_BLOCKS = 0.35f;

    // C#: eye sprite world size
    private static final float EYE_SIZE_BLOCKS = 0.12f;

    // C#: eye offsets from head center (pixels / 20)
    private static final float EYE_FORWARD_OFFSET = 0.25f; // 5px / 20
    private static final float EYE_LATERAL_OFFSET = 0.15f;  // 3px / 20

    // Body + head colour (C#: palette.blackColor)
    private static final int BODY_R = 15, BODY_G = 15, BODY_B = 15, BODY_A = 255;

    // Normal for flat shading
    private static final float NX = 0f, NY = 1f, NZ = 0f;

    // ── Per-entity client state ───────────────────────────────────────
    private final Map<Integer, WormAnimState> animStates = new HashMap<>();

    /**
     * Client-side animation + physics state cached per worm entity ID.
     * Matches C# GarbageWormGraphics fields + GarbageWorm tentacle instance.
     */
    private static class WormAnimState {
        // ── C# GarbageWormGraphics fields ──
        float sinWave = 0f;
        float numberOfWavesOnBody = 1.8f;
        float sinSpeed = 1f / 60f;
        float lastExtended = 1f;
        float extended = 1f;
        float[] swallowArray;

        // ── Tick tracking ──
        int lastAge = -1;

        // ── Client-side tentacle (chunk positions) ──
        RenderTentacle tentacle;
        boolean initialized = false;

        WormAnimState(float bodySize) {
            // C#: Lerp(bodySize, 1, 0.5) = bodySize + 0.5*(1-bodySize)
            float adjustedSize = MathHelper.lerp(0.5f, bodySize, 1f);
            int chunkCount = Math.max(3, (int) (NUM_CHUNKS * adjustedSize));
            float length = BASE_IDEAL_LENGTH * bodySize;
            float radius = BASE_CHUNK_RADIUS * adjustedSize;
            tentacle = new RenderTentacle(chunkCount, length, radius);
            // C#: GarbageWorm constructor TentacleProps
            tentacle.stretchAndSqueeze = 0.1f;
            tentacle.stiff = false;
            tentacle.massDeteriorationPerChunk = 0.5f;
            tentacle.chunkVelocityCap = 0.5f; // 10px / 20
            tentacle.goalAttractionSpeedTip = 0.07f; // C#: 1.4 / 20
            tentacle.goalAttractionSpeed = 0f;
            tentacle.limp = false;
            swallowArray = new float[chunkCount];
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
    //  render()  — C#: GarbageWormGraphics.DrawSprites
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

        // Skip rendering when fully retracted
        if (ext <= 0f) return;

        // Head pos with tick interpolation
        Vec3d headPos = new Vec3d(
                MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()),
                MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()),
                MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ())
        );

        // ── Get / create animation state ──
        final float bs = bodySize;
        WormAnimState anim = animStates.computeIfAbsent(entity.getId(), k -> new WormAnimState(bs));
        if (!anim.initialized) {
            anim.tentacle.reset(rootPos);
            anim.initialized = true;
        }

        // ── Tick animation & tentacle physics (once per entity age tick) ──
        if (anim.lastAge != entity.age) {
            anim.lastAge = entity.age;
            tickAnimState(anim, ext, stress, atkCtr);

            // Update tentacle retraction
            anim.tentacle.retractFac = 1f - ext;
            anim.tentacle.limp = false;

            // C#: goalAttractionSpeedTip varies with attack state
            float headDist = (float) anim.tentacle.tip().pos.distanceTo(headPos);
            if (atkCtr == 0) {
                // C#: Lerp(0.15, 1.9, InverseLerp(40, 290, dist)) → /20
                anim.tentacle.goalAttractionSpeedTip =
                        MathHelper.lerp(inverseLerp(2f, 14.5f, headDist), 0.0075f, 0.095f);
            } else if (atkCtr < 20) {
                anim.tentacle.goalAttractionSpeedTip = 0.005f;
            } else if (atkCtr < 40) {
                anim.tentacle.goalAttractionSpeedTip = 2.0f;
            } else {
                anim.tentacle.goalAttractionSpeedTip = 0.0005f;
            }

            anim.tentacle.update(rootPos, headPos);
        }

        float interpExtended = MathHelper.lerp(tickDelta, anim.lastExtended, anim.extended);

        // ── Camera ──
        Camera camera = this.dispatcher.camera;
        Vec3d cameraPos = camera.getPos();

        // ── Matrix setup ──
        // EntityRenderer places (0,0,0) at the entity's camera-relative pos.
        // Undo that offset so we work in world-minus-camera coordinates.
        matrices.push();
        Vec3d entityCamRel = new Vec3d(
                MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()) - cameraPos.x,
                MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()) - cameraPos.y,
                MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ()) - cameraPos.z
        );
        matrices.translate(-entityCamRel.x, -entityCamRel.y, -entityCamRel.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        Vec3d rootCR = rootPos.subtract(cameraPos);
        Vec3d headCR = headPos.subtract(cameraPos);

        // ── Render layers ──
        VertexConsumer bodyVc = consumers.getBuffer(
                RenderLayer.getEntityTranslucent(WORM_HEAD.textureIdentifier));
        VertexConsumer eyeVc = consumers.getBuffer(
                RenderLayer.getEntityTranslucent(WORM_EYE.textureIdentifier));
        int fullLight = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        // ═══════════════════════════════════════════════════════════════
        //  Compute segment positions
        //  C#: GarbageWormGraphics.DrawSprites body loop
        // ═══════════════════════════════════════════════════════════════

        int numChunks = anim.tentacle.chunkCount();

        // C#: vector = bodyChunks[1].pos + (0, -30 - 100*(1-extended))
        // → blocks: (0, -1.5 - 5*(1-ext))
        Vec3d startCR = rootCR.add(0, -1.5 - 5.0 * (1.0 - interpExtended), 0);

        Vec3d[] segPositions = new Vec3d[numChunks];

        for (int i = 0; i < numChunks; i++) {
            RenderTentacleChunk chunk = anim.tentacle.getChunk(i);

            // C#: a = Lerp(tChunks[i].lastPos, tChunks[i].pos, timeStacker)
            Vec3d a = lerpVec(chunk.lastPos, chunk.pos, tickDelta).subtract(cameraPos);

            float t = (float) i / (float) (numChunks - 1);

            // C#: retraction blend
            // num4 = pow(max(1 - t - extended, 0), 1.5)
            float retractFactor = (float) Math.pow(Math.max(1.0f - t - interpExtended, 0f), 1.5f);
            if (interpExtended < 0.2f) {
                retractFactor = Math.min(1f, retractFactor + inverseLerp(0.2f, 0f, interpExtended));
            }
            // C#: a = Lerp(a, bodyChunks[1].pos, num4) + (0, -100*pow(num4,0.5))
            a = lerpVec(a, rootCR, retractFactor);
            a = a.add(0, -5.0 * Math.pow(retractFactor, 0.5), 0);

            // C#: sine wave perpendicular to body direction
            float interpSinWave = MathHelper.lerp(tickDelta,
                    anim.sinWave - anim.sinSpeed, anim.sinWave);
            float sineValue = (float) Math.sin(
                    (interpSinWave + t * anim.numberOfWavesOnBody) * Math.PI * 2.0);

            // C#: amplitude = 11 * pow(max(0, sin(t*PI)), 0.75) * extended
            float envelope = (float) Math.pow(Math.max(0f,
                    (float) Math.sin(t * Math.PI)), 0.75f) * interpExtended;
            float amplitude = SINE_AMPLITUDE * bodySize * envelope;

            // Perpendicular direction (camera-facing)
            Vec3d bodyDir = a.subtract(startCR);
            if (bodyDir.lengthSquared() < 1e-8) bodyDir = new Vec3d(0, 1, 0);
            bodyDir = bodyDir.normalize();

            Vec3d toCamera = a.multiply(-1);
            if (toCamera.lengthSquared() < 1e-8) toCamera = new Vec3d(0, 0, 1);

            Vec3d sinePerp = bodyDir.crossProduct(toCamera.normalize());
            if (sinePerp.lengthSquared() < 1e-8) {
                sinePerp = bodyDir.crossProduct(new Vec3d(1, 0, 0));
            }
            if (sinePerp.lengthSquared() < 1e-8) {
                sinePerp = bodyDir.crossProduct(new Vec3d(0, 0, 1));
            }
            sinePerp = sinePerp.normalize();

            // C#: a += perp * num5 * 11 * pow(max(0,sin(t*PI)),0.75) * extended
            a = a.add(sinePerp.multiply(sineValue * amplitude));

            segPositions[i] = a;
        }

        // ── Pin endpoints at root and head, smoothly distribute correction ──
        // The tentacle physics gives organic curvature, but endpoints may
        // drift from rootCR / headCR. Blend the offset so segment 0 = rootCR,
        // segment N-1 = headCR, and intermediate segments shift proportionally.
        {
            Vec3d baseOffset = rootCR.subtract(segPositions[0]);
            Vec3d tipOffset  = headCR.subtract(segPositions[numChunks - 1]);
            for (int i = 0; i < numChunks; i++) {
                float blend = (float) i / (float) (numChunks - 1);
                Vec3d offset = lerpVec(baseOffset, tipOffset, blend);
                segPositions[i] = segPositions[i].add(offset);
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  Draw body mesh  (C#: TriangleMesh ribbon strip)
        //  C# builds 4 vertices per segment:
        //    v0 = prevPos - perp*(curWidth+prevWidth)/2 + dir*spacing
        //    v1 = prevPos + perp*(curWidth+prevWidth)/2 + dir*spacing
        //    v2 = curPos  - perp*curWidth - dir*spacing
        //    v3 = curPos  + perp*curWidth - dir*spacing
        // ═══════════════════════════════════════════════════════════════

        Vec3d prevPos = segPositions[0];
        float prevWidth = anim.tentacle.getChunk(0).stretchedRad();

        for (int i = 1; i < numChunks; i++) {
            Vec3d curPos = segPositions[i];

            // C#: num8 = stretchedRad + swallowArray[i]*5 → /20 = swallow*0.25
            float swallowBonus = (i < anim.swallowArray.length)
                    ? anim.swallowArray[i] * 0.25f : 0f;
            float curWidth = anim.tentacle.getChunk(i).stretchedRad() + swallowBonus;

            // Direction and camera-facing perpendicular
            Vec3d segDir = curPos.subtract(prevPos);
            if (segDir.lengthSquared() < 1e-10) segDir = new Vec3d(0, 1, 0);
            segDir = segDir.normalize();

            Vec3d toCamera = curPos.multiply(-1);
            if (toCamera.lengthSquared() < 1e-8) toCamera = new Vec3d(0, 0, 1);
            Vec3d ribbonPerp = segDir.crossProduct(toCamera.normalize());
            if (ribbonPerp.lengthSquared() < 1e-8) {
                ribbonPerp = segDir.crossProduct(new Vec3d(1, 0, 0));
            }
            if (ribbonPerp.lengthSquared() < 1e-8) {
                ribbonPerp = segDir.crossProduct(new Vec3d(0, 0, 1));
            }
            ribbonPerp = ribbonPerp.normalize();

            // Build quad vertices (C# MoveVertice pattern)
            // No spacing inset — quads share edges in a continuous strip
            float halfWidth0 = (curWidth + prevWidth) * 0.5f;
            Vec3d bl = prevPos.subtract(ribbonPerp.multiply(halfWidth0));
            Vec3d br = prevPos.add(ribbonPerp.multiply(halfWidth0));
            Vec3d tr = curPos.add(ribbonPerp.multiply(curWidth));
            Vec3d tl = curPos.subtract(ribbonPerp.multiply(curWidth));

            emitQuad(bodyVc, matrix, bl, br, tr, tl,
                    0f, 1f, 1f, 0f,
                    BODY_R, BODY_G, BODY_B, BODY_A, fullLight);

            prevPos = curPos;
            prevWidth = curWidth;
        }

        // ═══════════════════════════════════════════════════════════════
        //  Draw head  (C#: sprites[2] "WormHead")
        //  Head and eyes render at entity pos (headCR) so they match hitbox.
        // ═══════════════════════════════════════════════════════════════
        Vec3d headSegPos = headCR;
        Vec3d headDir;
        if (numChunks >= 2) {
            headDir = headSegPos.subtract(segPositions[numChunks - 2]);
            if (headDir.lengthSquared() < 1e-10) headDir = new Vec3d(0, 1, 0);
            headDir = headDir.normalize();
        } else {
            headDir = new Vec3d(0, 1, 0);
        }

        {
            // Camera-facing perpendicular for billboard
            Vec3d camDir = headSegPos.multiply(-1).normalize();
            if (camDir.lengthSquared() < 1e-8) camDir = new Vec3d(0, 0, 1);

            Vec3d headRight = headDir.crossProduct(camDir);
            if (headRight.lengthSquared() < 1e-8)
                headRight = headDir.crossProduct(new Vec3d(1, 0, 0));
            if (headRight.lengthSquared() < 1e-8)
                headRight = headDir.crossProduct(new Vec3d(0, 0, 1));
            headRight = headRight.normalize();

            // C#: sprites[2].scale = Lerp(bodySize, 1, 0.5)
            float headScale = HEAD_SIZE_BLOCKS * MathHelper.lerp(0.5f, bodySize, 1f);
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
        //  C#: white normally, red when angry
        // ═══════════════════════════════════════════════════════════════
        {
            int eyeR = 255;
            int eyeG = angry ? 0 : 255;
            int eyeB = angry ? 0 : 255;
            int eyeA = 255;

            Vec3d camDir = headSegPos.multiply(-1).normalize();
            if (camDir.lengthSquared() < 1e-8) camDir = new Vec3d(0, 0, 1);

            Vec3d headRight = headDir.crossProduct(camDir);
            if (headRight.lengthSquared() < 1e-8)
                headRight = headDir.crossProduct(new Vec3d(1, 0, 0));
            if (headRight.lengthSquared() < 1e-8)
                headRight = headDir.crossProduct(new Vec3d(0, 0, 1));
            headRight = headRight.normalize();

            // C#: eye depth ordering
            // f = cos(aimAngle / 360 * 2 * PI), pow(abs(f), 0.25) * sign(f)
            // In 3D: approximate using dot product of headRight with camDir
            float facingDot = (float) headRight.dotProduct(camDir);
            float f = (float) (Math.pow(Math.abs(facingDot), 0.25f) * Math.signum(facingDot));

            float fwdOff = EYE_FORWARD_OFFSET * bodySize;
            float latOff = EYE_LATERAL_OFFSET * MathHelper.lerp(0.75f, bodySize, 1f);
            float eyeHalf = EYE_SIZE_BLOCKS * 0.5f;

            // Left eye  (+ headRight * lateral * f)
            Vec3d eyeL = headSegPos
                    .add(headDir.multiply(fwdOff))
                    .add(headRight.multiply(latOff * f));
            emitBillboardQuad(eyeVc, matrix, eyeL, headRight, headDir, eyeHalf,
                    eyeR, eyeG, eyeB, eyeA, fullLight);

            // Right eye (- headRight * lateral * f)
            Vec3d eyeRpos = headSegPos
                    .add(headDir.multiply(fwdOff))
                    .subtract(headRight.multiply(latOff * f));
            emitBillboardQuad(eyeVc, matrix, eyeRpos, headRight, headDir, eyeHalf,
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

        // C#: numberOfWavesOnBody & sinSpeed adjust based on stress and attack state
        if (atkCtr < 20) {
            // C#: Lerp(numberOfWavesOnBody, Lerp(1.8, 3.4, stress), 0.1)
            anim.numberOfWavesOnBody = MathHelper.lerp(0.1f, anim.numberOfWavesOnBody,
                    MathHelper.lerp(stress, 1.8f, 3.4f));
            // C#: Lerp(sinSpeed, Lerp(1/60, 0.05, stress), 0.05)
            anim.sinSpeed = MathHelper.lerp(0.05f, anim.sinSpeed,
                    MathHelper.lerp(stress, 1f / 60f, 0.05f));
        } else {
            // C#: Lerp(numberOfWavesOnBody, 5, 0.01)
            anim.numberOfWavesOnBody = MathHelper.lerp(0.01f, anim.numberOfWavesOnBody, 5f);
            // C#: Lerp(sinSpeed, 0.05, 0.1)
            anim.sinSpeed = MathHelper.lerp(0.1f, anim.sinSpeed, 0.05f);
        }

        anim.sinWave += anim.sinSpeed;
        if (anim.sinWave > 1f) anim.sinWave -= 1f;

        // C#: swallow bulge propagation during attack (atkCtr 40..190)
        if (atkCtr > 40 && atkCtr < 190 && Math.random() < 1.0 / 30.0) {
            anim.swallowArray[anim.swallowArray.length - 1] =
                    (float) Math.pow(Math.random(), 0.5);
        }
        // C#: propagate bulge down the body
        if (Math.random() < 1.0 / 3.0) {
            for (int i = 0; i < anim.swallowArray.length - 1; i++) {
                anim.swallowArray[i] = MathHelper.lerp(0.7f, anim.swallowArray[i],
                        anim.swallowArray[i + 1]);
            }
        }
        // C#: Lerp(swallowArray[last], 0, 0.7)
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
        return MathHelper.clamp((v - a) / (b - a), 0f, 1f);
    }
}
