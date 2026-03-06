package dev.fouriis.karmagate.block;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WormGrassManager {

    private static final class GrabState {
        LivingEntity entity;

        // All wormgrass blocks touched this tick
        final Set<BlockPos> touchedThisTick = new HashSet<>();

        double pull;
        double bury;

        boolean startedBury;

        double buryStartY;
        double buryTargetY;
        double buryX;
        double buryZ;

        int ticksSinceTouch;

        GrabState(LivingEntity entity) {
            this.entity = entity;
        }
    }

    private static final Map<UUID, GrabState> STATES = new HashMap<>();

    // ── Tuning ─────────────────────────────

    private static final double PULL_RADIUS = 1.5;
    private static final double PULL_RADIUS_SQ = PULL_RADIUS * PULL_RADIUS;

    private static final double PULL_RATE = 0.01;
    private static final double BURY_RATE = 0.03;

    private static final double MAX_DRAG = 0.75;
    private static final double PULL_FORCE = 0.08;

    // Feet end inside the block below the lowest touched wormgrass block.
    private static final double BURY_TARGET_OFFSET = 0.10;

    private static final int TOUCH_GRACE_TICKS = 5;

    private WormGrassManager() {}

    // Called from WormGrassBlock.onEntityCollision
    public static void onEntityTouch(World world, BlockPos pos, Entity entity) {
        if (world.isClient) return;
        if (!(entity instanceof LivingEntity living)) return;
        if (entity instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) return;

        GrabState state = STATES.computeIfAbsent(entity.getUuid(), id -> new GrabState(living));
        state.entity = living;
        state.ticksSinceTouch = 0;
        state.touchedThisTick.add(pos.toImmutable());
    }

    public static void tick(ServerWorld world) {
        Iterator<GrabState> it = STATES.values().iterator();

        while (it.hasNext()) {
            GrabState s = it.next();
            LivingEntity e = s.entity;

            if (e == null || !e.isAlive()) {
                stopForcedMovement(e);
                it.remove();
                continue;
            }

            s.ticksSinceTouch++;

            boolean hasTouch = !s.touchedThisTick.isEmpty();

            // If not yet burying and we've lost contact for a few ticks, drop state.
            if (!s.startedBury && !hasTouch && s.ticksSinceTouch > TOUCH_GRACE_TICKS) {
                stopForcedMovement(e);
                it.remove();
                continue;
            }

            // ── Pull phase ──────────────────
            if (!s.startedBury) {
                if (!hasTouch) {
                    s.touchedThisTick.clear();
                    continue;
                }

                PullData pullData = computePullData(e, s.touchedThisTick);

                // Wide entities are allowed if ANY touched block is close enough to their hitbox.
                if (!pullData.inRange) {
                    s.touchedThisTick.clear();
                    continue;
                }

                s.pull = Math.min(1.0, s.pull + PULL_RATE);

                Vec3d vel = e.getVelocity();
                double drag = 1.0 - s.pull * MAX_DRAG;

                double vx = vel.x * drag + pullData.pullX * PULL_FORCE * s.pull;
                double vy = vel.y * drag;
                double vz = vel.z * drag + pullData.pullZ * PULL_FORCE * s.pull;

                e.setVelocity(vx, vy, vz);
                e.velocityModified = true;

                if (s.pull >= 1.0) {
                    // Lock burial to the entity's current footprint, not a block center.
                    s.startedBury = true;
                    s.buryStartY = e.getY();
                    s.buryX = e.getX();
                    s.buryZ = e.getZ();

                    int lowestGrassY = getLowestY(s.touchedThisTick);
                    s.buryTargetY = lowestGrassY - 1 + BURY_TARGET_OFFSET;

                    // Safety: always move downward.
                    if (s.buryTargetY >= s.buryStartY) {
                        s.buryTargetY = s.buryStartY - 0.9;
                    }
                }

                s.touchedThisTick.clear();
                continue;
            }

            // ── Bury phase ──────────────────
            s.bury = Math.min(1.0, s.bury + BURY_RATE);

            double y = lerp(s.buryStartY, s.buryTargetY, s.bury);

            e.noClip = true;
            e.setNoGravity(true);
            e.fallDistance = 0.0f;
            e.setVelocity(0.0, 0.0, 0.0);
            e.velocityModified = true;

            // Keep X/Z locked so wide entities do not snag on adjacent blocks.
            e.setPosition(s.buryX, y, s.buryZ);

            if (e instanceof ServerPlayerEntity player) {
                player.networkHandler.requestTeleport(s.buryX, y, s.buryZ, player.getYaw(), player.getPitch());
            }

            if (s.bury >= 1.0) {
                // Re-enable when ready:
                stopForcedMovement(e);
                e.damage(world.getDamageSources().outOfWorld(), Float.MAX_VALUE);
                it.remove();
            }

            s.touchedThisTick.clear();
        }
    }

    private static PullData computePullData(LivingEntity entity, Set<BlockPos> touchedBlocks) {
        Box box = entity.getBoundingBox();

        double sumX = 0.0;
        double sumZ = 0.0;
        int contributors = 0;
        boolean inRange = false;

        for (BlockPos pos : touchedBlocks) {
            double cx = pos.getX() + 0.5;
            double cz = pos.getZ() + 0.5;

            // Closest point on the entity hitbox to this wormgrass block center
            double nearestX = clamp(cx, box.minX, box.maxX);
            double nearestZ = clamp(cz, box.minZ, box.maxZ);

            double dx = cx - nearestX;
            double dz = cz - nearestZ;
            double distSq = dx * dx + dz * dz;

            if (distSq <= PULL_RADIUS_SQ) {
                inRange = true;

                // Pull toward this block center from the entity body nearest point
                sumX += cx - entity.getX();
                sumZ += cz - entity.getZ();
                contributors++;
            }
        }

        if (!inRange || contributors == 0) {
            return new PullData(false, 0.0, 0.0);
        }

        return new PullData(true, sumX / contributors, sumZ / contributors);
    }

    private static int getLowestY(Set<BlockPos> positions) {
        int y = Integer.MAX_VALUE;
        for (BlockPos pos : positions) {
            if (pos.getY() < y) y = pos.getY();
        }
        return y;
    }

    private static void stopForcedMovement(LivingEntity e) {
        if (e == null) return;
        e.noClip = false;
        e.setNoGravity(false);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record PullData(boolean inRange, double pullX, double pullZ) {}
}