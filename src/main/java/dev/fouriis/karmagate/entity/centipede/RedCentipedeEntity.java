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
import net.minecraft.entity.mob.MobEntity;
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
import net.minecraft.world.GameMode;
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

    // --- Pathfinding (from C# PathFinder → StandardPather → CentipedePather) ---
    private CentipedePathfinder.IncrementalSearch currentSearch = null;
    private List<BlockPos> currentPath = null;
    private int pathIndex = 0;
    private int pathRecalcTimer = 0;
    private BlockPos lastPathGoal = null;
    /** How many A* nodes to process per tick (C# stepsPerFrame) */
    private static final int PATH_STEPS_PER_TICK = 80;
    /** Ticks between path recalculations */
    private static final int PATH_RECALC_INTERVAL = 30;
    /** Look-ahead distance for path following (smoother movement) */
    private static final int PATH_LOOK_AHEAD = 3;
    /** Waypoints within this distance are considered "reached" */
    private static final double WAYPOINT_REACH_DIST = 1.5;

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

        // Only target players in survival mode
        this.targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false,
                entity -> {
                    if (entity instanceof PlayerEntity player) {
                        if (player.isCreative() || player.isSpectator()) return false;
                    }
                    return true;
                }));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, LivingEntity.class, 10, true, false,
                entity -> !(entity instanceof RedCentipedeEntity)
                        && !(entity instanceof CentipedeSegmentEntity)
                        && !(entity instanceof PlayerEntity p && (p.isCreative() || p.isSpectator()))
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
            updatePathfinding();
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

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        // Centipedes are immune to fall damage (wall/ceiling crawlers)
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Immune to suffocation and fall damage
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

    /**
     * Chain physics: keep segments spaced, apply crawl propulsion.
     * Follows C# Centipede.Update() for rope/chain constraints
     * and Centipede.Crawl() for movement.
     * Uses Entity.move() for proper Minecraft block collision.
     * Supports wall and ceiling crawling like C# AccessibleTile().
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
                if (!isNearSurface(segments[i])) continue;

                // Push toward the segment ahead (toward head)
                int ahead = bodyDirection ? (i + 1) : (i - 1);
                int behind = bodyDirection ? (i - 1) : (i + 1);

                if (ahead >= 0 && ahead < TOTAL_SEGMENTS && segments[ahead] != null && !segments[ahead].isRemoved()) {
                    if (isNearSurface(segments[ahead])) {
                        Vec3d toAhead = segments[ahead].getPos().subtract(segments[i].getPos()).normalize();
                        // C#: vel += DirVec(...) * 1.5 * Lerp(0.5,1.5,size) * 1.25 for Red
                        segments[i].segmentVelocity = segments[i].segmentVelocity.add(toAhead.multiply(0.085));
                    }
                }

                // Pull away from the segment behind (C#: vel -= DirVec * 0.8 * Lerp(...))
                if (behind >= 0 && behind < TOTAL_SEGMENTS && segments[behind] != null && !segments[behind].isRemoved()) {
                    Vec3d toBehind = segments[behind].getPos().subtract(segments[i].getPos()).normalize();
                    segments[i].segmentVelocity = segments[i].segmentVelocity.subtract(toBehind.multiply(0.05));
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

        // --- Apply velocity, gravity, surface adhesion to each segment ---
        for (int i = 0; i < TOTAL_SEGMENTS; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;

            Vec3d vel = segments[i].segmentVelocity;

            // Compute surface normal for this segment (for wall/ceiling crawling)
            Vec3d surfaceNormal = computeSurfaceNormal(segments[i]);
            boolean onSurface = surfaceNormal.lengthSquared() > 0.01;

            if (onSurface) {
                // C# Crawl: on accessible tile, vel *= 0.7 and cancel gravity entirely
                vel = vel.multiply(0.7);

                // Adhere to surface: push segment toward surface (cancel gravity + stick)
                // This allows ceiling and wall crawling
                vel = vel.subtract(surfaceNormal.multiply(0.06));

                // Store surface normal on segment for renderer roll computation
                segments[i].surfaceNormalX = (float) surfaceNormal.x;
                segments[i].surfaceNormalY = (float) surfaceNormal.y;
                segments[i].surfaceNormalZ = (float) surfaceNormal.z;
            } else {
                // In air: apply gravity, less friction
                vel = vel.add(0, -0.06, 0);
                vel = vel.multiply(0.92);

                // Decay surface normal
                segments[i].surfaceNormalX *= 0.9f;
                segments[i].surfaceNormalY *= 0.9f;
                segments[i].surfaceNormalZ *= 0.9f;
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
    }

    /**
     * Compute and set the yaw, pitch, and surface normal for each segment.
     * Used by client-side renderers for full pitch/yaw/roll orientation.
     * Mirrors C# CentipedeGraphics.bodyRotations / RotatAtChunk().
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

            // Compute pitch from vertical component of chain direction
            float pitch = (float) (Math.asin(MathHelper.clamp(dir.y, -1, 1)) * (180.0 / Math.PI));

            // Set entity yaw/bodyYaw so GeckoLib picks it up
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

    /**
     * Check if a segment is near any surface (floor, wall, or ceiling).
     * Mirrors C# AccessibleTile / ClimbableTile — centipedes can use any adjacent surface.
     */
    private boolean isNearSurface(Entity entity) {
        BlockPos pos = entity.getBlockPos();
        World world = entity.getWorld();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (world.getBlockState(neighbor).isSolidBlock(world, neighbor)) {
                return true;
            }
        }
        // Also check one block below with a slightly larger range
        BlockPos below = entity.getBlockPos().down();
        if (world.getBlockState(below).isSolidBlock(world, below)) return true;
        return false;
    }

    /**
     * Compute the surface normal for a segment based on nearby solid blocks.
     * This is a weighted average of directions pointing AWAY from nearby solid surfaces.
     * Mirrors C# CentipedeGraphics.BestBodyRotatAtChunk() which checks perpendicular
     * directions for solids and orients the body accordingly.
     * Returns Vec3d.ZERO if not near any surface.
     */
    private Vec3d computeSurfaceNormal(Entity entity) {
        BlockPos pos = entity.getBlockPos();
        World world = entity.getWorld();
        Vec3d normal = Vec3d.ZERO;
        int count = 0;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (world.getBlockState(neighbor).isSolidBlock(world, neighbor)) {
                // Surface detected: normal points AWAY from the solid block
                normal = normal.add(
                    -dir.getOffsetX(),
                    -dir.getOffsetY(),
                    -dir.getOffsetZ()
                );
                count++;
            }
        }

        // Also check below for ground (common case)
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
        this.currentPath = null;
        this.currentSearch = null;
    }

    public boolean isMoving() {
        return moving;
    }

    // =========================================================================
    // Pathfinding API (from C# PathFinder → StandardPather → CentipedePather)
    // =========================================================================

    /**
     * Request a new path to the given goal position.
     * Uses the incremental pathfinder that spreads computation across ticks.
     * Mirrors C# PathFinder.Update() with stepsPerFrame.
     */
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

    /**
     * Request a path to a Vec3d target (converts to BlockPos).
     */
    public void requestPathTo(Vec3d target) {
        requestPath(BlockPos.ofFloored(target.x, target.y, target.z));
    }

    /**
     * Update the incremental pathfinder search each tick.
     * Mirrors C# PathFinder.Update() main loop.
     */
    private void updatePathfinding() {
        // Process incremental search steps
        if (currentSearch != null && !currentSearch.isFinished()) {
            currentSearch.step(PATH_STEPS_PER_TICK);

            if (currentSearch.isFinished()) {
                currentPath = currentSearch.getPath();
                pathIndex = 0;
                currentSearch = null;
            }
        }

        // Periodic path recalculation
        if (pathRecalcTimer > 0) {
            pathRecalcTimer--;
        }
    }

    /**
     * Check if the current path needs recalculation.
     * Returns true if:
     * - No path exists
     * - Goal has moved significantly
     * - Path has become invalid (blocked by world changes)
     * - Recalc timer has expired
     */
    public boolean needsPathRecalc(Vec3d goalPos) {
        if (currentPath == null || currentPath.isEmpty()) return true;
        if (pathRecalcTimer > 0) return false;

        // Check if goal moved significantly
        if (lastPathGoal != null) {
            BlockPos newGoal = BlockPos.ofFloored(goalPos.x, goalPos.y, goalPos.z);
            if (newGoal.getManhattanDistance(lastPathGoal) > 3) return true;
        }

        // Check if path is still valid (sample a few waypoints)
        if (!CentipedePathfinder.isPathValid(this.getWorld(), currentPath)) return true;

        return false;
    }

    /**
     * Get the current path (may be null if no path computed yet).
     */
    public List<BlockPos> getCurrentPath() {
        return currentPath;
    }

    /**
     * Check if a search is currently in progress.
     */
    public boolean isSearching() {
        return currentSearch != null && !currentSearch.isFinished();
    }

    /**
     * Follow the current path, driving segment velocity toward the next waypoint.
     * Mirrors C# StandardPather.FollowPath() + Centipede.Crawl().
     *
     * Called by AI goals instead of the old direct driveTowardTarget().
     */
    public void followCurrentPath() {
        if (currentPath == null || currentPath.isEmpty()) {
            // No path available — fall back to direct movement
            driveTowardTarget();
            return;
        }

        CentipedeHeadEntity head = getLeadingHead();
        if (head == null || head.isRemoved()) return;

        Vec3d headPos = head.getPos();

        // Find the next waypoint to move toward using path following
        // (mirrors C# StandardPather.FollowPath finding best connection)
        BlockPos nextWaypoint = CentipedePathfinder.followPath(
                currentPath, headPos, PATH_LOOK_AHEAD);

        if (nextWaypoint == null) {
            // Path exhausted
            currentPath = null;
            moving = false;
            return;
        }

        // Check if we've reached the end of the path
        BlockPos lastWaypoint = currentPath.get(currentPath.size() - 1);
        double distToEnd = headPos.squaredDistanceTo(
                lastWaypoint.getX() + 0.5, lastWaypoint.getY() + 0.5, lastWaypoint.getZ() + 0.5);
        if (distToEnd < WAYPOINT_REACH_DIST * WAYPOINT_REACH_DIST) {
            currentPath = null;
            moving = false;
            return;
        }

        // Drive toward the waypoint
        Vec3d waypointCenter = new Vec3d(
                nextWaypoint.getX() + 0.5,
                nextWaypoint.getY() + 0.5,
                nextWaypoint.getZ() + 0.5);

        Vec3d dir = waypointCenter.subtract(headPos);
        double dist = dir.length();

        if (dist < 0.1) return;
        dir = dir.normalize();

        // C# Crawl: HeadChunk.vel += DirVec(HeadChunk.pos, moveToPos) * speed
        double speed = 0.18 * SIZE * 1.25;

        if (isNearSurface(head)) {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed));
        } else {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed * 0.3));
        }

        // Body propulsion: middle segments push along path direction
        for (int i = 1; i < TOTAL_SEGMENTS - 1; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;
            if (!isNearSurface(segments[i])) continue;

            int inFront = bodyDirection ? (i + 1) : (i - 1);
            if (inFront < 0 || inFront >= TOTAL_SEGMENTS) continue;
            if (segments[inFront] == null) continue;

            Vec3d towardFront = segments[inFront].getPos().subtract(segments[i].getPos()).normalize();
            segments[i].segmentVelocity = segments[i].segmentVelocity.add(towardFront.multiply(0.06));
        }
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

        // Check if head is on any accessible surface (floor, wall, or ceiling)
        if (isNearSurface(head)) {
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed));
        } else {
            // In air, less control
            head.segmentVelocity = head.segmentVelocity.add(dir.multiply(speed * 0.3));
        }

        // Body propulsion: middle segments push toward the direction of travel (C# Crawl body wave)
        int leadIdx = getHeadIndex();
        for (int i = 1; i < TOTAL_SEGMENTS - 1; i++) {
            if (segments[i] == null || segments[i].isRemoved()) continue;
            if (!isNearSurface(segments[i])) continue;

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
     * Uses CentipedePather.TileClosestToGoal() to determine which head
     * is closer to the goal along accessible surfaces.
     */
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

        // Use CentipedePather.TileClosestToGoal: compare the two head positions
        // to determine which is better positioned to reach the goal
        boolean rearIsCloser = CentipedePathfinder.tileClosestToGoal(
                this.getWorld(), rear.getBlockPos(), front.getBlockPos(), targetBlock);

        if (rearIsCloser) {
            changeDirCounter++;
            if (changeDirCounter > 5) {
                bodyDirection = !bodyDirection;
                directionChangeBlock = 40;
                changeDirCounter = 0;
                // Re-request path from the new leading head
                if (currentPath != null) {
                    requestPath(targetBlock);
                }
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
        if (head0 != null && head1 != null) {
            // Determine which head is grabbing and which is free
            CentipedeHeadEntity grabbingHead = null;
            CentipedeHeadEntity freeHead = null;
            int grabbingIdx = -1;

            if (head0.isGrabbing() && !head1.isGrabbing()) {
                grabbingHead = head0; freeHead = head1; grabbingIdx = 0;
            } else if (head1.isGrabbing() && !head0.isGrabbing()) {
                grabbingHead = head1; freeHead = head0; grabbingIdx = TOTAL_SEGMENTS - 1;
            }

            if (grabbingHead != null && freeHead != null) {
                LivingEntity grabbed = grabbingHead.getGrabbedEntity();
                if (grabbed != null) {
                    Vec3d grabPos = grabbed.getPos();
                    moveTarget = grabPos;
                    moving = true;

                    // Ensure direction points the FREE head as the leader
                    // so crawl drives the free head toward the prey
                    boolean freeHeadIsAt0 = (freeHead == head0);
                    boolean needsDirection = freeHeadIsAt0 ? bodyDirection : !bodyDirection;
                    if (needsDirection && directionChangeBlock <= 0) {
                        bodyDirection = !bodyDirection;
                        directionChangeBlock = 60;
                    }

                    doubleGrabCharge = Math.min(1.0f, doubleGrabCharge + 0.02f);

                    // Strong constant force pulling free head toward target
                    // (C# UpdateGrasp: aggressive velocity toward grabbed creature)
                    Vec3d toTarget = grabPos.subtract(freeHead.getPos());
                    double distToTarget = toTarget.length();
                    if (distToTarget > 0.3) {
                        Vec3d dir = toTarget.normalize();
                        // Base force + ramping force as charge builds
                        double force = 0.15 + 0.2 * doubleGrabCharge;
                        freeHead.segmentVelocity = freeHead.segmentVelocity.add(dir.multiply(force));
                    }

                    // Body curl: segments actively pull toward the grabbed entity
                    // This makes the centipede wrap its body around the prey
                    for (int i = 1; i < TOTAL_SEGMENTS - 1; i++) {
                        if (segments[i] == null || segments[i].isRemoved()) continue;
                        Vec3d segToTarget = grabPos.subtract(segments[i].getPos());
                        double segDist = segToTarget.length();
                        if (segDist > 1.0) {
                            // Curl force is stronger for segments closer to the free head
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

        // Shock give-up: if wrapping takes too long, release and retry
        if (doubleGrabCharge > 0.85f) {
            shockGiveUpCounter++;
            if (shockGiveUpCounter >= 160) {
                if (head0 != null) head0.releaseGrab();
                if (head1 != null) head1.releaseGrab();
                shockGiveUpCounter = 0;
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
     * Ignores players not in survival mode.
     */
    private boolean isValidPrey(LivingEntity entity) {
        if (entity == this) return false;
        if (entity instanceof CentipedeSegmentEntity) return false;
        if (entity instanceof RedCentipedeEntity) return false;
        if (entity.isRemoved() || entity.isDead()) return false;
        if (entity.isInvulnerable()) return false;
        // Ignore players not in survival mode
        if (entity instanceof PlayerEntity player) {
            if (player.isCreative() || player.isSpectator()) return false;
        }
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
                        < target0.getWidth() + 3.0;
            }
            if (!sameTarget && target1 != null && !headsCloseToTarget) {
                headsCloseToTarget = head0.getPos().distanceTo(target1.getPos())
                        < target1.getWidth() + 3.0;
            }

            if (sameTarget || headsCloseToTarget) {
                // C#: shockCharge += 1 / Lerp(100, 5, size) → for Red (size=1): 0.2/tick
                // Charge over ~1.5 seconds (30 ticks); both heads are in position
                shockCharge += 0.033f;

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
                if (dist < grabbed.getWidth() + 2.5) {
                    // Free head is close to the grabbed entity — build charge!
                    // C# UpdateGrasp: shockCharge += 1/Lerp(100,5,size)
                    shockCharge += 0.033f;
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
