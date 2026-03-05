package dev.fouriis.karmagate.entity.tentacle;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * A single physical chunk of a {@link RenderTentacle}.
 * <p>
 * Matches C# {@code Tentacle.TentacleChunk}: holds position, velocity, radius,
 * and supports chain-link constraints between adjacent chunks.
 * <p>
 * This is a client-side rendering construct — it does not interact with the
 * MC block world. It simulates verlet-like physics each tick so that the
 * tentacle moves organically between the root (base) and the head (tip).
 */
public class RenderTentacleChunk {

    /** Current world position. */
    public Vec3d pos;
    /** Position at the start of the previous tick (for interpolation). */
    public Vec3d lastPos;
    /** Velocity vector. */
    public Vec3d vel;
    /** Base radius of this chunk (C# pixels → MC blocks: px / 20). */
    public float rad;
    /** Normalised position along the tentacle (0 = base, 1 = tip). */
    public float tPos;
    /** Index within the parent tentacle's chunk array. */
    public int index;
    /** Stretch factor — ratio of ideal segment length to actual distance. */
    public float stretchedFac = 1f;

    // ── Owner reference ──────────────────────────────────────────────
    private RenderTentacle tentacle;

    // ══════════════════════════════════════════════════════════════════
    //  Constructor
    // ══════════════════════════════════════════════════════════════════

    public RenderTentacleChunk(RenderTentacle tentacle, int index, float tPos, float rad) {
        this.tentacle = tentacle;
        this.index = index;
        this.tPos = tPos;
        this.rad = rad;
        this.pos = Vec3d.ZERO;
        this.lastPos = Vec3d.ZERO;
        this.vel = Vec3d.ZERO;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Computed properties
    // ══════════════════════════════════════════════════════════════════

    /**
     * Stretched radius — mimics C#
     * {@code rad * Clamp(pow(stretchedFac, stretchAndSqueeze), 0.5, 1.5)}.
     */
    public float stretchedRad() {
        float s = (float) Math.pow(stretchedFac, tentacle.stretchAndSqueeze);
        return rad * MathHelper.clamp(s, 0.5f, 1.5f);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Reset
    // ══════════════════════════════════════════════════════════════════

    /** Reset this chunk to the given position with zero velocity. */
    public void reset(Vec3d resetPos) {
        this.pos = resetPos;
        this.lastPos = resetPos;
        this.vel = Vec3d.ZERO;
        this.stretchedFac = 1f;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Physics update  (C#: TentacleChunk.Update, simplified)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Run one physics tick.
     * <p>
     * Mirrors the C# TentacleChunk.Update logic:
     * <ul>
     *   <li>Chain-link constraint to the previous chunk (or base position)</li>
     *   <li>Velocity clamping</li>
     *   <li>Position integration</li>
     * </ul>
     *
     * @param basePos the base (root) position of the tentacle in world space
     */
    public void update(Vec3d basePos) {
        lastPos = pos;

        // ── Chain constraint ──
        Vec3d anchorPos;
        if (index == 0) {
            anchorPos = basePos;
        } else {
            anchorPos = tentacle.getChunk(index - 1).pos;
        }

        Vec3d toAnchor = dirVec(pos, anchorPos);
        double dist = pos.distanceTo(anchorPos);
        double idealSegLen = tentacle.idealLength / tentacle.chunkCount()
                * MathHelper.lerp(tentacle.retractFac, 1.0, 0.1);

        boolean stiff = tentacle.stiff;
        if (stiff || dist > idealSegLen) {
            double correction = idealSegLen - dist;
            double myShare = (index == 0) ? 1.0 : (1.0 - tentacle.massDeteriorationPerChunk);
            pos = pos.subtract(toAnchor.multiply(correction * myShare));
            vel = vel.subtract(toAnchor.multiply(correction * myShare));

            if (index > 0) {
                RenderTentacleChunk prev = tentacle.getChunk(index - 1);
                prev.pos = prev.pos.add(toAnchor.multiply(correction * tentacle.massDeteriorationPerChunk));
                prev.vel = prev.vel.add(toAnchor.multiply(correction * tentacle.massDeteriorationPerChunk));
            }
        }

        stretchedFac = (float) (idealSegLen / Math.max(0.001, dist));

        // ── Velocity cap ──
        double speed = vel.length();
        if (speed > tentacle.chunkVelocityCap) {
            vel = vel.multiply(tentacle.chunkVelocityCap / speed);
        }

        // ── Integrate ──
        pos = pos.add(vel);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Utility
    // ══════════════════════════════════════════════════════════════════

    /** Direction vector from a to b (normalised, or zero if coincident). */
    private static Vec3d dirVec(Vec3d a, Vec3d b) {
        Vec3d d = b.subtract(a);
        double len = d.length();
        if (len < 1e-8) return Vec3d.ZERO;
        return d.multiply(1.0 / len);
    }
}
