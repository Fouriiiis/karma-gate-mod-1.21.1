package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * AI goal: Wander / idle behavior.
 * The centipede picks random nearby positions and crawls to them.
 * Mirrors C# CentipedeAI Behavior.Idle with idle position scoring.
 */
public class CentipedeWanderGoal extends Goal {

    private final RedCentipedeEntity centipede;
    private Vec3d wanderTarget;
    private int idleCounter = 0;
    private int retargetCooldown = 0;

    public CentipedeWanderGoal(RedCentipedeEntity centipede) {
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
                centipede.setMoveTarget(wanderTarget);
                centipede.driveTowardTarget();
            }
        }
    }

    private void pickNewTarget() {
        // Pick a random position within 10 blocks, preferring near surfaces (C# IdleScore)
        Vec3d pos = centipede.getPos();
        double dx = (centipede.getRandom().nextDouble() - 0.5) * 20;
        double dy = (centipede.getRandom().nextDouble() - 0.5) * 6;
        double dz = (centipede.getRandom().nextDouble() - 0.5) * 20;
        wanderTarget = new Vec3d(pos.x + dx, pos.y + dy, pos.z + dz);
        retargetCooldown = 100 + centipede.getRandom().nextInt(200);
    }
}
