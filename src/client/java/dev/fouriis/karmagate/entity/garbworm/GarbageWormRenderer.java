package dev.fouriis.karmagate.entity.garbworm;

import dev.fouriis.karmagate.entity.tentacle.RenderTentacle;
import dev.fouriis.karmagate.entity.tentacle.RenderTentacleChunk;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

public class GarbageWormRenderer extends EntityRenderer<GarbageWormEntity> {

    private static final int NUM_CHUNKS = 15;
    private static final float BASE_CHUNK_RADIUS = 0.10f;
    private static final float BASE_IDEAL_LENGTH = 20.0f;
    private static final float SINE_AMPLITUDE = 0.55f;

    private static final int BODY_R = 15;
    private static final int BODY_G = 15;
    private static final int BODY_B = 15;
    private static final int BODY_A = 255;

    private static final int EYE_R = 255;
    private static final int EYE_G = 255;
    private static final int EYE_B = 255;
    private static final int EYE_A = 255;

    private final Map<Integer, WormAnimState> animStates = new HashMap<>();

    private static class WormAnimState {
        float sinWave = 0f;
        float numberOfWavesOnBody = 1.8f;
        float sinSpeed = 1f / 60f;
        float lastExtended = 1f;
        float extended = 1f;
        float[] swallowArray;

        int lastAge = -1;

        RenderTentacle tentacle;
        boolean initialized = false;

        WormAnimState(float bodySize) {
            float adjustedSize = MathHelper.lerp(0.5f, bodySize, 1f);
            int chunkCount = Math.max(3, (int) (NUM_CHUNKS * adjustedSize));
            float length = BASE_IDEAL_LENGTH * bodySize;
            float radius = BASE_CHUNK_RADIUS * adjustedSize;

            tentacle = new RenderTentacle(chunkCount, length, radius);
            tentacle.stretchAndSqueeze = 0.1f;
            tentacle.stiff = false;
            tentacle.massDeteriorationPerChunk = 0.5f;
            tentacle.chunkVelocityCap = 0.5f;
            tentacle.goalAttractionSpeedTip = 0.07f;
            tentacle.goalAttractionSpeed = 0f;
            tentacle.limp = false;

            swallowArray = new float[chunkCount];
        }
    }

    private static class Frame {
        final Vec3d tangent;
        final Vec3d right;
        final Vec3d up;

        Frame(Vec3d tangent, Vec3d right, Vec3d up) {
            this.tangent = tangent;
            this.right = right;
            this.up = up;
        }
    }

    public GarbageWormRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    public Identifier getTexture(GarbageWormEntity entity) {
        return Identifier.ofVanilla("textures/misc/white.png");
    }

