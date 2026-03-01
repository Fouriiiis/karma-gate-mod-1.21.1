package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * AI goal: Hunt a target.
 * The centipede crawls toward its target and attempts to grab it with its leading head.
 * Mirrors C# CentipedeAI Behavior.Hunt + Centipede.Act()/Crawl().
 */
public class CentipedeHuntGoal extends Goal {

    private final RedCentipedeEntity centipede;
    private LivingEntity target;
    private int cooldown = 0;

    public CentipedeHuntGoal(RedCentipedeEntity centipede) {
        this.centipede = centipede;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        LivingEntity t = centipede.getTarget();
        if (t == null || !t.isAlive()) return false;
        target = t;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (target == null || !target.isAlive()) return false;
        if (target.isRemoved()) return false;
        return centipede.squaredDistanceTo(target) < 48 * 48;
    }

    @Override
    public void start() {
        centipede.setHuntTarget(target);
    }

    @Override
    public void stop() {
        centipede.setHuntTarget(null);
        centipede.stopMoving();
        target = null;
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) return;

        centipede.setHuntTarget(target);
        centipede.updateDirectionChange();

        // Set move target to the prey's position
        centipede.setMoveTarget(target.getPos());
        centipede.driveTowardTarget();

        // Look at target
        centipede.getLookControl().lookAt(target, 30.0f, 30.0f);
    }
}
