package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * AI goal: Hunt a target.
 * The centipede uses the CentipedePathfinder to find a surface-connected
 * path to its target, then follows the path waypoints.
 * Mirrors C# CentipedeAI Behavior.Hunt + StandardPather.FollowPath().
 */
public class CentipedeHuntGoal<T extends HostileEntity & CentipedeController> extends Goal {

    private final T centipede;
    private LivingEntity target;
    private int pathRecalcCooldown = 0;

    public CentipedeHuntGoal(T centipede) {
        this.centipede = centipede;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        LivingEntity t = centipede.getTarget();
        if (t == null || !t.isAlive()) return false;
        // Ignore players not in survival mode
        if (t instanceof PlayerEntity player) {
            if (player.isCreative() || player.isSpectator()) return false;
        }
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
        // Request initial path to target
        centipede.requestPathTo(target.getPos());
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

        // Check if we need to recalculate the path
        // (target moved, path invalidated, or no path yet)
        pathRecalcCooldown--;
        if (centipede.needsPathRecalc(target.getPos()) && pathRecalcCooldown <= 0) {
            centipede.requestPathTo(target.getPos());
            pathRecalcCooldown = 10; // Don't spam path requests
        }

        // Set the final move target for direct fallback
        centipede.setMoveTarget(target.getPos());

        // Follow the computed path (falls back to direct if no path)
        centipede.followCurrentPath();

        // Look at target
        centipede.getLookControl().lookAt(target, 30.0f, 30.0f);
    }
}
