package dev.fouriis.karmagate.entity.oracle;

import dev.fouriis.karmagate.entity.oracle.OracleArm.Joint;
import dev.fouriis.karmagate.entity.oracle.OracleArm.JointView;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class OracleEntityRenderer extends EntityRenderer<OracleEntity> {
    private static final Identifier WHITE_TEX = Identifier.of("minecraft", "textures/misc/white.png");
    private static final int TUBE_SIDES = 6;
    private static final int SPHERE_SEGMENTS = 10;
    private static final int SPHERE_RINGS = 6;
    private static final float UMBILICAL_MODEL_DEPTH_PIXELS = 1.0f;
    private static final float SPRITE_MODEL_DEPTH_PIXELS = 1.0f;
    private static final float BLOCKS_PER_RAIN_WORLD_PIXEL = 1.0f / 20.0f;
    private static final float ARM_ROOT_THICKNESS_BLOCKS = 1.0f;
    private static final float ARM_ROOT_CENTER_FROM_RAIL_PIXELS = 10.0f;
    private static final float ARM_ROOT_HALF_FROM_CENTER_PIXELS = 17.0f;
    private static final float ARM_ROOT_SOCKET_SURFACE_MARGIN_BLOCKS = 0.05f;
    private static final double ARM_VISUAL_COLLISION_RADIUS_BLOCKS = 0.22;
    private static final double ARM_VISUAL_COLLISION_SAMPLE_SPACING_BLOCKS = 0.3;
    private static final Vec3d ARM_2D_NORMAL = new Vec3d(0.0, 0.0, 1.0);
    private static final int UMBILICAL_MAIN_COORDS = 80;
    private static final int UMBILICAL_SMALL_CORDS = 14;
    private static final int UMBILICAL_SMALL_COORDS = 20;
    private static final int UMBILICAL_TETHER_INDEX = UMBILICAL_MAIN_COORDS - 20;
    private static final float UMBILICAL_MAIN_SPACING_PIXELS = 10.0f;
    private static final String[] UMBILICAL_MODEL_CANDIDATES = {
            "CentipedeSegment",
            "CentipedeBackShell",
            "CentipedeShell"
    };

    private static FAtlasElement circleSprite;
    private static FAtlasElement eyeSprite;
    private static FAtlasElement glyphSprite;
    private static FAtlasElement mirosLegSmallPartSprite;
    private static FAtlasElement karmaPetalSprite;
    private static FAtlasElement lizardScaleSprite;
    private static FAtlasElement mouseEyeA5Sprite;
    private static FAtlasElement moonSigilSprite;
    private static Object umbilicalSegmentModel;
    private static final Map<String, Object> atlasSpriteModels = new HashMap<>();
    private static final Map<Object, SpriteModelSnapshot> atlasSpriteModelSnapshots = new IdentityHashMap<>();
    private static final Map<Integer, OracleUmbilicalState> umbilicalStates = new HashMap<>();
    private static boolean triedLoadSprites;
    private static boolean triedLoadUmbilicalModel;

    public OracleEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = px(6.0f);
    }

    @Override
    public void render(OracleEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        int fullBright = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        Vec3d renderOrigin = entity.getLerpedPos(tickDelta);
        Vec3d bodyCenter = new Vec3d(0.0, entity.getHeight() * 0.58, 0.0);
        Vec3d lookLocal = entity.getSyncedLookTarget().subtract(renderOrigin);
        Vec3d forward = safeNormalize(lookLocal.subtract(bodyCenter), entity.getSyncedGetToDir());
        Vec3d[] frame = makeFrame(forward);
        Vec3d right = frame[0];
        Vec3d up = frame[1];
        OraclePose pose = computePose(entity, bodyCenter, forward, right, up, tickDelta);

        VertexConsumer solid = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE_TEX));
        VertexConsumer translucent = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE_TEX));
        VertexConsumer lines = vertexConsumers.getBuffer(RenderLayer.LINES);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        renderRailDebug(entity, lines, matrix, renderOrigin, fullBright);
        renderOracleBody(entity, solid, translucent, matrix, pose, light);
        renderArm(entity, solid, translucent, matrix, pose, renderOrigin, tickDelta, light);
        renderUmbilical(entity, vertexConsumers, solid, matrix, pose, renderOrigin, tickDelta, light);
        renderDebugSkeleton(entity, lines, matrix, pose, renderOrigin, tickDelta, fullBright);
        renderDebugHitbox(entity, lines, matrix, renderOrigin, fullBright);
        renderSpriteAccents(entity, vertexConsumers, matrix, pose, right, up, forward, fullBright);
        renderOracleFSprites(entity, vertexConsumers, matrix, pose, right, up, forward, renderOrigin, fullBright, tickDelta);
        renderLookLine(lines, matrix, pose.head, lookLocal, fullBright);

        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    private static OraclePose computePose(OracleEntity entity, Vec3d bodyCenter, Vec3d forward, Vec3d right, Vec3d up, float tickDelta) {
        float t = entity.age + tickDelta;
        boolean moon = entity.getOracleId() == OracleId.LOOKS_TO_THE_MOON;
        float breath = 0.5f + 0.5f * MathHelper.sin(t * 0.11f);
        Vec3d axis = moon ? new Vec3d(-0.2, -0.95, 0.0).normalize() : forward.multiply(-0.18).add(0.0, -1.0, 0.0).normalize();

        Vec3d chest = bodyCenter.add(up.multiply(0.42 + breath * 0.025));
        Vec3d hips = chest.add(axis.multiply(moon ? 0.74 : 0.88));
        Vec3d head = chest.add(forward.multiply(0.14)).add(up.multiply(0.38 + breath * 0.018));
        Vec3d neck = chest.add(up.multiply(0.18));

        Vec3d leftHand = chest.add(right.multiply(-0.52)).add(axis.multiply(0.38)).add(up.multiply(MathHelper.sin(t * 0.065f) * 0.08));
        Vec3d rightHand = chest.add(right.multiply(0.52)).add(axis.multiply(0.38)).add(up.multiply(MathHelper.sin(t * 0.071f + 1.7f) * 0.08));
        Vec3d leftFoot = hips.add(right.multiply(-0.28)).add(axis.multiply(0.48));
        Vec3d rightFoot = hips.add(right.multiply(0.28)).add(axis.multiply(0.48));

        return new OraclePose(chest, hips, neck, head, leftHand, rightHand, leftFoot, rightFoot);
    }

    private static void renderOracleBody(OracleEntity entity, VertexConsumer solid, VertexConsumer translucent,
                                         Matrix4f matrix, OraclePose pose, int light) {
        int skin = entity.getOracleId().skinColor();
        int robe = entity.getOracleId().robeColor();
        float robeAlpha = entity.getOracleId() == OracleId.LOOKS_TO_THE_MOON ? 150f : 190f;

        renderSphere(solid, matrix, pose.head, px(5.0f), skin, 255, light);
        renderTube(solid, matrix, List.of(pose.neck, pose.chest), px(1.5f), px(2.0f), skin, 255, light);
        renderTube(translucent, matrix, List.of(pose.chest, pose.hips), px(6.0f), px(4.0f), robe, (int) robeAlpha, light);
        renderTube(solid, matrix, List.of(pose.chest, pose.leftHand), px(1.0f), px(0.75f), skin, 230, light);
        renderTube(solid, matrix, List.of(pose.chest, pose.rightHand), px(1.0f), px(0.75f), skin, 230, light);
        renderTube(solid, matrix, List.of(pose.hips, pose.leftFoot), px(1.0f), px(0.75f), skin, 230, light);
        renderTube(solid, matrix, List.of(pose.hips, pose.rightFoot), px(1.0f), px(0.75f), skin, 230, light);
        renderSphere(solid, matrix, pose.leftHand, px(2.0f), skin, 255, light);
        renderSphere(solid, matrix, pose.rightHand, px(2.0f), skin, 255, light);
        renderSphere(solid, matrix, pose.leftFoot, px(2.0f), skin, 255, light);
        renderSphere(solid, matrix, pose.rightFoot, px(2.0f), skin, 255, light);
    }

    private static void renderArm(OracleEntity entity, VertexConsumer solid, VertexConsumer translucent,
                                  Matrix4f matrix, OraclePose pose, Vec3d renderOrigin, float tickDelta, int light) {
        int metalColor = entity.getOracleId().armColor();
        int baseColor = oracleArmSegmentColor(entity, false);
        int highlightColor = oracleArmSegmentColor(entity, true);
        Vec3d faceNormal = armPlaneNormal();
        List<Vec3d> points = armVisualPoints(entity, tickDelta, faceNormal, pose.hips, renderOrigin);
        for (int i = 0; i < points.size() - 1; i++) {
            int logicalIndex = Math.min(i / 2, 3);
            float width = switch (i) {
                case 0 -> px(7.0f);
                case 1, 2 -> px(5.0f);
                case 3, 4 -> px(4.0f);
                default -> px(3.0f);
            };
            renderArmSegmentStrip(solid, matrix, points.get(i), points.get(i + 1), faceNormal,
                    width, baseColor, highlightColor, logicalIndex, light);
        }
        for (int i = 0; i < points.size() - 1; i++) {
            renderArmCollar(solid, matrix, points.get(i), faceNormal, metalColor, light);
        }
    }

    private static void renderArmSegmentStrip(VertexConsumer vc, Matrix4f matrix, Vec3d start, Vec3d end, Vec3d normal,
                                              float halfWidth, int baseColor, int highlightColor, int segmentIndex, int light) {
        Vec3d chord = end.subtract(start);
        if (chord.lengthSquared() < 1.0E-5) {
            return;
        }
        Vec3d tangent = chord.normalize();
        Vec3d side = safeNormalize(normal.crossProduct(tangent), makeFrame(tangent)[0]);
        double bendAmount = segmentIndex == 0 ? 0.0 : Math.min(0.08, chord.length() * 0.025) * (segmentIndex % 2 == 0 ? 1.0 : -1.0);
        Vec3d control = start.lerp(end, 0.5).add(side.multiply(bendAmount));

        int steps = 7;
        Vec3d[] centers = new Vec3d[steps + 1];
        Vec3d[] sides = new Vec3d[steps + 1];
        float[] widths = new float[steps + 1];
        Vec3d thickness = normal.multiply(px(1.0f));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            centers[i] = quadratic(start, control, end, t);
            Vec3d previous = quadratic(start, control, end, Math.max(0f, t - 1f / steps));
            Vec3d next = quadratic(start, control, end, Math.min(1f, t + 1f / steps));
            Vec3d sampleTangent = safeNormalize(next.subtract(previous), tangent);
            sides[i] = safeNormalize(normal.crossProduct(sampleTangent), side);
            widths[i] = armStripHalfWidth(halfWidth, t);
        }

        int baseR = colorR(baseColor);
        int baseG = colorG(baseColor);
        int baseB = colorB(baseColor);
        int highR = colorR(highlightColor);
        int highG = colorG(highlightColor);
        int highB = colorB(highlightColor);
        for (int i = 0; i < steps; i++) {
            Vec3d p0 = centers[i];
            Vec3d p1 = centers[i + 1];
            Vec3d s0 = sides[i];
            Vec3d s1 = sides[i + 1];
            float w0 = widths[i];
            float w1 = widths[i + 1];
            Vec3d f0l = p0.subtract(s0.multiply(w0)).add(thickness);
            Vec3d f0r = p0.add(s0.multiply(w0)).add(thickness);
            Vec3d f1l = p1.subtract(s1.multiply(w1)).add(thickness);
            Vec3d f1r = p1.add(s1.multiply(w1)).add(thickness);
            Vec3d b0l = p0.subtract(s0.multiply(w0)).subtract(thickness);
            Vec3d b0r = p0.add(s0.multiply(w0)).subtract(thickness);
            Vec3d b1l = p1.subtract(s1.multiply(w1)).subtract(thickness);
            Vec3d b1r = p1.add(s1.multiply(w1)).subtract(thickness);
            Vec3d segmentTangent = safeNormalize(p1.subtract(p0), tangent);
            Vec3d leftNormal = safeNormalize(s0.add(s1).multiply(-1.0), side.negate());
            Vec3d rightNormal = safeNormalize(s0.add(s1), side);

            emitQuadCorners(vc, matrix, f0l, f0r, f1r, f1l, normal, baseR, baseG, baseB, 245, light);
            emitQuadCorners(vc, matrix, b0r, b0l, b1l, b1r, normal.negate(), baseR, baseG, baseB, 245, light);
            emitQuadCorners(vc, matrix, f0l, f1l, b1l, b0l, leftNormal, baseR, baseG, baseB, 245, light);
            emitQuadCorners(vc, matrix, f1r, f0r, b0r, b1r, rightNormal, baseR, baseG, baseB, 245, light);
            emitQuadCorners(vc, matrix, f0r, f0l, b0l, b0r, segmentTangent.negate(), baseR, baseG, baseB, 245, light);
            emitQuadCorners(vc, matrix, f1l, f1r, b1r, b1l, segmentTangent, baseR, baseG, baseB, 245, light);
            emitQuadCorners(vc, matrix,
                    p0.subtract(s0.multiply(w0 * 0.42)).add(thickness).add(normal.multiply(0.008)),
                    p0.add(s0.multiply(w0 * 0.42)).add(thickness).add(normal.multiply(0.008)),
                    p1.add(s1.multiply(w1 * 0.42)).add(thickness).add(normal.multiply(0.008)),
                    p1.subtract(s1.multiply(w1 * 0.42)).add(thickness).add(normal.multiply(0.008)),
                    normal,
                    highR, highG, highB, 220, light);
        }
    }

    private static float armStripHalfWidth(float baseWidth, float t) {
        float endBulk = 1f - MathHelper.sin(t * (float) Math.PI);
        float collarBulk = Math.max(
                MathHelper.sin(MathHelper.clamp(t / 0.22f, 0f, 1f) * (float) Math.PI),
                MathHelper.sin(MathHelper.clamp((1f - t) / 0.22f, 0f, 1f) * (float) Math.PI)
        );
        return baseWidth * (0.58f + endBulk * 0.22f + collarBulk * 0.18f);
    }

    private static void renderArmCollar(VertexConsumer vc, Matrix4f matrix, Vec3d point, Vec3d normal, int color, int light) {
        Vec3d[] frame = makeFrame(normal);
        int metal = lighten(color, 0.10f);
        emitQuad(vc, matrix, point.add(normal.multiply(px(0.3f))), frame[0], frame[1], normal,
                px(7.0f), px(7.0f), colorR(metal), colorG(metal), colorB(metal), 235, light);
    }

    private static List<Vec3d> armVisualPoints(OracleEntity entity, float tickDelta, Vec3d faceNormal,
                                               Vec3d lowerBodyAnchor, Vec3d renderOrigin) {
        Joint[] joints = entity.getArm().joints();
        if (joints.length == 0) {
            return List.of(flattenLocalToArmPlane(entity, lowerBodyAnchor, renderOrigin));
        }

        Vec3d[] localJoints = new Vec3d[joints.length];
        for (Joint joint : joints) {
            JointView view = joint.view();
            localJoints[view.index()] = flattenWorldToArmPlane(entity, lerp(view.lastPos(), view.pos(), tickDelta)).subtract(renderOrigin);
        }
        localJoints[0] = localJoints[0].add(armRootJointOffset(entity));
        Vec3d body = flattenWorldToArmPlane(entity, lowerBodyAnchor.add(renderOrigin)).subtract(renderOrigin);

        List<Vec3d> points = new ArrayList<>(joints.length * 2);
        points.add(localJoints[0]);
        for (int i = 0; i < joints.length; i++) {
            Vec3d start = localJoints[i];
            Vec3d end = i + 1 < joints.length ? localJoints[i + 1] : body;
            double length = joints[i].view().length();
            double firstLength = length / 3.0;
            double secondLength = i + 1 < joints.length ? length * 2.0 / 3.0 : length / 3.0;
            points.add(solveKnee2D(start, end, firstLength, secondLength, i));
            points.add(end);
        }
        return collideArmVisualPoints(entity, points, renderOrigin);
    }

    private static Vec3d armBendPole(Vec3d start, Vec3d end, Vec3d faceNormal, int index) {
        Vec3d chord = end.subtract(start);
        Vec3d side = faceNormal.crossProduct(chord);
        if (side.lengthSquared() < 1.0E-6) {
            side = makeFrame(safeNormalize(chord, new Vec3d(0.0, 1.0, 0.0)))[0];
        }
        return side.multiply(index % 2 == 0 ? 1.0 : -1.0);
    }

    private static Vec3d armPlaneNormal() {
        return ARM_2D_NORMAL;
    }

    private static Vec3d flattenWorldToArmPlane(OracleEntity entity, Vec3d worldPoint) {
        return new Vec3d(worldPoint.x, worldPoint.y, entity.getChamberCenter().z);
    }

    private static Vec3d flattenLocalToArmPlane(OracleEntity entity, Vec3d localPoint, Vec3d renderOrigin) {
        return flattenWorldToArmPlane(entity, localPoint.add(renderOrigin)).subtract(renderOrigin);
    }

    private static Vec3d armRootJointOffset(OracleEntity entity) {
        Vec3d baseDir = entity.chamberTrackInwardDir(entity.getSyncedBaseTarget()).negate();
        Vec3d awayFromRail = baseDir.negate();
        return awayFromRail.multiply(px(ARM_ROOT_CENTER_FROM_RAIL_PIXELS + ARM_ROOT_HALF_FROM_CENTER_PIXELS)
                + ARM_ROOT_SOCKET_SURFACE_MARGIN_BLOCKS);
    }

    private static List<Vec3d> collideArmVisualPoints(OracleEntity entity, List<Vec3d> localPoints, Vec3d renderOrigin) {
        if (localPoints.size() < 3) {
            return localPoints;
        }
        List<Vec3d> collided = new ArrayList<>(localPoints);
        for (int i = 1; i < collided.size() - 1; i++) {
            Vec3d worldPoint = collided.get(i).add(renderOrigin);
            Vec3d adjusted = OraclePhysicsUtil.collidePoint(entity.getWorld(), worldPoint,
                    ARM_VISUAL_COLLISION_RADIUS_BLOCKS, entity.getOracleCollisionCache());
            collided.set(i, flattenWorldToArmPlane(entity, adjusted).subtract(renderOrigin));
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < collided.size() - 1; i++) {
                Vec3d start = collided.get(i).add(renderOrigin);
                Vec3d end = collided.get(i + 1).add(renderOrigin);
                Vec3d correction = OraclePhysicsUtil.segmentCollisionCorrection(entity.getWorld(), start, end,
                        ARM_VISUAL_COLLISION_RADIUS_BLOCKS, ARM_VISUAL_COLLISION_SAMPLE_SPACING_BLOCKS,
                        entity.getOracleCollisionCache());
                correction = new Vec3d(correction.x, correction.y, 0.0);
                if (correction.lengthSquared() < 1.0E-8) {
                    continue;
                }
                boolean startPinned = i == 0;
                boolean endPinned = i + 1 == collided.size() - 1;
                if (!startPinned) {
                    collided.set(i, collided.get(i).add(correction.multiply(endPinned ? 1.0 : 0.5)));
                }
                if (!endPinned) {
                    collided.set(i + 1, collided.get(i + 1).add(correction.multiply(startPinned ? 1.0 : 0.5)));
                }
            }
        }
        for (int i = 0; i < collided.size(); i++) {
            collided.set(i, flattenLocalToArmPlane(entity, collided.get(i), renderOrigin));
        }
        return collided;
    }

    private static Vec3d umbilicalTetherAnchor(List<Vec3d> armPoints, Vec3d faceNormal) {
        if (armPoints.size() < 4) {
            return armPoints.isEmpty() ? Vec3d.ZERO : armPoints.get(armPoints.size() - 1);
        }
        Vec3d joint = armPoints.get(2);
        Vec3d elbow = armPoints.get(3);
        Vec3d segment = elbow.subtract(joint);
        Vec3d perpendicular = safeNormalize(faceNormal.crossProduct(segment), new Vec3d(0.0, 1.0, 0.0));
        return joint.lerp(elbow, 0.4).add(perpendicular.multiply(px(8.0f)));
    }

    private static Vec3d quadratic(Vec3d a, Vec3d b, Vec3d c, float t) {
        float inv = 1f - t;
        return a.multiply(inv * inv).add(b.multiply(2f * inv * t)).add(c.multiply(t * t));
    }

    private static int oracleArmSegmentColor(OracleEntity entity, boolean highlight) {
        if (entity.getOracleId() == OracleId.LOOKS_TO_THE_MOON) {
            return highlight ? 0xB5C4BD : 0x879991;
        }
        return highlight ? 0xD0B4AE : 0xB3918A;
    }

    private static void renderUmbilical(OracleEntity entity, VertexConsumerProvider vertexConsumers, VertexConsumer fallbackVc,
                                        Matrix4f matrix, OraclePose pose, Vec3d renderOrigin, float tickDelta, int light) {
        int color = entity.getOracleId() == OracleId.LOOKS_TO_THE_MOON ? 0x303742 : 0x08060B;
        float t = entity.age + tickDelta;
        boolean zeroG = entity.isZeroG();
        Object model = getUmbilicalSegmentModel();

        if (entity.getOracleId() == OracleId.FIVE_PEBBLES) {
            OracleUmbilicalState state = umbilicalStates.computeIfAbsent(entity.getId(), OracleUmbilicalState::new);
            List<Vec3d> armPoints = armVisualPoints(entity, tickDelta, armPlaneNormal(), pose.hips, renderOrigin);
            Vec3d chamberCenter = entity.getChamberCenter();
            Vec3d bottomRailAnchor = chamberCenter.add(0.0, -OracleEntity.CHAMBER_TRACK_HALF_WIDTH, 0.0);
            Vec3d tetherAnchor = umbilicalTetherAnchor(armPoints, armPlaneNormal()).add(renderOrigin);
            Vec3d firstChunkAnchor = pose.chest.add(renderOrigin);
            Vec3d headAnchor = pose.head.add(renderOrigin);
            Vec3d headLookDir = safeNormalize(entity.getSyncedLookTarget().subtract(headAnchor), entity.getSyncedGetToDir());
            state.update(entity, entity.age, bottomRailAnchor, tetherAnchor, firstChunkAnchor, headAnchor, headLookDir, zeroG);

            List<Vec3d> points = state.mainPoints(tickDelta, renderOrigin);
            if (model != null && modelTexture(model) != null) {
                try {
                    renderUmbilicalModels(vertexConsumers, matrix, model, points, px(1.2f), color, lighten(color, 0.28f), light);
                    for (int i = 0; i < UMBILICAL_SMALL_CORDS; i++) {
                        int cordColor = state.smallCordColor(i, color);
                        renderUmbilicalModels(vertexConsumers, matrix, model, state.smallCordPoints(i, tickDelta, renderOrigin),
                                px(0.5f), cordColor, lighten(cordColor, 0.25f), light);
                    }
                    return;
                } catch (RuntimeException | LinkageError ignored) {
                    umbilicalSegmentModel = null;
                    triedLoadUmbilicalModel = true;
                }
            }
            renderTube(fallbackVc, matrix, points, px(1.2f), px(0.9f), color, 235, light);
            for (int i = 0; i < UMBILICAL_SMALL_CORDS; i++) {
                int cordColor = state.smallCordColor(i, color);
                renderTube(fallbackVc, matrix, state.smallCordPoints(i, tickDelta, renderOrigin),
                        px(0.5f), px(0.35f), cordColor, 235, light);
            }
            return;
        }

        List<Vec3d> points = new ArrayList<>();
        Vec3d body = pose.hips;
        points.add(body);
        points.add(body.add(-0.25, -0.5, MathHelper.sin(t * 0.05f) * 0.12f));
        points.add(body.add(-0.65, -0.9, MathHelper.cos(t * 0.04f) * 0.18f));
        points.add(body.add(-1.05, -1.08, 0.0));

        if (model != null && modelTexture(model) != null) {
            try {
                renderUmbilicalModels(vertexConsumers, matrix, model, points, px(1.2f), color, lighten(color, 0.28f), light);
                renderMoonDisconnectedCords(vertexConsumers, matrix, model, pose.hips, t, color, zeroG, light);
                return;
            } catch (RuntimeException | LinkageError ignored) {
                umbilicalSegmentModel = null;
                triedLoadUmbilicalModel = true;
            }
        }

        renderTube(fallbackVc, matrix, points, px(1.2f), px(0.9f), color, 235, light);
    }

    private static Object getUmbilicalSegmentModel() {
        if (triedLoadUmbilicalModel) {
            return umbilicalSegmentModel;
        }
        triedLoadUmbilicalModel = true;
        try {
            var atlas = LibrainworldmcClient.getAtlasManager();
            for (String candidate : UMBILICAL_MODEL_CANDIDATES) {
                Object model = invokeAtlasModelLookup(atlas, candidate, UMBILICAL_MODEL_DEPTH_PIXELS);
                if (model != null && modelTexture(model) != null) {
                    umbilicalSegmentModel = model;
                    break;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            umbilicalSegmentModel = null;
        }
        return umbilicalSegmentModel;
    }

    private static Object invokeAtlasModelLookup(Object atlas, String name, float depth)
            throws ReflectiveOperationException {
        Method method = atlas.getClass().getMethod("getModelWithName", String.class, float.class);
        return method.invoke(atlas, name, depth);
    }

    private static void renderUmbilicalModels(VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                              Object model, List<Vec3d> points,
                                              float halfWidth, int baseColor, int highlightColor, int light) {
        SpriteModelSnapshot snapshot = spriteModelSnapshot(model);
        if (snapshot == null) {
            return;
        }
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(snapshot.texture()));
        Vec3d faceHint = new Vec3d(0.0, 1.0, 0.0);
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3d start = points.get(i);
            Vec3d end = points.get(i + 1);
            if (start.squaredDistanceTo(end) < 1.0E-5) {
                continue;
            }
            renderSpriteModelSpan(vc, matrix, snapshot, start, end, halfWidth, faceHint, baseColor, highlightColor, 235, light);
        }
    }

    private static void renderPebblesSmallCords(VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                                Object model, Vec3d root, Vec3d head,
                                                float age, int baseColor, int light) {
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI * 2.0 / 8.0;
            double phase = age * (0.035 + i * 0.002) + i * 1.73;
            Vec3d side = new Vec3d(Math.cos(angle), Math.sin(phase) * 0.25, Math.sin(angle));
            Vec3d mid = root.lerp(head, 0.48)
                    .add(side.multiply(0.16 + 0.035 * (i % 3)))
                    .add(0.0, Math.sin(phase) * 0.10, 0.0);
            List<Vec3d> cord = List.of(root.add(side.multiply(0.035)), mid, head.add(side.multiply(0.055)));
            int color = switch (i % 3) {
                case 1 -> mixColor(0xFF0000, baseColor, 0.55f);
                case 2 -> mixColor(0x0000FF, baseColor, 0.55f);
                default -> lighten(baseColor, 0.18f);
            };
            renderUmbilicalModels(vertexConsumers, matrix, model, cord, px(0.5f), color, lighten(color, 0.25f), light);
        }
    }

    private static void renderMoonDisconnectedCords(VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                                    Object model, Vec3d root, float age, int baseColor, boolean zeroG, int light) {
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI * 2.0 / 6.0;
            double length = 0.22 + (i % 4) * 0.075;
            Vec3d dir = new Vec3d(Math.cos(angle) * 0.55, zeroG ? Math.sin(age * 0.02 + i) * 0.18 : -0.8, Math.sin(angle) * 0.55).normalize();
            double floatY = zeroG ? Math.sin(age * 0.05 + i) * 0.055 : Math.sin(age * 0.05 + i) * 0.035;
            Vec3d mid = root.add(dir.multiply(length * 0.55)).add(0.0, floatY, 0.0);
            Vec3d end = root.add(dir.multiply(length)).add(0.0, zeroG ? Math.cos(age * 0.04 + i) * 0.07 : Math.cos(age * 0.04 + i) * 0.05, 0.0);
            int color = i % 2 == 0 ? lighten(baseColor, 0.18f) : mixColor(0x334CFF, baseColor, 0.45f);
            renderUmbilicalModels(vertexConsumers, matrix, model, List.of(root, mid, end), px(0.5f), color, lighten(color, 0.25f), light);
        }
    }

    private static void renderSpriteModelSpan(VertexConsumer vc, Matrix4f matrix,
                                              SpriteModelSnapshot model, Vec3d start, Vec3d end,
                                              float halfWidth, Vec3d faceHint,
                                              int baseColor, int highlightColor, int alpha, int light) {
        Vec3d limbDir = end.subtract(start);
        double limbLen = limbDir.length();
        if (limbLen < 0.001) {
            return;
        }

        Vec3d tangent = limbDir.normalize();
        Vec3d face = faceHint.subtract(tangent.multiply(faceHint.dotProduct(tangent)));
        if (face.lengthSquared() < 0.001) {
            face = tangent.crossProduct(new Vec3d(0.0, 1.0, 0.0));
            if (face.lengthSquared() < 0.001) {
                face = tangent.crossProduct(new Vec3d(1.0, 0.0, 0.0));
            }
        }
        face = face.normalize();

        Vec3d widthDir = tangent.crossProduct(face);
        if (widthDir.lengthSquared() < 0.001) {
            widthDir = face;
        } else {
            widthDir = widthDir.normalize();
        }

        float modelWidth = model.width();
        float modelHeight = model.height();
        float xScale = (halfWidth * 2f) / modelWidth;
        float yScale = (float) (limbLen / modelHeight);
        float zScale = xScale;

        int baseR = (baseColor >> 16) & 0xFF;
        int baseG = (baseColor >> 8) & 0xFF;
        int baseB = baseColor & 0xFF;
        int highR = (highlightColor >> 16) & 0xFF;
        int highG = (highlightColor >> 8) & 0xFF;
        int highB = highlightColor & 0xFF;

        for (Object quad : model.quads()) {
            Vec3d normal = transformModelNormal(widthDir, tangent, face,
                    invokeFloat(quad, "normalX"), invokeFloat(quad, "normalY"), invokeFloat(quad, "normalZ"));
            emitSpriteModelVertex(matrix, vc, modelWidth, modelHeight, xScale, yScale, zScale,
                    start, widthDir, tangent, face, normal, invokeObject(quad, "a"),
                    baseR, baseG, baseB, highR, highG, highB, alpha, light);
            emitSpriteModelVertex(matrix, vc, modelWidth, modelHeight, xScale, yScale, zScale,
                    start, widthDir, tangent, face, normal, invokeObject(quad, "b"),
                    baseR, baseG, baseB, highR, highG, highB, alpha, light);
            emitSpriteModelVertex(matrix, vc, modelWidth, modelHeight, xScale, yScale, zScale,
                    start, widthDir, tangent, face, normal, invokeObject(quad, "c"),
                    baseR, baseG, baseB, highR, highG, highB, alpha, light);
            emitSpriteModelVertex(matrix, vc, modelWidth, modelHeight, xScale, yScale, zScale,
                    start, widthDir, tangent, face, normal, invokeObject(quad, "d"),
                    baseR, baseG, baseB, highR, highG, highB, alpha, light);
        }
    }

    private static SpriteModelSnapshot spriteModelSnapshot(Object model) {
        if (model == null) {
            return null;
        }
        SpriteModelSnapshot cached = atlasSpriteModelSnapshots.get(model);
        if (cached != null) {
            return cached;
        }
        Identifier texture = modelTexture(model);
        List<Object> quads = modelQuads(model);
        if (texture == null || quads.isEmpty()) {
            return null;
        }
        SpriteModelSnapshot snapshot = new SpriteModelSnapshot(texture, Math.max(modelWidth(model), 1f), Math.max(modelHeight(model), 1f), quads);
        atlasSpriteModelSnapshots.put(model, snapshot);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> modelQuads(Object model) {
        try {
            Object quads = invokeObject(model, "quads");
            if (quads instanceof List<?> list) {
                return (List<Object>) list;
            }
        } catch (RuntimeException ignored) {
        }
        return List.of();
    }

    private static Identifier modelTexture(Object model) {
        try {
            Object element = invokeObject(model, "element");
            if (element instanceof FAtlasElement atlasElement) {
                return atlasElement.textureIdentifier;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static float modelWidth(Object model) {
        return invokeFloat(model, "width");
    }

    private static float modelHeight(Object model) {
        return invokeFloat(model, "height");
    }

    private static Vec3d transformModelNormal(Vec3d widthDir, Vec3d tangent, Vec3d face,
                                              float normalX, float normalY, float normalZ) {
        Vec3d normal = widthDir.multiply(normalX)
                .add(tangent.multiply(normalY))
                .add(face.multiply(normalZ));
        if (normal.lengthSquared() < 0.001) {
            return face;
        }
        return normal.normalize();
    }

    private static void emitSpriteModelVertex(Matrix4f matrix, VertexConsumer vc,
                                              float modelWidth, float modelHeight,
                                              float xScale, float yScale, float zScale,
                                              Vec3d start, Vec3d widthDir, Vec3d tangent, Vec3d face, Vec3d normal,
                                              Object vertex,
                                              int baseR, int baseG, int baseB,
                                              int highR, int highG, int highB,
                                              int alpha, int light) {
        float vx = invokeFloat(vertex, "x");
        float vy = invokeFloat(vertex, "y");
        float vz = invokeFloat(vertex, "z");
        float x = (vx - modelWidth * 0.5f) * xScale;
        float y = vy * yScale;
        float z = vz * zScale;

        Vec3d pos = start
                .add(widthDir.multiply(x))
                .add(tangent.multiply(y))
                .add(face.multiply(z));

        float colorT = MathHelper.clamp(vy / modelHeight, 0f, 1f);
        int r = MathHelper.lerp(colorT, baseR, highR);
        int g = MathHelper.lerp(colorT, baseG, highG);
        int b = MathHelper.lerp(colorT, baseB, highB);

        vc.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, alpha)
                .texture(invokeFloat(vertex, "u"), invokeFloat(vertex, "v"))
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static Object invokeObject(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Could not read atlas model method " + methodName, e);
        }
    }

    private static float invokeFloat(Object target, String methodName) {
        Object value = invokeObject(target, methodName);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return 0f;
    }

    private static void renderDebugSkeleton(OracleEntity entity, VertexConsumer lines, Matrix4f matrix, OraclePose pose,
                                            Vec3d renderOrigin, float tickDelta, int light) {
        int lineColor = entity.getOracleId() == OracleId.FIVE_PEBBLES ? 0xFF4CC7 : 0x47C7FF;
        line(lines, matrix, pose.hips, pose.chest, lineColor, light);
        line(lines, matrix, pose.chest, pose.neck, 0xFFFFFF, light);
        line(lines, matrix, pose.neck, pose.head, 0xFFFFFF, light);
        line(lines, matrix, pose.chest, pose.leftHand, 0x46FF85, light);
        line(lines, matrix, pose.chest, pose.rightHand, 0x46FF85, light);
        line(lines, matrix, pose.hips, pose.leftFoot, 0x46A5FF, light);
        line(lines, matrix, pose.hips, pose.rightFoot, 0x46A5FF, light);

        List<Vec3d> armPoints = armVisualPoints(entity, tickDelta, armPlaneNormal(), pose.hips, renderOrigin);
        for (int i = 0; i < armPoints.size() - 1; i++) {
            line(lines, matrix, armPoints.get(i), armPoints.get(i + 1), 0xFFD34D, light);
        }
    }

    private static void renderDebugHitbox(OracleEntity entity, VertexConsumer lines, Matrix4f matrix, Vec3d renderOrigin, int light) {
        Box box = entity.getBoundingBox().offset(renderOrigin.negate());
        Vec3d a = new Vec3d(box.minX, box.minY, box.minZ);
        Vec3d b = new Vec3d(box.maxX, box.minY, box.minZ);
        Vec3d c = new Vec3d(box.maxX, box.minY, box.maxZ);
        Vec3d d = new Vec3d(box.minX, box.minY, box.maxZ);
        Vec3d e = new Vec3d(box.minX, box.maxY, box.minZ);
        Vec3d f = new Vec3d(box.maxX, box.maxY, box.minZ);
        Vec3d g = new Vec3d(box.maxX, box.maxY, box.maxZ);
        Vec3d h = new Vec3d(box.minX, box.maxY, box.maxZ);
        int color = 0xFF3030;
        line(lines, matrix, a, b, color, light);
        line(lines, matrix, b, c, color, light);
        line(lines, matrix, c, d, color, light);
        line(lines, matrix, d, a, color, light);
        line(lines, matrix, e, f, color, light);
        line(lines, matrix, f, g, color, light);
        line(lines, matrix, g, h, color, light);
        line(lines, matrix, h, e, color, light);
        line(lines, matrix, a, e, color, light);
        line(lines, matrix, b, f, color, light);
        line(lines, matrix, c, g, color, light);
        line(lines, matrix, d, h, color, light);
    }

    private static void renderLookLine(VertexConsumer lines, Matrix4f matrix, Vec3d head, Vec3d lookLocal, int light) {
        if (lookLocal.squaredDistanceTo(head) > 1.0E-4) {
            line(lines, matrix, head, lookLocal, 0xB8F7FF, light);
        }
    }

    private static void renderRailDebug(OracleEntity entity, VertexConsumer lines, Matrix4f matrix, Vec3d renderOrigin, int light) {
        Vec3d center = entity.getChamberCenter();
        for (OracleEntity.RailSegment segment : OracleEntity.chamberRailSegments(center)) {
            line(lines, matrix, segment.start().subtract(renderOrigin), segment.end().subtract(renderOrigin), 0x70F5FF, light);
        }
        for (Vec3d junction : OracleEntity.chamberRailJunctions(center)) {
            Vec3d local = junction.subtract(renderOrigin);
            line(lines, matrix, local.add(-0.16, 0.0, 0.0), local.add(0.16, 0.0, 0.0), 0xFFFFFF, light);
            line(lines, matrix, local.add(0.0, -0.16, 0.0), local.add(0.0, 0.16, 0.0), 0xFFFFFF, light);
            line(lines, matrix, local.add(0.0, 0.0, -0.16), local.add(0.0, 0.0, 0.16), 0xFFFFFF, light);
        }
    }

    private static void renderSpriteAccents(OracleEntity entity, VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                            OraclePose pose, Vec3d right, Vec3d up, Vec3d forward, int light) {
        loadSprites();
        FAtlasElement eye = eyeSprite != null ? eyeSprite : circleSprite;
        String eyeName = eyeSprite != null ? "deerEyeB" : "Circle20";
        if (eye != null) {
            float side = 0.075f;
            Vec3d eyeRight = right.multiply(0.075);
            Vec3d eyeCenter = pose.head.add(forward.multiply(0.17)).add(up.multiply(0.02));
            renderAtlasModelOrQuad(eyeName, vertexConsumers, matrix, eye,
                    eyeCenter.subtract(eyeRight), right, up, forward, side * 0.48f, side * 0.28f, 255, 255, 255, 245, light, false);
            renderAtlasModelOrQuad(eyeName, vertexConsumers, matrix, eye,
                    eyeCenter.add(eyeRight), right, up, forward, side * 0.48f, side * 0.28f, 255, 255, 255, 245, light, false);
            if (entity.getOracleId() == OracleId.LOOKS_TO_THE_MOON) {
                renderAtlasModelOrQuad(eyeName, vertexConsumers, matrix, eye,
                        eyeCenter.subtract(up.multiply(0.09)), right, up, forward, side * 0.33f, side * 0.16f,
                        200, 60, 220, 230, light, false);
            }
        }

        if (glyphSprite != null && entity.getOracleId() == OracleId.FIVE_PEBBLES) {
            Vec3d halo = pose.head.add(up.multiply(0.36));
            float pulse = 0.09f + MathHelper.sin((float) (halo.x + halo.y + halo.z) * 0.5f) * 0.015f;
            renderAtlasModelOrQuad("haloGlyph-1", vertexConsumers, matrix, glyphSprite,
                    halo, right, up, forward, pulse, pulse, 255, 210, 96, 180, light, true);
        }
    }

    private static void renderOracleFSprites(OracleEntity entity, VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                             OraclePose pose, Vec3d right, Vec3d up, Vec3d forward,
                                             Vec3d renderOrigin, int light, float tickDelta) {
        loadSprites();
        int skin = entity.getOracleId().skinColor();
        int robe = entity.getOracleId().robeColor();
        int arm = entity.getOracleId().armColor();
        float age = entity.age + tickDelta;

        renderBodyCircleSprites(vertexConsumers, matrix, pose, right, up, forward, skin, robe, light);
        renderPhoneSprites(entity, vertexConsumers, matrix, pose, right, up, forward, arm, light);
        renderHandFootGlyphs(vertexConsumers, matrix, pose, right, up, forward, skin, light);
        renderArmBaseFSprites(entity, vertexConsumers, matrix, pose, forward, renderOrigin, arm, tickDelta, light);
        renderArmFSprites(entity, vertexConsumers, matrix, pose, right, up, forward, renderOrigin, arm, tickDelta, light);
        renderFutileWhiteGlow(entity, vertexConsumers, matrix, pose, right, up, forward, age, light);

        if (entity.getOracleId() == OracleId.FIVE_PEBBLES) {
            renderPebblesHaloAndPetals(vertexConsumers, matrix, pose, right, up, forward, robe, arm, age, light);
        } else {
            renderMoonSprites(vertexConsumers, matrix, pose, right, up, forward, skin, age, light);
        }
    }

    private static void renderBodyCircleSprites(VertexConsumerProvider vertexConsumers, Matrix4f matrix, OraclePose pose,
                                                Vec3d right, Vec3d up, Vec3d forward, int skin, int robe, int light) {
        if (circleSprite == null) {
            return;
        }
        renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, pose.chest, right, up, forward, px(6.0f), px(6.0f),
                colorR(robe), colorG(robe), colorB(robe), 185, light, false);
        renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, pose.hips, right, up, forward, px(6.0f), px(6.0f),
                colorR(robe), colorG(robe), colorB(robe), 150, light, false);
        renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, pose.head, right, up, forward, px(5.55f), px(4.55f),
                colorR(skin), colorG(skin), colorB(skin), 245, light, false);
        Vec3d chin = pose.head.subtract(up.multiply(px(3.6f))).add(forward.multiply(px(0.6f)));
        renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, chin, right, up, forward, px(3.35f), px(3.35f),
                colorR(skin), colorG(skin), colorB(skin), 235, light, false);
    }

    private static void renderPhoneSprites(OracleEntity entity, VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                           OraclePose pose, Vec3d right, Vec3d up, Vec3d forward,
                                           int armColor, int light) {
        if (circleSprite == null && lizardScaleSprite == null) {
            return;
        }
        for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
            double side = sideIndex == 0 ? -1.0 : 1.0;
            Vec3d base = pose.head.add(right.multiply(side * px(3.6f))).subtract(up.multiply(px(0.4f))).subtract(forward.multiply(px(0.2f)));
            if (circleSprite != null) {
                renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, base, right, up, forward, px(2.5f), px(2.75f),
                        colorR(armColor), colorG(armColor), colorB(armColor), 230, light, false);
                renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, base.add(forward.multiply(px(0.25f))), right, up, forward, px(2.0f), px(2.2f),
                        colorR(lighten(armColor, 0.35f)), colorG(lighten(armColor, 0.35f)), colorB(lighten(armColor, 0.35f)), 205, light, false);
            }
            if (lizardScaleSprite != null && entity.getOracleId() == OracleId.FIVE_PEBBLES) {
                Vec3d antennaTip = base.add(right.multiply(side * px(1.2f))).add(up.multiply(px(4.2f))).subtract(forward.multiply(px(0.2f)));
                renderAtlasModelOrQuad("LizardScaleA1", vertexConsumers, matrix, lizardScaleSprite, antennaTip, right, up, forward, px(2.25f), px(8.0f),
                        210, 190, 165, 220, light, false);
            }
        }
    }

    private static void renderHandFootGlyphs(VertexConsumerProvider vertexConsumers, Matrix4f matrix, OraclePose pose,
                                             Vec3d right, Vec3d up, Vec3d forward, int skin, int light) {
        if (glyphSprite == null) {
            return;
        }
        int r = colorR(skin);
        int g = colorG(skin);
        int b = colorB(skin);
        renderAtlasModelOrQuad("haloGlyph-1", vertexConsumers, matrix, glyphSprite, pose.leftHand.add(forward.multiply(0.015)), right, up, forward, 0.070f, 0.070f, r, g, b, 245, light, false);
        renderAtlasModelOrQuad("haloGlyph-1", vertexConsumers, matrix, glyphSprite, pose.rightHand.add(forward.multiply(0.015)), right, up, forward, 0.070f, 0.070f, r, g, b, 245, light, false);
        renderAtlasModelOrQuad("haloGlyph-1", vertexConsumers, matrix, glyphSprite, pose.leftFoot.add(forward.multiply(0.015)), right, up, forward, 0.060f, 0.060f, r, g, b, 235, light, false);
        renderAtlasModelOrQuad("haloGlyph-1", vertexConsumers, matrix, glyphSprite, pose.rightFoot.add(forward.multiply(0.015)), right, up, forward, 0.060f, 0.060f, r, g, b, 235, light, false);
    }

    private static void renderArmFSprites(OracleEntity entity, VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                          OraclePose pose, Vec3d right, Vec3d up, Vec3d forward, Vec3d renderOrigin,
                                          int armColor, float tickDelta, int light) {
        VertexConsumer pixel = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE_TEX));
        int highlight = lighten(armColor, 0.35f);
        Vec3d armNormal = armPlaneNormal();
        List<Vec3d> armPoints = armVisualPoints(entity, tickDelta, armNormal, pose.hips, renderOrigin);
        for (int i = 0; i < armPoints.size() - 1; i++) {
            Vec3d jointLocal = armPoints.get(i);
            Vec3d nextLocal = armPoints.get(i + 1);
            Vec3d jointToNext = safeNormalize(nextLocal.subtract(jointLocal), up);
            Vec3d spriteRight = safeNormalize(armNormal.crossProduct(jointToNext), right);
            Vec3d spriteUp = safeNormalize(jointToNext, up);
            int logicalIndex = Math.min(i / 2, 3);
            float scale = px(Math.max(2.0f, (i % 2 == 0 ? 7.0f : 6.0f) - logicalIndex * 1.2f));
            if (i % 2 == 0) {
                renderCogBars(pixel, matrix, jointLocal.add(jointToNext.multiply(i == 0 ? px(12.0f) : px(2.0f))),
                        jointToNext, armNormal, armColor, logicalIndex, entity.age + tickDelta, light);
            }
            if (circleSprite != null) {
                renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, jointLocal, spriteRight, spriteUp, armNormal, scale, scale,
                        colorR(armColor), colorG(armColor), colorB(armColor), 245, light, false);
            }
            if (eyeSprite != null) {
                renderAtlasModelOrQuad("deerEyeB", vertexConsumers, matrix, eyeSprite, jointLocal.add(armNormal.multiply(px(0.35f))), spriteRight, spriteUp, armNormal,
                        scale * 0.58f, scale * 0.38f, colorR(highlight), colorG(highlight), colorB(highlight), 220, light, false);
            }
            if (mirosLegSmallPartSprite != null && i % 2 == 1 && logicalIndex < 3) {
                renderAtlasModelOrQuad("MirosLegSmallPart", vertexConsumers, matrix, mirosLegSmallPartSprite,
                        jointLocal.subtract(spriteUp.multiply(scale * 1.45f)), spriteRight, spriteUp, armNormal,
                        scale * 0.55f, scale * 1.35f, colorR(highlight), colorG(highlight), colorB(highlight), 210, light, false);
            }
        }
    }

    private static void renderArmBaseFSprites(OracleEntity entity, VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                             OraclePose pose, Vec3d forward, Vec3d renderOrigin,
                                             int armColor, float tickDelta, int light) {
        Joint[] joints = entity.getArm().joints();
        if (joints.length < 2) {
            return;
        }

        VertexConsumer pixel = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE_TEX));
        Vec3d base = flattenWorldToArmPlane(entity, lerp(joints[0].view().lastPos(), joints[0].view().pos(), tickDelta)).subtract(renderOrigin);
        Vec3d visualBase = base.add(armRootJointOffset(entity));
        Vec3d next = flattenWorldToArmPlane(entity, lerp(joints[1].view().lastPos(), joints[1].view().pos(), tickDelta)).subtract(renderOrigin);
        Vec3d baseDir = entity.chamberTrackInwardDir(entity.getSyncedBaseTarget()).negate();
        Vec3d railTangent = entity.chamberTrackTangentDir(entity.getSyncedBaseTarget());
        Vec3d perp = projectToPlane(railTangent, baseDir);
        perp = safeNormalize(perp, makeFrame(baseDir)[0]);
        Vec3d railNormal = safeNormalize(baseDir.crossProduct(perp), new Vec3d(0.0, 0.0, 1.0));
        Vec3d plateCenter = base.subtract(baseDir.multiply(px(ARM_ROOT_CENTER_FROM_RAIL_PIXELS)));

        int metal = mixColor(armColor, 0xFFFFFF, 0.18f);
        int plate = mixColor(metal, armColor, 0.45f);
        int highlight = lighten(metal, 0.38f);
        renderArmBasePlate(pixel, matrix, plateCenter, baseDir, perp, railNormal,
                px(30.0f), px(ARM_ROOT_HALF_FROM_CENTER_PIXELS), px(20.0f), ARM_ROOT_THICKNESS_BLOCKS * 0.5f, plate, 225, light);
        renderArmBasePlate(pixel, matrix, plateCenter.add(railNormal.multiply(ARM_ROOT_THICKNESS_BLOCKS * 0.5f + px(0.2f))), baseDir, perp, railNormal,
                px(24.0f), px(15.0f), px(30.0f), px(0.2f), highlight, 205, light);

        List<Vec3d> armPoints = armVisualPoints(entity, tickDelta, armPlaneNormal(), pose.hips, renderOrigin);
        Vec3d elbowTarget = armPoints.size() > 1 ? armPoints.get(1) : solveKnee3D(base, next, px(100.0), px(200.0), perp.add(baseDir.multiply(px(6.0f))));
        Vec3d supportTarget = visualBase.lerp(elbowTarget, 0.25);
        for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
            double side = sideIndex == 0 ? -1.0 : 1.0;
            Vec3d root = plateCenter.add(railNormal.multiply(ARM_ROOT_THICKNESS_BLOCKS * 0.5f)).add(baseDir.multiply(px(11.0f))).add(perp.multiply(px(17.0f) * side));
            Vec3d hinge = plateCenter.add(railNormal.multiply(ARM_ROOT_THICKNESS_BLOCKS * 0.5f)).add(perp.multiply(px(17.0f) * side));
            Vec3d knee = solveKnee3D(root, supportTarget, px(25.0), px(45.0), perp.multiply(-side));

            renderPixelRod(pixel, matrix, root, knee, railNormal, px(1.0f), metal, 230, light);
            renderPixelRod(pixel, matrix, knee, supportTarget, railNormal, px(1.0f), metal, 230, light);
            if (eyeSprite != null) {
                renderAtlasModelOrQuad("deerEyeB", vertexConsumers, matrix, eyeSprite, knee.add(railNormal.multiply(px(0.4f))), perp, baseDir, railNormal,
                        px(1.5f), px(1.0f), colorR(metal), colorG(metal), colorB(metal), 230, light, false);
            }
            if (circleSprite != null) {
                renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, hinge.add(railNormal.multiply(px(0.35f))), perp, baseDir, railNormal,
                        px(5.0f), px(5.0f), colorR(plate), colorG(plate), colorB(plate), 235, light, false);
                renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, hinge.add(perp.multiply(-px(0.5f))).add(baseDir.multiply(px(0.5f))).add(railNormal.multiply(px(0.6f))),
                        perp, baseDir, railNormal, px(4.5f), px(4.5f), colorR(highlight), colorG(highlight), colorB(highlight), 215, light, false);
            }
        }
    }

    private static void renderCogBars(VertexConsumer vc, Matrix4f matrix, Vec3d center, Vec3d axis, Vec3d normal,
                                      int color, int jointIndex, float age, int light) {
        Vec3d cogUp = projectToPlane(axis, normal);
        cogUp = safeNormalize(cogUp, new Vec3d(0.0, 1.0, 0.0));
        Vec3d cogRight = safeNormalize(normal.crossProduct(cogUp), new Vec3d(1.0, 0.0, 0.0));
        float halfLength = px((18.0f - jointIndex * 2.0f) * 0.5f);
        float halfWidth = px((5.0f - jointIndex * 0.5f) * 0.5f);
        float spin = (age * (jointIndex % 2 == 0 ? -0.065f : 0.065f)) + jointIndex * 0.47f;
        int metal = lighten(color, 0.26f);
        for (int i = 0; i < 3; i++) {
            float angle = spin + (float) (i * Math.PI / 3.0);
            Vec3d longAxis = cogUp.multiply(MathHelper.cos(angle)).add(cogRight.multiply(MathHelper.sin(angle)));
            Vec3d shortAxis = cogRight.multiply(MathHelper.cos(angle)).subtract(cogUp.multiply(MathHelper.sin(angle)));
            renderOrientedBox(vc, matrix, center, shortAxis, longAxis, normal, halfWidth, halfLength, px(0.4f),
                    colorR(metal), colorG(metal), colorB(metal), 230, light);
        }
    }

    private static void renderArmBasePlate(VertexConsumer vc, Matrix4f matrix, Vec3d pos, Vec3d dir, Vec3d perp, Vec3d normal,
                                           float width, float height, float innerWidth, float halfDepth,
                                           int color, int alpha, int light) {
        Vec3d[] p = new Vec3d[12];
        p[0] = pos.subtract(perp.multiply(innerWidth * 0.5)).subtract(dir.multiply(height));
        p[1] = pos.subtract(perp.multiply(width)).subtract(dir.multiply(height * 0.75));
        p[2] = pos.subtract(perp.multiply(width)).add(dir.multiply(height * 0.75));
        p[3] = pos.subtract(perp.multiply(width * 0.8)).add(dir.multiply(height));
        p[4] = pos.subtract(perp.multiply(width * 0.5)).add(dir.multiply(height));
        p[5] = pos.subtract(perp.multiply(width * 0.3)).subtract(dir.multiply(height * 0.1));
        p[6] = pos.add(perp.multiply(width * 0.3)).subtract(dir.multiply(height * 0.1));
        p[7] = pos.add(perp.multiply(width * 0.5)).add(dir.multiply(height));
        p[8] = pos.add(perp.multiply(width * 0.8)).add(dir.multiply(height));
        p[9] = pos.add(perp.multiply(width)).add(dir.multiply(height * 0.75));
        p[10] = pos.add(perp.multiply(width)).subtract(dir.multiply(height * 0.75));
        p[11] = pos.add(perp.multiply(innerWidth * 0.5)).subtract(dir.multiply(height));

        int r = colorR(color);
        int g = colorG(color);
        int b = colorB(color);
        Vec3d depth = normal.multiply(halfDepth);
        Vec3d[] front = new Vec3d[p.length];
        Vec3d[] back = new Vec3d[p.length];
        for (int i = 0; i < p.length; i++) {
            front[i] = p[i].add(depth);
            back[i] = p[i].subtract(depth);
        }

        emitArmBasePlateFace(vc, matrix, front, normal, r, g, b, alpha, light);
        emitArmBasePlateFace(vc, matrix, back, normal.negate(), r, g, b, alpha, light);
        for (int i = 0; i < p.length; i++) {
            int next = (i + 1) % p.length;
            Vec3d edge = p[next].subtract(p[i]);
            Vec3d sideNormal = safeNormalize(edge.crossProduct(normal), normal);
            emitQuadCorners(vc, matrix, front[i], front[next], back[next], back[i], sideNormal, r, g, b, alpha, light);
        }
    }

    private static void emitArmBasePlateFace(VertexConsumer vc, Matrix4f matrix, Vec3d[] p, Vec3d normal,
                                             int r, int g, int b, int alpha, int light) {
        emitQuadCorners(vc, matrix, p[0], p[1], p[2], p[5], normal, r, g, b, alpha, light);
        emitQuadCorners(vc, matrix, p[5], p[2], p[3], p[4], normal, r, g, b, alpha, light);
        emitQuadCorners(vc, matrix, p[0], p[5], p[6], p[11], normal, r, g, b, alpha, light);
        emitQuadCorners(vc, matrix, p[6], p[7], p[8], p[9], normal, r, g, b, alpha, light);
        emitQuadCorners(vc, matrix, p[6], p[9], p[10], p[11], normal, r, g, b, alpha, light);
    }

    private static void renderPixelRod(VertexConsumer vc, Matrix4f matrix, Vec3d start, Vec3d end, Vec3d normal,
                                       float halfWidth, int color, int alpha, int light) {
        Vec3d tangent = end.subtract(start);
        if (tangent.lengthSquared() < 1.0E-5) {
            return;
        }
        tangent = tangent.normalize();
        Vec3d side = safeNormalize(normal.crossProduct(tangent), new Vec3d(1.0, 0.0, 0.0)).multiply(halfWidth);
        emitQuadCorners(vc, matrix, start.subtract(side), start.add(side), end.add(side), end.subtract(side), normal,
                colorR(color), colorG(color), colorB(color), alpha, light);
    }

    private static Vec3d solveKnee3D(Vec3d start, Vec3d end, double firstLength, double secondLength, Vec3d pole) {
        Vec3d chord = end.subtract(start);
        double distance = chord.length();
        if (distance < 1.0E-5) {
            return start.add(safeNormalize(pole, new Vec3d(0.0, 1.0, 0.0)).multiply(firstLength));
        }
        Vec3d dir = chord.normalize();
        double clampedDistance = Math.min(distance, firstLength + secondLength - 1.0E-4);
        double along = (firstLength * firstLength - secondLength * secondLength + clampedDistance * clampedDistance) / (2.0 * clampedDistance);
        double height = Math.sqrt(Math.max(0.0, firstLength * firstLength - along * along));
        Vec3d polePlane = pole.subtract(dir.multiply(pole.dotProduct(dir)));
        polePlane = safeNormalize(polePlane, makeFrame(dir)[0]);
        return start.add(dir.multiply(along)).add(polePlane.multiply(height));
    }

    private static Vec3d solveKnee2D(Vec3d start, Vec3d end, double firstLength, double secondLength, int index) {
        Vec3d chord = new Vec3d(end.x - start.x, end.y - start.y, 0.0);
        double distance = chord.length();
        double flip = index % 2 == 0 ? 1.0 : -1.0;
        if (distance < 1.0E-5) {
            return start.add(0.0, firstLength * flip, 0.0);
        }
        Vec3d dir = chord.normalize();
        double clampedDistance = Math.min(distance, firstLength + secondLength - 1.0E-4);
        double along = (firstLength * firstLength - secondLength * secondLength + clampedDistance * clampedDistance) / (2.0 * clampedDistance);
        double height = Math.sqrt(Math.max(0.0, firstLength * firstLength - along * along));
        Vec3d perpendicular = new Vec3d(-dir.y, dir.x, 0.0).multiply(flip);
        return start.add(dir.multiply(along)).add(perpendicular.multiply(height));
    }

    private static Vec3d projectToPlane(Vec3d vector, Vec3d normal) {
        Vec3d n = safeNormalize(normal, new Vec3d(0.0, 0.0, 1.0));
        return vector.subtract(n.multiply(vector.dotProduct(n)));
    }

    private static void renderPebblesHaloAndPetals(VertexConsumerProvider vertexConsumers, Matrix4f matrix, OraclePose pose,
                                                   Vec3d right, Vec3d up, Vec3d forward,
                                                   int robe, int arm, float age, int light) {
        int haloColor = lighten(arm, 0.45f);
        if (circleSprite != null) {
            for (int i = 0; i < 7; i++) {
                float angle = (float) (i * Math.PI * 2.0 / 7.0 + age * 0.012f);
                Vec3d radial = right.multiply(MathHelper.cos(angle)).add(up.multiply(MathHelper.sin(angle))).normalize();
                Vec3d center = pose.head.add(up.multiply(0.38)).add(radial.multiply(0.26));
                renderAtlasModelOrQuad("Circle20", vertexConsumers, matrix, circleSprite, center, right, up, forward, 0.035f, 0.035f,
                        colorR(haloColor), colorG(haloColor), colorB(haloColor), 190, light, true);
            }
        }
        if (karmaPetalSprite != null) {
            for (int i = -2; i <= 2; i++) {
                Vec3d center = pose.hips.add(right.multiply(i * 0.075)).subtract(up.multiply(0.23 + Math.abs(i) * 0.018)).add(forward.multiply(0.02));
                int petal = lighten(robe, i == 0 ? 0.22f : 0.10f);
                renderAtlasModelOrQuad("KarmaPetal", vertexConsumers, matrix, karmaPetalSprite, center, right, up, forward, 0.055f, 0.115f,
                        colorR(petal), colorG(petal), colorB(petal), 215, light, false);
            }
        }
    }

    private static void renderMoonSprites(VertexConsumerProvider vertexConsumers, Matrix4f matrix, OraclePose pose,
                                          Vec3d right, Vec3d up, Vec3d forward, int skin, float age, int light) {
        FAtlasElement thirdEye = mouseEyeA5Sprite != null ? mouseEyeA5Sprite : circleSprite;
        String thirdEyeName = mouseEyeA5Sprite != null ? "mouseEyeA5" : "Circle20";
        if (thirdEye != null) {
            int thirdEyeColor = mixColor(0xFF00FF, skin, 0.5f);
            Vec3d center = pose.head.subtract(up.multiply(0.105)).add(forward.multiply(0.018));
            renderAtlasModelOrQuad(thirdEyeName, vertexConsumers, matrix, thirdEye, center, right, up, forward, 0.050f, 0.030f,
                    colorR(thirdEyeColor), colorG(thirdEyeColor), colorB(thirdEyeColor), 235, light, false);
        }
        if (moonSigilSprite != null) {
            Vec3d center = pose.head.subtract(up.multiply(0.265)).add(forward.multiply(0.016));
            int alpha = 120 + (int) (MathHelper.sin(age * 0.08f) * 35f);
            renderAtlasModelOrQuad("MoonSigil", vertexConsumers, matrix, moonSigilSprite, center, right, up, forward, 0.105f, 0.105f,
                    31, 73, 123, MathHelper.clamp(alpha, 60, 180), light, true);
        }
    }

    private static void renderFutileWhiteGlow(OracleEntity entity, VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                              OraclePose pose, Vec3d right, Vec3d up, Vec3d forward, float age, int light) {
        int color = entity.getOracleId() == OracleId.FIVE_PEBBLES ? 0x000000 : 0xFFFFFF;
        int alpha = entity.getOracleId() == OracleId.FIVE_PEBBLES ? 85 : 45;
        float pulse = 0.46f + MathHelper.sin(age * 0.04f) * 0.025f;
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE_TEX));
        emitQuad(vc, matrix, pose.head.subtract(forward.multiply(0.025)), right, up, forward,
                pulse, pulse, colorR(color), colorG(color), colorB(color), alpha, light);
    }

    private static void loadSprites() {
        if (triedLoadSprites) {
            return;
        }
        triedLoadSprites = true;
        try {
            var atlas = LibrainworldmcClient.getAtlasManager();
            circleSprite = atlas.getElementWithName("Circle20");
            eyeSprite = atlas.getElementWithName("deerEyeB");
            glyphSprite = atlas.getElementWithName("haloGlyph-1");
            mirosLegSmallPartSprite = atlas.getElementWithName("MirosLegSmallPart");
            karmaPetalSprite = atlas.getElementWithName("KarmaPetal");
            lizardScaleSprite = atlas.getElementWithName("LizardScaleA1");
            mouseEyeA5Sprite = atlas.getElementWithName("mouseEyeA5");
            moonSigilSprite = atlas.getElementWithName("MoonSigil");
        } catch (RuntimeException ignored) {
            circleSprite = null;
            eyeSprite = null;
            glyphSprite = null;
            mirosLegSmallPartSprite = null;
            karmaPetalSprite = null;
            lizardScaleSprite = null;
            mouseEyeA5Sprite = null;
            moonSigilSprite = null;
        }
    }

    private static Object getAtlasSpriteModel(String name) {
        if (atlasSpriteModels.containsKey(name)) {
            return atlasSpriteModels.get(name);
        }
        Object model = null;
        try {
            model = invokeAtlasModelLookup(LibrainworldmcClient.getAtlasManager(), name, SPRITE_MODEL_DEPTH_PIXELS);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            model = null;
        }
        atlasSpriteModels.put(name, model);
        return model;
    }

    private static void renderAtlasModelOrQuad(String modelName, VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                               FAtlasElement fallbackSprite,
                                               Vec3d center, Vec3d right, Vec3d up, Vec3d normal,
                                               float halfWidth, float halfHeight,
                                               int r, int g, int b, int a, int light, boolean emissive) {
        Object model = getAtlasSpriteModel(modelName);
        if (model != null && modelTexture(model) != null && !modelQuads(model).isEmpty()) {
            renderAtlasModel(vertexConsumers, matrix, model, center, right, up, normal,
                    halfWidth, halfHeight, r, g, b, a, light, emissive);
            return;
        }
        renderAtlasQuad(vertexConsumers, matrix, fallbackSprite, center, right, up, normal,
                halfWidth, halfHeight, r, g, b, a, light, emissive);
    }

    private static void renderAtlasModel(VertexConsumerProvider vertexConsumers, Matrix4f matrix, Object model,
                                         Vec3d center, Vec3d right, Vec3d up, Vec3d normal,
                                         float halfWidth, float halfHeight,
                                         int r, int g, int b, int a, int light, boolean emissive) {
        Identifier texture = modelTexture(model);
        if (texture == null) {
            return;
        }
        float modelWidth = Math.max(modelWidth(model), 1f);
        float modelHeight = Math.max(modelHeight(model), 1f);
        float[] zBounds = modelZBounds(model);
        float zCenter = (zBounds[0] + zBounds[1]) * 0.5f;
        float xScale = (halfWidth * 2f) / modelWidth;
        float yScale = (halfHeight * 2f) / modelHeight;
        float zScale = Math.min(xScale, yScale);
        VertexConsumer vc = vertexConsumers.getBuffer(emissive
                ? RenderLayer.getEntityTranslucentEmissive(texture)
                : RenderLayer.getEntityCutoutNoCull(texture));

        for (Object quad : modelQuads(model)) {
            Vec3d transformedNormal = transformModelNormal(right, up, normal,
                    invokeFloat(quad, "normalX"), invokeFloat(quad, "normalY"), invokeFloat(quad, "normalZ"));
            emitAtlasModelVertex(matrix, vc, modelWidth, modelHeight, zCenter, xScale, yScale, zScale,
                    center, right, up, normal, transformedNormal, invokeObject(quad, "a"), r, g, b, a, light);
            emitAtlasModelVertex(matrix, vc, modelWidth, modelHeight, zCenter, xScale, yScale, zScale,
                    center, right, up, normal, transformedNormal, invokeObject(quad, "b"), r, g, b, a, light);
            emitAtlasModelVertex(matrix, vc, modelWidth, modelHeight, zCenter, xScale, yScale, zScale,
                    center, right, up, normal, transformedNormal, invokeObject(quad, "c"), r, g, b, a, light);
            emitAtlasModelVertex(matrix, vc, modelWidth, modelHeight, zCenter, xScale, yScale, zScale,
                    center, right, up, normal, transformedNormal, invokeObject(quad, "d"), r, g, b, a, light);
        }
    }

    private static void emitAtlasModelVertex(Matrix4f matrix, VertexConsumer vc,
                                             float modelWidth, float modelHeight, float modelZCenter,
                                             float xScale, float yScale, float zScale,
                                             Vec3d center, Vec3d right, Vec3d up, Vec3d normal, Vec3d transformedNormal,
                                             Object vertex, int r, int g, int b, int a, int light) {
        float x = (invokeFloat(vertex, "x") - modelWidth * 0.5f) * xScale;
        float y = (invokeFloat(vertex, "y") - modelHeight * 0.5f) * yScale;
        float z = (invokeFloat(vertex, "z") - modelZCenter) * zScale;
        Vec3d pos = center.add(right.multiply(x)).add(up.multiply(y)).add(normal.multiply(z));
        vc.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, a)
                .texture(invokeFloat(vertex, "u"), invokeFloat(vertex, "v"))
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal((float) transformedNormal.x, (float) transformedNormal.y, (float) transformedNormal.z);
    }

    private static float[] modelZBounds(Object model) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (Object quad : modelQuads(model)) {
            for (String vertexName : List.of("a", "b", "c", "d")) {
                float z = invokeFloat(invokeObject(quad, vertexName), "z");
                min = Math.min(min, z);
                max = Math.max(max, z);
            }
        }
        if (!Float.isFinite(min) || !Float.isFinite(max)) {
            return new float[] { 0f, SPRITE_MODEL_DEPTH_PIXELS };
        }
        return new float[] { min, max };
    }

    private static void renderAtlasQuad(VertexConsumerProvider vertexConsumers, Matrix4f matrix, FAtlasElement sprite,
                                        Vec3d center, Vec3d right, Vec3d up, Vec3d normal,
                                        float halfWidth, float halfHeight,
                                        int r, int g, int b, int a, int light, boolean emissive) {
        if (sprite == null || sprite.textureIdentifier == null) {
            return;
        }
        VertexConsumer vc = vertexConsumers.getBuffer(emissive
                ? RenderLayer.getEntityTranslucentEmissive(sprite.textureIdentifier)
                : RenderLayer.getEntityCutoutNoCull(sprite.textureIdentifier));
        emitQuad(vc, matrix, center, right, up, normal, halfWidth, halfHeight, r, g, b, a, light);
    }

    private static void renderTube(VertexConsumer vc, Matrix4f matrix, List<Vec3d> points, float startRadius, float endRadius,
                                   int color, int alpha, int light) {
        if (points.size() < 2) {
            return;
        }
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        Vec3d[][] rings = new Vec3d[points.size()][TUBE_SIDES];
        Vec3d[][] normals = new Vec3d[points.size()][TUBE_SIDES];
        for (int i = 0; i < points.size(); i++) {
            Vec3d tangent;
            if (i == 0) {
                tangent = points.get(1).subtract(points.get(0));
            } else if (i == points.size() - 1) {
                tangent = points.get(i).subtract(points.get(i - 1));
            } else {
                tangent = points.get(i + 1).subtract(points.get(i - 1));
            }
            tangent = safeNormalize(tangent, new Vec3d(0.0, 1.0, 0.0));
            Vec3d[] frame = makeFrame(tangent);
            Vec3d right = frame[0];
            Vec3d up = frame[1];
            float t = i / (float) Math.max(1, points.size() - 1);
            float radius = MathHelper.lerp(t, startRadius, endRadius);
            for (int side = 0; side < TUBE_SIDES; side++) {
                float angle = (float) (Math.PI * 2.0 * side / TUBE_SIDES);
                Vec3d normal = right.multiply(MathHelper.cos(angle)).add(up.multiply(MathHelper.sin(angle))).normalize();
                normals[i][side] = normal;
                rings[i][side] = points.get(i).add(normal.multiply(radius));
            }
        }

        for (int i = 0; i < points.size() - 1; i++) {
            for (int side = 0; side < TUBE_SIDES; side++) {
                int next = (side + 1) % TUBE_SIDES;
                Vec3d normal = safeNormalize(normals[i][side].add(normals[i][next]).add(normals[i + 1][side]).add(normals[i + 1][next]), normals[i][side]);
                putVertex(vc, matrix, rings[i][side], 0f, 1f, r, g, b, alpha, light, normal);
                putVertex(vc, matrix, rings[i][next], 1f, 1f, r, g, b, alpha, light, normal);
                putVertex(vc, matrix, rings[i + 1][next], 1f, 0f, r, g, b, alpha, light, normal);
                putVertex(vc, matrix, rings[i + 1][side], 0f, 0f, r, g, b, alpha, light, normal);
            }
        }
    }

    private static void renderSphere(VertexConsumer vc, Matrix4f matrix, Vec3d center, float radius, int color, int alpha, int light) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        for (int ring = 0; ring < SPHERE_RINGS; ring++) {
            float theta1 = (float) (ring * Math.PI / SPHERE_RINGS);
            float theta2 = (float) ((ring + 1) * Math.PI / SPHERE_RINGS);
            float y1 = MathHelper.cos(theta1) * radius;
            float y2 = MathHelper.cos(theta2) * radius;
            float rr1 = MathHelper.sin(theta1) * radius;
            float rr2 = MathHelper.sin(theta2) * radius;

            for (int seg = 0; seg < SPHERE_SEGMENTS; seg++) {
                float phi1 = (float) (seg * Math.PI * 2.0 / SPHERE_SEGMENTS);
                float phi2 = (float) ((seg + 1) * Math.PI * 2.0 / SPHERE_SEGMENTS);
                Vec3d p1 = center.add(MathHelper.cos(phi1) * rr1, y1, MathHelper.sin(phi1) * rr1);
                Vec3d p2 = center.add(MathHelper.cos(phi1) * rr2, y2, MathHelper.sin(phi1) * rr2);
                Vec3d p3 = center.add(MathHelper.cos(phi2) * rr2, y2, MathHelper.sin(phi2) * rr2);
                Vec3d p4 = center.add(MathHelper.cos(phi2) * rr1, y1, MathHelper.sin(phi2) * rr1);
                putVertex(vc, matrix, p1, 0f, 0f, r, g, b, alpha, light, p1.subtract(center).normalize());
                putVertex(vc, matrix, p2, 0f, 1f, r, g, b, alpha, light, p2.subtract(center).normalize());
                putVertex(vc, matrix, p3, 1f, 1f, r, g, b, alpha, light, p3.subtract(center).normalize());
                putVertex(vc, matrix, p4, 1f, 0f, r, g, b, alpha, light, p4.subtract(center).normalize());
            }
        }
    }

    private static void emitQuad(VertexConsumer vc, Matrix4f matrix, Vec3d center, Vec3d rightUnit, Vec3d upUnit, Vec3d normalUnit,
                                 float halfWidth, float halfHeight, int r, int g, int b, int a, int light) {
        Vec3d right = rightUnit.multiply(halfWidth);
        Vec3d up = upUnit.multiply(halfHeight);
        putVertex(vc, matrix, center.subtract(right).subtract(up), 0f, 1f, r, g, b, a, light, normalUnit);
        putVertex(vc, matrix, center.add(right).subtract(up), 1f, 1f, r, g, b, a, light, normalUnit);
        putVertex(vc, matrix, center.add(right).add(up), 1f, 0f, r, g, b, a, light, normalUnit);
        putVertex(vc, matrix, center.subtract(right).add(up), 0f, 0f, r, g, b, a, light, normalUnit);
    }

    private static void renderOrientedBox(VertexConsumer vc, Matrix4f matrix, Vec3d center,
                                          Vec3d rightUnit, Vec3d upUnit, Vec3d normalUnit,
                                          float halfWidth, float halfHeight, float halfDepth,
                                          int r, int g, int b, int a, int light) {
        Vec3d right = rightUnit.multiply(halfWidth);
        Vec3d up = upUnit.multiply(halfHeight);
        Vec3d depth = normalUnit.multiply(halfDepth);

        Vec3d fbl = center.subtract(right).subtract(up).add(depth);
        Vec3d fbr = center.add(right).subtract(up).add(depth);
        Vec3d ftr = center.add(right).add(up).add(depth);
        Vec3d ftl = center.subtract(right).add(up).add(depth);
        Vec3d bbl = center.subtract(right).subtract(up).subtract(depth);
        Vec3d bbr = center.add(right).subtract(up).subtract(depth);
        Vec3d btr = center.add(right).add(up).subtract(depth);
        Vec3d btl = center.subtract(right).add(up).subtract(depth);

        emitQuadCorners(vc, matrix, fbl, fbr, ftr, ftl, normalUnit, r, g, b, a, light);
        emitQuadCorners(vc, matrix, bbr, bbl, btl, btr, normalUnit.negate(), r, g, b, a, light);
        emitQuadCorners(vc, matrix, fbr, bbr, btr, ftr, rightUnit, r, g, b, a, light);
        emitQuadCorners(vc, matrix, bbl, fbl, ftl, btl, rightUnit.negate(), r, g, b, a, light);
        emitQuadCorners(vc, matrix, ftl, ftr, btr, btl, upUnit, r, g, b, a, light);
        emitQuadCorners(vc, matrix, fbr, fbl, bbl, bbr, upUnit.negate(), r, g, b, a, light);
    }

    private static void emitQuadCorners(VertexConsumer vc, Matrix4f matrix,
                                        Vec3d a, Vec3d b, Vec3d c, Vec3d d, Vec3d normal,
                                        int r, int g, int bl, int alpha, int light) {
        putVertex(vc, matrix, a, 0f, 1f, r, g, bl, alpha, light, normal);
        putVertex(vc, matrix, b, 1f, 1f, r, g, bl, alpha, light, normal);
        putVertex(vc, matrix, c, 1f, 0f, r, g, bl, alpha, light, normal);
        putVertex(vc, matrix, d, 0f, 0f, r, g, bl, alpha, light, normal);
    }

    private static void putVertex(VertexConsumer vc, Matrix4f matrix, Vec3d point, float u, float v,
                                  int r, int g, int b, int a, int light, Vec3d normal) {
        vc.vertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .color(r, g, b, a)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static void line(VertexConsumer vc, Matrix4f matrix, Vec3d a, Vec3d b, int color, int light) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int bl = color & 0xFF;
        Vec3d normal = safeNormalize(b.subtract(a), new Vec3d(0.0, 1.0, 0.0));
        vc.vertex(matrix, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, 255).normal((float) normal.x, (float) normal.y, (float) normal.z);
        vc.vertex(matrix, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, 255).normal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static Vec3d lerp(Vec3d a, Vec3d b, float t) {
        return new Vec3d(
                MathHelper.lerp(t, a.x, b.x),
                MathHelper.lerp(t, a.y, b.y),
                MathHelper.lerp(t, a.z, b.z)
        );
    }

    private static float px(float pixels) {
        return pixels * BLOCKS_PER_RAIN_WORLD_PIXEL;
    }

    private static double px(double pixels) {
        return pixels * BLOCKS_PER_RAIN_WORLD_PIXEL;
    }

    private static Vec3d safeNormalize(Vec3d vector, Vec3d fallback) {
        if (vector.lengthSquared() < 1.0E-6) {
            return fallback.lengthSquared() < 1.0E-6 ? new Vec3d(0.0, 1.0, 0.0) : fallback.normalize();
        }
        return vector.normalize();
    }

    private static Vec3d[] makeFrame(Vec3d forward) {
        Vec3d worldUp = new Vec3d(0.0, 1.0, 0.0);
        Vec3d right = worldUp.crossProduct(forward);
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vec3d(1.0, 0.0, 0.0).crossProduct(forward);
        }
        right = safeNormalize(right, new Vec3d(1.0, 0.0, 0.0));
        Vec3d up = safeNormalize(forward.crossProduct(right), worldUp);
        return new Vec3d[] { right, up };
    }

    private static int lighten(int color, float amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) MathHelper.lerp(amount, r, 255);
        g = (int) MathHelper.lerp(amount, g, 255);
        b = (int) MathHelper.lerp(amount, b, 255);
        return (r << 16) | (g << 8) | b;
    }

    private static int mixColor(int from, int to, float amount) {
        int fromR = (from >> 16) & 0xFF;
        int fromG = (from >> 8) & 0xFF;
        int fromB = from & 0xFF;
        int toR = (to >> 16) & 0xFF;
        int toG = (to >> 8) & 0xFF;
        int toB = to & 0xFF;
        int r = (int) MathHelper.lerp(amount, fromR, toR);
        int g = (int) MathHelper.lerp(amount, fromG, toG);
        int b = (int) MathHelper.lerp(amount, fromB, toB);
        return (r << 16) | (g << 8) | b;
    }

    private static int colorR(int color) {
        return (color >> 16) & 0xFF;
    }

    private static int colorG(int color) {
        return (color >> 8) & 0xFF;
    }

    private static int colorB(int color) {
        return color & 0xFF;
    }

    @Override
    public Identifier getTexture(OracleEntity entity) {
        return WHITE_TEX;
    }

    private static class OracleUmbilicalState {
        private static final int RAIN_WORLD_STEPS_PER_MINECRAFT_TICK = 2;
        private static final double MAIN_SPACING = UMBILICAL_MAIN_SPACING_PIXELS * BLOCKS_PER_RAIN_WORLD_PIXEL;
        private static final double MAX_BODY_DISTANCE = 80.0 * BLOCKS_PER_RAIN_WORLD_PIXEL;
        private static final double MAIN_COLLISION_RADIUS = 2.0 * BLOCKS_PER_RAIN_WORLD_PIXEL;
        private static final double SMALL_COLLISION_RADIUS = 0.75 * BLOCKS_PER_RAIN_WORLD_PIXEL;
        private static final double CORD_SEGMENT_SAMPLE_SPACING = 4.0 * BLOCKS_PER_RAIN_WORLD_PIXEL;

        private final Vec3d[] main = new Vec3d[UMBILICAL_MAIN_COORDS];
        private final Vec3d[] lastMain = new Vec3d[UMBILICAL_MAIN_COORDS];
        private final Vec3d[] mainVel = new Vec3d[UMBILICAL_MAIN_COORDS];
        private final Vec3d[][] small = new Vec3d[UMBILICAL_SMALL_CORDS][UMBILICAL_SMALL_COORDS];
        private final Vec3d[][] lastSmall = new Vec3d[UMBILICAL_SMALL_CORDS][UMBILICAL_SMALL_COORDS];
        private final Vec3d[][] smallVel = new Vec3d[UMBILICAL_SMALL_CORDS][UMBILICAL_SMALL_COORDS];
        private final double[] smallLengths = new double[UMBILICAL_SMALL_CORDS];
        private final Vec3d[] smallHeadDirs = new Vec3d[UMBILICAL_SMALL_CORDS];
        private final int[] smallColors = new int[UMBILICAL_SMALL_CORDS];
        private boolean initialized;
        private int lastUpdateAge = -1;

        private OracleUmbilicalState(int seed) {
            Random random = new Random(seed * 341873128712L + 132897987541L);
            for (int i = 0; i < UMBILICAL_SMALL_CORDS; i++) {
                if (random.nextFloat() < 0.5f) {
                    smallLengths[i] = px(50.0 + random.nextDouble() * 15.0);
                } else {
                    smallLengths[i] = px(50.0 + (200.0 - 50.0) * Math.pow(random.nextDouble(), 1.5));
                }
                smallColors[i] = random.nextInt(3);
                smallHeadDirs[i] = randomDirection(random).multiply(random.nextDouble());
            }
        }

        private void update(OracleEntity entity, int age, Vec3d bottomAnchor, Vec3d tetherAnchor, Vec3d firstChunkAnchor,
                            Vec3d headAnchor, Vec3d headLookDir, boolean zeroG) {
            if (!initialized) {
                initialize(bottomAnchor, tetherAnchor, headAnchor);
            }
            if (lastUpdateAge == age) {
                return;
            }
            lastUpdateAge = age;
            rememberRenderPositions();

            for (int step = 0; step < RAIN_WORLD_STEPS_PER_MINECRAFT_TICK; step++) {
                simulateStep(entity, bottomAnchor, tetherAnchor, firstChunkAnchor, headAnchor, headLookDir, zeroG);
            }
        }

        private void rememberRenderPositions() {
            for (int i = 0; i < main.length; i++) {
                lastMain[i] = main[i];
            }
            for (int cord = 0; cord < UMBILICAL_SMALL_CORDS; cord++) {
                for (int i = 0; i < UMBILICAL_SMALL_COORDS; i++) {
                    lastSmall[cord][i] = small[cord][i];
                }
            }
        }

        private void simulateStep(OracleEntity entity, Vec3d bottomAnchor, Vec3d tetherAnchor, Vec3d firstChunkAnchor,
                                  Vec3d headAnchor, Vec3d headLookDir, boolean zeroG) {
            for (int i = 0; i < main.length; i++) {
                main[i] = main[i].add(mainVel[i]);
                double value = i / (double) (main.length - 1);
                double lift = inverseLerpClamped(0.2, 0.0, value);
                mainVel[i] = mainVel[i].multiply(0.995).add(0.0, px(lift - (zeroG ? 0.0 : 0.81)), 0.0);
            }

            collideMainCord(entity);
            collideMainSegments(entity);
            setStuckSegments(bottomAnchor, tetherAnchor);
            satisfyMainLinksForward();
            collideMainCord(entity);
            collideMainSegments(entity);
            setStuckSegments(bottomAnchor, tetherAnchor);
            satisfyMainLinksBackward();
            collideMainCord(entity);
            collideMainSegments(entity);
            setStuckSegments(bottomAnchor, tetherAnchor);
            applyMainBendForces();
            collideMainCord(entity);
            collideMainSegments(entity);
            setStuckSegments(bottomAnchor, tetherAnchor);
            constrainMainEnd(firstChunkAnchor);
            updateSmallCords(entity, headAnchor, headLookDir, zeroG);
        }

        private void initialize(Vec3d bottomAnchor, Vec3d tetherAnchor, Vec3d headAnchor) {
            for (int i = 0; i < main.length; i++) {
                double t = i / (double) (main.length - 1);
                Vec3d point = t < 0.76
                        ? bottomAnchor.lerp(tetherAnchor, t / 0.76)
                        : tetherAnchor.lerp(headAnchor, (t - 0.76) / 0.24);
                main[i] = point;
                lastMain[i] = point;
                mainVel[i] = Vec3d.ZERO;
            }
            for (int cord = 0; cord < UMBILICAL_SMALL_CORDS; cord++) {
                for (int i = 0; i < UMBILICAL_SMALL_COORDS; i++) {
                    double t = i / (double) (UMBILICAL_SMALL_COORDS - 1);
                    Vec3d point = main[main.length - 1].lerp(headAnchor, t)
                            .add(smallHeadDirs[cord].multiply(px(3.0) * Math.sin(t * Math.PI)));
                    small[cord][i] = point;
                    lastSmall[cord][i] = point;
                    smallVel[cord][i] = Vec3d.ZERO;
                }
            }
            initialized = true;
        }

        private void setStuckSegments(Vec3d bottomAnchor, Vec3d tetherAnchor) {
            main[0] = bottomAnchor;
            mainVel[0] = Vec3d.ZERO;
            Vec3d jointToTether = safeNormalize(tetherAnchor.subtract(main[Math.max(0, UMBILICAL_TETHER_INDEX - 1)]), new Vec3d(0.0, 1.0, 0.0));
            Vec3d side = safeNormalize(jointToTether.crossProduct(new Vec3d(0.0, 0.0, 1.0)), new Vec3d(1.0, 0.0, 0.0));
            for (int i = -1; i < 2; i++) {
                int index = UMBILICAL_TETHER_INDEX + i;
                double weight = i == 0 ? 1.0 : 0.5;
                Vec3d target = tetherAnchor.add(jointToTether.multiply(px(10.0f * i))).add(side.multiply(px(2.0f * i)));
                main[index] = main[index].lerp(target, weight);
                mainVel[index] = mainVel[index].multiply(1.0 - weight);
            }
        }

        private void satisfyMainLinksForward() {
            for (int i = 1; i < main.length; i++) {
                satisfyLink(main, mainVel, i - 1, i, MAIN_SPACING);
            }
        }

        private void satisfyMainLinksBackward() {
            for (int i = 0; i < main.length - 1; i++) {
                satisfyLink(main, mainVel, i, i + 1, MAIN_SPACING);
            }
        }

        private void applyMainBendForces() {
            double force = px(0.5);
            for (int distance = 2; distance < 4; distance++) {
                for (int i = distance; i < main.length - distance; i++) {
                    Vec3d prevDir = safeNormalize(main[i].subtract(main[i - distance]), Vec3d.ZERO);
                    Vec3d nextDir = safeNormalize(main[i].subtract(main[i + distance]), Vec3d.ZERO);
                    mainVel[i] = mainVel[i].add(prevDir.multiply(force)).add(nextDir.multiply(force));
                    mainVel[i - distance] = mainVel[i - distance].subtract(prevDir.multiply(force));
                    mainVel[i + distance] = mainVel[i + distance].subtract(nextDir.multiply(force));
                }
                force *= 0.75;
            }
        }

        private void constrainMainEnd(Vec3d firstChunkAnchor) {
            int end = main.length - 1;
            Vec3d toBody = firstChunkAnchor.subtract(main[end]);
            double distance = toBody.length();
            if (distance > MAX_BODY_DISTANCE) {
                Vec3d correction = toBody.normalize().multiply((distance - MAX_BODY_DISTANCE) * 0.25);
                main[end] = main[end].add(correction);
                mainVel[end] = mainVel[end].add(correction.multiply(2.0));
            }
        }

        private void updateSmallCords(OracleEntity entity, Vec3d headAnchor, Vec3d headLookDir, boolean zeroG) {
            Vec3d mainEnd = main[main.length - 1];
            Vec3d mainDir = safeNormalize(mainEnd.subtract(main[main.length - 2]), new Vec3d(0.0, 1.0, 0.0));
            for (int cord = 0; cord < UMBILICAL_SMALL_CORDS; cord++) {
                for (int i = 0; i < UMBILICAL_SMALL_COORDS; i++) {
                    small[cord][i] = small[cord][i].add(smallVel[cord][i]);
                    double speedPixels = smallVel[cord][i].length() / BLOCKS_PER_RAIN_WORLD_PIXEL;
                    double damping = lerpDouble(inverseLerpClamped(2.0, 6.0, speedPixels), 0.999, 0.9);
                    smallVel[cord][i] = smallVel[cord][i].multiply(damping);
                    if (!zeroG) {
                        smallVel[cord][i] = smallVel[cord][i].add(0.0, -px(0.81), 0.0);
                    }
                }
                collideSmallCord(entity, cord);
                collideSmallSegments(entity, cord);
                double spacing = smallLengths[cord] / UMBILICAL_SMALL_COORDS;
                for (int i = 1; i < UMBILICAL_SMALL_COORDS; i++) {
                    satisfyLink(small[cord], smallVel[cord], i - 1, i, spacing);
                }
                for (int i = 0; i < UMBILICAL_SMALL_COORDS - 1; i++) {
                    satisfyLink(small[cord], smallVel[cord], i, i + 1, spacing);
                }

                small[cord][0] = mainEnd;
                smallVel[cord][0] = Vec3d.ZERO;
                smallVel[cord][1] = smallVel[cord][1].add(mainDir.multiply(px(5.0)));
                smallVel[cord][2] = smallVel[cord][2].add(mainDir.multiply(px(3.0)));
                smallVel[cord][3] = smallVel[cord][3].add(mainDir.multiply(px(1.5)));

                int last = UMBILICAL_SMALL_COORDS - 1;
                Vec3d headPoint = headAnchor.add(smallHeadDirs[cord].multiply(px(1.5)));
                small[cord][last] = headPoint;
                smallVel[cord][last] = Vec3d.ZERO;
                Vec3d headForce = headLookDir.add(smallHeadDirs[cord]);
                smallVel[cord][last - 1] = smallVel[cord][last - 1].subtract(headForce.multiply(px(2.0)));
                smallVel[cord][last - 2] = smallVel[cord][last - 2].subtract(headForce.multiply(px(1.0)));
                collideSmallCord(entity, cord);
                collideSmallSegments(entity, cord);
            }
        }

        private void collideMainCord(OracleEntity entity) {
            for (int i = 1; i < main.length; i++) {
                if (Math.abs(i - UMBILICAL_TETHER_INDEX) <= 1) {
                    continue;
                }
                collidePoint(entity, main, mainVel, i, MAIN_COLLISION_RADIUS);
            }
        }

        private void collideSmallCord(OracleEntity entity, int cord) {
            int last = UMBILICAL_SMALL_COORDS - 1;
            for (int i = 1; i < last; i++) {
                collidePoint(entity, small[cord], smallVel[cord], i, SMALL_COLLISION_RADIUS);
            }
        }

        private void collideMainSegments(OracleEntity entity) {
            for (int i = 0; i < main.length - 1; i++) {
                collideSegment(entity, main, mainVel, i, i + 1, MAIN_COLLISION_RADIUS, this::isMainPinned);
            }
        }

        private void collideSmallSegments(OracleEntity entity, int cord) {
            for (int i = 0; i < UMBILICAL_SMALL_COORDS - 1; i++) {
                collideSegment(entity, small[cord], smallVel[cord], i, i + 1, SMALL_COLLISION_RADIUS, this::isSmallPinned);
            }
        }

        private void collidePoint(OracleEntity entity, Vec3d[] points, Vec3d[] velocities, int index, double radius) {
            Vec3d before = points[index];
            Vec3d after = OraclePhysicsUtil.collidePoint(entity.getWorld(), before, radius, entity.getOracleCollisionCache());
            if (before.squaredDistanceTo(after) < 1.0E-8) {
                return;
            }
            Vec3d correction = after.subtract(before);
            points[index] = after;
            velocities[index] = velocities[index].add(correction).multiply(0.35);
        }

        private void collideSegment(OracleEntity entity, Vec3d[] points, Vec3d[] velocities,
                                    int a, int b, double radius, PinPredicate pinned) {
            Vec3d correction = OraclePhysicsUtil.segmentCollisionCorrection(entity.getWorld(), points[a], points[b],
                    radius, CORD_SEGMENT_SAMPLE_SPACING, entity.getOracleCollisionCache());
            if (correction.lengthSquared() < 1.0E-8) {
                return;
            }

            boolean aPinned = pinned.isPinned(a);
            boolean bPinned = pinned.isPinned(b);
            if (aPinned && bPinned) {
                return;
            }
            double aWeight = aPinned ? 0.0 : bPinned ? 1.0 : 0.5;
            double bWeight = bPinned ? 0.0 : aPinned ? 1.0 : 0.5;
            applySegmentCorrection(points, velocities, a, correction.multiply(aWeight));
            applySegmentCorrection(points, velocities, b, correction.multiply(bWeight));
        }

        private static void applySegmentCorrection(Vec3d[] points, Vec3d[] velocities, int index, Vec3d correction) {
            if (correction.lengthSquared() < 1.0E-8) {
                return;
            }
            points[index] = points[index].add(correction);
            velocities[index] = velocities[index].add(correction).multiply(0.55);
        }

        private boolean isMainPinned(int index) {
            return index == 0 || Math.abs(index - UMBILICAL_TETHER_INDEX) <= 1;
        }

        private boolean isSmallPinned(int index) {
            return index == 0 || index == UMBILICAL_SMALL_COORDS - 1;
        }

        private List<Vec3d> mainPoints(float tickDelta, Vec3d renderOrigin) {
            List<Vec3d> points = new ArrayList<>(main.length);
            for (int i = 0; i < main.length; i++) {
                points.add(lerp(lastMain[i], main[i], tickDelta).subtract(renderOrigin));
            }
            return points;
        }

        private List<Vec3d> smallCordPoints(int cord, float tickDelta, Vec3d renderOrigin) {
            List<Vec3d> points = new ArrayList<>(UMBILICAL_SMALL_COORDS);
            for (int i = 0; i < UMBILICAL_SMALL_COORDS; i++) {
                points.add(lerp(lastSmall[cord][i], small[cord][i], tickDelta).subtract(renderOrigin));
            }
            return points;
        }

        private int smallCordColor(int cord, int metalColor) {
            return switch (smallColors[cord]) {
                case 1 -> mixColor(0xFF0000, metalColor, 0.5f);
                case 2 -> mixColor(0x0000FF, metalColor, 0.5f);
                default -> metalColor;
            };
        }

        private static void satisfyLink(Vec3d[] points, Vec3d[] velocities, int a, int b, double desired) {
            Vec3d delta = points[b].subtract(points[a]);
            double distance = delta.length();
            if (distance < 1.0E-6) {
                return;
            }
            Vec3d correction = delta.normalize().multiply((distance - desired) * 0.5);
            points[b] = points[b].subtract(correction);
            velocities[b] = velocities[b].subtract(correction);
            points[a] = points[a].add(correction);
            velocities[a] = velocities[a].add(correction);
        }

        private static Vec3d randomDirection(Random random) {
            double x = random.nextDouble() * 2.0 - 1.0;
            double y = random.nextDouble() * 2.0 - 1.0;
            double z = (random.nextDouble() * 2.0 - 1.0) * 0.35;
            return safeNormalize(new Vec3d(x, y, z), new Vec3d(1.0, 0.0, 0.0));
        }

        private static double inverseLerpClamped(double from, double to, double value) {
            if (Math.abs(to - from) < 1.0E-6) {
                return 0.0;
            }
            return MathHelper.clamp((value - from) / (to - from), 0.0, 1.0);
        }

        private static double lerpDouble(double t, double from, double to) {
            return from + (to - from) * t;
        }

        private interface PinPredicate {
            boolean isPinned(int index);
        }
    }

    private record SpriteModelSnapshot(Identifier texture, float width, float height, List<Object> quads) {
    }

    private record OraclePose(
            Vec3d chest,
            Vec3d hips,
            Vec3d neck,
            Vec3d head,
            Vec3d leftHand,
            Vec3d rightHand,
            Vec3d leftFoot,
            Vec3d rightFoot
    ) {
    }
}
