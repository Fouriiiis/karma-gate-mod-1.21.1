package dev.fouriis.karmagate.entity.centipede;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.AtlasSpriteModel;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

/**
 * More faithful 3D port of CentipedeGraphics.cs leg behavior.
 *
 * Main changes from the previous version:
 * - Render-time knee solving uses a dynamic bend plane ("pole vector") derived
 *   from body side, crawl surface, chain direction, and segment/body direction.
 * - Legs keep a small visual bend even near full extension.
 * - Leg attach point and IK bend direction follow the same intent as the C#:
 *   f = Lerp(-1,1,Clamp(num - bodyDir*0.4, 0, 1)) * Lerp(sideSign, -rotX, abs(rotX))
 * - Update and render both use the same outward / forward logic.
 */
public final class CentipedeLegRenderer {

    private static AtlasSpriteModel legAModel = null;
    private static AtlasSpriteModel legBModel = null;

    // 1 RW pixel -> MC blocks
    private static final float PX = 0.05f;

    // Approx body radius used for leg attachment offset
    private static final float BODY_RADIUS = 5f * PX;

    private static final float LEG_MODEL_DEPTH_PIXELS = 1.0f;

    // Colors
    private static final int UPPER_R = 9, UPPER_G = 7, UPPER_B = 6;
    private static final int LOWER_BOT_R = 9, LOWER_BOT_G = 7, LOWER_BOT_B = 6;

    private static final boolean debug = false;
    private static final float LEG_REACH_MULT = 0.75f;

    private CentipedeLegRenderer() {}

