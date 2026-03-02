package dev.fouriis.karmagate.entity.spider;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;

/**
 * Flee from creatures too large to hunt.
 *
 * Ported from C# Spider.ConsiderCreature's moveAwayFromPos logic:
 * When a spider detects a creature that is too big to be prey and is close
 * by (within 30 + creature.TotalMass * 8 units), the spider sets a
 * moveAwayFromPos and flees in the opposite direction.
 *
 * Also triggers when another spider in the flock has a moveAwayFromPos
 * that is near this spider (C# information sharing).
 */
public class SpiderFleeFromEntityGoal extends Goal {
    private final SpiderEntity spider;
    private Vec3d fleeTarget;
    private LivingEntity threatEntity;
    private int fleeTicks;
    private int cooldown;
    private static final int MAX_FLEE_TICKS = 30; // 1.5 seconds at 20 TPS
    private static final double FLEE_DISTANCE = 10.0;

    public SpiderFleeFromEntityGoal(SpiderEntity spider) {
        this.spider = spider;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (spider.isDead() || !spider.isAlive()) return false;

        // Don't flee if we're actively hunting — let hunt/attack goals handle it
        if (spider.getFlockPrey() != null && spider.getFlockPrey().isAlive()
                && spider.getBloodLust() > 0.3f) {
            spider.moveAwayFromPos = null; // clear stale flee positions
            return false;
        }

        // Check if a flock member already set moveAwayFromPos near us
        Vec3d flockFlee = spider.moveAwayFromPos;
        if (flockFlee != null && spider.squaredDistanceTo(flockFlee) < 15.0 * 15.0) {
            // Flee away from that position
            fleeTarget = computeFleeTarget(flockFlee);
            return fleeTarget != null;
        }

        // Scan for threatening entities (too big to eat)
        threatEntity = findThreat();
        if (threatEntity != null) {
            spider.moveAwayFromPos = threatEntity.getPos();
            // Share with nearby spiders (C# ConsiderCreature sharing)
            shareFleeWithFlock(threatEntity.getPos());
            fleeTarget = computeFleeTarget(threatEntity.getPos());
            return fleeTarget != null;
        }

        return false;
    }

    @Override
    public boolean shouldContinue() {
        fleeTicks++;
        if (fleeTicks > MAX_FLEE_TICKS) return false;
        // Keep fleeing as long as threat is nearby
        if (threatEntity != null && threatEntity.isAlive() && spider.distanceTo(threatEntity) < 16.0) return true;
        if (spider.moveAwayFromPos != null && spider.squaredDistanceTo(spider.moveAwayFromPos) < FLEE_DISTANCE * FLEE_DISTANCE) return true;
        return false;
    }

    @Override
    public void start() {
        fleeTicks = 0;
    }

    @Override
    public void tick() {
        fleeTicks++;
        // Flee using direct velocity - much more reliable than navigation
        Vec3d fleeFrom = null;
        if (threatEntity != null && threatEntity.isAlive()) {
            fleeFrom = threatEntity.getPos();
        } else if (spider.moveAwayFromPos != null) {
            fleeFrom = spider.moveAwayFromPos;
        }
        if (fleeFrom != null) {
            spider.moveAwayFrom(fleeFrom, 0.06);
        }
    }

    @Override
    public void stop() {
        threatEntity = null;
        fleeTicks = 0;
        cooldown = 3 + spider.getRandom().nextInt(5);
    }

    /**
     * Find a nearby entity that is too large for the current chain to overpower.
     * Compares entity mass vs chain mass — flee if entity is bigger.
     */
    private LivingEntity findThreat() {
        double scanRange = 16.0;
        List<LivingEntity> entities = spider.getWorld().getEntitiesByClass(
                LivingEntity.class,
                spider.getBoundingBox().expand(scanRange),
                e -> e.isAlive() && !(e instanceof SpiderEntity)
                        && SpiderEntity.isValidTarget(e)
        );

        float chainMass = spider.getChainMass();
        // Count nearby unchained spiders (wide radius to include whole flock)
        List<SpiderEntity> nearbySpiders = spider.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                spider.getBoundingBox().expand(16.0),
                s -> s.isAlive() && s != spider && !s.inChain
        );
        float totalNearbyMass = chainMass;
        for (SpiderEntity s : nearbySpiders) {
            totalNearbyMass += net.minecraft.util.math.MathHelper.lerp(s.getSizeFactor(), 0.08f, 0.25f);
        }

        LivingEntity closestThreat = null;
        double closestDist = Double.MAX_VALUE;

        for (LivingEntity entity : entities) {
            float mass = SpiderEntity.getEntityMass(entity);

            // If the entity's mass MASSIVELY exceeds our flock mass → it's a threat
            // Only flee from truly massive creatures (iron golem tier), not players
            if (mass > totalNearbyMass * 5.0f) {
                double dist = spider.distanceTo(entity);
                double dangerRadius = 6.0 + mass * 1.5;
                dangerRadius = Math.min(dangerRadius, 16.0);

                if (dist < dangerRadius && dist < closestDist) {
                    closestDist = dist;
                    closestThreat = entity;
                }
            }
        }

        return closestThreat;
    }

    /**
     * Compute a point to flee to, away from the threat position.
     */
    private Vec3d computeFleeTarget(Vec3d threatPos) {
        Vec3d away = spider.getPos().subtract(threatPos);
        if (away.lengthSquared() < 0.01) {
            double angle = spider.getRandom().nextDouble() * Math.PI * 2.0;
            away = new Vec3d(Math.cos(angle), 0, Math.sin(angle));
        }
        away = away.normalize();

        Vec3d candidate = spider.getPos().add(away.multiply(FLEE_DISTANCE));
        net.minecraft.util.math.BlockPos groundPos = net.minecraft.util.math.BlockPos.ofFloored(candidate);
        // Search nearby for any valid spider position (floor, wall, or ceiling)
        for (int dy = -4; dy <= 4; dy++) {
            net.minecraft.util.math.BlockPos check = groundPos.add(0, dy, 0);
            if (SpiderEntity.isValidSpiderPos(spider.getWorld(), check)) {
                return Vec3d.ofCenter(check);
            }
        }
        return candidate;
    }

    /**
     * Share flee direction with nearby spiders.
     * C# ConsiderCreature: if another spider has moveAwayFromPos near this spider, adopt it.
     */
    private void shareFleeWithFlock(Vec3d threatPos) {
        List<SpiderEntity> nearby = spider.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                spider.getBoundingBox().expand(6.0),
                s -> s.isAlive() && s != spider
                        && s.squaredDistanceTo(spider) < 36.0
        );
        for (SpiderEntity s : nearby) {
            if (s.moveAwayFromPos == null) {
                s.moveAwayFromPos = threatPos;
            }
        }
    }
}
