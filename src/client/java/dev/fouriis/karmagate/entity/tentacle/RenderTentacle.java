package dev.fouriis.karmagate.entity.tentacle;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Client-side visual tentacle — a chain of {@link RenderTentacleChunk}s pinned
 * between a base (root) and driven toward a goal (head / tip).
 * <p>
 * This is a faithful Java port of the Rain World C# {@code Tentacle} rendering
 * physics. It is intentionally decoupled from Minecraft block/tile logic so
 * that any entity can instantiate one and call {@link #update} each tick.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // In your entity or renderer — create once:
 * RenderTentacle tentacle = new RenderTentacle(numChunks, idealLengthBlocks, chunkRadius);
 * tentacle.reset(rootPos);
 *
 * // Each tick (client-side):
 * tentacle.update(rootPos, headPos);
 *
 * // In render():
 * for (int i = 0; i < tentacle.chunkCount(); i++) {
 *     RenderTentacleChunk c = tentacle.getChunk(i);
 *     Vec3d pos = lerpVec(c.lastPos, c.pos, tickDelta);
 *     // … draw body mesh at pos with radius c.stretchedRad() …
 * }
 * }</pre>
 *
 * <h3>C# properties exposed</h3>
 * <ul>
 *   <li>{@link #retractFac} — 0 = fully extended, 1 = fully retracted</li>
 *   <li>{@link #stretchAndSqueeze} — how much the radius responds to stretch</li>
 *   <li>{@link #stiff} — if true, chunks always enforce segment length</li>
 *   <li>{@link #goalAttractionSpeedTip} — tip attraction toward goal</li>
 *   <li>{@link #goalAttractionSpeed} — non-tip attraction toward goal</li>
 *   <li>{@link #limp} — if true, gravity pulls chunks down</li>
 * </ul>
 */
public class RenderTentacle {

    // ── Chunks ────────────────────────────────────────────────────────
    private final RenderTentacleChunk[] chunks;

    // ── Physical properties (C#: TentacleProps + Tentacle fields) ────
    /** Ideal length of the full tentacle in MC blocks. */
    public float idealLength;

    /** Retraction factor: 0 = fully extended, 1 = fully retracted. */
    public float retractFac = 0f;

    /** How aggressively the radius responds to stretch (C# default 0.5). */
    public float stretchAndSqueeze = 0.5f;

    /**
     * Whether the chain links are always enforced (not just when stretched).
     * C#: {@code TentacleProps.stiff}.
     */
    public boolean stiff = false;

    /**
     * How much of the chain constraint is absorbed by the further-from-base
     * chunk (C#: {@code massDeteriorationPerChunk}, default 0.5).
     */
    public float massDeteriorationPerChunk = 0.5f;

    /**
     * Velocity cap per chunk per tick (MC blocks/tick).
     * C#: 10px / 20px/block = 0.5 blocks.
     */
    public float chunkVelocityCap = 0.5f;

    /** Tip attraction toward goal (C# default 1.4 → 0.07 blocks). */
    public float goalAttractionSpeedTip = 0.07f;

    /** Non-tip attraction toward goal (C# default 0). */
    public float goalAttractionSpeed = 0f;

    /** If true, gravity pulls chunks downward. */
    public boolean limp = false;

    /** Gravity strength (blocks/tick²). */
    public float gravity = 0.045f; // 0.9px/frame / 20

    // ══════════════════════════════════════════════════════════════════
    //  Construction
    // ══════════════════════════════════════════════════════════════════

    /**
     * Create a new tentacle.
     *
     * @param numChunks       number of chunks (C# default for garbage worm ≈ 15)
     * @param idealLength     total ideal length in MC blocks
     * @param chunkRadius     per-chunk radius in MC blocks
     */
    public RenderTentacle(int numChunks, float idealLength, float chunkRadius) {
        this.idealLength = idealLength;
        this.chunks = new RenderTentacleChunk[numChunks];
        for (int i = 0; i < numChunks; i++) {
            float tPos = (float) (i + 1) / (float) numChunks;
            chunks[i] = new RenderTentacleChunk(this, i, tPos, chunkRadius);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Access
    // ══════════════════════════════════════════════════════════════════

    public int chunkCount() { return chunks.length; }
    public RenderTentacleChunk getChunk(int i) { return chunks[i]; }
    public RenderTentacleChunk tip() { return chunks[chunks.length - 1]; }

    // ══════════════════════════════════════════════════════════════════
    //  Reset
    // ══════════════════════════════════════════════════════════════════

    /** Reset all chunks to the given position. */
    public void reset(Vec3d pos) {
        for (RenderTentacleChunk chunk : chunks) {
            chunk.reset(pos);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Update  (call once per client tick)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Tick the tentacle physics.
     * <p>
     * Matches the C# {@code Tentacle.Update()} + {@code GarbageWorm.Update()}
     * velocity adjustments. Each chunk:
     * <ol>
     *   <li>Receives goal attraction velocity</li>
     *   <li>Receives per-chunk friction / upward forces</li>
     *   <li>Runs chain-constraint physics</li>
     * </ol>
     *
     * @param basePos world-space anchor (root) position
     * @param goalPos world-space goal (head / tip target) position
     */
    public void update(Vec3d basePos, Vec3d goalPos) {
        // ── Per-chunk velocity adjustments (C#: GarbageWorm.Update loop) ──
        for (int i = 0; i < chunks.length; i++) {
            RenderTentacleChunk c = chunks[i];
            float t = ((float) i + 0.5f) / (float) chunks.length;

            // Friction (C#: vel *= lerp(0.9, 0.99, t))
            double friction = MathHelper.lerp(t, 0.9, 0.99);
            c.vel = c.vel.multiply(friction);

            if (limp) {
                // Gravity
                c.vel = c.vel.add(0, -gravity, 0);
            } else {
                // Goal attraction
                Vec3d toGoal = clampMagnitude(goalPos.subtract(c.pos), 1.0);
                if (i == chunks.length - 1) {
                    c.vel = c.vel.add(toGoal.multiply(goalAttractionSpeedTip));
                } else {
                    c.vel = c.vel.add(toGoal.multiply(goalAttractionSpeed));

                    // Non-tip upward force (C#: vel.y += (1-t)*0.5 → /20 = 0.025)
                    c.vel = c.vel.add(0, (1.0 - t) * 0.025, 0);
                }

                // Repulsion from look point to create organic "S" shape
                // C#: vel += DirVec(lookPoint, chunk.pos) * sin(PI * pow(t,2)) * 0.1
                Vec3d repulse = dirVec(goalPos, c.pos);
                double repulseStr = Math.sin(Math.PI * Math.pow(t, 2.0)) * 0.008;
                c.vel = c.vel.add(repulse.multiply(repulseStr));

                // Chain stiffness (C#: vel += DirVec(prev-2, chunk) * 0.2, both ways → /20)
                if (i > 1) {
                    Vec3d stiffDir = dirVec(chunks[i - 2].pos, c.pos);
                    c.vel = c.vel.add(stiffDir.multiply(0.015));
                    chunks[i - 2].vel = chunks[i - 2].vel.subtract(stiffDir.multiply(0.015));
                }
            }
        }

        // ── Chain-constraint physics (C#: TentacleChunk.Update) ──
        for (int i = 0; i < chunks.length; i++) {
            chunks[i].update(basePos);
        }

        // ── Pin chunk 0 at the root so the body emerges from the surface ──
        chunks[0].pos = basePos;
        chunks[0].vel = Vec3d.ZERO;

        // ── Push chunks apart (C#: Tentacle.PushChunksApart) ──
        for (int i = 0; i < chunks.length - 1; i++) {
            pushChunksApart(i, i + 1);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════

    /**
     * Push two chunks apart if they overlap (C#: Tentacle.PushChunksApart).
     */
    private void pushChunksApart(int a, int b) {
        RenderTentacleChunk ca = chunks[a];
        RenderTentacleChunk cb = chunks[b];
        double dist = ca.pos.distanceTo(cb.pos);
        double minDist = ca.rad + cb.rad;
        if (dist < minDist && dist > 1e-8) {
            Vec3d dir = cb.pos.subtract(ca.pos).multiply(1.0 / dist);
            double push = (minDist - dist) * 0.5;
            ca.pos = ca.pos.subtract(dir.multiply(push));
            cb.pos = cb.pos.add(dir.multiply(push));
        }
    }

    /** Direction vector from a toward b (normalised). */
    private static Vec3d dirVec(Vec3d a, Vec3d b) {
        Vec3d d = b.subtract(a);
        double len = d.length();
        if (len < 1e-8) return Vec3d.ZERO;
        return d.multiply(1.0 / len);
    }

    /** Clamp a vector's magnitude. */
    private static Vec3d clampMagnitude(Vec3d v, double max) {
        double len = v.length();
        if (len > max && len > 1e-8) {
            return v.multiply(max / len);
        }
        return v;
    }
}
