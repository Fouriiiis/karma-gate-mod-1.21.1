package dev.fouriis.karmagate.entity.spider;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;

/**
 * Melee attack goal for spiders. When close to prey, spiders latch on
 * and deal periodic bite damage.
 *
 * Ported from C# Spider.Attached() + Spider.TryToAttatch():
 *  - Spider grabs onto target body chunk
 *  - Deals gradual damage weighted by number of attached spiders
 *  - Spider clings to prey (reduced movement, legsPosition = 1.0)
 *  - Releases when prey is dead, or when flock mass < prey mass
 *  - Has a random chance to release (C#: Random.value < 0.00083)
 */
public class SpiderMeleeAttackGoal extends Goal {
    private final SpiderEntity spider;
    private LivingEntity target;
    private int attackTick;
    private int attachedTicks;
    private boolean attached;
    private static final double ATTACH_RANGE = 1.5;
    private static final int ATTACK_INTERVAL = 6; // ~0.3 seconds between bites at 20 TPS

    public SpiderMeleeAttackGoal(SpiderEntity spider) {
        this.spider = spider;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        LivingEntity prey = spider.getFlockPrey();
        if (prey == null || !prey.isAlive()) return false;
        if (spider.isDead() || !spider.isAlive()) return false;

        double distSq = spider.squaredDistanceTo(prey);
        if (distSq > ATTACH_RANGE * ATTACH_RANGE * 4) return false;

        // Always allow melee when close enough — let damage scale with mass ratio
        // Don't gate on mass; the damage tick() already handles mass-based scaling
        target = prey;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (target == null || !target.isAlive()) return false;
        if (spider.isDead() || !spider.isAlive()) return false;

        // Random release chance (C#: 0.00083 per tick)
        if (attached && spider.getRandom().nextFloat() < 0.002f) return false;

        // Stay attached/chasing while close
        double distSq = spider.squaredDistanceTo(target);
        if (!attached && distSq > 5.0 * 5.0) return false;
        if (attached && distSq > 3.0 * 3.0) {
            // Got knocked off
            attached = false;
            return false;
        }

        // Release when prey is dead
        if (target.isDead()) {
            // C#: dead prey → 1% chance per tick to release
            if (spider.getRandom().nextFloat() < 0.02f) return false;
        }

        return true;
    }

    @Override
    public void start() {
        attackTick = 0;
        attachedTicks = 0;
        attached = false;
    }

    @Override
    public void tick() {
        if (target == null) return;
        attackTick++;

        spider.getLookControl().lookAt(target, 30f, 30f);

        double dist = spider.distanceTo(target);

        if (!attached) {
            // Move toward target using direct velocity
            spider.moveToward(target.getPos(), 0.06);

            // Try to attach when close enough
            if (dist < ATTACH_RANGE) {
                attached = true;
                attackTick = 0;
                spider.setLegsPosition(1.0f);
                // Stop navigation, we're latched on
                spider.getNavigation().stop();
            }
        } else {
            // Attached behavior (C# Spider.Attached)
            attachedTicks++;

            // Cling to prey - move spider toward target body center
            Vec3d toTarget = target.getPos().subtract(spider.getPos());
            if (toTarget.lengthSquared() > 0.01) {
                // Stay on the surface of the prey
                Vec3d cling = toTarget.normalize().multiply(0.08);
                spider.setVelocity(spider.getVelocity().add(cling));
            }

            // Cancel gravity while attached
            spider.setNoGravity(true);

            // Count nearby spiders also attacking this target
            int attackingCount = countAttackingSpiders();
            float totalMass = getAttackingFlockMass();
            float preyMass = SpiderEntity.getEntityMass(target);

            // Deal damage periodically — always deal damage, scale with mass ratio
            if (attackTick % ATTACK_INTERVAL == 0) {
                float damage;
                if (totalMass > preyMass) {
                    // Flock outmasses prey: full damage
                    damage = 0.5f + (totalMass / Math.max(preyMass, 0.1f)) * 0.3f;
                } else {
                    // Undermassed: weaker probing bites (still deal damage)
                    damage = 0.3f + (totalMass / Math.max(preyMass, 0.1f)) * 0.2f;
                }
                damage = Math.min(damage, 3.0f); // cap
                target.damage(spider.getDamageSources().mobAttack(spider), damage);

                // C#: prey gets slowed when enough spiders attached
                if (attackingCount >= 3 && target.getVelocity().lengthSquared() > 0.01) {
                    target.setVelocity(target.getVelocity().multiply(0.85));
                }
            }

            // C# physic interaction: spider body chunk vel *= 0.3 when attached
            spider.setVelocity(spider.getVelocity().multiply(0.3));

            // Release if chain mass drops well below prey mass (and not enough spiders attacking)
            if (totalMass < preyMass * 0.3f && attackingCount < 3) {
                attached = false;
                spider.setLegsPosition(0f);
                spider.setNoGravity(false);
            }
        }
    }

    @Override
    public void stop() {
        attached = false;
        spider.setLegsPosition(0f);
        spider.setNoGravity(false);
        target = null;
        attackTick = 0;
        attachedTicks = 0;
    }

    private int countAttackingSpiders() {
        if (target == null) return 1;
        List<SpiderEntity> nearby = spider.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                target.getBoundingBox().expand(2.0),
                s -> s.isAlive() && s.getFlockPrey() == target
                        && s.distanceTo(target) < ATTACH_RANGE * 2
        );
        return Math.max(1, nearby.size());
    }

    private float getAttackingFlockMass() {
        // Use chain mass as the primary measure
        return getChainAndNearbyMass();
    }

    /**
     * Get the combined mass of this spider's chain plus nearby unchained spiders.
     */
    private float getChainAndNearbyMass() {
        float chainMass = spider.getChainMass();
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
}
