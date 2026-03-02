package dev.fouriis.karmagate.entity.spider;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.EnumSet;
import java.util.List;

/**
 * Wander goal — ported from C# Spider.Crawl() with idle and active modes.
 *
 * Idle mode (C# Spider.idle):
 *  - Spider sits still in complete darkness
 *  - Only moves to stay in zero-light tiles
 *  - Very slow, short-range movements
 *  - Occasionally shifts to a nearby dark tile (1/12 chance per tick)
 *
 * Active mode (C# Spider.Crawl active path scoring):
 *  - Creates random paths and scores them using TileScore
 *  - Prefers dark areas, avoids light
 *  - When in light: scores higher for darker destinations
 *  - When in dark: prefers narrow spaces, areas near flock, shortcuts
 *  - Moves away from moveAwayFromPos if set
 *  - Moves toward prey position if hunting
 */
public class SpiderWanderGoal extends Goal {
    private final SpiderEntity spider;
    private final double speed;
    private Vec3d target;
    private int wanderTicks;

    public SpiderWanderGoal(SpiderEntity spider, double speed) {
        this.spider = spider;
        this.speed = speed;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        // Always try to wander when not busy
        // C# idle mode: very low chance of moving
        if (spider.idle) {
            // Only move if light appeared (flee) or very low random chance
            if (spider.lightExposure > 0f) {
                return spider.getRandom().nextFloat() < 0.4f;
            }
            return spider.getRandom().nextFloat() < 0.1f;
        }

        // Active mode: wander more frequently when no prey (to disperse)
        boolean hasPrey = spider.getFlockPrey() != null && spider.getFlockPrey().isAlive();
        float chance = hasPrey ? 0.4f : 0.8f;
        return spider.getRandom().nextFloat() < chance;
    }

    @Override
    public boolean shouldContinue() {
        wanderTicks++;
        int maxTicks = spider.idle ? 15 : 40; // longer wander when active
        if (wanderTicks > maxTicks) return false;
        if (target == null) return false;
        return spider.squaredDistanceTo(target) > 0.5;
    }

    @Override
    public void start() {
        wanderTicks = 0;
        if (spider.idle) {
            target = findIdleTarget();
        } else {
            target = findActiveTarget();
        }
    }

    @Override
    public void tick() {
        if (target == null) return;
        // Stronger movement force when no prey (dispersing)
        boolean hasPrey = spider.getFlockPrey() != null && spider.getFlockPrey().isAlive();
        double moveForce;
        if (spider.idle) {
            moveForce = 0.01;
        } else if (!hasPrey) {
            moveForce = 0.04; // stronger push to disperse
        } else {
            moveForce = 0.03;
        }
        spider.moveToward(target, moveForce);
    }

    @Override
    public void stop() {
        target = null;
        wanderTicks = 0;
    }

    /**
     * C# idle wandering: stay in pitch-black areas, tiny movements only.
     * Only pick tiles with zero light exposure.
     */
    private Vec3d findIdleTarget() {
        BlockPos origin = spider.getBlockPos();
        BlockPos best = null;
        int bestLight = Integer.MAX_VALUE;

        for (int attempt = 0; attempt < 6; attempt++) {
            int dx = spider.getRandom().nextInt(5) - 2;
            int dy = spider.getRandom().nextInt(5) - 2;
            int dz = spider.getRandom().nextInt(5) - 2;
            BlockPos candidate = origin.add(dx, dy, dz);

            if (!SpiderEntity.isValidSpiderPos(spider.getWorld(), candidate)) continue;

            int light = Math.max(
                    spider.getWorld().getLightLevel(LightType.SKY, candidate),
                    spider.getWorld().getLightLevel(LightType.BLOCK, candidate));

            // C#: only move to zero-light tiles when idle
            if (light == 0 && light < bestLight) {
                bestLight = light;
                best = candidate;
            }
        }

        return best != null ? Vec3d.ofCenter(best) : null;
    }

    /**
     * C# Spider.Crawl active mode with TileScore-based path scoring.
     * Creates random path candidates and picks the best-scoring one.
     */
    private Vec3d findActiveTarget() {
        BlockPos origin = spider.getBlockPos();

        // Generate two random paths, pick the higher-scoring one (C#: scratchPath vs path)
        Vec3d path1 = generateScoredPath(origin);
        Vec3d path2 = generateScoredPath(origin);

        if (path1 == null) return path2;
        if (path2 == null) return path1;

        float score1 = tileScore(BlockPos.ofFloored(path1));
        float score2 = tileScore(BlockPos.ofFloored(path2));
        return score1 > score2 ? path1 : path2;
    }

