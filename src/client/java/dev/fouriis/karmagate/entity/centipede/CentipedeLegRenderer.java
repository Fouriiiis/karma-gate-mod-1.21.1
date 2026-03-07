package dev.fouriis.karmagate.entity.centipede;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
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
 * Leg rendering and per-tick limb simulation for centipede segments.
 * Faithfully ports CentipedeGraphics.cs leg behavior:
 * - Each leg is a physics-simulated Limb with position, velocity, grip state
 * - Legs find terrain surface grips (FindGrip) and plant on them
 * - Legs stay planted while body moves (reachedSnapPosition)
 * - Legs release when the body stretches them beyond reach, then re-grip
 * - Walk cycle creates wave-like stepping via per-segment phase offsets
 * - Rendering uses 2-bone IK with billboarded sprite quads (CentipedeLegA/B)
 */
public final class CentipedeLegRenderer {

    // Atlas sprites (lazily resolved)
    private static FAtlasElement legASprite = null;
    private static FAtlasElement legBSprite = null;

    // Scale: 1 C# pixel ≈ 0.025 MC blocks
    private static final float PX = 0.025f;

    // Body radius for attach point offset (C#: bodyChunks[j].rad ≈ 5-6 px)
    private static final float BODY_RADIUS = 5f * PX;

    // Sprite pixel aspect ratio (width / nativeHeight).
    // C#: CentipedeLegA native height = 27px, width ≈ 6px  → 6/27 ≈ 0.22
    //     CentipedeLegB native height = 25px, width ≈ 6px  → 6/25 ≈ 0.24
    private static final float ASPECT_A = 0.22f;   // upper bone (LegA)
    private static final float ASPECT_B = 0.24f;   // lower bone (LegB)

    // LegA (upper): blackColor — same for all centipede variants
    private static final int UPPER_R = 9, UPPER_G = 7, UPPER_B = 6;
    // LegB (lower) bottom: blackColor — same for all variants
    private static final int LOWER_BOT_R = 9, LOWER_BOT_G = 7, LOWER_BOT_B = 6;

    private CentipedeLegRenderer() {}

