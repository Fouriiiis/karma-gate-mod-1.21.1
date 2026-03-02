package dev.fouriis.karmagate.entity.spider;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;

/**
 * Swarm/flock behavior — ported from C# Spider.Flock and Centipede chain movement.
 *
 * Three modes:
 * 1. **Chain following**: If this spider is in a chain and has a front spider,
 *    follow that spider (C# Centipede.Assist behavior).
 * 2. **Prey swarming**: If the flock has a prey target, swarm toward it together.
 * 3. **Flock cohesion**: Otherwise, gravitate toward the center of nearby spiders.
 *
 * Mirrors C# Flock.Update (merge flocks), Centipede.Update (chain movement),
 * and Spider.Assist (follow chain leader).
 */
public class SpiderSwarmGoal extends Goal {
    private final SpiderEntity spider;
    private Vec3d moveTarget;
    private int ticksSinceUpdate;
    private Mode mode;

    private enum Mode {
        CHAIN_FOLLOW,
        PREY_SWARM,
        FLOCK_COHESION
    }

    public SpiderSwarmGoal(SpiderEntity spider) {
        this.spider = spider;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (spider.isIdle()) return false;

        // Mode 1: Chain following (C# Spider.Assist)
        SpiderEntity chainFront = spider.getChainFront();
        if (chainFront != null && chainFront.isAlive()) {
            // Follow the spider ahead in the chain
            double dist = spider.squaredDistanceTo(chainFront);
            if (dist > 1.5 * 1.5) { // C# connectDistance: follow if not in right place
                moveTarget = chainFront.getPos();
                mode = Mode.CHAIN_FOLLOW;
                return true;
            }
        }

        // Mode 2: Prey swarming (C# Centipede prey pursuit)
        LivingEntity prey = spider.getFlockPrey();
        if (prey != null && prey.isAlive() && spider.getBloodLust() > 0.2f) {
            double distToPrey = spider.squaredDistanceTo(prey);
            if (distToPrey < 30.0 * 30.0 && distToPrey > 3.0 * 3.0) {
                moveTarget = prey.getPos();
                mode = Mode.PREY_SWARM;
                return true;
            }
        }

        // Mode 3: Flock cohesion (C# Flock) — ONLY when prey exists
        // Without prey, spiders should disperse rather than clump
        LivingEntity flockPrey2 = spider.getFlockPrey();
        if (flockPrey2 == null || !flockPrey2.isAlive()) {
            return false; // no cohesion without prey — let wander goal disperse them
        }

        List<SpiderEntity> nearby = spider.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                spider.getBoundingBox().expand(SpiderEntity.ZONE_RADIUS),
                s -> s.isAlive() && s != spider
                        && s.squaredDistanceTo(spider) < SpiderEntity.ZONE_RADIUS * SpiderEntity.ZONE_RADIUS
        );

        if (nearby.size() < 2) return false;

        double cx = 0, cy = 0, cz = 0;
        for (SpiderEntity s : nearby) {
            cx += s.getX();
            cy += s.getY();
            cz += s.getZ();
        }
        cx /= nearby.size();
        cy /= nearby.size();
        cz /= nearby.size();
        moveTarget = new Vec3d(cx, cy, cz);
        mode = Mode.FLOCK_COHESION;

