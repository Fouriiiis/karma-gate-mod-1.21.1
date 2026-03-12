package dev.fouriis.karmagate.entity.garbworm;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Garbage Worm entity — Rain World faithful port.
 *
 * Entity position = head (small hitbox). Body/tentacle is rendered only.
 * Root position is pinned at the mycelium hole.
 *
 * Patched so:
 * - movement uses a hover/watch point around the target
 * - visual head aim uses the target's actual eye position
 * - if a player is holding an item, the worm will attempt to steal it
 * - stolen item is rendered on the head briefly while the worm retracts
 * - worms cannot be damaged by weapons / normal attacks
 * - hitting one alerts nearby worms and makes them hostile
 * - hostile worms grab players and either fling them or drag/hold them underwater
 * - /kill still removes them
 */
public class GarbageWormEntity extends MobEntity {

    private static final Logger LOGGER = LoggerFactory.getLogger("GarbageWorm");

    // ── Tracked data keys ──────────────────────────────────────────────
    private static final TrackedData<Float> ROOT_X = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ROOT_Y = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ROOT_Z = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> EXTENDED = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> STRESS_DATA = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> SHOW_ANGRY = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> LOOK_X = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> LOOK_Y = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> LOOK_Z = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Integer> ATTACK_CTR = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> BODY_SIZE_DATA = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<ItemStack> STOLEN_STACK = DataTracker.registerData(GarbageWormEntity.class, TrackedDataHandlerRegistry.ITEM_STACK);

    // ── Constants ──────────────────────────────────────────────────────
    private static final float TENTACLE_LENGTH = 16.0f;
    private static final float AIR_FRICTION = 0.94f;
    private static final int SCAN_RANGE = 20;

    /** Rain World-like "watch from a distance" radius. */
    private static final float WATCH_RADIUS = 8.0f;
    private static final float WATCH_RADIUS_TOLERANCE = 1.5f;

    /** Prefer to observe from above when possible. */
    private static final float WATCH_HEIGHT_MIN = 4.0f;
    private static final float WATCH_HEIGHT_MAX = 8.5f;

    /** Observation wiggle / peering. */
    private static final float WATCH_SIDE_ORBIT = 1.25f;
    private static final float WATCH_WIGGLE_SIDE = 0.95f;
    private static final float WATCH_WIGGLE_UP = 0.55f;
    private static final float WATCH_WIGGLE_FORE = 0.35f;

    /** Idle floor-looking behavior from RW reference. */
    private static final float IDLE_LOOK_SPREAD_XZ = 0.65f;
    private static final float IDLE_LOOK_Y_OFFSET = 0.15f;

    /** Panic / retraction tuning. */
    private static final float ROOT_PANIC_RADIUS = 5.0f;
    private static final float HEAD_PANIC_RADIUS = 3.0f;
    private static final float FAST_APPROACH_SPEED = 0.20f;
    private static final float VERY_FAST_APPROACH_SPEED = 0.35f;

    /** Sucking behavior. */
    private static final int SUCK_PICK_MIN_TICKS = 60;
    private static final int SUCK_PICK_MAX_TICKS = 160;
    private static final int SUCK_HOLD_TICKS = 40;
    private static final double SUCK_SEARCH_RADIUS = 8.0;
    private static final double SUCK_SURFACE_OFFSET = 0.18;
    private static final double SUCK_REACH = 1.15;

    /** Theft behavior. */
    private static final double STEAL_REACH = 1.85;
    private static final int STOLEN_DISPLAY_TICKS = 40;

    /** Hostility / harassment behavior. */
    private static final double ALERT_RADIUS = 18.0;
    private static final int HOSTILE_TICKS = 20 * 20;
    private static final double HOSTILE_GRAB_REACH = 1.65;

    /** Fling = grab, swing, then release with velocity. */
    private static final int FLING_WINDUP_TICKS = 10;
    private static final int FLING_SWING_TICKS = 10;
    private static final double FLING_SWING_RADIUS = 2.4;
    private static final double FLING_THROW_SPEED = 1.55;
    private static final double FLING_THROW_UP_SPEED = 0.95;

    /** Drown = grab, drag to nearby water, hold victim below surface. */
    private static final int DROWN_HOLD_TICKS = 70;
    private static final double DROWN_SEARCH_RADIUS = 10.0;
    private static final double DROWN_PULL_STRENGTH = 0.34;
    private static final double DROWN_VEL_CAP = 0.52;
    private static final double DROWN_SUBMERGE_DEPTH = 0.35;

    // ── Server-side state ──────────────────────────────────────────────
    private Vec3d rootPos = Vec3d.ZERO;
    private float extended = 1f;
    private float retractSpeed = 0.005f;
    private float bodySize = 1f;
    private float stress = 0f;
    private boolean showAsAngry = false;

    /** Tracked and exposed to renderer. */
    private Vec3d lookPoint = Vec3d.ZERO;

    /** Actual movement goal for the head. */
    private Vec3d movementPoint = Vec3d.ZERO;

    /** True while the worm is actively closing distance to steal from a player. */
    private boolean stealingItem = false;

    private int attackCounter = 0;
    private int searchCounter = 0;
    private int comeBackOutCounter = 0;
    private int retractCounter = 0;
    private boolean lastExtended = true;
    private int gracePeriod = 0;
    private int debugLogTimer = 0;

    private final List<BlockPos> myceliumHoles = new ArrayList<>();
    private final List<BlockPos> floorTiles = new ArrayList<>();
    private BlockPos lookAtFloor;
    private int currentHole = -1;
    private boolean initialized = false;

    /** Entity-local head velocity (custom physics, NOT MC movement). */
    private Vec3d headVel = Vec3d.ZERO;

    /** Current watched target for movement behavior. */
    private LivingEntity watchedTarget;

    /** Small phase to make the worm orbit/peer organically while watching. */
    private float watchPhase = 0f;

    /** Sucking state. */
    private BlockPos suckBlockPos;
    private Vec3d suckSurfacePos = Vec3d.ZERO;
    private int suckTimer = 0;
    private int nextSuckAttempt = SUCK_PICK_MIN_TICKS;
    private boolean suckingBlock = false;

