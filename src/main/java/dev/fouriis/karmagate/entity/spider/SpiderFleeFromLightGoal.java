package dev.fouriis.karmagate.entity.spider;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.EnumSet;

/**
 * Flee from light sources — mirrors C# Spider's light avoidance behavior.
 * Spider moves away from bright areas toward darkness.
 */
public class SpiderFleeFromLightGoal extends Goal {
    private final SpiderEntity spider;
    private final double speed;
    private Vec3d fleeTarget;
    private int cooldown;

    public SpiderFleeFromLightGoal(SpiderEntity spider, double speed) {
        this.spider = spider;
        this.speed = speed;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        // Don't flee from light when actively hunting or attacking prey
        if (spider.getFlockPrey() != null && spider.getFlockPrey().isAlive()
                && spider.getBloodLust() > 0.2f) {
            return false;
        }
        // Only flee if we're in a very lit area (raised threshold)
        return spider.lightExposure > 0.5f;
    }

    @Override
    public boolean shouldContinue() {
        return spider.lightExposure > 0.15f && fleeTarget != null
                && spider.squaredDistanceTo(fleeTarget) > 0.5;
    }

    @Override
    public void start() {
        fleeTarget = findDarkSpot();
    }

    @Override
    public void tick() {
        if (fleeTarget != null) {
            spider.moveToward(fleeTarget, 0.05);
        }
    }

    @Override
    public void stop() {
        fleeTarget = null;
        cooldown = 10 + spider.getRandom().nextInt(20);
    }

    private Vec3d findDarkSpot() {
        BlockPos origin = spider.getBlockPos();
        BlockPos darkest = null;
        int darkestLight = Integer.MAX_VALUE;

        for (int attempt = 0; attempt < 16; attempt++) {
            int dx = spider.getRandom().nextInt(17) - 8;
            int dy = spider.getRandom().nextInt(9) - 4;
            int dz = spider.getRandom().nextInt(17) - 8;
            BlockPos candidate = origin.add(dx, dy, dz);

            if (!SpiderEntity.isValidSpiderPos(spider.getWorld(), candidate)) continue;

            int light = Math.max(
                    spider.getWorld().getLightLevel(LightType.SKY, candidate),
                    spider.getWorld().getLightLevel(LightType.BLOCK, candidate));

            if (light < darkestLight) {
                darkestLight = light;
                darkest = candidate;
            }
        }

        return darkest != null ? Vec3d.ofCenter(darkest) : null;
    }
}
