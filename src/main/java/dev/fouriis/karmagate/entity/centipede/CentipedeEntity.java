package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.block.BlockState;
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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.Random;

/**
 * Controller entity for a normal (orange) Centipede. Uses the C# variable size system
 * where size is in [0, 1], biased toward smaller values via pow(random, 1.5).
 *
 * Segment count varies: (int)lerp(7, 17, size) total (2 heads + N body).
 * Body radius uses the standard formula without the +1.5 Red bonus.
 * Color is orange (C# hue=lerp(0.04,0.1,random), saturation=0.9).
 */
public class CentipedeEntity extends HostileEntity implements GeoAnimatable, CentipedeController {
    private static final Logger LOGGER = LoggerFactory.getLogger(CentipedeEntity.class);

    protected final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    protected static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    // --- Model depth constants (BlockBench units / 16 * render_scale 0.5) ---
    private static final double BODY_RENDER_DEPTH = 23.0 / 16.0 * 0.5;
    private static final double HEAD_RENDER_DEPTH = 16.0 / 16.0 * 0.5;

    // --- Tracked data ---
    private static final TrackedData<Boolean> BODY_DIRECTION = DataTracker.registerData(
            CentipedeEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> SHOCK_CHARGE = DataTracker.registerData(
            CentipedeEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> IS_MOVING = DataTracker.registerData(
            CentipedeEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> BODY_WAVE = DataTracker.registerData(
            CentipedeEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CENTIPEDE_SIZE = DataTracker.registerData(
            CentipedeEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // --- Size-derived config (computed once from size) ---
    protected float size = 0.5f;
    protected int totalSegments = 12;
    protected int bodySegmentCount = 10;
    protected float maxRadius = 6.5f;

    // --- Segment references ---
    protected int[] segmentIds;
    protected CentipedeSegmentEntity[] segments;
    protected boolean segmentsSpawned = false;

    // --- Movement / AI state ---
    protected boolean bodyDirection = false;
    protected Vec3d moveTarget = Vec3d.ZERO;
    protected float bodyWave = 0f;
    protected boolean moving = false;
    protected int directionChangeBlock = 0;
    protected int changeDirCounter = 0;

    // --- Shock / grab state ---
    protected float shockCharge = 0f;
    protected int shockGiveUpCounter = 0;
    protected float doubleGrabCharge = 0f;

    // --- AI behavior ---
    protected LivingEntity huntTarget = null;

    // --- Forced pathing (debug command) ---
    protected boolean forcedPathing = false;
    protected BlockPos forcedPathTarget = null;

    // --- Pathfinding ---
    protected CentipedePathfinder.Search currentSearch = null;
    protected int pathRecalcTimer = 0;
    protected BlockPos lastPathGoal = null;
    protected static final int PATH_STEPS_PER_TICK = 80;
    protected static final int PATH_RECALC_INTERVAL = 30;
    protected static final int PATH_LOOK_AHEAD = 3;
    protected static final double WAYPOINT_REACH_DIST = 1.5;

    // --- Stall detection ---
    protected Vec3d lastHeadProgressPos = Vec3d.ZERO;
    protected int stuckTicks = 0;

    // CentipedeEntity.java
private static final int SHELL_COLOR = (242 << 16) | (109 << 8) | 13;
private static final int SECONDARY_SHELL_COLOR = (145 << 16) | (66 << 8) | 8;

    public CentipedeEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        recalcSizeDerivedFields();
    }

    protected void configureFixedVariant(float size, int totalSegments, float maxRadius) {
        this.size = size;
        this.totalSegments = Math.max(totalSegments, 3);
        this.bodySegmentCount = this.totalSegments - 2;
        this.maxRadius = maxRadius;
        this.segmentIds = new int[this.totalSegments];
        this.segments = new CentipedeSegmentEntity[this.totalSegments];
        java.util.Arrays.fill(this.segmentIds, -1);
    }

    /**
     * Compute size from the entity's UUID, mirroring C# GenerateSize:
     * size = pow(random, 1.5) where random is seeded from creature ID.
     */
    protected void computeSizeFromSeed() {
        long seed = this.getUuid().getLeastSignificantBits();
        Random rng = new Random(seed);
        this.size = (float) Math.pow(rng.nextFloat(), 1.5f);
        recalcSizeDerivedFields();
    }

    protected void readTrackedDataFromClient() {
        bodyDirection = this.dataTracker.get(BODY_DIRECTION);
        shockCharge = this.dataTracker.get(SHOCK_CHARGE);
        moving = this.dataTracker.get(IS_MOVING);
        bodyWave = this.dataTracker.get(BODY_WAVE);
        float syncedSize = this.dataTracker.get(CENTIPEDE_SIZE);
        if (Math.abs(syncedSize - this.size) > 0.001f) {
            this.size = syncedSize;
            recalcSizeDerivedFields();
        }
    }

    protected void recalcSizeDerivedFields() {
        this.totalSegments = (int) MathHelper.lerp(size, 7f, 17f);
        if (this.totalSegments < 3) this.totalSegments = 3;
        this.bodySegmentCount = this.totalSegments - 2;

        this.maxRadius = MathHelper.lerp(size, 4f, 6.5f);

        this.segmentIds = new int[totalSegments];
        this.segments = new CentipedeSegmentEntity[totalSegments];
        java.util.Arrays.fill(segmentIds, -1);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0)
                .add(EntityAttributes.GENERIC_ARMOR, 4.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.4);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(BODY_DIRECTION, false);
        builder.add(SHOCK_CHARGE, 0f);
        builder.add(IS_MOVING, false);
        builder.add(BODY_WAVE, 0f);
        builder.add(CENTIPEDE_SIZE, 0.5f);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new CentipedeShockGoal<>(this));
        this.goalSelector.add(2, new CentipedeHuntGoal<>(this));
        this.goalSelector.add(3, new CentipedeWanderGoal<>(this));
        this.goalSelector.add(4, new LookAroundGoal(this));

        this.targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false,
                entity -> {
                    if (entity instanceof PlayerEntity player) {
                        if (player.isCreative() || player.isSpectator()) return false;
                    }
                    return true;
                }));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, LivingEntity.class, 10, true, false,
                entity -> !(entity instanceof CentipedeController)
                        && !(entity instanceof CentipedeSegmentEntity)
                        && !(entity instanceof PlayerEntity p && (p.isCreative() || p.isSpectator()))
                        && entity.getType().getSpawnGroup().isPeaceful()));
    }

    // =========================================================================
    // Segment management
    // =========================================================================

    protected void spawnSegments() {
        if (this.getWorld().isClient || segmentsSpawned) return;
        ServerWorld sw = (ServerWorld) this.getWorld();

        Vec3d basePos = this.getPos();
        float yawRad = this.getYaw() * ((float) Math.PI / 180f);
        Vec3d forward = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));

        double cumulativeOffset = 0;
        for (int i = 0; i < totalSegments; i++) {
            if (i > 0) {
                cumulativeOffset += getSegmentSpacing(i - 1, i);
            }
            Vec3d segPos = basePos.subtract(forward.multiply(cumulativeOffset));
            CentipedeSegmentEntity seg;

            if (i == 0 || i == totalSegments - 1) {
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
            seg.setHasShell(true);
            if (this.hasCustomName()) {
                seg.applySharedCustomName(this.getCustomName());
            }
            sw.spawnEntity(seg);

            segmentIds[i] = seg.getId();
            segments[i] = seg;
        }

        segmentsSpawned = true;
    }

    protected void resolveSegments() {
        for (int i = 0; i < totalSegments; i++) {
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
        int idx = bodyDirection ? (totalSegments - 1) : 0;
        if (segments != null && idx < segments.length && segments[idx] instanceof CentipedeHeadEntity h) return h;
        return null;
    }

    @Override
    public CentipedeHeadEntity getRearHead() {
        int idx = bodyDirection ? 0 : (totalSegments - 1);
        if (segments != null && idx < segments.length && segments[idx] instanceof CentipedeHeadEntity h) return h;
        return null;
    }

    public CentipedeHeadEntity getLeadingHead() {
        return getFrontHead();
    }

    public int getHeadIndex() {
        return bodyDirection ? (totalSegments - 1) : 0;
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
        if (idx >= totalSegments) {
            totalSegments = idx + 1;
            bodySegmentCount = totalSegments - 2;
            int[] newIds = new int[totalSegments];
            CentipedeSegmentEntity[] newSegs = new CentipedeSegmentEntity[totalSegments];
            java.util.Arrays.fill(newIds, -1);
            if (segmentIds != null) {
                System.arraycopy(segmentIds, 0, newIds, 0, Math.min(segmentIds.length, totalSegments));
            }
            if (segments != null) {
                System.arraycopy(segments, 0, newSegs, 0, Math.min(segments.length, totalSegments));
            }
            segmentIds = newIds;
            segments = newSegs;
        }
        if (segments != null && idx >= 0 && idx < totalSegments) {
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
        float segRatio = (float) segIndex / (float) (totalSegments - 1);
        float sinVal = (float) Math.max(0, Math.sin(Math.PI * segRatio));
        float powExp = MathHelper.lerp(size, 0.7f, 0.3f);
        float powVal = (float) Math.pow(sinVal, powExp);
        float minR = MathHelper.lerp(size, 2f, 3.5f);
        float maxR = MathHelper.lerp(size, 4f, 6.5f);
        return MathHelper.lerp(powVal, minR, maxR);
    }

    @Override
    public float getMaxRadius() {
        return maxRadius;
    }

    protected double getRenderedDepth(int segIndex) {
        if (segIndex == 0 || segIndex == totalSegments - 1) {
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
            tickServer();
        } else {
            readTrackedDataFromClient();
        }
    }

    /**
     * Server-side tick logic. Subclasses (e.g. CentiwingEntity) can override
     * this to replace crawling physics with flight physics.
     */
    protected void tickServer() {
        if (!segmentsSpawned) {
            computeSizeFromSeed();
            spawnSegments();
            return;
        }

        resolveSegments();

        // Auto-clear forced pathing when close to target
        if (forcedPathing && forcedPathTarget != null) {
            CentipedeHeadEntity head = getLeadingHead();
            if (head != null && !head.isRemoved()) {
                double dist = head.getPos().squaredDistanceTo(Vec3d.ofCenter(forcedPathTarget));
                if (dist < 4.0) {
                    forcedPathing = false;
                    forcedPathTarget = null;
                }
            }
        }

        updatePathfinding();
        updateChainPhysics();
        updateSegmentRotations();
        updateGrabs();
        updateShockCharge();
        syncTrackedData();
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

    protected void syncTrackedData() {
        this.dataTracker.set(BODY_DIRECTION, bodyDirection);
        this.dataTracker.set(SHOCK_CHARGE, shockCharge);
        this.dataTracker.set(IS_MOVING, moving);
        this.dataTracker.set(BODY_WAVE, bodyWave);
        this.dataTracker.set(CENTIPEDE_SIZE, size);
    }

    protected void updateChainPhysics() {
        CentipedeHeadEntity lead = getLeadingHead();
        if (lead != null && !lead.isRemoved()) {
            this.setPosition(lead.getPos());
        }

        if (moving) {
            bodyWave += (bodyDirection ? -1f : 1f) * 0.1f;
        }

        if (moving) {
            for (int i = 1; i < totalSegments - 1; i++) {
                if (segments[i] == null || segments[i].isRemoved()) continue;
                if (!isNearSurface(segments[i])) continue;

                int ahead = bodyDirection ? (i + 1) : (i - 1);
                int behind = bodyDirection ? (i - 1) : (i + 1);

                if (ahead >= 0 && ahead < totalSegments && segments[ahead] != null && !segments[ahead].isRemoved()) {
                    if (isNearSurface(segments[ahead])) {
                        Vec3d toAhead = segments[ahead].getPos().subtract(segments[i].getPos()).normalize();
                        segments[i].segmentVelocity = segments[i].segmentVelocity.add(toAhead.multiply(0.068 * MathHelper.lerp(size, 0.5f, 1.5f)));
                    }
                }

                if (behind >= 0 && behind < totalSegments && segments[behind] != null && !segments[behind].isRemoved()) {
                    Vec3d toBehind = segments[behind].getPos().subtract(segments[i].getPos()).normalize();
                    segments[i].segmentVelocity = segments[i].segmentVelocity.subtract(toBehind.multiply(0.04));
                }
            }
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
            for (int i = 0; i < totalSegments - 1; i++) {
                enforceSpacing(i, i + 1);
            }
            for (int i = totalSegments - 2; i >= 0; i--) {
                enforceSpacing(i, i + 1);
            }
        }
    }

    protected void updateSegmentRotations() {
        for (int i = 0; i < totalSegments; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;

            Vec3d dir = getChainDirection(i);
            float yaw = (float) (Math.atan2(-dir.x, dir.z) * (180.0 / Math.PI));
            if (i == totalSegments - 1) {
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

    protected void enforceSpacing(int a, int b) {
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

    protected Vec3d getChainDirection(int index) {
        if (index <= 0 && segments.length > 1 && segments[1] != null) {
            return segments[1].getPos().subtract(segments[0].getPos()).normalize();
        }
        if (index >= totalSegments - 1 && segments[totalSegments - 2] != null) {
            return segments[totalSegments - 1].getPos().subtract(segments[totalSegments - 2].getPos()).normalize();
        }
        if (index > 0 && index < totalSegments - 1 && segments[index - 1] != null && segments[index + 1] != null) {
            return segments[index + 1].getPos().subtract(segments[index - 1].getPos()).normalize();
        }
        return new Vec3d(1, 0, 0);
    }

    protected boolean isNearSurface(Entity entity) {
        return computeSurfaceNormal(entity).lengthSquared() > 0.001;
    }

    private Vec3d computeSurfaceNormal(Entity entity) {
        World world = entity.getWorld();
        Vec3d center = entity.getBoundingBox().getCenter();
        Vec3d normal = Vec3d.ZERO;
        int count = 0;

        double[] distances = {0.55, 0.8};

        for (double d : distances) {
            for (Direction dir : Direction.values()) {
                BlockPos probePos = BlockPos.ofFloored(
                        center.x + dir.getOffsetX() * d,
                        center.y + dir.getOffsetY() * d,
                        center.z + dir.getOffsetZ() * d
                );

                if (world.getBlockState(probePos).isSolidBlock(world, probePos)) {
                    normal = normal.add(-dir.getOffsetX(), -dir.getOffsetY(), -dir.getOffsetZ());
                    count++;
                }
            }
        }

        if (count > 0 && normal.lengthSquared() > 0.001) {
            return normal.normalize();
        }
        return Vec3d.ZERO;
    }

    private Vec3d getSurfaceAlignedMove(CentipedeSegmentEntity segment, Vec3d desiredDir, double speed) {
    World world = segment.getWorld();
    Vec3d surfaceNormal = computeSurfaceNormal(segment);
    Vec3d move = desiredDir.normalize().multiply(speed);

    BlockPos basePos = segment.getBlockPos();
    Direction horizontalDir = Direction.getFacing(desiredDir.x, 0, desiredDir.z);
    if (horizontalDir.getAxis().isVertical()) {
        horizontalDir = segment.getHorizontalFacing();
    }

    BlockPos front = basePos.offset(horizontalDir);
    BlockPos frontUp = front.up();
    BlockPos up = basePos.up();
    BlockPos upForward = up.offset(horizontalDir);
    BlockPos topSurfaceAhead = basePos.add(horizontalDir.getOffsetX(), 1, horizontalDir.getOffsetZ());

    boolean wallAhead = world.getBlockState(front).isSolidBlock(world, front);
    boolean wallAheadUp = world.getBlockState(frontUp).isSolidBlock(world, frontUp);
    boolean ceilingUp = world.getBlockState(up).isSolidBlock(world, up);

    boolean openUp = !world.getBlockState(up).isSolidBlock(world, up);
    boolean openFrontUp = !world.getBlockState(frontUp).isSolidBlock(world, frontUp);
    boolean openUpForward = !world.getBlockState(upForward).isSolidBlock(world, upForward);
    boolean openTopSurfaceAhead = !world.getBlockState(topSurfaceAhead).isSolidBlock(world, topSurfaceAhead);

    if (surfaceNormal.lengthSquared() > 0.001) {
        Vec3d tangent = move.subtract(surfaceNormal.multiply(move.dotProduct(surfaceNormal)));
        if (tangent.lengthSquared() > 1.0E-4) {
            move = tangent.normalize().multiply(speed);
        }
    }

    // ---------------------------------------------------------------------
    // Floor -> wall transition
    // ---------------------------------------------------------------------
    if (desiredDir.y > 0.12 && wallAhead) {
        Vec3d climb = new Vec3d(0, 1, 0).multiply(speed * 0.95);
        Vec3d towardWall = new Vec3d(horizontalDir.getOffsetX(), 0, horizontalDir.getOffsetZ()).multiply(speed * 0.30);
        move = move.add(climb).add(towardWall);
    }

    // ---------------------------------------------------------------------
    // Wall -> top transition (the lip problem)
    // Strong forward/upward bias when the cell above the lip is open.
    // ---------------------------------------------------------------------
    boolean cresting =
            desiredDir.y > 0.05 &&
            wallAhead &&
            !wallAheadUp &&
            openFrontUp &&
            openUpForward;

    if (cresting) {
        Vec3d overLip = new Vec3d(horizontalDir.getOffsetX(), 0, horizontalDir.getOffsetZ()).multiply(speed * 0.90);
        Vec3d lift = new Vec3d(0, 1, 0).multiply(speed * 0.75);
        move = move.add(overLip).add(lift);
    }

    // If we are already near the top edge and there is open space above-forward,
    // prefer moving onto the top rather than continuing to stick to the wall.
    boolean topOut =
            !wallAheadUp &&
            openUp &&
            openFrontUp &&
            openTopSurfaceAhead &&
            desiredDir.y >= -0.05;

    if (topOut) {
        Vec3d ontoTop = new Vec3d(horizontalDir.getOffsetX(), 0.55, horizontalDir.getOffsetZ()).normalize().multiply(speed * 1.00);
        move = move.add(ontoTop);
    }

    // ---------------------------------------------------------------------
    // Ceiling / upper-surface assist
    // ---------------------------------------------------------------------
    if (desiredDir.y > 0.12 && ceilingUp) {
        move = move.add(0, speed * 0.25, 0);
    }

    // ---------------------------------------------------------------------
    // Wall cling: weaker during cresting so it can peel off the wall
    // ---------------------------------------------------------------------
    if (wallAhead) {
        double clingStrength = cresting || topOut ? speed * 0.10 : speed * 0.35;
        Vec3d wallNormal = new Vec3d(-horizontalDir.getOffsetX(), 0, -horizontalDir.getOffsetZ());
        move = move.add(wallNormal.multiply(clingStrength));
    }

    // Mild downward stabilization when moving across a top surface
    if (!wallAhead && openUp && desiredDir.y < 0.15) {
        move = move.add(0, -speed * 0.08, 0);
    }

    return move;
}

    private void applyHeadPathingForce(CentipedeHeadEntity head, Vec3d targetPos, double speed) {
    Vec3d dir = targetPos.subtract(head.getPos());
    double dist = dir.length();
    if (dist < 0.1) return;

    Vec3d desiredDir = dir.normalize();
    Vec3d move = getSurfaceAlignedMove(head, desiredDir, speed);

    if (!isNearSurface(head)) {
        move = desiredDir.multiply(speed * 0.50);
    }

    double dy = targetPos.y - head.getY();

    // Stronger upward assist for ledge topping.
    if (dy > 0.10) {
        move = move.add(0, Math.min(speed * 1.05, dy * 0.40), 0);
    }

    // Slight forward bonus helps the head commit over the lip.
    Vec3d horizontal = new Vec3d(desiredDir.x, 0, desiredDir.z);
    if (horizontal.lengthSquared() > 1.0E-4) {
        move = move.add(horizontal.normalize().multiply(speed * 0.12));
    }

    head.segmentVelocity = head.segmentVelocity.add(move);
}

    private Vec3d getWaypointAnchor(BlockPos waypoint, Vec3d headPos) {
        Vec3d center = new Vec3d(waypoint.getX() + 0.5, waypoint.getY() + 0.5, waypoint.getZ() + 0.5);

        Direction bestSurface = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos adj = waypoint.offset(dir);
            if (this.getWorld().getBlockState(adj).isSolidBlock(this.getWorld(), adj)) {
                Vec3d anchor = center.add(
                        -dir.getOffsetX() * 0.35,
                        -dir.getOffsetY() * 0.35,
                        -dir.getOffsetZ() * 0.35
                );
                double d = headPos.squaredDistanceTo(anchor);
                if (d < bestDist) {
                    bestDist = d;
                    bestSurface = dir;
                }
            }
        }

        if (bestSurface != null) {
            return center.add(
                    -bestSurface.getOffsetX() * 0.35,
                    -bestSurface.getOffsetY() * 0.35,
                    -bestSurface.getOffsetZ() * 0.35
            );
        }

        return center;
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
        currentSearch = CentipedePathfinder.beginSearch(
                this.getWorld(),
                start,
                goal,
                (int) this.getAttributeValue(EntityAttributes.GENERIC_FOLLOW_RANGE)
        );

        lastPathGoal = goal;
        pathRecalcTimer = PATH_RECALC_INTERVAL;
    }

    @Override
    public void requestPathTo(Vec3d target) {
        requestPath(BlockPos.ofFloored(target.x, target.y, target.z));
    }

    protected void updatePathfinding() {
        if (currentSearch != null && !currentSearch.isFinished()) {
            currentSearch.update(
                    CentipedePathfinder.DEFAULT_ACCESSIBILITY_STEPS,
                    PATH_STEPS_PER_TICK
            );
        }

        if (pathRecalcTimer > 0) {
            pathRecalcTimer--;
        }
    }

    @Override
    public boolean needsPathRecalc(Vec3d goalPos) {
        if (currentSearch == null) return true;
        if (pathRecalcTimer > 0) return false;

        BlockPos newGoal = BlockPos.ofFloored(goalPos.x, goalPos.y, goalPos.z);

        if (lastPathGoal != null && newGoal.getManhattanDistance(lastPathGoal) > 3) {
            return true;
        }

        return !CentipedePathfinder.isSearchStillUseful(currentSearch, this.getPos(), goalPos);
    }

    private boolean canHeadReachForPath(BlockPos target) {
        CentipedeHeadEntity head = getLeadingHead();
        if (head == null || head.isRemoved()) return false;

        Vec3d headPos = head.getPos();

        // Hard limit: don't let the head chase waypoints too far above itself.
        double maxVerticalStep = 1.35;
        if (target.getY() + 0.5 > headPos.y + maxVerticalStep) {
            return false;
        }

        // Require some nearby body support behind the head.
        int supportSegments = 0;
        int checkCount = Math.min(4, totalSegments);

        for (int i = 0; i < checkCount; i++) {
            int idx = bodyDirection ? (totalSegments - 1 - i) : i;
            if (idx < 0 || idx >= totalSegments) continue;
            if (segments[idx] == null || segments[idx].isRemoved()) continue;

            if (isNearSurface(segments[idx])) {
                supportSegments++;
            }
        }

        // If the front of the body is not well supported, don't keep climbing higher.
        if (supportSegments < 2 && target.getY() > head.getBlockPos().getY()) {
            return false;
        }

        return true;
    }

    @Override
    public void followCurrentPath() {
        if (currentSearch == null) {
            driveTowardTarget();
            return;
        }

        CentipedeHeadEntity head = getLeadingHead();
        if (head == null || head.isRemoved()) return;

        Vec3d headPos = head.getPos();

        // Clamp lookahead while climbing: if the next step is above the head, use lookahead 1.
        BlockPos probeWaypoint = CentipedePathfinder.followPathfieldLookAhead(currentSearch, headPos, 1);
        int lookAhead = PATH_LOOK_AHEAD;

        if (probeWaypoint != null && probeWaypoint.getY() > head.getBlockPos().getY()) {
            lookAhead = 1;
        }

        BlockPos nextWaypoint = CentipedePathfinder.followPathfieldLookAhead(
                currentSearch,
                headPos,
                lookAhead
        );

        if (nextWaypoint == null) {
            driveTowardTarget();
            return;
        }

        // Reject waypoints the head can't realistically reach.
        if (!canHeadReachForPath(nextWaypoint)) {
            requestPath(currentSearch.getDestination());
            return;
        }

        BlockPos goal = currentSearch.getDestination();
        double distToEnd = headPos.squaredDistanceTo(
                goal.getX() + 0.5,
                goal.getY() + 0.5,
                goal.getZ() + 0.5
        );

        if (distToEnd < WAYPOINT_REACH_DIST * WAYPOINT_REACH_DIST) {
            currentSearch = null;
            moving = false;
            return;
        }

        Vec3d waypointCenter = getWaypointAnchor(nextWaypoint, headPos);

        double speed = 0.14 * MathHelper.lerp(size, 0.5f, 1.5f);
        applyHeadPathingForce(head, waypointCenter, speed);

        for (int i = 1; i < totalSegments - 1; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;
            if (!isNearSurface(segments[i])) continue;

            int inFront = bodyDirection ? (i + 1) : (i - 1);
            if (inFront < 0 || inFront >= totalSegments) continue;
            if (segments[inFront] == null) continue;

            Vec3d towardFront = segments[inFront].getPos().subtract(segments[i].getPos()).normalize();
            segments[i].segmentVelocity = segments[i].segmentVelocity.add(towardFront.multiply(0.05));
        }

        // Stall detection: if the head hasn't moved, force a repath.
        Vec3d newHeadPos = head.getPos();
        if (lastHeadProgressPos != Vec3d.ZERO) {
            double moved = newHeadPos.squaredDistanceTo(lastHeadProgressPos);
            if (moved < 0.0025) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
        }
        lastHeadProgressPos = newHeadPos;

        if (stuckTicks > 12) {
            requestPath(currentSearch.getDestination());
            stuckTicks = 0;
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

        double speed = 0.14 * MathHelper.lerp(size, 0.5f, 1.5f);
        applyHeadPathingForce(head, moveTarget, speed);

        for (int i = 1; i < totalSegments - 1; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;
            if (!isNearSurface(segments[i])) continue;

            int inFront = bodyDirection ? (i + 1) : (i - 1);
            if (inFront < 0 || inFront >= totalSegments) continue;
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

        boolean rearIsCloser = currentSearch != null
                ? CentipedePathfinder.tileClosestToGoal(currentSearch, rear.getBlockPos(), front.getBlockPos())
                : rear.getBlockPos().getSquaredDistance(targetBlock) < front.getBlockPos().getSquaredDistance(targetBlock);

        if (rearIsCloser) {
            changeDirCounter++;
            if (changeDirCounter > 5) {
                bodyDirection = !bodyDirection;
                directionChangeBlock = 40;
                changeDirCounter = 0;
                requestPath(targetBlock);
            }
        } else {
            changeDirCounter = 0;
        }
    }

    // =========================================================================
    // Grab & Shock system
    // =========================================================================

    protected void updateGrabs() {
        CentipedeHeadEntity head0 = (segments[0] instanceof CentipedeHeadEntity h) ? h : null;
        CentipedeHeadEntity head1 = (segments[totalSegments - 1] instanceof CentipedeHeadEntity h) ? h : null;

        if (head0 != null) checkHeadGrab(head0);
        if (head1 != null) checkHeadGrab(head1);

        if (head0 != null && head1 != null) {
            CentipedeHeadEntity grabbingHead = null;
            CentipedeHeadEntity freeHead = null;

            if (head0.isGrabbing() && !head1.isGrabbing()) {
                grabbingHead = head0;
                freeHead = head1;
            } else if (head1.isGrabbing() && !head0.isGrabbing()) {
                grabbingHead = head1;
                freeHead = head0;
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

                    for (int i = 1; i < totalSegments - 1; i++) {
                        if (segments[i] == null || segments[i].isRemoved()) continue;
                        Vec3d segToTarget = grabPos.subtract(segments[i].getPos());
                        double segDist = segToTarget.length();
                        if (segDist > 1.0) {
                            int distFromFree = freeHeadIsAt0 ? i : (totalSegments - 1 - i);
                            double curlWeight = 1.0 - (double) distFromFree / totalSegments;
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
                CentipedeHeadEntity head1g = (segments[totalSegments - 1] instanceof CentipedeHeadEntity h) ? h : null;
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

    protected boolean isValidPrey(LivingEntity entity) {
        if (entity == this) return false;
        if (entity instanceof CentipedeSegmentEntity) return false;
        if (entity instanceof CentipedeController) return false;
        if (entity.isRemoved() || entity.isDead()) return false;
        if (entity.isInvulnerable()) return false;
        if (entity instanceof PlayerEntity player) {
            if (player.isCreative() || player.isSpectator()) return false;
        }
        return true;
    }

    protected void updateShockCharge() {
        CentipedeHeadEntity head0 = (segments[0] instanceof CentipedeHeadEntity h) ? h : null;
        CentipedeHeadEntity head1 = (segments[totalSegments - 1] instanceof CentipedeHeadEntity h) ? h : null;

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
                float chargeRate = 1.0f / MathHelper.lerp(size, 100f, 5f);
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
            LivingEntity grabbed = grabbing.getGrabbedEntity();

            if (grabbed != null) {
                CentipedeHeadEntity free = head0Grab ? head1 : head0;
                double dist = free.getPos().distanceTo(grabbed.getPos());
                if (dist < grabbed.getWidth() + 2.5) {
                    float chargeRate = 1.0f / MathHelper.lerp(size, 100f, 5f);
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

    protected void shock(LivingEntity victim) {
        if (victim == null || victim.isRemoved()) return;

        this.getWorld().playSound(null, this.getBlockPos(),
                SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, this.getSoundCategory(), 0.8f, 1.5f);

        if (this.getWorld() instanceof ServerWorld sw) {
            Vec3d victimPos = victim.getPos();
            int particleCount = (int) MathHelper.lerp(size, 5f, 15f);
            for (int i = 0; i < particleCount; i++) {
                sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK,
                        victimPos.x + (random.nextDouble() - 0.5) * 2,
                        victimPos.y + random.nextDouble() * victim.getHeight(),
                        victimPos.z + (random.nextDouble() - 0.5) * 2,
                        1, 0.3, 0.3, 0.3, 0.1);
            }
        }

        for (CentipedeSegmentEntity seg : segments) {
            if (seg != null && !seg.isRemoved()) {
                seg.segmentVelocity = seg.segmentVelocity.add(
                        (random.nextDouble() - 0.5) * 0.3,
                        (random.nextDouble() - 0.5) * 0.3,
                        (random.nextDouble() - 0.5) * 0.3);
            }
        }

        float centipedeMass = totalSegments * 2.0f;
        float shockDamage = MathHelper.lerp(size, 4f, 20f);
        if (victim.getWidth() * victim.getHeight() * 10 < centipedeMass) {
            victim.damage(this.getDamageSources().mobAttack(this), Float.MAX_VALUE);
        } else {
            victim.damage(this.getDamageSources().mobAttack(this), shockDamage);
            victim.setVelocity(
                    (random.nextDouble() - 0.5) * 0.5,
                    0.3,
                    (random.nextDouble() - 0.5) * 0.5);
        }
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

    @Override
    public boolean isForcedPathing() {
        return forcedPathing;
    }

    @Override
    public void setForcedPathTarget(BlockPos target) {
        if (target == null) {
            this.forcedPathing = false;
            this.forcedPathTarget = null;
            return;
        }
        this.forcedPathing = true;
        this.forcedPathTarget = target;
        this.huntTarget = null;
        this.setTarget(null);
        this.requestPath(target);
        this.setMoveTarget(Vec3d.ofCenter(target));
    }

    @Override
    public void setCustomName(Text customName) {
        super.setCustomName(customName);
        syncSharedCustomName(customName, null);
    }

    void onSegmentCustomNamed(Text customName, CentipedeSegmentEntity source) {
        super.setCustomName(customName);
        syncSharedCustomName(customName, source);
    }

    private void syncSharedCustomName(Text customName, CentipedeSegmentEntity source) {
        if (segments == null) {
            return;
        }

        for (CentipedeSegmentEntity segment : segments) {
            if (segment == null || segment.isRemoved() || segment == source) {
                continue;
            }
            segment.applySharedCustomName(customName);
        }
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
        nbt.putFloat("CentipedeSize", size);
        nbt.putBoolean("BodyDirection", bodyDirection);
        nbt.putFloat("ShockCharge", shockCharge);
        nbt.putBoolean("SegmentsSpawned", segmentsSpawned);

        int[] ids = new int[totalSegments];
        for (int i = 0; i < totalSegments; i++) {
            ids[i] = segmentIds[i];
        }
        nbt.putIntArray("SegmentIds", ids);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("CentipedeSize")) {
            this.size = nbt.getFloat("CentipedeSize");
            recalcSizeDerivedFields();
        }
        if (nbt.contains("BodyDirection")) bodyDirection = nbt.getBoolean("BodyDirection");
        if (nbt.contains("ShockCharge")) shockCharge = nbt.getFloat("ShockCharge");
        if (nbt.contains("SegmentsSpawned")) segmentsSpawned = nbt.getBoolean("SegmentsSpawned");

        if (nbt.contains("SegmentIds")) {
            int[] ids = nbt.getIntArray("SegmentIds");
            if (segmentIds == null || segmentIds.length != totalSegments) {
                segmentIds = new int[totalSegments];
                segments = new CentipedeSegmentEntity[totalSegments];
                java.util.Arrays.fill(segmentIds, -1);
            }
            for (int i = 0; i < Math.min(ids.length, totalSegments); i++) {
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
        return totalSegments;
    }

    @Override
    public float getSize() {
        return size;
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
        return 1.0f;
    }
}