    public static void renderLegs(CentipedeSegmentEntity entity, MatrixStack matrices,
                                  VertexConsumerProvider vcProvider, int light, float tickDelta) {
        if (legAModel == null) {
            legAModel = LibrainworldmcClient.getAtlasManager().getModelWithName("CentipedeLegA", LEG_MODEL_DEPTH_PIXELS);
            if (legAModel == null) return;
        }
        if (legBModel == null) {
            legBModel = LibrainworldmcClient.getAtlasManager().getModelWithName("CentipedeLegB", LEG_MODEL_DEPTH_PIXELS);
            if (legBModel == null) return;
        }

        CentipedeController parent = entity.getParentCentipede();
        if (parent == null) return;

        CentipedeSegmentEntity[] segs = parent.getSegments();
        if (segs == null) return;

        int idx = entity.getSegmentIndex();
        int totalSegs = segs.length;
        if (idx < 0 || idx >= totalSegs) return;

        float segRatio = (totalSegs > 1) ? (float) idx / (float) (totalSegs - 1) : 0f;

        // Per-tick simulation
        boolean newTick = (entity.legUpdateAge != entity.age);
        if (newTick) {
            entity.legUpdateAge = entity.age;
            updateLimbs(entity, segs, idx, totalSegs, segRatio, parent);
        }

        // Render
        Vec3d segPos = lerpPos(entity, tickDelta).add(0, 0.25, 0);

        Vec3d chainDir = computeChainDirection(segs, idx, tickDelta);
        Vec3d surfaceNormal = getSurfaceNormal(entity);
        Vec3d sideAxis = computeSideAxis(chainDir, surfaceNormal);

        float legLength = computeLegLength(segRatio, parent.getSize());
        float bodyDir = parent.isBodyDirection() ? -1f : 1f;

        for (int side = 0; side < 2; side++) {
            float sideSign = (side == 0) ? -1f : 1f;

            // C#-style attach point:
            // vector7 = vector3 - vector5 * ((l == 0) ? (-1f) : 1f) * normalized.y * rad;
            // In 3D we attach sideways off the segment along sideAxis.
            Vec3d outward = sideAxis.multiply(sideSign);
            Vec3d attachLocal = outward.multiply(BODY_RADIUS);

            Vec3d footWorld = new Vec3d(
                    MathHelper.lerp(tickDelta, entity.legLastPos[side].x, entity.legPos[side].x),
                    MathHelper.lerp(tickDelta, entity.legLastPos[side].y, entity.legPos[side].y),
                    MathHelper.lerp(tickDelta, entity.legLastPos[side].z, entity.legPos[side].z)
            );
            Vec3d footLocal = footWorld.subtract(segPos);

            // More faithful C# bend factor:
            // f = Lerp(-1, 1, Clamp(num - bodyDir * 0.4f, 0, 1)) * ...
            float f = MathHelper.lerp(
                    MathHelper.clamp(segRatio - bodyDir * 0.4f, 0f, 1f),
                    -1f, 1f
            ) * sideSign;

            f = (float) (Math.pow(Math.abs(f), 0.2) * Math.signum(f));

            // Build a pole vector / bend direction that resembles the 2D C# intent.
            Vec3d pole = computeLegPoleVector(chainDir, surfaceNormal, outward, f);

            Vec3d kneeLocal = solveKnee3DWithMinimumBend(
                    attachLocal,
                    footLocal,
                    legLength * 0.5f,
                    legLength * 0.5f,
                    pole,
                    0.18f // minimum visual bend fraction
            );

            double upperLen = kneeLocal.subtract(attachLocal).length();
            double lowerLen = footLocal.subtract(kneeLocal).length();
            float legScale = parent.getLegScale();

            float halfWidthA = modelHalfWidth(upperLen, legAModel, legScale);
            float halfWidthB = modelHalfWidth(lowerLen, legBModel, legScale);

            int secColor = parent.getSecondaryShellColorRGB();
            int lowerTopR = (secColor >> 16) & 0xFF;
            int lowerTopG = (secColor >> 8) & 0xFF;
            int lowerTopB = secColor & 0xFF;

            renderLegModel(
                    matrices, vcProvider, light,
                    attachLocal, kneeLocal, halfWidthA, legAModel,
                    surfaceNormal,
                    UPPER_R, UPPER_G, UPPER_B,
                    UPPER_R, UPPER_G, UPPER_B
            );

            renderLegModel(
                    matrices, vcProvider, light,
                    kneeLocal, footLocal, halfWidthB, legBModel,
                    surfaceNormal,
                    lowerTopR, lowerTopG, lowerTopB,
                    LOWER_BOT_R, LOWER_BOT_G, LOWER_BOT_B
            );

            if (debug) {
                renderDebugLine(matrices, vcProvider, attachLocal, kneeLocal, 50, 50, 255);
                renderDebugLine(matrices, vcProvider, kneeLocal, footLocal, 80, 200, 255);
            }
        }
    }

    // =========================================================================
    // Limb simulation
    // =========================================================================

