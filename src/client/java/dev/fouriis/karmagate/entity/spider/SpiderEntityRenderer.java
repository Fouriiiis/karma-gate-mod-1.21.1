package dev.fouriis.karmagate.entity.spider;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasManager;
import net.minecraft.client.MinecraftClient;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Procedural renderer for Rain World coalmine spiders.
 * 
 * Ports SpiderGraphics.cs rendering:
 * - Body sprite as a billboard quad
 * - 8 legs (4 pairs) with 2-bone IK, each rendered as billboarded sprite quads
 * - Per-tick limb simulation with surface grip (FindGrip)
 * - Walk cycle animations with alternating leg phases
 * 
 * Uses FAtlasManager from librainworldmc for sprites, specifically the "tinyStar"
 * element as the body sprite (per user request).
 */
public class SpiderEntityRenderer extends EntityRenderer<SpiderEntity> {

    // Atlas sprites (lazily resolved)
    private static FAtlasManager atlasManager = null;
    private static FAtlasElement bodySprite = null;
    // Per-leg-pair sprites: legSprites[legIdx][segment] where segment 0=A (upper), 1=B (lower)
    private static final FAtlasElement[][] legSprites = new FAtlasElement[4][2];
    private static boolean spritesLoaded = false;

    // Scale: 1 C# pixel ≈ 0.025 MC blocks
    private static final float PX = 0.025f;

    // C# legSpriteSizes: native sprite dimensions for angle calculations
    private static final float[][] LEG_SPRITE_SIZES = {
            {19f, 20f},
            {26f, 20f},
            {21f, 23f},
            {26f, 17f}
    };

    // C# limbLengths: proportional lengths per leg pair
    private static final float[][] LIMB_LENGTHS = {
            {0.85f, 0.5f},
            {1.0f, 0.6f},
            {0.95f, 0.5f},
            {0.9f, 0.65f}
    };

    // Spider body/leg colors (dark, matches Rain World palette)
    private static final int BODY_R = 15, BODY_G = 12, BODY_B = 10;
    private static final int LEG_R = 20, LEG_G = 18, LEG_B = 15;

    // Sprite width aspect ratios for leg bones
    private static final float ASPECT_UPPER = 0.25f;
    private static final float ASPECT_LOWER = 0.28f;