    /**
     * Generate a random path destination and return the end point.
     */
    private Vec3d generateScoredPath(BlockPos origin) {
        BlockPos best = null;
        float bestScore = Float.NEGATIVE_INFINITY;

        // Longer paths when dispersing (no prey)
        boolean hasPrey = spider.getFlockPrey() != null && spider.getFlockPrey().isAlive();
        int pathLen = hasPrey ? (3 + spider.getRandom().nextInt(5)) : (5 + spider.getRandom().nextInt(6));
        BlockPos current = origin;

        for (int step = 0; step < pathLen; step++) {
            BlockPos nextBest = null;
            float nextBestScore = Float.NEGATIVE_INFINITY;

            // Wider search radius when dispersing
            int range = hasPrey ? 2 : 4;
            for (int attempt = 0; attempt < 4; attempt++) {
                int dx = spider.getRandom().nextInt(range * 2 + 1) - range;
                int dy = spider.getRandom().nextInt(7) - 3;
                int dz = spider.getRandom().nextInt(range * 2 + 1) - range;
                BlockPos candidate = current.add(dx, dy, dz);

                if (!SpiderEntity.isValidSpiderPos(spider.getWorld(), candidate)) continue;

                float score = tileScore(candidate);
                if (score > nextBestScore) {
                    nextBestScore = score;
                    nextBest = candidate;
                }
            }

            if (nextBest != null) {
                current = nextBest;
                if (nextBestScore > bestScore) {
                    bestScore = nextBestScore;
                    best = nextBest;
                }
            }
        }

        return best != null ? Vec3d.ofCenter(best) : null;
    }

    /**
     * C# Spider.TileScore: score a tile based on various factors.
     *
     * In light: primarily score by darkness of destination
     * In dark: score by narrow spaces, flock proximity, hunt direction
     */
    private float tileScore(BlockPos tile) {
        float score = 0f;

        int light = Math.max(
                spider.getWorld().getLightLevel(LightType.SKY, tile),
                spider.getWorld().getLightLevel(LightType.BLOCK, tile));

        // === C# moveAwayFromPos score ===
        if (spider.moveAwayFromPos != null) {
            double distFromThreat = Vec3d.ofCenter(tile).distanceTo(spider.moveAwayFromPos);
            score += (float)(distFromThreat * 2.0); // higher = better (further from threat)
        }

        // === Light-dependent scoring ===
        if (spider.lightExposure < 0.01f && light == 0) {
            // C#: In total darkness, score by exploration and flock proximity

            // C# terrainProximity: count distance to nearest solid. Lower = closer to walls.
            // Spiders slightly prefer being near 1-2 surfaces but PENALIZE tight corners.
            int solidNeighbors = 0;
            for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
                if (spider.getWorld().getBlockState(tile.offset(dir)).isSolidBlock(
                        spider.getWorld(), tile.offset(dir))) {
                    solidNeighbors++;
                }
            }
            // C#: narrowSpace penalty (-0.01) and terrainProximity penalty
            // Prefer tiles near 1-2 surfaces (walls/floor), penalize corners (3+ surfaces)
            if (solidNeighbors == 0) {
                score -= 1f;  // open air: slightly bad (no surface to crawl on)
            } else if (solidNeighbors <= 2) {
                score += 0.5f; // ideal: near a surface or wall
            } else {
                score -= solidNeighbors * 0.5f; // corners: penalized, more surfaces = worse
            }

            // Slight random factor (C#: Random.value * 200)
            score += spider.getRandom().nextFloat() * 20f;

            // Flock proximity: attract toward flock when hunting, disperse when idle
            List<SpiderEntity> nearby = spider.getWorld().getEntitiesByClass(
                    SpiderEntity.class,
                    spider.getBoundingBox().expand(SpiderEntity.ZONE_RADIUS),
                    s -> s.isAlive() && s != spider
            );
            if (!nearby.isEmpty()) {
                double cx = 0, cz = 0;
                for (SpiderEntity s : nearby) {
                    cx += s.getX();
                    cz += s.getZ();
                }
                cx /= nearby.size();
                cz /= nearby.size();
                double distToFlock = Vec3d.ofCenter(tile).distanceTo(new Vec3d(cx, tile.getY(), cz));

                LivingEntity prey = spider.getFlockPrey();
                if (prey != null && prey.isAlive() && spider.getBloodLust() > 0.1f) {
                    // Hunting: prefer tiles closer to flock center (swarming)
                    score -= (float)(distToFlock * spider.getBloodLust() * 3f);
                } else {
                    // No prey: prefer tiles AWAY from other spiders (disperse)
                    score += (float)(distToFlock * 2f);
                }
            }
        } else {
            // C#: In light, strongly prefer darker destinations
            score += (15 - light) * 15f;

            // Hunt direction bonus (C# Centipede prey scoring)
            LivingEntity prey = spider.getFlockPrey();
            if (prey != null && prey.isAlive() && spider.huntIntensity > 0f) {
                double distToPrey = Vec3d.ofCenter(tile).distanceTo(prey.getPos());
                score -= (float)(distToPrey * spider.huntIntensity * 2f);
            }
        }

        // Small random factor
        score += spider.getRandom().nextFloat() * 8f;

        return score;
    }
}