    private static void updateLimbs(CentipedeSegmentEntity entity,
                                    CentipedeSegmentEntity[] segs, int idx, int totalSegs,
                                    float segRatio, CentipedeController parent) {
        Vec3d segPos = entity.getPos();
        Vec3d chainDir = computeChainDirectionTick(segs, idx);
        Vec3d surfaceNormal = getSurfaceNormal(entity);
        Vec3d sideAxis = computeSideAxis(chainDir, surfaceNormal);

        float legLength = computeLegLength(segRatio, parent.getSize());
        float walkCycle = parent.getWalkCycle();
        float bodyDirSign = parent.isBodyDirection() ? -1f : 1f;
        World world = entity.getWorld();

        boolean isMoving = entity.segmentVelocity.horizontalLength() > 0.008;

        final float SWING_FRAC = 0.32f;
        float segOffset = (totalSegs > 1) ? (float) idx / (float) totalSegs : 0f;

        float a = MathHelper.lerp(segRatio, -1f, 1f);
        float outerFac = 0.5f + 0.5f * (float) Math.sin(segRatio * Math.PI);

        for (int side = 0; side < 2; side++) {
            float sideSign = (side == 0) ? -1f : 1f;
            float sideOffset = (side == 0) ? 0f : 0.5f;

            float rawPhase = walkCycle * bodyDirSign + segOffset + sideOffset;
            float phase = rawPhase - (float) Math.floor(rawPhase);

            boolean inSwing = isMoving && (phase < SWING_FRAC);
            float swingT = inSwing ? (phase / SWING_FRAC) : 0f;

            entity.legLastPos[side] = entity.legPos[side];
            entity.legPos[side] = entity.legPos[side].add(entity.legVel[side]);

            Vec3d outward = sideAxis.multiply(sideSign);
            Vec3d attachPt = segPos.add(outward.multiply(BODY_RADIUS));

            Vec3d toFoot = entity.legPos[side].subtract(attachPt);
            double dist = toFoot.length();
            if (dist > legLength) {
                entity.legPos[side] = attachPt.add(toFoot.normalize().multiply(legLength));
            }

            entity.legVel[side] = entity.legVel[side].add(entity.segmentVelocity.multiply(0.08));
            entity.legVel[side] = entity.legVel[side].multiply(0.8);

            // Similar intent to the C# desired leg direction:
            // mix body-chain and sideways placement, then bias toward terrain surface
            Vec3d idealDir = slerp3D(chainDir.multiply(a), outward, outerFac);
            idealDir = idealDir.add(surfaceNormal.negate().multiply(0.45));
            if (idealDir.lengthSquared() > 0.001) idealDir = idealDir.normalize();
            else idealDir = outward;

            Vec3d idealFoot = attachPt.add(idealDir.multiply(legLength));

            if (!entity.legsInitialized) {
                Vec3d grip = findGrip(world, attachPt, idealFoot, legLength * 1.5);
                entity.legPos[side] = (grip != null) ? grip : idealFoot;
                entity.legLastPos[side] = entity.legPos[side];
                entity.legGripTarget[side] = grip;
                entity.legGripped[side] = (grip != null);
                entity.legVel[side] = Vec3d.ZERO;
                continue;
            }

            if (inSwing) {
                if (entity.legGripped[side]) {
                    entity.legGripped[side] = false;
                    entity.legGripTarget[side] = null;
                    entity.legVel[side] = entity.legVel[side].add(surfaceNormal.multiply(6f * PX));
                }

                if (swingT < 0.6f) {
                    Vec3d toIdeal2 = idealFoot.subtract(entity.legPos[side]);
                    entity.legVel[side] = entity.legVel[side].add(toIdeal2.multiply(0.25));

                    float arc = (float) Math.sin((swingT / 0.6f) * Math.PI) * 5.5f * PX;
                    entity.legVel[side] = entity.legVel[side].add(surfaceNormal.multiply(arc));
                } else {
                    Vec3d grip = findGrip(world, attachPt, idealFoot, legLength * 1.5);
                    if (grip != null) {
                        entity.legGripTarget[side] = grip;
                        Vec3d toGrip = grip.subtract(entity.legPos[side]);
                        double toGripDist = toGrip.length();
                        if (toGripDist < 0.04) {
                            entity.legGripped[side] = true;
                            entity.legPos[side] = grip;
                            entity.legVel[side] = Vec3d.ZERO;
                        } else {
                            entity.legVel[side] = entity.legVel[side].add(
                                    toGrip.normalize().multiply(Math.min(toGripDist * 0.5, 0.18))
                            );
                        }
                    } else {
                        Vec3d toIdeal2 = idealFoot.subtract(entity.legPos[side]);
                        entity.legVel[side] = entity.legVel[side].add(toIdeal2.multiply(0.3));
                    }
                }
            } else {
                if (entity.legGripped[side] && entity.legGripTarget[side] != null) {
                    double attachToGrip = attachPt.distanceTo(entity.legGripTarget[side]);
                    if (attachToGrip > legLength * 1.7) {
                        entity.legGripped[side] = false;
                        entity.legGripTarget[side] = null;
                        entity.legVel[side] = entity.legVel[side].add(idealDir.multiply(10f * PX));
                    } else {
                        entity.legPos[side] = entity.legGripTarget[side];
                        entity.legVel[side] = Vec3d.ZERO;
                    }
                } else {
                    Vec3d grip = findGrip(world, attachPt, idealFoot, legLength * 1.5);
                    if (grip != null) {
                        entity.legGripped[side] = true;
                        entity.legGripTarget[side] = grip;
                        entity.legPos[side] = grip;
                        entity.legVel[side] = Vec3d.ZERO;
                    } else {
                        entity.legVel[side] = entity.legVel[side].add(idealDir.multiply(10f * PX));
                        Vec3d toIdeal2 = idealFoot.subtract(entity.legPos[side]);
                        entity.legVel[side] = lerpVec(entity.legVel[side], toIdeal2, 0.45);
                    }
                }
            }
        }

        entity.legsInitialized = true;
    }