    public SpiderEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.1f;
    }

    @Override
    public Identifier getTexture(SpiderEntity entity) {
        // We use atlas sprites, not a single texture
        return Identifier.of("karma-gate-mod", "textures/entity/spider_fallback.png");
    }

    @Override
    public void render(SpiderEntity entity, float yaw, float tickDelta,
                       MatrixStack matrices, VertexConsumerProvider vcProvider, int light) {
        // Lazily load atlas sprites
        if (!spritesLoaded) {
            atlasManager = LibrainworldmcClient.getAtlasManager();
            if (atlasManager == null) return;
            // Body sprite: "SpiderBody" as referenced in C# InitiateSprites
            bodySprite = atlasManager.getElementWithName("SpiderBody");
            // Fallback to tinyStar if SpiderBody not found
            if (bodySprite == null) bodySprite = atlasManager.getElementWithName("tinyStar");
            if (bodySprite == null) return;
            // Per-leg sprites: "SpiderLeg0A", "SpiderLeg0B", ..., "SpiderLeg3A", "SpiderLeg3B"
            for (int i = 0; i < 4; i++) {
                legSprites[i][0] = atlasManager.getElementWithName("SpiderLeg" + i + "A");
                if (legSprites[i][0] == null) legSprites[i][0] = bodySprite;
                legSprites[i][1] = atlasManager.getElementWithName("SpiderLeg" + i + "B");
                if (legSprites[i][1] == null) legSprites[i][1] = bodySprite;
            }
            spritesLoaded = true;
        }
        if (bodySprite == null) return;

        float size = entity.getSizeFactor();
        float limbLength = MathHelper.lerp(size, 10f, 40f) * PX;

        // --- Per-tick limb simulation ---
        boolean newTick = (entity.legUpdateAge != entity.age);
        if (newTick) {
            entity.legUpdateAge = entity.age;
            updateLegs(entity, limbLength, size);
        }

        // Camera billboard basis
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.gameRenderer == null) return;
        Quaternionf camRot = new Quaternionf(client.gameRenderer.getCamera().getRotation());
        Vector3f billRight = camRot.transform(new Vector3f(1f, 0f, 0f));
        Vector3f billUp = camRot.transform(new Vector3f(0f, 1f, 0f));
        Vector3f billNorm = camRot.transform(new Vector3f(0f, 0f, 1f));

        // Entity position (interpolated)
        Vec3d entityPos = new Vec3d(
                MathHelper.lerp(tickDelta, entity.prevTickPos.x, entity.getPos().x),
                MathHelper.lerp(tickDelta, entity.prevTickPos.y, entity.getPos().y),
                MathHelper.lerp(tickDelta, entity.prevTickPos.z, entity.getPos().z));

        matrices.push();

        // --- Compute body direction ---
        Vec3d velocity = entity.getVelocity();
        Vec3d bodyDir;
        if (velocity.horizontalLengthSquared() > 0.001) {
            bodyDir = velocity.normalize();
        } else {
            bodyDir = entity.direction;
        }
        Vec3d perpDir = bodyDir.crossProduct(new Vec3d(0, 1, 0));
        if (perpDir.lengthSquared() < 0.001) {
            perpDir = bodyDir.crossProduct(new Vec3d(1, 0, 0));
        }
        if (perpDir.lengthSquared() > 0.001) {
            perpDir = perpDir.normalize();
        }

        // --- Render body with rotation matching bodyDir (C# DrawSprites) ---
        // C# body scale: Lerp(0.2, 1.0, size)
        float bodyScale = MathHelper.lerp(size, 0.2f, 1.0f);
        float bodySize = bodyScale * 0.06f; // map to MC world units
        // Project bodyDir onto billboard plane to get rotation angle
        float bodyDirOnRight = (float)(bodyDir.x * billRight.x + bodyDir.y * billRight.y + bodyDir.z * billRight.z);
        float bodyDirOnUp = (float)(bodyDir.x * billUp.x + bodyDir.y * billUp.y + bodyDir.z * billUp.z);
        float bodyAngle = (float) Math.atan2(bodyDirOnRight, bodyDirOnUp);
        renderRotatedBillboardQuad(matrices, vcProvider, light, bodySprite,
                Vec3d.ZERO, bodySize, bodySize, bodyAngle,
                billRight, billUp, billNorm,
                BODY_R, BODY_G, BODY_B);

        // --- Walk cycle ---
        float moveSpeed = (float) velocity.horizontalLength();

        // --- Render legs ---
        for (int legIdx = 0; legIdx < 4; legIdx++) {
            for (int side = 0; side < 2; side++) {
                int limbIndex = legIdx + side * 4;

                // Interpolated foot position relative to entity
                Vec3d footWorld = new Vec3d(
                        MathHelper.lerp(tickDelta, entity.legLastPos[limbIndex].x, entity.legPos[limbIndex].x),
                        MathHelper.lerp(tickDelta, entity.legLastPos[limbIndex].y, entity.legPos[limbIndex].y),
                        MathHelper.lerp(tickDelta, entity.legLastPos[limbIndex].z, entity.legPos[limbIndex].z));
                Vec3d footLocal = footWorld.subtract(entityPos);

                // Attach point on body
                float sideSign = (side == 0) ? -1f : 1f;
                Vec3d attachOffset = bodyDir.multiply((7f - legIdx * 0.5f - ((legIdx == 3) ? 1.5f : 0f)) * size * PX)
                        .add(perpDir.multiply((3f + legIdx * 0.5f - ((legIdx == 3) ? 5.5f : 0f)) * sideSign * size * PX));

                // Bone lengths
                float upperLen = LIMB_LENGTHS[legIdx][0] * LIMB_LENGTHS[legIdx][1] * limbLength;
                float lowerLen = LIMB_LENGTHS[legIdx][0] * (1f - LIMB_LENGTHS[legIdx][1]) * limbLength;

                // IK bend factor
                float bendFactor = ((legIdx < 3) ? 1f : -1f) * sideSign;

                // Inverse kinematics for knee position
                Vec3d kneeLocal = inverseKinematics3D(attachOffset, footLocal,
                        upperLen, lowerLen, bendFactor, perpDir);

                // C# scaleX: ((j == 0) ? 1 : -1) * Lerp(0.45, 0.65, size)
                float scaleX = MathHelper.lerp(size, 0.45f, 0.65f);

                // Render upper leg bone (SpiderLeg{legIdx}A)
                float halfWidthA = (float)(attachOffset.distanceTo(kneeLocal) * ASPECT_UPPER * 0.5f) * scaleX;
                renderLegSprite(matrices, vcProvider, light,
                        attachOffset, kneeLocal, halfWidthA,
                        legSprites[legIdx][0], billRight, billUp, billNorm,
                        LEG_R, LEG_G, LEG_B, LEG_R, LEG_G, LEG_B);

                // Render lower leg bone (SpiderLeg{legIdx}B)
                float halfWidthB = (float)(kneeLocal.distanceTo(footLocal) * ASPECT_LOWER * 0.5f) * scaleX;
                renderLegSprite(matrices, vcProvider, light,
                        kneeLocal, footLocal, halfWidthB,
                        legSprites[legIdx][1], billRight, billUp, billNorm,
                        LEG_R + 5, LEG_G + 3, LEG_B + 2,
                        LEG_R - 2, LEG_G - 2, LEG_B - 2);
            }
        }

        matrices.pop();

        super.render(entity, yaw, tickDelta, matrices, vcProvider, light);
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

        Vec3d perpDir = bodyDir.crossProduct(new Vec3d(0, 1, 0));
        if (perpDir.lengthSquared() < 0.001) {
            perpDir = bodyDir.crossProduct(new Vec3d(1, 0, 0));
        }
        if (perpDir.lengthSquared() > 0.001) perpDir = perpDir.normalize();

        // Update direction for rendering
        if (velocity.horizontalLengthSquared() > 0.001) {
            entity.direction = bodyDir;
        }

        float moveSpeed = (float)velocity.horizontalLength();
        boolean isMoving = moveSpeed > 0.01f;

        float walkCycle = entity.age * 0.15f * moveSpeed;

        // Metachronal wave for 4 legs per side
        final float SWING_FRAC = 0.35f;

        for (int legIdx = 0; legIdx < 4; legIdx++) {
            for (int side = 0; side < 2; side++) {
                int limbIndex = legIdx + side * 4;
                float sideSign = (side == 0) ? -1f : 1f;

                // Save last position
                entity.legLastPos[limbIndex] = entity.legPos[limbIndex];

                // Apply velocity
                entity.legPos[limbIndex] = entity.legPos[limbIndex].add(entity.legVel[limbIndex]);

                // Attach point
                Vec3d attachPt = entityPos.add(
                        bodyDir.multiply((7f - legIdx * 0.5f - ((legIdx == 3) ? 1.5f : 0f)) * size * PX))
                        .add(perpDir.multiply((3f + legIdx * 0.5f - ((legIdx == 3) ? 5.5f : 0f)) * sideSign * size * PX));

                float totalLegLen = LIMB_LENGTHS[legIdx][0] * limbLength;

                // Constrain to max leg length
                Vec3d toFoot = entity.legPos[limbIndex].subtract(attachPt);
                double dist = toFoot.length();
                if (dist > totalLegLen) {
                    entity.legPos[limbIndex] = attachPt.add(toFoot.normalize().multiply(totalLegLen));
                }

                // Friction + body momentum transfer
                entity.legVel[limbIndex] = entity.legVel[limbIndex].add(velocity.multiply(0.06));
                entity.legVel[limbIndex] = entity.legVel[limbIndex].multiply(0.78);

                // Ideal foot direction (C# SpiderGraphics leg angles)
                float angleDeg = MathHelper.lerp(legIdx / 3f, 30f, 140f) + 20f * entity.getLegsPosition();
                if (legIdx == 3) angleDeg += 20f;
                double angleRad = Math.toRadians(angleDeg * sideSign);

                // Rotate bodyDir by angle
                double cos = Math.cos(angleRad);
                double sin = Math.sin(angleRad);
                Vec3d idealDir = bodyDir.multiply(cos).add(perpDir.multiply(sin));
                if (idealDir.lengthSquared() > 0.001) idealDir = idealDir.normalize();

                Vec3d idealFoot = attachPt.add(idealDir.multiply(totalLegLen * 0.85));

                // First-tick initialization
                if (!entity.legsInitialized) {
                    Vec3d grip = findGrip(world, attachPt, idealFoot, totalLegLen * 1.5);
                    entity.legPos[limbIndex] = (grip != null) ? grip : idealFoot;
                    entity.legLastPos[limbIndex] = entity.legPos[limbIndex];
                    entity.legGripTarget[limbIndex] = grip;
                    entity.legGripped[limbIndex] = (grip != null);
                    entity.legVel[limbIndex] = Vec3d.ZERO;
                    continue;
                }

                // Phase for walk cycle (alternating pairs)
                float sideOffset = (side == 0) ? 0f : 0.5f;
                float segOffset = legIdx / 4f;
                float rawPhase = walkCycle + segOffset + sideOffset;
                float phase = rawPhase - (float) Math.floor(rawPhase);
                boolean inSwing = isMoving && (phase < SWING_FRAC);
                float swingT = inSwing ? (phase / SWING_FRAC) : 0f;

                if (inSwing) {
                    // Swing phase: lift and arc forward
                    if (entity.legGripped[limbIndex]) {
                        entity.legGripped[limbIndex] = false;
                        entity.legGripTarget[limbIndex] = null;
                        entity.legVel[limbIndex] = entity.legVel[limbIndex].add(
                                new Vec3d(0, 6f * PX, 0));
                    }

                    if (swingT < 0.6f) {
                        Vec3d toIdeal = idealFoot.subtract(entity.legPos[limbIndex]);
                        entity.legVel[limbIndex] = entity.legVel[limbIndex].add(toIdeal.multiply(0.2));
                        float arcY = (float) Math.sin(swingT / 0.6f * Math.PI) * 4f * PX;
                        entity.legVel[limbIndex] = entity.legVel[limbIndex].add(new Vec3d(0, arcY, 0));
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
                                        toGrip.normalize().multiply(Math.min(toGripDist * 0.5, 0.15)));
                            }
                        } else {
                            Vec3d toIdeal = idealFoot.subtract(entity.legPos[limbIndex]);
                            entity.legVel[limbIndex] = entity.legVel[limbIndex].add(toIdeal.multiply(0.25));
                        }
                    }
                } else {
                    // Stance phase: keep planted
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
    // FindGrip: search for terrain surface near idealFoot
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
            case UP -> new Vec3d(MathHelper.clamp(target.x, bx, bx + 1), by + 1.0, MathHelper.clamp(target.z, bz, bz + 1));
            case DOWN -> new Vec3d(MathHelper.clamp(target.x, bx, bx + 1), by, MathHelper.clamp(target.z, bz, bz + 1));
            case NORTH -> new Vec3d(MathHelper.clamp(target.x, bx, bx + 1), MathHelper.clamp(target.y, by, by + 1), bz);
            case SOUTH -> new Vec3d(MathHelper.clamp(target.x, bx, bx + 1), MathHelper.clamp(target.y, by, by + 1), bz + 1.0);
            case WEST -> new Vec3d(bx, MathHelper.clamp(target.y, by, by + 1), MathHelper.clamp(target.z, bz, bz + 1));
            case EAST -> new Vec3d(bx + 1.0, MathHelper.clamp(target.y, by, by + 1), MathHelper.clamp(target.z, bz, bz + 1));
        };
    }

    // =========================================================================
    // 3D Inverse Kinematics
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
    // Billboard quad rendering
    // =========================================================================

    /**
     * Render a billboard quad with rotation on the billboard plane.
     * Matches C#'s body sprite rendering with AimFromOneVectorToAnother rotation.
     */
    private static void renderRotatedBillboardQuad(MatrixStack matrices, VertexConsumerProvider vcProvider, int light,
                                                    FAtlasElement sprite, Vec3d center, float halfW, float halfH,
                                                    float angle,
                                                    Vector3f billRight, Vector3f billUp, Vector3f billNorm,
                                                    int r, int g, int b) {
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        // Rotate right/up axes on the billboard plane
        float rotRX = cosA * billRight.x - sinA * billUp.x;
        float rotRY = cosA * billRight.y - sinA * billUp.y;
        float rotRZ = cosA * billRight.z - sinA * billUp.z;
        float rotUX = sinA * billRight.x + cosA * billUp.x;
        float rotUY = sinA * billRight.y + cosA * billUp.y;
        float rotUZ = sinA * billRight.z + cosA * billUp.z;

        float cx = (float) center.x;
        float cy = (float) center.y;
        float cz = (float) center.z;

        float blX = cx - rotRX * halfW - rotUX * halfH;
        float blY = cy - rotRY * halfW - rotUY * halfH;
        float blZ = cz - rotRZ * halfW - rotUZ * halfH;
        float brX = cx + rotRX * halfW - rotUX * halfH;
        float brY = cy + rotRY * halfW - rotUY * halfH;
        float brZ = cz + rotRZ * halfW - rotUZ * halfH;
        float trX = cx + rotRX * halfW + rotUX * halfH;
        float trY = cy + rotRY * halfW + rotUY * halfH;
        float trZ = cz + rotRZ * halfW + rotUZ * halfH;
        float tlX = cx - rotRX * halfW + rotUX * halfH;
        float tlY = cy - rotRY * halfW + rotUY * halfH;
        float tlZ = cz - rotRZ * halfW + rotUZ * halfH;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vcProvider.getBuffer(
                RenderLayer.getEntityCutoutNoCull(sprite.textureIdentifier));

        vc.vertex(mat, blX, blY, blZ).color(r, g, b, 255)
                .texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(billNorm.x, billNorm.y, billNorm.z);
        vc.vertex(mat, brX, brY, brZ).color(r, g, b, 255)
                .texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(billNorm.x, billNorm.y, billNorm.z);
        vc.vertex(mat, trX, trY, trZ).color(r, g, b, 255)
                .texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(billNorm.x, billNorm.y, billNorm.z);
        vc.vertex(mat, tlX, tlY, tlZ).color(r, g, b, 255)
                .texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(billNorm.x, billNorm.y, billNorm.z);
    }

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