    /**
     * Render legs for a centipede segment. Called after super.render() in GeoEntityRenderer.
     * Also runs the per-tick limb simulation update when needed.
     */
    public static void renderLegs(CentipedeSegmentEntity entity, MatrixStack matrices,
                                   VertexConsumerProvider vcProvider, int light, float tickDelta) {
        if (legASprite == null) {
            legASprite = LibrainworldmcClient.getAtlasManager().getElementWithName("CentipedeLegA");
            if (legASprite == null) return;
        }
        if (legBSprite == null) {
            legBSprite = LibrainworldmcClient.getAtlasManager().getElementWithName("CentipedeLegB");
            if (legBSprite == null) return;
        }

        CentipedeController parent = entity.getParentCentipede();
        if (parent == null) return;
        CentipedeSegmentEntity[] segs = parent.getSegments();
        if (segs == null) return;

        int idx = entity.getSegmentIndex();
        int totalSegs = segs.length;
        if (idx < 0 || idx >= totalSegs) return;
        float segRatio = (totalSegs > 1) ? (float) idx / (float) (totalSegs - 1) : 0f;

        // --- Per-tick limb simulation update ---
        boolean newTick = (entity.legUpdateAge != entity.age);
        if (newTick) {
            entity.legUpdateAge = entity.age;
            updateLimbs(entity, segs, idx, totalSegs, segRatio, parent);
        }

        // --- Rendering ---
        // segPos is offset +0.25Y to match the visual raise applied in the segment renderers,
        // so all leg-local coordinates (footLocal, attachLocal) map correctly onto screen.
        Vec3d segPos = lerpPos(entity, tickDelta).add(0, 0.25, 0);
        Vec3d chainDir = computeChainDirection(segs, idx, tickDelta);
        Vec3d perp = surfacePerp(chainDir, entity);
        float legLength = computeLegLength(segRatio, parent.getSize());

        // C# bodyDir for IK bend factor
        float bodyDir = parent.isBodyDirection() ? -1f : 1f;

        for (int side = 0; side < 2; side++) {
            float sideSign = (side == 0) ? -1f : 1f;

            // Attach point (local to segment render position)
            Vec3d attachLocal = perp.multiply(sideSign * BODY_RADIUS);

            // Smoothly interpolated foot position
            Vec3d footWorld = new Vec3d(
                    MathHelper.lerp(tickDelta, entity.legLastPos[side].x, entity.legPos[side].x),
                    MathHelper.lerp(tickDelta, entity.legLastPos[side].y, entity.legPos[side].y),
                    MathHelper.lerp(tickDelta, entity.legLastPos[side].z, entity.legPos[side].z));
            Vec3d footLocal = footWorld.subtract(segPos);

            // IK bend factor (C#: f = Lerp(-1,1,Clamp(num-bodyDir*0.4,0,1)) * Lerp(sideSign, -rotX, abs(rotX)))
            // Simplified for 3D: just use sideSign-based factor
            float f = MathHelper.lerp(MathHelper.clamp(segRatio - bodyDir * 0.4f, 0f, 1f), -1f, 1f)
                    * sideSign;
            f = (float) (Math.pow(Math.abs(f), 0.2) * Math.signum(f));

            Vec3d kneeLocal = inverseKinematics3D(attachLocal, footLocal,
                    legLength * 0.5f, legLength * 0.5f, f, perp);

            // Dynamic half-widths: scale with actual bone length so the sprite
            // maintains a physically consistent aspect ratio regardless of legLength.
            // C#: scaleY = distance/27 (LegA) or /25 (LegB); scaleX = ±1.3 (fixed pixels)
            double upperLen = kneeLocal.subtract(attachLocal).length();
            double lowerLen = footLocal.subtract(kneeLocal).length();
            float legScale = parent.getLegScale();
            float halfWidthA = (float)(upperLen * ASPECT_A * 0.5f) * legScale;
            float halfWidthB = (float)(lowerLen * ASPECT_B * 0.5f) * legScale;

            // Lower leg top color from parent's secondary shell color
            int secColor = parent.getSecondaryShellColorRGB();
            int lowerTopR = (secColor >> 16) & 0xFF;
            int lowerTopG = (secColor >> 8) & 0xFF;
            int lowerTopB = secColor & 0xFF;

            renderLegSprite(matrices, vcProvider, light,
                    attachLocal, kneeLocal, halfWidthA, legASprite,
                    perp,
                    UPPER_R, UPPER_G, UPPER_B, UPPER_R, UPPER_G, UPPER_B);

            renderLegSprite(matrices, vcProvider, light,
                    kneeLocal, footLocal, halfWidthB, legBSprite,
                    perp,
                    lowerTopR, lowerTopG, lowerTopB,
                    LOWER_BOT_R, LOWER_BOT_G, LOWER_BOT_B);

            // Debug: bright blue = bone A (upper), cyan-blue = bone B (lower)
            renderDebugLine(matrices, vcProvider, attachLocal, kneeLocal, 50, 50, 255);
            renderDebugLine(matrices, vcProvider, kneeLocal, footLocal, 80, 200, 255);
        }
    }

    // =========================================================================
    // Per-tick Limb simulation (C# Limb.Update + ConnectToPoint + FindGrip)
    // =========================================================================

