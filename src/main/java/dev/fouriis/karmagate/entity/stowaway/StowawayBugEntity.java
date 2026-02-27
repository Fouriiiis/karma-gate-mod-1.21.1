package dev.fouriis.karmagate.entity.stowaway;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

/**
 * The Stowaway Bug entity: a creature with feeler tentacles (passive, procedural)
 * and grabbing tentacles (active, AI-driven).
 *
 * Core features:
 * - Multiple feeler tentacles that sway and drape over terrain
 * - 3 grabbing head tentacles that fire, hook, and retract prey
 * - AI behavior with state-based actions (Idle, Attacking, Hidden, Sleeping)
 * - Procedural rope physics (client-side only)
 */
public class StowawayBugEntity extends Entity {
    private static final Logger LOGGER = LoggerFactory.getLogger(StowawayBugEntity.class);
    
    // Tracked data for synchronization
    private static final TrackedData<Float> HOME_POS_X = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HOME_POS_Y = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HOME_POS_Z = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> PLACED_DIR_X = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> PLACED_DIR_Y = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> SLEEP_SCALE = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> TENTACLES_WITHDRAWN = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> MOUTH_OPEN = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Integer> HUNT_DELAY = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.INTEGER);
    
    // Grabber head sync data (3 heads)
    private static final TrackedData<Boolean> HEAD_0_FIRED = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> HEAD_1_FIRED = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> HEAD_2_FIRED = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> HEAD_0_TARGET_X = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HEAD_0_TARGET_Y = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HEAD_0_TARGET_Z = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HEAD_1_TARGET_X = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HEAD_1_TARGET_Y = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HEAD_1_TARGET_Z = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HEAD_2_TARGET_X = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HEAD_2_TARGET_Y = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> HEAD_2_TARGET_Z = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.FLOAT);
    // Grabbed entity IDs (-1 = none)
    private static final TrackedData<Integer> HEAD_0_GRABBED_ID = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> HEAD_1_GRABBED_ID = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> HEAD_2_GRABBED_ID = DataTracker.registerData(StowawayBugEntity.class, TrackedDataHandlerRegistry.INTEGER);
    
    // Entity data
    protected Vec3d homePos;
    protected Vec3d placedDirection;
    protected float sleepScale;
    protected float tentaclesWithdrawn;
    protected boolean mouthOpen;
    protected int huntDelay;
    
    // Grabber server-side state
    private boolean[] headFired = new boolean[3];
    private Vec3d[] headTargets = new Vec3d[3];
    private float[] headCooldowns = new float[3];
    private LivingEntity[] grabbedEntities = new LivingEntity[3];
    private int spitCooldown = 0;  // C#: spitCooldown timer between head fires
    private Random random = new Random();
    
    // AI
    public StowawayBugAI ai;
    
    // Client-side only: tentacle systems
    private FeelerTentacle[] feelerTentacles;
    private GrabbingTentacle[] grabbingTentacles;
    private int numFeeelers = 8;  // Number of feeler tentacles
    private int numHeads = 3;  // 3 grabbing heads
    private boolean tentaclesInitialized = false;  // Track initialization
    
    // Constants (20 pixels in C# = 1 Minecraft block; C# runs at 40 FPS, MC at 20 TPS)
    // C#: num7 = Mathf.Lerp(10f, 1f, tentaclesWithdrawn) - so ~10 pixels = 0.5 blocks normally
    private static final float FEELER_LENGTH = 0.5f;  // Distance between segments (C#: 10 pixels)
    private static final int FEELER_SEGMENTS = 12;  // Segments per feeler (C# uses variable 5-20)
    private static final int GRABBER_SEGMENTS = 40;  // Segments per grabber head
    private static final float GRABBER_LENGTH = 0.5f;  // Distance between segments in grabber
    
    public StowawayBugEntity(EntityType<?> type, World world, Vec3d homePos) {
        super(type, world);
        this.homePos = homePos;
        this.placedDirection = new Vec3d(0, 1, 0);  // Default direction
        this.sleepScale = 0;
        this.tentaclesWithdrawn = 0;
        this.mouthOpen = false;
        this.huntDelay = 0;
        
        // Initialize grabber states
        for (int i = 0; i < numHeads; i++) {
            headFired[i] = false;
            headTargets[i] = Vec3d.ZERO;
            headCooldowns[i] = 0;
            grabbedEntities[i] = null;
        }
        
        // Initialize AI
        this.ai = new StowawayBugAI(this);
        
        // Client-side initialization will happen on first tick when entity position is synced
        this.tentaclesInitialized = false;
    }
    
    public StowawayBugEntity(EntityType<?> type, World world) {
        this(type, world, Vec3d.ZERO);
    }
    
    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(HOME_POS_X, 0.0f);
        builder.add(HOME_POS_Y, 0.0f);
        builder.add(HOME_POS_Z, 0.0f);
        builder.add(PLACED_DIR_X, 0.0f);
        builder.add(PLACED_DIR_Y, 1.0f);
        builder.add(SLEEP_SCALE, 0.0f);
        builder.add(TENTACLES_WITHDRAWN, 0.0f);
        builder.add(MOUTH_OPEN, false);
        builder.add(HUNT_DELAY, 0);
        
        // Grabber head trackers
        builder.add(HEAD_0_FIRED, false);
        builder.add(HEAD_1_FIRED, false);
        builder.add(HEAD_2_FIRED, false);
        builder.add(HEAD_0_TARGET_X, 0.0f);
        builder.add(HEAD_0_TARGET_Y, 0.0f);
        builder.add(HEAD_0_TARGET_Z, 0.0f);
        builder.add(HEAD_1_TARGET_X, 0.0f);
        builder.add(HEAD_1_TARGET_Y, 0.0f);
        builder.add(HEAD_1_TARGET_Z, 0.0f);
        builder.add(HEAD_2_TARGET_X, 0.0f);
        builder.add(HEAD_2_TARGET_Y, 0.0f);
        builder.add(HEAD_2_TARGET_Z, 0.0f);
        builder.add(HEAD_0_GRABBED_ID, -1);
        builder.add(HEAD_1_GRABBED_ID, -1);
        builder.add(HEAD_2_GRABBED_ID, -1);
    }
    
    @Override
    public void tick() {
        super.tick();
        
        if (!this.getWorld().isClient) {
            // Server-side logic
            huntDelay--;
            spitCooldown--;
            
            // Update head cooldowns
            for (int i = 0; i < numHeads; i++) {
                if (headCooldowns[i] > 0) {
                    headCooldowns[i] -= 0.1f;  // C#: headCooldown[i] -= 0.1f per frame (scaled for ticks)
                }
            }
            
            if (this.isAlive()) {
                ai.update();
                updateBehavior();
                updateGrabberTargeting();
                updateGrabDamage();
            }
            
            // Sync data to clients
            syncData();
        } else {
            // Client-side: sync data from tracker
            readSyncedData();
            
            // Initialize tentacles on first tick
            if (!tentaclesInitialized) {
                initializeTentacles();
                tentaclesInitialized = true;
            }
            
            // Update rope physics with proper world reference for collision
            World world = this.getWorld();
            
            if (feelerTentacles != null) {
                // Calculate individual attach positions for each feeler (radial spread)
                for (int i = 0; i < feelerTentacles.length; i++) {
                    float angle = feelerTentacles[i].getInitialAngle();
                    float radius = 0.6f;
                    float offsetX = radius * (float) Math.cos(angle);
                    float offsetZ = radius * (float) Math.sin(angle);
                    Vec3d attachPos = getAttachmentPos().add(offsetX, 0, offsetZ);
                    feelerTentacles[i].update(attachPos, sleepScale, tentaclesWithdrawn, world);
                }
            }
            
            if (grabbingTentacles != null) {
                // Body direction for coiled tentacle positioning (matches C# placedDirection)
                Vec3d bodyDir = placedDirection.normalize();
                
                for (int i = 0; i < grabbingTentacles.length; i++) {
                    float angle = (float) (i * 2.0 * Math.PI / numHeads);
                    float radius = 0.2f;
                    float offsetX = radius * (float) Math.cos(angle);
                    float offsetZ = radius * (float) Math.sin(angle);
                    Vec3d headBasePos = this.getEyePos().add(offsetX, 0, offsetZ);
                    grabbingTentacles[i].update(headBasePos, bodyDir, world);
                }
            }
        }
    }
    
    private void initializeTentacles() {
        LOGGER.info("Initializing tentacles: {} feelers, {} heads at pos {}", numFeeelers, numHeads, this.getPos());
        
        // Initialize feeler tentacles in a circular pattern
        feelerTentacles = new FeelerTentacle[numFeeelers];
        for (int i = 0; i < numFeeelers; i++) {
            // Radial offset around entity (spread for natural hanging)
            float angle = (float) (i * 2.0 * Math.PI / numFeeelers);
            float radius = 0.6f; // Larger offset so tentacles spread around entity
            float offsetX = radius * (float) Math.cos(angle);
            float offsetZ = radius * (float) Math.sin(angle);
            
            Vec3d attachPos = getAttachmentPos().add(offsetX, 0, offsetZ);
            feelerTentacles[i] = new FeelerTentacle(attachPos, FEELER_SEGMENTS, FEELER_LENGTH, angle);
        }
        
        // Initialize grabbing tentacles (heads) in triangular pattern
        grabbingTentacles = new GrabbingTentacle[numHeads];
        for (int i = 0; i < numHeads; i++) {
            float angle = (float) (i * 2.0 * Math.PI / numHeads);
            float radius = 0.2f;
            float offsetX = radius * (float) Math.cos(angle);
            float offsetZ = radius * (float) Math.sin(angle);
            
            Vec3d basePos = this.getEyePos().add(offsetX, 0, offsetZ);
            grabbingTentacles[i] = new GrabbingTentacle(basePos, GRABBER_SEGMENTS, GRABBER_LENGTH, this);
        }
        
        LOGGER.info("Tentacles initialized successfully");
    }
    
    private void updateBehavior() {
        // Update body state based on AI behavior
        if (ai.getBehavior() == StowawayBugAI.Behavior.SLEEPING || ai.getBehavior() == StowawayBugAI.Behavior.HIDDEN) {
            sleepScale = Math.min(1.0f, sleepScale + 0.01f);
            tentaclesWithdrawn = Math.min(1.0f, tentaclesWithdrawn + 0.09f);
        } else {
            sleepScale = Math.max(0, sleepScale - 0.07f);
            tentaclesWithdrawn = Math.max(0, tentaclesWithdrawn - 0.11f);
        }
        
        mouthOpen = ai.shouldMouthBeOpen();
    }
    
    /**
     * Server-side grabber targeting logic.
     * Scans for nearby living entities and fires heads towards them.
     * Matches C# StowawayBug behavior when attacking.
     */
    private void updateGrabberTargeting() {
        // Only attack when not sleeping/hidden
        if (ai.getBehavior() == StowawayBugAI.Behavior.SLEEPING || 
            ai.getBehavior() == StowawayBugAI.Behavior.HIDDEN) {
            return;
        }
        
        // C#: Detection range ~15 blocks for heads
        double detectionRange = 15.0;
        Box searchBox = this.getBoundingBox().expand(detectionRange);
        
        // Find nearby living entities (excluding self, spectators, and non-survival players)
        List<LivingEntity> nearbyEntities = this.getWorld().getEntitiesByClass(
            LivingEntity.class, 
            searchBox, 
            entity -> {
                if (entity.equals(this) || !entity.isAlive() || entity.isSpectator()) {
                    return false;
                }
                // Ignore players not in survival mode
                if (entity instanceof PlayerEntity player) {
                    return !player.isCreative() && !player.isSpectator();
                }
                return true;
            }
        );
        
        // Process each head
        for (int i = 0; i < numHeads; i++) {
            // Update retraction for already fired heads
            if (headFired[i]) {
                // Server-side grab detection: check if any entity is near the target position
                // This approximates the tentacle tip position
                if (grabbedEntities[i] == null && headCooldowns[i] > 10) {
                    Vec3d targetPos = headTargets[i];
                    double grabRadius = 1.5;  // C#: radius + 16 pixels
                    
                    for (LivingEntity entity : nearbyEntities) {
                        if (entity.getPos().distanceTo(targetPos) < grabRadius) {
                            grabbedEntities[i] = entity;
                            break;
                        }
                    }
                }
                
                // Check if head should retract - simplified server-side tracking
                // The actual retraction physics happens client-side
                if (headCooldowns[i] <= 0) {
                    headFired[i] = false;  // Head fully retracted
                    grabbedEntities[i] = null;
                }
            }
        }
        
        // Fire heads at targets if not on cooldown
        if (spitCooldown <= 0 && !nearbyEntities.isEmpty()) {
            // Find a head that's ready to fire
            for (int k = 0; k < numHeads; k++) {
                if (!headFired[k] && headCooldowns[k] <= 0) {
                    // Pick a target - C# picks from prey list
                    LivingEntity target = nearbyEntities.get(random.nextInt(nearbyEntities.size()));
                    
                    // C#: fire toward prey.DangerPos + vel * Random.value * (10-45)
                    Vec3d targetPos = target.getEyePos();
                    Vec3d targetVel = target.getVelocity();
                    float leadTime = 10 + random.nextFloat() * 35;
                    Vec3d aimPos = targetPos.add(targetVel.multiply(leadTime * 0.05));  // Scale for MC ticks
                    
                    // Fire this head
                    headFired[k] = true;
                    headTargets[k] = aimPos;
                    headCooldowns[k] = 20 + random.nextFloat() * 20;  // C#: Random.Range(20, 40)
                    
                    // C#: spitCooldown = 40-60 between firing heads
                    spitCooldown = 20 + random.nextInt(20);  // Scaled for MC ticks
                    
                    // Open mouth when attacking
                    mouthOpen = true;
                    
                    break;  // Fire one head at a time
                }
            }
        }
    }
    
    /**
     * Server-side damage dealing for grabbed entities.
     * Applies damage when grabbed entities are pulled close to the mouth.
     * C#: creature takes damage when pulled to body
     */
    private void updateGrabDamage() {
        float damageRadius = 1.5f;  // Distance from body for damage
        float damageAmount = 2.0f;  // 1 heart of damage
        
        for (int i = 0; i < numHeads; i++) {
            if (grabbedEntities[i] != null && grabbedEntities[i].isAlive()) {
                LivingEntity grabbed = grabbedEntities[i];
                double distToBody = grabbed.getPos().distanceTo(this.getPos());
                
                // Calculate retraction progress based on head cooldown
                // headCooldowns starts at ~30 and decreases to 0
                float retractProgress = 1.0f - (headCooldowns[i] / 30.0f);
                retractProgress = Math.max(0, Math.min(1, retractProgress));
                
                // Apply damage when pulled close (C# deals damage when retracted)
                if (distToBody < damageRadius) {
                    // C#: creature.Violence(...)
                    grabbed.damage(this.getDamageSources().mobAttack(null), damageAmount);
                    
                    // Apply continuing damage while close
                    if (this.age % 20 == 0) {  // Once per second
                        grabbed.damage(this.getDamageSources().mobAttack(null), damageAmount);
                    }
                }
                
                // Pull grabbed entity toward body - strength increases as tentacle retracts
                // This creates matching movement with the visual tentacle retraction
                float pullStrength = 0.05f + retractProgress * 0.15f;  // 0.05 to 0.2
                Vec3d pullDir = this.getPos().subtract(grabbed.getPos()).normalize().multiply(pullStrength);
                grabbed.setVelocity(grabbed.getVelocity().multiply(0.8).add(pullDir));  // Dampen existing velocity + add pull
                grabbed.velocityModified = true;
                
                // Check if entity is dead or too far - release grab
                if (!grabbed.isAlive() || distToBody > 20.0) {
                    grabbedEntities[i] = null;
                }
            }
        }
    }
    
    private void syncData() {
        dataTracker.set(HOME_POS_X, (float) homePos.x);
        dataTracker.set(HOME_POS_Y, (float) homePos.y);
        dataTracker.set(HOME_POS_Z, (float) homePos.z);
        dataTracker.set(PLACED_DIR_X, (float) placedDirection.x);
        dataTracker.set(PLACED_DIR_Y, (float) placedDirection.y);
        dataTracker.set(SLEEP_SCALE, sleepScale);
        dataTracker.set(TENTACLES_WITHDRAWN, tentaclesWithdrawn);
        dataTracker.set(MOUTH_OPEN, mouthOpen);
        dataTracker.set(HUNT_DELAY, huntDelay);
        
        // Sync grabber head states
        dataTracker.set(HEAD_0_FIRED, headFired[0]);
        dataTracker.set(HEAD_1_FIRED, headFired[1]);
        dataTracker.set(HEAD_2_FIRED, headFired[2]);
        dataTracker.set(HEAD_0_TARGET_X, (float) headTargets[0].x);
        dataTracker.set(HEAD_0_TARGET_Y, (float) headTargets[0].y);
        dataTracker.set(HEAD_0_TARGET_Z, (float) headTargets[0].z);
        dataTracker.set(HEAD_1_TARGET_X, (float) headTargets[1].x);
        dataTracker.set(HEAD_1_TARGET_Y, (float) headTargets[1].y);
        dataTracker.set(HEAD_1_TARGET_Z, (float) headTargets[1].z);
        dataTracker.set(HEAD_2_TARGET_X, (float) headTargets[2].x);
        dataTracker.set(HEAD_2_TARGET_Y, (float) headTargets[2].y);
        dataTracker.set(HEAD_2_TARGET_Z, (float) headTargets[2].z);
        
        // Sync grabbed entity IDs
        dataTracker.set(HEAD_0_GRABBED_ID, grabbedEntities[0] != null ? grabbedEntities[0].getId() : -1);
        dataTracker.set(HEAD_1_GRABBED_ID, grabbedEntities[1] != null ? grabbedEntities[1].getId() : -1);
        dataTracker.set(HEAD_2_GRABBED_ID, grabbedEntities[2] != null ? grabbedEntities[2].getId() : -1);
    }
    
    /**
     * Read synced data from the data tracker (client-side).
     * This syncs values from server to client for visual updates.
     */
    private void readSyncedData() {
        homePos = new Vec3d(
            dataTracker.get(HOME_POS_X),
            dataTracker.get(HOME_POS_Y),
            dataTracker.get(HOME_POS_Z)
        );
        placedDirection = new Vec3d(
            dataTracker.get(PLACED_DIR_X),
            dataTracker.get(PLACED_DIR_Y),
            0  // Z component not tracked, default to 0
        ).normalize();
        sleepScale = dataTracker.get(SLEEP_SCALE);
        tentaclesWithdrawn = dataTracker.get(TENTACLES_WITHDRAWN);
        mouthOpen = dataTracker.get(MOUTH_OPEN);
        huntDelay = dataTracker.get(HUNT_DELAY);
        
        // Read grabber head states and fire on client-side if newly fired
        if (grabbingTentacles != null) {
            boolean[] serverHeadFired = {
                dataTracker.get(HEAD_0_FIRED),
                dataTracker.get(HEAD_1_FIRED),
                dataTracker.get(HEAD_2_FIRED)
            };
            Vec3d[] serverHeadTargets = {
                new Vec3d(dataTracker.get(HEAD_0_TARGET_X), dataTracker.get(HEAD_0_TARGET_Y), dataTracker.get(HEAD_0_TARGET_Z)),
                new Vec3d(dataTracker.get(HEAD_1_TARGET_X), dataTracker.get(HEAD_1_TARGET_Y), dataTracker.get(HEAD_1_TARGET_Z)),
                new Vec3d(dataTracker.get(HEAD_2_TARGET_X), dataTracker.get(HEAD_2_TARGET_Y), dataTracker.get(HEAD_2_TARGET_Z))
            };
            int[] serverGrabbedIds = {
                dataTracker.get(HEAD_0_GRABBED_ID),
                dataTracker.get(HEAD_1_GRABBED_ID),
                dataTracker.get(HEAD_2_GRABBED_ID)
            };
            
            for (int i = 0; i < numHeads && i < grabbingTentacles.length; i++) {
                // Fire the client-side tentacle if server says it's newly fired and it's not already firing
                if (serverHeadFired[i] && !grabbingTentacles[i].isFired()) {
                    grabbingTentacles[i].fireToward(serverHeadTargets[i]);
                }
                
                // Sync grabbed entity from server - look up entity by ID
                int grabbedId = serverGrabbedIds[i];
                if (grabbedId != -1) {
                    Entity entity = this.getWorld().getEntityById(grabbedId);
                    if (entity instanceof LivingEntity living) {
                        grabbingTentacles[i].setGrabbedEntity(living);
                    }
                } else {
                    // Server says no grab - clear client grab
                    grabbingTentacles[i].setGrabbedEntity(null);
                }
            }
        }
    }
    
    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        if (nbt.contains("HomePos")) {
            NbtCompound homeTag = nbt.getCompound("HomePos");
            homePos = new Vec3d(homeTag.getDouble("X"), homeTag.getDouble("Y"), homeTag.getDouble("Z"));
        }
        if (nbt.contains("PlacedDir")) {
            NbtCompound dirTag = nbt.getCompound("PlacedDir");
            placedDirection = new Vec3d(dirTag.getDouble("X"), dirTag.getDouble("Y"), dirTag.getDouble("Z"));
        }
    }
    
    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        NbtCompound homeTag = new NbtCompound();
        homeTag.putDouble("X", homePos.x);
        homeTag.putDouble("Y", homePos.y);
        homeTag.putDouble("Z", homePos.z);
        nbt.put("HomePos", homeTag);
        
        NbtCompound dirTag = new NbtCompound();
        dirTag.putDouble("X", placedDirection.x);
        dirTag.putDouble("Y", placedDirection.y);
        dirTag.putDouble("Z", placedDirection.z);
        nbt.put("PlacedDir", dirTag);
    }
    
    // ============ Rendering helpers ============
    
    /**
     * The tentacles extend far past the entity's hitbox; enlarge the render
     * bounding box so they're not culled when the body itself goes off screen.
     */
    // Not an override in 1.21; defined here for clarity if needed by renderers.
    // Frustum culling actually uses getVisibilityBoundingBox(), which we expand below.
    public Box getRenderBoundingBox() {
        // roughly cover the maximum reach of a tentacle (≈6 blocks) plus some margin
        return this.getBoundingBox().expand(8.0, 8.0, 8.0);
    }

    /**
     * Visibility bounding box controls frustum culling. Use the same as
     * render box so the entity remains "visible" while tentacles are on screen.
     */
    @Override
    public Box getVisibilityBoundingBox() {
        // ensure visibility check uses expanded bounds for tentacles
        return this.getBoundingBox().expand(8.0, 8.0, 8.0);
    }

    // ============ Getters ============
    
    public Vec3d getHomePos() {
        return homePos;
    }
    
    public void setHomePos(Vec3d pos) {
        this.homePos = pos;
    }
    
    public Vec3d getPlacedDirection() {
        return placedDirection;
    }
    
    public void setPlacedDirection(Vec3d dir) {
        this.placedDirection = dir.normalize();
    }
    
    public float getSleepScale() {
        return sleepScale;
    }
    
    public float getTentaclesWithdrawn() {
        return tentaclesWithdrawn;
    }
    
    public boolean isMouthOpen() {
        return mouthOpen;
    }
    
    public StowawayBugAI getAI() {
        return ai;
    }
    
    public FeelerTentacle[] getFeelerTentacles() {
        return feelerTentacles;
    }
    
    public GrabbingTentacle[] getGrabbingTentacles() {
        return grabbingTentacles;
    }
    
    // Attachment point for feelers (derived from body + placedDirection like in C# version)
    private Vec3d getAttachmentPos() {
        return this.getPos().add(placedDirection.multiply(1.5f));
    }
    
    @Override
    public boolean canStartRiding(Entity entity) {
        return false;
    }
    
    @Override
    public boolean isAttackable() {
        return true;
    }
    
    @Override
    public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
        // Can be damaged
        if (ai != null) {
            ai.onDamaged();
        }
        return super.damage(source, amount);
    }
}
