package dev.fouriis.karmagate;

import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;

/**
 * Reusable Rain World-style mycelium strand simulation (ported from CoralBrain.Mycelium).
 *
 * Key behaviors mirrored from C#:
 * - points[i][0]=pos, points[i][1]=prevPos, points[i][2]=vel :contentReference[oaicite:0]{index=0}
 * - conRad = length / pointCount :contentReference[oaicite:1]{index=1}
 * - per-tick: pos += vel; vel *= 0.999 :contentReference[oaicite:2]{index=2}
 * - base is pinned to owner.ConnectionPos(index, 1f); base vel zeroed :contentReference[oaicite:3]{index=3}
 * - optional tip-to-tip connections between different owners :contentReference[oaicite:4]{index=4}
 *
 * Notes vs RW:
 * - RW does wall avoidance + terrain proximity via a job (MyceliumJob01). This class does NOT.
 *   You can add collision/wall push later if you want.
 *
 * Rendering hook:
 * - call samplePoints(timeStacker) each frame; build your ribbon/segments from returned points.
 */
public final class Mycelium {

    /**
     * Port of IOwnMycelia: the thing that owns/anchors this strand.
     *
     * ConnectionPos(index, t) is used for base interpolation in render and for pinning in sim.
     * ResetDir(index) is used to choose initial "grow" direction on reset. :contentReference[oaicite:5]{index=5}
     */
    public interface Owner {
        Vec3d connectionPos(int index, float timeStacker);

        /** Should be roughly unit-length. */
        Vec3d resetDir(int index);

        /** Used to avoid connecting to strands from the same owner. */
        default Object ownerIdentity() {
            return this;
        }
    }

    /**
     * Symmetric connection between two strands (like MyceliaConnection). :contentReference[oaicite:6]{index=6}
     */
    public static final class Connection {
        public final Mycelium a;
        public final Mycelium b;

        public Connection(Mycelium a, Mycelium b) {
            this.a = a;
            this.b = b;
        }

        public Mycelium other(Mycelium me) {
            return me == a ? b : a;
        }
    }

    // points[i][0]=pos, points[i][1]=prevPos, points[i][2]=vel :contentReference[oaicite:7]{index=7}
    private final Vec3d[][] points;

    public final Owner owner;
    public final int index;
    public final double length;
    public final double conRad;

    // Optional: maintained by caller (e.g., a system list) to enable cross-strand linking.
    public Connection connection = null;
    public int rest = 0;

    // Your renderer may want this
    public int pointCount() {
        return points.length;
    }

    public Vec3d basePos() {
        return points[0][0];
    }

    public Vec3d tipPos() {
        return points[points.length - 1][0];
    }

    /**
     * @param length      strand length (blocks)
     * @param initPoint   initial base point (local or world, your choice—just be consistent)
     * @param seedSalt    stable seed input (e.g., entityId, objectId) so resets are deterministic-ish
     */
    public Mycelium(Owner owner, int index, double length, Vec3d initPoint, long seedSalt) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.index = index;
        this.length = length;

        // RW: num = Max(length, Lerp(length, 40, 0.5)) ; points = Clamp((int)(num/15), 2, 20) :contentReference[oaicite:8]{index=8}
        double num = Math.max(length, lerp(length, 40.0, 0.5));
        int n = clampInt((int) Math.ceil(num / 8.0), 6, 20);

        this.points = new Vec3d[n][3];
        this.conRad = length / (double) n; // :contentReference[oaicite:9]{index=9}

