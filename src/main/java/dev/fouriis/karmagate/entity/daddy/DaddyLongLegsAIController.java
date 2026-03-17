package dev.fouriis.karmagate.entity.daddy;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class DaddyLongLegsAIController {
    public enum State {
        IDLE,
        MOVING,
        STUCK_RECOVER
    }

    private final DaddyVariantConfig config;
    private State state = State.IDLE;
    private Vec3d wanderTarget;
    private int retargetCooldown;
    private int noProgressTicks;
    private double lastDistance;

    public DaddyLongLegsAIController(DaddyVariantConfig config) {
        this.config = config;
        this.retargetCooldown = config.targetIntervalTicks();
        this.lastDistance = Double.MAX_VALUE;
    }

    public void tick(ServerWorld world, DaddyLongLegsEntity entity, boolean hardResetTarget) {
        retargetCooldown--;
        if (hardResetTarget || wanderTarget == null || retargetCooldown <= 0 || !isTargetStillValid(world, wanderTarget, entity)) {
            wanderTarget = pickWanderTarget(world, entity);
            retargetCooldown = config.targetIntervalTicks();
            noProgressTicks = 0;
            lastDistance = Double.MAX_VALUE;
        }

        if (wanderTarget == null) {
            state = State.IDLE;
            return;
        }

        double dist = entity.getPos().distanceTo(wanderTarget);
        state = dist < 2.2 ? State.IDLE : State.MOVING;

        if (state == State.MOVING) {
            double improvement = lastDistance - dist;
            if (improvement < 0.06) {
                noProgressTicks++;
            } else {
                noProgressTicks = Math.max(0, noProgressTicks - 3);
            }
            if (noProgressTicks > config.stuckProgressWindowTicks()) {
                state = State.STUCK_RECOVER;
                if (noProgressTicks > config.stuckProgressWindowTicks() + config.stuckRecoveryTicks()) {
                    wanderTarget = pickWanderTarget(world, entity);
                    noProgressTicks = 0;
                    retargetCooldown = config.targetIntervalTicks() / 2;
                }
            }
        } else {
            noProgressTicks = Math.max(0, noProgressTicks - 2);
        }

        lastDistance = dist;
    }

    public State getState() {
        return state;
    }

    public Vec3d getWanderTarget() {
        return wanderTarget;
    }

    public float getSearchBiasBoost() {
        return state == State.STUCK_RECOVER ? 1.75f : 0.0f;
    }

    private boolean isTargetStillValid(ServerWorld world, Vec3d target, DaddyLongLegsEntity entity) {
        if (world.getFluidState(BlockPos.ofFloored(target)).isStill()) {
            return false;
        }
        return hasBodySpace(world, target, entity);
    }

    private Vec3d pickWanderTarget(ServerWorld world, DaddyLongLegsEntity entity) {
        Vec3d origin = entity.getPos();
        Vec3d best = null;
        double bestScore = -1.0e9;

        for (int i = 0; i < config.targetSearchAttempts(); i++) {
            int dx = world.random.nextBetween(-config.horizontalTargetRadius(), config.horizontalTargetRadius());
            int dz = world.random.nextBetween(-config.horizontalTargetRadius(), config.horizontalTargetRadius());
            int dy = world.random.nextBetween(-config.verticalTargetRadius(), config.verticalTargetRadius());

            Vec3d candidate = origin.add(dx, dy, dz);
            candidate = snapToNearbyAir(world, candidate);
            if (candidate == null) {
                continue;
            }
            if (!hasBodySpace(world, candidate, entity)) {
                continue;
            }
            if (!world.getFluidState(BlockPos.ofFloored(candidate)).isEmpty()) {
                continue;
            }

            int anchors = TentacleAnchorFinder.countAnchorableFaces(world, candidate, 2);
            if (anchors < 6) {
                continue;
            }

            int surfaceContacts = countSolidNeighbors(world, candidate);
            double openness = countAirNeighbors(world, candidate);
            double dist = origin.distanceTo(candidate);

            double score = 0;
            score += MathHelper.clamp(anchors / 20.0, 0.0, 2.8);
            score += MathHelper.clamp(surfaceContacts / 6.0, 0.0, 1.6);
            score -= Math.abs(openness - 3.5) * 0.35;
            score -= dist * 0.03;
            score += world.random.nextFloat() * 0.35;

            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return best;
    }

    private Vec3d snapToNearbyAir(ServerWorld world, Vec3d pos) {
        BlockPos base = BlockPos.ofFloored(pos);
        for (int y = 0; y <= 4; y++) {
            BlockPos up = base.up(y);
            if (world.isAir(up) && world.isAir(up.up())) {
                return Vec3d.ofBottomCenter(up);
            }
            BlockPos down = base.down(y);
            if (world.isAir(down) && world.isAir(down.up())) {
                return Vec3d.ofBottomCenter(down);
            }
        }
        return null;
    }

    private boolean hasBodySpace(ServerWorld world, Vec3d pos, DaddyLongLegsEntity entity) {
        float r = entity.getVariantConfig().bodyRadius();
        Box box = new Box(pos.x - r, pos.y, pos.z - r, pos.x + r, pos.y + entity.getHeight(), pos.z + r);
        return world.isSpaceEmpty(entity, box);
    }

    private int countSolidNeighbors(ServerWorld world, Vec3d pos) {
        BlockPos b = BlockPos.ofFloored(pos);
        int c = 0;
        for (BlockPos n : new BlockPos[]{b.north(), b.south(), b.east(), b.west(), b.up(), b.down()}) {
            if (world.getBlockState(n).isOpaqueFullCube(world, n)) {
                c++;
            }
        }
        return c;
    }

    private int countAirNeighbors(ServerWorld world, Vec3d pos) {
        BlockPos b = BlockPos.ofFloored(pos);
        int c = 0;
        for (BlockPos n : new BlockPos[]{b.north(), b.south(), b.east(), b.west(), b.up(), b.down()}) {
            if (world.isAir(n)) {
                c++;
            }
        }
        return c;
    }
}
