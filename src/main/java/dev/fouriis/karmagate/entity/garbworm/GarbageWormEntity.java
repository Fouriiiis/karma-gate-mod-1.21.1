package dev.fouriis.karmagate.entity.garbworm;

import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Garbage Worm entity — Rain World faithful port.
 *
 * Emerges from mycelium blocks, extends a tentacle body upward,
 * watches nearby creatures with curiosity/fear, and retracts when stressed.
 *
 * Entity position = head (small hitbox). Body/tentacle is rendered only.
 * Root position is pinned at the mycelium hole.
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

    // ── Constants ──────────────────────────────────────────────────────
    /** Base tentacle reach in blocks (C#: 400px / 20px/tile = 20 tiles, scaled to 8 for MC). */
    private static final float TENTACLE_LENGTH = 8.0f;
    private static final float AIR_FRICTION = 0.96f;
    private static final int SCAN_RANGE = 20;

    // ── Server-side state ──────────────────────────────────────────────
    private Vec3d rootPos = Vec3d.ZERO;
    private float extended = 1f;
    private float retractSpeed = 0.005f;
    private float bodySize = 1f;
    private float stress = 0f;
    private boolean showAsAngry = false;
    private Vec3d lookPoint = Vec3d.ZERO;
    private int attackCounter = 0;
    private int searchCounter = 0;
    private int comeBackOutCounter = 0;
    private int retractCounter = 0;
    private boolean lastExtended = true;
    /** Grace period after emerging — suppress retraction. */
    private int gracePeriod = 0;

    /** Debug tick counter for periodic logging. */
    private int debugLogTimer = 0;

    private final List<BlockPos> myceliumHoles = new ArrayList<>();
    private final List<BlockPos> floorTiles = new ArrayList<>();
    private BlockPos lookAtFloor;
    private int currentHole = -1;
    private boolean initialized = false;

    /** Entity-local head velocity (custom physics, NOT MC movement). */
    private Vec3d headVel = Vec3d.ZERO;

    /** Angry-at UUIDs is skipped — anger is per-instance via lastAttacker. */

    // ════════════════════════════════════════════════════════════════════
    //  Registration helpers
    // ════════════════════════════════════════════════════════════════════

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 15.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)   // sessile
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 20.0);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Constructor
    // ════════════════════════════════════════════════════════════════════

    public GarbageWormEntity(EntityType<? extends GarbageWormEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        // C#: bodySize is stored in GarbageWormState (0.8–1.2 range)
        this.bodySize = 0.8f + random.nextFloat() * 0.4f;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Overrides
    // ════════════════════════════════════════════════════════════════════

    @Override
    protected void initGoals() {
        // All behaviour is custom — no MC goal selectors needed.
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
    }

    @Override
    public void tick() {
        this.noClip = true;
        // Zero out MC's velocity so the built-in systems never move us.
        this.setVelocity(Vec3d.ZERO);
        super.tick();

        if (!getWorld().isClient) {
            serverTick();
        }
    }

    @Override public boolean isPushable() { return false; }
    @Override protected void pushAway(Entity entity) { /* sessile */ }
    @Override public boolean cannotDespawn() { return true; }

    /** Disable MC's built-in movement / gravity system entirely. */
    @Override
    public void travel(Vec3d movementInput) {
        // Do nothing — all movement is handled in updateHeadMovement().
    }

    /** Prevent suffocation damage when head is inside blocks near root. */
    @Override
    public boolean isInsideWall() {
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Server tick
    // ════════════════════════════════════════════════════════════════════

    private void serverTick() {
        if (!initialized) {
            initialize();
            initialized = true;
        }

        // ── extend / retract ──
        extended += retractSpeed;
        extended = MathHelper.clamp(extended, 0f, 1f);
        boolean currentlyExtended = extended > 0f;

        if (extended == 0f && lastExtended) {
            // Just fully retracted — hide below root.
            setPosition(rootPos.x, rootPos.y - 2.0, rootPos.z);
            headVel = Vec3d.ZERO;
        }

        lastExtended = currentlyExtended;

        if (gracePeriod > 0) gracePeriod--;

        if (currentlyExtended) {
            updateAI();
            updateHeadMovement();
        } else {
            // While retracted, decide when to re-emerge.
            // retractCounter must reach 0 before comeBackOutCounter can accumulate.
            // Bug fix: retractCounter can be >0 here if a threat was still nearby
            // during the retraction phase (updateAI increments it while extended>0
            // and retracting). We must decrement it while retracted too, otherwise
            // the worm is permanently stuck underground.
            if (retractCounter > 0) {
                retractCounter--;
                comeBackOutCounter = 0;
            } else {
                // C#: comeBackOutCounter += Random(0,3)
                comeBackOutCounter += random.nextInt(3);
                if (comeBackOutCounter > 80) {
                    doExtend();
                }
            }
        }

        syncTrackedData();

        // ── Debug logging (every 60 ticks / ~3 seconds) ──
        debugLogTimer++;
        if (debugLogTimer >= 60) {
            debugLogTimer = 0;
            String state;
            if (extended > 0f) {
                state = retractSpeed >= 0 ? "EXTENDING" : "RETRACTING";
            } else {
                state = "RETRACTED";
            }
            LOGGER.info("[GarbageWorm id={}] state={} extended={} retractSpeed={} " +
                            "stress={} retractCounter={} comeBackOutCounter={} " +
                            "gracePeriod={} attackCounter={} holes={} " +
                            "rootPos=({},{},{}) headPos=({},{},{}) lookPoint=({},{},{})",
                    getId(), state,
                    String.format("%.3f", extended),
                    String.format("%.4f", retractSpeed),
                    String.format("%.3f", stress),
                    retractCounter, comeBackOutCounter,
                    gracePeriod, attackCounter,
                    myceliumHoles.size(),
                    String.format("%.1f", rootPos.x), String.format("%.1f", rootPos.y), String.format("%.1f", rootPos.z),
                    String.format("%.1f", getX()), String.format("%.1f", getY()), String.format("%.1f", getZ()),
                    String.format("%.1f", lookPoint.x), String.format("%.1f", lookPoint.y), String.format("%.1f", lookPoint.z));
        }
    }

    // ── Initialisation (first tick) ────────────────────────────────────

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
            // Fallback: use spawn pos as root, extend upward.
            rootPos = spawnPos;
            lookPoint = rootPos.add(0, 3, 0);
            setPosition(rootPos.x, rootPos.y + 3, rootPos.z);
        }
        dataTracker.set(BODY_SIZE_DATA, bodySize);
    }

    /**
     * Scan for vanilla mycelium blocks within {@code SCAN_RANGE} of {@code center}.
     * These serve the role of "garbage holes" from the C# original.
     */
    private void scanForMycelium(Vec3d center) {
        myceliumHoles.clear();
        BlockPos cp = BlockPos.ofFloored(center);
        World w = getWorld();

        for (int dx = -SCAN_RANGE; dx <= SCAN_RANGE; dx++) {
            for (int dy = -SCAN_RANGE; dy <= SCAN_RANGE; dy++) {
                for (int dz = -SCAN_RANGE; dz <= SCAN_RANGE; dz++) {
                    BlockPos bp = cp.add(dx, dy, dz);
                    if (w.getBlockState(bp).isOf(Blocks.MYCELIUM)) {
                        // Need air above to emerge.
                        if (!w.getBlockState(bp.up()).isSolidBlock(w, bp.up())) {
                            myceliumHoles.add(bp.toImmutable());
                        }
                    }
                }
            }
        }
    }

    // ── Hole management ────────────────────────────────────────────────

    /**
     * Pick a new hole (mycelium block) to emerge from.
     * C#: GarbageWorm.NewHole
     */
    private void pickNewHole(boolean burrowed) {
        if (myceliumHoles.isEmpty()) {
            retractSpeed = -1f / 30f;
            return;
        }
        currentHole = random.nextInt(myceliumHoles.size());
        BlockPos holePos = myceliumHoles.get(currentHole);

        // Root sits visibly on top of the mycelium block surface.
        rootPos = new Vec3d(holePos.getX() + 0.5, holePos.getY() + 1.05, holePos.getZ() + 0.5);

        // Default lookPoint above root so head starts in a visible position.
        lookPoint = rootPos.add(0, TENTACLE_LENGTH * bodySize * 0.4, 0);

        if (!burrowed) {
            // Place head above root at ~half tentacle reach.
            setPosition(rootPos.x, rootPos.y + TENTACLE_LENGTH * bodySize * 0.5, rootPos.z);
            headVel = Vec3d.ZERO;
        }

        mapFloor();
    }

    /**
     * C#: GarbageWormAI.MapFloor
     * Builds a list of floor-level positions near the hole for idle searching.
     */
    private void mapFloor() {
        floorTiles.clear();
        // All mycelium holes double as floor search positions.
        for (BlockPos hole : myceliumHoles) {
            floorTiles.add(hole);
        }
        // Also walk outward ±6 blocks looking for solid floor.
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

    // ════════════════════════════════════════════════════════════════════
    //  AI  (C#: GarbageWormAI.Update, simplified)
    // ════════════════════════════════════════════════════════════════════

    private void updateAI() {
        showAsAngry = false;

        // ── Attack sequence counter ──
        if (attackCounter > 0) {
            attackCounter++;
            if (attackCounter > 180) {
                attackCounter = 0;
            }
            return; // skip normal AI while attacking
        }

        // ── Find nearest threat ──
        LivingEntity nearestThreat = null;
        float nearestDist = Float.MAX_VALUE;
        float dangerAccum = 0f;

        double searchRadius = TENTACLE_LENGTH * bodySize * 2.0;
        List<LivingEntity> nearby = getWorld().getEntitiesByClass(
                LivingEntity.class,
                getBoundingBox().expand(searchRadius),
                e -> e != this && e.isAlive() && !e.isSpectator()
        );

        for (LivingEntity entity : nearby) {
            float dist = (float) distanceTo(entity);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestThreat = entity;
            }
            float danger = entityDanger(entity);
            if (dist > 0.01f) {
                // C#: danger *= (7 + vel.magnitude), danger /= distance
                // Scaled down for MC distances — accumulates gradually.
                float contribution = danger * (7f + (float) entity.getVelocity().length() * 20f);
                contribution /= (dist * dist); // quadratic falloff
                dangerAccum += contribution / 80f;
            }
        }

        // C#: stress -= 0.005f each tick
        stress += dangerAccum - 0.005f;
        stress = MathHelper.clamp(stress, 0f, 1f);

        if (nearestThreat != null && nearestDist < searchRadius) {
            // ── Creature of interest visible ──
            lookPoint = nearestThreat.getEyePos();
            searchCounter = 0;

            // Retraction logic (C#: retractCounter)
            // Grace period suppresses retraction after emerging.
            float threatDistFromRoot = (float) rootPos.distanceTo(nearestThreat.getPos());
            if (gracePeriod <= 0 && stress > 0.9f && threatDistFromRoot < 17f) {
                retractCounter++;
                if (threatDistFromRoot < 2f) retractCounter += 4;
                if (nearestDist < 2f) retractCounter++;
                if (retractCounter > 80) {
                    doRetract();
                    retractCounter = 0;
                }
            } else if (retractCounter > 0) {
                retractCounter--;
            }

            // Anger display
            if (getAttacker() != null && getAttacker() == nearestThreat) {
                showAsAngry = true;
            }
        } else {
            // ── Idle: search floor ──
            // C#: pick random floor tile, peck at garbage occasionally.
            if ((random.nextFloat() < 0.025f || searchCounter < 5)) {
                // Idle: worm looks around at random points ABOVE the root,
                // creating the characteristic curious peering behaviour.
                float maxReach = TENTACLE_LENGTH * bodySize;
                float idleHeight = 2.0f + random.nextFloat() * (maxReach * 0.5f);
                float hSpread = 1.5f + random.nextFloat() * 2.5f;
                lookPoint = rootPos.add(
                        (random.nextFloat() - 0.5f) * hSpread,
                        idleHeight,
                        (random.nextFloat() - 0.5f) * hSpread
                );
            }
            searchCounter++;

            // C#: if (searchCounter>100 && random<0.003 && close to lookPoint) → attack
            if (searchCounter > 100 && random.nextFloat() < 0.006f) {
                Vec3d headPos = getPos();
                if (headPos.distanceTo(lookPoint) < 5.0) {
                    attackCounter = 1;
                    searchCounter = 0;
                }
            }

            if (retractCounter > 0) retractCounter--;
        }
    }

    /**
     * C#: CreatureInterest.danger based on template bodySize.
     */
    private float entityDanger(LivingEntity entity) {
        if (entity instanceof PlayerEntity) return 1.5f;
        return entity.getHeight() > 1.0f ? entity.getHeight() : 0f;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Head movement  (C#: GarbageWorm.Update body-chunk physics)
    // ════════════════════════════════════════════════════════════════════

    private void updateHeadMovement() {
        Vec3d headPos = getPos();

        if (attackCounter > 0) {
            // ── Attack sequence ──
            if (attackCounter < 20) {
                // C#: slow attraction
                Vec3d dir = lookPoint.subtract(headPos);
                if (dir.length() > 0.01) {
                    headVel = headVel.add(dir.normalize().multiply(0.005));
                }
            } else if (attackCounter < 40) {
                // C#: fast lunge — goalAttractionSpeedTip = 40
                Vec3d dir = lookPoint.subtract(headPos);
                headVel = headVel.add(dir.multiply(0.1));
            } else if (attackCounter < 150) {
                // C#: held at lookPoint (swallowing)
                setPosition(lookPoint.x, lookPoint.y, lookPoint.z);
                headVel = Vec3d.ZERO;
                return;
            } else {
                // C#: drift upward / recover
                Vec3d upTarget = rootPos.add(0, TENTACLE_LENGTH * bodySize * 0.5, 0);
                Vec3d dir = upTarget.subtract(headPos);
                if (dir.length() > 0.01) {
                    headVel = headVel.add(dir.normalize().multiply(0.002));
                }
            }
        } else {
            // ── Normal goal attraction ──
            // C#: goalAttractionSpeedTip = Lerp(0.15, 1.9, InverseLerp(40, 290, dist))
            Vec3d dir = lookPoint.subtract(headPos);
            float dist = (float) dir.length();
            float searchRange = searchCounter > 0 ? 4.5f : 14.5f;
            float attraction = MathHelper.lerp(
                    MathHelper.clamp((dist - 2f) / searchRange, 0f, 1f),
                    0.004f, 0.04f
            );
            if (dist > 0.01) {
                headVel = headVel.add(dir.normalize().multiply(attraction));
            }

            // C#: bodyChunks[0].vel.y += gravity  (counteracts gravity — head floats)
            // We add a tiny upward bias to keep head above root.
            headVel = headVel.add(0, 0.002, 0);
        }

        // Air friction  (C#: airFriction = 0.99, ×2 ticks ≈ 0.98)
        headVel = headVel.multiply(AIR_FRICTION);

        // Apply
        Vec3d newPos = headPos.add(headVel);

        // ── Distance constraint  (C#: clamp to tentacle reach) ──
        double maxReach = TENTACLE_LENGTH * bodySize * extended;
        Vec3d rootToHead = newPos.subtract(rootPos);
        if (rootToHead.length() > maxReach && maxReach > 0.01) {
            newPos = rootPos.add(rootToHead.normalize().multiply(maxReach));
            headVel = headVel.multiply(0.8);
        }

        // ── Simple block collision (skip near root so head can emerge) ──
        double distFromRoot = newPos.distanceTo(rootPos);
        if (distFromRoot > 2.0) {
            BlockPos newBlock = BlockPos.ofFloored(newPos);
            World w = getWorld();
            if (w.getBlockState(newBlock).isSolidBlock(w, newBlock)) {
                headVel = headVel.multiply(-0.3);
                newPos = headPos;
            }
        }

        setPosition(newPos.x, newPos.y, newPos.z);
    }

    // ── Retract / Extend ───────────────────────────────────────────────

    /** C#: GarbageWorm.Retract */
    private void doRetract() {
        retractSpeed = -1f / 30f;
        LOGGER.info("[GarbageWorm id={}] RETRACT triggered (stress={}, retractCounter was >80)",
                getId(), String.format("%.3f", stress));
    }

    /** C#: GarbageWorm.Extend */
    private void doExtend() {
        LOGGER.info("[GarbageWorm id={}] EXTEND triggered (comeBackOutCounter={}, holes={})",
                getId(), comeBackOutCounter, myceliumHoles.size());
        // Re-scan mycelium if list lost (e.g. after world reload / NBT load)
        if (myceliumHoles.isEmpty()) {
            scanForMycelium(rootPos);
            LOGGER.info("[GarbageWorm id={}] Re-scanned mycelium, found {} holes",
                    getId(), myceliumHoles.size());
        }
        pickNewHole(true);
        retractSpeed = 1f / 10f;  // fast extension (~0.5s; C# constructor uses 1.0 = instant)
        extended = 0.05f;         // small seed so maxReach allows head movement immediately
        comeBackOutCounter = 0;
        gracePeriod = 120; // ~6 seconds of immunity after emerging
        stress = 0f;
        retractCounter = 0;
        // Target above root so the head rises visibly.
        lookPoint = rootPos.add(0, TENTACLE_LENGTH * bodySize * 0.4, 0);
        // Place head at root surface so it emerges from the hole.
        setPosition(rootPos.x, rootPos.y + 0.5, rootPos.z);
        headVel = new Vec3d(0, 0.15, 0); // strong upward push to emerge
    }

    // ── Data sync ──────────────────────────────────────────────────────

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
    }

    // ════════════════════════════════════════════════════════════════════
    //  Client-side getters  (used by renderer)
    // ════════════════════════════════════════════════════════════════════

    public Vec3d getRootPos()  { return new Vec3d(dataTracker.get(ROOT_X), dataTracker.get(ROOT_Y), dataTracker.get(ROOT_Z)); }
    public float getExtended() { return dataTracker.get(EXTENDED); }
    public float getStress()   { return dataTracker.get(STRESS_DATA); }
    public boolean isShowAngry() { return dataTracker.get(SHOW_ANGRY); }
    public Vec3d getLookPoint() { return new Vec3d(dataTracker.get(LOOK_X), dataTracker.get(LOOK_Y), dataTracker.get(LOOK_Z)); }
    public int getAttackCtr()  { return dataTracker.get(ATTACK_CTR); }
    public float getBodySizeValue() { return dataTracker.get(BODY_SIZE_DATA); }

    // ════════════════════════════════════════════════════════════════════
    //  NBT persistence
    // ════════════════════════════════════════════════════════════════════

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
    }
}
