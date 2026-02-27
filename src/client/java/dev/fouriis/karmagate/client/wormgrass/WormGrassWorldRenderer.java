package dev.fouriis.karmagate.client.wormgrass;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.hologram.RainWorldFrameIndex;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import net.minecraft.util.math.ChunkPos;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.fouriis.karmagate.client.wormgrass.WormGrassStrandModel.*;

public final class WormGrassWorldRenderer {
    private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");

    private static final class PendingEye {
        final float x, y, z;
        final float perpAx, perpAy, perpAz;
        final float perpBx, perpBy, perpBz;
        final float tipDirX, tipDirY, tipDirZ;
        final float tipW;
        final float eyeOpen;
        final int light;
        final long strandHash;

        PendingEye(float x, float y, float z,
                   float perpAx, float perpAy, float perpAz,
                   float perpBx, float perpBy, float perpBz,
                   float tipDirX, float tipDirY, float tipDirZ,
                   float tipW, float eyeOpen, int light, long strandHash) {
            this.x = x; this.y = y; this.z = z;
            this.perpAx = perpAx; this.perpAy = perpAy; this.perpAz = perpAz;
            this.perpBx = perpBx; this.perpBy = perpBy; this.perpBz = perpBz;
            this.tipDirX = tipDirX; this.tipDirY = tipDirY; this.tipDirZ = tipDirZ;
            this.tipW = tipW;
            this.eyeOpen = eyeOpen;
            this.light = light;
            this.strandHash = strandHash;
        }
    }

    @SuppressWarnings("unused")
    private static final RainWorldFrameIndex FRAME_INDEX = RainWorldFrameIndex.load(
            "karma-gate-mod:textures/hologram/rainworld.png",
            "karma-gate-mod:hologram/rainworld.json"
    );
    @SuppressWarnings("unused")
    private static final RainWorldFrameIndex.Frame TINY_STAR_FRAME = FRAME_INDEX.get("tinyStar");

    // -------------------------------------------------------------------------
    // LOD / culling
    // -------------------------------------------------------------------------
    /** Full square-tube geometry + full physics. lodLevel 0..1 */
    private static final float LOD_HIGH_DIST   = 16.0f;
    /** Flat crossing quads per segment, no physics (procedural sway only). lodLevel 1..2 */
    private static final float LOD_MEDIUM_DIST = 22.0f;
    /** Single flat crossing quad base-to-tip + cap, no physics. lodLevel 2..3 */
    private static final float LOD_LOW_DIST    = 36.0f;

    private static final float LOD_HIGH_DIST_SQ = LOD_HIGH_DIST * LOD_HIGH_DIST;
    private static final float LOD_MEDIUM_DIST_SQ = LOD_MEDIUM_DIST * LOD_MEDIUM_DIST;
    private static final float LOD_LOW_DIST_SQ = LOD_LOW_DIST * LOD_LOW_DIST;

    /** Prevent runaway strand state creation. */
    private static final int MAX_STRAND_STATES = 25_000;

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

    // --- Color (edge -> center) ---
    private static final float EDGE_R = 0.02f;
    private static final float EDGE_G = 0.00f;
    private static final float EDGE_B = 0.03f;
    private static final float CENTER_R = 0.45f;
    private static final float CENTER_G = 0.04f;
    private static final float CENTER_B = 0.18f;




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

    // --- Dormant behavior ---
    // When dormant, strands should be perfectly straight and still.
    // We achieve this by (1) turning off idle sway and gravity, (2) strongly damping velocities,
    // and (3) applying a spring toward the straight-up rest pose with a smooth blend.
    private static final float DORMANT_BEGIN_T = 0.02f;
    private static final float DORMANT_END_T = 0.12f;
    private static final float DORMANT_SPRING = 0.070f;
    private static final float DORMANT_VEL_DAMP = 0.55f;
    private static final float ACTIVE_VEL_DAMP = 0.88f;
    private static final float REST_SNAP_DIST_SQ = 1.0e-6f;
    private static final float REST_SNAP_VEL_SQ = 1.0e-6f;

    private static final int ENTITY_SCAN_INTERVAL = 5;

    private static final float LATCH_RANGE = 1.5f;
    private static final float LATCH_RANGE_SQ = LATCH_RANGE * LATCH_RANGE;
    private static final float LATCH_BREAK_RANGE = 3.0f;
    private static final float LATCH_BREAK_RANGE_SQ = LATCH_BREAK_RANGE * LATCH_BREAK_RANGE;

    // Per-strand state tracking for physics-based animation
    private static final Map<Long, StrandAnimState> STRAND_ANIM_STATES = new HashMap<>();

    // Tick tracking for physics updates
    private static long lastPhysicsTick = 0;

    private static long lastEntityScanTick = -1;
    private static List<LivingEntity> cachedEntities = new ArrayList<>();
    private static final java.util.Set<Long> AWAKE_STRAND_KEYS = new HashSet<>();

    // -------------------------------------------------------------------------
    // Cached patch data — only recomputed when chunks change or on a timer.
    // -------------------------------------------------------------------------
    private static Map<Long, PatchCell> cachedPatchCells = new HashMap<>();
    private static Map<Long, Float> cachedDepthField = new HashMap<>();
    private static int cachedPatchVersion = -1;
    private static long cachedPatchTick = -100;
    private static final int PATCH_RECOMPUTE_INTERVAL = 20; // ticks

    // -------------------------------------------------------------------------
    // Reusable per-frame collections (avoid re-allocation every frame).
    // -------------------------------------------------------------------------
    private static final ArrayList<Long> frameDetailedPositions = new ArrayList<>(2048);
    private static final HashSet<Long> frameAnalysisSet = new HashSet<>(4096);
    private static final ArrayList<PendingEye> framePendingEyes = new ArrayList<>();
    private static final ArrayList<Long> physicsSnapshot = new ArrayList<>();

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        ClientWorld world = client.world;
        double camX = context.camera().getPos().x;
        double camY = context.camera().getPos().y;
        double camZ = context.camera().getPos().z;
        float tickDelta = context.tickCounter().getTickDelta(true);