        reset(initPoint, seedSalt);
    }

    /**
     * Resets the strand into a bezier-ish shape (3D approximation of RW's 2D bezier + noise). :contentReference[oaicite:10]{index=10}
     */
    public void reset(Vec3d resetPos, long seedSalt) {
        Vec3d dir = safeNormalize(owner.resetDir(index), new Vec3d(0, 1, 0));
        Vec3d tip = resetPos.add(dir.multiply(length * 0.6));

        // RW uses random perturbations in control points. We do similar in 3D.
        Vec3d r1 = randomUnit(seedSalt ^ 0xA2B3C4D5E6F70819L).multiply(0.5);
        Vec3d r2 = randomUnit(seedSalt ^ 0x19F807E6D5C4B3A2L).multiply(0.5);

        double halfDist = resetPos.distanceTo(tip) * 0.5;

        Vec3d cA = resetPos.add(safeNormalize(tip.subtract(resetPos).add(r1), dir).multiply(halfDist));
        Vec3d cB = tip.add(safeNormalize(resetPos.subtract(tip).add(r2), dir.multiply(-1)).multiply(halfDist));

        int last = points.length - 1;
        for (int i = 0; i < points.length; i++) {
            double t = (last == 0) ? 0.0 : (double) i / (double) last;
            Vec3d p = cubicBezier(resetPos, cA, tip, cB, t).add(randomUnit(seedSalt + i * 1013L).multiply(0.15));
            points[i][0] = p;      // pos
            points[i][1] = p;      // prev
            points[i][2] = randomUnit(seedSalt + i * 9176L).multiply(0.05); // vel (small)
        }
    }

    /**
     * Main update step.
     *
     * @param wind       a small drift vector (like system.wind); you can pass Vec3d.ZERO if none.
     * @param forceScale scale to compensate for tick rate differences (0.35-0.6 usually good).
     * @param tickSeed   per-tick seed for probabilistic behaviors (connections).
     * @param systemPool optional list of all mycelia in a "system" to enable connecting; may be null/empty.
     */
    public void tick(Vec3d wind, double forceScale, long tickSeed, List<Mycelium> systemPool) {
        // Integrate: pos += vel; vel *= 0.999 :contentReference[oaicite:11]{index=11}
        for (int i = 0; i < points.length; i++) {
            Vec3d pos = points[i][0];
            Vec3d prev = pos;
            Vec3d vel = points[i][2];

            // apply velocity
            pos = pos.add(vel);

            // damping (RW: *0.999f)
            vel = vel.multiply(0.999);

            // optional wind (caller decides magnitude, RW elsewhere adds wind to other structures)
            if (wind != null) {
                vel = vel.add(wind.multiply(0.0025 * forceScale));
            }

            points[i][1] = prev;
            points[i][0] = pos;
            points[i][2] = vel;
        }

        // Pin base to owner.ConnectionPos(index, 1f) and zero base vel :contentReference[oaicite:12]{index=12}
        Vec3d base = owner.connectionPos(index, 1.0f);
        points[0][0] = base;
        points[0][2] = Vec3d.ZERO;

        // Soft constraint passes similar to CoralNeuron.Connect (weighted by inverse lerp) :contentReference[oaicite:13]{index=13}
        // (MyceliumJob01 likely does something similar + wall push. We do just distance keeping.)
        for (int i = points.length - 1; i > 0; i--) {
            connect(i, i - 1);
        }
        points[0][0] = base;
        points[0][2] = Vec3d.ZERO;

        for (int i = 1; i < points.length; i++) {
            connect(i, i - 1);
        }
        points[0][0] = base;
        points[0][2] = Vec3d.ZERO;

        // Rest countdown (used to pause reconnection) :contentReference[oaicite:14]{index=14}
        if (rest > 0) rest--;

        // Connection logic (tip-to-tip), ported from RW :contentReference[oaicite:15]{index=15}
        if (connection != null) {
            Mycelium other = connection.other(this);

            boolean invalid =
                    other == null ||
                    other.connection != connection ||
                    !distLess(connection.a.basePos(), connection.b.basePos(), connection.a.length + connection.b.length) ||
                    hash01(tickSeed ^ 0x5DEECE66DL) < 0.005; // RW: Random.value < 0.005f :contentReference[oaicite:16]{index=16}

            if (invalid) {
                connection = null;
                rest = randRangeInt(tickSeed ^ 0xBADC0FFEE0DDF00DL, 20, 200); // :contentReference[oaicite:17]{index=17}
                return;
            }

            Vec3d tipA = this.tipPos();
            Vec3d tipB = other.tipPos();

            if (distLess(tipA, tipB, 10.0)) { // :contentReference[oaicite:18]{index=18}
                Vec3d dir = safeNormalize(tipB.subtract(tipA), new Vec3d(1, 0, 0));
                double d = tipA.distanceTo(tipB);

                Vec3d move = dir.multiply((d - 1.0) * 0.5);

                int lastA = points.length - 1;
                int lastB = other.points.length - 1;

                // pos and vel both adjusted (RW does this) :contentReference[oaicite:19]{index=19}
                points[lastA][0] = points[lastA][0].add(move);
                points[lastA][2] = points[lastA][2].add(move);

                other.points[lastB][0] = other.points[lastB][0].subtract(move);
                other.points[lastB][2] = other.points[lastB][2].subtract(move);

                // RW sometimes spawns NeuronSpark here; you can do particles in caller if you want. :contentReference[oaicite:20]{index=20}
            } else {
                // tip vel lerp toward clamp(otherTip - tip, 5) at 0.5 :contentReference[oaicite:21]{index=21}
                int last = points.length - 1;
                Vec3d target = clampMagnitude(tipB.subtract(tipA), 5.0);
                points[last][2] = lerp(points[last][2], target, 0.5);
            }
        } else if (systemPool != null && rest < 1 && !systemPool.isEmpty()) {
            // Attempt to connect to a random other strand from different owner :contentReference[oaicite:22]{index=22}
            int pick = randRangeInt(tickSeed ^ 0xCAFEBABEL, 0, systemPool.size() - 1);
            Mycelium candidate = systemPool.get(pick);

            if (candidate != null
                    && candidate != this
                    && !Objects.equals(candidate.owner.ownerIdentity(), this.owner.ownerIdentity())
                    && candidate.connection == null
                    && distLess(this.basePos(), candidate.basePos(), (this.length + candidate.length) * 0.75)) { // :contentReference[oaicite:23]{index=23}
                Connection c = new Connection(this, candidate);
                this.connection = c;
                candidate.connection = c;
            }
        }
    }

    /**
     * Like CoralNeuron adding sideways "spread" to early points of each mycelium:
     * mycelia.points[1,2] += perp * ±1 and optionally [2,2] += perp * ±0.5 :contentReference[oaicite:24]{index=24}
     */
    public void addImpulseNearBase(Vec3d impulse) {
        if (points.length > 1) points[1][2] = points[1][2].add(impulse);
        if (points.length > 2) points[2][2] = points[2][2].add(impulse.multiply(0.5));
    }

    /**
     * Sample interpolated positions for rendering (like Vector2.Lerp(points[i,1], points[i,0], timeStacker)). :contentReference[oaicite:25]{index=25}
     * The base uses owner.connectionPos(index, timeStacker) to stay glued during interpolation. :contentReference[oaicite:26]{index=26}
     */
    public Vec3d[] samplePoints(float timeStacker) {
        Vec3d[] out = new Vec3d[points.length];
        out[0] = owner.connectionPos(index, timeStacker);

        for (int i = 1; i < points.length; i++) {
            Vec3d prev = points[i][1];
            Vec3d cur = points[i][0];
            out[i] = lerp(prev, cur, timeStacker);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Internal: connect constraint
    // -------------------------------------------------------------------------

    private void connect(int a, int b) {
        Vec3d pa = points[a][0];
        Vec3d pb = points[b][0];

        Vec3d delta = pa.subtract(pb);
        double dist = delta.length();
        if (dist < 1e-8) return;

        Vec3d dir = delta.multiply(1.0 / dist);

        // RW uses num2 = InverseLerp(0, conRad, dist) :contentReference[oaicite:27]{index=27}
        double w = inverseLerpClamped(0.0, conRad, dist);

        Vec3d move = dir.multiply((conRad - dist) * 0.5 * w);

        // RW adjusts both pos and vel for BOTH points :contentReference[oaicite:28]{index=28}
        points[a][0] = points[a][0].add(move);
        points[a][2] = points[a][2].add(move);

        points[b][0] = points[b][0].subtract(move);
        points[b][2] = points[b][2].subtract(move);
    }

    // -------------------------------------------------------------------------
    // Math + deterministic RNG helpers
    // -------------------------------------------------------------------------

    private static boolean distLess(Vec3d a, Vec3d b, double d) {
        return a.squaredDistanceTo(b) < d * d;
    }

    private static Vec3d clampMagnitude(Vec3d v, double maxLen) {
        double ls = v.lengthSquared();
        double maxLs = maxLen * maxLen;
        if (ls <= maxLs) return v;
        return v.multiply(maxLen / Math.sqrt(ls));
    }

    private static Vec3d safeNormalize(Vec3d v, Vec3d fallback) {
        double ls = v.lengthSquared();
        if (ls < 1e-12) return fallback;
        return v.multiply(1.0 / Math.sqrt(ls));
    }

    private static Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return a.add(b.subtract(a).multiply(t));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double inverseLerpClamped(double a, double b, double v) {
        if (a == b) return 0.0;
        double t = (v - a) / (b - a);
        if (t < 0.0) return 0.0;
        if (t > 1.0) return 1.0;
        return t;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Deterministic pseudo-random in [0,1).
     */
    private static double hash01(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return ((x >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private static int randRangeInt(long seed, int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) return minInclusive;
        double r = hash01(seed);
        int span = (maxInclusive - minInclusive) + 1;
        int v = (int) Math.floor(r * span);
        if (v >= span) v = span - 1;
        return minInclusive + v;
    }

    /**
     * Deterministic random unit vector (not perfectly uniform, but stable and "good enough" for motion).
     */
    private static Vec3d randomUnit(long seed) {
        double x = hash01(seed * 31L + 0x1234ABCDL) * 2.0 - 1.0;
        double y = hash01(seed * 17L + 0xBEEFCAFE1L) * 2.0 - 1.0;
        double z = hash01(seed * 73L + 0x0DDC0FFEL) * 2.0 - 1.0;

        Vec3d v = new Vec3d(x, y, z);
        return safeNormalize(v, new Vec3d(1, 0, 0));
    }

    private static Vec3d cubicBezier(Vec3d p0, Vec3d c0, Vec3d p1, Vec3d c1, double t) {
        // Note: RW calls Custom.Bezier(resetPos, cA, vector, cB, t) (their parameter order). :contentReference[oaicite:29]{index=29}
        // We’re treating it as cubic Bezier: p0 -> c0 -> p1 -> c1.
        double u = 1.0 - t;
        double tt = t * t;
        double uu = u * u;
        double uuu = uu * u;
        double ttt = tt * t;

        Vec3d a = p0.multiply(uuu);
        Vec3d b = c0.multiply(3.0 * uu * t);
        Vec3d c = p1.multiply(3.0 * u * tt);
        Vec3d d = c1.multiply(ttt);
        return a.add(b).add(c).add(d);
    }
}
