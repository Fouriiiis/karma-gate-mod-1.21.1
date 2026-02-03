package dev.fouriis.karmagate.client.wormgrass;

import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.hologram.RainWorldFrameIndex;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WormGrassWorldRenderer {
    private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");

    @SuppressWarnings("unused")
    private static final RainWorldFrameIndex FRAME_INDEX = RainWorldFrameIndex.load(
            "karma-gate-mod:textures/hologram/rainworld.png",
            "karma-gate-mod:hologram/rainWorld.json"
    );
    @SuppressWarnings("unused")
    private static final RainWorldFrameIndex.Frame TINY_STAR_FRAME = FRAME_INDEX.get("tinyStar");

    private static final int VIEW_DISTANCE_CHUNKS = 10;

    // --- Rain World-ish macro sizing ---
    private static final float EDGE_HEIGHT = 0.35f;
    private static final float CENTER_HEIGHT_SMALL_PATCH = 1.7f;
    private static final float CENTER_HEIGHT_BIG_PATCH   = 4.5f;
    private static final float CENTER_HEIGHT_TYPICAL     = 4.0f;

    private static final int PATCH_SIZE_SATURATION = 140;
    private static final int DEPTH_PAD = 2;

    // --- Density ---
    private static final int MIN_STRANDS = 8;
    private static final int MAX_STRANDS = 22;

    // --- Thickness (20 px = 1 block) ---
    private static final float EDGE_THICK_PX   = 1.5f;
    private static final float CENTER_THICK_PX = 5.5f;

    private static final float EDGE_THICK_BLOCKS   = EDGE_THICK_PX / 20f;
    private static final float CENTER_THICK_BLOCKS = CENTER_THICK_PX / 20f;

    // -------------------------------------------------------------------------
    // Continuous tile sampling settings
    // -------------------------------------------------------------------------
    /** Smaller => denser carpet; 0.22–0.30 is a good range. */
    private static final float GRID_SPACING = 0.24f;

    /**
     * How far strands may extend past the block border (in block units).
     * Helps adjacent tiles merge.
     */
    private static final float BLEED = 0.10f;

    // --- Awakening / reach behavior ---
    private static final float AWAKEN_RANGE = 8.0f;
    private static final float AWAKEN_RANGE_SQ = AWAKEN_RANGE * AWAKEN_RANGE;
    private static final float AWAKEN_HEIGHT_BOOST = 1.35f;
    private static final float AWAKEN_THICKNESS_BOOST = 1.15f;

    private static final float EXCITEMENT_RISE = 0.12f;
    private static final float EXCITEMENT_FALL = 0.025f;

    private static final float LATCH_RANGE = 1.5f;
    private static final float LATCH_RANGE_SQ = LATCH_RANGE * LATCH_RANGE;
    private static final float LATCH_BREAK_RANGE = 3.0f;
    private static final float LATCH_BREAK_RANGE_SQ = LATCH_BREAK_RANGE * LATCH_BREAK_RANGE;

    // Per-strand state tracking for physics-based animation
    private static final Map<Long, StrandAnimState> STRAND_ANIM_STATES = new HashMap<>();
    
    // Tick tracking for physics updates
    private static long lastPhysicsTick = 0;

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        ClientWorld world = client.world;
        Vec3d cam = context.camera().getPos();
        float tickDelta = context.tickCounter().getTickDelta(true);

        // Run physics tick if enough time has passed (~20 TPS)
        long now = world.getTime();
        if (now != lastPhysicsTick) {
            lastPhysicsTick = now;
            tickPhysics(world, cam);
        }

        MatrixStack matrices = context.matrixStack();
        matrices.push();

        // IMPORTANT: In AFTER_ENTITIES the stack is commonly NOT camera-relative.
        matrices.translate(-cam.x, -cam.y, -cam.z);

        Matrix4f posMat = matrices.peek().getPositionMatrix();

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) {
            matrices.pop();
            return;
        }

        VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));

        int camChunkX = MathHelper.floor(cam.x / 16.0);
        int camChunkZ = MathHelper.floor(cam.z / 16.0);

        double maxDistBlocks = VIEW_DISTANCE_CHUNKS * 16.0;
        double maxDistSq = maxDistBlocks * maxDistBlocks;

        // -------------------------
        // PASS 1: Collect visible wormgrass positions.
        // -------------------------
        ArrayList<Long> visiblePositions = new ArrayList<>(4096);
        HashSet<Long> visibleSet = new HashSet<>(4096);

        for (int dz = -VIEW_DISTANCE_CHUNKS; dz <= VIEW_DISTANCE_CHUNKS; dz++) {
            for (int dx = -VIEW_DISTANCE_CHUNKS; dx <= VIEW_DISTANCE_CHUNKS; dx++) {
                int cx = camChunkX + dx;
                int cz = camChunkZ + dz;

                List<Long> positions = WormGrassRenderCache.getPositionsForChunk(cx, cz);
                if (positions.isEmpty()) continue;

                for (long packed : positions) {
                    BlockPos pos = BlockPos.fromLong(packed);

                    double ddx = (pos.getX() + 0.5) - cam.x;
                    double ddz = (pos.getZ() + 0.5) - cam.z;
                    if (ddx * ddx + ddz * ddz > maxDistSq) continue;

                    if (world.getBlockState(pos).getBlock() != ModBlocks.WORM_GRASS) continue;

                    visiblePositions.add(packed);
                    visibleSet.add(packed);
                }
            }
        }

        if (visiblePositions.isEmpty()) {
            matrices.pop();
            return;
        }

        // -------------------------
        // PASS 2: Build patch info (size + distance-to-edge field).
        // -------------------------
        Map<Long, PatchCell> patchCellByPos = computePatchCellsXZ(visibleSet);

        // Precompute a normalized depth field for smooth sampling across the patch.
        Map<Long, Float> depthField = new HashMap<>(patchCellByPos.size() * 2);
        for (var e : patchCellByPos.entrySet()) {
            PatchCell c = e.getValue();
            float d;
            if (c.maxDist <= 0) d = 0f;
            else d = MathHelper.clamp(c.distToEdge / (float)(c.maxDist + DEPTH_PAD), 0f, 1f);

            // Keep the same easing you already use
            d = smoothstep(d);

            depthField.put(e.getKey(), d);
        }

        // -------------------------
        // PASS 3: Render (continuous height field)
        // -------------------------
        for (long packed : visiblePositions) {
            BlockPos pos = BlockPos.fromLong(packed);
            PatchCell cell = patchCellByPos.get(packed);
            if (cell == null) continue;

            // Patch-scale maturity (constant over the component)
            float patchScale = MathHelper.clamp(cell.size / (float) PATCH_SIZE_SATURATION, 0f, 1f);
            patchScale = smoothstep(patchScale);

            float centerHeight = MathHelper.lerp(patchScale, CENTER_HEIGHT_SMALL_PATCH, CENTER_HEIGHT_BIG_PATCH);
            float typicalBias = smoothstep(MathHelper.clamp((patchScale - 0.40f) / 0.60f, 0f, 1f));
            centerHeight = MathHelper.lerp(typicalBias * 0.65f, centerHeight, CENTER_HEIGHT_TYPICAL);

            int y = pos.getY();
            int light = WorldRenderer.getLightmapCoordinates(world, pos);

            // Block bounds (+bleed) in world space
            float blockMinX = pos.getX() - BLEED;
            float blockMaxX = (pos.getX() + 1f) + BLEED;
            float blockMinZ = pos.getZ() - BLEED;
            float blockMaxZ = (pos.getZ() + 1f) + BLEED;

            int gx0 = MathHelper.floor(blockMinX / GRID_SPACING);
            int gx1 = MathHelper.floor(blockMaxX / GRID_SPACING);
            int gz0 = MathHelper.floor(blockMinZ / GRID_SPACING);
            int gz1 = MathHelper.floor(blockMaxZ / GRID_SPACING);

            // Use your existing target "strands" count to derive thinning probability
            // (but now strands is computed per *sample* using continuous depth)
            float expectedPerBlock = (1.0f / (GRID_SPACING * GRID_SPACING));

            for (int gz = gz0; gz <= gz1; gz++) {
                for (int gx = gx0; gx <= gx1; gx++) {

                    long h = hash2D(gx, gz, y);

                    float jx = rand01(h ^ 0xA1B2C3D4E5F60718L);
                    float jz = rand01(h ^ 0x1F2E3D4C5B6A7988L);

                    float x = (gx + jx) * GRID_SPACING;
                    float z = (gz + jz) * GRID_SPACING;

                    if (x < blockMinX || x > blockMaxX || z < blockMinZ || z > blockMaxZ) continue;

                    // -------------------------
                    // CONTINUOUS DEPTH SAMPLING
                    // -------------------------
                    float depthScale = sampleDepthBilinear(depthField, y, x, z);

                    // Recompute height/thickness/density per strand from continuous depth
                    float height = MathHelper.lerp(depthScale, EDGE_HEIGHT, centerHeight);

                    float hN = MathHelper.clamp((height - EDGE_HEIGHT) / (CENTER_HEIGHT_BIG_PATCH - EDGE_HEIGHT), 0f, 1f);
                    hN = smoothstep(hN);

                    float thickT = smoothstep(MathHelper.clamp(0.55f * depthScale + 0.75f * hN, 0f, 1f));

                    float smallPatchBoost = MathHelper.lerp(patchScale, 1.35f, 1.00f);
                    float thickDensityReduce = MathHelper.lerp(thickT, 1.00f, 0.88f);

                    float densityT = smoothstep(MathHelper.clamp(0.65f * depthScale + 0.35f * patchScale, 0f, 1f));
                    int strands = (int) (MathHelper.lerp(densityT, MIN_STRANDS, MAX_STRANDS) * smallPatchBoost * thickDensityReduce);
                    strands = MathHelper.clamp(strands, MIN_STRANDS, MAX_STRANDS);

                    // Convert strands target into keep probability for this depth.
                    float desiredPerBlock = strands;
                    float keepChance = MathHelper.clamp((desiredPerBlock / expectedPerBlock), 0f, 1f);

                    // probabilistic thinning (this is what makes density follow the curve smoothly)
                    if (rand01(h ^ 0xC0FFEE1234ABCDEFL) > keepChance) continue;

                    float edgeR = 0.02f, edgeG = 0.00f, edgeB = 0.03f;
                    float cenR  = 0.45f, cenG  = 0.04f, cenB  = 0.18f;

                    float r = MathHelper.lerp(hN, edgeR, cenR);
                    float g = MathHelper.lerp(hN, edgeG, cenG);
                    float b = MathHelper.lerp(hN, edgeB, cenB);

                    float baseWidth = MathHelper.lerp(thickT, EDGE_THICK_BLOCKS, CENTER_THICK_BLOCKS);

                    float widthJitter = MathHelper.lerp(rand01(h ^ 0x2545F4914F6CDD1DL), 0.85f, 1.20f);
                    float width = baseWidth * widthJitter;

                    float baseStrandHeight = height * MathHelper.lerp(
                            rand01(h ^ 0x94D049BB133111EBL),
                            MathHelper.lerp(depthScale, 0.25f, 0.55f),
                            1.0f
                    );

                    float yaw = (float) (rand01(h ^ 0x123456789ABCDEFL) * Math.PI);
                    
                    // -------------------------
                    // PHYSICS-BASED STRAND ANIMATION
                    // -------------------------
                    StrandAnimState strandState = getOrCreateStrandState(h, x, y, z, baseStrandHeight);
                    
                    // Get interpolated tip offset for smooth rendering
                    float lashX = strandState.getLerpTipOffsetX(tickDelta);
                    float lashZ = strandState.getLerpTipOffsetZ(tickDelta);
                    float strandExcitement = strandState.excitement;
                    
                    // Apply excitement-based scaling to height and width
                    float excitementHeightBoost = MathHelper.lerp(strandExcitement, 1.0f, AWAKEN_HEIGHT_BOOST);
                    float excitementWidthBoost = MathHelper.lerp(strandExcitement, 1.0f, AWAKEN_THICKNESS_BOOST);
                    float strandHeight = baseStrandHeight * excitementHeightBoost;
                    width *= excitementWidthBoost;
                    
                    // Rotate yaw toward target when excited
                    if (strandExcitement > 0.05f && (lashX != 0 || lashZ != 0)) {
                        float targetYaw = (float) Math.atan2(lashZ, lashX);
                        yaw = MathHelper.lerp(strandExcitement, yaw, targetYaw);
                    }

                    float eyeOpen = MathHelper.clamp(strandExcitement * MathHelper.lerp(
                            rand01(h ^ 0x9E3779B97F4A7C15L), 0.75f, 1.15f), 0f, 1f);
                    if (strandState.isAttached) eyeOpen = 1.0f;

                    WormGrassStrandModel.emitAwakeStrandLashX(
                            vc, posMat,
                            x, y, z,
                            lashX, lashZ,
                            width, strandHeight,
                            yaw,
                            light,
                            r, g, b,
                            eyeOpen
                    );
                }
            }
        }

        matrices.pop();
    }

    // ---- Per-strand physics state ----
    
    private static final class StrandAnimState {
        final float baseX, baseY, baseZ;
        final float length;
        final long strandHash;
        
        // Current tip position (world coords)
        float tipX, tipY, tipZ;
        // Previous tick tip position (for interpolation)
        float lastTipX, lastTipY, lastTipZ;
        // Velocity
        float velX, velY, velZ;
        
        // Behavioral state
        float excitement;
        Vec3d targetPos;
        boolean isAttached;
        UUID attachedEntityId;
        
        // Time tracking
        long lastUpdateTick;
        
        StrandAnimState(long strandHash, float baseX, float baseY, float baseZ, float length) {
            this.strandHash = strandHash;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.length = length;
            
            // Start at rest (straight up)
            this.tipX = baseX;
            this.tipY = baseY + length;
            this.tipZ = baseZ;
            this.lastTipX = tipX;
            this.lastTipY = tipY;
            this.lastTipZ = tipZ;
            
            this.excitement = 0f;
            this.targetPos = null;
            this.isAttached = false;
            this.lastUpdateTick = 0;
        }
        
        float getLerpTipOffsetX(float tickDelta) {
            float lerped = MathHelper.lerp(tickDelta, lastTipX, tipX);
            return lerped - baseX;
        }
        
        float getLerpTipOffsetZ(float tickDelta) {
            float lerped = MathHelper.lerp(tickDelta, lastTipZ, tipZ);
            return lerped - baseZ;
        }
    }
    
    private static StrandAnimState getOrCreateStrandState(long strandHash, float x, float y, float z, float length) {
        StrandAnimState state = STRAND_ANIM_STATES.get(strandHash);
        if (state == null) {
            state = new StrandAnimState(strandHash, x, y, z, length);
            STRAND_ANIM_STATES.put(strandHash, state);
        }
        return state;
    }
    
    /**
     * Physics tick - updates all strand states based on nearby entities.
     * Called once per game tick from render().
     */
    private static void tickPhysics(ClientWorld world, Vec3d cam) {
        // Clean up distant strands
        double cleanupDistSq = 80.0 * 80.0;
        STRAND_ANIM_STATES.entrySet().removeIf(entry -> {
            StrandAnimState s = entry.getValue();
            double dx = s.baseX - cam.x;
            double dy = s.baseY - cam.y;
            double dz = s.baseZ - cam.z;
            return dx * dx + dy * dy + dz * dz > cleanupDistSq;
        });
        
        // Find entities for targeting
        Box searchBox = new Box(
                cam.x - AWAKEN_RANGE * 2, cam.y - AWAKEN_RANGE * 2, cam.z - AWAKEN_RANGE * 2,
                cam.x + AWAKEN_RANGE * 2, cam.y + AWAKEN_RANGE * 2, cam.z + AWAKEN_RANGE * 2
        );
        List<LivingEntity> entities = world.getEntitiesByClass(
                LivingEntity.class, searchBox,
                e -> e.isAlive() && !e.isSpectator()
        );
        
        long currentTick = world.getTime();
        
        // Update each strand
        for (StrandAnimState strand : STRAND_ANIM_STATES.values()) {
            updateStrandPhysics(strand, entities, currentTick);
        }
    }
    
    private static void updateStrandPhysics(StrandAnimState strand, List<LivingEntity> entities, long currentTick) {
        // Skip if already updated this tick
        if (strand.lastUpdateTick == currentTick) return;
        strand.lastUpdateTick = currentTick;
        
        // Save previous position for interpolation
        strand.lastTipX = strand.tipX;
        strand.lastTipY = strand.tipY;
        strand.lastTipZ = strand.tipZ;
        
        Vec3d base = new Vec3d(strand.baseX, strand.baseY, strand.baseZ);
        
        // Find best target
        float bestExcitement = 0f;
        Vec3d bestTarget = null;
        LivingEntity bestEntity = null;
        
        for (LivingEntity entity : entities) {
            Vec3d entityPos = entity.getPos().add(0, entity.getHeight() * 0.5, 0);
            double distSq = entityPos.squaredDistanceTo(base);
            
            if (distSq > AWAKEN_RANGE_SQ) continue;
            
            float dist = (float) Math.sqrt(distSq);
            float excitement = 1.0f - (dist / AWAKEN_RANGE);
            excitement = smoothstep(MathHelper.clamp(excitement, 0f, 1f));
            
            if (excitement > bestExcitement) {
                bestExcitement = excitement;
                bestTarget = entityPos;
                bestEntity = entity;
            }
        }
        
        strand.targetPos = bestTarget;
        
        // Update excitement with smooth rise/fall (matching C# behavior)
        if (bestExcitement > strand.excitement) {
            // Rise faster when creature is close
            strand.excitement = Math.min(strand.excitement + EXCITEMENT_RISE * bestExcitement + 0.02f, 1.0f);
        } else {
            // Fall gradually - slower decay for smooth return to dormant
            strand.excitement = Math.max(strand.excitement - EXCITEMENT_FALL * (1.0f - strand.excitement * 0.5f), 0.0f);
        }
        
        // Check for latch
        if (bestEntity != null && bestTarget != null) {
            Vec3d tipPos = new Vec3d(strand.tipX, strand.tipY, strand.tipZ);
            double tipDistSq = bestTarget.squaredDistanceTo(tipPos);
            if (tipDistSq <= LATCH_RANGE_SQ && strand.excitement > 0.75f) {
                strand.isAttached = true;
                strand.attachedEntityId = bestEntity.getUuid();
            }
        }
        
        // Check if latch should break
        if (strand.isAttached && strand.attachedEntityId != null) {
            LivingEntity attached = findEntityById(entities, strand.attachedEntityId);
            if (attached == null) {
                strand.isAttached = false;
                strand.attachedEntityId = null;
            } else {
                Vec3d attachedPos = attached.getPos().add(0, attached.getHeight() * 0.5, 0);
                if (attachedPos.squaredDistanceTo(base) > LATCH_BREAK_RANGE_SQ) {
                    strand.isAttached = false;
                    strand.attachedEntityId = null;
                } else {
                    // Stay attached - set target to entity
                    strand.targetPos = attachedPos;
                    strand.excitement = 1.0f;
                }
            }
        }
        
        // Apply physics
        strand.tipX += strand.velX;
        strand.tipY += strand.velY;
        strand.tipZ += strand.velZ;
        
        // Dampening (matching C#: vel *= 0.9f)
        strand.velX *= 0.88f;
        strand.velY *= 0.88f;
        strand.velZ *= 0.88f;
        
        // Slight gravity (scaled for block units)
        strand.velY += 0.012f;
        
        if (strand.isAttached && strand.targetPos != null) {
            // Attached: stick to entity with some give
            float dx = (float) strand.targetPos.x - strand.tipX;
            float dy = (float) strand.targetPos.y - strand.tipY;
            float dz = (float) strand.targetPos.z - strand.tipZ;
            strand.velX += dx * 0.35f;
            strand.velY += dy * 0.35f;
            strand.velZ += dz * 0.35f;
        } else if (strand.targetPos != null && strand.excitement > 0.05f) {
            // Reaching toward target
            float dx = (float) strand.targetPos.x - strand.tipX;
            float dy = (float) strand.targetPos.y - strand.tipY;
            float dz = (float) strand.targetPos.z - strand.tipZ;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            
            if (dist > 0.01f) {
                float nx = dx / dist;
                float ny = dy / dist;
                float nz = dz / dist;
                
                // Reach force scales with excitement
                float reachForce = strand.excitement * MathHelper.lerp(
                        MathHelper.clamp(dist * 0.3f, 0f, 1f),
                        0.06f, 0.015f
                );
                
                // Add wiggle/thrashing when excited (matches C# behavior)
                float time = (System.nanoTime() % 100000000000L) * 0.000000001f;
                float phase = (strand.strandHash & 0xFFFF) * 0.0001f;
                float wiggle = MathHelper.sin(time * 5.5f + phase) * strand.excitement * 0.025f;
                float thrash = MathHelper.sin(time * 8.2f + phase * 1.7f) * strand.excitement * 0.018f;
                
                float perpX = -nz;
                float perpZ = nx;
                
                strand.velX += nx * reachForce + perpX * wiggle + nx * thrash;
                strand.velY += ny * reachForce;
                strand.velZ += nz * reachForce + perpZ * wiggle + nz * thrash;
            }
        } else {
            // Dormant: spring back to rest position
            float restX = strand.baseX;
            float restY = strand.baseY + strand.length;
            float restZ = strand.baseZ;
            
            float dx = restX - strand.tipX;
            float dy = restY - strand.tipY;
            float dz = restZ - strand.tipZ;
            
            // Gentle spring force
            float springForce = 0.035f;
            strand.velX += dx * springForce;
            strand.velY += dy * springForce;
            strand.velZ += dz * springForce;
            
            // Subtle idle sway
            float time = (System.nanoTime() % 100000000000L) * 0.000000001f;
            float phase = (strand.strandHash & 0xFFFF) * 0.0001f;
            float sway = MathHelper.sin(time * 0.9f + phase) * 0.002f;
            strand.velX += sway;
            strand.velZ += MathHelper.cos(time * 0.7f + phase * 1.3f) * 0.0015f;
        }
        
        // Constrain tip to maximum reach from base
        float dx = strand.tipX - strand.baseX;
        float dy = strand.tipY - strand.baseY;
        float dz = strand.tipZ - strand.baseZ;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        
        float maxReach = strand.length * (strand.isAttached ? 1.5f : 1.0f);
        if (dist > maxReach) {
            float nx = dx / dist;
            float ny = dy / dist;
            float nz = dz / dist;
            float pullback = (dist - maxReach) * 0.2f;
            strand.tipX -= nx * pullback;
            strand.tipY -= ny * pullback;
            strand.tipZ -= nz * pullback;
            strand.velX -= nx * pullback * 0.5f;
            strand.velY -= ny * pullback * 0.5f;
            strand.velZ -= nz * pullback * 0.5f;
        }
        
        // Ensure tip doesn't go below base
        if (strand.tipY < strand.baseY + 0.1f) {
            strand.tipY = strand.baseY + 0.1f;
            strand.velY = Math.max(strand.velY, 0f);
        }
    }
    
    private static LivingEntity findEntityById(List<LivingEntity> entities, UUID id) {
        for (LivingEntity e : entities) {
            if (e.getUuid().equals(id)) return e;
        }
        return null;
    }

    // ---- Patch computation ----

    private static final class PatchCell {
        final int size;
        final int distToEdge;
        final int maxDist;

        PatchCell(int size, int distToEdge, int maxDist) {
            this.size = size;
            this.distToEdge = distToEdge;
            this.maxDist = maxDist;
        }
    }

    private static Map<Long, PatchCell> computePatchCellsXZ(HashSet<Long> set) {
        HashSet<Long> unvisited = new HashSet<>(set);
        HashMap<Long, PatchCell> out = new HashMap<>(set.size() * 2);

        ArrayDeque<Long> q = new ArrayDeque<>();
        ArrayList<Long> component = new ArrayList<>(256);

        while (!unvisited.isEmpty()) {
            long start = unvisited.iterator().next();
            unvisited.remove(start);

            q.clear();
            component.clear();
            q.add(start);
            component.add(start);

            int y = BlockPos.fromLong(start).getY();

            while (!q.isEmpty()) {
                long cur = q.removeFirst();
                BlockPos p = BlockPos.fromLong(cur);

                tryNeighbor(unvisited, set, q, component, p.getX() + 1, y, p.getZ());
                tryNeighbor(unvisited, set, q, component, p.getX() - 1, y, p.getZ());
                tryNeighbor(unvisited, set, q, component, p.getX(), y, p.getZ() + 1);
                tryNeighbor(unvisited, set, q, component, p.getX(), y, p.getZ() - 1);
            }

            int size = component.size();
            if (size == 0) continue;

            HashSet<Long> compSet = new HashSet<>(size * 2);
            for (long lp : component) compSet.add(lp);

            ArrayDeque<Long> bfs = new ArrayDeque<>();
            HashMap<Long, Integer> dist = new HashMap<>(size * 2);

            for (long lp : component) {
                BlockPos p = BlockPos.fromLong(lp);
                if (isBoundary(compSet, p.getX(), y, p.getZ())) {
                    bfs.add(lp);
                    dist.put(lp, 0);
                }
            }

            if (bfs.isEmpty()) {
                bfs.add(component.get(0));
                dist.put(component.get(0), 0);
            }

            int maxDist = 0;

            while (!bfs.isEmpty()) {
                long cur = bfs.removeFirst();
                int d = dist.get(cur);
                if (d > maxDist) maxDist = d;

                BlockPos p = BlockPos.fromLong(cur);

                maxDist = bfsNeighbor(compSet, dist, bfs, p.getX() + 1, y, p.getZ(), d, maxDist);
                maxDist = bfsNeighbor(compSet, dist, bfs, p.getX() - 1, y, p.getZ(), d, maxDist);
                maxDist = bfsNeighbor(compSet, dist, bfs, p.getX(), y, p.getZ() + 1, d, maxDist);
                maxDist = bfsNeighbor(compSet, dist, bfs, p.getX(), y, p.getZ() - 1, d, maxDist);
            }

            for (long lp : component) {
                int d = dist.getOrDefault(lp, 0);
                out.put(lp, new PatchCell(size, d, maxDist));
            }
        }

        return out;
    }

    private static boolean isBoundary(HashSet<Long> compSet, int x, int y, int z) {
        return !compSet.contains(BlockPos.asLong(x + 1, y, z))
                || !compSet.contains(BlockPos.asLong(x - 1, y, z))
                || !compSet.contains(BlockPos.asLong(x, y, z + 1))
                || !compSet.contains(BlockPos.asLong(x, y, z - 1));
    }

    private static int bfsNeighbor(
            HashSet<Long> compSet,
            HashMap<Long, Integer> dist,
            ArrayDeque<Long> bfs,
            int x, int y, int z,
            int curDist,
            int maxDist
    ) {
        long key = BlockPos.asLong(x, y, z);
        if (!compSet.contains(key)) return maxDist;
        if (dist.containsKey(key)) return maxDist;

        int nd = curDist + 1;
        dist.put(key, nd);
        bfs.addLast(key);
        return Math.max(maxDist, nd);
    }

    private static void tryNeighbor(
            HashSet<Long> unvisited,
            HashSet<Long> all,
            ArrayDeque<Long> q,
            ArrayList<Long> component,
            int x, int y, int z
    ) {
        long key = BlockPos.asLong(x, y, z);
        if (!all.contains(key)) return;
        if (!unvisited.remove(key)) return;
        q.addLast(key);
        component.add(key);
    }

    // ---- Continuous-grid hashing helpers ----

    private static long hash2D(int gx, int gz, int y) {
        long k = 0L;
        k ^= (long) gx * 0x9E3779B97F4A7C15L;
        k ^= (long) gz * 0xC2B2AE3D27D4EB4FL;
        k ^= (long) y  * 0x165667B19E3779F9L;
        return mix64(k);
    }

    // ---- Helpers ----

    private static float smoothstep(float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float rand01(long x) {
        return (float) ((x >>> 40) & 0xFFFFFFL) / (float) 0x1000000;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    private static float sampleDepthBilinear(Map<Long, Float> depthField, int y, float x, float z) {
        int bx = MathHelper.floor(x);
        int bz = MathHelper.floor(z);

        float fx = x - bx;
        float fz = z - bz;

        float d00 = depthAt(depthField, bx,     y, bz);
        float d10 = depthAt(depthField, bx + 1, y, bz);
        float d01 = depthAt(depthField, bx,     y, bz + 1);
        float d11 = depthAt(depthField, bx + 1, y, bz + 1);

        // bilinear blend
        float dx0 = MathHelper.lerp(fx, d00, d10);
        float dx1 = MathHelper.lerp(fx, d01, d11);
        return MathHelper.lerp(fz, dx0, dx1);
    }

    private static float depthAt(Map<Long, Float> depthField, int x, int y, int z) {
        Float v = depthField.get(BlockPos.asLong(x, y, z));
        return (v == null) ? 0f : v;
    }

    private WormGrassWorldRenderer() {}
}