        return spider.squaredDistanceTo(moveTarget) > 4.0;
    }

    @Override
    public boolean shouldContinue() {
        ticksSinceUpdate++;
        if (ticksSinceUpdate > 40) return false;
        if (moveTarget == null) return false;

        switch (mode) {
            case CHAIN_FOLLOW -> {
                SpiderEntity front = spider.getChainFront();
                if (front == null || !front.isAlive()) return false;
                // Update target to follow moving leader
                moveTarget = front.getPos();
                return spider.squaredDistanceTo(moveTarget) > 1.0;
            }
            case PREY_SWARM -> {
                LivingEntity prey = spider.getFlockPrey();
                if (prey == null || !prey.isAlive()) return false;
                moveTarget = prey.getPos();
                return spider.squaredDistanceTo(moveTarget) > 2.0;
            }
            case FLOCK_COHESION -> {
                return spider.squaredDistanceTo(moveTarget) > 2.0;
            }
        }
        return false;
    }

    @Override
    public void start() {
        ticksSinceUpdate = 0;
    }

    @Override
    public void tick() {
        ticksSinceUpdate++;

        // Update target for dynamic modes
        if (ticksSinceUpdate % 3 == 0) {
            if (mode == Mode.CHAIN_FOLLOW) {
                SpiderEntity front = spider.getChainFront();
                if (front != null) moveTarget = front.getPos();
            } else if (mode == Mode.PREY_SWARM) {
                LivingEntity prey = spider.getFlockPrey();
                if (prey != null) moveTarget = prey.getPos();
            }
        }

        // Move using direct velocity
        if (moveTarget != null) {
            double force;
            switch (mode) {
                case CHAIN_FOLLOW -> force = 0.10;
                case PREY_SWARM -> force = 0.08;
                default -> force = 0.03;
            }
            spider.moveToward(moveTarget, force);
        }

        // Chain following: maintain connection distance (C# Spider.Assist physics)
        if (mode == Mode.CHAIN_FOLLOW) {
            SpiderEntity front = spider.getChainFront();
            if (front != null && front.isAlive()) {
                double dist = spider.distanceTo(front);
                float connectDist = spider.connectDistance * 40f; // scale up for MC blocks

                // C# Assist: if in right place, match velocity
                if (dist < connectDist * 2) {
                    Vec3d frontVel = front.getVelocity();
                    Vec3d myVel = spider.getVelocity();
                    // Blend toward leader's velocity (C# vel *= 0.3f + frontVel)
                    spider.setVelocity(myVel.multiply(0.5).add(frontVel.multiply(0.5)));

                    // C# legsPosition for chain: alternating with leader
                    spider.setLegsPosition(front.getLegsPosition() * -0.5f);
                }

                // If too far, pull harder (C# centipedePart.separatedCounter logic)
                if (dist > connectDist * 4) {
                    Vec3d pullDir = front.getPos().subtract(spider.getPos()).normalize();
                    spider.setVelocity(spider.getVelocity().add(pullDir.multiply(0.15)));
                }
            }
        }

        // C# Flock.Update: merge flocks by sharing bloodlust/prey info
        if (ticksSinceUpdate % 10 == 0 && mode == Mode.FLOCK_COHESION) {
            mergeFlockInfo();
        }
    }

    @Override
    public void stop() {
        moveTarget = null;
        ticksSinceUpdate = 0;
        mode = null;
    }

    private void mergeFlockInfo() {
        List<SpiderEntity> nearby = spider.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                spider.getBoundingBox().expand(SpiderEntity.ZONE_RADIUS * 0.5),
                s -> s.isAlive() && s != spider
                        && s.squaredDistanceTo(spider) < 150.0
        );

        for (SpiderEntity other : nearby) {
            // Share bloodlust (higher spreads)
            if (other.getBloodLust() > spider.getBloodLust()) {
                spider.setBloodLust(MathHelper.lerp(0.1f, spider.getBloodLust(), other.getBloodLust()));
            }

            // Share prey target
            LivingEntity otherPrey = other.getFlockPrey();
            if (otherPrey != null && otherPrey.isAlive() && spider.getFlockPrey() == null) {
                spider.setFlockPrey(otherPrey);
            }

            // Share seenNoPreyCounter (lower is better)
            if (other.seenNoPreyCounter < spider.seenNoPreyCounter) {
                spider.seenNoPreyCounter = other.seenNoPreyCounter;
            }

            // Share moveAwayFromPos (C# ConsiderCreature)
            if (spider.moveAwayFromPos == null && other.moveAwayFromPos != null
                    && spider.squaredDistanceTo(other.moveAwayFromPos) < 100.0) {
                spider.moveAwayFromPos = other.moveAwayFromPos;
            }
        }
    }
}
