package dev.fouriis.karmagate.entity.spider;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;

/**
 * Ported from C# Spider.ConsiderCreature() + Centipede prey targeting.
 *
 * When a spider flock's combined bloodLust is high enough and they are in
 * darkness, they collectively pick a prey target. Individual spiders then
 * chase the prey. The flock leader (lowest entity-ID spider) picks the
 * target and nearby spiders follow suit.
 *
 * Prey criteria (from C# ConsiderPrey):
 *  - Target mass must be less than combined flock mass (~3.3× single spider)
 *  - Target must not be another spider
 *  - Target must be visible (line of sight in darkness)
 */
public class SpiderHuntPreyGoal extends Goal {
    private final SpiderEntity spider;
    private LivingEntity target;
    private int cooldown;
    private int chaseTime;
    private boolean stalking;
    private static final int MAX_CHASE_TIME = 300; // 15 seconds at 20 TPS — longer chase allowed
    private static final double STALK_DISTANCE = 5.0;

    public SpiderHuntPreyGoal(SpiderEntity spider) {
        this.spider = spider;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        // Very low threshold so spiders engage quickly
        if (spider.getBloodLust() < 0.01f) return false;
        if (!spider.isAlive() || spider.isDead()) return false;

        // If we already have a flock-shared target, check chain mass vs target
        LivingEntity flockTarget = spider.getFlockPrey();
        if (flockTarget != null && flockTarget.isAlive()
                && spider.squaredDistanceTo(flockTarget) < 30.0 * 30.0) {
            float chainMass = getChainAndNearbyMass();
            float targetMass = SpiderEntity.getEntityMass(flockTarget);
            if (chainMass > targetMass) {
                // Outmass target → full hunt
                target = flockTarget;
                stalking = false;
                return true;
            } else if (chainMass > targetMass * 0.1f) {
                // Not enough mass yet but not hopeless → stalk and recruit
                target = flockTarget;
                stalking = true;
                return true;
            } else {
                // Severely outmassed (iron golem tier) → flee but DON'T clear prey
                spider.moveAwayFromPos = flockTarget.getPos();
                cooldown = 10;
                return false;
            }
        }

        // Otherwise, try to find a prey creature ourselves
        target = findPrey();
        if (target != null) {
            // Determine if hunting or stalking based on mass
            float chainMass = getChainAndNearbyMass();
            float targetMass = SpiderEntity.getEntityMass(target);
            stalking = chainMass <= targetMass;
            // Share with flock
            spider.setFlockPrey(target);
            sharePreyWithFlock(target);
            return true;
        }

        // Increment seenNoPreyCounter (C# logic)
        spider.seenNoPreyCounter++;
        cooldown = 2 + spider.getRandom().nextInt(3);
        return false;
    }

    @Override
    public boolean shouldContinue() {
        if (target == null || !target.isAlive()) return false;
        if (spider.isDead() || !spider.isAlive()) return false;
        if (spider.squaredDistanceTo(target) > 35.0 * 35.0) return false;
        if (chaseTime > MAX_CHASE_TIME) return false;
        // Only abandon if in bright light AND far from prey (don't abandon mid-attack)
        if (spider.lightExposure > 0.8f && spider.squaredDistanceTo(target) > 10.0 * 10.0) return false;
        // Stop hunting if chain mass drops below target mass
        if (chaseTime % 5 == 0) {
            float chainMass = getChainAndNearbyMass();
            float targetMass = SpiderEntity.getEntityMass(target);
            if (chainMass > targetMass) {
                stalking = false; // enough mass, transition to full hunt
            } else if (chainMass > targetMass * 0.1f) {
                stalking = true; // keep stalking
            } else {
                // Severely outmassed (iron golem tier) → abandon
                spider.moveAwayFromPos = target.getPos();
                return false;
            }
        }
        return true;
    }

    @Override
    public void start() {
        chaseTime = 0;
        spider.seenNoPreyCounter = 0;
    }

