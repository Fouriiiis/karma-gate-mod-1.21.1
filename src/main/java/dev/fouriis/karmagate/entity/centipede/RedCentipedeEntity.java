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
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Controller entity for a Red Centipede. This invisible entity manages the chain
 * of CentipedeHeadEntity (2) and CentipedeBodyEntity (16) segments.
 *
 * Behavior (from Rain World C# Centipede):
 * - Crawls along surfaces, with a sinusoidal body wave
 * - Hunts living entities; grabs with one head, wraps around to grab with 2nd head
 * - Once both heads grab the same target, shock charge builds → instakill
 * - Body direction can reverse (either end can be the "leading" head)
 * - Shells on body segments absorb damage and can break off
 *
 * The controller itself is invisible (rendered at 0,0,0 offset or hidden).
 * All visual representation comes from the segment entities.
 */
public class RedCentipedeEntity extends HostileEntity implements GeoAnimatable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedCentipedeEntity.class);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    // --- Configuration ---
    /** Total body segments (not counting heads). C# Red = 18 chunks, we use 16 body + 2 heads */
    public static final int BODY_SEGMENT_COUNT = 16;
    /** Total segments including heads */
    public static final int TOTAL_SEGMENTS = BODY_SEGMENT_COUNT + 2;
    /** Spacing between segments in blocks (C#: ~body chunk radius * 2 ≈ 0.5 blocks) */
    public static final double SEGMENT_SPACING = 0.55;
    /** Size factor (C# Red centipede: size=1.0) */
    public static final float SIZE = 1.0f;

    // --- Tracked data ---
    private static final TrackedData<Boolean> BODY_DIRECTION = DataTracker.registerData(
            RedCentipedeEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> SHOCK_CHARGE = DataTracker.registerData(
            RedCentipedeEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> IS_MOVING = DataTracker.registerData(
            RedCentipedeEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> BODY_WAVE = DataTracker.registerData(
            RedCentipedeEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // --- Segment references (server-side entity IDs, resolved each tick) ---
    private int[] segmentIds = new int[TOTAL_SEGMENTS]; // [0]=front head, [1..16]=body, [17]=rear head
    private CentipedeSegmentEntity[] segments = new CentipedeSegmentEntity[TOTAL_SEGMENTS];
    private boolean segmentsSpawned = false;

    // --- Movement / AI state ---
    private boolean bodyDirection = false; // false=head at index 0 leads; true=head at last index leads
    private Vec3d moveTarget = Vec3d.ZERO;
    private float bodyWave = 0f; // repurposed as walk cycle (C# walkCycle)
    private boolean moving = false;
    private int directionChangeBlock = 0;
    private int changeDirCounter = 0;
    private int noFollowCounter = 0;

    // --- Shock / grab state ---
    private float shockCharge = 0f;
    private int shockGiveUpCounter = 0;
    private float doubleGrabCharge = 0f;

    // --- AI behavior ---
    private LivingEntity huntTarget = null;

    // --- Run/excitement (from C# AI) ---
    private float excitement = 0f;
    private float run = 500f; // Red centipedes always run

    public RedCentipedeEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        // Controller is invisible and non-physical; only segments have hitboxes
        this.noClip = true;
        this.setNoGravity(true);
        java.util.Arrays.fill(segmentIds, -1);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 80.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.35)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 6.0)
                .add(EntityAttributes.GENERIC_ARMOR, 8.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.8);
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
        // Custom centipede AI goals
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new CentipedeShockGoal(this));
        this.goalSelector.add(2, new CentipedeHuntGoal(this));
        this.goalSelector.add(3, new CentipedeWanderGoal(this));
        this.goalSelector.add(4, new LookAroundGoal(this));

        this.targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, LivingEntity.class, 10, true, false,
                entity -> !(entity instanceof RedCentipedeEntity)
                        && !(entity instanceof CentipedeSegmentEntity)
                        && entity.getType().getSpawnGroup().isPeaceful()));
    }

    // =========================================================================
    // Segment management
    // =========================================================================

    /**
     * Spawn all segment entities on the first server tick.
     */
    private void spawnSegments() {
        if (this.getWorld().isClient || segmentsSpawned) return;
        ServerWorld sw = (ServerWorld) this.getWorld();

        Vec3d basePos = this.getPos();
        // Get yaw direction for initial chain layout
        float yawRad = this.getYaw() * ((float) Math.PI / 180f);
        Vec3d forward = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));

        for (int i = 0; i < TOTAL_SEGMENTS; i++) {
            Vec3d segPos = basePos.subtract(forward.multiply(i * SEGMENT_SPACING));
            CentipedeSegmentEntity seg;

            if (i == 0 || i == TOTAL_SEGMENTS - 1) {
                // Head segments
                EntityType<?> headType = dev.fouriis.karmagate.KarmaGateMod.CENTIPEDE_HEAD_ENTITY_TYPE;
                CentipedeHeadEntity head = (CentipedeHeadEntity) headType.create(sw);
                if (head == null) continue;
                head.setFrontHead(i == 0);
                seg = head;
            } else {
                // Body segments
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
    }

    /**
     * Resolve segment entity references from IDs (handles save/load).
     */
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

    public CentipedeHeadEntity getFrontHead() {
        int idx = bodyDirection ? (TOTAL_SEGMENTS - 1) : 0;
        if (segments[idx] instanceof CentipedeHeadEntity h) return h;
        return null;
    }

    public CentipedeHeadEntity getRearHead() {
        int idx = bodyDirection ? 0 : (TOTAL_SEGMENTS - 1);
        if (segments[idx] instanceof CentipedeHeadEntity h) return h;
        return null;
    }

    /**
     * Get the "leading" head (the one that chases the target).
     */
    public CentipedeHeadEntity getLeadingHead() {
        return getFrontHead();
    }

    /**
     * Get the head index in the segments array that leads.
     */
    public int getHeadIndex() {
        return bodyDirection ? (TOTAL_SEGMENTS - 1) : 0;
    }

    public CentipedeSegmentEntity[] getSegments() {
        return segments;
    }

    /**
     * Get the walk cycle phase. Used by client-side leg renderers.
     * C# walkCycle: advances by ±1/10 per tick when moving.
     */
    public float getWalkCycle() {
        return bodyWave;
    }

    /**
     * Whether the body is moving in the "reverse" direction (rear head leads).
     * C# centipede.bodyDirection.
     */
    public boolean isBodyDirection() {
        return bodyDirection;
    }

    /**
     * Register a segment on the client side so renderers can find neighbors.
     */
    public void registerClientSegment(CentipedeSegmentEntity seg) {
        int idx = seg.getSegmentIndex();
        if (idx >= 0 && idx < TOTAL_SEGMENTS) {
            segments[idx] = seg;
            segmentIds[idx] = seg.getId();
        }
    }

    public boolean areSegmentsSpawned() {
        return segmentsSpawned;
    }

    // =========================================================================
    // Tick / Physics / Movement
    // =========================================================================

    @Override
    public void tick() {
        // Ensure controller stays non-physical
        this.noClip = true;
        super.tick();

        if (!this.getWorld().isClient) {
            // Spawn segments on first tick
            if (!segmentsSpawned) {
                spawnSegments();
                return;
            }

            resolveSegments();
            updateChainPhysics();
            updateSegmentRotations();
            updateGrabs();
            updateShockCharge();
            syncTrackedData();
        } else {
            // Client: read tracked data
            bodyDirection = this.dataTracker.get(BODY_DIRECTION);
            shockCharge = this.dataTracker.get(SHOCK_CHARGE);
            moving = this.dataTracker.get(IS_MOVING);
            bodyWave = this.dataTracker.get(BODY_WAVE);
        }
    }

    // --- Environment protection (controller is non-physical) ---

    @Override
    public boolean isInsideWall() {
        return false;
    }

    @Override
    public boolean isFireImmune() {
        return true;
    }

    private void syncTrackedData() {
        this.dataTracker.set(BODY_DIRECTION, bodyDirection);
        this.dataTracker.set(SHOCK_CHARGE, shockCharge);
        this.dataTracker.set(IS_MOVING, moving);
        this.dataTracker.set(BODY_WAVE, bodyWave);
    }

    /**
     * Chain physics: keep segments spaced, apply crawl propulsion.
     * Follows C# Centipede.Update() for rope/chain constraints
     * and Centipede.Crawl() for movement (NOT Fly() — no body wave).
     * Uses Entity.move() for proper Minecraft block collision.
     */
    private void updateChainPhysics() {
        // Move controller entity to the leading head position
        CentipedeHeadEntity lead = getLeadingHead();
        if (lead != null && !lead.isRemoved()) {
            this.setPosition(lead.getPos());
        }

        // Update walk cycle (C# walkCycle += bodyDirection ? -1 : 1 / 10)
        if (moving) {
            bodyWave += (bodyDirection ? -1f : 1f) * 0.1f;
        }

        // --- Crawl propulsion (from C# Crawl method) ---
        // Body segments push toward the segment ahead when moving
        if (moving) {
            for (int i = 1; i < TOTAL_SEGMENTS - 1; i++) {
                if (segments[i] == null || segments[i].isRemoved()) continue;
                if (!isOnGround(segments[i]) && !isNearWall(segments[i])) continue;

                // Push toward the segment ahead (toward head)
                int ahead = bodyDirection ? (i + 1) : (i - 1);
                int behind = bodyDirection ? (i - 1) : (i + 1);

                if (ahead >= 0 && ahead < TOTAL_SEGMENTS && segments[ahead] != null && !segments[ahead].isRemoved()) {
                    if (isOnGround(segments[ahead]) || isNearWall(segments[ahead])) {
                        Vec3d toAhead = segments[ahead].getPos().subtract(segments[i].getPos()).normalize();
                        segments[i].segmentVelocity = segments[i].segmentVelocity.add(toAhead.multiply(0.075));
                    }
                }

                // Pull away from the segment behind
                if (behind >= 0 && behind < TOTAL_SEGMENTS && segments[behind] != null && !segments[behind].isRemoved()) {
                    Vec3d toBehind = segments[behind].getPos().subtract(segments[i].getPos()).normalize();
                    segments[i].segmentVelocity = segments[i].segmentVelocity.subtract(toBehind.multiply(0.04));
                }
            }
        }

        // --- Stiffness: push non-adjacent segments apart (C# bodyChunkConnections push) ---
        float stiffnessForce = (float)(MathHelper.lerp(shockCharge, 1.0, 6.0) * MathHelper.lerp(SIZE, 1.0, 2.0));
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

        // --- Apply velocity, gravity, damping to each segment using Minecraft collision ---
        for (int i = 0; i < TOTAL_SEGMENTS; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;

            Vec3d vel = segments[i].segmentVelocity;
            boolean grounded = isOnGround(segments[i]);
            boolean walled = isNearWall(segments[i]);

            // Gravity
            vel = vel.add(0, -0.04, 0);

            // C# Crawl: on accessible tile, vel *= 0.7 and cancel gravity
            if (grounded || walled) {
                vel = vel.multiply(0.7);
                vel = new Vec3d(vel.x, Math.max(vel.y, 0), vel.z);
                if (walled && !grounded) {
                    vel = new Vec3d(vel.x, vel.y + 0.04, vel.z);
                }
            } else {
                vel = vel.multiply(0.92);
            }

            // Use Minecraft's collision system via Entity.move()
            Vec3d oldPos = segments[i].getPos();
            segments[i].noClip = false;
            segments[i].move(MovementType.SELF, vel);
            segments[i].noClip = true;

            // Update velocity to reflect actual movement (collision may have truncated it)
            Vec3d actualMovement = segments[i].getPos().subtract(oldPos);
            segments[i].segmentVelocity = actualMovement;
        }

        // --- Chain constraints: enforce spacing (C# connectionRopes) ---
        // Multiple iterations for convergence
        for (int iter = 0; iter < 3; iter++) {
            for (int i = 0; i < TOTAL_SEGMENTS - 1; i++) {
                enforceSpacing(i, i + 1);
            }
            for (int i = TOTAL_SEGMENTS - 2; i >= 0; i--) {
                enforceSpacing(i, i + 1);
            }
        }

        // --- Update leg positions for each segment ---
        // Leg positions are tracked client-side in CentipedeLegRenderer for smooth rendering
    }

    /**
     * Prevent segments from clipping through solid blocks.
     * If the new position is inside a solid block, push the segment to the nearest face.
    /**
     * Compute and set the yaw and pitch for each segment based on chain direction.
     * This is used by the client-side renderers via bodyYaw.
     */
    private void updateSegmentRotations() {
        for (int i = 0; i < TOTAL_SEGMENTS; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;

            Vec3d dir = getChainDirection(i);

            // Compute yaw from horizontal direction
            float yaw = (float) (Math.atan2(-dir.x, dir.z) * (180.0 / Math.PI));

            // For the rear head, flip 180 degrees
            if (i == TOTAL_SEGMENTS - 1) {
                yaw += 180f;
            }

            // Set entity yaw/bodyYaw so GeckoLib picks it up
            segments[i].prevBodyYaw = segments[i].bodyYaw;
            segments[i].bodyYaw = yaw;
            segments[i].prevYaw = segments[i].getYaw();
            segments[i].setYaw(yaw);
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

        double error = dist - SEGMENT_SPACING;
        Vec3d correction = diff.normalize().multiply(error * 0.5);

        // Move both segments toward each other using collision-aware movement
        moveWithCollision(segments[a], correction);
        moveWithCollision(segments[b], correction.negate());

        // Also adjust velocities
        Vec3d velCorrection = correction.multiply(0.5);
        segments[a].segmentVelocity = segments[a].segmentVelocity.add(velCorrection);
        segments[b].segmentVelocity = segments[b].segmentVelocity.subtract(velCorrection);
    }

    /**
     * Move a segment using Minecraft's collision system.
     * Temporarily disables noClip so Entity.move() checks block collisions.
     */
    private void moveWithCollision(CentipedeSegmentEntity seg, Vec3d movement) {
        seg.noClip = false;
        seg.move(MovementType.SELF, movement);
        seg.noClip = true;
    }

    private Vec3d getChainDirection(int index) {
        if (index <= 0 && segments[1] != null) {
            return segments[1].getPos().subtract(segments[0].getPos()).normalize();
        }
        if (index >= TOTAL_SEGMENTS - 1 && segments[TOTAL_SEGMENTS - 2] != null) {
            return segments[TOTAL_SEGMENTS - 1].getPos().subtract(segments[TOTAL_SEGMENTS - 2].getPos()).normalize();
        }
        if (segments[index - 1] != null && segments[index + 1] != null) {
            return segments[index + 1].getPos().subtract(segments[index - 1].getPos()).normalize();
        }
        return new Vec3d(1, 0, 0);
    }

    private boolean isOnGround(Entity entity) {
        BlockPos below = entity.getBlockPos().down();
        BlockState state = entity.getWorld().getBlockState(below);
        return state.isSolidBlock(entity.getWorld(), below);
    }

    private boolean isNearWall(Entity entity) {
        BlockPos pos = entity.getBlockPos();
        World world = entity.getWorld();
        return world.getBlockState(pos.north()).isSolidBlock(world, pos.north())
                || world.getBlockState(pos.south()).isSolidBlock(world, pos.south())
                || world.getBlockState(pos.east()).isSolidBlock(world, pos.east())
                || world.getBlockState(pos.west()).isSolidBlock(world, pos.west());
    }

    // =========================================================================
    // Movement API (called by AI goals)
    // =========================================================================

    /**
     * Set the target position the leading head should move toward.
     * Called by AI goals.
     */
    public void setMoveTarget(Vec3d target) {
        this.moveTarget = target;
        this.moving = true;
    }

    public void stopMoving() {
        this.moving = false;
    }

    public boolean isMoving() {
        return moving;
    }

    /**
     * Drive the leading head toward the move target.
     * Called each tick by the crawl AI goal.
     * Mirrors C# Centipede.Crawl() and Centipede.Act() logic.
     */
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

        // C# Crawl: HeadChunk.vel += DirVec(HeadChunk.pos, moveToPos) * LerpMap(num, 0, bodyChunks.Length, 6, 3) * Lerp(0.7, 1.3, size)
        // For Red centipede (size=1.0), that's about 3 * 1.3 = 3.9 in C# units
        // Convert to MC: 3.9 * (1/20) pixels ≈ 0.195 blocks/tick
        double speed = 0.18 * SIZE * 1.25; // Red centipede speed boost

        // Check if head is on accessible ground
        if (isOnGround(head) || isNearWall(head)) {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed));
        } else {
            // In air, less control
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed * 0.3));
        }

        // Body propulsion: middle segments push toward the direction of travel (C# Crawl body wave)
        int leadIdx = getHeadIndex();
        for (int i = 1; i < TOTAL_SEGMENTS - 1; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;
            if (!isOnGround(segments[i]) && !isNearWall(segments[i])) continue;

            // Determine direction toward the segment in front (toward head)
            int inFront = bodyDirection ? (i + 1) : (i - 1);
            if (inFront < 0 || inFront >= TOTAL_SEGMENTS) continue;
            if (segments[inFront] == null) continue;

            Vec3d towardFront = segments[inFront].getPos().subtract(segments[i].getPos()).normalize();
            // C#: bodyChunks[i].vel += DirVec(...) * 1.5 * Lerp(0.5, 1.5, size) * 1.25
            segments[i].segmentVelocity = segments[i].segmentVelocity.add(towardFront.multiply(0.06));
        }
    }

    /**
     * Handle body direction changes (C# Act() directionChange logic).
     * If the rear head is closer to the target, swap direction.
     */
    public void updateDirectionChange() {
        if (directionChangeBlock > 0) {
            if (moving) directionChangeBlock--;
            return;
        }

        if (huntTarget == null) return;
        Vec3d targetPos = huntTarget.getPos();

        CentipedeHeadEntity front = getFrontHead();
        CentipedeHeadEntity rear = getRearHead();
        if (front == null || rear == null) return;

        double frontDist = front.getPos().squaredDistanceTo(targetPos);
        double rearDist = rear.getPos().squaredDistanceTo(targetPos);

        if (rearDist < frontDist) {
            changeDirCounter++;
            if (changeDirCounter > 5) {
                bodyDirection = !bodyDirection;
                directionChangeBlock = 40;
                changeDirCounter = 0;
            }
        } else {
            changeDirCounter = 0;
        }
    }

    // =========================================================================
    // Grab & Shock system (core combat mechanic)
    // =========================================================================

    /**
     * Called each tick to check for entities to grab with head segments.
     * Mirrors C# Centipede.Collide() — grab on collision with prey.
     */
    private void updateGrabs() {
        CentipedeHeadEntity head0 = (segments[0] instanceof CentipedeHeadEntity h) ? h : null;
        CentipedeHeadEntity head1 = (segments[TOTAL_SEGMENTS - 1] instanceof CentipedeHeadEntity h) ? h : null;

        // Check for entities near each head for grabbing
        if (head0 != null) checkHeadGrab(head0);
        if (head1 != null) checkHeadGrab(head1);

        // If one head has grabbed, drive the other head toward the grabbed entity
        // (C# UpdateGrasp: when grasps[1-g] == null, move other head toward grabbed)
        CentipedeHeadEntity leadingH = getLeadingHead();
        CentipedeHeadEntity rearH = getRearHead();

        if (leadingH != null && rearH != null) {
            if (leadingH.isGrabbing() && !rearH.isGrabbing()) {
                // Drive rear head toward the grabbed entity
                LivingEntity grabbed = leadingH.getGrabbedEntity();
                if (grabbed != null) {
                    moveTarget = grabbed.getPos();
                    moving = true;
                    // Swap direction so the non-grabbing head leads toward the target
                    if (directionChangeBlock <= 0) {
                        bodyDirection = !bodyDirection;
                        directionChangeBlock = 60;
                    }
                    // Also directly push rear head toward target  
                    Vec3d dir = grabbed.getPos().subtract(rearH.getPos()).normalize();
                    rearH.segmentVelocity = rearH.segmentVelocity.add(dir.multiply(0.2 * Math.pow(doubleGrabCharge, 2)));
                    doubleGrabCharge = Math.min(1.0f, doubleGrabCharge + 0.0125f);
                }
            } else if (rearH.isGrabbing() && !leadingH.isGrabbing()) {
                LivingEntity grabbed = rearH.getGrabbedEntity();
                if (grabbed != null) {
                    moveTarget = grabbed.getPos();
                    moving = true;
                    if (directionChangeBlock <= 0) {
                        bodyDirection = !bodyDirection;
                        directionChangeBlock = 60;
                    }
                    Vec3d dir = grabbed.getPos().subtract(leadingH.getPos()).normalize();
                    leadingH.segmentVelocity = leadingH.segmentVelocity.add(dir.multiply(0.2 * Math.pow(doubleGrabCharge, 2)));
                    doubleGrabCharge = Math.min(1.0f, doubleGrabCharge + 0.0125f);
                }
            } else if (!leadingH.isGrabbing() && !rearH.isGrabbing()) {
                doubleGrabCharge = Math.max(0, doubleGrabCharge - 0.025f);
                shockGiveUpCounter = Math.max(0, shockGiveUpCounter - 2);
            }
        }

        // Shock give-up: if both heads fail to grab for too long, release
        if (doubleGrabCharge > 0.9f) {
            shockGiveUpCounter++;
            if (shockGiveUpCounter >= 110) {
                if (head0 != null) head0.releaseGrab();
                if (head1 != null) head1.releaseGrab();
                shockGiveUpCounter = 30;
                doubleGrabCharge = 0;
            }
        }
    }

    /**
     * Check if a head should grab a nearby entity.
     * C# Collide: grabs on collision with prey, plays attach sound.
     */
    private void checkHeadGrab(CentipedeHeadEntity head) {
        if (head.isGrabbing()) return;

        // Find entities near this head
        Box searchBox = head.getBoundingBox().expand(0.8);
        List<LivingEntity> nearby = this.getWorld().getEntitiesByClass(
                LivingEntity.class, searchBox,
                e -> isValidPrey(e));

        for (LivingEntity target : nearby) {
            if (head.tryGrab(target)) {
                // C#: room.PlaySound(SoundID.Centipede_Attach, ...)
                this.getWorld().playSound(null, head.getBlockPos(),
                        SoundEvents.ENTITY_SPIDER_AMBIENT, this.getSoundCategory(), 0.6f, 1.8f);
                break;
            }
        }
    }

    /**
     * Check if an entity is valid prey for the centipede.
     */
    private boolean isValidPrey(LivingEntity entity) {
        if (entity == this) return false;
        if (entity instanceof CentipedeSegmentEntity) return false;
        if (entity instanceof RedCentipedeEntity) return false;
        if (entity.isRemoved() || entity.isDead()) return false;
        if (entity.isInvulnerable()) return false;
        return true;
    }

    /**
     * Update shock charge. When both heads grab the same (or any) target,
     * charge builds. At full charge → shock → instakill.
     * From C# UpdateGrasp: shockCharge += 1/Lerp(100,5,size)
     * For Red (size=1): 1/5 = 0.2 per tick → 5 ticks to charge
     * In MC at 20tps that's 0.25 seconds which may be too fast; we slow it down.
     */
    private void updateShockCharge() {
        CentipedeHeadEntity head0 = (segments[0] instanceof CentipedeHeadEntity h) ? h : null;
        CentipedeHeadEntity head1 = (segments[TOTAL_SEGMENTS - 1] instanceof CentipedeHeadEntity h) ? h : null;

        if (head0 == null || head1 == null) return;

        boolean head0Grab = head0.isGrabbing();
        boolean head1Grab = head1.isGrabbing();

        if (head0Grab && head1Grab) {
            // Both heads grabbing — check if they're grabbing the same target or are close enough
            LivingEntity target0 = head0.getGrabbedEntity();
            LivingEntity target1 = head1.getGrabbedEntity();

            boolean sameTarget = (target0 == target1);
            boolean headsCloseToTarget = false;

            if (!sameTarget && target0 != null) {
                // Check if head1 is close to target0's body
                headsCloseToTarget = head1.getPos().distanceTo(target0.getPos())
                        < target0.getWidth() + 1.5;
            }
            if (!sameTarget && target1 != null && !headsCloseToTarget) {
                headsCloseToTarget = head0.getPos().distanceTo(target1.getPos())
                        < target1.getWidth() + 1.5;
            }

            if (sameTarget || headsCloseToTarget) {
                // C#: shockCharge += 1 / Lerp(100, 5, size) → for Red (size=1): 0.2/tick
                // Slow it down for Minecraft: charge over ~2 seconds (40 ticks)
                shockCharge += 0.025f;

                if (shockCharge >= 1.0f) {
                    // SHOCK — instakill!
                    LivingEntity victim = (target0 != null) ? target0 : target1;
                    shock(victim);
                    shockCharge = 0;
                    head0.releaseGrab();
                    head1.releaseGrab();
                }
            }
        } else if (head0Grab || head1Grab) {
            // Only one head grabbing — check if the other head is near the grabbed entity's body
            CentipedeHeadEntity grabbing = head0Grab ? head0 : head1;
            CentipedeHeadEntity free = head0Grab ? head1 : head0;
            LivingEntity grabbed = grabbing.getGrabbedEntity();

            if (grabbed != null) {
                double dist = free.getPos().distanceTo(grabbed.getPos());
                if (dist < grabbed.getWidth() + 1.0) {
                    // Free head is touching the grabbed entity — build charge!
                    // C# UpdateGrasp: shockCharge += 1/Lerp(100,5,size)
                    shockCharge += 0.025f;
                    if (shockCharge >= 1.0f) {
                        shock(grabbed);
                        shockCharge = 0;
                        grabbing.releaseGrab();
                    }
                }
            }
        } else {
            // No grabs — decay charge
            shockCharge = Math.max(0, shockCharge - 1f / 60f);
        }
    }

    /**
     * Shock a victim entity — instakill for smaller creatures, heavy stun for larger.
     * From C# Centipede.Shock().
     */
    private void shock(LivingEntity victim) {
        if (victim == null || victim.isRemoved()) return;

        // Play shock sound
        this.getWorld().playSound(null, this.getBlockPos(),
                SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, this.getSoundCategory(), 1.0f, 1.5f);

        // Spark particles
        if (this.getWorld() instanceof ServerWorld sw) {
            Vec3d victimPos = victim.getPos();
            for (int i = 0; i < 15; i++) {
                sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK,
                        victimPos.x + (random.nextDouble() - 0.5) * 2,
                        victimPos.y + random.nextDouble() * victim.getHeight(),
                        victimPos.z + (random.nextDouble() - 0.5) * 2,
                        1, 0.3, 0.3, 0.3, 0.1);
            }
        }

        // Knockback all segments (C#: bodyChunks[j].vel += RNV() * 6)
        for (CentipedeSegmentEntity seg : segments) {
            if (seg != null && !seg.isRemoved()) {
                seg.segmentVelocity = seg.segmentVelocity.add(
                        (random.nextDouble() - 0.5) * 0.3,
                        (random.nextDouble() - 0.5) * 0.3,
                        (random.nextDouble() - 0.5) * 0.3);
            }
        }

        // C#: if TotalMass < centipede.TotalMass → Die()
        // Red centipede always instakills smaller creatures
        float centipedeMass = TOTAL_SEGMENTS * 2.0f; // Rough mass approximation
        if (victim.getWidth() * victim.getHeight() * 10 < centipedeMass) {
            // Instakill
            victim.damage(this.getDamageSources().mobAttack(this), Float.MAX_VALUE);
        } else {
            // Heavy damage + stun
            victim.damage(this.getDamageSources().mobAttack(this), 20.0f);
            // Apply slowness as "stun"
            victim.setVelocity(
                    (random.nextDouble() - 0.5) * 0.5,
                    0.3,
                    (random.nextDouble() - 0.5) * 0.5);
        }
    }

    // =========================================================================
    // Target management
    // =========================================================================

    public void setHuntTarget(LivingEntity target) {
        this.huntTarget = target;
    }

    public LivingEntity getHuntTarget() {
        return huntTarget;
    }

    // =========================================================================
    // Death / cleanup
    // =========================================================================

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        // Kill all segments
        for (CentipedeSegmentEntity seg : segments) {
            if (seg != null && !seg.isRemoved()) {
                seg.discard();
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        for (CentipedeSegmentEntity seg : segments) {
            if (seg != null && !seg.isRemoved()) {
                seg.discard();
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
    // GeckoLib (controller entity is invisible but still needs the interface)
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
}