        Quaternionf camRot = new Quaternionf(context.camera().getRotation());
        Vector3f billboardRight = camRot.transform(new Vector3f(1f, 0f, 0f));
        Vector3f billboardUp = camRot.transform(new Vector3f(0f, 1f, 0f));
        Vector3f billboardTowardCam = camRot.transform(new Vector3f(0f, 0f, 1f));
        Frustum frustum = context.frustum();

        // Run physics tick if enough time has passed (~20 TPS)
        long now = world.getTime();
        if (now != lastPhysicsTick && (lastPhysicsTick + 1) <= now) {
            lastPhysicsTick = now;
            tickPhysics(world, camX, camY, camZ);
        }

        MatrixStack matrices = context.matrixStack();
        matrices.push();

        // IMPORTANT: In AFTER_ENTITIES the stack is commonly NOT camera-relative.
        matrices.translate(-camX, -camY, -camZ);

        Matrix4f posMat = matrices.peek().getPositionMatrix();

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) {
            matrices.pop();
            return;
        }

        VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));

        // Reuse per-frame collections instead of allocating new ones every frame.
        framePendingEyes.clear();
        frameDetailedPositions.clear();
        frameAnalysisSet.clear();

        int camChunkX = MathHelper.floor(camX / 16.0);
        int camChunkZ = MathHelper.floor(camZ / 16.0);

        // Use the client's actual render distance so the potato LOD extends to the horizon.
        int viewDistChunks = client.options.getViewDistance().getValue();
        double maxDistBlocks = viewDistChunks * 16.0;
        double maxDistSq = maxDistBlocks * maxDistBlocks;
        double potatoDistSq = maxDistSq; // potato LOD reaches full render distance

        // -------------------------
        // PASS 1: Collect visible wormgrass positions.
        // -------------------------
        // IMPORTANT:
        //  - Frustum culling must ONLY decide what to draw.
        //  - Patch/depth/height must be computed from a camera-independent set, otherwise
        //    turning the camera makes the patch "shrink" and heights pop.
        //
        // So we collect TWO sets:
        //  (1) frameAnalysisSet: all wormgrass blocks within the analysis radius (independent of frustum)
        //  (2) frameDetailedPositions: the actually visible blocks we will draw

        for (int dz = -viewDistChunks; dz <= viewDistChunks; dz++) {
            for (int dx = -viewDistChunks; dx <= viewDistChunks; dx++) {
                int cx = camChunkX + dx;
                int cz = camChunkZ + dz;

                // Don't touch unloaded chunks.
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;

                List<Long> positions = WormGrassRenderCache.getPositionsForChunk(cx, cz);
                if (positions.isEmpty()) continue;

                for (long packed : positions) {
                    // Avoid BlockPos.fromLong() allocation — extract x/z directly.
                    int px = BlockPos.unpackLongX(packed);
                    int pz = BlockPos.unpackLongZ(packed);

                    double ddx = (px + 0.5) - camX;
                    double ddz = (pz + 0.5) - camZ;
                    double distSq = ddx * ddx + ddz * ddz;
                    if (distSq > maxDistSq) continue;

                    // Camera-independent analysis set used for patch/depth computation.
                    frameAnalysisSet.add(packed);

                    // From here on: culling is ONLY for rendering.
                    if (frustum != null) {
                        int py = BlockPos.unpackLongY(packed);
                        // Construct Box directly to avoid BlockPos allocation.
                        Box box = new Box(px, py, pz, px + 1, py + CENTER_HEIGHT_BIG_PATCH + 1.0, pz + 1);
                        if (!frustum.isVisible(box)) continue;
                    }

                    frameDetailedPositions.add(packed);
                }
            }
        }


        if (frameDetailedPositions.isEmpty()) {
            matrices.pop();
            return;
        }

        // -------------------------
        // PASS 2: Build patch info — CACHED, only recompute when dirty or on timer.
        // -------------------------
        int currentVersion = WormGrassRenderCache.getDirtyVersion();
        if (currentVersion != cachedPatchVersion || (now - cachedPatchTick) > PATCH_RECOMPUTE_INTERVAL) {
            cachedPatchCells = computePatchCellsXZ(frameAnalysisSet);
            cachedDepthField = new HashMap<>(cachedPatchCells.size() * 2);
            for (var e : cachedPatchCells.entrySet()) {
                PatchCell c = e.getValue();
                float d;
                if (c.maxDist <= 0) d = 0f;
                else d = MathHelper.clamp(c.distToEdge / (float)(c.maxDist + DEPTH_PAD), 0f, 1f);
                d = smoothstep(d);
                cachedDepthField.put(e.getKey(), d);
            }
            cachedPatchVersion = currentVersion;
            cachedPatchTick = now;
        }
        Map<Long, PatchCell> patchCellByPos = cachedPatchCells;
        Map<Long, Float> depthField = cachedDepthField;

        // -------------------------
        // PASS 3: Render (continuous height field)
        // -------------------------
        // Pre-compute time values once (not per-strand).
        float swayTime = (world.getTime() + tickDelta) * 0.15f;

        for (int blockIdx = 0; blockIdx < frameDetailedPositions.size(); blockIdx++) {
            long packed = frameDetailedPositions.get(blockIdx);
            // Avoid BlockPos.fromLong() allocation — extract coords directly.
            int posX = BlockPos.unpackLongX(packed);
            int posY = BlockPos.unpackLongY(packed);
            int posZ = BlockPos.unpackLongZ(packed);
            PatchCell cell = patchCellByPos.get(packed);
            if (cell == null) continue;

            double bdx = (posX + 0.5) - camX;
            double bdz = (posZ + 0.5) - camZ;
            double blockDistSq = bdx * bdx + bdz * bdz;

            // ---- Block-level thinning at far distance ----
            // Block-level thinning at distance — gentler thresholds to keep
            // coverage visually uniform.  Far LODs use cheap billboards so the
            // extra blocks are affordable.
            if (blockDistSq > LOD_LOW_DIST_SQ) {
                long blockHash = packed * 0x9E3779B97F4A7C15L;
                float skipThreshold;
                if (blockDistSq > 80.0 * 80.0) {
                    skipThreshold = 0.60f;      // ultra-far: keep ~40% of blocks
                } else if (blockDistSq > 56.0 * 56.0) {
                    skipThreshold = 0.45f;      // far: keep ~55% of blocks
                } else {
                    skipThreshold = 0.25f;      // near-low: keep ~75% of blocks
                }
                if (rand01(blockHash) < skipThreshold) continue;
            }

            // Patch-scale maturity (constant over the component)
            float patchScale = MathHelper.clamp(cell.size / (float) PATCH_SIZE_SATURATION, 0f, 1f);
            patchScale = smoothstep(patchScale);

            float centerHeight = MathHelper.lerp(patchScale, CENTER_HEIGHT_SMALL_PATCH, CENTER_HEIGHT_BIG_PATCH);
            float typicalBias = smoothstep(MathHelper.clamp((patchScale - 0.40f) / 0.60f, 0f, 1f));
            centerHeight = MathHelper.lerp(typicalBias * 0.65f, centerHeight, CENTER_HEIGHT_TYPICAL);

            int y = posY;
            int light = WorldRenderer.getLightmapCoordinates(world, new BlockPos(posX, posY, posZ));

            // ---- Multi-tier density — tuned for visually uniform coverage ----
            // Close LOD reduced 30%; each farther tier uses progressively
            // sparser sampling, but gently enough that the carpet looks even.
            float spacing = GRID_SPACING;
            if (blockDistSq > 56.0 * 56.0) {
                spacing *= 4.0f;        // ultra-far: billboard quads are cheap
            } else if (blockDistSq > LOD_LOW_DIST_SQ) {
                spacing *= 3.0f;        // potato tier
            } else if (blockDistSq > LOD_MEDIUM_DIST_SQ) {
                spacing *= 2.0f;        // low tier
            } else if (blockDistSq > LOD_HIGH_DIST_SQ) {
                spacing *= 1.60f;       // medium tier
            } else {
                spacing *= 1.20f;       // close LOD: ~30% fewer strands
            }

            // Block bounds (+bleed) in world space
            float blockMinX = posX - BLEED;
            float blockMaxX = (posX + 1f) + BLEED;
            float blockMinZ = posZ - BLEED;
            float blockMaxZ = (posZ + 1f) + BLEED;

            int gx0 = MathHelper.floor(blockMinX / spacing);
            int gx1 = MathHelper.floor(blockMaxX / spacing);
            int gz0 = MathHelper.floor(blockMinZ / spacing);
            int gz1 = MathHelper.floor(blockMaxZ / spacing);

            // Use your existing target "strands" count to derive thinning probability
            // (but now strands is computed per *sample* using continuous depth)
            float expectedPerBlock = (1.0f / (spacing * spacing));

            for (int gz = gz0; gz <= gz1; gz++) {
                for (int gx = gx0; gx <= gx1; gx++) {

                    long h = hash2D(gx, gz, y);

                    float jx = rand01(h ^ 0xA1B2C3D4E5F60718L);
                    float jz = rand01(h ^ 0x1F2E3D4C5B6A7988L);

                    float x = (gx + jx) * spacing;
                    float z = (gz + jz) * spacing;

                    if (x < blockMinX || x > blockMaxX || z < blockMinZ || z > blockMaxZ) continue;

                    // -------------------------
                    // CONTINUOUS DEPTH SAMPLING
                    // Skip expensive bilinear at distance — use block's precomputed depth.
                    // -------------------------
                    float depthScale;
                    if (blockDistSq > LOD_MEDIUM_DIST_SQ) {
                        depthScale = depthAt(depthField, posX, y, posZ);
                    } else {
                        depthScale = sampleDepthBilinear(depthField, y, x, z);
                    }

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

                    // Per-strand color jitter — breaks up the uniform "blob" that appears
                    // when shaders apply consistent directional lighting across all strands.
                    float brightnessJitter = MathHelper.lerp(rand01(h ^ 0xDEADBEEF12345678L), 0.68f, 1.32f);
                    float hueShift = (rand01(h ^ 0xFACECAFE01234567L) - 0.5f) * 0.07f;
                    r = MathHelper.clamp(r * brightnessJitter + hueShift, 0f, 1f);
                    g = MathHelper.clamp(g * brightnessJitter, 0f, 1f);
                    b = MathHelper.clamp(b * brightnessJitter - hueShift * 0.5f, 0f, 1f);

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
                    float lashX, lashY, lashZ;
                    float strandExcitement;

                    // Determine the LOD level for this strand based on its distance.
                    // 0..1 = high (full square tube + full physics)
                    // 1..2 = medium (flat crossing quads per segment, procedural sway)
                    // 2..3 = low (single flat crossing quad base-to-tip + cap, no sway)
                    // 3..4 = potato (Y-axis billboard, no curve)
                    float strandLodLevel;
                    if (blockDistSq <= LOD_HIGH_DIST_SQ) {
                        strandLodLevel = 0f;
                    } else if (blockDistSq <= LOD_MEDIUM_DIST_SQ) {
                        strandLodLevel = 1f;
                    } else if (blockDistSq <= LOD_LOW_DIST_SQ) {
                        strandLodLevel = 2f;
                    } else {
                        strandLodLevel = 3f;
                    }

                    // Segment count: high = 3, medium = 2, low = 1, potato = ignored (billboard)
                    int strandSegments;
                    if (strandLodLevel <= 1f) {
                        strandSegments = WormGrassStrandModel.SEGMENTS;
                    } else if (strandLodLevel <= 2f) {
                        strandSegments = WormGrassStrandModel.SEGMENTS_MID;
                    } else {
                        strandSegments = 1;
                    }

                    // Only allocate + simulate strand physics in high LOD.
                    StrandAnimState strandState = null;
                    if (strandLodLevel <= 1f) {
                        strandState = getOrCreateStrandState(h, x, y, z, baseStrandHeight);
                    }

                    if (strandLodLevel <= 1f) {
                        // High LOD: full physics + sway with trig
                        float phase = ((h >>> 16) & 0xFFFF) * 0.01f;
                        float sway = MathHelper.sin(swayTime + phase) * 0.06f * depthScale;
                        float sway2 = MathHelper.cos(swayTime * 0.9f + phase * 1.3f) * 0.04f * depthScale;

                        if (strandState != null) {
                            lashX = strandState.getLerpTipOffsetX(tickDelta) + sway;
                            lashY = strandState.getLerpTipOffsetY(tickDelta) + sway2;
                            lashZ = strandState.getLerpTipOffsetZ(tickDelta);
                            strandExcitement = strandState.excitement;
                        } else {
                            lashX = sway;
                            lashZ = sway2;
                            lashY = 0f;
                            strandExcitement = 0f;
                        }
                    } else if (strandLodLevel <= 2f) {
                        // Medium LOD: minimal sway with trig (only 1 sin call)
                        float phase = ((h >>> 16) & 0xFFFF) * 0.01f;
                        float sway = MathHelper.sin(swayTime + phase) * 0.05f * depthScale;
                        lashX = sway;
                        lashZ = sway * 0.6f;
                        lashY = 0f;
                        strandExcitement = 0f;
                    } else {
                        // Low + Potato LOD: no sway at all (saves trig calls)
                        lashX = 0f;
                        lashZ = 0f;
                        lashY = 0f;
                        strandExcitement = 0f;
                    }

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
                    if (strandState != null && strandState.isAttached) eyeOpen = 1.0f;
                    if (strandLodLevel > 1f) eyeOpen = 0f;

                    WormGrassStrandModel.emitCurvedStrand(
                            vc, posMat,
                            x, y, z,
                            lashX, lashY, lashZ,
                            width, strandHeight,
                            light,
                            r, g, b,
                            strandLodLevel,
                            strandSegments,
                            (float) camX, (float) camZ,
                            strandExcitement, swayTime
                    );

                    //eyeOpen = 1;
                    if(eyeOpen > 0f) {
                        float bezierCtrlX = x + lashX * 0.25f;
                        float bezierCtrlY = y + strandHeight * 0.65f + lashY * 0.25f;
                        float bezierCtrlZ = z + lashZ * 0.25f;

                        float strandTipX = x + lashX;
                        float strandTipY = y + strandHeight + lashY;
                        float strandTipZ = z + lashZ;

                        float lastSegT = (WormGrassStrandModel.SEGMENTS - 1) / (float) WormGrassStrandModel.SEGMENTS;
                        float lastSegOneMinusT = 1f - lastSegT;
                        float lastSegPtX = lastSegOneMinusT * lastSegOneMinusT * x + 2f * lastSegOneMinusT * lastSegT * bezierCtrlX + lastSegT * lastSegT * strandTipX;
                        float lastSegPtY = lastSegOneMinusT * lastSegOneMinusT * y + 2f * lastSegOneMinusT * lastSegT * bezierCtrlY + lastSegT * lastSegT * strandTipY;
                        float lastSegPtZ = lastSegOneMinusT * lastSegOneMinusT * z + 2f * lastSegOneMinusT * lastSegT * bezierCtrlZ + lastSegT * lastSegT * strandTipZ;

                        float tipTangentX = strandTipX - lastSegPtX;
                        float tipTangentY = strandTipY - lastSegPtY;
                        float tipTangentZ = strandTipZ - lastSegPtZ;

                        float tipTangentLen = (float) Math.sqrt(tipTangentX * tipTangentX + tipTangentY * tipTangentY + tipTangentZ * tipTangentZ);

                        if(tipTangentLen > 0.0001f) {
                            tipTangentX /= tipTangentLen;
                            tipTangentY /= tipTangentLen;
                            tipTangentZ /= tipTangentLen;
                        }
                        else {
                            tipTangentX = 1;
                            tipTangentY = 0;
                            tipTangentZ = 1;
                        }

                        float eyeRightX = billboardRight.x;
                        float eyeRightY = billboardRight.y;
                        float eyeRightZ = billboardRight.z;

                        float eyeUpX = billboardUp.x;
                        float eyeUpY = billboardUp.y;
                        float eyeUpZ = billboardUp.z;

                        float eyeWidthAtTip = width * MathHelper.lerp(smoothstep(1f), 1.0f, 0.45f);

                        framePendingEyes.add(new PendingEye(
                                strandTipX, strandTipY, strandTipZ,
                                eyeRightX, eyeRightY, eyeRightZ,
                                eyeUpX, eyeUpY, eyeUpZ,
                                tipTangentX, tipTangentY, tipTangentZ,
                                eyeWidthAtTip, eyeOpen, light, h
                        ));
                    }
                }
            }
        }

        matrices.pop();

        if(!framePendingEyes.isEmpty()) {
            matrices.push();
            matrices.translate(-camX, -camY, -camZ);
            Matrix4f eyePosMat = matrices.peek().getPositionMatrix();
            VertexConsumer evc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));

            for(PendingEye eye : framePendingEyes) {
                float openAmount = MathHelper.clamp(eye.eyeOpen, 0f, 1f);
                float eyeSize = eye.tipW * MathHelper.lerp(openAmount, 0.0f, 0.70f);

                float backsetAlongTangent = eye.tipW * 0.08f;
                float eyeCenterX = eye.x - eye.tipDirX * backsetAlongTangent;
                float eyeCenterY = eye.y - eye.tipDirY * backsetAlongTangent;
                float eyeCenterZ = eye.z - eye.tipDirZ * backsetAlongTangent;

                float lateralSide = (randSigned(eye.strandHash) >= 0f) ? 1f : -1f;
                eyeCenterX += eye.perpAx * (eye.tipW * 0.15f) * lateralSide;
                eyeCenterY += eye.perpAy * (eye.tipW * 0.15f) * lateralSide;
                eyeCenterZ += eye.perpAz * (eye.tipW * 0.15f) * lateralSide;

                float nudge = eye.tipW * 0.55f;
                eyeCenterX += billboardTowardCam.x * nudge;
                eyeCenterY += billboardTowardCam.y * nudge;
                eyeCenterZ += billboardTowardCam.z * nudge;

                float eyeColorR = 0.20f, eyeColorG = 0.00f, eyeColorB = 1.00f, eyeColorA = 1.0f;

                float halfWidth  = eyeSize * 0.5f;
                float halfHeight = eyeSize * 0.5f;

                float faceNormalX = eye.perpAy * eye.perpBz - eye.perpAz * eye.perpBy;
                float faceNormalY = eye.perpAz * eye.perpBx - eye.perpAx * eye.perpBz;
                float faceNormalZ = eye.perpAx * eye.perpBy - eye.perpAy * eye.perpBx;

                float crossedFaceNormalX = eye.perpBy * (-eye.perpAz) - eye.perpBz * (-eye.perpAy);
                float crossedFaceNormalY = eye.perpBz * (-eye.perpAx) - eye.perpBx * (-eye.perpAz);
                float crossedFaceNormalZ = eye.perpBx * (-eye.perpAy) - eye.perpBy * (-eye.perpAx);

                emitEyeQuad(evc, eyePosMat,
                        eyeCenterX, eyeCenterY, eyeCenterZ,
                        halfWidth, halfHeight,
                        eye.perpAx, eye.perpAy, eye.perpAz,
                        eye.perpBx, eye.perpBy, eye.perpBz,
                        faceNormalX, faceNormalY, faceNormalZ,
                        eyeColorR, eyeColorG, eyeColorB, eyeColorA, eye.light);

                emitEyeQuad(evc, eyePosMat,
                        eyeCenterX, eyeCenterY, eyeCenterZ,
                        halfWidth, halfHeight,
                        eye.perpBx, eye.perpBy, eye.perpBz,
                        -eye.perpAx, -eye.perpAy, -eye.perpAz,
                        crossedFaceNormalX, crossedFaceNormalY, crossedFaceNormalZ,
                        eyeColorR, eyeColorG, eyeColorB, eyeColorA, eye.light);
            }
            matrices.pop();
        }
    }

    private static void emitEyeQuad(
            VertexConsumer vertexConsumer, Matrix4f positionMatrix,
            float centerX, float centerY, float centerZ,
            float halfWidth, float halfHeight,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ,
            float faceNormalX, float faceNormalY, float faceNormalZ,
            float colorR, float colorG, float colorB, float colorA,
            int packedLight
    ) {
        float bottomLeftX = centerX - rightX * halfWidth - upX * halfHeight;
        float bottomLeftY = centerY - rightY * halfWidth - upY * halfHeight;
        float bottomLeftZ = centerZ - rightZ * halfWidth - upZ * halfHeight;

        float bottomRightX = centerX + rightX * halfWidth - upX * halfHeight;
        float bottomRightY = centerY + rightY * halfWidth - upY * halfHeight;
        float bottomRightZ = centerZ + rightZ * halfWidth - upZ * halfHeight;

        float topRightX = centerX + rightX * halfWidth + upX * halfHeight;
        float topRightY = centerY + rightY * halfWidth + upY * halfHeight;
        float topRightZ = centerZ + rightZ * halfWidth + upZ * halfHeight;

        float topLeftX = centerX - rightX * halfWidth + upX * halfHeight;
        float topLeftY = centerY - rightY * halfWidth + upY * halfHeight;
        float topLeftZ = centerZ - rightZ * halfWidth + upZ * halfHeight;

        vertexConsumer.vertex(positionMatrix, bottomLeftX, bottomLeftY, bottomLeftZ)
                .color(colorR, colorG, colorB, colorA)
                .texture(0f, 1f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(packedLight)
                .normal(faceNormalX, faceNormalY, faceNormalZ);

        vertexConsumer.vertex(positionMatrix, bottomRightX, bottomRightY, bottomRightZ)
                .color(colorR, colorG, colorB, colorA)
                .texture(1f, 1f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(packedLight)
                .normal(faceNormalX, faceNormalY, faceNormalZ);

        vertexConsumer.vertex(positionMatrix, topRightX, topRightY, topRightZ)
                .color(colorR, colorG, colorB, colorA)
                .texture(1f, 0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(packedLight)
                .normal(faceNormalX, faceNormalY, faceNormalZ);

        vertexConsumer.vertex(positionMatrix, topLeftX, topLeftY, topLeftZ)
                .color(colorR, colorG, colorB, colorA)
                .texture(0f, 0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(packedLight)
                .normal(faceNormalX, faceNormalY, faceNormalZ);
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
        boolean hasTarget;
        float targetX, targetY, targetZ;
        boolean isAttached;
        UUID attachedEntityId;

        boolean isAtRest;
        boolean awake;

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
            this.hasTarget = false;
            this.targetX = 0f;
            this.targetY = 0f;
            this.targetZ = 0f;
            this.isAttached = false;
            this.isAtRest = true;
            this.awake = false;
            this.lastUpdateTick = 0;
        }

        float getLerpTipOffsetX(float tickDelta) {
            float lerped = MathHelper.lerp(tickDelta, lastTipX, tipX);
            return lerped - baseX;
        }

        float getLerpTipOffsetY(float tickDelta) {
            float lerped = MathHelper.lerp(tickDelta, lastTipY, tipY);
            // Return offset from the "rest" position (baseY + length)
            return lerped - (baseY + length);
        }

        float getLerpTipOffsetZ(float tickDelta) {
            float lerped = MathHelper.lerp(tickDelta, lastTipZ, tipZ);
            return lerped - baseZ;
        }
    }

    private static StrandAnimState getOrCreateStrandState(long strandHash, float x, float y, float z, float length) {
        StrandAnimState state = STRAND_ANIM_STATES.get(strandHash);
        if (state == null) {
            // Safety valve: if something goes wrong and we start creating too many states,
            // fall back to procedural sway rather than tanking FPS.
            if (STRAND_ANIM_STATES.size() >= MAX_STRAND_STATES) return null;
            state = new StrandAnimState(strandHash, x, y, z, length);
            STRAND_ANIM_STATES.put(strandHash, state);
        }
        return state;
    }

    /**
     * Physics tick - updates all strand states based on nearby entities.
     * Called once per game tick from render().
     */
    private static void tickPhysics(ClientWorld world, double camX, double camY, double camZ) {
        long currentTick = world.getTime();
        double physicsCleanupDistSq = (LOD_HIGH_DIST + 16.0) * (LOD_HIGH_DIST + 16.0);
        STRAND_ANIM_STATES.entrySet().removeIf(entry -> {
            StrandAnimState s = entry.getValue();
            double dx = s.baseX - camX;
            double dy = s.baseY - camY;
            double dz = s.baseZ - camZ;
            if (dx * dx + dy * dy + dz * dz > physicsCleanupDistSq) {
                AWAKE_STRAND_KEYS.remove(entry.getKey());
                return true;
            }
            return false;
        });

        boolean doEntityScan = (currentTick - lastEntityScanTick) >= ENTITY_SCAN_INTERVAL
                || lastEntityScanTick < 0;

        if (doEntityScan) {
            lastEntityScanTick = currentTick;

            Box searchBox = new Box(
                    camX - AWAKEN_RANGE * 2, camY - AWAKEN_RANGE * 2, camZ - AWAKEN_RANGE * 2,
                    camX + AWAKEN_RANGE * 2, camY + AWAKEN_RANGE * 2, camZ + AWAKEN_RANGE * 2
            );
            cachedEntities = world.getEntitiesByClass(
                    LivingEntity.class, searchBox,
                    e -> e.isAlive() && !e.isSpectator() && !e.isInCreativeMode()
            );

            for (StrandAnimState s : STRAND_ANIM_STATES.values()) {
                s.awake = false;
            }
            AWAKE_STRAND_KEYS.clear();
            for (Map.Entry<Long, StrandAnimState> entry : STRAND_ANIM_STATES.entrySet()) {
                StrandAnimState s = entry.getValue();
                if (!s.isAtRest) {
                    s.awake = true;
                    AWAKE_STRAND_KEYS.add(entry.getKey());
                    continue;
                }

                for (LivingEntity entity : cachedEntities) {
                    float etx = (float) entity.getX();
                    float ety = (float) (entity.getY() + entity.getHeight() * 0.5f);
                    float etz = (float) entity.getZ();
                    float ddx = etx - s.baseX;
                    float ddy = ety - s.baseY;
                    float ddz = etz - s.baseZ;
                    if (ddx * ddx + ddy * ddy + ddz * ddz <= AWAKEN_RANGE_SQ) {
                        s.awake = true;
                        s.isAtRest = false;
                        AWAKE_STRAND_KEYS.add(entry.getKey());
                        break;
                    }
                }
            }
        }
        float loopTimeSeconds = (System.nanoTime() % 100_000_000_000L) * 1e-9f;
        // Use reusable list instead of allocating new Long[] every tick.
        physicsSnapshot.clear();
        physicsSnapshot.addAll(AWAKE_STRAND_KEYS);
        for (int pi = 0; pi < physicsSnapshot.size(); pi++) {
            long key = physicsSnapshot.get(pi);
            StrandAnimState strand = STRAND_ANIM_STATES.get(key);
            if (strand == null) continue;
            if (strand.isAtRest && strand.excitement <= DORMANT_BEGIN_T && !strand.awake) continue;
            updateStrandPhysics(strand, cachedEntities, currentTick, loopTimeSeconds);
        }
    }

    private static void updateStrandPhysics(StrandAnimState strand, List<LivingEntity> entities, long currentTick, float timeSeconds) {
        if (strand.isAtRest && strand.excitement <= DORMANT_BEGIN_T) return;

        // Skip if already updated this tick
        if (strand.lastUpdateTick == currentTick) return;
        strand.lastUpdateTick = currentTick;

        // Save previous position for interpolation
        strand.lastTipX = strand.tipX;
        strand.lastTipY = strand.tipY;
        strand.lastTipZ = strand.tipZ;

        // Find best target
        float bestExcitement = 0f;
        float bestTargetX = 0f, bestTargetY = 0f, bestTargetZ = 0f;
        boolean foundTarget = false;
        LivingEntity bestEntity = null;

        for (LivingEntity entity : entities) {
            float etx = (float) entity.getX();
            float ety = (float) (entity.getY() + entity.getHeight() * 0.5);
            float etz = (float) entity.getZ();

            float ddx = etx - strand.baseX;
            float ddy = ety - strand.baseY;
            float ddz = etz - strand.baseZ;
            float distSq = ddx * ddx + ddy * ddy + ddz * ddz;

            if (distSq > AWAKEN_RANGE_SQ) continue;

            float dist = (float) Math.sqrt(distSq);
            float excitement = 1.0f - (dist / AWAKEN_RANGE);
            excitement = smoothstep(MathHelper.clamp(excitement, 0f, 1f));

            if (excitement > bestExcitement) {
                bestExcitement = excitement;
                bestTargetX = etx;
                bestTargetY = ety;
                bestTargetZ = etz;
                foundTarget = true;
                bestEntity = entity;
            }
        }

        strand.hasTarget = foundTarget;
        strand.targetX = bestTargetX;
        strand.targetY = bestTargetY;
        strand.targetZ = bestTargetZ;

        // Update excitement with smooth rise/fall (matching C# behavior)
        if (bestExcitement > strand.excitement) {
            strand.excitement = Math.min(strand.excitement + EXCITEMENT_RISE * bestExcitement + 0.02f, 1.0f);
        } else {
            strand.excitement = Math.max(strand.excitement - EXCITEMENT_FALL * (1.0f - strand.excitement * 0.5f), 0.0f);
        }

        // Check for latch
        if (bestEntity != null && foundTarget) {
            float tdx = bestTargetX - strand.tipX;
            float tdy = bestTargetY - strand.tipY;
            float tdz = bestTargetZ - strand.tipZ;
            float tipDistSq = tdx * tdx + tdy * tdy + tdz * tdz;
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
                float ax = (float) attached.getX();
                float ay = (float) (attached.getY() + attached.getHeight() * 0.5);
                float az = (float) attached.getZ();
                float adx = ax - strand.baseX;
                float ady = ay - strand.baseY;
                float adz = az - strand.baseZ;
                if (adx * adx + ady * ady + adz * adz > LATCH_BREAK_RANGE_SQ) {
                    strand.isAttached = false;
                    strand.attachedEntityId = null;
                } else {
                    // Stay attached - set target to entity
                    strand.hasTarget = true;
                    strand.targetX = ax;
                    strand.targetY = ay;
                    strand.targetZ = az;
                    strand.excitement = 1.0f;
                }
            }
        }

        // Apply physics
        strand.tipX += strand.velX;
        strand.tipY += strand.velY;
        strand.tipZ += strand.velZ;

        // -----------------------------------------------------------------
        // Smoothly blend active motion vs dormant return-to-rest.
        // activeT = 1 when excited, 0 when dormant.
        // -----------------------------------------------------------------
        boolean hasMeaningfulTarget = (strand.isAttached && strand.hasTarget)
                || (strand.hasTarget && strand.excitement > DORMANT_BEGIN_T);

        float activeT;
        if (!hasMeaningfulTarget) {
            activeT = 0f;
        } else {
            // Map excitement into an [0..1] activation window to avoid snapping at a hard threshold.
            float x = (strand.excitement - DORMANT_BEGIN_T) / (DORMANT_END_T - DORMANT_BEGIN_T);
            activeT = smoothstep(MathHelper.clamp(x, 0f, 1f));
        }
        float dormantT = 1f - activeT;

        // Damping: heavier when dormant so it settles to perfectly still.
        float velMul = MathHelper.lerp(dormantT, ACTIVE_VEL_DAMP, DORMANT_VEL_DAMP);
        strand.velX *= velMul;
        strand.velY *= velMul;
        strand.velZ *= velMul;

        // Gravity is disabled when dormant to prevent perpetual micro-motion.
        // (Note: sign preserved as in existing behavior; only scaled down when dormant.)
        strand.velY += 0.012f * activeT;

        if (strand.isAttached && strand.hasTarget) {
            // Attached: stick to entity with some give
            float dx = strand.targetX - strand.tipX;
            float dy = strand.targetY - strand.tipY;
            float dz = strand.targetZ - strand.tipZ;
            strand.velX += dx * 0.35f;
            strand.velY += dy * 0.35f;
            strand.velZ += dz * 0.35f;
        } else if (strand.hasTarget && activeT > 0.0001f) {
            // Reaching toward target
            float dx = strand.targetX - strand.tipX;
            float dy = strand.targetY - strand.tipY;
            float dz = strand.targetZ - strand.tipZ;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist > 0.01f) {
                float nx = dx / dist;
                float ny = dy / dist;
                float nz = dz / dist;

                // Reach force scales with excitement
                float reachForce = (strand.excitement * activeT) * MathHelper.lerp(
                        MathHelper.clamp(dist * 0.3f, 0f, 1f),
                        0.06f, 0.015f
                );

                // Add wiggle/thrashing when excited (matches C# behavior)
                float time = timeSeconds;
                float phase = (strand.strandHash & 0xFFFF) * 0.0001f;
                float wiggle = MathHelper.sin(time * 5.5f + phase) * (strand.excitement * activeT) * 0.025f;
                float thrash = MathHelper.sin(time * 8.2f + phase * 1.7f) * (strand.excitement * activeT) * 0.018f;

                float perpX = -nz;
                float perpZ = nx;

                strand.velX += nx * reachForce + perpX * wiggle + nx * thrash;
                strand.velY += ny * reachForce;
                strand.velZ += nz * reachForce + perpZ * wiggle + nz * thrash;
            }
        }

        // Dormant return-to-rest (always applied, but strongest when dormant).
        // This avoids snapping when transitioning across the dormant/active threshold.
        if (dormantT > 0f) {
            float restX = strand.baseX;
            float restY = strand.baseY + strand.length;
            float restZ = strand.baseZ;

            float rdx = restX - strand.tipX;
            float rdy = restY - strand.tipY;
            float rdz = restZ - strand.tipZ;

            float spring = DORMANT_SPRING * dormantT;
            strand.velX += rdx * spring;
            strand.velY += rdy * spring;
            strand.velZ += rdz * spring;

            // Once it's essentially at rest and essentially dormant, lock it exactly.
            // This guarantees "completely still and straight" without visible snapping.
            float restDistSq = rdx * rdx + rdy * rdy + rdz * rdz;
            float velSq = strand.velX * strand.velX + strand.velY * strand.velY + strand.velZ * strand.velZ;
            if (strand.excitement <= DORMANT_BEGIN_T && restDistSq <= REST_SNAP_DIST_SQ && velSq <= REST_SNAP_VEL_SQ) {
                strand.tipX = restX;
                strand.tipY = restY;
                strand.tipZ = restZ;
                strand.velX = 0f;
                strand.velY = 0f;
                strand.velZ = 0f;
                strand.isAtRest = true;
                strand.awake = false;
                AWAKE_STRAND_KEYS.remove(strand.strandHash);
            }
        }

        // Constrain tip to maximum reach from base
        float dx = strand.tipX - strand.baseX;
        float dy = strand.tipY - strand.baseY;
        float dz = strand.tipZ - strand.baseZ;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        float maxReach = strand.length * (strand.isAttached ? 1.5f : 1.0f);
        if (dist > maxReach && dist > 0.0001f) {
            float nx = dx / dist;
            float ny = dy / dist;
            float nz = dz / dist;
            float overshoot = dist - maxReach;
            float correctionStrength = 0.35f;
            strand.velX -= nx * overshoot * correctionStrength;
            strand.velY -= ny * overshoot * correctionStrength;
            strand.velZ -= nz * overshoot * correctionStrength;
        }

        // Ensure tip doesn't go below base
        if (strand.tipY < strand.baseY + 0.1f) {
            strand.velY += (strand.baseY + 0.1f - strand.tipY) * 0.4f;
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

            int y = BlockPos.unpackLongY(start);

            while (!q.isEmpty()) {
                long cur = q.removeFirst();
                int cpx = BlockPos.unpackLongX(cur);
                int cpz = BlockPos.unpackLongZ(cur);

                tryNeighbor(unvisited, set, q, component, cpx + 1, y, cpz);
                tryNeighbor(unvisited, set, q, component, cpx - 1, y, cpz);
                tryNeighbor(unvisited, set, q, component, cpx, y, cpz + 1);
                tryNeighbor(unvisited, set, q, component, cpx, y, cpz - 1);
            }

            int size = component.size();
            if (size == 0) continue;

            HashSet<Long> compSet = new HashSet<>(size * 2);
            for (long lp : component) compSet.add(lp);

            ArrayDeque<Long> bfs = new ArrayDeque<>();
            HashMap<Long, Integer> dist = new HashMap<>(size * 2);

            for (long lp : component) {
                int lpx = BlockPos.unpackLongX(lp);
                int lpz = BlockPos.unpackLongZ(lp);
                if (isBoundary(compSet, lpx, y, lpz)) {
                    bfs.add(lp);
                    dist.put(lp, 0);
                }
            }

            if (bfs.isEmpty()) {
                // No real boundary found — wormgrass extends beyond loaded area
                // on all sides (e.g. superflat world). Give all blocks maximum depth
                // so they appear at full height.
                int fakeMaxDist = 100;
                int fakeDistToEdge = fakeMaxDist + DEPTH_PAD; // ensures depthScale = 1.0
                for (long lp : component) {
                    out.put(lp, new PatchCell(size, fakeDistToEdge, fakeMaxDist));
                }
                continue;
            }

            int maxDist = 0;

            while (!bfs.isEmpty()) {
                long cur = bfs.removeFirst();
                int d = dist.get(cur);
                if (d > maxDist) maxDist = d;

                int bpx = BlockPos.unpackLongX(cur);
                int bpz = BlockPos.unpackLongZ(cur);

                maxDist = bfsNeighbor(compSet, dist, bfs, bpx + 1, y, bpz, d, maxDist);
                maxDist = bfsNeighbor(compSet, dist, bfs, bpx - 1, y, bpz, d, maxDist);
                maxDist = bfsNeighbor(compSet, dist, bfs, bpx, y, bpz + 1, d, maxDist);
                maxDist = bfsNeighbor(compSet, dist, bfs, bpx, y, bpz - 1, d, maxDist);
            }

            for (long lp : component) {
                int d = dist.getOrDefault(lp, 0);
                out.put(lp, new PatchCell(size, d, maxDist));
            }
        }

        return out;
    }

    /**
     * A block is a real boundary only if at least one neighbor is truly absent
     * from the world (not just outside the analysis set). This prevents false
     * boundaries at the edge of the view distance on large / infinite patches.
     */
    private static boolean isBoundary(HashSet<Long> compSet, int x, int y, int z) {
        if (!compSet.contains(BlockPos.asLong(x + 1, y, z))
                && !WormGrassRenderCache.hasPosition(x + 1, y, z)) return true;
        if (!compSet.contains(BlockPos.asLong(x - 1, y, z))
                && !WormGrassRenderCache.hasPosition(x - 1, y, z)) return true;
        if (!compSet.contains(BlockPos.asLong(x, y, z + 1))
                && !WormGrassRenderCache.hasPosition(x, y, z + 1)) return true;
        if (!compSet.contains(BlockPos.asLong(x, y, z - 1))
                && !WormGrassRenderCache.hasPosition(x, y, z - 1)) return true;
        return false;
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
