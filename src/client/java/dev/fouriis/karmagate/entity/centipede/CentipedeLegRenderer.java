package dev.fouriis.karmagate.entity.centipede;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.minecraft.client.MinecraftClient;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

    // Quad half-width for billboard rendering
    private static final float LEG_HALF_WIDTH = 0.025f;
    // Red centipede leg scale (C#: scaleX *= 1.3f for Red)
    private static final float RED_LEG_SCALE = 1.3f;

    // Colors
    private static final int UPPER_R = 30, UPPER_G = 25, UPPER_B = 22;
    private static final int LOWER_TOP_R = 100, LOWER_TOP_G = 15, LOWER_TOP_B = 10;
    private static final int LOWER_BOT_R = 25, LOWER_BOT_G = 20, LOWER_BOT_B = 18;

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

        RedCentipedeEntity parent = entity.getParentCentipede();
        if (parent == null) return;
        CentipedeSegmentEntity[] segs = parent.getSegments();
        if (segs == null) return;

        int idx = entity.getSegmentIndex();
        int totalSegs = segs.length;
        float segRatio = (float) idx / (float) (totalSegs - 1);

        // --- Per-tick limb simulation update ---
        boolean newTick = (entity.legUpdateAge != entity.age);
        if (newTick) {
            entity.legUpdateAge = entity.age;
            updateLimbs(entity, segs, idx, totalSegs, segRatio, parent);
        }

        // --- Rendering ---
        Vec3d segPos = lerpPos(entity, tickDelta);
        Vec3d chainDir = computeChainDirection(segs, idx, tickDelta);
        Vec3d perp = horizontalPerp(chainDir);
        float legLength = computeLegLength(segRatio);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.gameRenderer == null) return;
        Quaternionf camRot = new Quaternionf(client.gameRenderer.getCamera().getRotation());
        Vector3f billRight = camRot.transform(new Vector3f(1f, 0f, 0f));
        Vector3f billUp = camRot.transform(new Vector3f(0f, 1f, 0f));
        Vector3f billNorm = camRot.transform(new Vector3f(0f, 0f, 1f));

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

            renderLegSprite(matrices, vcProvider, light,
                    attachLocal, kneeLocal, LEG_HALF_WIDTH * RED_LEG_SCALE, legASprite,
                    billRight, billUp, billNorm,
                    UPPER_R, UPPER_G, UPPER_B, UPPER_R, UPPER_G, UPPER_B);

            renderLegSprite(matrices, vcProvider, light,
                    kneeLocal, footLocal, LEG_HALF_WIDTH * RED_LEG_SCALE * 0.85f, legBSprite,
                    billRight, billUp, billNorm,
                    LOWER_TOP_R, LOWER_TOP_G, LOWER_TOP_B,
                    LOWER_BOT_R, LOWER_BOT_G, LOWER_BOT_B);
        }
    }

    // =========================================================================
    // Per-tick Limb simulation (C# Limb.Update + ConnectToPoint + FindGrip)
    // =========================================================================

    /**
     * Update all limbs for one segment. Mirrors C# CentipedeGraphics.Update() leg loop:
     * 1. Limb.Update() — apply velocity
     * 2. ConnectToPoint — constrain to attach, transfer body vel
     * 3. FindGrip — search for terrain surface if not gripped
     * 4. Grip/Dangle logic — stay planted or push toward ideal position
     */
    private static void updateLimbs(CentipedeSegmentEntity entity,
                                     CentipedeSegmentEntity[] segs, int idx, int totalSegs,
                                     float segRatio, RedCentipedeEntity parent) {
        Vec3d segPos = entity.getPos();
        Vec3d chainDir = computeChainDirectionTick(segs, idx);
        Vec3d perp = horizontalPerp(chainDir);
        float legLength = computeLegLength(segRatio);
        float walkCycle = parent.getWalkCycle();
        float bodyDirSign = parent.isBodyDirection() ? -1f : 1f;
        World world = entity.getWorld();

        // C# walkPhase: num3 = 0.5 + 0.5 * sin((walkCycle + j/10) * PI * 2)
        float walkPhase = 0.5f + 0.5f * (float) Math.sin((walkCycle + idx * 0.1f) * Math.PI * 2.0);

        // C# ideal leg direction factor:
        // a = Lerp(-1, 1, segRatio)
        // a = Lerp(a, bodyDirSign, abs(walkPhase - 0.5))
        float a = MathHelper.lerp(segRatio, -1f, 1f);
        a = MathHelper.lerp(Math.abs(walkPhase - 0.5f), a, bodyDirSign);

        // C# blend factor: Lerp(0.5+0.5*sin(segRatio*PI), 0, abs(walkPhase-0.5)*2)
        float outerFac = MathHelper.lerp(Math.abs(walkPhase - 0.5f) * 2f,
                0.5f + 0.5f * (float) Math.sin(segRatio * Math.PI), 0f);

        for (int side = 0; side < 2; side++) {
            float sideSign = (side == 0) ? -1f : 1f;

            // 1. Save previous position for render interpolation
            entity.legLastPos[side] = entity.legPos[side];

            // 2. Limb.Update(): apply velocity
            entity.legPos[side] = entity.legPos[side].add(entity.legVel[side]);

            // 3. Compute attach point (C#: bodyChunks[j].pos + perp * sideSign * bodyRotat.y * rad)
            // In 3D ground-crawling: bodyRotat.y ≈ -1 (shell on top), simplify to just sideSign
            Vec3d attachPt = segPos.add(perp.multiply(sideSign * BODY_RADIUS));

            // 4. ConnectToPoint: constrain leg to be within legLength of attach
            Vec3d toFoot = entity.legPos[side].subtract(attachPt);
            double dist = toFoot.length();
            if (dist > legLength) {
                entity.legPos[side] = attachPt.add(toFoot.normalize().multiply(legLength));
            }
            // Transfer some body velocity (C#: hostVel * 0.1)
            entity.legVel[side] = entity.legVel[side].add(entity.segmentVelocity.multiply(0.1));
            // Air friction (C#: Limb uses airFriction param, typically 0.9)
            entity.legVel[side] = entity.legVel[side].multiply(0.85);

            // 5. Compute ideal leg direction and ideal foot position
            // C#: legDir = Slerp(chainDir*a, perp*sideSign, outerFac).normalized
            Vec3d idealDir = slerp3D(chainDir.multiply(a), perp.multiply(sideSign), outerFac);
            if (idealDir.lengthSquared() > 0.001) idealDir = idealDir.normalize();
            else idealDir = perp.multiply(sideSign);
            Vec3d idealFoot = attachPt.add(idealDir.multiply(legLength));

            if (!entity.legsInitialized) {
                // First tick: snap to surface immediately
                Vec3d grip = findGrip(world, attachPt, idealFoot, legLength * 1.5);
                entity.legPos[side] = (grip != null) ? grip : idealFoot;
                entity.legLastPos[side] = entity.legPos[side];
                entity.legGripTarget[side] = grip;
                entity.legGripped[side] = (grip != null);
                entity.legVel[side] = Vec3d.ZERO;
                continue;
            }

            // 6. Grip logic (mirrors C# reachedSnapPosition + Dangle)

            if (entity.legGripped[side] && entity.legGripTarget[side] != null) {
                // C#: if (!DistLess(pos, absoluteHuntPos, legLength * 1.5)) → release
                double attachToGrip = attachPt.distanceTo(entity.legGripTarget[side]);
                if (attachToGrip > legLength * 1.4) {
                    // Body moved too far from grip — release (enter Dangle)
                    entity.legGripped[side] = false;
                    entity.legGripTarget[side] = null;
                    // C# Dangle: vel += legDir * 13; vel = Lerp(vel, idealFoot-pos, 0.5)
                    entity.legVel[side] = entity.legVel[side].add(idealDir.multiply(13f * PX));
                    Vec3d toIdeal = idealFoot.subtract(entity.legPos[side]);
                    entity.legVel[side] = lerpVec(entity.legVel[side], toIdeal, 0.5);
                } else {
                    // Stay planted at grip (C# reachedSnapPosition = true)
                    entity.legPos[side] = entity.legGripTarget[side];
                    entity.legVel[side] = Vec3d.ZERO;
                    // C#: vel += legDir * 5 (small push, but constrained, so minimal effect)
                }
            } else {
                // Not gripped — search for new grip (C#: FindGrip when !reachedSnapPosition)
                Vec3d grip = findGrip(world, attachPt, idealFoot, legLength * 1.5);

                if (grip != null) {
                    entity.legGripTarget[side] = grip;
                    // Move toward grip target
                    Vec3d toGrip = grip.subtract(entity.legPos[side]);
                    double toGripDist = toGrip.length();
                    if (toGripDist < 0.04) {
                        // Reached grip — plant foot (C#: reachedSnapPosition = true)
                        entity.legGripped[side] = true;
                        entity.legPos[side] = grip;
                        entity.legVel[side] = Vec3d.ZERO;
                    } else {
                        // Hunting toward grip (C#: HuntRelativePosition mode)
                        // Blend velocity toward grip target
                        entity.legVel[side] = entity.legVel[side].add(
                                toGrip.normalize().multiply(Math.min(toGripDist, 0.15)));
                        // Also add ideal direction push (C#: vel += legDir * 5)
                        entity.legVel[side] = entity.legVel[side].add(idealDir.multiply(5f * PX));
                    }
                } else {
                    // No surface found — pure Dangle mode
                    // C#: vel += legDir * 13; vel = Lerp(vel, idealFoot - pos, 0.5)
                    entity.legVel[side] = entity.legVel[side].add(idealDir.multiply(13f * PX));
                    Vec3d toIdeal = idealFoot.subtract(entity.legPos[side]);
                    entity.legVel[side] = lerpVec(entity.legVel[side], toIdeal, 0.5);
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

    /** Horizontal perpendicular to chain direction (cross with up). */
    private static Vec3d horizontalPerp(Vec3d chainDir) {
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d perp = chainDir.crossProduct(up);
        if (perp.lengthSquared() < 0.001) {
            perp = chainDir.crossProduct(new Vec3d(1, 0, 0));
        }
        return perp.normalize();
    }

    /** Leg length from C# constructor: Lerp(10,25,sin(t*PI)) * Lerp(0.5,1.5,size). */
    private static float computeLegLength(float t) {
        float csharpLen = MathHelper.lerp((float) Math.sin(t * Math.PI), 10f, 25f);
        csharpLen *= MathHelper.lerp(RedCentipedeEntity.SIZE, 0.5f, 1.5f);
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
    // Billboard sprite rendering
    // =========================================================================

    private static void renderLegSprite(MatrixStack matrices, VertexConsumerProvider vcProvider, int light,
                                         Vec3d startLocal, Vec3d endLocal, float halfWidth,
                                         FAtlasElement sprite,
                                         Vector3f billRight, Vector3f billUp, Vector3f billNorm,
                                         int topR, int topG, int topB,
                                         int botR, int botG, int botB) {
        Vec3d limbDir = endLocal.subtract(startLocal);
        double limbLen = limbDir.length();
        if (limbLen < 0.001) return;

        Vec3d tangent = limbDir.normalize();
        float tRight = (float)(tangent.x * billRight.x + tangent.y * billRight.y + tangent.z * billRight.z);
        float tUp = (float)(tangent.x * billUp.x + tangent.y * billUp.y + tangent.z * billUp.z);
        float angle = (float) Math.atan2(tRight, tUp);

        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);

        float rotUpX = cosA * billUp.x + sinA * billRight.x;
        float rotUpY = cosA * billUp.y + sinA * billRight.y;
        float rotUpZ = cosA * billUp.z + sinA * billRight.z;
        float rotRightX = cosA * billRight.x - sinA * billUp.x;
        float rotRightY = cosA * billRight.y - sinA * billUp.y;
        float rotRightZ = cosA * billRight.z - sinA * billUp.z;

        float cx = (float)((startLocal.x + endLocal.x) * 0.5);
        float cy = (float)((startLocal.y + endLocal.y) * 0.5);
        float cz = (float)((startLocal.z + endLocal.z) * 0.5);
        float halfH = (float)(limbLen * 0.5);

        float blX = cx - rotRightX * halfWidth - rotUpX * halfH;
        float blY = cy - rotRightY * halfWidth - rotUpY * halfH;
        float blZ = cz - rotRightZ * halfWidth - rotUpZ * halfH;
        float brX = cx + rotRightX * halfWidth - rotUpX * halfH;
        float brY = cy + rotRightY * halfWidth - rotUpY * halfH;
        float brZ = cz + rotRightZ * halfWidth - rotUpZ * halfH;
        float trX = cx + rotRightX * halfWidth + rotUpX * halfH;
        float trY = cy + rotRightY * halfWidth + rotUpY * halfH;
        float trZ = cz + rotRightZ * halfWidth + rotUpZ * halfH;
        float tlX = cx - rotRightX * halfWidth + rotUpX * halfH;
        float tlY = cy - rotRightY * halfWidth + rotUpY * halfH;
        float tlZ = cz - rotRightZ * halfWidth + rotUpZ * halfH;

        float nx = billNorm.x, ny = billNorm.y, nz = billNorm.z;
        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vcProvider.getBuffer(
                RenderLayer.getEntityCutoutNoCull(sprite.textureIdentifier));

        vc.vertex(mat, blX, blY, blZ).color(botR, botG, botB, 255)
                .texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        vc.vertex(mat, brX, brY, brZ).color(botR, botG, botB, 255)
                .texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        vc.vertex(mat, trX, trY, trZ).color(topR, topG, topB, 255)
                .texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        vc.vertex(mat, tlX, tlY, tlZ).color(topR, topG, topB, 255)
                .texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
    }
}
