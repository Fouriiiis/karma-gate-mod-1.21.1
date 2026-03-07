package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * AI goal: Wander / idle behavior.
 * The centipede picks random accessible positions near surfaces and uses
 * the CentipedePathfinder to find a valid surface-connected path.
 * Mirrors C# CentipedeAI Behavior.Idle with idle position scoring
 * and StandardPather.FollowPath() for path following.
 */
public class CentipedeWanderGoal<T extends HostileEntity & CentipedeController> extends Goal {

    private final T centipede;
    private Vec3d wanderTarget;
    private int idleCounter = 0;
    private int retargetCooldown = 0;
    private int failedAttempts = 0;

    public CentipedeWanderGoal(T centipede) {
        this.centipede = centipede;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        // Only wander if no hunt target
        return centipede.getTarget() == null && centipede.areSegmentsSpawned();
    }

    @Override
    public boolean shouldContinue() {
        return centipede.getTarget() == null;
    }

    @Override
    public void start() {
        pickNewTarget();
    }

    @Override
    public void stop() {
        centipede.stopMoving();
        wanderTarget = null;
    }

    @Override
    public void tick() {
        idleCounter++;
        retargetCooldown--;

        if (wanderTarget == null || idleCounter > 200 || retargetCooldown <= 0) {
            pickNewTarget();
            idleCounter = 0;
        }

        if (wanderTarget != null) {
            double dist = centipede.getPos().squaredDistanceTo(wanderTarget);
            if (dist < 2.0) {
                // Reached target — pause then pick new
                centipede.stopMoving();
                if (idleCounter > 40) {
                    pickNewTarget();
                    idleCounter = 0;
                }
            } else {
                // Check if we need a path recalc
                if (centipede.needsPathRecalc(wanderTarget) && retargetCooldown < 80) {
                    centipede.requestPathTo(wanderTarget);
                }

                centipede.setMoveTarget(wanderTarget);
                // Use pathfinding-based movement
                centipede.followCurrentPath();
            }
        }
    }

    /**
     * Pick a random accessible position near a surface.
     * Mirrors C# CentipedeAI.IdleScore — prefers positions
     * adjacent to solid blocks (walls, ceiling, floor).
     */
    private void pickNewTarget() {
        Vec3d pos = centipede.getPos();

        // Try multiple random positions and pick the best one
        // (mirrors C# IdleScore which evaluates candidate positions)
        BlockPos bestTarget = null;
        int bestProximity = Integer.MAX_VALUE;

        for (int attempt = 0; attempt < 8; attempt++) {
            double dx = (centipede.getRandom().nextDouble() - 0.5) * 20;
            double dy = (centipede.getRandom().nextDouble() - 0.5) * 6;
            double dz = (centipede.getRandom().nextDouble() - 0.5) * 20;

            BlockPos candidate = BlockPos.ofFloored(pos.x + dx, pos.y + dy, pos.z + dz);

            // Check if the candidate is accessible (near a surface)
            if (CentipedePathfinder.isAccessible(centipede.getWorld(), candidate)) {
                int proximity = CentipedePathfinder.getTerrainProximity(
                        centipede.getWorld(), candidate);
                // Prefer positions directly touching surfaces (proximity == 1)
                if (proximity < bestProximity) {
                    bestProximity = proximity;
                    bestTarget = candidate;
                }
            }
        }

        if (bestTarget != null) {
            wanderTarget = new Vec3d(
                    bestTarget.getX() + 0.5,
                    bestTarget.getY() + 0.5,
                    bestTarget.getZ() + 0.5);
            // Request a path to the chosen target
            centipede.requestPathTo(wanderTarget);
            failedAttempts = 0;
        } else {
            // No accessible target found — try a shorter range next time
            failedAttempts++;
            wanderTarget = null;
        }

        retargetCooldown = 100 + centipede.getRandom().nextInt(200);
    }
}