    @Override
    public void render(GarbageWormEntity entity, float yaw, float tickDelta,
                       MatrixStack matrices, VertexConsumerProvider consumers, int light) {

        Vec3d rootPos = entity.getRootPos();
        float ext = entity.getExtended();
        float stress = entity.getStress();
        int atkCtr = entity.getAttackCtr();
        float bodySize = entity.getBodySizeValue();
        // if (bodySize < 0.01f) bodySize = 1f;

        if (ext <= 0f) {
            return;
        }

        Vec3d headPos = new Vec3d(
                MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()),
                MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()),
                MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ())
        );

        WormAnimState anim = animStates.computeIfAbsent(entity.getId(), k -> new WormAnimState(bodySize));
        if (!anim.initialized) {
            anim.tentacle.reset(rootPos);
            anim.initialized = true;
        }

        if (anim.lastAge != entity.age) {
            anim.lastAge = entity.age;
            tickAnimState(anim, ext, stress, atkCtr);

            anim.tentacle.retractFac = 1f - ext;
            anim.tentacle.limp = false;

            float headDist = (float) anim.tentacle.tip().pos.distanceTo(headPos);
            if (atkCtr == 0) {
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

        Camera camera = this.dispatcher.camera;
        Vec3d cameraPos = camera.getPos();

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

        VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntitySolid(getTexture(entity)));
        int fullLight = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        int numChunks = anim.tentacle.chunkCount();
        Vec3d startCR = rootCR.add(0.0, -1.5 - 5.0 * (1.0 - interpExtended), 0.0);

        Vec3d[] centers = new Vec3d[numChunks];
        float[] radii = new float[numChunks];

        for (int i = 0; i < numChunks; i++) {
            RenderTentacleChunk chunk = anim.tentacle.getChunk(i);

            Vec3d a = lerpVec(chunk.lastPos, chunk.pos, tickDelta).subtract(cameraPos);

            float t = (float) i / (float) (numChunks - 1);

            float retractFactor = (float) Math.pow(Math.max(1.0f - t - interpExtended, 0f), 1.5f);
            if (interpExtended < 0.2f) {
                retractFactor = Math.min(1f, retractFactor + inverseLerp(0.2f, 0f, interpExtended));
            }

            a = lerpVec(a, rootCR, retractFactor);
            a = a.add(0.0, -5.0 * Math.pow(retractFactor, 0.5), 0.0);

            float interpSinWave = MathHelper.lerp(tickDelta, anim.sinWave - anim.sinSpeed, anim.sinWave);
            float sineValue = (float) Math.sin((interpSinWave + t * anim.numberOfWavesOnBody) * Math.PI * 2.0);

            float envelope = (float) Math.pow(Math.max(0f, (float) Math.sin(t * Math.PI)), 0.75f) * interpExtended;
            float amplitude = SINE_AMPLITUDE * bodySize * envelope;

            Vec3d bodyDir = a.subtract(startCR);
            if (bodyDir.lengthSquared() < 1e-8) {
                bodyDir = new Vec3d(0, 1, 0);
            }
            bodyDir = bodyDir.normalize();

            Vec3d toCamera = a.multiply(-1.0);
            if (toCamera.lengthSquared() < 1e-8) {
                toCamera = new Vec3d(0, 0, 1);
            }

            Vec3d sinePerp = bodyDir.crossProduct(toCamera.normalize());
            if (sinePerp.lengthSquared() < 1e-8) {
                sinePerp = bodyDir.crossProduct(new Vec3d(1, 0, 0));
            }
            if (sinePerp.lengthSquared() < 1e-8) {
                sinePerp = bodyDir.crossProduct(new Vec3d(0, 0, 1));
            }
            sinePerp = sinePerp.normalize();

            a = a.add(sinePerp.multiply(sineValue * amplitude));

            centers[i] = a;
            radii[i] = chunk.stretchedRad() + anim.swallowArray[i] * 0.25f;
        }

        // Pin first and last points
        {
            Vec3d baseOffset = rootCR.subtract(centers[0]);
            Vec3d tipOffset = headCR.subtract(centers[numChunks - 1]);
            for (int i = 0; i < numChunks; i++) {
                float blend = (float) i / (float) (numChunks - 1);
                centers[i] = centers[i].add(lerpVec(baseOffset, tipOffset, blend));
            }
        }

        // prepend lowered root start like the C# renderer
        Vec3d[] bodyCenters = new Vec3d[numChunks + 1];
        float[] bodyRadii = new float[numChunks + 1];
        bodyCenters[0] = startCR;
        bodyRadii[0] = 0.20f; // 4px / 20
        System.arraycopy(centers, 0, bodyCenters, 1, numChunks);
        System.arraycopy(radii, 0, bodyRadii, 1, numChunks);

        Frame[] frames = buildFrames(bodyCenters);

        Vec3d[][] rings = new Vec3d[bodyCenters.length][];
        for (int i = 0; i < bodyCenters.length; i++) {
            float halfWidth = bodyRadii[i];
            float halfHeight = Math.max(0.02f, bodyRadii[i] * 0.90f);
            rings[i] = buildSquareRing(bodyCenters[i], frames[i], halfWidth, halfHeight);
        }

        for (int i = 0; i < bodyCenters.length - 1; i++) {
            emitTubeSection(vc, matrix, rings[i], rings[i + 1],
                    BODY_R, BODY_G, BODY_B, BODY_A, fullLight);
        }

        emitRingCap(vc, matrix, bodyCenters[0], rings[0], true,
                BODY_R, BODY_G, BODY_B, BODY_A, fullLight);

        emitRingCap(vc, matrix, bodyCenters[bodyCenters.length - 1], rings[rings.length - 1], false,
                BODY_R, BODY_G, BODY_B, BODY_A, fullLight);

        Vec3d headCenter = bodyCenters[bodyCenters.length - 1];
        Frame headFrame = frames[frames.length - 1];

        float headScale = 0.62f * MathHelper.lerp(0.5f, bodySize, 1f);
        float headHalfW = headScale * 0.60f;
        float headHalfH = headScale * 0.72f;
        float headHalfD = 0.18f * MathHelper.lerp(0.5f, bodySize, 1f);

        Vec3d headBoxCenter = headCenter.add(headFrame.tangent.multiply(headHalfD * 0.35f));
        emitOrientedBox(
                vc, matrix,
                headBoxCenter,
                headFrame.right, headFrame.up, headFrame.tangent,
                headHalfW, headHalfH, headHalfD,
                BODY_R, BODY_G, BODY_B, BODY_A, fullLight
        );

        float eyeForward = 0.25f * bodySize;
        float eyeSide = 0.15f * MathHelper.lerp(0.75f, bodySize, 1f);
        float eyeLift = 0.04f * bodySize;
        float eyeSize = 0.085f * MathHelper.lerp(0.6f, bodySize, 1f);

        Vec3d eyeBase = headBoxCenter
                .add(headFrame.tangent.multiply(eyeForward))
                .add(headFrame.up.multiply(eyeLift));

        Vec3d leftEye = eyeBase.add(headFrame.right.multiply(eyeSide));
        Vec3d rightEye = eyeBase.subtract(headFrame.right.multiply(eyeSide));

        emitOrientedBox(
                vc, matrix,
                leftEye,
                headFrame.right, headFrame.up, headFrame.tangent,
                eyeSize * 0.5f, eyeSize * 0.5f, eyeSize * 0.5f,
                EYE_R, EYE_G, EYE_B, EYE_A, fullLight
        );

        emitOrientedBox(
                vc, matrix,
                rightEye,
                headFrame.right, headFrame.up, headFrame.tangent,
                eyeSize * 0.5f, eyeSize * 0.5f, eyeSize * 0.5f,
                EYE_R, EYE_G, EYE_B, EYE_A, fullLight
        );

        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, consumers, light);
    }

    private void tickAnimState(WormAnimState anim, float ext, float stress, int atkCtr) {
        anim.lastExtended = anim.extended;
        anim.extended = ext;

        if (atkCtr < 20) {
            anim.numberOfWavesOnBody = MathHelper.lerp(
                    0.1f,
                    anim.numberOfWavesOnBody,
                    MathHelper.lerp(stress, 1.8f, 3.4f)
            );
            anim.sinSpeed = MathHelper.lerp(
                    0.05f,
                    anim.sinSpeed,
                    MathHelper.lerp(stress, 1f / 60f, 0.05f)
            );
        } else {
            anim.numberOfWavesOnBody = MathHelper.lerp(0.01f, anim.numberOfWavesOnBody, 5f);
            anim.sinSpeed = MathHelper.lerp(0.1f, anim.sinSpeed, 0.05f);
        }

        anim.sinWave += anim.sinSpeed;
        if (anim.sinWave > 1f) {
            anim.sinWave -= 1f;
        }

        if (atkCtr > 40 && atkCtr < 190 && Math.random() < 1.0 / 30.0) {
            anim.swallowArray[anim.swallowArray.length - 1] =
                    (float) Math.pow(Math.random(), 0.5);
        }

        if (Math.random() < 1.0 / 3.0) {
            for (int i = 0; i < anim.swallowArray.length - 1; i++) {
                anim.swallowArray[i] = MathHelper.lerp(
                        0.7f,
                        anim.swallowArray[i],
                        anim.swallowArray[i + 1]
                );
            }
        }

        anim.swallowArray[anim.swallowArray.length - 1] =
                MathHelper.lerp(0.7f, anim.swallowArray[anim.swallowArray.length - 1], 0f);
    }

    private static Frame[] buildFrames(Vec3d[] centers) {
        Frame[] frames = new Frame[centers.length];

        Vec3d firstTangent = tangentAt(centers, 0);
        Vec3d ref = Math.abs(firstTangent.dotProduct(new Vec3d(0, 1, 0))) > 0.98
                ? new Vec3d(1, 0, 0)
                : new Vec3d(0, 1, 0);

        Vec3d right = firstTangent.crossProduct(ref);
        if (right.lengthSquared() < 1e-10) {
            right = firstTangent.crossProduct(new Vec3d(0, 0, 1));
        }
        right = right.normalize();
        Vec3d up = right.crossProduct(firstTangent).normalize();

        frames[0] = new Frame(firstTangent, right, up);

        for (int i = 1; i < centers.length; i++) {
            Vec3d tangent = tangentAt(centers, i);

            Vec3d transportedRight = frames[i - 1].right.subtract(
                    tangent.multiply(frames[i - 1].right.dotProduct(tangent))
            );
            if (transportedRight.lengthSquared() < 1e-10) {
                transportedRight = frames[i - 1].up.crossProduct(tangent);
            }
            if (transportedRight.lengthSquared() < 1e-10) {
                Vec3d fallback = Math.abs(tangent.dotProduct(new Vec3d(0, 1, 0))) > 0.98
                        ? new Vec3d(1, 0, 0)
                        : new Vec3d(0, 1, 0);
                transportedRight = tangent.crossProduct(fallback);
            }

            transportedRight = transportedRight.normalize();
            Vec3d transportedUp = transportedRight.crossProduct(tangent).normalize();

            frames[i] = new Frame(tangent, transportedRight, transportedUp);
        }

        return frames;
    }

    private static Vec3d tangentAt(Vec3d[] centers, int i) {
        Vec3d tangent;
        if (i == 0) {
            tangent = centers[1].subtract(centers[0]);
        } else if (i == centers.length - 1) {
            tangent = centers[i].subtract(centers[i - 1]);
        } else {
            tangent = centers[i + 1].subtract(centers[i - 1]);
        }

        if (tangent.lengthSquared() < 1e-10) {
            tangent = new Vec3d(0, 1, 0);
        }
        return tangent.normalize();
    }

    private static Vec3d[] buildSquareRing(Vec3d center, Frame frame, float halfWidth, float halfHeight) {
        // 0 = bottom-left, 1 = bottom-right, 2 = top-right, 3 = top-left
        return new Vec3d[]{
                center.subtract(frame.right.multiply(halfWidth)).subtract(frame.up.multiply(halfHeight)),
                center.add(frame.right.multiply(halfWidth)).subtract(frame.up.multiply(halfHeight)),
                center.add(frame.right.multiply(halfWidth)).add(frame.up.multiply(halfHeight)),
                center.subtract(frame.right.multiply(halfWidth)).add(frame.up.multiply(halfHeight))
        };
    }

    private static void emitTubeSection(VertexConsumer vc, Matrix4f matrix,
                                        Vec3d[] a, Vec3d[] b,
                                        int r, int g, int bl, int alpha, int light) {
        // bottom
        emitQuad(vc, matrix, a[0], b[0], b[1], a[1], r, g, bl, alpha, light);
        // right
        emitQuad(vc, matrix, a[1], b[1], b[2], a[2], r, g, bl, alpha, light);
        // top
        emitQuad(vc, matrix, a[2], b[2], b[3], a[3], r, g, bl, alpha, light);
        // left
        emitQuad(vc, matrix, a[3], b[3], b[0], a[0], r, g, bl, alpha, light);
    }

    private static void emitRingCap(VertexConsumer vc, Matrix4f matrix,
                                    Vec3d center, Vec3d[] ring, boolean rootCap,
                                    int r, int g, int bl, int alpha, int light) {
        // ring: 0 bl, 1 br, 2 tr, 3 tl
        if (rootCap) {
            emitTri(vc, matrix, center, ring[1], ring[0], r, g, bl, alpha, light);
            emitTri(vc, matrix, center, ring[2], ring[1], r, g, bl, alpha, light);
            emitTri(vc, matrix, center, ring[3], ring[2], r, g, bl, alpha, light);
            emitTri(vc, matrix, center, ring[0], ring[3], r, g, bl, alpha, light);
        } else {
            emitTri(vc, matrix, center, ring[0], ring[1], r, g, bl, alpha, light);
            emitTri(vc, matrix, center, ring[1], ring[2], r, g, bl, alpha, light);
            emitTri(vc, matrix, center, ring[2], ring[3], r, g, bl, alpha, light);
            emitTri(vc, matrix, center, ring[3], ring[0], r, g, bl, alpha, light);
        }
    }

    private static void emitOrientedBox(VertexConsumer vc, Matrix4f matrix,
                                        Vec3d center,
                                        Vec3d right, Vec3d up, Vec3d forward,
                                        float halfX, float halfY, float halfZ,
                                        int r, int g, int b, int a, int light) {

        Vec3d rx = right.multiply(halfX);
        Vec3d uy = up.multiply(halfY);
        Vec3d fz = forward.multiply(halfZ);

        Vec3d p000 = center.subtract(rx).subtract(uy).subtract(fz);
        Vec3d p001 = center.subtract(rx).subtract(uy).add(fz);
        Vec3d p010 = center.subtract(rx).add(uy).subtract(fz);
        Vec3d p011 = center.subtract(rx).add(uy).add(fz);
        Vec3d p100 = center.add(rx).subtract(uy).subtract(fz);
        Vec3d p101 = center.add(rx).subtract(uy).add(fz);
        Vec3d p110 = center.add(rx).add(uy).subtract(fz);
        Vec3d p111 = center.add(rx).add(uy).add(fz);

        emitQuad(vc, matrix, p100, p000, p010, p110, r, g, b, a, light);
        emitQuad(vc, matrix, p001, p101, p111, p011, r, g, b, a, light);
        emitQuad(vc, matrix, p000, p001, p011, p010, r, g, b, a, light);
        emitQuad(vc, matrix, p101, p100, p110, p111, r, g, b, a, light);
        emitQuad(vc, matrix, p010, p011, p111, p110, r, g, b, a, light);
        emitQuad(vc, matrix, p100, p101, p001, p000, r, g, b, a, light);
    }

    private static void emitQuad(VertexConsumer vc, Matrix4f matrix,
                                 Vec3d a, Vec3d b, Vec3d c, Vec3d d,
                                 int r, int g, int bl, int alpha, int light) {
        putVertex(vc, matrix, a, 0f, 1f, r, g, bl, alpha, light);
        putVertex(vc, matrix, b, 1f, 1f, r, g, bl, alpha, light);
        putVertex(vc, matrix, c, 1f, 0f, r, g, bl, alpha, light);
        putVertex(vc, matrix, d, 0f, 0f, r, g, bl, alpha, light);
    }

    private static void emitTri(VertexConsumer vc, Matrix4f matrix,
                                Vec3d a, Vec3d b, Vec3d c,
                                int r, int g, int bl, int alpha, int light) {
        putVertex(vc, matrix, a, 0.5f, 0.5f, r, g, bl, alpha, light);
        putVertex(vc, matrix, b, 0f, 1f, r, g, bl, alpha, light);
        putVertex(vc, matrix, c, 1f, 1f, r, g, bl, alpha, light);
    }

    private static void putVertex(VertexConsumer vc, Matrix4f matrix, Vec3d p,
                                  float u, float v, int r, int g, int b, int a, int light) {
        vc.vertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, a)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(0f, 1f, 0f);
    }

    private static Vec3d lerpVec(Vec3d a, Vec3d b, float t) {
        return new Vec3d(
                MathHelper.lerp(t, a.x, b.x),
                MathHelper.lerp(t, a.y, b.y),
                MathHelper.lerp(t, a.z, b.z)
        );
    }

    private static float inverseLerp(float a, float b, float v) {
        if (Math.abs(b - a) < 1e-8f) {
            return 0f;
        }
        return MathHelper.clamp((v - a) / (b - a), 0f, 1f);
    }
}