package dev.fouriis.karmagate;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Rain World-ish "CoralNeuron" chain simulation + RW-style Mycelium strands anchored to stem joints.
 *
 * Refactor notes:
 * - Endpoints are PER-ENTITY (not static), so multiple entities can exist at once.
 * - Segment count is computed dynamically from endpoint distance:
 *   segmentCount = (int) clamp(length / 20f, 1f, 200f)
 * - Arrays are allocated based on computed pointCount.
 * - WORLD endpoints are persisted to NBT so /summon + chunk reload work.
 *
 * Added:
 * - "Magnet tether" behavior when an end is NOT wall-anchored:
 *   a soft pull toward the anchor point instead of hard pinning.
 */
public class CoralNeuronEntity extends Entity implements Mycelium.Owner {

    // ------------------------------------------------------------
    // Tracked data for client-server sync
    // ------------------------------------------------------------
    private static final TrackedData<Float> ANCHOR_AX = DataTracker.registerData(CoralNeuronEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ANCHOR_AY = DataTracker.registerData(CoralNeuronEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ANCHOR_AZ = DataTracker.registerData(CoralNeuronEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ANCHOR_BX = DataTracker.registerData(CoralNeuronEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ANCHOR_BY = DataTracker.registerData(CoralNeuronEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ANCHOR_BZ = DataTracker.registerData(CoralNeuronEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> ANCHOR_A_PINNED = DataTracker.registerData(CoralNeuronEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> ANCHOR_B_PINNED = DataTracker.registerData(CoralNeuronEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> HAS_ANCHORS = DataTracker.registerData(CoralNeuronEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // ------------------------------------------------------------
    // Per-entity configuration
    // ------------------------------------------------------------

    /** Endpoints in ENTITY-LOCAL space (per-entity, not static). */
    private Vec3d localAnchorA = Vec3d.ZERO;
    private Vec3d localAnchorB = Vec3d.ZERO;

    /** Whether each endpoint is pinned to its anchor (i.e., wall-anchored). */
    private boolean anchorAPinned = false;
    private boolean anchorBPinned = false;

    /** Derived from endpoint distance. */
    private int segmentCount = 2;
    private int pointCount = segmentCount + 1;

    /** Rest length per segment (distance / segments). */
    private double restLen = 1.0;

    // ------------------------------------------------------------
    // Persisted endpoints (WORLD space) so /summon + chunk reload work
    // ------------------------------------------------------------
    private Vec3d worldAnchorA = null;
    private Vec3d worldAnchorB = null;
    private boolean hasAnchors = false;

    // RW-style state in ENTITY-LOCAL space:
    // pos ~ segments[j,0], posPrev ~ segments[j,1], vel ~ segments[j,2]
    private Vec3d[] pos;
    private Vec3d[] posPrev;
    private Vec3d[] vel;

    // Slowly wandering "system wind"
    private Vec3d wind = Vec3d.ZERO;

    private boolean initialized = false;

    // ------------------------------------------------------------
    // Mycelia (computed from pointCount, per entity)
    // ------------------------------------------------------------
    private final ArrayList<Mycelium> mycelia = new ArrayList<>();
    private boolean myceliaInitialized = false;

    // Optional shared pool so the RW tip-to-tip connection logic can work across owners
    private static final WeakHashMap<World, ArrayList<Mycelium>> WORLD_POOL = new WeakHashMap<>();
    private ArrayList<Mycelium> poolForWorld() {
        //noinspection resource
        return WORLD_POOL.computeIfAbsent(getWorld(), w -> new ArrayList<>());
    }

    /**
     * REQUIRED by Fabric's EntityType factory registration (EntityType, World).
     * Uses a default 10-block span; you can call setEndpointsWorld(...) after spawning.
     */
    public CoralNeuronEntity(EntityType<? extends CoralNeuronEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        allocateArraysForSegments(this.segmentCount);
    }

    /**
     * Convenience constructor: takes TWO WORLD-SPACE points and configures the vine immediately.
     */
    public CoralNeuronEntity(
            EntityType<? extends CoralNeuronEntity> type,
            World world,
            Vec3d worldA,
            Vec3d worldB,
            boolean anchoredA,
            boolean anchoredB
    ) {
        super(type, world);
        this.noClip = true;
        setEndpointsWorld(worldA, worldB, anchoredA, anchoredB);
    }

    /**
     * Convenience constructor using BlockPos.
     */
    public CoralNeuronEntity(
            EntityType<? extends CoralNeuronEntity> type,
            World world,
            BlockPos pos1,
            BlockPos pos2,
            boolean anchored1,
            boolean anchored2
    ) {
        this(type, world,
                Vec3d.ofCenter(pos1),
                Vec3d.ofCenter(pos2),
                anchored1,
                anchored2
        );
    }

    /**
     * Call this to reconfigure endpoints (world-space).
     * Sets entity position to midpoint and stores anchors in entity-local space.
     * Also persists the world-space anchors so chunk reload recreates correctly.
     */
    public void setEndpointsWorld(Vec3d worldA, Vec3d worldB, boolean anchoredA, boolean anchoredB) {
        this.anchorAPinned = anchoredA;
        this.anchorBPinned = anchoredB;

        // Persist in world-space
        this.worldAnchorA = worldA;
        this.worldAnchorB = worldB;
        this.hasAnchors = true;

        // Sync to DataTracker for client
        this.dataTracker.set(ANCHOR_AX, (float) worldA.x);
        this.dataTracker.set(ANCHOR_AY, (float) worldA.y);
        this.dataTracker.set(ANCHOR_AZ, (float) worldA.z);
        this.dataTracker.set(ANCHOR_BX, (float) worldB.x);
        this.dataTracker.set(ANCHOR_BY, (float) worldB.y);
        this.dataTracker.set(ANCHOR_BZ, (float) worldB.z);
        this.dataTracker.set(ANCHOR_A_PINNED, anchoredA);
        this.dataTracker.set(ANCHOR_B_PINNED, anchoredB);
        this.dataTracker.set(HAS_ANCHORS, true);

        // Midpoint becomes entity position so "local" anchors are stable.
        Vec3d mid = worldA.add(worldB).multiply(0.5);
        this.setPos(mid.x, mid.y, mid.z);

        // Store anchors in entity-local space (derived)
        this.localAnchorA = worldA.subtract(mid);
        this.localAnchorB = worldB.subtract(mid);

        double length = worldA.distanceTo(worldB);

        // Segments: roughly 1 segment per 1.5 blocks for good visual density
        // Minimum 2 segments, maximum 200 segments
        int segs = (int) MathHelper.clamp((float) (length / 1.5), 2.0f, 200.0f);
        allocateArraysForSegments(segs);

        // rest length per segment so the chain naturally spans between the anchors
        this.restLen = (segs <= 0) ? 1.0 : (length / (double) segs);

        // Reset init flags so it rebuilds cleanly
        this.initialized = false;
        this.myceliaInitialized = false;
    }

    private void rebuildFromWorldAnchorsIfPresent() {
        if (!hasAnchors || worldAnchorA == null || worldAnchorB == null) return;

        Vec3d mid = worldAnchorA.add(worldAnchorB).multiply(0.5);
        this.setPos(mid.x, mid.y, mid.z);

        this.localAnchorA = worldAnchorA.subtract(mid);
        this.localAnchorB = worldAnchorB.subtract(mid);

        double length = worldAnchorA.distanceTo(worldAnchorB);
        int segs = (int) MathHelper.clamp((float) (length / 1.5), 2.0f, 200.0f);

        if (pos == null || segmentCount != segs) {
            allocateArraysForSegments(segs);
        }

        this.restLen = (segs <= 0) ? 1.0 : (length / (double) segs);

        // Force re-init of point state after load
        this.initialized = false;
        this.myceliaInitialized = false;
    }

    /**
     * Called on the client to sync state from DataTracker.
     * This ensures the client has the correct anchor positions and pinned states.
     */
    private void syncFromDataTracker() {
        boolean trackedHasAnchors = this.dataTracker.get(HAS_ANCHORS);
        if (!trackedHasAnchors) {
            return; // No anchors set yet
        }

        Vec3d trackedA = new Vec3d(
                this.dataTracker.get(ANCHOR_AX),
                this.dataTracker.get(ANCHOR_AY),
                this.dataTracker.get(ANCHOR_AZ)
        );
        Vec3d trackedB = new Vec3d(
                this.dataTracker.get(ANCHOR_BX),
                this.dataTracker.get(ANCHOR_BY),
                this.dataTracker.get(ANCHOR_BZ)
        );
        boolean trackedAPinned = this.dataTracker.get(ANCHOR_A_PINNED);
        boolean trackedBPinned = this.dataTracker.get(ANCHOR_B_PINNED);

        // Check if values changed or we haven't initialized yet
        boolean needsRebuild = (trackedHasAnchors != hasAnchors)
                || worldAnchorA == null || worldAnchorB == null
                || !trackedA.equals(worldAnchorA)
                || !trackedB.equals(worldAnchorB)
                || trackedAPinned != anchorAPinned
                || trackedBPinned != anchorBPinned;

        if (needsRebuild) {
            this.worldAnchorA = trackedA;
            this.worldAnchorB = trackedB;
            this.anchorAPinned = trackedAPinned;
            this.anchorBPinned = trackedBPinned;
            this.hasAnchors = true;

            // Recompute local anchors from world anchors
            Vec3d mid = trackedA.add(trackedB).multiply(0.5);
            this.localAnchorA = trackedA.subtract(mid);
            this.localAnchorB = trackedB.subtract(mid);

            double length = trackedA.distanceTo(trackedB);
            int segs = (int) MathHelper.clamp((float) (length / 1.5), 2.0f, 200.0f);

            if (pos == null || segmentCount != segs) {
                allocateArraysForSegments(segs);
            }

            this.restLen = (segs <= 0) ? 1.0 : (length / (double) segs);

            // Force re-init
            this.initialized = false;
            this.myceliaInitialized = false;
        }
    }

    private void allocateArraysForSegments(int segs) {
        this.segmentCount = Math.max(1, segs);
        this.pointCount = this.segmentCount + 1;

        this.pos = new Vec3d[this.pointCount];
        this.posPrev = new Vec3d[this.pointCount];
        this.vel = new Vec3d[this.pointCount];
    }

    @Override
    protected void initDataTracker(net.minecraft.entity.data.DataTracker.Builder builder) {
        builder.add(ANCHOR_AX, 0.0f);
        builder.add(ANCHOR_AY, 0.0f);
        builder.add(ANCHOR_AZ, 0.0f);
        builder.add(ANCHOR_BX, 0.0f);
        builder.add(ANCHOR_BY, 0.0f);
        builder.add(ANCHOR_BZ, 0.0f);
        builder.add(ANCHOR_A_PINNED, false);
        builder.add(ANCHOR_B_PINNED, false);
        builder.add(HAS_ANCHORS, false);
    }

    @Override
    public void tick() {
        super.tick();

        // On client, sync from DataTracker if we have anchors
        if (this.getWorld().isClient) {
            syncFromDataTracker();
        }

        // If loaded from disk and anchors exist, ensure derived local state/arrays are correct.
        if (!initialized && hasAnchors) {
            rebuildFromWorldAnchorsIfPresent();
        }

        if (!initialized) {
            initPointsBetweenAnchors();
            initialized = true;
        }

        // ------------------------------------------------------------------
        // Stem tuning (RW-style stem sim)
        // ------------------------------------------------------------------
        final double conRad = restLen * 1.5;
        final double velDamping = 0.999; // Match C# damping

        updateWind(1.0);

        // 1) Tension exchange: (i-2) <-> i (matches C# exactly)
        for (int i = 2; i < pointCount; i++) {
            Vec3d dir = dirVec(pos[i - 2], pos[i]);
            Vec3d push = dir.multiply(0.15);
            vel[i - 2] = vel[i - 2].subtract(push);
            vel[i] = vel[i].add(push);
        }

        // 2) Integrate positions with velocities + wind + terrain avoidance + endpoint influence
        final double clampDist = 4.0;
        final double denomPinned = 42.0;
        final double denomTether = 420.0; // Match C# /420f

        // Get entity world position for local->world conversion
        Vec3d entityPos = this.getPos();

        for (int i = 0; i < pointCount; i++) {
            boolean isAEnd = (i == 0);
            boolean isBEnd = (i == pointCount - 1);

            // If the end is wall-anchored, skip physics and hard set it (ConnectToWalls-style)
            if ((isAEnd && anchorAPinned) || (isBEnd && anchorBPinned)) {
                posPrev[i] = pos[i];
                pos[i] = isAEnd ? localAnchorA : localAnchorB;
                vel[i] = Vec3d.ZERO;
                continue;
            }

            double t = (pointCount <= 1) ? 0.0 : (double) i / (double) (pointCount - 1);

            posPrev[i] = pos[i];
            pos[i] = pos[i].add(vel[i]);

            vel[i] = vel[i].multiply(velDamping);
            
            // ------------------------------------------------------------------
            // TERRAIN AVOIDANCE (adapted from C# CoralNeuron.Update)
            // Push segments away from solid blocks
            // ------------------------------------------------------------------
            Vec3d worldPos = entityPos.add(pos[i]);
            Vec3d terrainPush = getTerrainAvoidance(worldPos);
            vel[i] = vel[i].add(terrainPush);

            // Wind influence
            vel[i] = vel[i].add(wind.multiply(0.005));

            // Endpoint influence bands (matches C# behavior)
            if (t < 0.5) {
                double w = inverseLerpClamped(0.25, 0.0, t);
                double denom = anchorAPinned ? denomPinned : denomTether;

                Vec3d pull = clampMagnitude(localAnchorA.subtract(pos[i]), clampDist)
                        .multiply(w / denom);

                vel[i] = vel[i].add(pull);
            } else {
                double w = inverseLerpClamped(0.75, 1.0, t);
                double denom = anchorBPinned ? denomPinned : denomTether;

                Vec3d pull = clampMagnitude(localAnchorB.subtract(pos[i]), clampDist)
                        .multiply(w / denom);

                vel[i] = vel[i].add(pull);
            }
        }

        // 3) Soft constraint passes
        pinAnchorsIfPinned();
        for (int i = pointCount - 1; i > 0; i--) connect(i, i - 1, conRad);

        pinAnchorsIfPinned();
        for (int i = 1; i < pointCount; i++) connect(i, i - 1, conRad);

        pinAnchorsIfPinned();

        // ------------------------------------------------------------------
        // Mycelia: init + tick (anchored to joints)
        // ------------------------------------------------------------------
        if (!myceliaInitialized) {
            initMycelia_OBVIOUS();
            myceliaInitialized = true;
        }

        if (!mycelia.isEmpty()) {
            long tickSeed = (((long) this.getId()) << 32) ^ this.getWorld().getTime();
            List<Mycelium> systemPool = poolForWorld();

            double forceScale = 1.0;
            for (int k = 0; k < mycelia.size(); k++) {
                Mycelium m = mycelia.get(k);
                if (m == null) continue;

                m.tick(wind, forceScale, tickSeed + k * 1013L, systemPool);

                // keep them flared out for visibility while debugging
                if ((this.age & 7) == 0) { // every 8 ticks
                    m.addImpulseNearBase(resetDir(m.index).multiply(0.20));
                }
            }
        }
    }

    private void initPointsBetweenAnchors() {
        // Evenly distribute points between anchors in LOCAL space
        // C#: segments[k, 0] = Vector2.Lerp(posA.Value, posB.Value, t) + Custom.RNV() * Random.value;
        // Add slight random offset for natural initial curvature
        long salt = ((long) this.getId() * 0x9E3779B97F4A7C15L) ^ 0xDEADBEEFL;
        
        for (int i = 0; i < pointCount; i++) {
            double t = (pointCount <= 1) ? 0.0 : (double) i / (double) (pointCount - 1);
            Vec3d p0 = lerp(localAnchorA, localAnchorB, t);
            
            // Add random offset for natural curvature (but not at pinned endpoints)
            boolean isPinnedEnd = (i == 0 && anchorAPinned) || (i == pointCount - 1 && anchorBPinned);
            if (!isPinnedEnd) {
                double rx = hash01(salt ^ (i * 12345L)) * 2.0 - 1.0;
                double ry = hash01(salt ^ (i * 67890L)) * 2.0 - 1.0;
                double rz = hash01(salt ^ (i * 13579L)) * 2.0 - 1.0;
                double rMag = hash01(salt ^ (i * 24680L)) * 0.5; // Small random magnitude
                Vec3d randomOffset = new Vec3d(rx, ry, rz).normalize().multiply(rMag);
                p0 = p0.add(randomOffset);
            }
            
            pos[i] = p0;
            posPrev[i] = p0;
            vel[i] = Vec3d.ZERO;
        }
        pinAnchorsIfPinned();
    }

    private void pinAnchorsIfPinned() {
        if (anchorAPinned) {
            pos[0] = localAnchorA;
            posPrev[0] = localAnchorA;
            vel[0] = Vec3d.ZERO;
        }
        if (anchorBPinned) {
            pos[pointCount - 1] = localAnchorB;
            posPrev[pointCount - 1] = localAnchorB;
            vel[pointCount - 1] = Vec3d.ZERO;
        }
    }

    private void updateWind(double forceScale) {
        // Match C#: wind += Custom.RNV() * 0.2f * Random.value;
        long wt = this.getWorld().getTime();
        double r1 = hash01(this.getId() * 31L + wt * 131L);
        double r2 = hash01(this.getId() * 17L + wt * 197L);
        double r3 = hash01(this.getId() * 73L + wt * 89L);

        Vec3d rnd = new Vec3d(r1 * 2 - 1, r2 * 2 - 1, r3 * 2 - 1);
        if (rnd.lengthSquared() > 1e-9) rnd = rnd.normalize();

        double mag = 0.2 * hash01(this.getId() * 999L + wt * 37L);

        wind = wind.add(rnd.multiply(mag));
        wind = clampMagnitude(wind, 1.0);
    }

    /**
     * Terrain avoidance adapted from C# CoralNeuron.Update().
     * Pushes segments away from solid blocks to prevent clipping.
     * 
     * In C#:
     * - Checks getTerrainProximity < 4
     * - For each of 4 directions, if not solid, accumulates weighted push
     * - Applies push scaled by proximity (closer = stronger push)
     */
    private Vec3d getTerrainAvoidance(Vec3d worldPos) {
        World world = this.getWorld();
        BlockPos blockPos = BlockPos.ofFloored(worldPos);
        
        // Check if we're close to or inside a solid block
        if (!isNearSolid(blockPos, 2)) {
            return Vec3d.ZERO;
        }
        
        // Find direction to push away from solids
        Vec3d pushDir = Vec3d.ZERO;
        
        // Check all 6 directions in 3D (C# uses 4 for 2D)
        int[][] directions = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
        };
        
        for (int[] dir : directions) {
            BlockPos neighborPos = blockPos.add(dir[0], dir[1], dir[2]);
            
            // If neighbor is NOT solid, we can push toward it
            if (!world.getBlockState(neighborPos).isSolidBlock(world, neighborPos)) {
                // Weight by how open the area beyond is
                double openness = getTerrainOpenness(neighborPos, 2);
                pushDir = pushDir.add(new Vec3d(dir[0], dir[1], dir[2]).multiply(openness));
            }
        }
        
        // Normalize and scale by proximity to solid
        double proximity = getTerrainProximity(blockPos);
        if (pushDir.lengthSquared() < 1e-9) {
            return Vec3d.ZERO;
        }
        
        pushDir = pushDir.normalize();
        
        // C# uses: Custom.LerpMap(proximity, 0f, 3f, 2f, 0.2f)
        // meaning: at proximity 0 (inside solid) push = 2, at proximity 3+ push = 0.2
        double pushStrength = lerpMap(proximity, 0.0, 3.0, 1.5, 0.1);
        
        return pushDir.multiply(pushStrength);
    }
    
    /**
     * Checks if any solid block is within range of the given position.
     */
    private boolean isNearSolid(BlockPos center, int range) {
        World world = this.getWorld();
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos checkPos = center.add(dx, dy, dz);
                    if (world.getBlockState(checkPos).isSolidBlock(world, checkPos)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Returns a rough measure of how far from solid blocks we are (0 = inside solid, higher = more open)
     */
    private double getTerrainProximity(BlockPos center) {
        World world = this.getWorld();
        
        // If current block is solid, return 0
        if (world.getBlockState(center).isSolidBlock(world, center)) {
            return 0.0;
        }
        
        // Count non-solid neighbors in immediate vicinity
        int openCount = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos checkPos = center.add(dx, dy, dz);
                    if (!world.getBlockState(checkPos).isSolidBlock(world, checkPos)) {
                        openCount++;
                    }
                }
            }
        }
        
        // Scale to 0-4 range like C#'s terrain proximity
        return (openCount / 26.0) * 4.0;
    }
    
    /**
     * Returns how "open" the terrain is around a position (higher = more open space)
     */
    private double getTerrainOpenness(BlockPos center, int range) {
        World world = this.getWorld();
        int openCount = 0;
        int total = 0;
        
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos checkPos = center.add(dx, dy, dz);
                    if (!world.getBlockState(checkPos).isSolidBlock(world, checkPos)) {
                        openCount++;
                    }
                    total++;
                }
            }
        }
        
        return total > 0 ? (double) openCount / total : 0.0;
    }
    
    /**
     * Linear interpolation with mapping (like C#'s Custom.LerpMap)
     */
    private static double lerpMap(double value, double fromA, double fromB, double toA, double toB) {
        double t = inverseLerpClamped(fromA, fromB, value);
        return toA + (toB - toA) * t;
    }

    private void connect(int A, int B, double conRad) {
        Vec3d delta = pos[A].subtract(pos[B]);
        double dist = delta.length();
        if (dist < 1e-8) return;

        Vec3d dir = delta.multiply(1.0 / dist);
        double w = inverseLerpClamped(0.0, conRad, dist);

        Vec3d move = dir.multiply((conRad - dist) * 0.5 * w);

        boolean aPinned = (A == 0 && anchorAPinned) || (A == pointCount - 1 && anchorBPinned);
        boolean bPinned = (B == 0 && anchorAPinned) || (B == pointCount - 1 && anchorBPinned);

        if (aPinned && bPinned) return;

        if (aPinned) {
            Vec3d m2 = move.multiply(2.0);
            pos[B] = pos[B].subtract(m2);
            vel[B] = vel[B].subtract(m2);
        } else if (bPinned) {
            Vec3d m2 = move.multiply(2.0);
            pos[A] = pos[A].add(m2);
            vel[A] = vel[A].add(m2);
        } else {
            pos[A] = pos[A].add(move);
            vel[A] = vel[A].add(move);

            pos[B] = pos[B].subtract(move);
            vel[B] = vel[B].subtract(move);
        }
    }

    // ============================================================
    // Mycelium integration
    // ============================================================

    private int segmentOfMycelium(int mycIndex) {
        return (mycIndex / 2) + 1; // 1..pointCount-2
    }

    @Override
    public Vec3d connectionPos(int index, float timeStacker) {
        int seg = clampInt(segmentOfMycelium(index), 1, pointCount - 2);
        return lerp(posPrev[seg], pos[seg], timeStacker);
    }

    @Override
    public Vec3d resetDir(int index) {
        int seg = clampInt(segmentOfMycelium(index), 1, pointCount - 2);

        Vec3d f = dirVec(pos[seg], pos[seg + 1]);
        if (f.lengthSquared() < 1e-12) f = new Vec3d(0, 1, 0);

        Vec3d perp = new Vec3d(-f.y, f.x, 0.0);
        double ls = perp.lengthSquared();
        if (ls < 1e-12) perp = new Vec3d(1, 0, 0);
        else perp = perp.multiply(1.0 / Math.sqrt(ls));

        double sign = (index % 2 == 0) ? -1.0 : 1.0;
        return perp.multiply(sign);
    }

    public List<Mycelium> getMycelia() {
        return Collections.unmodifiableList(mycelia);
    }

    private void initMycelia_OBVIOUS() {
        mycelia.clear();
        ArrayList<Mycelium> pool = poolForWorld();

        final int myceliaRows = Math.min(Math.max(pointCount - 2, 0), 20);
        final int myceliaCount = myceliaRows * 2;

        long baseSalt = ((long) this.getId() * 0x9E3779B97F4A7C15L) ^ 0xC0FEBABEL;

        for (int row = 0; row < myceliaRows; row++) {
            for (int m = 0; m < 2; m++) {
                int idx = row * 2 + m;
                if (idx >= myceliaCount) break;

                int seg = clampInt(segmentOfMycelium(idx), 1, pointCount - 2);
                Vec3d init = pos[seg];

                double length = 10.0 + 6.0 * hash01(baseSalt ^ (idx * 9176L)); // 10..16 blocks

                Mycelium strand = new Mycelium(this, idx, length, init, baseSalt ^ (idx * 1337L));
                mycelia.add(strand);
                pool.add(strand);

                strand.addImpulseNearBase(resetDir(idx).multiply(1.25));
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!mycelia.isEmpty()) {
            poolForWorld().removeAll(mycelia);
            mycelia.clear();
        }
        super.remove(reason);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static Vec3d dirVec(Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from);
        double ls = d.lengthSquared();
        if (ls < 1e-12) return Vec3d.ZERO;
        return d.multiply(1.0 / Math.sqrt(ls));
    }

    private static double inverseLerpClamped(double a, double b, double v) {
        if (a == b) return 0.0;
        double t = (v - a) / (b - a);
        if (t < 0) return 0;
        if (t > 1) return 1;
        return t;
    }

    private static Vec3d clampMagnitude(Vec3d v, double maxLen) {
        double ls = v.lengthSquared();
        double maxLs = maxLen * maxLen;
        if (ls <= maxLs) return v;
        double inv = maxLen / Math.sqrt(ls);
        return v.multiply(inv);
    }

    private static Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return a.add(b.subtract(a).multiply(t));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double hash01(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return ((x >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    public Vec3d[] getPointsLocalCopy() {
        return Arrays.copyOf(pos, pos.length);
    }

    /**
     * Returns true if anchor A is pinned (wall-anchored).
     */
    public boolean isAnchorAPinned() {
        return anchorAPinned;
    }

    /**
     * Returns true if anchor B is pinned (wall-anchored).
     */
    public boolean isAnchorBPinned() {
        return anchorBPinned;
    }

    /**
     * Gets the world-space position of anchor A.
     */
    public Vec3d getWorldAnchorA() {
        return worldAnchorA;
    }

    /**
     * Gets the world-space position of anchor B.
     */
    public Vec3d getWorldAnchorB() {
        return worldAnchorB;
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        this.hasAnchors = nbt.getBoolean("HasAnchors");

        // Default to false when NBT doesn't contain the keys (consistent with runtime defaults)
        this.anchorAPinned = nbt.contains("AnchorAPinned") ? nbt.getBoolean("AnchorAPinned") : false;
        this.anchorBPinned = nbt.contains("AnchorBPinned") ? nbt.getBoolean("AnchorBPinned") : false;

        if (hasAnchors) {
            this.worldAnchorA = new Vec3d(
                    nbt.getDouble("Ax"),
                    nbt.getDouble("Ay"),
                    nbt.getDouble("Az")
            );
            this.worldAnchorB = new Vec3d(
                    nbt.getDouble("Bx"),
                    nbt.getDouble("By"),
                    nbt.getDouble("Bz")
            );

            // Immediately compute local anchors and allocate arrays so the entity isn't collapsed
            Vec3d mid = worldAnchorA.add(worldAnchorB).multiply(0.5);
            this.setPos(mid.x, mid.y, mid.z);

            this.localAnchorA = worldAnchorA.subtract(mid);
            this.localAnchorB = worldAnchorB.subtract(mid);

            double length = worldAnchorA.distanceTo(worldAnchorB);
            int segs = (int) MathHelper.clamp((float) (length / 1.5), 2.0f, 200.0f);
            allocateArraysForSegments(segs);
            this.restLen = (segs <= 0) ? 1.0 : (length / (double) segs);

            // If we're on the logical server side, write the values into the DataTracker so clients will receive them
            if (!this.getWorld().isClient) {
                this.dataTracker.set(ANCHOR_AX, (float) worldAnchorA.x);
                this.dataTracker.set(ANCHOR_AY, (float) worldAnchorA.y);
                this.dataTracker.set(ANCHOR_AZ, (float) worldAnchorA.z);
                this.dataTracker.set(ANCHOR_BX, (float) worldAnchorB.x);
                this.dataTracker.set(ANCHOR_BY, (float) worldAnchorB.y);
                this.dataTracker.set(ANCHOR_BZ, (float) worldAnchorB.z);
                this.dataTracker.set(ANCHOR_A_PINNED, anchorAPinned);
                this.dataTracker.set(ANCHOR_B_PINNED, anchorBPinned);
                this.dataTracker.set(HAS_ANCHORS, true);
            }

            this.initialized = false;
            this.myceliaInitialized = false;
        } else {
            this.worldAnchorA = null;
            this.worldAnchorB = null;
        }
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putBoolean("HasAnchors", hasAnchors);

        nbt.putBoolean("AnchorAPinned", anchorAPinned);
        nbt.putBoolean("AnchorBPinned", anchorBPinned);

        if (hasAnchors && worldAnchorA != null && worldAnchorB != null) {
            nbt.putDouble("Ax", worldAnchorA.x);
            nbt.putDouble("Ay", worldAnchorA.y);
            nbt.putDouble("Az", worldAnchorA.z);

            nbt.putDouble("Bx", worldAnchorB.x);
            nbt.putDouble("By", worldAnchorB.y);
            nbt.putDouble("Bz", worldAnchorB.z);
        }
    }
}
