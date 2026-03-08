package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * AI goal: Centiwing wander / idle behavior.
 * Unlike CentipedeWanderGoal, centiwings:
 * - Prefer open air positions (no terrain proximity preference)
 * - Search a larger range (30 blocks instead of 20)
 * - Re-target more frequently (1/3 chance of full-room random position)
 * - Set wantToFly=true when destination is in open air
 * - C# CentipedeAI: Centiwing run=500, higher idle position change rate,
 *   no NoiseTracker, larger direction thresholds (40/10 vs 10/2)
 */
public class CentiwingWanderGoal<T extends HostileEntity & CentipedeController> extends Goal {

    private final T centipede;
    private Vec3d wanderTarget;
    private int idleCounter = 0;
    private int retargetCooldown = 0;

    public CentiwingWanderGoal(T centipede) {
        this.centipede = centipede;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        return !centipede.isForcedPathing() && centipede.getTarget() == null && centipede.areSegmentsSpawned();
    }

    @Override
    public boolean shouldContinue() {
        return !centipede.isForcedPathing() && centipede.getTarget() == null;
    }

    @Override
    public void start() {
        pickNewTarget();
    }

    @Override
    public void stop() {
        centipede.stopMoving();
        wanderTarget = null;
        // When stopping wander, don't need to fly
        if (centipede instanceof CentiwingEntity cw) {
            cw.setWantToFly(false);
        }
    }

    @Override
    public void tick() {
        idleCounter++;
        retargetCooldown--;

        // C# Centiwing: higher idle position change rate
        if (wanderTarget == null || idleCounter > 120 || retargetCooldown <= 0) {
            pickNewTarget();
            idleCounter = 0;
        }

        if (wanderTarget != null) {
            double dist = centipede.getPos().squaredDistanceTo(wanderTarget);
            if (dist < 3.0) {
                // Reached target — brief pause then pick new
                centipede.stopMoving();
                if (idleCounter > 20) {
                    pickNewTarget();
                    idleCounter = 0;
                }
            } else {
                centipede.setMoveTarget(wanderTarget);

                // Centiwings fly directly or follow paths
                if (centipede instanceof CentiwingEntity cw && cw.isFlying()) {
                    // When flying, updateFlyPhysics() handles all movement.
                    // Just ensure moveTarget is set (done above).
                } else {
                    if (centipede.needsPathRecalc(wanderTarget) && retargetCooldown < 60) {
                        centipede.requestPathTo(wanderTarget);
                    }
                    centipede.followCurrentPath();
                }
            }
        }
    }

    /**
     * Pick a random position. Centiwings prefer open air, not surfaces.
     * C#: 1/3 chance of picking any random position in range (open air preferred).
     * No terrain proximity penalty (unlike normal centipedes).
     */
    private void pickNewTarget() {
        Vec3d pos = centipede.getPos();
        boolean wantFly = false;

        // C#: 1/3 chance of random full-room (open-air) position
        if (centipede.getRandom().nextFloat() < 0.33f) {
            // Open air position — larger range, prefer vertical freedom
            double dx = (centipede.getRandom().nextDouble() - 0.5) * 60;
            double dy = (centipede.getRandom().nextDouble() - 0.3) * 20; // bias upward
            double dz = (centipede.getRandom().nextDouble() - 0.5) * 60;

            BlockPos candidate = BlockPos.ofFloored(pos.x + dx, pos.y + dy, pos.z + dz);
            if (centipede.getWorld().isAir(candidate)) {
                wanderTarget = new Vec3d(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5);
                wantFly = true;
            } else {
                wanderTarget = null;
            }
        }

        // If no open air target chosen, try accessible position (favoring open spaces)
        if (wanderTarget == null) {
            BlockPos bestTarget = null;
            int bestProximity = -1; // For centiwings: prefer HIGHER proximity = more open

            for (int attempt = 0; attempt < 10; attempt++) {
                double dx = (centipede.getRandom().nextDouble() - 0.5) * 30;
                double dy = (centipede.getRandom().nextDouble() - 0.5) * 10;
                double dz = (centipede.getRandom().nextDouble() - 0.5) * 30;

                BlockPos candidate = BlockPos.ofFloored(pos.x + dx, pos.y + dy, pos.z + dz);

                // Accept any accessible position, but prefer open air
                if (centipede.getWorld().isAir(candidate)) {
                    int proximity = CentipedePathfinder.getTerrainProximity(
                            centipede.getWorld(), candidate);
                    // Higher proximity = farther from surfaces = better for centiwing
                    if (proximity > bestProximity) {
                        bestProximity = proximity;
                        bestTarget = candidate;
                    }
                } else if (CentipedePathfinder.isAccessible(centipede.getWorld(), candidate)) {
                    if (bestTarget == null) {
                        bestTarget = candidate;
                    }
                }
            }

            if (bestTarget != null) {
                wanderTarget = new Vec3d(
                        bestTarget.getX() + 0.5,
                        bestTarget.getY() + 0.5,
                        bestTarget.getZ() + 0.5);

                int prox = CentipedePathfinder.getTerrainProximity(centipede.getWorld(), bestTarget);
                wantFly = (prox > 2); // fly if not touching surface
            }
        }

        // Set fly state
        if (centipede instanceof CentiwingEntity cw) {
            cw.setWantToFly(wantFly);
        }

        if (wanderTarget != null) {
            centipede.requestPathTo(wanderTarget);
        }

        // C# Centiwing: faster retargeting
        retargetCooldown = 60 + centipede.getRandom().nextInt(100);
    }
}