    /**
     * Update all limbs for one segment using a metachronal wave gait.
     *
     * Each leg has a personal phase:
     *   phase = frac(walkCycle * bodyDirSign + segIdx * SEG_OFFSET + sideOffset)
     * where left=0.0 offset, right=0.5 offset → left and right never swing together.
     * SEG_OFFSET = 1.0/numSegs creates one full wave across the whole body.
     *
     * SWING phase (0 .. SWING_FRAC): foot lifts, arcs forward, finds new grip at landing.
     * STANCE phase (SWING_FRAC .. 1.0): foot stays planted; only releases if extremely
     * overstretched (body moved way past it).
     */
    private static void updateLimbs(CentipedeSegmentEntity entity,
                                     CentipedeSegmentEntity[] segs, int idx, int totalSegs,
                                     float segRatio, CentipedeController parent) {
        Vec3d segPos = entity.getPos();
        Vec3d chainDir = computeChainDirectionTick(segs, idx);
        Vec3d perp = surfacePerp(chainDir, entity);
        float legLength = computeLegLength(segRatio, parent.getSize());
        float walkCycle = parent.getWalkCycle();
        float bodyDirSign = parent.isBodyDirection() ? -1f : 1f;
        World world = entity.getWorld();

        // Is the body actually moving? Wave stepping is only useful when moving.
        boolean isMoving = entity.segmentVelocity.horizontalLength() > 0.008;

        // Fraction of cycle spent in swing (lifting/arcing). The rest is stance.
        final float SWING_FRAC = 0.32f;
        // Segment phase spacing: spread one full wave across all segments.
        float segOffset = (totalSegs > 1) ? (float) idx / (float) totalSegs : 0f;

        // Ideal leg direction parameters (same C# formulas, use neutral walkPhase=0.5)
        float a = MathHelper.lerp(segRatio, -1f, 1f);
        float outerFac = 0.5f + 0.5f * (float) Math.sin(segRatio * Math.PI);

        for (int side = 0; side < 2; side++) {
            float sideSign = (side == 0) ? -1f : 1f;
            // Right leg is half a cycle out of phase → legs alternate left/right
            float sideOffset = (side == 0) ? 0f : 0.5f;

            // Personal phase in [0, 1)
            float rawPhase = walkCycle * bodyDirSign + segOffset + sideOffset;
            float phase = rawPhase - (float) Math.floor(rawPhase);

            boolean inSwing = isMoving && (phase < SWING_FRAC);
            // Normalised progress through swing arc: 0 = lift-off, 1 = plant
            float swingT = inSwing ? (phase / SWING_FRAC) : 0f;

            // 1. Save previous position for render interpolation
            entity.legLastPos[side] = entity.legPos[side];

            // 2. Limb.Update(): apply velocity
            entity.legPos[side] = entity.legPos[side].add(entity.legVel[side]);

            // 3. Attach point (body surface where this leg connects)
            Vec3d attachPt = segPos.add(perp.multiply(sideSign * BODY_RADIUS));

            // 4. ConnectToPoint: keep leg within legLength of attach
            Vec3d toFoot = entity.legPos[side].subtract(attachPt);
            double dist = toFoot.length();
            if (dist > legLength) {
                entity.legPos[side] = attachPt.add(toFoot.normalize().multiply(legLength));
            }
            // Transfer body momentum and apply friction
            entity.legVel[side] = entity.legVel[side].add(entity.segmentVelocity.multiply(0.08));
            entity.legVel[side] = entity.legVel[side].multiply(0.8);

            // 5. Compute ideal foot direction + position (neutral, no walkPhase modulation)
            // Mix chain direction with perpendicular, then bias toward the surface
            Vec3d idealDir = slerp3D(chainDir.multiply(a), perp.multiply(sideSign), outerFac);
            // Add a bias toward the surface the segment is crawling on (opposite of surface normal)
            Vec3d surfNorm = new Vec3d(entity.surfaceNormalX, entity.surfaceNormalY, entity.surfaceNormalZ);
            if (surfNorm.lengthSquared() > 0.01) {
                idealDir = idealDir.add(surfNorm.negate().multiply(0.5));
            }
            if (idealDir.lengthSquared() > 0.001) idealDir = idealDir.normalize();
            else idealDir = perp.multiply(sideSign);
            Vec3d idealFoot = attachPt.add(idealDir.multiply(legLength));

            // --- First-tick snap ---
            if (!entity.legsInitialized) {
                Vec3d grip = findGrip(world, attachPt, idealFoot, legLength * 1.5);
                entity.legPos[side] = (grip != null) ? grip : idealFoot;
                entity.legLastPos[side] = entity.legPos[side];
                entity.legGripTarget[side] = grip;
                entity.legGripped[side] = (grip != null);
                entity.legVel[side] = Vec3d.ZERO;
                continue;
            }

            // -------------------------------------------------------
            // SWING phase: lift the foot and arc it forward to new grip
            // -------------------------------------------------------
            if (inSwing) {
                if (entity.legGripped[side]) {
                    // Lift off — release current grip
                    entity.legGripped[side] = false;
                    entity.legGripTarget[side] = null;
                    // Small upward kick at lift-off
                    entity.legVel[side] = entity.legVel[side].add(new Vec3d(0, 8f * PX, 0));
                }

                // During the first 60% of swing: push toward ideal foot + small lift
                // During last 40%: pull down to land and search for grip
                if (swingT < 0.6f) {
                    Vec3d toIdeal = idealFoot.subtract(entity.legPos[side]);
                    entity.legVel[side] = entity.legVel[side].add(toIdeal.multiply(0.25));
                    // Arc upward at the apex (swingT ≈ 0.3)
                    float arcY = (float) Math.sin(swingT / 0.6f * Math.PI) * 6f * PX;
                    entity.legVel[side] = entity.legVel[side].add(new Vec3d(0, arcY, 0));
                } else {
                    // Descending — hunt for grip near ideal foot
                    Vec3d grip = findGrip(world, attachPt, idealFoot, legLength * 1.5);
                    if (grip != null) {
                        entity.legGripTarget[side] = grip;
                        Vec3d toGrip = grip.subtract(entity.legPos[side]);
                        double toGripDist = toGrip.length();
                        if (toGripDist < 0.04) {
                            // Plant foot early if we've arrived
                            entity.legGripped[side] = true;
                            entity.legPos[side] = grip;
                            entity.legVel[side] = Vec3d.ZERO;
                        } else {
                            entity.legVel[side] = entity.legVel[side].add(
                                    toGrip.normalize().multiply(Math.min(toGripDist * 0.5, 0.18)));
                        }
                    } else {
                        // No grip found: fall toward ideal foot position
                        Vec3d toIdeal = idealFoot.subtract(entity.legPos[side]);
                        entity.legVel[side] = entity.legVel[side].add(toIdeal.multiply(0.3));
                    }
                }

            // -------------------------------------------------------
            // STANCE phase: keep foot planted; only release if body
            // has moved so far that the leg is extremely overstretched.
            // -------------------------------------------------------
            } else {
                if (entity.legGripped[side] && entity.legGripTarget[side] != null) {
                    double attachToGrip = attachPt.distanceTo(entity.legGripTarget[side]);
                    if (attachToGrip > legLength * 1.7) {
                        // Emergency release — overstretched during stance
                        entity.legGripped[side] = false;
                        entity.legGripTarget[side] = null;
                        entity.legVel[side] = entity.legVel[side].add(idealDir.multiply(10f * PX));
                    } else {
                        // Planted: lock position, zero velocity
                        entity.legPos[side] = entity.legGripTarget[side];
                        entity.legVel[side] = Vec3d.ZERO;
                    }
                } else {
                    // Ungripped during stance — immediately find and plant
                    Vec3d grip = findGrip(world, attachPt, idealFoot, legLength * 1.5);
                    if (grip != null) {
                        entity.legGripped[side] = true;
                        entity.legGripTarget[side] = grip;
                        entity.legPos[side] = grip;
                        entity.legVel[side] = Vec3d.ZERO;
                    } else {
                        // No surface: dangle toward ideal
                        entity.legVel[side] = entity.legVel[side].add(idealDir.multiply(10f * PX));
                        Vec3d toIdeal = idealFoot.subtract(entity.legPos[side]);
                        entity.legVel[side] = lerpVec(entity.legVel[side], toIdeal, 0.45);
                    }
                }
            }
        }

        entity.legsInitialized = true;
    }