    // =========================================================================
    // Grip finding
    // =========================================================================

    private static Vec3d findGrip(World world, Vec3d attachPt, Vec3d idealFoot, double maxReach) {
        Vec3d bestGrip = null;
        double bestDist = Double.MAX_VALUE;

        BlockPos footBlock = BlockPos.ofFloored(idealFoot);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos bp = footBlock.add(dx, dy, dz);
                    if (!world.getBlockState(bp).isSolidBlock(world, bp)) continue;

                    for (Direction face : Direction.values()) {
                        BlockPos adjacent = bp.offset(face);
                        if (world.getBlockState(adjacent).isSolidBlock(world, adjacent)) continue;

                        Vec3d surfacePoint = surfacePointOnFace(bp, face, idealFoot);
                        if (surfacePoint.distanceTo(attachPt) > maxReach) continue;

                        double d = surfacePoint.distanceTo(idealFoot);
                        if (d < bestDist) {
                            bestDist = d;
                            bestGrip = surfacePoint;
                        }
                    }
                }
            }
        }

        return bestGrip;
    }

    private static Vec3d surfacePointOnFace(BlockPos block, Direction face, Vec3d target) {
        double bx = block.getX(), by = block.getY(), bz = block.getZ();

        return switch (face) {
            case UP -> new Vec3d(
                    MathHelper.clamp(target.x, bx, bx + 1), by + 1.0,
                    MathHelper.clamp(target.z, bz, bz + 1));
            case DOWN -> new Vec3d(
                    MathHelper.clamp(target.x, bx, bx + 1), by,
                    MathHelper.clamp(target.z, bz, bz + 1));
            case NORTH -> new Vec3d(
                    MathHelper.clamp(target.x, bx, bx + 1),
                    MathHelper.clamp(target.y, by, by + 1), bz);
            case SOUTH -> new Vec3d(
                    MathHelper.clamp(target.x, bx, bx + 1),
                    MathHelper.clamp(target.y, by, by + 1), bz + 1.0);
            case WEST -> new Vec3d(bx,
                    MathHelper.clamp(target.y, by, by + 1),
                    MathHelper.clamp(target.z, bz, bz + 1));
            case EAST -> new Vec3d(bx + 1.0,
                    MathHelper.clamp(target.y, by, by + 1),
                    MathHelper.clamp(target.z, bz, bz + 1));
        };
    }

    // =========================================================================
    // Direction helpers
    // =========================================================================

    private static Vec3d computeChainDirectionTick(CentipedeSegmentEntity[] segs, int idx) {
        if (idx < 0 || idx >= segs.length || segs[idx] == null) return new Vec3d(0, 0, 1);

        Vec3d dir = Vec3d.ZERO;
        int count = 0;

        if (idx > 0 && segs[idx - 1] != null && !segs[idx - 1].isRemoved()) {
            Vec3d d = segs[idx - 1].getPos().subtract(segs[idx].getPos());
            if (d.lengthSquared() > 0.001) {
                dir = dir.add(d.normalize());
                count++;
            }
        }

        if (idx < segs.length - 1 && segs[idx + 1] != null && !segs[idx + 1].isRemoved()) {
            Vec3d d = segs[idx].getPos().subtract(segs[idx + 1].getPos());
            if (d.lengthSquared() > 0.001) {
                dir = dir.add(d.normalize());
                count++;
            }
        }

        if (count > 0 && dir.lengthSquared() > 0.001) return dir.normalize();
        return new Vec3d(0, 0, 1);
    }

    private static Vec3d computeChainDirection(CentipedeSegmentEntity[] segs, int idx, float tickDelta) {
        if (idx < 0 || idx >= segs.length || segs[idx] == null) return new Vec3d(0, 0, 1);

        Vec3d dir = Vec3d.ZERO;
        int count = 0;

        if (idx > 0 && segs[idx - 1] != null && !segs[idx - 1].isRemoved()) {
            Vec3d prev = lerpPos(segs[idx - 1], tickDelta);
            Vec3d curr = lerpPos(segs[idx], tickDelta);
            Vec3d d = prev.subtract(curr);
            if (d.lengthSquared() > 0.001) {
                dir = dir.add(d.normalize());
                count++;
            }
        }

        if (idx < segs.length - 1 && segs[idx + 1] != null && !segs[idx + 1].isRemoved()) {
            Vec3d curr = lerpPos(segs[idx], tickDelta);
            Vec3d next = lerpPos(segs[idx + 1], tickDelta);
            Vec3d d = curr.subtract(next);
            if (d.lengthSquared() > 0.001) {
                dir = dir.add(d.normalize());
                count++;
            }
        }

        if (count > 0 && dir.lengthSquared() > 0.001) return dir.normalize();
        return new Vec3d(0, 0, 1);
    }

    private static Vec3d getSurfaceNormal(CentipedeSegmentEntity entity) {
        Vec3d n = new Vec3d(entity.surfaceNormalX, entity.surfaceNormalY, entity.surfaceNormalZ);
        if (n.lengthSquared() < 0.01) return new Vec3d(0, 1, 0);
        return n.normalize();
    }

    /**
     * Side axis of the body. Equivalent to the "perpendicular" direction the C# uses
     * when offsetting the leg root from the segment center.
     */
    private static Vec3d computeSideAxis(Vec3d chainDir, Vec3d surfaceNormal) {
        Vec3d side = chainDir.crossProduct(surfaceNormal);
        if (side.lengthSquared() < 0.001) {
            side = chainDir.crossProduct(new Vec3d(0, 1, 0));
            if (side.lengthSquared() < 0.001) {
                side = chainDir.crossProduct(new Vec3d(1, 0, 0));
            }
        }
        return side.normalize();
    }

    /**
     * Pole vector for the knee.
     *
     * This is the important part for getting the "bent insect leg" look.
     * It combines:
     * - outward from body
     * - slight downward / surface-following bias
     * - forward/back bias from C#-style f
     */
    private static Vec3d computeLegPoleVector(Vec3d chainDir, Vec3d surfaceNormal, Vec3d outward, float f) {
        Vec3d pole =
                outward.multiply(1.00)
                        .add(surfaceNormal.negate().multiply(0.35))
                        .add(chainDir.multiply(0.65 * f));

        if (pole.lengthSquared() < 0.001) {
            pole = outward;
        }
        return pole.normalize();
    }

    // =========================================================================
    // IK
    // =========================================================================

    /**
     * 3D 2-bone IK with a pole vector and a minimum bend amount.
     *
     * This is what prevents the knee from visually collapsing into a straight line.
     */
    private static Vec3d solveKnee3DWithMinimumBend(Vec3d start, Vec3d end,
                                                    float len1, float len2,
                                                    Vec3d poleVector,
                                                    float minBendFraction) {
        Vec3d diff = end.subtract(start);
        double dist = diff.length();

        if (dist < 1e-5) {
            return start.add(poleVector.normalize().multiply(len1));
        }

        Vec3d dir = diff.normalize();

        // Clamp render-time distance slightly below full extension so there is always some bend.
        double maxReach = len1 + len2 - 1e-4;
        double clampedDist = Math.min(dist, maxReach);

        // Law of cosines
        double cosA = ((clampedDist * clampedDist) + (len1 * len1) - (len2 * len2))
                / (2.0 * clampedDist * len1);
        cosA = MathHelper.clamp((float) cosA, -1f, 1f);

        double along = cosA * len1;
        double bend = Math.sqrt(Math.max(0.0, (len1 * len1) - (along * along)));

        // Enforce a minimum visual bend
        double minBend = Math.min(len1, len2) * minBendFraction;
        bend = Math.max(bend, minBend);

        // Build a bend direction in the plane perpendicular to dir using the pole vector
        Vec3d planePole = poleVector.subtract(dir.multiply(poleVector.dotProduct(dir)));
        if (planePole.lengthSquared() < 0.001) {
            planePole = dir.crossProduct(new Vec3d(0, 1, 0));
            if (planePole.lengthSquared() < 0.001) {
                planePole = dir.crossProduct(new Vec3d(1, 0, 0));
            }
        }
        planePole = planePole.normalize();

        return start
                .add(dir.multiply(along))
                .add(planePole.multiply(bend));
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private static float computeLegLength(float t, float size) {
        float csharpLen = MathHelper.lerp((float) Math.sin(t * Math.PI), 10f, 25f);
        csharpLen *= MathHelper.lerp(size, 0.5f, 1.5f);
        return csharpLen * PX * LEG_REACH_MULT;
    }

    private static Vec3d slerp3D(Vec3d a, Vec3d b, float t) {
        double la = a.length(), lb = b.length();
        if (la < 0.001 || lb < 0.001) return a.add(b.subtract(a).multiply(t));

        Vec3d na = a.normalize(), nb = b.normalize();
        double dot = MathHelper.clamp(na.dotProduct(nb), -1.0, 1.0);
        double theta = Math.acos(dot);

        if (theta < 0.001) return a.add(b.subtract(a).multiply(t));

        double sinTheta = Math.sin(theta);
        double fa = Math.sin((1 - t) * theta) / sinTheta;
        double fb = Math.sin(t * theta) / sinTheta;
        double lerpLen = la + (lb - la) * t;

        return na.multiply(fa).add(nb.multiply(fb)).multiply(lerpLen);
    }

    private static Vec3d lerpVec(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t
        );
    }

    private static Vec3d lerpPos(CentipedeSegmentEntity seg, float tickDelta) {
        return new Vec3d(
                MathHelper.lerp(tickDelta, seg.prevTickPos.x, seg.getPos().x),
                MathHelper.lerp(tickDelta, seg.prevTickPos.y, seg.getPos().y),
                MathHelper.lerp(tickDelta, seg.prevTickPos.z, seg.getPos().z)
        );
    }

    // =========================================================================
    // Debug
    // =========================================================================

    private static void renderDebugLine(MatrixStack matrices, VertexConsumerProvider vcProvider,
                                        Vec3d start, Vec3d end, int r, int g, int b) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vcProvider.getBuffer(RenderLayer.LINES);

        Vec3d dir = end.subtract(start);
        double len = dir.length();
        float nx = 0f, ny = 1f, nz = 0f;

        if (len > 0.001) {
            Vec3d n = dir.normalize();
            nx = (float) n.x;
            ny = (float) n.y;
            nz = (float) n.z;
        }

        vc.vertex(mat, (float) start.x, (float) start.y, (float) start.z)
                .color(r, g, b, 255)
                .normal(nx, ny, nz);
        vc.vertex(mat, (float) end.x, (float) end.y, (float) end.z)
                .color(r, g, b, 255)
                .normal(nx, ny, nz);
    }

    // =========================================================================
    // Sprite model rendering
    // =========================================================================

    private static float modelHalfWidth(double limbLen, AtlasSpriteModel model, float legScale) {
        float modelHeight = Math.max(model.height(), 1f);
        return (float) (limbLen * (model.width() / modelHeight) * 0.5f) * legScale;
    }

    private static void renderLegModel(MatrixStack matrices, VertexConsumerProvider vcProvider, int light,
                                       Vec3d startLocal, Vec3d endLocal, float halfWidth,
                                       AtlasSpriteModel model,
                                       Vec3d faceHint,
                                       int topR, int topG, int topB,
                                       int botR, int botG, int botB) {
        Vec3d limbDir = endLocal.subtract(startLocal);
        double limbLen = limbDir.length();
        if (limbLen < 0.001 || model.element().textureIdentifier == null) return;

        Vec3d tangent = limbDir.normalize();

        Vec3d face = faceHint.subtract(tangent.multiply(faceHint.dotProduct(tangent)));
        if (face.lengthSquared() < 0.001) {
            face = tangent.crossProduct(new Vec3d(0, 1, 0));
            if (face.lengthSquared() < 0.001) {
                face = tangent.crossProduct(new Vec3d(1, 0, 0));
            }
        }
        face = face.normalize();

        Vec3d widthDir = tangent.crossProduct(face);
        if (widthDir.lengthSquared() < 0.001) widthDir = face;
        else widthDir = widthDir.normalize();

        float modelWidth = Math.max(model.width(), 1f);
        float modelHeight = Math.max(model.height(), 1f);
        float xScale = (halfWidth * 2f) / modelWidth;
        float yScale = (float) (limbLen / modelHeight);
        float zScale = xScale;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vcProvider.getBuffer(RenderLayer.getEntityCutoutNoCull(model.element().textureIdentifier));

        for (AtlasSpriteModel.Quad quad : model.quads()) {
            Vec3d normal = transformNormal(widthDir, tangent, face, quad.normalX(), quad.normalY(), quad.normalZ());
            emitModelVertex(mat, vc, light, modelWidth, modelHeight, xScale, yScale, zScale,
                    startLocal, widthDir, tangent, face, normal, quad.a(), topR, topG, topB, botR, botG, botB);
            emitModelVertex(mat, vc, light, modelWidth, modelHeight, xScale, yScale, zScale,
                    startLocal, widthDir, tangent, face, normal, quad.b(), topR, topG, topB, botR, botG, botB);
            emitModelVertex(mat, vc, light, modelWidth, modelHeight, xScale, yScale, zScale,
                    startLocal, widthDir, tangent, face, normal, quad.c(), topR, topG, topB, botR, botG, botB);
            emitModelVertex(mat, vc, light, modelWidth, modelHeight, xScale, yScale, zScale,
                    startLocal, widthDir, tangent, face, normal, quad.d(), topR, topG, topB, botR, botG, botB);
        }
    }

    private static Vec3d transformNormal(Vec3d widthDir, Vec3d tangent, Vec3d face,
                                         float normalX, float normalY, float normalZ) {
        Vec3d normal = widthDir.multiply(normalX)
                .add(tangent.multiply(normalY))
                .add(face.multiply(normalZ));
        if (normal.lengthSquared() < 0.001) return face;
        return normal.normalize();
    }

    private static void emitModelVertex(Matrix4f mat, VertexConsumer vc, int light,
                                        float modelWidth, float modelHeight,
                                        float xScale, float yScale, float zScale,
                                        Vec3d startLocal, Vec3d widthDir, Vec3d tangent, Vec3d face, Vec3d normal,
                                        AtlasSpriteModel.Vertex vertex,
                                        int topR, int topG, int topB,
                                        int botR, int botG, int botB) {
        float x = (vertex.x() - modelWidth * 0.5f) * xScale;
        float y = vertex.y() * yScale;
        float z = vertex.z() * zScale;

        Vec3d pos = startLocal
                .add(widthDir.multiply(x))
                .add(tangent.multiply(y))
                .add(face.multiply(z));

        float colorT = MathHelper.clamp(vertex.y() / modelHeight, 0f, 1f);
        int r = MathHelper.lerp(colorT, botR, topR);
        int g = MathHelper.lerp(colorT, botG, topG);
        int b = MathHelper.lerp(colorT, botB, topB);

        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, 255)
                .texture(vertex.u(), vertex.v())
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z);
    }
}