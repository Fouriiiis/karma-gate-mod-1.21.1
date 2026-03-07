package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * AI goal: Centiwing hunt behavior.
 * Similar to CentipedeHuntGoal but centiwings:
 * - Lower prey tracking weight / less persistent (C#: 0.12 vs 0.9)
 * - Always flying when hunting (sets wantToFly=true)
 * - Larger pursuit range to accommodate flight
 * - Fly directly toward target instead of surface-crawling pathfinding
 * - Higher direction change thresholds (C#: 40/10 vs 10/2)
 */
public class CentiwingHuntGoal<T extends HostileEntity & CentipedeController> extends Goal {

    private final T centipede;
    private LivingEntity target;
    private int giveUpTimer = 0;

    public CentiwingHuntGoal(T centipede) {
        this.centipede = centipede;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        LivingEntity t = centipede.getTarget();
        if (t == null || !t.isAlive()) return false;
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
        // Centiwings have a larger pursuit range than normal centipedes
        if (centipede.squaredDistanceTo(target) > 64 * 64) return false;
        // C# lower prey tracking — more likely to give up
        if (giveUpTimer > 400) return false;
        return true;
    }

    @Override
    public void start() {
        centipede.setHuntTarget(target);
        giveUpTimer = 0;
        // Centiwings fly when hunting
        if (centipede instanceof CentiwingEntity cw) {
            cw.setWantToFly(true);
        }
    }

    @Override
    public void stop() {
        centipede.setHuntTarget(null);
        centipede.stopMoving();
        target = null;
        giveUpTimer = 0;
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) return;

        centipede.setHuntTarget(target);
        centipede.updateDirectionChange();

        // Track how long we've been chasing without making progress
        double dist = centipede.squaredDistanceTo(target);
        if (dist > 20 * 20) {
            giveUpTimer++;
        } else {
            giveUpTimer = Math.max(0, giveUpTimer - 2);
        }

        // For centiwings: fly directly toward target
        centipede.setMoveTarget(target.getPos());

        if (centipede instanceof CentiwingEntity cw && cw.isFlying()) {
            // Flying: updateFlyPhysics() handles all movement.
            // Just ensure moveTarget is set (done above).
        } else {
            // On ground: use pathfinding like normal centipede
            if (centipede.needsPathRecalc(target.getPos())) {
                centipede.requestPathTo(target.getPos());
            }
            centipede.followCurrentPath();
        }

        // Look at target
        centipede.getLookControl().lookAt(target, 30.0f, 30.0f);
    }
}
