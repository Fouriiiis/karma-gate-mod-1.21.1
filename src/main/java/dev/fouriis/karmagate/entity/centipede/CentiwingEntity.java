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
 * Controller entity for a Centiwing — the winged centipede variant.
 * C# size range: lerp(0.5, 0.65, random).
 * Uses standard centipede segment system but with:
 * - Flying mode with undulating sine-wave body movement
 * - Wing deployment/folding system
 * - Shorter legs (legScale = 0.65)
 * - Green-yellow hue (C# hue = lerp(0.28, 0.38), sat = 0.5)
 * - Body segments are slightly thinner (radius lerped toward min by 0.4)
 */
public class CentiwingEntity extends HostileEntity implements GeoAnimatable, CentipedeController {
    private static final Logger LOGGER = LoggerFactory.getLogger(CentiwingEntity.class);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    // --- Model depth constants (same as normal centipede) ---
    private static final double BODY_RENDER_DEPTH = 23.0 / 16.0 * 0.5;
    private static final double HEAD_RENDER_DEPTH = 16.0 / 16.0 * 0.5;

    // C# pixel scale
    private static final float PX = 0.025f;

    // --- Tracked data ---
    private static final TrackedData<Boolean> BODY_DIRECTION = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> SHOCK_CHARGE = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> IS_MOVING = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> BODY_WAVE = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CENTIPEDE_SIZE = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> IS_FLYING = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> WINGS_STARTED_UP = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> WING_FLAP_CYCLE = DataTracker.registerData(
            CentiwingEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // --- Size-derived config ---
    private float size = 0.575f; // default midpoint of 0.5-0.65
    private int totalSegments = 12;
    private int bodySegmentCount = 10;
    private float maxRadius = 6.5f;
    private float[] wingLengths;

    // --- Segment references ---
    private int[] segmentIds;
    private CentipedeSegmentEntity[] segments;
    private boolean segmentsSpawned = false;

    // --- Movement / AI state ---
    private boolean bodyDirection = false;
    private Vec3d moveTarget = Vec3d.ZERO;
    private float bodyWave = 0f;
    private boolean moving = false;
    private int directionChangeBlock = 0;
    private int changeDirCounter = 0;

    // --- Flying state ---
    private boolean flying = true;
    private boolean wantToFly = true;
    private int flyModeCounter = 100;
    private float wingsStartedUp = 1f;
    private float wingFlapCycle = 0f;
    private float lastWingFlapCycle = 0f;
    private float wingsFolded = 0f;
    private float lastWingsFolded = 0f;

    // --- Shock / grab state ---
    private float shockCharge = 0f;
    private int shockGiveUpCounter = 0;
    private float doubleGrabCharge = 0f;

    // --- AI behavior ---
    private LivingEntity huntTarget = null;

    // --- Pathfinding ---
    private CentipedePathfinder.IncrementalSearch currentSearch = null;
    private List<BlockPos> currentPath = null;
    private int pathIndex = 0;
    private int pathRecalcTimer = 0;
    private BlockPos lastPathGoal = null;
    private static final int PATH_STEPS_PER_TICK = 80;
    private static final int PATH_RECALC_INTERVAL = 30;
    private static final int PATH_LOOK_AHEAD = 3;
    private static final double WAYPOINT_REACH_DIST = 1.5;

    // --- Centiwing colors ---
    // C# HSL(lerp(0.28,0.38), 0.5, 0.5) ≈ green-yellow.
    // Using midpoint hue 0.33 → HSL(0.33, 0.5, 0.5) ≈ RGB(106, 191, 64)
    private static final int SHELL_COLOR = (106 << 16) | (191 << 8) | 64;
    // HSL(0.33, 0.5, 0.3) ≈ RGB(64, 115, 38)
    private static final int SECONDARY_SHELL_COLOR = (64 << 16) | (115 << 8) | 38;

    public CentiwingEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        recalcSizeDerivedFields();
    }

    /**
     * Compute size from UUID. C# Centiwing: size = lerp(0.5, 0.65, random).
     */
    private void computeSizeFromSeed() {
        long seed = this.getUuid().getLeastSignificantBits();
        Random rng = new Random(seed);
        this.size = MathHelper.lerp(rng.nextFloat(), 0.5f, 0.65f);
        recalcSizeDerivedFields();
    }

    private void recalcSizeDerivedFields() {
        // C#: bodyChunks.Length = (int)Lerp(7, 17, size)
        this.totalSegments = (int) MathHelper.lerp(size, 7f, 17f);
        if (this.totalSegments < 3) this.totalSegments = 3;
        this.bodySegmentCount = this.totalSegments - 2;

        // Centiwing body radius: standard formula lerped toward min by 0.4
        // maxRadius at middle segment
        this.maxRadius = computeSegmentRadius(totalSegments / 2);

        // Initialize arrays
        this.segmentIds = new int[totalSegments];
        this.segments = new CentipedeSegmentEntity[totalSegments];
        java.util.Arrays.fill(segmentIds, -1);

        // Wing lengths — C# formula from CentipedeGraphics constructor
        this.wingLengths = new float[totalSegments];
        for (int j = 0; j < totalSegments; j++) {
            float num = (float) j / (float) (totalSegments - 1);
            float num2 = (float) Math.sin(Math.pow(MathHelper.clamp(
                    (0.5f - num) / 0.5f, 0f, 1f), 0.75) * Math.PI);
            num2 *= 1f - num;
            float num3 = (float) Math.sin(Math.pow(MathHelper.clamp(
                    (1f - num) / 0.5f, 0f, 1f), 0.75) * Math.PI);
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
        builder.add(BODY_DIRECTION, false);
        builder.add(SHOCK_CHARGE, 0f);
        builder.add(IS_MOVING, true);
        builder.add(BODY_WAVE, 0f);
        builder.add(CENTIPEDE_SIZE, 0.575f);
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

    private void spawnSegments() {
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
            sw.spawnEntity(seg);

            segmentIds[i] = seg.getId();
            segments[i] = seg;
        }

        segmentsSpawned = true;

        // Initialize moveTarget to spawn position so fly physics doesn't steer toward world origin
        moveTarget = this.getPos();
    }

    private void resolveSegments() {
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

    /**
     * Centiwing body radius: standard formula but lerped 40% toward the thinner bound.
     * C#: num2 = Lerp(num2, Lerp(2, 3.5, size), 0.4) where num2 is the standard radius.
     */
    @Override
    public float computeSegmentRadius(int segIndex) {
        float segRatio = (totalSegments > 1) ? (float) segIndex / (float) (totalSegments - 1) : 0.5f;
        float sinVal = (float) Math.max(0, Math.sin(Math.PI * segRatio));
        float powExp = MathHelper.lerp(size, 0.7f, 0.3f);
        float powVal = (float) Math.pow(sinVal, powExp);
        float minR = MathHelper.lerp(size, 2f, 3.5f);
        float maxR = MathHelper.lerp(size, 4f, 6.5f);
        float standardRadius = MathHelper.lerp(powVal, minR, maxR);
        // Centiwing: lerp toward the thinner end by 0.4
        return MathHelper.lerp(0.4f, standardRadius, minR);
    }

    @Override
    public float getMaxRadius() {
        return maxRadius;
    }

    private double getRenderedDepth(int segIndex) {
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
    // Wing interface
    // =========================================================================

    @Override
    public boolean hasWings() { return true; }

    @Override
    public boolean isFlying() { return flying; }

    @Override
    public float getWingsStartedUp() { return wingsStartedUp; }

    @Override
    public float getWingFlapCycle() { return wingFlapCycle; }

    @Override
    public float getLastWingFlapCycle() { return lastWingFlapCycle; }

    @Override
    public float getWingsFolded() { return wingsFolded; }

    @Override
    public float getLastWingsFolded() { return lastWingsFolded; }

    @Override
    public float getWingLength(int segIndex) {
        if (wingLengths == null || segIndex < 0 || segIndex >= wingLengths.length) return 0f;
        return wingLengths[segIndex];
    }

    // Public accessors for AI goals
    public boolean isWantToFly() { return wantToFly; }
    public void setWantToFly(boolean want) { this.wantToFly = want; }
    public int getFlyModeCounter() { return flyModeCounter; }

    /**
     * C# RatherClimbThanFly: returns true if the position is near a solid surface
     * (should crawl instead of fly).
     */
    public boolean ratherClimbThanFly(BlockPos pos) {
        return CentipedePathfinder.getTerrainProximity(this.getWorld(), pos) < 2;
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
                computeSizeFromSeed();
                spawnSegments();
                return;
            }

            resolveSegments();
            updateFlyState();
            updatePathfinding();

            if (flying) {
                updateFlyPhysics();
            } else {
                updateChainPhysics();
            }

            updateSegmentRotations();
            updateGrabs();
            updateShockCharge();
            syncTrackedData();
        } else {
            bodyDirection = this.dataTracker.get(BODY_DIRECTION);
            shockCharge = this.dataTracker.get(SHOCK_CHARGE);
            moving = this.dataTracker.get(IS_MOVING);
            bodyWave = this.dataTracker.get(BODY_WAVE);
            flying = this.dataTracker.get(IS_FLYING);
            wingsStartedUp = this.dataTracker.get(WINGS_STARTED_UP);

            // Wing animation cycles (client-side)
            lastWingFlapCycle = wingFlapCycle;
            wingFlapCycle = this.dataTracker.get(WING_FLAP_CYCLE);

            lastWingsFolded = wingsFolded;
            wingsFolded = 1f - wingsStartedUp;

            float syncedSize = this.dataTracker.get(CENTIPEDE_SIZE);
            if (Math.abs(syncedSize - this.size) > 0.001f) {
                this.size = syncedSize;
                recalcSizeDerivedFields();
            }
        }
    }

    /**
     * C# Centiwing fly state machine:
     * - wantToFly controls intent
     * - flyModeCounter ramps 0..100 to control transition
     * - wingsStartedUp smoothly follows flyModeCounter
     */
    private void updateFlyState() {
        if (wantToFly) {
            if (flyModeCounter == 100) {
                flying = true;
            }
            flyModeCounter = Math.min(100, flyModeCounter + 1);
        } else {
            if (flyModeCounter < 90) {
                flying = false;
            }
            flyModeCounter = Math.max(0, flyModeCounter - 1);
        }

        float target = MathHelper.clamp((flyModeCounter - 80f) / 20f, 0f, 1f);
        if (wingsStartedUp < target) {
            wingsStartedUp = Math.min(1f, wingsStartedUp + 0.025f);
        } else {
            wingsStartedUp = Math.max(0f, wingsStartedUp - 0.025f);
        }
        wingsStartedUp = MathHelper.lerp(0.05f, wingsStartedUp, target);

        // Wing animation
        lastWingFlapCycle = wingFlapCycle;
        wingFlapCycle += (float) Math.pow(wingsStartedUp, 3f);

        lastWingsFolded = wingsFolded;
        wingsFolded = 1f - wingsStartedUp;
    }

    /**
     * C# Fly(): undulating sine-wave "swimming through air" physics.
     *
     * Key C# behaviors ported:
     * - vel *= 0.9 (dampen)
     * - vel.y += gravity * wingsStartedUp (counteract gravity only)
     * - Body segments get forward thrust toward the segment ahead of them
     * - pos += perp * 2.5 * waveVal — POSITION offset, not velocity, for visible undulation
     * - Head gets thrust toward moveTarget with a DegToVec(bodyWave*10)*60 oscillation wobble
     *   that creates the characteristic swimming flight path
     *
     * 3D adaptation: two perpendicular axes create helical swimming through all 3 dimensions
     * instead of the C# 2D horizontal-only wave.
     */
    private void updateFlyPhysics() {
        bodyWave += 1f;
        moving = true;

        for (int i = 0; i < totalSegments; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;

            float num = (float) i / (float) (totalSegments - 1);
            if (!bodyDirection) num = 1f - num;

            // C#: sin((bodyWave - num * lerp(12,28,size)) * PI * 0.11)
            float wavePhase = (bodyWave - num * MathHelper.lerp(size, 12f, 28f)) * (float) Math.PI * 0.11f;
            float waveVal = (float) Math.sin(wavePhase);
            // Second wave 90° offset for 3D helical swimming
            float waveVal2 = (float) Math.cos(wavePhase);

            // C#: vel *= 0.9
            segments[i].segmentVelocity = segments[i].segmentVelocity.multiply(0.9);

            // C#: vel.y += gravity * wingsStartedUp
            // Since MC gravity is disabled (setNoGravity(true)), we apply manual gravity (−0.06)
            // then counteract it based on wingsStartedUp. Net: 0 when hovering, −0.06 when grounded.
            segments[i].segmentVelocity = segments[i].segmentVelocity.add(0, 0.06 * (wingsStartedUp - 1.0), 0);

            // Body segments (not head/tail) get forward thrust + wave position offset
            if (i > 0 && i < totalSegments - 1) {
                int ahead = bodyDirection ? (i - 1) : (i + 1);
                if (ahead >= 0 && ahead < totalSegments && segments[ahead] != null && !segments[ahead].isRemoved()) {
                    Vec3d toAhead = segments[ahead].getPos().subtract(segments[i].getPos());
                    double len = toAhead.length();
                    if (len > 0.001) {
                        Vec3d dir = toAhead.normalize();

                        // C#: vel += vector * 0.5 * lerp(0.5, 1.5, size)
                        // Forward thrust per body segment — pulls each segment toward the one ahead
                        segments[i].segmentVelocity = segments[i].segmentVelocity.add(
                                dir.multiply(0.045 * MathHelper.lerp(size, 0.5f, 1.5f)));

                        // Compute TWO perpendicular axes for 3D wave displacement.
                        // This is the critical difference from the 2D C# code:
                        // perp1 = horizontal perpendicular, perp2 = vertical perpendicular
                        // Together they create a helical swimming motion in all 3 dimensions.
                        Vec3d perp1 = dir.crossProduct(new Vec3d(0, 1, 0));
                        if (perp1.lengthSquared() < 0.01) {
                            // Direction is nearly vertical — use X axis as reference
                            perp1 = dir.crossProduct(new Vec3d(1, 0, 0));
                        }
                        perp1 = perp1.normalize();

                        Vec3d perp2 = perp1.crossProduct(dir);
                        if (perp2.lengthSquared() > 0.001) {
                            perp2 = perp2.normalize();
                        } else {
                            perp2 = new Vec3d(0, 1, 0);
                        }

                        // C#: pos += perp * 2.5 * waveVal — direct POSITION offset (not velocity!)
                        // 2.5 C# pixels ≈ 0.0625 MC blocks. Use both perpendiculars for 3D swimming.
                        double waveStrength = 0.065;
                        Vec3d waveDisplacement = perp1.multiply(waveVal * waveStrength)
                                .add(perp2.multiply(waveVal2 * waveStrength * 0.5));

                        segments[i].setPosition(segments[i].getPos().add(waveDisplacement));
                    }
                }
            }
        }

        // Head thrust toward moveTarget with swimming wobble.
        // C#: HeadChunk.vel += DirVec(head, moveToPos + DegToVec(bodyWave * 10) * 60) * 4 * lerp(0.7,1.3,size)
        // The DegToVec wobble makes the head trace a circling/swimming path through the air,
        // which the body segments then trail behind in a 3D undulating pattern.
        CentipedeHeadEntity head = getLeadingHead();
        if (head != null && !head.isRemoved()) {
            Vec3d headPos = head.getPos();

            // Wobble: circular oscillation in horizontal + slight vertical
            double wobbleAngle = bodyWave * 10.0 * Math.PI / 180.0;
            double wobbleRadius = 60.0 * PX; // 60 C# pixels → MC blocks
            Vec3d wobble = new Vec3d(
                    Math.cos(wobbleAngle) * wobbleRadius,
                    Math.sin(wobbleAngle * 0.7) * wobbleRadius * 0.3,
                    Math.sin(wobbleAngle) * wobbleRadius);

            Vec3d adjustedTarget = moveTarget.add(wobble);
            Vec3d dir = adjustedTarget.subtract(headPos);
            double dist = dir.length();
            if (dist > 0.3) {
                dir = dir.normalize();
                // C#: 4 * lerp(0.7, 1.3, size) pixels/frame → scaled to MC
                double speed = 0.1 * MathHelper.lerp(size, 0.7f, 1.3f);
                head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed));
            }

            this.setPosition(head.getPos());
        }

        // Stiffness — keeps the chain from folding on itself
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

        // Apply velocity (no surface adhesion when flying)
        for (int i = 0; i < totalSegments; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;
            Vec3d vel = segments[i].segmentVelocity;

            // Decay surface normals when flying (used by renderers for orientation)
            segments[i].surfaceNormalX *= 0.9f;
            segments[i].surfaceNormalY *= 0.9f;
            segments[i].surfaceNormalZ *= 0.9f;

            Vec3d oldPos = segments[i].getPos();
            segments[i].noClip = false;
            segments[i].move(MovementType.SELF, vel);
            segments[i].noClip = true;
            segments[i].segmentVelocity = segments[i].getPos().subtract(oldPos);
        }

        // Chain spacing constraints
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

    private void syncTrackedData() {
        this.dataTracker.set(BODY_DIRECTION, bodyDirection);
        this.dataTracker.set(SHOCK_CHARGE, shockCharge);
        this.dataTracker.set(IS_MOVING, moving);
        this.dataTracker.set(BODY_WAVE, bodyWave);
        this.dataTracker.set(CENTIPEDE_SIZE, size);
        this.dataTracker.set(IS_FLYING, flying);
        this.dataTracker.set(WINGS_STARTED_UP, wingsStartedUp);
        this.dataTracker.set(WING_FLAP_CYCLE, wingFlapCycle);
    }

    // Crawl physics — same as CentipedeEntity
    private void updateChainPhysics() {
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
                        segments[i].segmentVelocity = segments[i].segmentVelocity.add(
                                toAhead.multiply(0.068 * MathHelper.lerp(size, 0.5f, 1.5f)));
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

    private void updateSegmentRotations() {
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
        if (index >= totalSegments - 1 && segments[totalSegments - 2] != null) {
            return segments[totalSegments - 1].getPos().subtract(segments[totalSegments - 2].getPos()).normalize();
        }
        if (index > 0 && index < totalSegments - 1 && segments[index - 1] != null && segments[index + 1] != null) {
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
                pathIndex = 0;
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
        if (flying) {
            // When flying, updateFlyPhysics() handles all movement (head wobble + body thrust).
            // AI goals just need to set moveTarget; no additional thrust here.
            return;
        }

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

        double speed = 0.14 * MathHelper.lerp(size, 0.5f, 1.5f);

        if (isNearSurface(head)) {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed));
        } else {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed * 0.3));
        }

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

    public void driveTowardTarget() {
        // When flying, all physics (head thrust + wobble + body segment thrust)
        // are handled by updateFlyPhysics(). Do NOT apply additional thrust here
        // or it doubles up and the entity rockets out of control.
        if (flying) return;

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
        double speed = 0.14 * MathHelper.lerp(size, 0.5f, 1.5f);
        if (isNearSurface(head)) {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed));
        } else {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed * 0.3));
        }

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

        // C#: Centiwing has higher direction change threshold (40/10 vs 10/2)
        if (flying) return; // Don't change direction while flying

        if (huntTarget == null) return;
        BlockPos targetBlock = huntTarget.getBlockPos();

        CentipedeHeadEntity front = getFrontHead();
        CentipedeHeadEntity rear = getRearHead();
        if (front == null || rear == null) return;

        boolean rearIsCloser = CentipedePathfinder.tileClosestToGoal(
                this.getWorld(), rear.getBlockPos(), front.getBlockPos(), targetBlock);

        if (rearIsCloser) {
            changeDirCounter++;
            if (changeDirCounter > 20) { // C#: Centiwing uses flag ? 40 : 10
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
    // Grab & Shock system (same as CentipedeEntity)
    // =========================================================================

    private void updateGrabs() {
        CentipedeHeadEntity head0 = (segments[0] instanceof CentipedeHeadEntity h) ? h : null;
        CentipedeHeadEntity head1 = (segments[totalSegments - 1] instanceof CentipedeHeadEntity h) ? h : null;

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

    private boolean isValidPrey(LivingEntity entity) {
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

    private void updateShockCharge() {
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
            CentipedeHeadEntity free = head0Grab ? head1 : head0;
            LivingEntity grabbed = grabbing.getGrabbedEntity();

            if (grabbed != null) {
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

    private void shock(LivingEntity victim) {
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

        float shockDamage = MathHelper.lerp(size, 4f, 20f);
        float centipedeMass = totalSegments * 2.0f;
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
        nbt.putBoolean("Flying", flying);
        nbt.putFloat("WingsStartedUp", wingsStartedUp);
        nbt.putInt("FlyModeCounter", flyModeCounter);
        nbt.putBoolean("WantToFly", wantToFly);

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
        if (nbt.contains("Flying")) flying = nbt.getBoolean("Flying");
        if (nbt.contains("WingsStartedUp")) wingsStartedUp = nbt.getFloat("WingsStartedUp");
        if (nbt.contains("FlyModeCounter")) flyModeCounter = nbt.getInt("FlyModeCounter");
        if (nbt.contains("WantToFly")) wantToFly = nbt.getBoolean("WantToFly");

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
        return 0.65f; // C#: Centiwing legs are shorter
    }
}