    @Override
    public void tick() {
        chaseTime++;
        if (target == null || !target.isAlive()) return;

        spider.getLookControl().lookAt(target, 30f, 30f);

        double distSq = spider.squaredDistanceTo(target);

        if (stalking) {
            // Stalk mode: approach prey aggressively, recruit flock
            // Always close in — no dead zone between distances
            spider.moveToward(target.getPos(), 0.07);

            // When very close, attack even in stalking mode (probing bites)
            if (distSq < 1.8 * 1.8) {
                if (chaseTime % 8 == 0) {
                    target.damage(spider.getDamageSources().mobAttack(spider), 0.5f);
                    Vec3d toTarget = target.getPos().subtract(spider.getPos()).normalize();
                    spider.setVelocity(toTarget.multiply(0.12));
                    spider.setLegsPosition(1.0f);
                }
            }

            // Recruit nearby spiders frequently while stalking
            if (chaseTime % 3 == 0) {
                sharePreyWithFlock(target);
            }
            // Boost bloodlust rapidly while stalking prey
            spider.setBloodLust(Math.min(1f, spider.getBloodLust() + 0.03f));
        } else {
            // Full hunt: close in and attack
            spider.moveToward(target.getPos(), 0.09);

            // Attack if close enough (C# Attached behavior)
            if (distSq < 1.8 * 1.8) {
                if (chaseTime % 4 == 0) {
                    float damage = 1.0f;
                    float flockMass = getChainAndNearbyMass();
                    float preyMass = SpiderEntity.getEntityMass(target);
                    if (flockMass > preyMass) {
                        damage = 1.5f; // bonus when flock outmasses
                    }
                    target.damage(spider.getDamageSources().mobAttack(spider), damage);
                    Vec3d toTarget = target.getPos().subtract(spider.getPos()).normalize();
                    spider.setVelocity(toTarget.multiply(0.15));
                    spider.setLegsPosition(1.0f);
                }
            } else {
                spider.setLegsPosition(0f);
            }

            // Share prey info with nearby spiders periodically
            if (chaseTime % 10 == 0) {
                sharePreyWithFlock(target);
            }
        }
    }

    @Override
    public void stop() {
        target = null;
        // DON'T clear flockPrey — the flock should keep tracking the target
        // It gets cleared naturally when target dies or leaves range
        spider.setLegsPosition(0f);
        stalking = false;
        cooldown = 1 + spider.getRandom().nextInt(2);
        chaseTime = 0;
    }

    /**
     * Find a valid prey creature within range.
     * Only considers targets the chain+nearby spiders can overpower.
     */
    private LivingEntity findPrey() {
        double searchRange = 20.0;
        List<LivingEntity> candidates = spider.getWorld().getEntitiesByClass(
                LivingEntity.class,
                spider.getBoundingBox().expand(searchRange),
                e -> e.isAlive() && !(e instanceof SpiderEntity)
                        && SpiderEntity.isValidTarget(e) && canSeePrey(e)
        );

        if (candidates.isEmpty()) return null;

        float chainMass = getChainAndNearbyMass();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (LivingEntity candidate : candidates) {
            float mass = SpiderEntity.getEntityMass(candidate);
            // Skip only if MASSIVELY outmassed (iron golem tier); allow stalking otherwise
            if (chainMass <= mass * 0.1f) continue;

            double dist = spider.squaredDistanceTo(candidate);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }

        return best;
    }

    /**
     * Check if spider can see the prey (line of sight + visual radius).
     * Mirrors C# Spider.VisualContact.
     */
    private boolean canSeePrey(LivingEntity entity) {
        double distSq = spider.squaredDistanceTo(entity);
        // C# Template.visualRadius - roughly 20 blocks
        if (distSq > 20.0 * 20.0) return false;
        // Simple raycast check
        return spider.canSee(entity);
    }

    /**
     * Get the combined mass of this spider's chain plus nearby unchained spiders.
     * This determines whether the group can take on a target.
     */
    private float getChainAndNearbyMass() {
        float chainMass = spider.getChainMass();
        // Also count nearby unchained spiders who could join (wide radius to count whole flock)
        List<SpiderEntity> nearby = spider.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                spider.getBoundingBox().expand(16.0),
                s -> s.isAlive() && s != spider && !s.inChain
        );
        for (SpiderEntity s : nearby) {
            chainMass += MathHelper.lerp(s.getSizeFactor(), 0.08f, 0.25f);
        }
        return chainMass;
    }

    /**
     * Share prey target with all nearby spiders in the flock.
     * Mirrors C# Centipede.SeePrey and Flock prey sharing.
     */
    private void sharePreyWithFlock(LivingEntity prey) {
        List<SpiderEntity> nearby = spider.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                spider.getBoundingBox().expand(SpiderEntity.ZONE_RADIUS),
                s -> s.isAlive() && s != spider
                        && s.squaredDistanceTo(spider) < SpiderEntity.ZONE_RADIUS * SpiderEntity.ZONE_RADIUS
        );
        for (SpiderEntity s : nearby) {
            if (s.getFlockPrey() == null || !s.getFlockPrey().isAlive()) {
                s.setFlockPrey(prey);
            }
            // C# Spider.ConsiderCreature: bloodLust communication
            if (s.getBloodLust() < spider.getBloodLust()) {
                s.setBloodLust(spider.getBloodLust());
            }
            // C# seenNoPreyCounter sharing
            if (s.seenNoPreyCounter > spider.seenNoPreyCounter) {
                s.seenNoPreyCounter = spider.seenNoPreyCounter;
            }
        }
    }
}