    /** Render-only stolen item state, synced to client. */
    private ItemStack stolenDisplayStack = ItemStack.EMPTY;
    private int stolenDisplayTicks = 0;

    /** Hostility state. */
    private UUID hostileTargetUuid;
    private int hostileTicks = 0;
    private LivingEntity hostileTarget;

    /** Grab state. */
    private LivingEntity grabbedTarget;
    private int grabTicks = 0;
    private HarassMode harassMode = HarassMode.FLING;
    private Vec3d drownAnchor = Vec3d.ZERO;
    private float flingYawOffset = 0f;

    private enum HarassMode {
        FLING,
        DROWN
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 15.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 20.0);
    }

    public GarbageWormEntity(EntityType<? extends GarbageWormEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        this.bodySize = 0.8f + random.nextFloat() * 0.4f;
    }

    @Override
    protected void initGoals() {
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(ROOT_X, 0f);
        builder.add(ROOT_Y, 0f);
        builder.add(ROOT_Z, 0f);
        builder.add(EXTENDED, 1f);
        builder.add(STRESS_DATA, 0f);
        builder.add(SHOW_ANGRY, false);
        builder.add(LOOK_X, 0f);
        builder.add(LOOK_Y, 0f);
        builder.add(LOOK_Z, 0f);
        builder.add(ATTACK_CTR, 0);
        builder.add(BODY_SIZE_DATA, 1f);
        builder.add(STOLEN_STACK, ItemStack.EMPTY);
    }

    @Override
    public void tick() {
        this.noClip = true;
        this.setVelocity(Vec3d.ZERO);
        super.tick();

        if (!getWorld().isClient) {
            serverTick();
        }
    }

    @Override public boolean isPushable() { return false; }
    @Override protected void pushAway(Entity entity) { }
    @Override public boolean cannotDespawn() { return true; }

    @Override
    public void travel(Vec3d movementInput) {
    }

    @Override
    public boolean isInsideWall() {
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        Entity attacker = source.getAttacker();
        if (!(attacker instanceof LivingEntity living)) {
            return super.damage(source, amount);
        }

        alertNearbyWorms(living);
        return false;
    }

    private void serverTick() {
        if (!initialized) {
            initialize();
            initialized = true;
        }

        if (!stolenDisplayStack.isEmpty()) {
            stolenDisplayTicks--;
            if (stolenDisplayTicks <= 0 || extended <= 0f) {
                stolenDisplayStack = ItemStack.EMPTY;
                stolenDisplayTicks = 0;
            }
        }

        if (hostileTicks > 0) {
            hostileTicks--;
        } else {
            hostileTarget = null;
            hostileTargetUuid = null;
        }

        if (hostileTarget != null && !hostileTarget.isAlive()) {
            hostileTarget = null;
            hostileTargetUuid = null;
            hostileTicks = 0;
        }

        if (grabbedTarget != null && !grabbedTarget.isAlive()) {
            releaseGrabbedTarget();
        }

        extended += retractSpeed;
        extended = MathHelper.clamp(extended, 0f, 1f);
        boolean currentlyExtended = extended > 0f;

        if (extended == 0f && lastExtended) {
            setPosition(rootPos.x, rootPos.y - 2.0, rootPos.z);
            headVel = Vec3d.ZERO;
            releaseGrabbedTarget();
        }

        lastExtended = currentlyExtended;

        if (gracePeriod > 0) gracePeriod--;

        if (currentlyExtended) {
            updateAI();
            updateHeadMovement();
        } else {
            watchedTarget = null;
            stealingItem = false;
            suckingBlock = false;
            suckTimer = 0;
            releaseGrabbedTarget();

            if (retractCounter > 0) {
                retractCounter--;
                comeBackOutCounter = 0;
            } else {
                comeBackOutCounter += random.nextInt(3);
                if (comeBackOutCounter > 80) {
                    doExtend();
                }
            }
        }

        syncTrackedData();

        debugLogTimer++;
        if (debugLogTimer >= 60) {
            debugLogTimer = 0;
            String state = extended > 0f ? (retractSpeed >= 0 ? "EXTENDING" : "RETRACTING") : "RETRACTED";
            LOGGER.info("[GarbageWorm id={}] state={} extended={} hostileTicks={} stealingItem={} grabbed={} harassMode={} lookPoint=({},{},{})",
                    getId(),
                    state,
                    String.format("%.3f", extended),
                    hostileTicks,
                    stealingItem,
                    grabbedTarget != null,
                    harassMode,
                    String.format("%.1f", lookPoint.x),
                    String.format("%.1f", lookPoint.y),
                    String.format("%.1f", lookPoint.z));
        }
    }

    private void initialize() {
        Vec3d spawnPos = getPos();
        scanForMycelium(spawnPos);
        LOGGER.info("[GarbageWorm id={}] INIT at ({},{},{}) — found {} mycelium holes, bodySize={}",
                getId(),
                String.format("%.1f", spawnPos.x), String.format("%.1f", spawnPos.y), String.format("%.1f", spawnPos.z),
                myceliumHoles.size(), String.format("%.2f", bodySize));

        if (!myceliumHoles.isEmpty()) {
            pickNewHole(false);
        } else {
            rootPos = spawnPos;
            lookPoint = rootPos.add(0, 3, 0);
            movementPoint = lookPoint;
            setPosition(rootPos.x, rootPos.y + 3, rootPos.z);
        }
        nextSuckAttempt = random.nextBetween(SUCK_PICK_MIN_TICKS, SUCK_PICK_MAX_TICKS);
        dataTracker.set(BODY_SIZE_DATA, bodySize);
    }

    private void scanForMycelium(Vec3d center) {
        myceliumHoles.clear();
        BlockPos cp = BlockPos.ofFloored(center);
        World w = getWorld();

        for (int dx = -SCAN_RANGE; dx <= SCAN_RANGE; dx++) {
            for (int dy = -SCAN_RANGE; dy <= SCAN_RANGE; dy++) {
                for (int dz = -SCAN_RANGE; dz <= SCAN_RANGE; dz++) {
                    BlockPos bp = cp.add(dx, dy, dz);
                    if (w.getBlockState(bp).isOf(Blocks.MYCELIUM)) {
                        if (!w.getBlockState(bp.up()).isSolidBlock(w, bp.up())) {
                            myceliumHoles.add(bp.toImmutable());
                        }
                    }
                }
            }
        }
    }

    private void pickNewHole(boolean burrowed) {
        if (myceliumHoles.isEmpty()) {
            retractSpeed = -1f / 30f;
            return;
        }
        currentHole = random.nextInt(myceliumHoles.size());
        BlockPos holePos = myceliumHoles.get(currentHole);

        rootPos = new Vec3d(holePos.getX() + 0.5, holePos.getY() + 1.05, holePos.getZ() + 0.5);
        lookPoint = rootPos.add(0, TENTACLE_LENGTH * bodySize * 0.4, 0);
        movementPoint = lookPoint;

        if (!burrowed) {
            setPosition(rootPos.x, rootPos.y + TENTACLE_LENGTH * bodySize * 0.5, rootPos.z);
            headVel = Vec3d.ZERO;
        }

        mapFloor();
    }

    private void mapFloor() {
        floorTiles.clear();
        for (BlockPos hole : myceliumHoles) {
            floorTiles.add(hole);
        }

        BlockPos rootBlock = BlockPos.ofFloored(rootPos);
        World w = getWorld();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = 3; dy >= -3; dy--) {
                    BlockPos check = rootBlock.add(dx, dy, dz);
                    if (w.getBlockState(check).isSolidBlock(w, check)
                            && !w.getBlockState(check.up()).isSolidBlock(w, check.up())) {
                        floorTiles.add(check.toImmutable());
                        break;
                    }
                }
            }
        }
        if (floorTiles.isEmpty()) {
            floorTiles.add(BlockPos.ofFloored(rootPos));
        }
        lookAtFloor = floorTiles.get(random.nextInt(floorTiles.size()));
    }

    private void updateAI() {
        showAsAngry = false;
        watchedTarget = null;
        stealingItem = false;
        watchPhase += 0.08f;

        if (handleHostileBehavior()) {
            return;
        }

        if (attackCounter > 0) {
            attackCounter++;
            if (attackCounter > 180) {
                attackCounter = 0;
            }
            return;
        }

        double searchRadius = TENTACLE_LENGTH * bodySize * 2.0;
        List<LivingEntity> nearby = getWorld().getEntitiesByClass(
                LivingEntity.class,
                getBoundingBox().expand(searchRadius),
                e -> e != this && e.isAlive() && !e.isSpectator()
        );

        LivingEntity bestInterest = null;
        double bestInterestScore = 0.05;
        float dangerAccum = 0f;

        for (LivingEntity entity : nearby) {
            double dist = getPos().distanceTo(entity.getEyePos());
            float danger = entityDanger(entity);
            if (dist > 0.01) {
                float contribution = danger * (7f + (float) entity.getVelocity().length() * 20f);
                contribution /= (float) (dist * dist);
                dangerAccum += contribution / 80f;
            }

            double score = interestScore(entity, dist);
            if (score > bestInterestScore) {
                bestInterestScore = score;
                bestInterest = entity;
            }
        }

        stress += dangerAccum - 0.005f;
        stress = MathHelper.clamp(stress, 0f, 1f);

        if (bestInterest != null) {
            watchedTarget = bestInterest;
            suckingBlock = false;
            suckTimer = 0;

            if (bestInterest instanceof PlayerEntity player && canStealFrom(player)) {
                stealingItem = true;
                movementPoint = player.getEyePos();
                lookPoint = player.getEyePos();
                searchCounter = 0;

                if (getPos().distanceTo(player.getEyePos()) <= STEAL_REACH) {
                    stealHeldItem(player);
                    return;
                }
            } else {
                movementPoint = computeWatchPoint(bestInterest);
                lookPoint = bestInterest.getEyePos();
                searchCounter = 0;
            }

            float threatDistFromRoot = (float) rootPos.distanceTo(bestInterest.getPos());
            float threatDistFromHead = (float) getPos().distanceTo(bestInterest.getEyePos());

            if (gracePeriod <= 0 && stress > 0.9f && threatDistFromRoot < 17f) {
                retractCounter++;
                if (threatDistFromRoot < 2f) retractCounter += 4;
                if (threatDistFromHead < 2f) retractCounter++;
            } else if (retractCounter > 0) {
                retractCounter--;
            }

            if (gracePeriod <= 0) {
                applyPanicRetraction(bestInterest, threatDistFromRoot, threatDistFromHead);
            }

            if (retractCounter > 80) {
                doRetract();
                retractCounter = 0;
            }

            return;
        }

        if (retractCounter > 0) retractCounter--;

        if (suckingBlock) {
            suckTimer--;
            movementPoint = suckSurfacePos;
            lookPoint = suckSurfacePos;
            if (suckTimer <= 0 || suckBlockPos == null || !isValidSuckTarget(suckBlockPos, suckSurfacePos)) {
                stopSucking();
            }
            return;
        }

        nextSuckAttempt--;
        if (nextSuckAttempt <= 0 && tryStartSucking()) {
            return;
        }

        if ((random.nextFloat() < 0.025f || searchCounter < 5) && !floorTiles.isEmpty()) {
            lookAtFloor = floorTiles.get(random.nextInt(floorTiles.size()));
            Vec3d idlePoint = floorLookPoint(lookAtFloor);
            movementPoint = idlePoint;
            lookPoint = idlePoint;
        }
        searchCounter++;

        if (searchCounter > 100 && random.nextFloat() < 0.006f) {
            Vec3d headPos = getPos();
            if (headPos.distanceTo(movementPoint) < 5.0) {
                attackCounter = 1;
                searchCounter = 0;
            }
        }
    }

    private boolean handleHostileBehavior() {
        if (hostileTicks <= 0 || hostileTarget == null || !hostileTarget.isAlive()) {
            if (grabbedTarget != null) {
                releaseGrabbedTarget();
            }
            return false;
        }

        showAsAngry = true;
        watchedTarget = hostileTarget;
        suckingBlock = false;
        suckTimer = 0;

        if (grabbedTarget != null) {
            return updateGrabBehavior();
        }

        lookPoint = hostileTarget.getEyePos();
        movementPoint = hostileTarget.getEyePos();

        if (getPos().distanceTo(hostileTarget.getEyePos()) <= HOSTILE_GRAB_REACH) {
            startGrab(hostileTarget);
            return true;
        }

        return true;
    }

    private void startGrab(LivingEntity target) {
        grabbedTarget = target;
        grabTicks = 0;
        lookPoint = target.getEyePos();
        movementPoint = target.getEyePos();

        Vec3d waterAnchor = findNearbyWaterAnchor();
        if (waterAnchor != null && random.nextBoolean()) {
            harassMode = HarassMode.DROWN;
            drownAnchor = waterAnchor;
        } else {
            harassMode = HarassMode.FLING;
            drownAnchor = Vec3d.ZERO;
            flingYawOffset = random.nextBoolean() ? -1.35f : 1.35f;
        }
    }

    private boolean updateGrabBehavior() {
        if (grabbedTarget == null || !grabbedTarget.isAlive()) {
            releaseGrabbedTarget();
            return false;
        }

        showAsAngry = true;
        lookPoint = grabbedTarget.getEyePos();
        grabTicks++;

        if (harassMode == HarassMode.FLING) {
            Vec3d rootToTarget = grabbedTarget.getPos().subtract(rootPos);
            Vec3d horizontal = new Vec3d(rootToTarget.x, 0.0, rootToTarget.z);

            Vec3d baseDir;
            if (horizontal.lengthSquared() < 1.0e-6) {
                baseDir = new Vec3d(1, 0, 0);
            } else {
                baseDir = horizontal.normalize();
            }

            if (grabTicks <= FLING_WINDUP_TICKS) {
                Vec3d pullPoint = rootPos.add(0.0, 1.2, 0.0);
                movementPoint = pullPoint;
                pullEntityToward(grabbedTarget, getPos(), 0.42, 0.90);
                return true;
            }

            float swingT = MathHelper.clamp(
                    (float) (grabTicks - FLING_WINDUP_TICKS) / (float) FLING_SWING_TICKS,
                    0f,
                    1f
            );

            float yaw = (float) Math.atan2(baseDir.z, baseDir.x) + flingYawOffset * swingT;
            Vec3d arcDir = new Vec3d(Math.cos(yaw), 0.0, Math.sin(yaw));
            Vec3d swingPos = rootPos
                    .add(arcDir.multiply(FLING_SWING_RADIUS))
                    .add(0.0, 1.4 + Math.sin(swingT * Math.PI) * 1.1, 0.0);

            movementPoint = swingPos;
            pullEntityToward(grabbedTarget, swingPos, 0.46, 1.05);

            if (grabTicks >= FLING_WINDUP_TICKS + FLING_SWING_TICKS) {
                Vec3d throwDir = swingPos.subtract(rootPos);
                if (throwDir.lengthSquared() < 1.0e-6) {
                    throwDir = arcDir;
                } else {
                    throwDir = new Vec3d(throwDir.x, 0.0, throwDir.z).normalize();
                }

                Vec3d throwVel = throwDir.multiply(FLING_THROW_SPEED).add(0.0, FLING_THROW_UP_SPEED, 0.0);
                grabbedTarget.setVelocity(throwVel);
                grabbedTarget.velocityModified = true;
                releaseGrabbedTarget();
            }
            return true;
        }

        if (harassMode == HarassMode.DROWN) {
            Vec3d anchor = drownAnchor.lengthSquared() > 1.0e-6 ? drownAnchor : rootPos.add(0, -1.0, 0);

            movementPoint = anchor.add(0.0, 0.45, 0.0);

            Vec3d submergedPoint = new Vec3d(
                    anchor.x,
                    anchor.y - DROWN_SUBMERGE_DEPTH,
                    anchor.z
            );

            pullEntityToward(grabbedTarget, submergedPoint, DROWN_PULL_STRENGTH, DROWN_VEL_CAP);

            Vec3d v = grabbedTarget.getVelocity();
            grabbedTarget.setVelocity(v.x * 0.85, Math.min(v.y, -0.08), v.z * 0.85);
            grabbedTarget.velocityModified = true;

            if (grabTicks >= DROWN_HOLD_TICKS) {
                releaseGrabbedTarget();
            }
            return true;
        }

        return false;
    }

    private void releaseGrabbedTarget() {
        grabbedTarget = null;
        grabTicks = 0;
        drownAnchor = Vec3d.ZERO;
        flingYawOffset = 0f;
    }

    private void pullEntityToward(LivingEntity target, Vec3d anchor, double strength, double cap) {
        Vec3d toAnchor = anchor.subtract(target.getPos());
        if (toAnchor.lengthSquared() > 1.0e-6) {
            Vec3d vel = target.getVelocity().multiply(0.72).add(toAnchor.normalize().multiply(strength));
            if (vel.length() > cap) {
                vel = vel.normalize().multiply(cap);
            }
            target.setVelocity(vel);
            target.velocityModified = true;
        }
    }

    private Vec3d findNearbyWaterAnchor() {
        BlockPos center = BlockPos.ofFloored(rootPos);
        int r = (int) Math.ceil(DROWN_SEARCH_RADIUS);

        Vec3d best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 4; dy >= -6; dy--) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = getWorld().getBlockState(pos);
                    if (!state.isOf(Blocks.WATER)) {
                        continue;
                    }

                    if (!getWorld().getBlockState(pos.up()).isAir() && !getWorld().getBlockState(pos.up()).isOf(Blocks.WATER)) {
                        continue;
                    }

                    Vec3d surface = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5);
                    double distSq = rootPos.squaredDistanceTo(surface);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = surface;
                    }
                }
            }
        }

        return best;
    }

    private void alertNearbyWorms(LivingEntity attacker) {
        Box box = new Box(rootPos, rootPos).expand(ALERT_RADIUS);
        List<GarbageWormEntity> worms = getWorld().getEntitiesByClass(
                GarbageWormEntity.class,
                box,
                e -> e.isAlive()
        );

        for (GarbageWormEntity worm : worms) {
            worm.becomeHostileTo(attacker);
        }
    }

    public void becomeHostileTo(LivingEntity target) {
        hostileTarget = target;
        hostileTargetUuid = target.getUuid();
        hostileTicks = HOSTILE_TICKS;

        showAsAngry = true;
        watchedTarget = target;
        stealingItem = false;
        suckingBlock = false;
        suckTimer = 0;

        if (grabbedTarget != null && grabbedTarget != target) {
            releaseGrabbedTarget();
        }

        lookPoint = target.getEyePos();
        movementPoint = target.getEyePos();
    }

    private boolean canStealFrom(PlayerEntity player) {
        return !player.getMainHandStack().isEmpty() || !player.getOffHandStack().isEmpty();
    }

    private void stealHeldItem(PlayerEntity player) {
        Hand handToSteal;
        ItemStack held;

        if (!player.getMainHandStack().isEmpty() && !player.getOffHandStack().isEmpty()) {
            if (random.nextBoolean()) {
            handToSteal = Hand.MAIN_HAND;
            held = player.getMainHandStack();
            } else {
            handToSteal = Hand.OFF_HAND;
            held = player.getOffHandStack();
            }
        } else if (!player.getMainHandStack().isEmpty()) {
            handToSteal = Hand.MAIN_HAND;
            held = player.getMainHandStack();
        } else if (!player.getOffHandStack().isEmpty()) {
            handToSteal = Hand.OFF_HAND;
            held = player.getOffHandStack();
        } else {
            return;
        }

        ItemStack stolenCopy = held.copy();
        player.setStackInHand(handToSteal, ItemStack.EMPTY);

        stolenDisplayStack = stolenCopy;
        stolenDisplayTicks = STOLEN_DISPLAY_TICKS;

        watchedTarget = null;
        stealingItem = false;
        suckingBlock = false;
        attackCounter = 0;
        retractCounter = 0;

        movementPoint = getPos();
        lookPoint = getPos();

        LOGGER.info("[GarbageWorm id={}] stole item {} x{} from player {} and is retracting",
                getId(),
                stolenCopy.getItem(),
                stolenCopy.getCount(),
                player.getName().getString());

        doRetract();
    }

    private double interestScore(LivingEntity entity, double dist) {
        double base = Math.max(0.1, entityDanger(entity));
        double speed = entity.getVelocity().length();
        double score = base;

        score *= (1.0 + speed * 2.0);
        score /= (1.0 + dist * 0.22);

        if (entity instanceof PlayerEntity) {
            score *= 1.35;
        }

        if (getAttacker() != null && getAttacker() == entity) {
            score *= 2.5;
        }

        if (dist < WATCH_RADIUS * 0.55) {
            score *= 0.75;
        }

        return score;
    }

    private void applyPanicRetraction(LivingEntity threat, float threatDistFromRoot, float threatDistFromHead) {
        Vec3d vel = threat.getVelocity();
        double speed = vel.length();
        if (speed < 1.0e-4) {
            if (threatDistFromRoot < ROOT_PANIC_RADIUS * 0.5f) {
                retractCounter += 2;
            }
            return;
        }

        Vec3d toRoot = rootPos.subtract(threat.getPos());
        Vec3d toHead = getPos().subtract(threat.getEyePos());

        double towardRootSpeed = 0.0;
        double towardHeadSpeed = 0.0;

        if (toRoot.lengthSquared() > 1.0e-6) {
            towardRootSpeed = vel.dotProduct(toRoot.normalize());
        }
        if (toHead.lengthSquared() > 1.0e-6) {
            towardHeadSpeed = vel.dotProduct(toHead.normalize());
        }

        boolean nearRoot = threatDistFromRoot < ROOT_PANIC_RADIUS;
        boolean veryNearRoot = threatDistFromRoot < 2.25f;
        boolean nearHead = threatDistFromHead < HEAD_PANIC_RADIUS;

        boolean fastTowardRoot = towardRootSpeed > FAST_APPROACH_SPEED;
        boolean veryFastTowardRoot = towardRootSpeed > VERY_FAST_APPROACH_SPEED;
        boolean fastTowardHead = towardHeadSpeed > FAST_APPROACH_SPEED;

        if (nearRoot) retractCounter += 2;
        if (veryNearRoot) retractCounter += 5;
        if (nearHead) retractCounter += 2;

        if (fastTowardRoot && nearRoot) {
            retractCounter += 4;
            stress = Math.min(1f, stress + 0.08f);
        }
        if (veryFastTowardRoot) {
            retractCounter += 7;
            stress = Math.min(1f, stress + 0.14f);
        }
        if (fastTowardHead && threatDistFromHead < 5.0f) {
            retractCounter += 3;
            stress = Math.min(1f, stress + 0.05f);
        }
    }

    /**
     * Rain World-style observing movement:
     * - stay back from the target
     * - prefer being above it
     * - add a gentle peering wiggle while watching
     *
     * This is for the head's movement target, not the visual aim target.
     */
    private Vec3d computeWatchPoint(LivingEntity target) {
        Vec3d targetPos = target.getEyePos();

        Vec3d rootToTarget = targetPos.subtract(rootPos);
        Vec3d horizontal = new Vec3d(rootToTarget.x, 0.0, rootToTarget.z);
        Vec3d horizontalDir = horizontal.lengthSquared() < 1.0e-6 ? new Vec3d(1, 0, 0) : horizontal.normalize();
        Vec3d side = new Vec3d(-horizontalDir.z, 0.0, horizontalDir.x);

        double verticalBias = MathHelper.clamp(
                rootPos.y - targetPos.y + WATCH_HEIGHT_MIN,
                WATCH_HEIGHT_MIN,
                WATCH_HEIGHT_MAX
        );

        double orbit = Math.sin(watchPhase) * WATCH_SIDE_ORBIT;
        double wiggleSide = Math.sin(watchPhase * 1.85f) * WATCH_WIGGLE_SIDE;
        double wiggleUp = Math.sin(watchPhase * 1.20f + 0.8f) * WATCH_WIGGLE_UP;
        double wiggleFore = Math.cos(watchPhase * 1.05f + 1.6f) * WATCH_WIGGLE_FORE;

        Vec3d desired = targetPos
                .subtract(horizontalDir.multiply(WATCH_RADIUS + wiggleFore))
                .add(side.multiply(orbit + wiggleSide))
                .add(0.0, verticalBias + wiggleUp, 0.0);

        double maxReach = TENTACLE_LENGTH * bodySize * Math.max(extended, 0.1f);
        Vec3d rootToDesired = desired.subtract(rootPos);
        double len = rootToDesired.length();
        if (len > maxReach && len > 1.0e-6) {
            desired = rootPos.add(rootToDesired.multiply(maxReach / len));
        }

        double minObserveY = targetPos.y + WATCH_HEIGHT_MIN * 0.5f;
        if (desired.y < minObserveY) {
            desired = new Vec3d(desired.x, minObserveY, desired.z);
        }

        return desired;
    }

    private Vec3d floorLookPoint(BlockPos floor) {
        double offX = (random.nextDouble() * 2.0 - 1.0) * IDLE_LOOK_SPREAD_XZ;
        double offZ = (random.nextDouble() * 2.0 - 1.0) * IDLE_LOOK_SPREAD_XZ;
        return new Vec3d(
                floor.getX() + 0.5 + offX,
                floor.getY() + IDLE_LOOK_Y_OFFSET,
                floor.getZ() + 0.5 + offZ
        );
    }

    private boolean tryStartSucking() {
        List<SuckCandidate> candidates = gatherSuckCandidates();
        if (candidates.isEmpty()) {
            nextSuckAttempt = random.nextBetween(40, 100);
            return false;
        }

        candidates.sort(Comparator.comparingDouble(c -> c.score));
        SuckCandidate chosen = candidates.get(random.nextInt(Math.min(candidates.size(), 6)));

        suckBlockPos = chosen.blockPos;
        suckSurfacePos = chosen.surfacePos;
        suckTimer = SUCK_HOLD_TICKS + random.nextInt(20);
        suckingBlock = true;
        movementPoint = suckSurfacePos;
        lookPoint = suckSurfacePos;
        searchCounter = 0;
        nextSuckAttempt = random.nextBetween(SUCK_PICK_MIN_TICKS, SUCK_PICK_MAX_TICKS);
        return true;
    }

    private void stopSucking() {
        suckingBlock = false;
        suckTimer = 0;
        suckBlockPos = null;
        suckSurfacePos = Vec3d.ZERO;
        nextSuckAttempt = random.nextBetween(SUCK_PICK_MIN_TICKS, SUCK_PICK_MAX_TICKS);
    }

    private boolean isValidSuckTarget(BlockPos pos, Vec3d surface) {
        if (pos == null) return false;
        World w = getWorld();
        BlockState state = w.getBlockState(pos);
        if (state.isAir()) return false;
        if (w.getBlockState(pos.up()).isSolidBlock(w, pos.up())) return false;
        if (rootPos.distanceTo(surface) > TENTACLE_LENGTH * bodySize * Math.max(extended, 0.1f)) return false;
        return hasLineOfSight(getPos(), surface);
    }

    private List<SuckCandidate> gatherSuckCandidates() {
        List<SuckCandidate> out = new ArrayList<>();
        World w = getWorld();
        BlockPos center = BlockPos.ofFloored(rootPos);

        int r = (int) Math.ceil(SUCK_SEARCH_RADIUS);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -3; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos bp = center.add(dx, dy, dz);
                    BlockState state = w.getBlockState(bp);
                    if (state.isAir()) continue;
                    if (state.isOf(Blocks.MYCELIUM)) continue;
                    if (w.getBlockState(bp.up()).isSolidBlock(w, bp.up())) continue;

                    Vec3d surface = new Vec3d(bp.getX() + 0.5, bp.getY() + 1.0 + SUCK_SURFACE_OFFSET, bp.getZ() + 0.5);
                    double rootDist = rootPos.distanceTo(surface);
                    if (rootDist > TENTACLE_LENGTH * bodySize * Math.max(extended, 0.1f)) continue;
                    if (!hasLineOfSight(getPos(), surface)) continue;

                    double headDist = getPos().distanceTo(surface);
                    double heightBias = Math.abs(surface.y - (rootPos.y + 1.0));
                    double score = headDist + rootDist * 0.15 + heightBias * 0.25;

                    out.add(new SuckCandidate(bp.toImmutable(), surface, score));
                }
            }
        }
        return out;
    }

    private boolean hasLineOfSight(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        double len = delta.length();
        if (len < 1.0e-6) return true;

        Vec3d dir = delta.normalize();
        double step = 0.35;
        World w = getWorld();

        for (double d = step; d < len - step; d += step) {
            Vec3d sample = from.add(dir.multiply(d));
            BlockPos bp = BlockPos.ofFloored(sample);
            BlockState state = w.getBlockState(bp);
            if (state.isSolidBlock(w, bp)) {
                return false;
            }
        }
        return true;
    }

    private float entityDanger(LivingEntity entity) {
        if (entity instanceof PlayerEntity) return 1.5f;
        return entity.getHeight() > 1.0f ? entity.getHeight() : 0f;
    }

    private void updateHeadMovement() {
        Vec3d headPos = getPos();
        double maxReach = TENTACLE_LENGTH * bodySize * extended;

        if (attackCounter > 0) {
            if (attackCounter < 20) {
                Vec3d dir = movementPoint.subtract(headPos);
                if (dir.length() > 0.01) {
                    headVel = headVel.add(dir.normalize().multiply(0.015));
                }
            } else if (attackCounter < 40) {
                Vec3d dir = movementPoint.subtract(headPos);
                if (dir.length() > 0.01) {
                    double clampedLen = Math.min(dir.length(), 1.5);
                    headVel = headVel.add(dir.normalize().multiply(clampedLen * 0.5));
                }
            } else if (attackCounter < 150) {
                setPosition(movementPoint.x, movementPoint.y, movementPoint.z);
                headVel = Vec3d.ZERO;
                return;
            } else {
                Vec3d upTarget = rootPos.add(0, maxReach * 0.5, 0);
                Vec3d dir = upTarget.subtract(headPos);
                if (dir.length() > 0.01) {
                    headVel = headVel.add(dir.normalize().multiply(0.005));
                }
            }
        } else {
            Vec3d toMovePoint = movementPoint.subtract(headPos);
            double distToMovePoint = toMovePoint.length();

            if (distToMovePoint > 0.01) {
                Vec3d dir = toMovePoint.normalize();

                double desiredStop = suckingBlock ? 0.18 : WATCH_RADIUS_TOLERANCE;
                double maxSpeedNear = suckingBlock ? 0.18 : 0.10;
                double maxSpeedFar = suckingBlock ? 0.34 : 0.42;

                double targetSpeed;
                if (distToMovePoint > desiredStop) {
                    double t = MathHelper.clamp((float) ((distToMovePoint - desiredStop) / 6.0), 0f, 1f);
                    targetSpeed = MathHelper.lerp((float) t, (float) maxSpeedNear, (float) maxSpeedFar);
                } else {
                    targetSpeed = 0.0;
                }

                Vec3d desiredVel = dir.multiply(targetSpeed);
                Vec3d steering = desiredVel.subtract(headVel);

                double steeringCap = suckingBlock
                        ? (distToMovePoint < 0.75 ? 0.10 : 0.05)
                        : (distToMovePoint < 2.0 ? 0.08 : 0.045);

                double steeringLen = steering.length();
                if (steeringLen > steeringCap && steeringLen > 1.0e-8) {
                    steering = steering.multiply(steeringCap / steeringLen);
                }

                headVel = headVel.add(steering);
            }

            if (suckingBlock) {
                double dist = headPos.distanceTo(suckSurfacePos);
                if (dist < SUCK_REACH) {
                    headVel = new Vec3d(headVel.x * 0.78, headVel.y * 0.70, headVel.z * 0.78);
                    if (dist < 0.35) {
                        headVel = headVel.add(0.0, -0.01 + 0.02 * Math.sin((age + suckTimer) * 0.6), 0.0);
                    }
                }
            } else if (!stealingItem && hostileTarget == null && watchedTarget != null && watchedTarget.isAlive()) {
                Vec3d targetPos = watchedTarget.getEyePos();
                Vec3d fromTarget = headPos.subtract(targetPos);
                double actualDist = fromTarget.length();

                if (actualDist < WATCH_RADIUS) {
                    Vec3d pullBackDir;
                    if (actualDist > 1.0e-6) {
                        pullBackDir = fromTarget.normalize();
                    } else {
                        Vec3d fallback = headPos.subtract(rootPos);
                        pullBackDir = fallback.lengthSquared() > 1.0e-6 ? fallback.normalize() : new Vec3d(0, 1, 0);
                    }

                    double closeness = 1.0 - MathHelper.clamp((float) (actualDist / WATCH_RADIUS), 0f, 1f);
                    headVel = headVel.add(pullBackDir.multiply(0.08 + closeness * 0.16));
                }

                double desiredMinY = targetPos.y + WATCH_HEIGHT_MIN * 0.5f;
                if (headPos.y < desiredMinY) {
                    headVel = headVel.add(0.0, 0.04, 0.0);
                }
            }

            headVel = headVel.add(0, 0.003, 0);
        }

        headVel = headVel.multiply(AIR_FRICTION);

        double speed = headVel.length();
        double speedCap = attackCounter > 0 ? 0.8 : (suckingBlock ? 0.38 : 0.5);
        if (stealingItem || hostileTarget != null) {
            speedCap = 0.68;
        }
        if (speed > speedCap) {
            headVel = headVel.multiply(speedCap / speed);
        }

        Vec3d newPos = headPos.add(headVel);

        Vec3d rootToHead = newPos.subtract(rootPos);
        double rootToHeadLen = rootToHead.length();
        if (rootToHeadLen > maxReach && maxReach > 0.01) {
            double excess = rootToHeadLen - maxReach;
            Vec3d outward = rootToHead.normalize();
            newPos = newPos.subtract(outward.multiply(excess * 0.75));
            headVel = headVel.subtract(outward.multiply(excess * 0.10));
        }

        if (newPos.y < rootPos.y + 0.2) {
            newPos = new Vec3d(newPos.x, rootPos.y + 0.2, newPos.z);
            if (headVel.y < 0) headVel = new Vec3d(headVel.x, 0, headVel.z);
        }

        double distFromRoot = newPos.distanceTo(rootPos);
        if (distFromRoot > 2.0) {
            BlockPos newBlock = BlockPos.ofFloored(newPos);
            World w = getWorld();
            if (w.getBlockState(newBlock).isSolidBlock(w, newBlock)) {
                headVel = headVel.multiply(0.35);
                newPos = headPos;
            }
        }

        setPosition(newPos.x, newPos.y, newPos.z);
    }

    private void doRetract() {
        retractSpeed = -1f / 30f;
        LOGGER.info("[GarbageWorm id={}] RETRACT triggered (stress={}, retractCounter was >80)",
                getId(), String.format("%.3f", stress));
    }

    private void doExtend() {
        LOGGER.info("[GarbageWorm id={}] EXTEND triggered (comeBackOutCounter={}, holes={})",
                getId(), comeBackOutCounter, myceliumHoles.size());
        if (myceliumHoles.isEmpty()) {
            scanForMycelium(rootPos);
            LOGGER.info("[GarbageWorm id={}] Re-scanned mycelium, found {} holes",
                    getId(), myceliumHoles.size());
        }
        pickNewHole(true);
        retractSpeed = 1f / 10f;
        extended = 0.05f;
        comeBackOutCounter = 0;
        gracePeriod = 120;
        stress = 0f;
        retractCounter = 0;
        lookPoint = rootPos.add(0, TENTACLE_LENGTH * bodySize * 0.4, 0);
        movementPoint = lookPoint;
        stealingItem = false;
        hostileTarget = null;
        hostileTargetUuid = null;
        hostileTicks = 0;
        releaseGrabbedTarget();
        setPosition(rootPos.x, rootPos.y + 0.5, rootPos.z);
        headVel = new Vec3d(0, 0.15, 0);
        stopSucking();
    }

    private void syncTrackedData() {
        dataTracker.set(ROOT_X, (float) rootPos.x);
        dataTracker.set(ROOT_Y, (float) rootPos.y);
        dataTracker.set(ROOT_Z, (float) rootPos.z);
        dataTracker.set(EXTENDED, extended);
        dataTracker.set(STRESS_DATA, stress);
        dataTracker.set(SHOW_ANGRY, showAsAngry);
        dataTracker.set(LOOK_X, (float) lookPoint.x);
        dataTracker.set(LOOK_Y, (float) lookPoint.y);
        dataTracker.set(LOOK_Z, (float) lookPoint.z);
        dataTracker.set(ATTACK_CTR, attackCounter);
        dataTracker.set(BODY_SIZE_DATA, bodySize);
        dataTracker.set(STOLEN_STACK, stolenDisplayStack);
    }

    public Vec3d getRootPos() { return new Vec3d(dataTracker.get(ROOT_X), dataTracker.get(ROOT_Y), dataTracker.get(ROOT_Z)); }
    public float getExtended() { return dataTracker.get(EXTENDED); }
    public float getStress() { return dataTracker.get(STRESS_DATA); }
    public boolean isShowAngry() { return dataTracker.get(SHOW_ANGRY); }
    public Vec3d getLookPoint() { return new Vec3d(dataTracker.get(LOOK_X), dataTracker.get(LOOK_Y), dataTracker.get(LOOK_Z)); }
    public int getAttackCtr() { return dataTracker.get(ATTACK_CTR); }
    public float getBodySizeValue() { return dataTracker.get(BODY_SIZE_DATA); }
    public ItemStack getStolenDisplayStack() { return dataTracker.get(STOLEN_STACK); }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putFloat("BodySize", bodySize);
        nbt.putDouble("RootX", rootPos.x);
        nbt.putDouble("RootY", rootPos.y);
        nbt.putDouble("RootZ", rootPos.z);
        nbt.putFloat("Extended", extended);
        nbt.putFloat("RetractSpeed", retractSpeed);
        nbt.putInt("CurrentHole", currentHole);
        nbt.putBoolean("Initialized", initialized);
        nbt.putFloat("WatchPhase", watchPhase);
        nbt.putInt("NextSuckAttempt", nextSuckAttempt);
        nbt.putBoolean("SuckingBlock", suckingBlock);
        nbt.putBoolean("StealingItem", stealingItem);
        nbt.putInt("SuckTimer", suckTimer);
        nbt.putDouble("MoveX", movementPoint.x);
        nbt.putDouble("MoveY", movementPoint.y);
        nbt.putDouble("MoveZ", movementPoint.z);
        nbt.putInt("StolenDisplayTicks", stolenDisplayTicks);
        nbt.putInt("HostileTicks", hostileTicks);
        nbt.putInt("GrabTicks", grabTicks);
        nbt.putString("HarassMode", harassMode.name());
        if (hostileTargetUuid != null) {
            nbt.putUuid("HostileTarget", hostileTargetUuid);
        }
        if (!stolenDisplayStack.isEmpty()) {
            nbt.put("StolenDisplayStack", stolenDisplayStack.encodeAllowEmpty(getRegistryManager()));
        }
        if (suckBlockPos != null) {
            nbt.putInt("SuckX", suckBlockPos.getX());
            nbt.putInt("SuckY", suckBlockPos.getY());
            nbt.putInt("SuckZ", suckBlockPos.getZ());
        }
        if (drownAnchor.lengthSquared() > 1.0e-6) {
            nbt.putDouble("DrownX", drownAnchor.x);
            nbt.putDouble("DrownY", drownAnchor.y);
            nbt.putDouble("DrownZ", drownAnchor.z);
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("BodySize")) bodySize = nbt.getFloat("BodySize");
        if (nbt.contains("RootX")) {
            rootPos = new Vec3d(nbt.getDouble("RootX"), nbt.getDouble("RootY"), nbt.getDouble("RootZ"));
        }
        if (nbt.contains("Extended")) extended = nbt.getFloat("Extended");
        if (nbt.contains("RetractSpeed")) retractSpeed = nbt.getFloat("RetractSpeed");
        if (nbt.contains("CurrentHole")) currentHole = nbt.getInt("CurrentHole");
        if (nbt.contains("Initialized")) initialized = nbt.getBoolean("Initialized");
        if (nbt.contains("WatchPhase")) watchPhase = nbt.getFloat("WatchPhase");
        if (nbt.contains("NextSuckAttempt")) nextSuckAttempt = nbt.getInt("NextSuckAttempt");
        if (nbt.contains("SuckingBlock")) suckingBlock = nbt.getBoolean("SuckingBlock");
        if (nbt.contains("StealingItem")) stealingItem = nbt.getBoolean("StealingItem");
        if (nbt.contains("SuckTimer")) suckTimer = nbt.getInt("SuckTimer");
        if (nbt.contains("MoveX")) {
            movementPoint = new Vec3d(nbt.getDouble("MoveX"), nbt.getDouble("MoveY"), nbt.getDouble("MoveZ"));
        } else {
            movementPoint = lookPoint;
        }
        if (nbt.contains("StolenDisplayTicks")) {
            stolenDisplayTicks = nbt.getInt("StolenDisplayTicks");
        }
        if (nbt.contains("HostileTicks")) {
            hostileTicks = nbt.getInt("HostileTicks");
        }
        if (nbt.containsUuid("HostileTarget")) {
            hostileTargetUuid = nbt.getUuid("HostileTarget");
        }
        if (nbt.contains("GrabTicks")) {
            grabTicks = nbt.getInt("GrabTicks");
        }
        if (nbt.contains("HarassMode")) {
            try {
                harassMode = HarassMode.valueOf(nbt.getString("HarassMode"));
            } catch (IllegalArgumentException ignored) {
                harassMode = HarassMode.FLING;
            }
        }
        if (nbt.contains("StolenDisplayStack")) {
            stolenDisplayStack = ItemStack.fromNbtOrEmpty(getRegistryManager(), nbt.getCompound("StolenDisplayStack"));
        }
        if (nbt.contains("SuckX")) {
            suckBlockPos = new BlockPos(nbt.getInt("SuckX"), nbt.getInt("SuckY"), nbt.getInt("SuckZ"));
            suckSurfacePos = new Vec3d(suckBlockPos.getX() + 0.5, suckBlockPos.getY() + 1.0 + SUCK_SURFACE_OFFSET, suckBlockPos.getZ() + 0.5);
        }
        if (nbt.contains("DrownX")) {
            drownAnchor = new Vec3d(nbt.getDouble("DrownX"), nbt.getDouble("DrownY"), nbt.getDouble("DrownZ"));
        }
    }

    private record SuckCandidate(BlockPos blockPos, Vec3d surfacePos, double score) {}
}