    // =========================================================================
    // FindGrip: search for terrain surface near idealFoot within maxReach
    // Mirrors C# Limb.FindGrip() — searches tiles near ideal position
    // =========================================================================

    private static Vec3d findGrip(World world, Vec3d attachPt, Vec3d idealFoot, double maxReach) {
        Vec3d bestGrip = null;
        double bestDist = Double.MAX_VALUE;

        BlockPos footBlock = BlockPos.ofFloored(idealFoot);

        // Search a 3x3x3 area around ideal foot for surfaces
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos bp = footBlock.add(dx, dy, dz);
                    if (!world.getBlockState(bp).isSolidBlock(world, bp)) continue;

                    // Check each face of this solid block for a surface point
                    for (Direction face : Direction.values()) {
                        BlockPos adjacent = bp.offset(face);
                        if (world.getBlockState(adjacent).isSolidBlock(world, adjacent)) continue;

                        // Surface point on this face, closest to idealFoot
                        Vec3d surfacePoint = surfacePointOnFace(bp, face, idealFoot);

                        // Must be within maxReach of attach point
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

    /**
     * Get the closest point on a block face to the given target position.
     */
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
    // Chain direction computation
    // =========================================================================

    /** Chain direction for tick update (no interpolation needed). */
    private static Vec3d computeChainDirectionTick(CentipedeSegmentEntity[] segs, int idx) {
        if (idx < 0 || idx >= segs.length) return new Vec3d(0, 0, 1);
        Vec3d dir = Vec3d.ZERO;
        int count = 0;
        if (idx > 0 && segs[idx - 1] != null && !segs[idx - 1].isRemoved()) {
            Vec3d d = segs[idx - 1].getPos().subtract(segs[idx].getPos());
            if (d.lengthSquared() > 0.001) { dir = dir.add(d.normalize()); count++; }
        }
        if (idx < segs.length - 1 && segs[idx + 1] != null && !segs[idx + 1].isRemoved()) {
            Vec3d d = segs[idx].getPos().subtract(segs[idx + 1].getPos());
            if (d.lengthSquared() > 0.001) { dir = dir.add(d.normalize()); count++; }
        }
        if (count > 0 && dir.lengthSquared() > 0.001) return dir.normalize();
        return new Vec3d(0, 0, 1);
    }

    /** Chain direction for render with interpolation. */
    private static Vec3d computeChainDirection(CentipedeSegmentEntity[] segs, int idx, float tickDelta) {
        if (idx < 0 || idx >= segs.length) return new Vec3d(0, 0, 1);
        Vec3d dir = Vec3d.ZERO;
        int count = 0;
        if (idx > 0 && segs[idx - 1] != null && !segs[idx - 1].isRemoved()) {
            Vec3d prev = lerpPos(segs[idx - 1], tickDelta);
            Vec3d curr = lerpPos(segs[idx], tickDelta);
            Vec3d d = prev.subtract(curr);
            if (d.lengthSquared() > 0.001) { dir = dir.add(d.normalize()); count++; }
        }
        if (idx < segs.length - 1 && segs[idx + 1] != null && !segs[idx + 1].isRemoved()) {
            Vec3d curr = lerpPos(segs[idx], tickDelta);
            Vec3d next = lerpPos(segs[idx + 1], tickDelta);
            Vec3d d = curr.subtract(next);
            if (d.lengthSquared() > 0.001) { dir = dir.add(d.normalize()); count++; }
        }
        if (count > 0 && dir.lengthSquared() > 0.001) return dir.normalize();
        return new Vec3d(0, 0, 1);
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /** Perpendicular to chain direction, oriented toward the surface the segment is crawling on.
     *  Uses the segment's surface normal to determine which way legs should extend.
     *  On floors this gives horizontal perpendicular; on walls/ceilings it correctly
     *  orients legs toward the surface. */
    private static Vec3d surfacePerp(Vec3d chainDir, CentipedeSegmentEntity entity) {
        // Get interpolated surface normal
        float snX = entity.surfaceNormalX;
        float snY = entity.surfaceNormalY;
        float snZ = entity.surfaceNormalZ;
        Vec3d surfaceNormal = new Vec3d(snX, snY, snZ);
        if (surfaceNormal.lengthSquared() < 0.01) {
            surfaceNormal = new Vec3d(0, 1, 0); // default to floor
        } else {
            surfaceNormal = surfaceNormal.normalize();
        }

        // Perpendicular = chainDir x surfaceNormal (gives the sideways direction)
        Vec3d perp = chainDir.crossProduct(surfaceNormal);
        if (perp.lengthSquared() < 0.001) {
            // Fallback: try crossing with world up
            perp = chainDir.crossProduct(new Vec3d(0, 1, 0));
            if (perp.lengthSquared() < 0.001) {
                perp = chainDir.crossProduct(new Vec3d(1, 0, 0));
            }
        }
        return perp.normalize();
    }

    /** Horizontal perpendicular to chain direction (cross with up). Fallback method. */
    private static Vec3d horizontalPerp(Vec3d chainDir) {
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d perp = chainDir.crossProduct(up);
        if (perp.lengthSquared() < 0.001) {
            perp = chainDir.crossProduct(new Vec3d(1, 0, 0));
        }
        return perp.normalize();
    }

    /** Leg length from C# constructor: Lerp(10,25,sin(t*PI)) * Lerp(0.5,1.5,size). */
    private static float computeLegLength(float t, float size) {
        float csharpLen = MathHelper.lerp((float) Math.sin(t * Math.PI), 10f, 25f);
        csharpLen *= MathHelper.lerp(size, 0.5f, 1.5f);
        return csharpLen * PX;
    }

    /** 3D slerp between two vectors by factor t (0=a, 1=b). */
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

    /** Lerp between two Vec3d. */
    private static Vec3d lerpVec(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t);
    }

    /** Position interpolation for rendering. */
    private static Vec3d lerpPos(CentipedeSegmentEntity seg, float tickDelta) {
        return new Vec3d(
                MathHelper.lerp(tickDelta, seg.prevTickPos.x, seg.getPos().x),
                MathHelper.lerp(tickDelta, seg.prevTickPos.y, seg.getPos().y),
                MathHelper.lerp(tickDelta, seg.prevTickPos.z, seg.getPos().z));
    }

    // =========================================================================
    // 3D Inverse Kinematics (same as C# Custom.InverseKinematic)
    // =========================================================================

    private static Vec3d inverseKinematics3D(Vec3d start, Vec3d end,
                                              float len1, float len2,
                                              float sideFactor, Vec3d bendHint) {
        Vec3d diff = end.subtract(start);
        double dist = diff.length();
        if (dist < 0.001 || dist >= len1 + len2) {
            return start.add(end).multiply(0.5);
        }
        Vec3d dir = diff.normalize();
        Vec3d perp = dir.crossProduct(bendHint);
        if (perp.lengthSquared() < 0.001) perp = dir.crossProduct(new Vec3d(0, 1, 0));
        if (perp.lengthSquared() < 0.001) perp = dir.crossProduct(new Vec3d(1, 0, 0));
        perp = perp.normalize();

        double cosA = (dist * dist + (double)(len1 * len1) - (double)(len2 * len2)) / (2.0 * dist * len1);
        cosA = MathHelper.clamp((float) cosA, -1f, 1f);
        double sinA = Math.sqrt(1.0 - cosA * cosA);

        return start.add(dir.multiply(cosA * len1))
                .add(perp.multiply(sinA * len1 * Math.signum(sideFactor)));
    }

    // =========================================================================
    // Debug line rendering
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
            nx = (float) n.x; ny = (float) n.y; nz = (float) n.z;
        }
        vc.vertex(mat, (float) start.x, (float) start.y, (float) start.z)
                .color(r, g, b, 255).normal(nx, ny, nz);
        vc.vertex(mat, (float) end.x, (float) end.y, (float) end.z)
                .color(r, g, b, 255).normal(nx, ny, nz);
    }

