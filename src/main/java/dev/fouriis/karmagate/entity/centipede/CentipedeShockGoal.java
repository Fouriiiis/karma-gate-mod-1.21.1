package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * AI goal: Manage the shock/grab instakill behavior.
 * This is the highest-priority combat goal. When the centipede has grabbed
 * a target with one head, this goal drives the other head to wrap around
 * and grab the same target, building shock charge for the instakill.
 *
 * Mirrors C# Centipede.UpdateGrasp() and the double-grab → shock → die flow.
 *
 * Flow:
 * 1. One head grabs target via collision (handled in controller's updateGrabs)
 * 2. This goal activates and drives the body to wrap the other head around
 * 3. When both heads are on the target, shock charge builds
 * 4. At full charge → Shock() → instakill
 */
public class CentipedeShockGoal<T extends HostileEntity & CentipedeController> extends Goal {

    private final T centipede;
    private boolean pathRequested = false;

    public CentipedeShockGoal(T centipede) {
        this.centipede = centipede;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!centipede.areSegmentsSpawned()) return false;

        // Activate when at least one head has grabbed something
        CentipedeHeadEntity front = centipede.getFrontHead();
        CentipedeHeadEntity rear = centipede.getRearHead();

        if (front == null || rear == null) return false;
        return front.isGrabbing() || rear.isGrabbing();
    }

    @Override
    public boolean shouldContinue() {
        CentipedeHeadEntity front = centipede.getFrontHead();
        CentipedeHeadEntity rear = centipede.getRearHead();
        if (front == null || rear == null) return false;
        return front.isGrabbing() || rear.isGrabbing();
    }

    @Override
    public void start() {
        pathRequested = false;
    }

    @Override
    public void stop() {
        pathRequested = false;
    }

    @Override
    public void tick() {
        CentipedeHeadEntity front = centipede.getFrontHead();
        CentipedeHeadEntity rear = centipede.getRearHead();
        if (front == null || rear == null) return;

        CentipedeHeadEntity grabbing = front.isGrabbing() ? front : (rear.isGrabbing() ? rear : null);
        CentipedeHeadEntity free = (grabbing == front) ? rear : front;

        if (grabbing == null) return;

        LivingEntity target = grabbing.getGrabbedEntity();
        if (target == null || target.isRemoved() || target.isDead()) {
            grabbing.releaseGrab();
            return;
        }

        // If the free head is not yet grabbing, drive the centipede to wrap it around
        if (!free.isGrabbing()) {
            Vec3d targetPos = target.getPos();

            // Request a path to the target if needed
            if (!pathRequested || centipede.needsPathRecalc(targetPos)) {
                centipede.requestPathTo(targetPos);
                pathRequested = true;
            }

            // Set move target and follow the path
            centipede.setMoveTarget(targetPos);
            centipede.followCurrentPath();

            // Additional direct force on the free head toward the target
            // This supplements the path-following to make wrapping more aggressive
            Vec3d toTarget = targetPos.subtract(free.getPos());
            double dist = toTarget.length();
            if (dist > 0.5) {
                Vec3d dir = toTarget.normalize();
                // Strong direct pull — this is the key wrapping force
                free.segmentVelocity = free.segmentVelocity.add(dir.multiply(0.12));
            }
        }
        // If both heads are grabbing, the shock charge is handled by
        // RedCentipedeEntity.updateShockCharge() — we just keep the goal active
    }
}
