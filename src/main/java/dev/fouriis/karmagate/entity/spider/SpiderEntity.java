package dev.fouriis.karmagate.entity.spider;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.GameMode;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Random;

/**
 * Rain World-style coalmine spider entity.
 * 
 * Individual spiders are small dark creatures that:
 * - Crawl along surfaces (walls, floors, ceilings)
 * - Form flocks with nearby spiders
 * - Can merge into centipede-like chains to hunt prey
 * - Flee from light / stay in dark areas
 * - Spawning one creates a whole group (flock) nearby
 * 
 * Uses a zone-based adjacency system instead of Rain World's room system.
 */
public class SpiderEntity extends MobEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpiderEntity.class);

    // --- Synced data ---
    private static final TrackedData<Float> SIZE_FACTOR = DataTracker.registerData(SpiderEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> BLOOD_LUST = DataTracker.registerData(SpiderEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> IS_IDLE = DataTracker.registerData(SpiderEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> IS_MOVING = DataTracker.registerData(SpiderEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> LEGS_POSITION = DataTracker.registerData(SpiderEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Integer> FLOCK_LEADER_ID = DataTracker.registerData(SpiderEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // --- C# Spider.IndividualVariations ---
    public float dominance;

    // --- Movement state ---
    public Vec3d prevTickPos = Vec3d.ZERO;
    public Vec3d direction = Vec3d.ZERO;
    public boolean inAccessibleTerrain = true;
    public int outsideAccessibleCounter = 0;
    public float lightExposure = 0f;
    public float lightToMove = 0f;
    public int idleCounter = 0;
    public float deathSpasms = 1f;
    public int noCentipedeCounter = 0;
    public float connectDistance;
    public int seenNoPreyCounter = 0;

    // --- Hunting / prey tracking (C# Spider.centipede prey system) ---
    /** Current prey target shared across the flock */
    @Nullable
    private LivingEntity flockPrey = null;
    /** Position to flee from (C# Spider.moveAwayFromPos) */
    @Nullable
    public Vec3d moveAwayFromPos = null;
    /** Whether this spider is currently in a chain formation */
    public boolean inChain = false;
    /** Entity ID of the spider ahead of us in a chain (-1 = none) */
    public int chainFrontId = -1;
    /** Entity ID of the spider behind us in a chain (-1 = none) */
    public int chainBehindId = -1;
    /** Counter for how long since we could form a centipede chain */
    public int chainFormationCooldown = 0;
    /** C# Spider.idle: true when spider is sitting still in darkness */
    public boolean idle = false;
    /** C# Spider.moving: true when spider is actively crawling */
    public boolean moving = false;
    /** Visual counter for how long we've been tracking prey */
    public int preyVisualCounter = 0;
    /** C# hunt: how aggressively hunting (0-1) */
    public float huntIntensity = 0f;

    // --- Surface crawling ---
    public float surfaceNormalX = 0f, surfaceNormalY = 1f, surfaceNormalZ = 0f;
    public float prevSurfaceNormalX = 0f, prevSurfaceNormalY = 1f, prevSurfaceNormalZ = 0f;
    public Vec3d crawlTarget = null;
    public Vec3d dragPos = Vec3d.ZERO;
    private int pathfindCooldown = 0;
    private Vec3d currentMoveTarget = null;

    // --- Leg simulation (client-side but stored here for sync) ---
    public Vec3d[] legPos = new Vec3d[8];      // 4 legs * 2 sides
    public Vec3d[] legLastPos = new Vec3d[8];
    public Vec3d[] legVel = new Vec3d[8];
    public Vec3d[] legGripTarget = new Vec3d[8];
    public boolean[] legGripped = new boolean[8];
    public boolean legsInitialized = false;
    public int legUpdateAge = -1;

    // --- Zone-based flock constants ---
    /** Radius within which spiders consider each other as potential flock mates */
    public static final double ZONE_RADIUS = 24.0;
    /** How many spiders to spawn in a group */
    public static final int GROUP_MIN = 8;
    public static final int GROUP_MAX = 20;

    // --- Scale ---
    public static final float PX = 0.025f;

    public SpiderEntity(EntityType<? extends MobEntity> type, World world) {
        super(type, world);
        this.noClip = false;
        this.setNoGravity(false);
        this.experiencePoints = 1;

        // Initialize legs
        for (int i = 0; i < 8; i++) {
            legPos[i] = Vec3d.ZERO;
            legLastPos[i] = Vec3d.ZERO;
            legVel[i] = Vec3d.ZERO;
            legGripTarget[i] = null;
            legGripped[i] = false;
        }

        // Random direction
        double angle = random.nextDouble() * Math.PI * 2.0;
        direction = new Vec3d(Math.cos(angle), 0, Math.sin(angle));
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 4.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.35)
                .add(EntityAttributes.GENERIC_ARMOR, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
                .add(EntityAttributes.GENERIC_STEP_HEIGHT, 1.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(SIZE_FACTOR, 0.3f + (float)(Math.random() * 0.7));
        builder.add(BLOOD_LUST, 0.3f);
        builder.add(IS_IDLE, false);
        builder.add(IS_MOVING, false);
        builder.add(LEGS_POSITION, 0f);
        builder.add(FLOCK_LEADER_ID, -1);
    }

    @Override
    protected void initGoals() {
        // Priority 1: Don't drown
        this.goalSelector.add(1, new SwimGoal(this));
        // Priority 2: Attack latched-on prey (C# Spider.Attached) — HIGHEST combat priority
        this.goalSelector.add(2, new SpiderMeleeAttackGoal(this));
        // Priority 3: Hunt prey when bloodlust is high (C# Centipede.ConsiderCreature)
        this.goalSelector.add(3, new SpiderHuntPreyGoal(this));
        // Priority 4: Swarm with other spiders / toward prey (C# Flock)
        this.goalSelector.add(4, new SpiderSwarmGoal(this));
        // Priority 5: Flee from entities too large to prey on (C# moveAwayFromPos)
        this.goalSelector.add(5, new SpiderFleeFromEntityGoal(this));
        // Priority 6: Flee from light (C# Spider.Crawl light avoidance) — below combat
        this.goalSelector.add(6, new SpiderFleeFromLightGoal(this, 1.5));
        // Priority 7: Wander in dark areas (C# Spider.Crawl idle/active)
        this.goalSelector.add(7, new SpiderWanderGoal(this, 1.0));
        // Priority 8: Look around
        this.goalSelector.add(8, new LookAroundGoal(this));
    }

    // --- Accessors ---
    public float getSizeFactor() { return this.dataTracker.get(SIZE_FACTOR); }
    public void setSizeFactor(float size) { this.dataTracker.set(SIZE_FACTOR, size); }

    public float getBloodLust() { return this.dataTracker.get(BLOOD_LUST); }
    public void setBloodLust(float v) { this.dataTracker.set(BLOOD_LUST, MathHelper.clamp(v, 0f, 1f)); }

    public boolean isIdle() { return this.dataTracker.get(IS_IDLE); }
    public void setIdle(boolean v) { this.dataTracker.set(IS_IDLE, v); }

    public boolean isMoving() { return this.dataTracker.get(IS_MOVING); }
    public void setMoving(boolean v) { this.dataTracker.set(IS_MOVING, v); }

    public float getLegsPosition() { return this.dataTracker.get(LEGS_POSITION); }
    public void setLegsPosition(float v) { this.dataTracker.set(LEGS_POSITION, v); }

    public int getFlockLeaderId() { return this.dataTracker.get(FLOCK_LEADER_ID); }
    public void setFlockLeaderId(int id) { this.dataTracker.set(FLOCK_LEADER_ID, id); }

    // --- Tick ---
    @Override
    public void tick() {
        prevTickPos = this.getPos();
        prevSurfaceNormalX = surfaceNormalX;
        prevSurfaceNormalY = surfaceNormalY;
        prevSurfaceNormalZ = surfaceNormalZ;
        super.tick();

        if (this.getWorld().isClient) {
            return; // client rendering handled by renderer
        }

        // Server-side logic
        if (outsideAccessibleCounter > 0) outsideAccessibleCounter--;
        if (noCentipedeCounter > 0) noCentipedeCounter--;
        if (chainFormationCooldown > 0) chainFormationCooldown--;

        if (this.isDead()) {
            deathSpasms = Math.max(0f, deathSpasms - 1f / (100f + random.nextFloat() * 100f));
            return;
        }

        // Update light exposure (C#: room.LightSourceExposure, throttled)
        if (random.nextFloat() < 0.30f || this.age % 3 == 0) {
            updateLightExposure();
        }

        // Update surface normal from block contacts
        updateSurfaceNormal();

        // === Surface adhesion: stick to walls and ceilings ===
        // Uses surface normal for consistent adhesion (fixed magnitude, not additive per direction)
        // This prevents corner compounding where per-direction forces trap spiders
        {
            boolean adjacentToSolid = isAdjacentToSolid();
            boolean ceilingAbove = false;
            BlockPos bpos = this.getBlockPos();
            if (this.getWorld().getBlockState(bpos.offset(Direction.UP)).isSolidBlock(this.getWorld(), bpos.offset(Direction.UP))) {
                ceilingAbove = true;
            }

            if (adjacentToSolid) {
                this.setNoGravity(true);
                // Apply adhesion toward crawling surface using pre-computed surface normal
                // surfaceNormal points AWAY from solid blocks, so we push in -normal direction
                // Fixed magnitude (0.03) regardless of how many surfaces are adjacent
                double adhesionStrength = 0.03;
                this.setVelocity(this.getVelocity().add(
                        -surfaceNormalX * adhesionStrength,
                        -surfaceNormalY * adhesionStrength,
                        -surfaceNormalZ * adhesionStrength));
                // Extra ceiling damping: reduce downward drift when on ceiling
                if (ceilingAbove && !this.isOnGround()) {
                    Vec3d vel = this.getVelocity();
                    if (vel.y < 0) this.setVelocity(vel.x, vel.y * 0.3, vel.z);
                }
            } else {
                this.setNoGravity(false);
            }
        }

        // Drag position for graphics (C#: dragPos = mainBodyChunk.pos + dir * connectDistance)
        Vec3d dragDir = dragPos.subtract(this.getPos());
        if (dragDir.lengthSquared() > 0.0001) {
            dragPos = this.getPos().add(dragDir.normalize().multiply(connectDistance));
        } else {
            dragPos = this.getPos().add(direction.multiply(connectDistance));
        }

        // Movement state (C#: Spider.moving)
        this.moving = this.getVelocity().horizontalLengthSquared() > 0.001;
        this.setMoving(this.moving);

        // === C# Spider.Crawl idle logic ===
        // In complete darkness with no movement, no prey, no threat, and no nearby entities → go idle
        boolean entityNearby = (this.age % 3 == 0) ? hasNearbyEntity(16.0) : false;
        if (lightExposure < 0.01f && !this.moving
                && (flockPrey == null || !flockPrey.isAlive())
                && moveAwayFromPos == null && huntIntensity < 0.01f && !entityNearby) {
            idleCounter++;
            if (!idle && idleCounter > 50) {  // ~2.5 seconds before going idle (adjusted for 20 TPS)
                idle = true;
                setIdle(true);
                // C#: lightToMove = Pow(Random, 1+size*2) < 0.8 ? 0 : Pow(Random, 0.5+size*2) * 0.95
                float sizeFac = getSizeFactor();
                if (Math.pow(random.nextFloat(), 1f + sizeFac * 2f) < 0.8f) {
                    lightToMove = 0f;
                } else {
                    lightToMove = (float) Math.pow(random.nextFloat(), 0.5f + sizeFac * 2f) * 0.95f;
                }
            }
        } else if (lightExposure > lightToMove || this.moving
                || (flockPrey != null && flockPrey.isAlive())
                || moveAwayFromPos != null || huntIntensity > 0f || entityNearby) {
            idleCounter = 0;
            idle = false;
            setIdle(false);
        }

        // === Blood lust (C# Spider.bloodLust) ===
        // Increases when prey is detected, decays when no prey around
        float currentLust = getBloodLust();
        if (flockPrey != null && flockPrey.isAlive()) {
            // Prey present: bloodlust rises fast
            currentLust = Math.min(1f, currentLust + 0.03f);
            if (lightExposure < 0.1f) {
                currentLust = Math.min(1f, currentLust + 0.02f);
            }
        } else if (hasNearbyEntity(12.0)) {
            // Non-prey entity nearby: slow bloodlust rise (awareness)
            currentLust = Math.min(1f, currentLust + 0.004f);
        } else {
            // No entities around: bloodlust decays so spiders stop chaining and disperse
            currentLust = Math.max(0f, currentLust - 0.008f);
        }
        setBloodLust(currentLust);

        // === Hunt intensity (C# Centipede.hunt / lightAdaption) ===
        if (flockPrey != null && flockPrey.isAlive()) {
            huntIntensity = Math.min(1f, huntIntensity + 0.02f);
            preyVisualCounter = Math.max(0, preyVisualCounter - 2);
        } else {
            huntIntensity = Math.max(0f, huntIntensity - 0.01f);
            preyVisualCounter = Math.min(100, preyVisualCounter + 2);
        }

        // === moveAwayFromPos decay (C#: Random < 0.0125 or dist > 150) ===
        // Decays faster when hunting so spiders don't get stuck fleeing
        if (moveAwayFromPos != null) {
            float decayChance = (flockPrey != null && flockPrey.isAlive()) ? 0.25f : 0.10f;
            if (random.nextFloat() < decayChance
                    || this.squaredDistanceTo(moveAwayFromPos) > 15.0 * 15.0) {
                moveAwayFromPos = null;
            }
        }

        // === Chain formation (simplified C# centipede) ===
        if (this.age % 3 == 0 && !idle) {
            updateChainFormation();
        }

        // === Centipede chain physics: each non-head spider follows the one in front ===
        if (inChain && chainFrontId >= 0) {
            SpiderEntity front = getChainFront();
            if (front != null && front.isAlive()) {
                double dist = this.distanceTo(front);
                double targetDist = 0.6; // desired spacing between chain members
                Vec3d toFront = front.getPos().subtract(this.getPos());
                if (toFront.lengthSquared() > 0.001) {
                    Vec3d dir = toFront.normalize();
                    if (dist > targetDist * 1.2) {
                        // Too far — spring pull toward front spider
                        double pullForce = Math.min(0.25, (dist - targetDist) * 0.15);
                        this.setVelocity(this.getVelocity().add(dir.multiply(pullForce)));
                    } else if (dist < targetDist * 0.5) {
                        // Too close — push apart
                        this.setVelocity(this.getVelocity().add(dir.multiply(-0.06)));
                    }
                    // Blend velocity with front spider to create smooth centipede movement
                    Vec3d frontVel = front.getVelocity();
                    Vec3d myVel = this.getVelocity();
                    this.setVelocity(
                            myVel.x * 0.5 + frontVel.x * 0.5,
                            myVel.y * 0.6 + frontVel.y * 0.4,
                            myVel.z * 0.5 + frontVel.z * 0.5
                    );
                    // Face toward front spider
                    this.direction = new Vec3d(dir.x, 0, dir.z).normalize();
                }
            } else {
                // Front spider gone — become new head or break
                chainFrontId = -1;
                if (chainBehindId < 0) {
                    inChain = false;
                }
            }
        }

        // === C# Spider.ConsiderCreature: periodic creature scan ===
        if (this.age % 5 == 0) {
            considerCreatures();
        }

        // Flock leader tracking: find closest spider, elect leader by lowest entity ID
        if (this.age % 10 == 0) {
            updateFlockLeader();
        }

        // === Legs position (C#: 0 = normal, 1 = attached/climbing) ===
        if (flockPrey == null || distanceTo(flockPrey) > 2.0) {
            setLegsPosition(MathHelper.lerp(0.19f, getLegsPosition(), 0f));
        }
    }

    private void updateLightExposure() {
        BlockPos pos = this.getBlockPos();
        // Use sky light + block light to estimate exposure
        int skyLight = this.getWorld().getLightLevel(net.minecraft.world.LightType.SKY, pos);
        int blockLight = this.getWorld().getLightLevel(net.minecraft.world.LightType.BLOCK, pos);
        lightExposure = Math.max(skyLight, blockLight) / 15f;
    }

    private void updateSurfaceNormal() {
        // Detect nearby solid blocks to determine which surface we're crawling on
        BlockPos pos = this.getBlockPos();
        World world = this.getWorld();

        float nx = 0, ny = 0, nz = 0;
        int found = 0;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (world.getBlockState(neighbor).isSolidBlock(world, neighbor)) {
                // Surface is on the opposite side of this solid block
                nx += dir.getOpposite().getOffsetX();
                ny += dir.getOpposite().getOffsetY();
                nz += dir.getOpposite().getOffsetZ();
                found++;
            }
        }

        if (found > 0) {
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0.01f) {
                surfaceNormalX = nx / len;
                surfaceNormalY = ny / len;
                surfaceNormalZ = nz / len;
            }
        } else {
            // Default: standing on floor
            surfaceNormalX = 0;
            surfaceNormalY = 1;
            surfaceNormalZ = 0;
        }
    }

    /**
     * Direct velocity-based movement toward a position.
     * Bypasses Minecraft navigation which fails for tiny entities.
     */
    public void moveToward(Vec3d target, double force) {
        Vec3d dir = target.subtract(this.getPos());
        double dist = dir.length();
        if (dist < 0.1) return;
        dir = dir.normalize();
        Vec3d vel = this.getVelocity();
        // Full Y force when on walls/ceilings, reduced when in open air
        double yMul = isAdjacentToSolid() ? 1.0 : 0.3;
        // Compensate for MC's higher ground friction (0.546 vs 0.91 in air)
        // C# uses uniform vel *= 0.7 on all surfaces; MC has 5x more drag on ground
        double surfaceMul = this.isOnGround() ? 5.0 : 1.0;
        double effectiveForce = force * surfaceMul;
        // C# random jitter for scuttling movement (DegToVec(random*360) * size * 2)
        double jitterMag = effectiveForce * 0.3;
        double jAngle = this.getRandom().nextDouble() * Math.PI * 2.0;
        this.setVelocity(
                vel.x + dir.x * effectiveForce + Math.cos(jAngle) * jitterMag,
                vel.y + dir.y * effectiveForce * yMul,
                vel.z + dir.z * effectiveForce + Math.sin(jAngle) * jitterMag);
        // Update facing direction
        this.direction = new Vec3d(dir.x, 0, dir.z).normalize();
    }

    /**
     * Direct velocity-based movement away from a position.
     */
    public void moveAwayFrom(Vec3d threat, double force) {
        Vec3d away = this.getPos().subtract(threat);
        double dist = away.length();
        if (dist < 0.01) {
            // Random direction if on top of threat
            double angle = random.nextDouble() * Math.PI * 2.0;
            away = new Vec3d(Math.cos(angle), 0, Math.sin(angle));
        } else {
            away = away.normalize();
        }
        Vec3d vel = this.getVelocity();
        double yMul = isAdjacentToSolid() ? 1.0 : 0.3;
        // Compensate for MC's higher ground friction
        double surfaceMul = this.isOnGround() ? 5.0 : 1.0;
        double effectiveForce = force * surfaceMul;
        // C# random jitter
        double jitterMag = effectiveForce * 0.3;
        double jAngle = this.getRandom().nextDouble() * Math.PI * 2.0;
        this.setVelocity(
                vel.x + away.x * effectiveForce + Math.cos(jAngle) * jitterMag,
                vel.y + away.y * effectiveForce * yMul,
                vel.z + away.z * effectiveForce + Math.sin(jAngle) * jitterMag);
        this.direction = new Vec3d(away.x, 0, away.z).normalize();
    }

    /**
     * Check if any non-spider living entity is within range.
     */
    public boolean hasNearbyEntity(double range) {
        return !this.getWorld().getEntitiesByClass(
                LivingEntity.class,
                this.getBoundingBox().expand(range),
                e -> e.isAlive() && !(e instanceof SpiderEntity) && isValidTarget(e)
        ).isEmpty();
    }

    /**
     * Check if a living entity is a valid target (ignores creative/spectator players).
     */
    public static boolean isValidTarget(LivingEntity entity) {
        if (entity instanceof ServerPlayerEntity serverPlayer) {
            return serverPlayer.interactionManager.getGameMode() == GameMode.SURVIVAL;
        }
        if (entity instanceof PlayerEntity) {
            return false; // Client-side player entity, skip
        }
        return true;
    }

    /**
     * Check if the spider is adjacent to any solid block (floor, wall, or ceiling).
     */
    public boolean isAdjacentToSolid() {
        BlockPos pos = this.getBlockPos();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (this.getWorld().getBlockState(neighbor).isSolidBlock(this.getWorld(), neighbor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a block position is a valid spider position (air adjacent to any solid).
     * Spiders can crawl on floors, walls, and ceilings.
     */
    public static boolean isValidSpiderPos(World world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir()) return false;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (world.getBlockState(neighbor).isSolidBlock(world, neighbor)) {
                return true;
            }
        }
        return false;
    }

    // --- Prey tracking ---
    @Nullable
    public LivingEntity getFlockPrey() {
        // Clean up dead/removed prey references
        if (flockPrey != null && (!flockPrey.isAlive() || flockPrey.isRemoved())) {
            flockPrey = null;
        }
        return flockPrey;
    }

    public void setFlockPrey(@Nullable LivingEntity prey) {
        this.flockPrey = prey;
    }

    /**
     * C# Spider.ConsiderCreature: periodically scan for prey and threats.
     * Uses chain mass to decide: flee if entity > chain, hunt if chain > entity.
     */
    private void considerCreatures() {
        if (!this.isAlive() || this.isDead()) return;

        double scanRange = 16.0;
        List<LivingEntity> creatures = this.getWorld().getEntitiesByClass(
                LivingEntity.class,
                this.getBoundingBox().expand(scanRange),
                e -> e.isAlive() && !(e instanceof SpiderEntity) && isValidTarget(e)
        );

        if (creatures.isEmpty()) {
            seenNoPreyCounter++;
            if (seenNoPreyCounter > 50) {
                setBloodLust(Math.max(0f, getBloodLust() - 0.002f));
            }
            return;
        }

        float myChainMass = getChainMass();
        // Also count nearby unchained spiders as "potential" chain mass
        List<SpiderEntity> nearbySpiders = this.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                this.getBoundingBox().expand(8.0),
                s -> s.isAlive() && s != this
        );
        float nearbyMass = myChainMass;
        for (SpiderEntity s : nearbySpiders) {
            if (!s.inChain) {
                nearbyMass += MathHelper.lerp(s.getSizeFactor(), 0.08f, 0.25f);
            }
        }

        for (LivingEntity creature : creatures) {
            float mass = getEntityMass(creature);

            if (canSee(creature)) {
                seenNoPreyCounter = 0;
                setBloodLust(Math.min(1f, getBloodLust() + 0.02f));

                if (nearbyMass > mass) {
                    // Chain/flock is bigger → potential prey
                    if (flockPrey == null || !flockPrey.isAlive()
                            || this.squaredDistanceTo(creature) < this.squaredDistanceTo(flockPrey)) {
                        flockPrey = creature;
                    }
                } else if (mass > nearbyMass * 8.0f) {
                    // Entity is MASSIVELY bigger than our chain → FLEE (iron golem tier only)
                    double dangerDist = 4.0 + mass * 0.5;
                    if (this.distanceTo(creature) < dangerDist) {
                        moveAwayFromPos = creature.getPos();
                        // Only share flee for truly massive threats
                        for (SpiderEntity s : nearbySpiders) {
                            if (s.moveAwayFromPos == null && s.getFlockPrey() == null) {
                                s.moveAwayFromPos = creature.getPos();
                            }
                        }
                    }
                } else {
                    // Outmassed but not severely: track as prey to stalk/recruit
                    if (flockPrey == null || !flockPrey.isAlive()) {
                        flockPrey = creature;
                    }
                    // Boost bloodlust when aware of prey we can potentially take
                    setBloodLust(Math.min(1f, getBloodLust() + 0.01f));
                }
            } else {
                seenNoPreyCounter++;
            }
        }
    }

    /**
     * Centipede chain formation.
     * Spiders can form new pairs OR join the tail of an existing chain.
     * Much simpler probability than before — just needs bloodlust.
     */
    private void updateChainFormation() {
        if (chainFormationCooldown > 0) return;
        if (getBloodLust() < 0.05f) return;
        if (noCentipedeCounter > 0) return;

        // Only form/maintain chains when prey exists or bloodlust is high enough
        // Without prey, chains should dissolve so spiders disperse
        boolean hasPrey = flockPrey != null && flockPrey.isAlive();
        boolean hasNearby = hasNearbyEntity(16.0);

        if (!hasPrey && !hasNearby) {
            // No prey or entities around: actively break chains
            if (inChain && random.nextFloat() < 0.08f) {
                breakChain();
            }
            return; // don't form new chains without prey
        }

        // Simple probability: higher bloodlust = more likely to form/join chain
        if (random.nextFloat() > getBloodLust()) return;

        // Find nearby spiders to chain with
        List<SpiderEntity> nearby = this.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                this.getBoundingBox().expand(6.0),
                s -> s.isAlive() && s != this
                        && s.noCentipedeCounter < 1
                        && this.canSee(s)
        );

        if (nearby.isEmpty()) return;

        if (!this.inChain) {
            // We're unchained — try to join an existing chain's tail, or form a new pair
            for (SpiderEntity candidate : nearby) {
                if (candidate.inChain && candidate.chainBehindId < 0) {
                    // Join the tail of candidate's chain
                    this.inChain = true;
                    this.chainFrontId = candidate.getId();
                    candidate.chainBehindId = this.getId();
                    // Share prey target
                    if (candidate.flockPrey != null && this.flockPrey == null) {
                        this.setFlockPrey(candidate.flockPrey);
                    }
                    return;
                }
            }
            // No existing chain to join — form a new pair with another unchained spider
            for (SpiderEntity candidate : nearby) {
                if (!candidate.inChain && candidate.getBloodLust() > 0.1f) {
                    this.inChain = true;
                    candidate.inChain = true;
                    this.chainBehindId = candidate.getId();
                    candidate.chainFrontId = this.getId();
                    // Share prey
                    if (this.flockPrey != null && candidate.flockPrey == null) {
                        candidate.setFlockPrey(this.flockPrey);
                    } else if (candidate.flockPrey != null && this.flockPrey == null) {
                        this.setFlockPrey(candidate.flockPrey);
                    }
                    return;
                }
            }
        } else if (this.chainBehindId < 0) {
            // We're in a chain but are the tail — try to recruit an unchained spider
            for (SpiderEntity candidate : nearby) {
                if (!candidate.inChain && candidate.getBloodLust() > 0.1f && candidate.noCentipedeCounter < 1) {
                    candidate.inChain = true;
                    candidate.chainFrontId = this.getId();
                    this.chainBehindId = candidate.getId();
                    if (this.flockPrey != null) candidate.setFlockPrey(this.flockPrey);
                    return;
                }
            }
        }

        // Random chance to break chain (decreasing with bloodlust)
        // Chains break faster when no prey is around
        float breakDenom = hasPrey
                ? MathHelper.lerp(MathHelper.clamp(getBloodLust(), 0f, 1f), 200f, 20000f)
                : MathHelper.lerp(MathHelper.clamp(getBloodLust(), 0f, 1f), 30f, 300f);
        if (inChain && random.nextFloat() < 1f / breakDenom) {
            breakChain();
        }
    }

    /**
     * Break this spider out of any chain formation.
     */
    public void breakChain() {
        if (chainFrontId >= 0) {
            Entity front = this.getWorld().getEntityById(chainFrontId);
            if (front instanceof SpiderEntity frontSpider) {
                if (frontSpider.chainBehindId == this.getId()) {
                    frontSpider.chainBehindId = -1;
                }
            }
        }
        if (chainBehindId >= 0) {
            Entity behind = this.getWorld().getEntityById(chainBehindId);
            if (behind instanceof SpiderEntity behindSpider) {
                if (behindSpider.chainFrontId == this.getId()) {
                    behindSpider.chainFrontId = -1;
                    behindSpider.inChain = false;
                }
            }
        }
        this.inChain = false;
        this.chainFrontId = -1;
        this.chainBehindId = -1;
        this.noCentipedeCounter = 2;
        this.chainFormationCooldown = 3;
    }

    /**
     * Get the entity we're following in a chain.
     */
    @Nullable
    public SpiderEntity getChainFront() {
        if (chainFrontId < 0) return null;
        Entity e = this.getWorld().getEntityById(chainFrontId);
        if (e instanceof SpiderEntity s && s.isAlive()) return s;
        // Chain broken
        chainFrontId = -1;
        inChain = false;
        return null;
    }

    /**
     * Get the entity following us in a chain.
     */
    @Nullable
    public SpiderEntity getChainBehind() {
        if (chainBehindId < 0) return null;
        Entity e = this.getWorld().getEntityById(chainBehindId);
        if (e instanceof SpiderEntity s && s.isAlive()) return s;
        // Chain broken
        chainBehindId = -1;
        return null;
    }

    /**
     * Count how many spiders are in this spider's chain (traversing both directions).
     */
    public int getChainSize() {
        if (!inChain) return 1;
        int count = 1;
        // Walk forward
        SpiderEntity current = this;
        int safety = 50;
        while (current.getChainFront() != null && safety-- > 0) {
            current = current.getChainFront();
            count++;
        }
        // Walk backward
        current = this;
        safety = 50;
        while (current.getChainBehind() != null && safety-- > 0) {
            current = current.getChainBehind();
            count++;
        }
        return count;
    }

    /**
     * Sum the mass of all spiders in this spider's chain.
     * Spider mass per individual: lerp(size, 0.08, 0.25) — tuned higher so chains are meaningful.
     */
    public float getChainMass() {
        if (!inChain) return MathHelper.lerp(getSizeFactor(), 0.08f, 0.25f);
        float total = 0f;
        // Walk forward to chain head
        SpiderEntity head = this;
        int safety = 50;
        while (head.getChainFront() != null && safety-- > 0) {
            head = head.getChainFront();
        }
        // Walk backward from head summing mass
        SpiderEntity cur = head;
        safety = 50;
        while (cur != null && safety-- > 0) {
            total += MathHelper.lerp(cur.getSizeFactor(), 0.08f, 0.25f);
            cur = cur.getChainBehind();
        }
        return total;
    }

    /**
     * Estimate mass of a non-spider living entity.
     * Tuned so a flock of 10-15 spiders can take on a player.
     */
    public static float getEntityMass(LivingEntity entity) {
        float health = entity.getMaxHealth();
        float volume = entity.getWidth() * entity.getHeight() * entity.getWidth();
        return (health * 0.025f) + (volume * 0.3f);
    }

    private void updateFlockLeader() {
        // Find nearby spiders within ZONE_RADIUS and elect the one with lowest entity ID
        List<SpiderEntity> nearby = this.getWorld().getEntitiesByClass(
                SpiderEntity.class,
                this.getBoundingBox().expand(ZONE_RADIUS),
                s -> s.isAlive() && s.squaredDistanceTo(this) < ZONE_RADIUS * ZONE_RADIUS
        );

        int lowestId = this.getId();
        for (SpiderEntity s : nearby) {
            if (s.getId() < lowestId) {
                lowestId = s.getId();
            }
        }
        setFlockLeaderId(lowestId);
    }

    // --- Group spawning ---
    /**
     * Called after the initial spider is spawned to create the rest of the flock.
     */
    public void spawnFlock() {
        if (this.getWorld().isClient) return;

        int count = GROUP_MIN + random.nextInt(GROUP_MAX - GROUP_MIN + 1);
        for (int i = 0; i < count; i++) {
            SpiderEntity spider = dev.fouriis.karmagate.KarmaGateMod.SPIDER_ENTITY_TYPE.create(this.getWorld());
            if (spider == null) continue;
            // All spiders spawn at the same position as the leader
            spider.refreshPositionAndAngles(
                    this.getX(), this.getY(), this.getZ(),
                    random.nextFloat() * 360f, 0f);
            spider.setSizeFactor(0.2f + random.nextFloat() * 0.8f);
            spider.connectDistance = MathHelper.lerp(spider.getSizeFactor(), 6f, 12f) * PX;
            this.getWorld().spawnEntity(spider);
        }
    }

    // --- Combat ---
    @Override
    public boolean damage(DamageSource source, float amount) {
        if (super.damage(source, amount)) {
            // Alert nearby spiders: boost bloodlust and share threat info
            List<SpiderEntity> nearby = this.getWorld().getEntitiesByClass(
                    SpiderEntity.class,
                    this.getBoundingBox().expand(12),
                    s -> s.isAlive()
            );
            for (SpiderEntity s : nearby) {
                s.setBloodLust(Math.min(1f, Math.max(s.getBloodLust(), 0.8f)));
                // C# Spider.Stun: if hit, consider fleeing
                if (source.getAttacker() instanceof LivingEntity attacker) {
                    float attackerMass = SpiderEntity.getEntityMass(attacker);
                    // If attacker is truly massive (iron golem tier), flee
                    if (attackerMass > 3.0f) {
                        s.moveAwayFromPos = attacker.getPos();
                    } else {
                        // Attackable → share as prey target and boost bloodlust
                        s.setFlockPrey(attacker);
                        s.setBloodLust(Math.min(1f, s.getBloodLust() + 0.3f));
                    }
                }
            }
            // Only break chain on heavy damage (> 50% health), not every hit
            if (this.inChain && amount > this.getMaxHealth() * 0.5f) {
                breakChain();
            }
            return true;
        }
        return false;
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        // Break chain on death (C# Spider.Die)
        breakChain();
        super.onDeath(damageSource);
    }

    // --- NBT ---
    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putFloat("SizeFactor", getSizeFactor());
        nbt.putFloat("BloodLust", getBloodLust());
        nbt.putFloat("Dominance", dominance);
        nbt.putFloat("DeathSpasms", deathSpasms);
        nbt.putInt("SeenNoPreyCounter", seenNoPreyCounter);
        nbt.putFloat("HuntIntensity", huntIntensity);
        nbt.putBoolean("InChain", inChain);
        nbt.putInt("NoCentipedeCounter", noCentipedeCounter);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("SizeFactor")) setSizeFactor(nbt.getFloat("SizeFactor"));
        if (nbt.contains("BloodLust")) setBloodLust(nbt.getFloat("BloodLust"));
        if (nbt.contains("Dominance")) dominance = nbt.getFloat("Dominance");
        if (nbt.contains("DeathSpasms")) deathSpasms = nbt.getFloat("DeathSpasms");
        if (nbt.contains("SeenNoPreyCounter")) seenNoPreyCounter = nbt.getInt("SeenNoPreyCounter");
        if (nbt.contains("HuntIntensity")) huntIntensity = nbt.getFloat("HuntIntensity");
        if (nbt.contains("InChain")) inChain = nbt.getBoolean("InChain");
        if (nbt.contains("NoCentipedeCounter")) noCentipedeCounter = nbt.getInt("NoCentipedeCounter");
        connectDistance = MathHelper.lerp(getSizeFactor(), 6f, 12f) * PX;
    }

    // --- Sound ---
    @Override
    protected net.minecraft.sound.SoundEvent getAmbientSound() {
        return net.minecraft.sound.SoundEvents.ENTITY_SPIDER_AMBIENT;
    }

    @Override
    protected net.minecraft.sound.SoundEvent getHurtSound(DamageSource source) {
        return net.minecraft.sound.SoundEvents.ENTITY_SPIDER_HURT;
    }

    @Override
    protected net.minecraft.sound.SoundEvent getDeathSound() {
        return net.minecraft.sound.SoundEvents.ENTITY_SPIDER_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.15f * getSizeFactor();
    }

    // --- Surface climbing ---
    @Override
    public boolean isClimbing() {
        return isAdjacentToSolid();
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false; // Spiders take no fall damage
    }
}