    // =========================================================================
    // Billboard sprite rendering
    // =========================================================================

    private static void renderLegSprite(MatrixStack matrices, VertexConsumerProvider vcProvider, int light,
                                         Vec3d startLocal, Vec3d endLocal, float halfWidth,
                                         FAtlasElement sprite,
                                         Vec3d perp,
                                         int topR, int topG, int topB,
                                         int botR, int botG, int botB) {
        Vec3d limbDir = endLocal.subtract(startLocal);
        double limbLen = limbDir.length();
        if (limbLen < 0.001) return;

        // Fixed-angle orientation: the sprite lies in the plane of the leg (not camera-facing).
        // tangent runs along the bone; widthDir is perpendicular to the bone within the leg plane.
        Vec3d tangent = limbDir.normalize();
        // Project perp onto the plane perpendicular to the bone so widthDir stays stable.
        Vec3d perpProj = perp.subtract(tangent.multiply(perp.dotProduct(tangent)));
        if (perpProj.lengthSquared() < 0.001) {
            perpProj = tangent.crossProduct(new Vec3d(0, 1, 0));
            if (perpProj.lengthSquared() < 0.001) perpProj = tangent.crossProduct(new Vec3d(1, 0, 0));
        }
        perpProj = perpProj.normalize();
        // Width direction = tangent x perpProj (lies in the leg plane, perpendicular to bone)
        Vec3d widthDir = tangent.crossProduct(perpProj);
        if (widthDir.lengthSquared() < 0.001) widthDir = perpProj;
        else widthDir = widthDir.normalize();

        // Face normal points in the perpProj direction (sprite faces sideways from body)
        float nfx = (float) perpProj.x, nfy = (float) perpProj.y, nfz = (float) perpProj.z;

        float wdx = (float)(widthDir.x * halfWidth);
        float wdy = (float)(widthDir.y * halfWidth);
        float wdz = (float)(widthDir.z * halfWidth);

        float s0x = (float) startLocal.x, s0y = (float) startLocal.y, s0z = (float) startLocal.z;
        float s1x = (float) endLocal.x,   s1y = (float) endLocal.y,   s1z = (float) endLocal.z;

        // Quad: start = bottom of sprite (tex v=1, botColor), end = top (tex v=0, topColor)
        float blX = s0x - wdx, blY = s0y - wdy, blZ = s0z - wdz;
        float brX = s0x + wdx, brY = s0y + wdy, brZ = s0z + wdz;
        float trX = s1x + wdx, trY = s1y + wdy, trZ = s1z + wdz;
        float tlX = s1x - wdx, tlY = s1y - wdy, tlZ = s1z - wdz;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vcProvider.getBuffer(
                RenderLayer.getEntityCutoutNoCull(sprite.textureIdentifier));

        vc.vertex(mat, blX, blY, blZ).color(botR, botG, botB, 255)
                .texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nfx, nfy, nfz);
        vc.vertex(mat, brX, brY, brZ).color(botR, botG, botB, 255)
                .texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nfx, nfy, nfz);
        vc.vertex(mat, trX, trY, trZ).color(topR, topG, topB, 255)
                .texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nfx, nfy, nfz);
        vc.vertex(mat, tlX, tlY, tlZ).color(topR, topG, topB, 255)
                .texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nfx, nfy, nfz);
    }
}
