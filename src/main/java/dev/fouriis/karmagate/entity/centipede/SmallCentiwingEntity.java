package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * Controller entity for a Small Centiwing.
 * Identical behavior to SmallCentipede (no wings, no flying, size=0, 5 segments)
 * but with Centiwing green-yellow coloring.
 * Only hunts chickens.
 */
public class SmallCentiwingEntity extends HostileEntity implements GeoAnimatable, CentipedeController {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    private static final double BODY_RENDER_DEPTH = 23.0 / 16.0 * 0.5;
    private static final double HEAD_RENDER_DEPTH = 16.0 / 16.0 * 0.5;

    // --- Tracked data ---
    private static final TrackedData<Boolean> BODY_DIRECTION = DataTracker.registerData(
            SmallCentiwingEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> SHOCK_CHARGE = DataTracker.registerData(
            SmallCentiwingEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> IS_MOVING = DataTracker.registerData(
            SmallCentiwingEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> BODY_WAVE = DataTracker.registerData(
            SmallCentiwingEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // C# SmallCentipede: size = 0, always 5 segments
    private static final float SIZE = 0f;
    private static final int TOTAL_SEGMENTS = 5;
    private static final int BODY_SEGMENT_COUNT = 3;

    private float maxRadius;

    // --- Segment references ---
    private int[] segmentIds = new int[TOTAL_SEGMENTS];
    private CentipedeSegmentEntity[] segments = new CentipedeSegmentEntity[TOTAL_SEGMENTS];
    private boolean segmentsSpawned = false;

    // --- Movement / AI state ---
    private boolean bodyDirection = false;
    private Vec3d moveTarget = Vec3d.ZERO;
    private float bodyWave = 0f;
    private boolean moving = false;
    private int directionChangeBlock = 0;
    private int changeDirCounter = 0;

    // --- Shock / grab state ---
    private float shockCharge = 0f;
    private int shockGiveUpCounter = 0;
    private float doubleGrabCharge = 0f;

    // --- AI behavior ---
    private LivingEntity huntTarget = null;

    // --- Pathfinding ---
    private CentipedePathfinder.IncrementalSearch currentSearch = null;
    private List<BlockPos> currentPath = null;
    private int pathRecalcTimer = 0;
    private BlockPos lastPathGoal = null;
    private static final int PATH_STEPS_PER_TICK = 80;
    private static final int PATH_RECALC_INTERVAL = 30;
    private static final int PATH_LOOK_AHEAD = 3;
    private static final double WAYPOINT_REACH_DIST = 1.5;

    // --- Small centiwing shell colors (Centiwing green-yellow) ---
    // C# HSL(0.33, 0.5, 0.5) ≈ RGB(106, 191, 64)
    private static final int SHELL_COLOR = (106 << 16) | (191 << 8) | 64;
    // HSL(0.33, 0.5, 0.3) ≈ RGB(64, 115, 38)
    private static final int SECONDARY_SHELL_COLOR = (64 << 16) | (115 << 8) | 38;

    public SmallCentiwingEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        java.util.Arrays.fill(segmentIds, -1);
        // Same sizing reference as SmallCentipedeEntity — halved vs. red centipede.
        this.maxRadius = 8.0f;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 8.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
                .add(EntityAttributes.GENERIC_ARMOR, 0.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(BODY_DIRECTION, false);
        builder.add(SHOCK_CHARGE, 0f);
        builder.add(IS_MOVING, false);
        builder.add(BODY_WAVE, 0f);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new CentipedeShockGoal<>(this));
        this.goalSelector.add(2, new CentipedeHuntGoal<>(this));
        this.goalSelector.add(3, new CentipedeWanderGoal<>(this));
        this.goalSelector.add(4, new LookAroundGoal(this));

        // SmallCentiwings only hunt chickens
        this.targetSelector.add(1, new ActiveTargetGoal<>(this, ChickenEntity.class, 10, true, false,
                entity -> !entity.isRemoved() && entity.isAlive()));
    }

    // =========================================================================
    // Segment management
    // =========================================================================

    private void spawnSegments() {
        if (this.getWorld().isClient || segmentsSpawned) return;
        ServerWorld sw = (ServerWorld) this.getWorld();

        Vec3d basePos = this.getPos();
        float yawRad = this.getYaw() * ((float) Math.PI / 180f);
        Vec3d forward = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));

        double cumulativeOffset = 0;
        for (int i = 0; i < TOTAL_SEGMENTS; i++) {
            if (i > 0) {
                cumulativeOffset += getSegmentSpacing(i - 1, i);
            }
            Vec3d segPos = basePos.subtract(forward.multiply(cumulativeOffset));
            CentipedeSegmentEntity seg;

            if (i == 0 || i == TOTAL_SEGMENTS - 1) {
                EntityType<?> headType = dev.fouriis.karmagate.KarmaGateMod.CENTIPEDE_HEAD_ENTITY_TYPE;
                CentipedeHeadEntity head = (CentipedeHeadEntity) headType.create(sw);
                if (head == null) continue;
                head.setFrontHead(i == 0);
                seg = head;
            } else {
                EntityType<?> bodyType = dev.fouriis.karmagate.KarmaGateMod.CENTIPEDE_BODY_ENTITY_TYPE;
                CentipedeBodyEntity body = (CentipedeBodyEntity) bodyType.create(sw);
                if (body == null) continue;
                seg = body;
            }

            seg.setPosition(segPos);
            seg.setParentId(this.getId());
            seg.setSegmentIndex(i);
            seg.setHasShell(false);
            sw.spawnEntity(seg);

            segmentIds[i] = seg.getId();
            segments[i] = seg;
        }

        segmentsSpawned = true;
    }

    private void resolveSegments() {
        for (int i = 0; i < TOTAL_SEGMENTS; i++) {
            if (segments[i] == null || segments[i].isRemoved()) {
                if (segmentIds[i] != -1) {
                    Entity e = this.getWorld().getEntityById(segmentIds[i]);
                    if (e instanceof CentipedeSegmentEntity seg) {
                        segments[i] = seg;
                    }
                }
            }
        }
    }

    @Override
    public CentipedeHeadEntity getFrontHead() {
        int idx = bodyDirection ? (TOTAL_SEGMENTS - 1) : 0;
        if (segments != null && idx < segments.length && segments[idx] instanceof CentipedeHeadEntity h) return h;
        return null;
    }

    @Override
    public CentipedeHeadEntity getRearHead() {
        int idx = bodyDirection ? 0 : (TOTAL_SEGMENTS - 1);
        if (segments != null && idx < segments.length && segments[idx] instanceof CentipedeHeadEntity h) return h;
        return null;
    }

    public CentipedeHeadEntity getLeadingHead() {
        return getFrontHead();
    }

    @Override
    public CentipedeSegmentEntity[] getSegments() {
        return segments;
    }

    @Override
    public float getWalkCycle() {
        return bodyWave;
    }

    @Override
    public boolean isBodyDirection() {
        return bodyDirection;
    }

    @Override
    public void registerClientSegment(CentipedeSegmentEntity seg) {
        int idx = seg.getSegmentIndex();
        if (idx >= 0 && idx < TOTAL_SEGMENTS && segments != null) {
            segments[idx] = seg;
            segmentIds[idx] = seg.getId();
        }
    }

    @Override
    public boolean areSegmentsSpawned() {
        return segmentsSpawned;
    }

    @Override
    public float computeSegmentRadius(int segIndex) {
        float segRatio = (float) segIndex / (float) (TOTAL_SEGMENTS - 1);
        float sinVal = (float) Math.max(0, Math.sin(Math.PI * segRatio));
        float powVal = (float) Math.pow(sinVal, 0.7f);
        return MathHelper.lerp(powVal, 2f, 4f);
    }

    @Override
    public float getMaxRadius() {
        return maxRadius;
    }

    @Override
    public float getHeadScaleFactor() {
        return 0.5f;
    }

    private double getRenderedDepth(int segIndex) {
        if (segIndex == 0 || segIndex == TOTAL_SEGMENTS - 1) {
            return HEAD_RENDER_DEPTH;
        }
        float scaleFactor = computeSegmentRadius(segIndex) / maxRadius;
        return BODY_RENDER_DEPTH * scaleFactor;
    }

    @Override
    public double getSegmentSpacing(int a, int b) {
        return (getRenderedDepth(a) + getRenderedDepth(b)) / 2.0;
    }

    // =========================================================================
    // Tick / Physics / Movement
    // =========================================================================

    @Override
    public void tick() {
        this.noClip = true;
        super.tick();

        if (!this.getWorld().isClient) {
            if (!segmentsSpawned) {
                spawnSegments();
                return;
            }

            resolveSegments();
            updatePathfinding();
            updateChainPhysics();
            updateSegmentRotations();
            updateGrabs();
            updateShockCharge();
            syncTrackedData();
        } else {
            bodyDirection = this.dataTracker.get(BODY_DIRECTION);
            shockCharge = this.dataTracker.get(SHOCK_CHARGE);
            moving = this.dataTracker.get(IS_MOVING);
            bodyWave = this.dataTracker.get(BODY_WAVE);
        }
    }

    @Override
    public boolean isInsideWall() {
        return false;
    }

    @Override
    public boolean isFireImmune() {
        return false;
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

    private void syncTrackedData() {
        this.dataTracker.set(BODY_DIRECTION, bodyDirection);
        this.dataTracker.set(SHOCK_CHARGE, shockCharge);
        this.dataTracker.set(IS_MOVING, moving);
        this.dataTracker.set(BODY_WAVE, bodyWave);
    }

    private void updateChainPhysics() {
        CentipedeHeadEntity lead = getLeadingHead();
        if (lead != null && !lead.isRemoved()) {
            this.setPosition(lead.getPos());
        }

        if (moving) {
            bodyWave += (bodyDirection ? -1f : 1f) * 0.1f;
        }

        if (moving) {
            for (int i = 1; i < TOTAL_SEGMENTS - 1; i++) {
                if (segments[i] == null || segments[i].isRemoved()) continue;
                if (!isNearSurface(segments[i])) continue;

                int ahead = bodyDirection ? (i + 1) : (i - 1);
                int behind = bodyDirection ? (i - 1) : (i + 1);

                if (ahead >= 0 && ahead < TOTAL_SEGMENTS && segments[ahead] != null && !segments[ahead].isRemoved()) {
                    if (isNearSurface(segments[ahead])) {
                        Vec3d toAhead = segments[ahead].getPos().subtract(segments[i].getPos()).normalize();
                        segments[i].segmentVelocity = segments[i].segmentVelocity.add(toAhead.multiply(0.068 * 0.5));
                    }
                }

                if (behind >= 0 && behind < TOTAL_SEGMENTS && segments[behind] != null && !segments[behind].isRemoved()) {
                    Vec3d toBehind = segments[behind].getPos().subtract(segments[i].getPos()).normalize();
                    segments[i].segmentVelocity = segments[i].segmentVelocity.subtract(toBehind.multiply(0.04));
                }
            }
        }

        float stiffnessForce = (float)(MathHelper.lerp(shockCharge, 1.0, 6.0) * 1.0);
        float stiffnessMC = stiffnessForce * 0.015f;
        for (int i = 0; i < TOTAL_SEGMENTS - 2; i++) {
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

        for (int i = 0; i < TOTAL_SEGMENTS; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;

            Vec3d vel = segments[i].segmentVelocity;
            Vec3d surfaceNormal = computeSurfaceNormal(segments[i]);
            boolean onSurface = surfaceNormal.lengthSquared() > 0.01;

            if (onSurface) {
                vel = vel.multiply(0.7);
                vel = vel.subtract(surfaceNormal.multiply(0.06));
                segments[i].surfaceNormalX = (float) surfaceNormal.x;
                segments[i].surfaceNormalY = (float) surfaceNormal.y;
                segments[i].surfaceNormalZ = (float) surfaceNormal.z;
            } else {
                vel = vel.add(0, -0.06, 0);
                vel = vel.multiply(0.92);
                segments[i].surfaceNormalX *= 0.9f;
                segments[i].surfaceNormalY *= 0.9f;
                segments[i].surfaceNormalZ *= 0.9f;
            }

            Vec3d oldPos = segments[i].getPos();
            segments[i].noClip = false;
            segments[i].move(MovementType.SELF, vel);
            segments[i].noClip = true;
            segments[i].segmentVelocity = segments[i].getPos().subtract(oldPos);
        }

        for (int iter = 0; iter < 3; iter++) {
            for (int i = 0; i < TOTAL_SEGMENTS - 1; i++) {
                enforceSpacing(i, i + 1);
            }
            for (int i = TOTAL_SEGMENTS - 2; i >= 0; i--) {
                enforceSpacing(i, i + 1);
            }
        }
    }

    private void updateSegmentRotations() {
        for (int i = 0; i < TOTAL_SEGMENTS; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;

            Vec3d dir = getChainDirection(i);
            float yaw = (float) (Math.atan2(-dir.x, dir.z) * (180.0 / Math.PI));
            if (i == TOTAL_SEGMENTS - 1) {
                yaw += 180f;
            }
            float pitch = (float) (Math.asin(MathHelper.clamp(dir.y, -1, 1)) * (180.0 / Math.PI));

            segments[i].prevBodyYaw = segments[i].bodyYaw;
            segments[i].bodyYaw = yaw;
            segments[i].prevYaw = segments[i].getYaw();
            segments[i].setYaw(yaw);
            segments[i].setPitch(pitch);
        }
    }

    private void enforceSpacing(int a, int b) {
        if (segments[a] == null || segments[b] == null) return;
        if (segments[a].isRemoved() || segments[b].isRemoved()) return;

        Vec3d posA = segments[a].getPos();
        Vec3d posB = segments[b].getPos();
        Vec3d diff = posB.subtract(posA);
        double dist = diff.length();

        if (dist < 0.001) return;

        double spacing = getSegmentSpacing(a, b);
        double error = dist - spacing;
        Vec3d correction = diff.normalize().multiply(error * 0.5);

        moveWithCollision(segments[a], correction);
        moveWithCollision(segments[b], correction.negate());

        Vec3d velCorrection = correction.multiply(0.5);
        segments[a].segmentVelocity = segments[a].segmentVelocity.add(velCorrection);
        segments[b].segmentVelocity = segments[b].segmentVelocity.subtract(velCorrection);
    }

    private void moveWithCollision(CentipedeSegmentEntity seg, Vec3d movement) {
        seg.noClip = false;
        seg.move(MovementType.SELF, movement);
        seg.noClip = true;
    }

    private Vec3d getChainDirection(int index) {
        if (index <= 0 && segments.length > 1 && segments[1] != null) {
            return segments[1].getPos().subtract(segments[0].getPos()).normalize();
        }
        if (index >= TOTAL_SEGMENTS - 1 && segments[TOTAL_SEGMENTS - 2] != null) {
            return segments[TOTAL_SEGMENTS - 1].getPos().subtract(segments[TOTAL_SEGMENTS - 2].getPos()).normalize();
        }
        if (index > 0 && index < TOTAL_SEGMENTS - 1 && segments[index - 1] != null && segments[index + 1] != null) {
            return segments[index + 1].getPos().subtract(segments[index - 1].getPos()).normalize();
        }
        return new Vec3d(1, 0, 0);
    }

    private boolean isNearSurface(Entity entity) {
        BlockPos pos = entity.getBlockPos();
        World world = entity.getWorld();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (world.getBlockState(neighbor).isSolidBlock(world, neighbor)) {
                return true;
            }
        }
        BlockPos below = entity.getBlockPos().down();
        return world.getBlockState(below).isSolidBlock(world, below);
    }

    private Vec3d computeSurfaceNormal(Entity entity) {
        BlockPos pos = entity.getBlockPos();
        World world = entity.getWorld();
        Vec3d normal = Vec3d.ZERO;
        int count = 0;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (world.getBlockState(neighbor).isSolidBlock(world, neighbor)) {
                normal = normal.add(-dir.getOffsetX(), -dir.getOffsetY(), -dir.getOffsetZ());
                count++;
            }
        }

        BlockPos below = entity.getBlockPos().down();
        if (world.getBlockState(below).isSolidBlock(world, below)) {
            normal = normal.add(0, 1, 0);
            count++;
        }

        if (count > 0 && normal.lengthSquared() > 0.001) {
            return normal.normalize();
        }
        return Vec3d.ZERO;
    }

    // =========================================================================
    // Movement API
    // =========================================================================

    @Override
    public void setMoveTarget(Vec3d target) {
        this.moveTarget = target;
        this.moving = true;
    }

    @Override
    public void stopMoving() {
        this.moving = false;
        this.currentPath = null;
        this.currentSearch = null;
    }

    @Override
    public boolean isMoving() {
        return moving;
    }

    // =========================================================================
    // Pathfinding
    // =========================================================================

    @Override
    public void requestPath(BlockPos goal) {
        if (goal == null) return;

        CentipedeHeadEntity head = getLeadingHead();
        if (head == null || head.isRemoved()) return;

        BlockPos start = head.getBlockPos();
        currentSearch = new CentipedePathfinder.IncrementalSearch(
                this.getWorld(), start, goal, (int) this.getAttributeValue(EntityAttributes.GENERIC_FOLLOW_RANGE));
        lastPathGoal = goal;
        pathRecalcTimer = PATH_RECALC_INTERVAL;
    }

    @Override
    public void requestPathTo(Vec3d target) {
        requestPath(BlockPos.ofFloored(target.x, target.y, target.z));
    }

    private void updatePathfinding() {
        if (currentSearch != null && !currentSearch.isFinished()) {
            currentSearch.step(PATH_STEPS_PER_TICK);

            if (currentSearch.isFinished()) {
                currentPath = currentSearch.getPath();
                currentSearch = null;
            }
        }

        if (pathRecalcTimer > 0) {
            pathRecalcTimer--;
        }
    }

    @Override
    public boolean needsPathRecalc(Vec3d goalPos) {
        if (currentPath == null || currentPath.isEmpty()) return true;
        if (pathRecalcTimer > 0) return false;

        if (lastPathGoal != null) {
            BlockPos newGoal = BlockPos.ofFloored(goalPos.x, goalPos.y, goalPos.z);
            if (newGoal.getManhattanDistance(lastPathGoal) > 3) return true;
        }

        if (!CentipedePathfinder.isPathValid(this.getWorld(), currentPath)) return true;

        return false;
    }

    @Override
    public void followCurrentPath() {
        if (currentPath == null || currentPath.isEmpty()) {
            driveTowardTarget();
            return;
        }

        CentipedeHeadEntity head = getLeadingHead();
        if (head == null || head.isRemoved()) return;

        Vec3d headPos = head.getPos();

        BlockPos nextWaypoint = CentipedePathfinder.followPath(
                currentPath, headPos, PATH_LOOK_AHEAD);

        if (nextWaypoint == null) {
            currentPath = null;
            moving = false;
            return;
        }

        BlockPos lastWaypoint = currentPath.get(currentPath.size() - 1);
        double distToEnd = headPos.squaredDistanceTo(
                lastWaypoint.getX() + 0.5, lastWaypoint.getY() + 0.5, lastWaypoint.getZ() + 0.5);
        if (distToEnd < WAYPOINT_REACH_DIST * WAYPOINT_REACH_DIST) {
            currentPath = null;
            moving = false;
            return;
        }

        Vec3d waypointCenter = new Vec3d(
                nextWaypoint.getX() + 0.5,
                nextWaypoint.getY() + 0.5,
                nextWaypoint.getZ() + 0.5);

        Vec3d dir = waypointCenter.subtract(headPos);
        double dist = dir.length();

        if (dist < 0.1) return;
        dir = dir.normalize();

        double speed = 0.14 * 0.5;

        if (isNearSurface(head)) {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed));
        } else {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed * 0.3));
        }

        for (int i = 1; i < TOTAL_SEGMENTS - 1; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;
            if (!isNearSurface(segments[i])) continue;

            int inFront = bodyDirection ? (i + 1) : (i - 1);
            if (inFront < 0 || inFront >= TOTAL_SEGMENTS) continue;
            if (segments[inFront] == null) continue;

            Vec3d towardFront = segments[inFront].getPos().subtract(segments[i].getPos()).normalize();
            segments[i].segmentVelocity = segments[i].segmentVelocity.add(towardFront.multiply(0.05));
        }
    }

    public void driveTowardTarget() {
        CentipedeHeadEntity head = getLeadingHead();
        if (head == null || head.isRemoved()) return;

        Vec3d headPos = head.getPos();
        Vec3d dir = moveTarget.subtract(headPos);
        double dist = dir.length();

        if (dist < 0.5) {
            moving = false;
            return;
        }

        dir = dir.normalize();
        double speed = 0.14 * 0.5;

        if (isNearSurface(head)) {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed));
        } else {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed * 0.3));
        }

        for (int i = 1; i < TOTAL_SEGMENTS - 1; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;
            if (!isNearSurface(segments[i])) continue;

            int inFront = bodyDirection ? (i + 1) : (i - 1);
            if (inFront < 0 || inFront >= TOTAL_SEGMENTS) continue;
            if (segments[inFront] == null) continue;

            Vec3d towardFront = segments[inFront].getPos().subtract(segments[i].getPos()).normalize();
            segments[i].segmentVelocity = segments[i].segmentVelocity.add(towardFront.multiply(0.05));
        }
    }

    @Override
    public void updateDirectionChange() {
        if (directionChangeBlock > 0) {
            if (moving) directionChangeBlock--;
            return;
        }

        if (huntTarget == null) return;
        BlockPos targetBlock = huntTarget.getBlockPos();

        CentipedeHeadEntity front = getFrontHead();
        CentipedeHeadEntity rear = getRearHead();
        if (front == null || rear == null) return;

        boolean rearIsCloser = CentipedePathfinder.tileClosestToGoal(
                this.getWorld(), rear.getBlockPos(), front.getBlockPos(), targetBlock);

        if (rearIsCloser) {
            changeDirCounter++;
            if (changeDirCounter > 5) {
                bodyDirection = !bodyDirection;
                directionChangeBlock = 40;
                changeDirCounter = 0;
                if (currentPath != null) {
                    requestPath(targetBlock);
                }
            }
        } else {
            changeDirCounter = 0;
        }
    }

    // =========================================================================
    // Grab & Shock system
    // =========================================================================

    private void updateGrabs() {
        CentipedeHeadEntity head0 = (segments[0] instanceof CentipedeHeadEntity h) ? h : null;
        CentipedeHeadEntity head1 = (segments[TOTAL_SEGMENTS - 1] instanceof CentipedeHeadEntity h) ? h : null;

        if (head0 != null) checkHeadGrab(head0);
        if (head1 != null) checkHeadGrab(head1);

        if (head0 != null && head1 != null) {
            CentipedeHeadEntity grabbingHead = null;
            CentipedeHeadEntity freeHead = null;

            if (head0.isGrabbing() && !head1.isGrabbing()) {
                grabbingHead = head0; freeHead = head1;
            } else if (head1.isGrabbing() && !head0.isGrabbing()) {
                grabbingHead = head1; freeHead = head0;
            }

            if (grabbingHead != null && freeHead != null) {
                LivingEntity grabbed = grabbingHead.getGrabbedEntity();
                if (grabbed != null) {
                    Vec3d grabPos = grabbed.getPos();
                    moveTarget = grabPos;
                    moving = true;

                    boolean freeHeadIsAt0 = (freeHead == head0);
                    boolean needsDirection = freeHeadIsAt0 ? bodyDirection : !bodyDirection;
                    if (needsDirection && directionChangeBlock <= 0) {
                        bodyDirection = !bodyDirection;
                        directionChangeBlock = 60;
                    }

                    doubleGrabCharge = Math.min(1.0f, doubleGrabCharge + 0.02f);

                    Vec3d toTarget = grabPos.subtract(freeHead.getPos());
                    double distToTarget = toTarget.length();
                    if (distToTarget > 0.3) {
                        Vec3d dirVec = toTarget.normalize();
                        double force = 0.15 + 0.2 * doubleGrabCharge;
                        freeHead.segmentVelocity = freeHead.segmentVelocity.add(dirVec.multiply(force));
                    }

                    for (int i = 1; i < TOTAL_SEGMENTS - 1; i++) {
                        if (segments[i] == null || segments[i].isRemoved()) continue;
                        Vec3d segToTarget = grabPos.subtract(segments[i].getPos());
                        double segDist = segToTarget.length();
                        if (segDist > 1.0) {
                            int distFromFree = freeHeadIsAt0 ? i : (TOTAL_SEGMENTS - 1 - i);
                            double curlWeight = 1.0 - (double) distFromFree / TOTAL_SEGMENTS;
                            double curlForce = 0.04 * curlWeight * doubleGrabCharge;
                            segments[i].segmentVelocity = segments[i].segmentVelocity.add(
                                    segToTarget.normalize().multiply(curlForce));
                        }
                    }
                }
            } else if (!head0.isGrabbing() && !head1.isGrabbing()) {
                doubleGrabCharge = Math.max(0, doubleGrabCharge - 0.025f);
                shockGiveUpCounter = Math.max(0, shockGiveUpCounter - 2);
            }
        }

        if (doubleGrabCharge > 0.85f) {
            shockGiveUpCounter++;
            if (shockGiveUpCounter >= 160) {
                CentipedeHeadEntity head0g = (segments[0] instanceof CentipedeHeadEntity h) ? h : null;
                CentipedeHeadEntity head1g = (segments[TOTAL_SEGMENTS - 1] instanceof CentipedeHeadEntity h) ? h : null;
                if (head0g != null) head0g.releaseGrab();
                if (head1g != null) head1g.releaseGrab();
                shockGiveUpCounter = 0;
                doubleGrabCharge = 0;
            }
        }
    }

    private void checkHeadGrab(CentipedeHeadEntity head) {
        if (head.isGrabbing()) return;

        Box searchBox = head.getBoundingBox().expand(0.8);
        List<LivingEntity> nearby = this.getWorld().getEntitiesByClass(
                LivingEntity.class, searchBox, this::isValidPrey);

        for (LivingEntity target : nearby) {
            if (head.tryGrab(target)) {
                this.getWorld().playSound(null, head.getBlockPos(),
                        SoundEvents.ENTITY_SPIDER_AMBIENT, this.getSoundCategory(), 0.6f, 1.8f);
                break;
            }
        }
    }

    private boolean isValidPrey(LivingEntity entity) {
        if (entity == this) return false;
        if (entity instanceof CentipedeSegmentEntity) return false;
        if (entity instanceof CentipedeController) return false;
        if (entity.isRemoved() || entity.isDead()) return false;
        if (entity.isInvulnerable()) return false;
        return entity instanceof ChickenEntity;
    }

    private void updateShockCharge() {
        CentipedeHeadEntity head0 = (segments[0] instanceof CentipedeHeadEntity h) ? h : null;
        CentipedeHeadEntity head1 = (segments[TOTAL_SEGMENTS - 1] instanceof CentipedeHeadEntity h) ? h : null;

        if (head0 == null || head1 == null) return;

        boolean head0Grab = head0.isGrabbing();
        boolean head1Grab = head1.isGrabbing();

        if (head0Grab && head1Grab) {
            LivingEntity target0 = head0.getGrabbedEntity();
            LivingEntity target1 = head1.getGrabbedEntity();

            boolean sameTarget = (target0 == target1);
            boolean headsCloseToTarget = false;

            if (!sameTarget && target0 != null) {
                headsCloseToTarget = head1.getPos().distanceTo(target0.getPos())
                        < target0.getWidth() + 3.0;
            }
            if (!sameTarget && target1 != null && !headsCloseToTarget) {
                headsCloseToTarget = head0.getPos().distanceTo(target1.getPos())
                        < target1.getWidth() + 3.0;
            }

            if (sameTarget || headsCloseToTarget) {
                float chargeRate = 1.0f / 100f;
                shockCharge += chargeRate * 2f;

                if (shockCharge >= 1.0f) {
                    LivingEntity victim = (target0 != null) ? target0 : target1;
                    shock(victim);
                    shockCharge = 0;
                    head0.releaseGrab();
                    head1.releaseGrab();
                }
            }
        } else if (head0Grab || head1Grab) {
            CentipedeHeadEntity grabbing = head0Grab ? head0 : head1;
            CentipedeHeadEntity free = head0Grab ? head1 : head0;
            LivingEntity grabbed = grabbing.getGrabbedEntity();

            if (grabbed != null) {
                double dist = free.getPos().distanceTo(grabbed.getPos());
                if (dist < grabbed.getWidth() + 2.5) {
                    float chargeRate = 1.0f / 100f;
                    shockCharge += chargeRate * 2f;
                    if (shockCharge >= 1.0f) {
                        shock(grabbed);
                        shockCharge = 0;
                        grabbing.releaseGrab();
                    }
                }
            }
        } else {
            shockCharge = Math.max(0, shockCharge - 1f / 60f);
        }
    }

    private void shock(LivingEntity victim) {
        if (victim == null || victim.isRemoved()) return;

        this.getWorld().playSound(null, this.getBlockPos(),
                SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, this.getSoundCategory(), 0.5f, 2.0f);

        if (this.getWorld() instanceof ServerWorld sw) {
            Vec3d victimPos = victim.getPos();
            for (int i = 0; i < 5; i++) {
                sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK,
                        victimPos.x + (random.nextDouble() - 0.5) * 1.5,
                        victimPos.y + random.nextDouble() * victim.getHeight(),
                        victimPos.z + (random.nextDouble() - 0.5) * 1.5,
                        1, 0.2, 0.2, 0.2, 0.05);
            }
        }

        for (CentipedeSegmentEntity seg : segments) {
            if (seg != null && !seg.isRemoved()) {
                seg.segmentVelocity = seg.segmentVelocity.add(
                        (random.nextDouble() - 0.5) * 0.2,
                        (random.nextDouble() - 0.5) * 0.2,
                        (random.nextDouble() - 0.5) * 0.2);
            }
        }

        float shockDamage = 4f;
        victim.damage(this.getDamageSources().mobAttack(this), shockDamage);
        victim.setVelocity(
                (random.nextDouble() - 0.5) * 0.3,
                0.2,
                (random.nextDouble() - 0.5) * 0.3);
    }

    // =========================================================================
    // Target management
    // =========================================================================

    @Override
    public void setHuntTarget(LivingEntity target) {
        this.huntTarget = target;
    }

    @Override
    public LivingEntity getHuntTarget() {
        return huntTarget;
    }

    // =========================================================================
    // Death / cleanup
    // =========================================================================

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        for (CentipedeSegmentEntity seg : segments) {
            if (seg != null && !seg.isRemoved()) {
                seg.discard();
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (segments != null) {
            for (CentipedeSegmentEntity seg : segments) {
                if (seg != null && !seg.isRemoved()) {
                    seg.discard();
                }
            }
        }
    }

    // =========================================================================
    // NBT
    // =========================================================================

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("BodyDirection", bodyDirection);
        nbt.putFloat("ShockCharge", shockCharge);
        nbt.putBoolean("SegmentsSpawned", segmentsSpawned);

        int[] ids = new int[TOTAL_SEGMENTS];
        for (int i = 0; i < TOTAL_SEGMENTS; i++) {
            ids[i] = segmentIds[i];
        }
        nbt.putIntArray("SegmentIds", ids);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("BodyDirection")) bodyDirection = nbt.getBoolean("BodyDirection");
        if (nbt.contains("ShockCharge")) shockCharge = nbt.getFloat("ShockCharge");
        if (nbt.contains("SegmentsSpawned")) segmentsSpawned = nbt.getBoolean("SegmentsSpawned");

        if (nbt.contains("SegmentIds")) {
            int[] ids = nbt.getIntArray("SegmentIds");
            for (int i = 0; i < Math.min(ids.length, TOTAL_SEGMENTS); i++) {
                segmentIds[i] = ids[i];
            }
        }
    }

    // =========================================================================
    // GeckoLib
    // =========================================================================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 5, state -> {
            state.getController().setAnimation(IDLE_ANIM);
            return PlayState.CONTINUE;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    @Override
    public double getTick(Object entity) {
        return ((Entity) entity).age;
    }

    // =========================================================================
    // Rendering: make controller entity invisible
    // =========================================================================

    @Override
    public boolean isInvisible() {
        return true;
    }

    // =========================================================================
    // CentipedeController interface
    // =========================================================================

    @Override
    public int getTotalSegments() {
        return TOTAL_SEGMENTS;
    }

    @Override
    public float getSize() {
        return SIZE;
    }

    @Override
    public int getShellColorRGB() {
        return SHELL_COLOR;
    }

    @Override
    public int getSecondaryShellColorRGB() {
        return SECONDARY_SHELL_COLOR;
    }

    @Override
    public float getLegScale() {
        return 0.8f;
    }
}
