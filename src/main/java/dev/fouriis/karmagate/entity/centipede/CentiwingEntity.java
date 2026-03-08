package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Random;

/**
 * Centiwing = winged centipede.
 *
 * Rain World parity goals:
 * - Prefer flying over crawling.
 * - Only settle into crawling when very clearly operating on nearby terrain.
 * - When crawling, strongly avoid open-air route tiles.
 * - When flying, prefer open air away from terrain.
 */
public class CentiwingEntity extends CentipedeEntity {
    private static final float PX = 0.025f;

    private static final TrackedData<Boolean> IS_FLYING = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> WINGS_STARTED_UP = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> WING_FLAP_CYCLE = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.FLOAT);

    protected boolean flying = true;
    protected boolean wantToFly = true;
    protected int flyModeCounter = 100;
    protected float wingsStartedUp = 1f;
    protected float wingFlapCycle = 0f;
    protected float lastWingFlapCycle = 0f;
    protected float wingsFolded = 0f;
    protected float lastWingsFolded = 0f;

    protected float[] wingLengths;

    public CentiwingEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        recalcSizeDerivedFields();
    }

    @Override
    protected void computeSizeFromSeed() {
        long seed = this.getUuid().getLeastSignificantBits();
        Random rng = new Random(seed);
        this.size = MathHelper.lerp(rng.nextFloat(), 0.5f, 0.65f);
        recalcSizeDerivedFields();
    }

    @Override
    protected void recalcSizeDerivedFields() {
        this.totalSegments = (int) MathHelper.lerp(size, 7f, 17f);
        if (this.totalSegments < 3) this.totalSegments = 3;
        this.bodySegmentCount = this.totalSegments - 2;

        this.segmentIds = new int[totalSegments];
        this.segments = new CentipedeSegmentEntity[totalSegments];
        java.util.Arrays.fill(segmentIds, -1);

        this.wingLengths = new float[totalSegments];
        this.maxRadius = computeSegmentRadius(Math.max(0, totalSegments / 2));

        for (int j = 0; j < totalSegments; j++) {
            float num = totalSegments > 1 ? (float) j / (float) (totalSegments - 1) : 0.5f;

            float num2 = (float) Math.sin(Math.pow(MathHelper.clamp((0.5f - num) / 0.5f, 0f, 1f), 0.75f) * Math.PI);
            num2 *= 1f - num;

            float num3 = (float) Math.sin(Math.pow(MathHelper.clamp((1f - num) / 0.5f, 0f, 1f), 0.75f) * Math.PI);
            num3 *= num;

            num2 = 0.5f + 0.5f * num2;
            num3 = 0.5f + 0.5f * num3;

            float maxWingLen = MathHelper.lerp(MathHelper.clamp((size - 0.5f) / 0.5f, 0f, 1f), 60f, 80f);
            wingLengths[j] = MathHelper.lerp(
                    Math.max(num2, num3) - (float) Math.sin(num * Math.PI) * 0.25f,
                    3f, maxWingLen);
        }
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 30.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.28)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 40.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0)
                .add(EntityAttributes.GENERIC_ARMOR, 2.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.2);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(IS_FLYING, true);
        builder.add(WINGS_STARTED_UP, 1f);
        builder.add(WING_FLAP_CYCLE, 0f);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new CentipedeShockGoal<>(this));
        this.goalSelector.add(2, new CentiwingHuntGoal<>(this));
        this.goalSelector.add(3, new CentiwingWanderGoal<>(this));
        this.goalSelector.add(4, new LookAroundGoal(this));

        this.targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false,
                entity -> {
                    if (entity instanceof PlayerEntity player) {
                        return !player.isCreative() && !player.isSpectator();
                    }
                    return true;
                }));

        this.targetSelector.add(2, new ActiveTargetGoal<>(this, LivingEntity.class, 10, true, false,
                entity -> !(entity instanceof CentipedeController)
                        && !(entity instanceof CentipedeSegmentEntity)
                        && !(entity instanceof PlayerEntity p && (p.isCreative() || p.isSpectator()))
                        && entity.getType().getSpawnGroup().isPeaceful()));
    }

    @Override
    public boolean hasWings() {
        return true;
    }

    @Override
    public boolean isFlying() {
        return flying;
    }

    @Override
    public float getWingsStartedUp() {
        return wingsStartedUp;
    }

    @Override
    public float getWingFlapCycle() {
        return wingFlapCycle;
    }

    @Override
    public float getLastWingFlapCycle() {
        return lastWingFlapCycle;
    }

    @Override
    public float getWingsFolded() {
        return wingsFolded;
    }

    @Override
    public float getLastWingsFolded() {
        return lastWingsFolded;
    }

    @Override
    public float getWingLength(int segIndex) {
        if (wingLengths == null || segIndex < 0 || segIndex >= wingLengths.length) return 0f;
        return wingLengths[segIndex];
    }

    public boolean isWantToFly() {
        return wantToFly;
    }

    public void setWantToFly(boolean wantToFly) {
        this.wantToFly = wantToFly;
    }

    public int getFlyModeCounter() {
        return flyModeCounter;
    }

    /**
     * C# RatherClimbThanFly:
     * prefer crawling only when very near terrain.
     */
    public boolean ratherClimbThanFly(BlockPos pos) {
        return CentipedePathfinder.getTerrainProximity(this.getWorld(), pos) < 2;
    }

    /**
     * Stronger flight bias:
     * only crawl if both current position and target are terrain-adjacent
     * and the target is reasonably close.
     */
    private boolean shouldPreferCrawling(BlockPos current, BlockPos target, double targetDist) {
        boolean currentNearSurface = ratherClimbThanFly(current);
        boolean targetNearSurface = ratherClimbThanFly(target);

        if (!currentNearSurface || !targetNearSurface) {
            return false;
        }

        if (huntTarget != null && !huntTarget.isRemoved()) {
            BlockPos preyPos = huntTarget.getBlockPos();

            // If prey is not clearly terrain-bound, keep flying.
            if (!ratherClimbThanFly(preyPos)) {
                return false;
            }

            // Even for terrain-bound prey, only settle into crawling when close.
            return targetDist < 8.0;
        }

        // Non-hunt movement: only settle if clearly operating on nearby surface.
        return targetDist < 7.0;
    }

    private BlockPos adjustCrawlGoal(BlockPos goal) {
        if (goal == null) return null;

        if (ratherClimbThanFly(goal)) {
            return goal;
        }

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int r = 1; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;

                        BlockPos p = goal.add(dx, dy, dz);
                        if (!CentipedePathfinder.isAccessible(this.getWorld(), p)) continue;
                        if (!ratherClimbThanFly(p)) continue;

                        double d = p.getSquaredDistance(goal);
                        if (d < bestDist) {
                            bestDist = d;
                            best = p.toImmutable();
                        }
                    }
                }
            }
        }

        return best != null ? best : findNearestSurfaceAdjacent(goal, 8);
    }

    private BlockPos findNearestSurfaceAdjacent(BlockPos center, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;

                        BlockPos p = center.add(dx, dy, dz);
                        if (!CentipedePathfinder.isAccessible(this.getWorld(), p)) continue;
                        if (!ratherClimbThanFly(p)) continue;

                        double d = p.getSquaredDistance(center);
                        if (d < bestDist) {
                            bestDist = d;
                            best = p.toImmutable();
                        }
                    }
                }
            }
        }

        return best != null ? best : center;
    }

    private double csharpFlyingTerrainPenalty(BlockPos pos) {
        int prox = CentipedePathfinder.getTerrainProximity(this.getWorld(), pos);
        if (prox < 2) {
            return 0.0;
        }

        double t = MathHelper.clamp((prox - 1.0) / 5.0, 0.0, 1.0);
        return MathHelper.lerp((float) t, 500.0f, 0.0f);
    }

    private CentipedePathfinder.PathCost centiwingTravelPreference(
            BlockPos startPos,
            BlockPos endPos,
            CentipedePathfinder.MovementConnection conn,
            CentipedePathfinder.PathCost baseCost
    ) {
        if (!baseCost.considerable()) {
            return baseCost;
        }

        if (!this.flying) {
            if (!ratherClimbThanFly(endPos)) {
                return new CentipedePathfinder.PathCost(
                        baseCost.resistance + 1000.0,
                        baseCost.legality
                );
            }
            return baseCost;
        }

        return new CentipedePathfinder.PathCost(
                baseCost.resistance + csharpFlyingTerrainPenalty(endPos),
                baseCost.legality
        );
    }

    private BlockPos getPathSearchOrigin() {
        CentipedeHeadEntity head = getLeadingHead();
        if (head != null && !head.isRemoved()) {
            return head.getBlockPos();
        }
        return this.getBlockPos();
    }

    private void rebuildCrawlSearch(BlockPos goal) {
        if (goal == null) return;

        BlockPos crawlGoal = adjustCrawlGoal(goal);
        BlockPos start = getPathSearchOrigin();

        this.currentSearch = CentipedePathfinder.beginSearch(
                this.getWorld(),
                start,
                crawlGoal,
                CentipedePathfinder.DEFAULT_MAX_RANGE,
                this::centiwingTravelPreference
        );
    }

    @Override
    public void tick() {
        this.noClip = true;
        this.setNoGravity(true);
        super.tick();
    }

    @Override
    protected void tickServer() {
        if (!segmentsSpawned) {
            computeSizeFromSeed();
            spawnSegments();
            moveTarget = this.getPos();
            return;
        }

        resolveSegments();
        updateFlyState();

        if (flying) {
            currentSearch = null;
            updateFlyPhysics();
        } else {
            updatePathfinding();
            updateChainPhysics();
        }

        updateSegmentRotations();
        updateGrabs();
        updateShockCharge();
        syncTrackedData();
    }

    @Override
    protected void readTrackedDataFromClient() {
        super.readTrackedDataFromClient();

        flying = this.dataTracker.get(IS_FLYING);
        wingsStartedUp = this.dataTracker.get(WINGS_STARTED_UP);

        lastWingFlapCycle = wingFlapCycle;
        wingFlapCycle = this.dataTracker.get(WING_FLAP_CYCLE);

        lastWingsFolded = wingsFolded;
        wingsFolded = 1f - wingsStartedUp;
    }

    @Override
    protected void syncTrackedData() {
        super.syncTrackedData();
        this.dataTracker.set(IS_FLYING, flying);
        this.dataTracker.set(WINGS_STARTED_UP, wingsStartedUp);
        this.dataTracker.set(WING_FLAP_CYCLE, wingFlapCycle);
    }

    /**
     * Strongly flight-biased state update.
     *
     * Compared with the previous version:
     * - defaults toward flying
     * - only settles into crawling in a narrow close-to-surface case
     * - while hunting, continues to prefer flight unless prey is clearly on terrain
     */
    private void updateFlyState() {
        CentipedeHeadEntity head = getLeadingHead();
        if (head != null && !head.isRemoved()) {
            BlockPos currentBlock = head.getBlockPos();
            BlockPos targetBlock = BlockPos.ofFloored(moveTarget);
            double targetDist = head.getPos().distanceTo(moveTarget);

            // Default bias: fly.
            wantToFly = true;

            // Only switch toward crawling in a very narrow near-surface case.
            if (shouldPreferCrawling(currentBlock, targetBlock, targetDist)) {
                wantToFly = false;
            }

            // Keep airborne when pursuing anything not clearly terrain-bound.
            if (huntTarget != null && !huntTarget.isRemoved()) {
                BlockPos preyPos = huntTarget.getBlockPos();
                if (!ratherClimbThanFly(preyPos)) {
                    wantToFly = true;
                }
            }

            // If already airborne and the goal isn't strongly surface-bound, stay airborne.
            if (flying && !ratherClimbThanFly(targetBlock)) {
                wantToFly = true;
            }

            // If airborne and target is not extremely close, favor remaining in flight.
            if (flying && targetDist > 4.0) {
                wantToFly = true;
            }
        } else {
            wantToFly = true;
        }

        // Faster startup into flying, slower drop into crawling.
        if (wantToFly) {
            flyModeCounter = Math.min(100, flyModeCounter + 2);
            if (flyModeCounter >= 85) {
                flying = true;
            }
        } else {
            flyModeCounter = Math.max(0, flyModeCounter - 1);
            if (flyModeCounter <= 35) {
                flying = false;
            }
        }

        float target = MathHelper.clamp((flyModeCounter - 55f) / 45f, 0f, 1f);
        if (wingsStartedUp < target) {
            wingsStartedUp = Math.min(1f, wingsStartedUp + 0.035f);
        } else {
            wingsStartedUp = Math.max(0f, wingsStartedUp - 0.02f);
        }
        wingsStartedUp = MathHelper.lerp(0.08f, wingsStartedUp, target);

        lastWingFlapCycle = wingFlapCycle;
        wingFlapCycle += (float) Math.pow(wingsStartedUp, 3f);

        lastWingsFolded = wingsFolded;
        wingsFolded = 1f - wingsStartedUp;
    }

    private Vec3d computeAirAvoidance(Vec3d pos, Vec3d desiredDir) {
        World world = this.getWorld();
        Vec3d avoidance = Vec3d.ZERO;

        double probeDist = 1.25;
        double sideProbe = 0.9;

        Vec3d forward = desiredDir.lengthSquared() > 1.0E-4 ? desiredDir.normalize() : new Vec3d(0, 0, 1);
        Vec3d right = forward.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1.0E-4) {
            right = new Vec3d(1, 0, 0);
        } else {
            right = right.normalize();
        }
        Vec3d up = right.crossProduct(forward).normalize();

        Vec3d[] probes = new Vec3d[] {
                forward.multiply(probeDist),
                forward.multiply(probeDist).add(right.multiply(sideProbe)),
                forward.multiply(probeDist).subtract(right.multiply(sideProbe)),
                forward.multiply(probeDist).add(up.multiply(sideProbe)),
                forward.multiply(probeDist).subtract(up.multiply(sideProbe))
        };

        for (Vec3d probe : probes) {
            BlockPos bp = BlockPos.ofFloored(pos.add(probe));
            BlockState state = world.getBlockState(bp);
            if (state.isSolidBlock(world, bp)) {
                Vec3d away = pos.subtract(Vec3d.ofCenter(bp));
                if (away.lengthSquared() > 1.0E-4) {
                    avoidance = avoidance.add(away.normalize());
                }
            }
        }

        return avoidance;
    }

    private Vec3d computeAirSwimTarget(CentipedeHeadEntity head) {
        Vec3d headPos = head.getPos();
        Vec3d toTarget = moveTarget.subtract(headPos);

        Vec3d desiredDir = toTarget.lengthSquared() > 1.0E-4 ? toTarget.normalize() : new Vec3d(0, 0, 1);
        Vec3d avoidance = computeAirAvoidance(headPos, desiredDir).multiply(1.45);

        Vec3d openAirBias = Vec3d.ZERO;
        double biasStrength = 0.0;

        for (Direction dir : Direction.values()) {
            BlockPos adj = head.getBlockPos().offset(dir);
            if (this.getWorld().getBlockState(adj).isSolidBlock(this.getWorld(), adj)) {
                openAirBias = openAirBias.add(-dir.getOffsetX(), -dir.getOffsetY(), -dir.getOffsetZ());
                biasStrength += 1.0;
            }
        }

        if (openAirBias.lengthSquared() > 1.0E-4) {
            openAirBias = openAirBias.normalize().multiply(1.1 + 0.45 * biasStrength);
        }

        Vec3d steer = desiredDir
                .multiply(0.9)
                .add(avoidance)
                .add(openAirBias);

        if (steer.lengthSquared() < 1.0E-4) {
            steer = desiredDir;
        }
        return steer.normalize();
    }

    protected void updateFlyPhysics() {
        bodyWave += 1f;
        moving = true;

        if (huntTarget != null && !huntTarget.isRemoved()) {
            moveTarget = huntTarget.getPos().add(0, huntTarget.getHeight() * 0.5, 0);
        }

        for (int i = 0; i < totalSegments; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;

            float num = totalSegments > 1 ? (float) i / (float) (totalSegments - 1) : 0f;
            if (!bodyDirection) num = 1f - num;

            float wavePhase = (bodyWave - num * MathHelper.lerp(size, 12f, 28f)) * (float) Math.PI * 0.11f;
            float waveVal = (float) Math.sin(wavePhase);
            float waveVal2 = (float) Math.cos(wavePhase);

            segments[i].segmentVelocity = segments[i].segmentVelocity.multiply(0.9);
            segments[i].segmentVelocity = segments[i].segmentVelocity.add(0, 0.06 * (wingsStartedUp - 1.0), 0);

            if (i > 0 && i < totalSegments - 1) {
                int ahead = bodyDirection ? (i - 1) : (i + 1);
                if (ahead >= 0 && ahead < totalSegments && segments[ahead] != null && !segments[ahead].isRemoved()) {
                    Vec3d toAhead = segments[ahead].getPos().subtract(segments[i].getPos());
                    double len = toAhead.length();
                    if (len > 0.001) {
                        Vec3d dir = toAhead.normalize();

                        segments[i].segmentVelocity = segments[i].segmentVelocity.add(
                                dir.multiply(0.045 * MathHelper.lerp(size, 0.5f, 1.5f)));

                        Vec3d perp1 = dir.crossProduct(new Vec3d(0, 1, 0));
                        if (perp1.lengthSquared() < 0.01) {
                            perp1 = dir.crossProduct(new Vec3d(1, 0, 0));
                        }
                        perp1 = perp1.normalize();

                        Vec3d perp2 = perp1.crossProduct(dir);
                        if (perp2.lengthSquared() > 0.001) {
                            perp2 = perp2.normalize();
                        } else {
                            perp2 = new Vec3d(0, 1, 0);
                        }

                        double waveStrength = 0.065;
                        Vec3d waveDisplacement = perp1.multiply(waveVal * waveStrength)
                                .add(perp2.multiply(waveVal2 * waveStrength * 0.5));

                        segments[i].setPosition(segments[i].getPos().add(waveDisplacement));
                    }
                }
            }
        }

        CentipedeHeadEntity head = getLeadingHead();
        if (head != null && !head.isRemoved()) {
            Vec3d headPos = head.getPos();

            double wobbleAngle = bodyWave * 10.0 * Math.PI / 180.0;
            double wobbleRadius = 60.0 * PX;
            Vec3d wobble = new Vec3d(
                    Math.cos(wobbleAngle) * wobbleRadius,
                    Math.sin(wobbleAngle * 0.7) * wobbleRadius * 0.3,
                    Math.sin(wobbleAngle) * wobbleRadius
            );

            Vec3d adjustedTarget = moveTarget.add(wobble);
            Vec3d desired = adjustedTarget.subtract(headPos);
            if (desired.lengthSquared() > 1.0E-4) {
                desired = desired.normalize();
            } else {
                desired = new Vec3d(0, 0, 1);
            }

            Vec3d steerDir = computeAirSwimTarget(head).multiply(0.72).add(desired.multiply(0.28));
            if (steerDir.lengthSquared() > 1.0E-4) {
                steerDir = steerDir.normalize();
            } else {
                steerDir = desired;
            }

            double speed = 0.115 * MathHelper.lerp(size, 0.7f, 1.3f);
            head.segmentVelocity = head.segmentVelocity.add(steerDir.multiply(speed));

            this.setPosition(head.getPos());
        }

        float stiffnessForce = (float) (MathHelper.lerp(shockCharge, 1.0, 6.0) * MathHelper.lerp(size, 1.0, 2.0));
        float stiffnessMC = stiffnessForce * 0.015f;
        for (int i = 0; i < totalSegments - 2; i++) {
            if (segments[i] == null || segments[i + 2] == null) continue;
            if (segments[i].isRemoved() || segments[i + 2].isRemoved()) continue;

            Vec3d diff = segments[i].getPos().subtract(segments[i + 2].getPos());
            double dist = diff.length();
            if (dist > 0.01) {
                Vec3d push = diff.normalize().multiply(stiffnessMC);
                segments[i].segmentVelocity = segments[i].segmentVelocity.add(push);
                segments[i + 2].segmentVelocity = segments[i + 2].segmentVelocity.subtract(push);
            }
        }

        for (int i = 0; i < totalSegments; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;

            Vec3d vel = segments[i].segmentVelocity;

            segments[i].surfaceNormalX *= 0.9f;
            segments[i].surfaceNormalY *= 0.9f;
            segments[i].surfaceNormalZ *= 0.9f;

            Vec3d oldPos = segments[i].getPos();
            segments[i].noClip = false;
            segments[i].move(MovementType.SELF, vel);
            segments[i].noClip = true;
            segments[i].segmentVelocity = segments[i].getPos().subtract(oldPos);
        }

        for (int iter = 0; iter < 3; iter++) {
            for (int i = 0; i < totalSegments - 1; i++) {
                enforceSpacing(i, i + 1);
            }
            for (int i = totalSegments - 2; i >= 0; i--) {
                enforceSpacing(i, i + 1);
            }
        }
    }

    @Override
    public void stopMoving() {
        this.moving = false;
        this.currentSearch = null;
    }

    @Override
    public void requestPath(BlockPos goal) {
        if (goal == null) return;

        lastPathGoal = goal;
        pathRecalcTimer = PATH_RECALC_INTERVAL;

        Vec3d targetCenter = Vec3d.ofCenter(goal);

        // Strong flight bias: direct air motion is the default.
        if (flying || !shouldPreferCrawling(getPathSearchOrigin(), goal, this.getPos().distanceTo(targetCenter))) {
            this.moveTarget = targetCenter;
            this.moving = true;
            this.currentSearch = null;
            return;
        }

        BlockPos crawlGoal = adjustCrawlGoal(goal);
        this.moveTarget = Vec3d.ofCenter(crawlGoal);
        this.moving = true;
        rebuildCrawlSearch(crawlGoal);
    }

    @Override
    public void requestPathTo(Vec3d target) {
        BlockPos rawGoal = BlockPos.ofFloored(target);

        if (flying || !shouldPreferCrawling(getPathSearchOrigin(), rawGoal, this.getPos().distanceTo(target))) {
            this.moveTarget = target;
            this.moving = true;
            this.currentSearch = null;
            this.lastPathGoal = rawGoal;
            this.pathRecalcTimer = PATH_RECALC_INTERVAL;
            return;
        }

        BlockPos crawlGoal = adjustCrawlGoal(rawGoal);
        this.lastPathGoal = crawlGoal;
        this.pathRecalcTimer = PATH_RECALC_INTERVAL;
        this.moveTarget = Vec3d.ofCenter(crawlGoal);
        this.moving = true;
        rebuildCrawlSearch(crawlGoal);
    }

    @Override
    protected void updatePathfinding() {
        if (flying) {
            if (pathRecalcTimer > 0) {
                pathRecalcTimer--;
            }
            return;
        }

        if (!moving) {
            return;
        }

        BlockPos desiredGoal = lastPathGoal != null ? lastPathGoal : BlockPos.ofFloored(moveTarget);
        BlockPos crawlGoal = adjustCrawlGoal(desiredGoal);

        if (currentSearch == null
                || !CentipedePathfinder.isSearchStillUseful(currentSearch, this.getPos(), Vec3d.ofCenter(crawlGoal))
                || needsPathRecalc(Vec3d.ofCenter(crawlGoal))) {
            rebuildCrawlSearch(crawlGoal);
            pathRecalcTimer = PATH_RECALC_INTERVAL;
        } else if (pathRecalcTimer > 0) {
            pathRecalcTimer--;
        }

        if (currentSearch != null) {
            currentSearch.update(
                    CentipedePathfinder.DEFAULT_ACCESSIBILITY_STEPS,
                    CentipedePathfinder.DEFAULT_PATH_STEPS
            );

            if (currentSearch.isAccessibilityFinished()) {
                BlockPos next = CentipedePathfinder.followPathfieldLookAhead(
                        currentSearch,
                        this.getPos(),
                        CentipedePathfinder.DEFAULT_FOLLOW_LOOK_RADIUS
                );
                if (next != null) {
                    this.moveTarget = Vec3d.ofCenter(next);
                }
            }
        }
    }

    @Override
    public boolean needsPathRecalc(Vec3d goalPos) {
        if (flying) {
            if (pathRecalcTimer > 0) return false;
            if (lastPathGoal == null) return true;

            BlockPos newGoal = BlockPos.ofFloored(goalPos);
            return newGoal.getManhattanDistance(lastPathGoal) > 3;
        }

        if (pathRecalcTimer > 0) return false;
        if (lastPathGoal == null) return true;

        BlockPos adjusted = adjustCrawlGoal(BlockPos.ofFloored(goalPos));
        return adjusted.getManhattanDistance(lastPathGoal) > 2;
    }

    @Override
    public void followCurrentPath() {
        if (flying) {
            return;
        }
        super.followCurrentPath();
    }

    @Override
    public void driveTowardTarget() {
        if (flying) return;
        super.driveTowardTarget();
    }

    @Override
    public void updateDirectionChange() {
        if (directionChangeBlock > 0) {
            if (moving) directionChangeBlock--;
            return;
        }

        if (huntTarget == null) return;

        CentipedeHeadEntity front = getFrontHead();
        CentipedeHeadEntity rear = getRearHead();
        if (front == null || rear == null) return;

        if (flying) {
            double frontDist = front.getPos().squaredDistanceTo(huntTarget.getPos());
            double rearDist = rear.getPos().squaredDistanceTo(huntTarget.getPos());

            if (rearDist + 0.5 < frontDist) {
                changeDirCounter++;
                if (changeDirCounter > 10) {
                    bodyDirection = !bodyDirection;
                    directionChangeBlock = 30;
                    changeDirCounter = 0;
                }
            } else {
                changeDirCounter = 0;
            }
            return;
        }

        super.updateDirectionChange();
    }

    @Override
    public boolean isInsideWall() {
        return false;
    }

    @Override
    public boolean isFireImmune() {
        return true;
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.FALL)) {
            return false;
        }
        return super.damage(source, amount);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("Flying", flying);
        nbt.putFloat("WingsStartedUp", wingsStartedUp);
        nbt.putInt("FlyModeCounter", flyModeCounter);
        nbt.putBoolean("WantToFly", wantToFly);
        nbt.putFloat("WingFlapCycle", wingFlapCycle);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("Flying")) flying = nbt.getBoolean("Flying");
        if (nbt.contains("WingsStartedUp")) wingsStartedUp = nbt.getFloat("WingsStartedUp");
        if (nbt.contains("FlyModeCounter")) flyModeCounter = nbt.getInt("FlyModeCounter");
        if (nbt.contains("WantToFly")) wantToFly = nbt.getBoolean("WantToFly");
        if (nbt.contains("WingFlapCycle")) wingFlapCycle = nbt.getFloat("WingFlapCycle");
    }

    @Override
    public int getShellColorRGB() {
        return (106 << 16) | (191 << 8) | 64;
    }

    @Override
    public int getSecondaryShellColorRGB() {
        return (64 << 16) | (115 << 8) | 38;
    }

    @Override
    public float getLegScale() {
        return 0.65f;
    }

    @Override
    public float computeSegmentRadius(int segIndex) {
        float segRatio = (totalSegments > 1) ? (float) segIndex / (float) (totalSegments - 1) : 0.5f;
        float sinVal = (float) Math.max(0, Math.sin(Math.PI * segRatio));
        float powExp = MathHelper.lerp(size, 0.7f, 0.3f);
        float powVal = (float) Math.pow(sinVal, powExp);
        float minR = MathHelper.lerp(size, 2f, 3.5f);
        float maxR = MathHelper.lerp(size, 4f, 6.5f);
        float standardRadius = MathHelper.lerp(powVal, minR, maxR);
        return MathHelper.lerp(0.4f, standardRadius, minR);
    }
}