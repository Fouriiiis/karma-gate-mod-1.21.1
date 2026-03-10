package dev.fouriis.karmagate.entity.spider;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

/**
 * Procedural renderer for Rain World coalmine spiders.
 *
 * Main fixes:
 * - No camera billboarding
 * - Body sprite is rendered on the top of the hitbox
 * - Legs connect to the raised body sprite, not the entity origin
 * - Front legs angle upward / forward, rear legs angle downward / backward
 * - Fixed-plane 3D sprite rendering like centipede leg renderer
 */
public class SpiderEntityRenderer extends EntityRenderer<SpiderEntity> {

    private static FAtlasManager atlasManager = null;
    private static FAtlasElement bodySprite = null;
    private static final FAtlasElement[][] legSprites = new FAtlasElement[4][2];
    private static boolean spritesLoaded = false;

    // 1 C# pixel ≈ MC blocks
    private static final float PX = 0.025f;

    // C# limbLengths: proportional lengths per leg pair
    private static final float[][] LIMB_LENGTHS = {
            {0.85f, 0.5f},
            {1.0f, 0.6f},
            {0.95f, 0.5f},
            {0.9f, 0.65f}
    };

    // Colors
    private static final int BODY_R = 15, BODY_G = 12, BODY_B = 10;
    private static final int LEG_R = 20, LEG_G = 18, LEG_B = 15;

    // Sprite width aspect ratios
    private static final float ASPECT_UPPER = 0.25f;
    private static final float ASPECT_LOWER = 0.28f;

    public SpiderEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.1f;
    }

    @Override
    public Identifier getTexture(SpiderEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/spider_fallback.png");
    }

    @Override
    public void render(SpiderEntity entity, float yaw, float tickDelta,
                       MatrixStack matrices, VertexConsumerProvider vcProvider, int light) {
        ensureSpritesLoaded();
        if (bodySprite == null) return;

        float size = entity.getSizeFactor();
        float limbLength = MathHelper.lerp(size, 10f, 40f) * PX;

        // Per-tick limb simulation
        boolean newTick = (entity.legUpdateAge != entity.age);
        if (newTick) {
            entity.legUpdateAge = entity.age;
            updateLegs(entity, limbLength, size);
        }

        Vec3d entityPos = new Vec3d(
                MathHelper.lerp(tickDelta, entity.prevTickPos.x, entity.getPos().x),
                MathHelper.lerp(tickDelta, entity.prevTickPos.y, entity.getPos().y),
                MathHelper.lerp(tickDelta, entity.prevTickPos.z, entity.getPos().z)
        );

        Vec3d velocity = entity.getVelocity();
        Vec3d bodyDir;
        if (velocity.horizontalLengthSquared() > 0.001) {
            bodyDir = velocity.normalize();
        } else {
            bodyDir = entity.direction;
        }
        if (bodyDir.lengthSquared() < 0.001) bodyDir = new Vec3d(0, 0, 1);
        bodyDir = bodyDir.normalize();

        Vec3d surfaceNormal = getInterpolatedSurfaceNormal(entity, tickDelta);

        Vec3d sideDir = bodyDir.crossProduct(surfaceNormal);
        if (sideDir.lengthSquared() < 0.001) {
            sideDir = bodyDir.crossProduct(new Vec3d(0, 1, 0));
            if (sideDir.lengthSquared() < 0.001) {
                sideDir = bodyDir.crossProduct(new Vec3d(1, 0, 0));
            }
        }
        sideDir = sideDir.normalize();

        // Reproject forward axis onto the crawl plane
        bodyDir = surfaceNormal.crossProduct(sideDir);
        if (bodyDir.lengthSquared() < 0.001) bodyDir = new Vec3d(0, 0, 1);
        bodyDir = bodyDir.normalize();

        matrices.push();

        // Put the body on top of the hitbox along the crawl-surface normal
        float bodyScale = MathHelper.lerp(size, 0.2f, 1.0f);
        float bodyHalfW = bodyScale * 0.06f;
        float bodyHalfH = bodyScale * 0.06f;
        Vec3d bodyCenterLocal = surfaceNormal.multiply((entity.getHeight() * 0.5f) - 0.02f);

        renderPlanarQuad(
                matrices, vcProvider, light,
                bodySprite,
                bodyCenterLocal,
                sideDir,
                bodyDir,
                bodyHalfW,
                bodyHalfH,
                surfaceNormal,
                BODY_R, BODY_G, BODY_B
        );

        // Legs connect to the raised body
        for (int legIdx = 0; legIdx < 4; legIdx++) {
            for (int side = 0; side < 2; side++) {
                int limbIndex = legIdx + side * 4;
                float sideSign = (side == 0) ? -1f : 1f;

                Vec3d footWorld = new Vec3d(
                        MathHelper.lerp(tickDelta, entity.legLastPos[limbIndex].x, entity.legPos[limbIndex].x),
                        MathHelper.lerp(tickDelta, entity.legLastPos[limbIndex].y, entity.legPos[limbIndex].y),
                        MathHelper.lerp(tickDelta, entity.legLastPos[limbIndex].z, entity.legPos[limbIndex].z)
                );
                Vec3d footLocal = footWorld.subtract(entityPos);

                Vec3d attachOffset = computeBodyAttachOffset(
                        bodyCenterLocal, bodyDir, sideDir, surfaceNormal, size, legIdx, sideSign
                );

                float totalLegLen = LIMB_LENGTHS[legIdx][0] * limbLength;
                float upperLen = LIMB_LENGTHS[legIdx][0] * LIMB_LENGTHS[legIdx][1] * limbLength;
                float lowerLen = LIMB_LENGTHS[legIdx][0] * (1f - LIMB_LENGTHS[legIdx][1]) * limbLength;

                // Clamp the displayed foot so the visual rig never stretches too far from the body
                Vec3d toFoot = footLocal.subtract(attachOffset);
                double dist = toFoot.length();
                if (dist > totalLegLen && dist > 1e-5) {
                    footLocal = attachOffset.add(toFoot.normalize().multiply(totalLegLen));
                }

                float bendFactor = ((legIdx < 2) ? 1f : -1f) * sideSign;
                Vec3d pole = computeSpiderLegPole(bodyDir, sideDir.multiply(sideSign), surfaceNormal, bendFactor, legIdx);

                Vec3d kneeLocal = solveKnee3DWithMinimumBend(
                        attachOffset,
                        footLocal,
                        upperLen,
                        lowerLen,
                        pole,
                        0.18f
                );

                float scaleX = MathHelper.lerp(size, 0.45f, 0.65f);

                float halfWidthA = (float) (attachOffset.distanceTo(kneeLocal) * ASPECT_UPPER * 0.5f) * scaleX;
                renderBoneSprite(
                        matrices, vcProvider, light,
                        attachOffset, kneeLocal, halfWidthA,
                        legSprites[legIdx][0],
                        surfaceNormal,
                        LEG_R, LEG_G, LEG_B,
                        LEG_R, LEG_G, LEG_B
                );

                float halfWidthB = (float) (kneeLocal.distanceTo(footLocal) * ASPECT_LOWER * 0.5f) * scaleX;
                renderBoneSprite(
                        matrices, vcProvider, light,
                        kneeLocal, footLocal, halfWidthB,
                        legSprites[legIdx][1],
                        surfaceNormal,
                        LEG_R + 5, LEG_G + 3, LEG_B + 2,
                        LEG_R - 2, LEG_G - 2, LEG_B - 2
                );
            }
        }

        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vcProvider, light);
    }

    private static void ensureSpritesLoaded() {
        if (spritesLoaded) return;

        atlasManager = LibrainworldmcClient.getAtlasManager();
        if (atlasManager == null) return;

        bodySprite = atlasManager.getElementWithName("SpiderBody");
        if (bodySprite == null) bodySprite = atlasManager.getElementWithName("tinyStar");
        if (bodySprite == null) return;

        for (int i = 0; i < 4; i++) {
            legSprites[i][0] = atlasManager.getElementWithName("SpiderLeg" + i + "A");
            if (legSprites[i][0] == null) legSprites[i][0] = bodySprite;

            legSprites[i][1] = atlasManager.getElementWithName("SpiderLeg" + i + "B");
            if (legSprites[i][1] == null) legSprites[i][1] = bodySprite;
        }

        spritesLoaded = true;
    }

    // =========================================================================
    // Per-tick leg simulation
    // =========================================================================

    private void updateLegs(SpiderEntity entity, float limbLength, float size) {
        Vec3d entityPos = entity.getPos();
        Vec3d velocity = entity.getVelocity();
        World world = entity.getWorld();

        Vec3d bodyDir;
        if (velocity.horizontalLengthSquared() > 0.001) {
            bodyDir = velocity.normalize();
        } else {
            bodyDir = entity.direction;
        }
        if (bodyDir.lengthSquared() < 0.001) bodyDir = new Vec3d(0, 0, 1);
        bodyDir = bodyDir.normalize();

        Vec3d surfaceNormal = new Vec3d(entity.surfaceNormalX, entity.surfaceNormalY, entity.surfaceNormalZ);
        if (surfaceNormal.lengthSquared() < 0.001) surfaceNormal = new Vec3d(0, 1, 0);
        surfaceNormal = surfaceNormal.normalize();

        Vec3d sideDir = bodyDir.crossProduct(surfaceNormal);
        if (sideDir.lengthSquared() < 0.001) {
            sideDir = bodyDir.crossProduct(new Vec3d(0, 1, 0));
            if (sideDir.lengthSquared() < 0.001) {
                sideDir = bodyDir.crossProduct(new Vec3d(1, 0, 0));
            }
        }
        sideDir = sideDir.normalize();

        bodyDir = surfaceNormal.crossProduct(sideDir);
        if (bodyDir.lengthSquared() < 0.001) bodyDir = new Vec3d(0, 0, 1);
        bodyDir = bodyDir.normalize();

        if (velocity.horizontalLengthSquared() > 0.001) {
            entity.direction = bodyDir;
        }

        float moveSpeed = (float) velocity.horizontalLength();
        boolean isMoving = moveSpeed > 0.01f;
        float walkCycle = entity.age * 0.15f * moveSpeed;
        final float SWING_FRAC = 0.35f;

        Vec3d bodyCenterWorld = entityPos.add(surfaceNormal.multiply((entity.getHeight() * 0.5f) - 0.02f));

        for (int legIdx = 0; legIdx < 4; legIdx++) {
            for (int side = 0; side < 2; side++) {
                int limbIndex = legIdx + side * 4;
                float sideSign = (side == 0) ? -1f : 1f;

                entity.legLastPos[limbIndex] = entity.legPos[limbIndex];
                entity.legPos[limbIndex] = entity.legPos[limbIndex].add(entity.legVel[limbIndex]);

                Vec3d attachPt = bodyCenterWorld.add(
                        computeBodyAttachOffset(Vec3d.ZERO, bodyDir, sideDir, surfaceNormal, size, legIdx, sideSign)
                );

                float totalLegLen = LIMB_LENGTHS[legIdx][0] * limbLength;

                Vec3d toFoot = entity.legPos[limbIndex].subtract(attachPt);
                double dist = toFoot.length();
                if (dist > totalLegLen && dist > 1e-5) {
                    entity.legPos[limbIndex] = attachPt.add(toFoot.normalize().multiply(totalLegLen));
                }

                entity.legVel[limbIndex] = entity.legVel[limbIndex].add(velocity.multiply(0.06));
                entity.legVel[limbIndex] = entity.legVel[limbIndex].multiply(0.78);

                Vec3d idealDir = computeIdealSpiderLegDirection(
                        bodyDir, sideDir, surfaceNormal, legIdx, sideSign, entity.getLegsPosition()
                );
                Vec3d idealFoot = attachPt.add(idealDir.multiply(totalLegLen * 0.85));

                if (!entity.legsInitialized) {
                    Vec3d grip = findGrip(world, attachPt, idealFoot, totalLegLen * 1.5);
                    entity.legPos[limbIndex] = (grip != null) ? grip : idealFoot;
                    entity.legLastPos[limbIndex] = entity.legPos[limbIndex];
                    entity.legGripTarget[limbIndex] = grip;
                    entity.legGripped[limbIndex] = (grip != null);
                    entity.legVel[limbIndex] = Vec3d.ZERO;
                    continue;
                }

                float sideOffset = (side == 0) ? 0f : 0.5f;
                float segOffset = legIdx / 4f;
                float rawPhase = walkCycle + segOffset + sideOffset;
                float phase = rawPhase - (float) Math.floor(rawPhase);
                boolean inSwing = isMoving && (phase < SWING_FRAC);
                float swingT = inSwing ? (phase / SWING_FRAC) : 0f;

                if (inSwing) {
                    if (entity.legGripped[limbIndex]) {
                        entity.legGripped[limbIndex] = false;
                        entity.legGripTarget[limbIndex] = null;
                        entity.legVel[limbIndex] = entity.legVel[limbIndex].add(surfaceNormal.multiply(6f * PX));
                    }

                    if (swingT < 0.6f) {
                        Vec3d toIdeal = idealFoot.subtract(entity.legPos[limbIndex]);
                        entity.legVel[limbIndex] = entity.legVel[limbIndex].add(toIdeal.multiply(0.2));
                        float arc = (float) Math.sin(swingT / 0.6f * Math.PI) * 4f * PX;
                        entity.legVel[limbIndex] = entity.legVel[limbIndex].add(surfaceNormal.multiply(arc));
                    } else {
                        Vec3d grip = findGrip(world, attachPt, idealFoot, totalLegLen * 1.5);
                        if (grip != null) {
                            entity.legGripTarget[limbIndex] = grip;
                            Vec3d toGrip = grip.subtract(entity.legPos[limbIndex]);
                            double toGripDist = toGrip.length();
                            if (toGripDist < 0.04) {
                                entity.legGripped[limbIndex] = true;
                                entity.legPos[limbIndex] = grip;
                                entity.legVel[limbIndex] = Vec3d.ZERO;
                            } else {
                                entity.legVel[limbIndex] = entity.legVel[limbIndex].add(
                                        toGrip.normalize().multiply(Math.min(toGripDist * 0.5, 0.15))
                                );
                            }
                        } else {
                            Vec3d toIdeal = idealFoot.subtract(entity.legPos[limbIndex]);
                            entity.legVel[limbIndex] = entity.legVel[limbIndex].add(toIdeal.multiply(0.25));
                        }
                    }
                } else {
                    if (entity.legGripped[limbIndex] && entity.legGripTarget[limbIndex] != null) {
                        double attachToGrip = attachPt.distanceTo(entity.legGripTarget[limbIndex]);
                        if (attachToGrip > totalLegLen * 1.7) {
                            entity.legGripped[limbIndex] = false;
                            entity.legGripTarget[limbIndex] = null;
                            entity.legVel[limbIndex] = entity.legVel[limbIndex].add(idealDir.multiply(8f * PX));
                        } else {
                            entity.legPos[limbIndex] = entity.legGripTarget[limbIndex];
                            entity.legVel[limbIndex] = Vec3d.ZERO;
                        }
                    } else {
                        Vec3d grip = findGrip(world, attachPt, idealFoot, totalLegLen * 1.5);
                        if (grip != null) {
                            entity.legGripped[limbIndex] = true;
                            entity.legGripTarget[limbIndex] = grip;
                            entity.legPos[limbIndex] = grip;
                            entity.legVel[limbIndex] = Vec3d.ZERO;
                        } else {
                            entity.legVel[limbIndex] = entity.legVel[limbIndex].add(idealDir.multiply(8f * PX));
                            Vec3d toIdeal = idealFoot.subtract(entity.legPos[limbIndex]);
                            entity.legVel[limbIndex] = entity.legVel[limbIndex].add(toIdeal.multiply(0.3));
                        }
                    }
                }
            }
        }

        entity.legsInitialized = true;
    }

    // =========================================================================
    // Pose helpers
    // =========================================================================

    private static Vec3d getInterpolatedSurfaceNormal(SpiderEntity entity, float tickDelta) {
        Vec3d n = new Vec3d(
                MathHelper.lerp(tickDelta, entity.prevSurfaceNormalX, entity.surfaceNormalX),
                MathHelper.lerp(tickDelta, entity.prevSurfaceNormalY, entity.surfaceNormalY),
                MathHelper.lerp(tickDelta, entity.prevSurfaceNormalZ, entity.surfaceNormalZ)
        );
        if (n.lengthSquared() < 0.001) return new Vec3d(0, 1, 0);
        return n.normalize();
    }

    /**
     * Leg root positions around the raised body plane.
     * Front legs mount slightly higher, rear legs mount lower.
     */
    private static Vec3d computeBodyAttachOffset(Vec3d bodyCenterLocal,
                                                 Vec3d bodyDir,
                                                 Vec3d sideDir,
                                                 Vec3d surfaceNormal,
                                                 float size,
                                                 int legIdx,
                                                 float sideSign) {
        float[] forwardOffsets = { 0.13f, 0.05f, -0.03f, -0.10f };
        float[] sideOffsets = { 0.09f, 0.11f, 0.11f, 0.09f };
        float[] verticalOffsets = { 0.01f, -0.01f, -0.03f, -0.05f };

        return bodyCenterLocal
                .add(bodyDir.multiply(forwardOffsets[legIdx] * size))
                .add(sideDir.multiply(sideOffsets[legIdx] * sideSign * size))
                .add(surfaceNormal.multiply(verticalOffsets[legIdx] * size));
    }

    /**
     * Traditional spider feel:
     * front legs up and forward, rear legs down and backward.
     */
    private static Vec3d computeIdealSpiderLegDirection(Vec3d bodyDir,
                                                        Vec3d sideDir,
                                                        Vec3d surfaceNormal,
                                                        int legIdx,
                                                        float sideSign,
                                                        float legsPosition) {
        float[] forwardBias = { 0.85f, 0.35f, -0.25f, -0.75f };
        float[] outwardBias = { 1.10f, 1.00f, 0.95f, 0.85f };
        float[] verticalBias = { 0.35f, 0.05f, -0.18f, -0.35f };

        Vec3d dir = bodyDir.multiply(forwardBias[legIdx])
                .add(sideDir.multiply(outwardBias[legIdx] * sideSign))
                .add(surfaceNormal.multiply(verticalBias[legIdx]))
                .add(bodyDir.multiply(0.15f * legsPosition * sideSign));

        if (dir.lengthSquared() < 0.001) dir = sideDir.multiply(sideSign);
        return dir.normalize();
    }

    private static Vec3d computeSpiderLegPole(Vec3d bodyDir,
                                              Vec3d outward,
                                              Vec3d surfaceNormal,
                                              float bendFactor,
                                              int legIdx) {
        float[] upBias =   { 0.55f, 0.18f, -0.15f, -0.35f };
        float[] foreBias = { 0.45f, 0.18f, -0.18f, -0.40f };

        Vec3d pole = outward.multiply(1.0)
                .add(surfaceNormal.multiply(upBias[legIdx]))
                .add(bodyDir.multiply(foreBias[legIdx] * Math.signum(bendFactor)));

        if (pole.lengthSquared() < 0.001) pole = outward;
        return pole.normalize();
    }

    // =========================================================================
    // IK
    // =========================================================================

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
        double maxReach = len1 + len2 - 1e-4;
        double clampedDist = Math.min(dist, maxReach);

        double cosA = ((clampedDist * clampedDist) + (len1 * len1) - (len2 * len2))
                / (2.0 * clampedDist * len1);
        cosA = MathHelper.clamp((float) cosA, -1f, 1f);

        double along = cosA * len1;
        double bend = Math.sqrt(Math.max(0.0, (len1 * len1) - (along * along)));

        double minBend = Math.min(len1, len2) * minBendFraction;
        bend = Math.max(bend, minBend);

        Vec3d planePole = poleVector.subtract(dir.multiply(poleVector.dotProduct(dir)));
        if (planePole.lengthSquared() < 0.001) {
            planePole = dir.crossProduct(new Vec3d(0, 1, 0));
            if (planePole.lengthSquared() < 0.001) {
                planePole = dir.crossProduct(new Vec3d(1, 0, 0));
            }
        }
        planePole = planePole.normalize();

        return start.add(dir.multiply(along)).add(planePole.multiply(bend));
    }

    // =========================================================================
    // Grip search
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
                    MathHelper.clamp(target.x, bx, bx + 1),
                    by + 1.0,
                    MathHelper.clamp(target.z, bz, bz + 1)
            );
            case DOWN -> new Vec3d(
                    MathHelper.clamp(target.x, bx, bx + 1),
                    by,
                    MathHelper.clamp(target.z, bz, bz + 1)
            );
            case NORTH -> new Vec3d(
                    MathHelper.clamp(target.x, bx, bx + 1),
                    MathHelper.clamp(target.y, by, by + 1),
                    bz
            );
            case SOUTH -> new Vec3d(
                    MathHelper.clamp(target.x, bx, bx + 1),
                    MathHelper.clamp(target.y, by, by + 1),
                    bz + 1.0
            );
            case WEST -> new Vec3d(
                    bx,
                    MathHelper.clamp(target.y, by, by + 1),
                    MathHelper.clamp(target.z, bz, bz + 1)
            );
            case EAST -> new Vec3d(
                    bx + 1.0,
                    MathHelper.clamp(target.y, by, by + 1),
                    MathHelper.clamp(target.z, bz, bz + 1)
            );
        };
    }

    // =========================================================================
    // Rendering helpers
    // =========================================================================

    /**
     * Body quad rendered in a fixed world/body plane.
     */
    private static void renderPlanarQuad(MatrixStack matrices, VertexConsumerProvider vcProvider, int light,
                                         FAtlasElement sprite, Vec3d center,
                                         Vec3d rightAxis, Vec3d upAxis,
                                         float halfW, float halfH,
                                         Vec3d faceNormal,
                                         int r, int g, int b) {
        Vec3d right = rightAxis.normalize();
        Vec3d up = upAxis.normalize();
        Vec3d normal = faceNormal.normalize();

        float cx = (float) center.x;
        float cy = (float) center.y;
        float cz = (float) center.z;

        float rx = (float) (right.x * halfW);
        float ry = (float) (right.y * halfW);
        float rz = (float) (right.z * halfW);

        float ux = (float) (up.x * halfH);
        float uy = (float) (up.y * halfH);
        float uz = (float) (up.z * halfH);

        float blX = cx - rx - ux;
        float blY = cy - ry - uy;
        float blZ = cz - rz - uz;
        float brX = cx + rx - ux;
        float brY = cy + ry - uy;
        float brZ = cz + rz - uz;
        float trX = cx + rx + ux;
        float trY = cy + ry + uy;
        float trZ = cz + rz + uz;
        float tlX = cx - rx + ux;
        float tlY = cy - ry + uy;
        float tlZ = cz - rz + uz;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vcProvider.getBuffer(RenderLayer.getEntityCutoutNoCull(sprite.textureIdentifier));

        vc.vertex(mat, blX, blY, blZ).color(r, g, b, 255)
                .texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z);
        vc.vertex(mat, brX, brY, brZ).color(r, g, b, 255)
                .texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z);
        vc.vertex(mat, trX, trY, trZ).color(r, g, b, 255)
                .texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z);
        vc.vertex(mat, tlX, tlY, tlZ).color(r, g, b, 255)
                .texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    /**
     * Leg sprite rendered in a fixed plane like the centipede leg renderer.
     */
    private static void renderBoneSprite(MatrixStack matrices, VertexConsumerProvider vcProvider, int light,
                                         Vec3d startLocal, Vec3d endLocal, float halfWidth,
                                         FAtlasElement sprite,
                                         Vec3d faceHint,
                                         int topR, int topG, int topB,
                                         int botR, int botG, int botB) {
        Vec3d limbDir = endLocal.subtract(startLocal);
        double limbLen = limbDir.length();
        if (limbLen < 0.001) return;

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
        if (widthDir.lengthSquared() < 0.001) {
            widthDir = face;
        } else {
            widthDir = widthDir.normalize();
        }

        float nfx = (float) face.x;
        float nfy = (float) face.y;
        float nfz = (float) face.z;

        float wdx = (float) (widthDir.x * halfWidth);
        float wdy = (float) (widthDir.y * halfWidth);
        float wdz = (float) (widthDir.z * halfWidth);

        float s0x = (float) startLocal.x, s0y = (float) startLocal.y, s0z = (float) startLocal.z;
        float s1x = (float) endLocal.x,   s1y = (float) endLocal.y,   s1z = (float) endLocal.z;

        float blX = s0x - wdx, blY = s0y - wdy, blZ = s0z - wdz;
        float brX = s0x + wdx, brY = s0y + wdy, brZ = s0z + wdz;
        float trX = s1x + wdx, trY = s1y + wdy, trZ = s1z + wdz;
        float tlX = s1x - wdx, tlY = s1y - wdy, tlZ = s1z - wdz;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vcProvider.getBuffer(RenderLayer.getEntityCutoutNoCull(sprite.textureIdentifier));

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