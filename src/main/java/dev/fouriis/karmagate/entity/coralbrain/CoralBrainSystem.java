package dev.fouriis.karmagate.entity.coralbrain;

import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * Per-world equivalent of Rain World's {@code CoralNeuronSystem}.
 *
 * <p>Every neuron and WallMycelia anchor registers its strands here. This is
 * deliberately shared across object types: C# mycelia find connections through
 * one room-wide list, and SS swarmers select their suckle targets from that same
 * list.</p>
 */
public final class CoralBrainSystem {
    private static final WeakHashMap<World, State> STATES = new WeakHashMap<>();

    private CoralBrainSystem() {
    }

    private static State state(World world) {
        synchronized (STATES) {
            long dimensionSeed = world.getRegistryKey().getValue().hashCode() * 0x9E3779B97F4A7C15L;
            return STATES.computeIfAbsent(world, ignored -> new State(dimensionSeed));
        }
    }

    public static void register(World world, Mycelium strand) {
        if (world == null || strand == null) return;
        State state = state(world);
        if (!state.mycelia.contains(strand)) state.mycelia.add(strand);
    }

    public static void unregister(World world, Mycelium strand) {
        if (world == null || strand == null) return;
        synchronized (STATES) {
            State state = STATES.get(world);
            if (state != null && state.mycelia.remove(strand)) strand.disconnect();
        }
    }

    public static void unregisterOwner(World world, Object ownerIdentity) {
        if (world == null || ownerIdentity == null) return;
        synchronized (STATES) {
            State state = STATES.get(world);
            if (state != null) {
                ArrayList<Mycelium> removed = new ArrayList<>();
                state.mycelia.removeIf(strand -> {
                    boolean matches = strand.owner.ownerIdentity() == ownerIdentity;
                    if (matches) removed.add(strand);
                    return matches;
                });
                removed.forEach(Mycelium::disconnect);
            }
        }
    }

    /** Stable snapshot, because strands may be added during block/entity ticks. */
    public static List<Mycelium> mycelia(World world) {
        if (world == null) return List.of();
        State state = state(world);
        return Collections.unmodifiableList(new ArrayList<>(state.mycelia));
    }

    /**
     * Adds the brief, stationary two-sprite flash made by C# {@code NeuronSpark}.
     * Rendering remains client-only, but the contact simulation can publish the
     * event here without depending on client source-set classes.
     */
    public static void spawnNeuronSpark(World world, Vec3d position, long seed) {
        if (world == null || !world.isClient || position == null) return;
        State state = state(world);
        float lifeTimeTicks = 0.5f + (float) hash01(seed ^ 0xA24BAED4963EE407L) * 1.5f;
        state.neuronSparks.add(new NeuronSpark(position, world.getTime(), lifeTimeTicks, seed));
    }

    /** Returns the live spark events and retires flashes whose 1-4 RW frames elapsed. */
    public static List<NeuronSpark> neuronSparks(World world, float tickDelta) {
        if (world == null) return List.of();
        State state = state(world);
        updateNeuronSparkContacts(world, state);
        double now = world.getTime() + tickDelta;
        state.neuronSparks.removeIf(spark -> now - spark.spawnTick() > spark.lifeTimeTicks() + 0.05);
        return Collections.unmodifiableList(new ArrayList<>(state.neuronSparks));
    }

    /**
     * Performs the C# 40 Hz tip-contact spark test centrally. In 3D, two tips
     * can visibly meet one frame before their randomly selected connection is
     * established; using the actual tip positions prevents those contacts from
     * silently missing their flash.
     */
    private static void updateNeuronSparkContacts(World world, State state) {
        long tick = world.getTime();
        if (!world.isClient || state.lastSparkContactTick == tick) return;
        state.lastSparkContactTick = tick;

        final double contactRadius = 10.0 / 20.0;
        final double inverseCellSize = 1.0 / contactRadius;
        Map<TipCell, ArrayList<IndexedTip>> nearby = new HashMap<>();

        for (int index = 0; index < state.mycelia.size(); index++) {
            Mycelium strand = state.mycelia.get(index);
            if (strand == null) continue;
            Vec3d tip = strand.tipPos();
            TipCell cell = TipCell.of(tip, inverseCellSize);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<IndexedTip> candidates = nearby.get(cell.offset(dx, dy, dz));
                        if (candidates == null) continue;
                        for (IndexedTip candidate : candidates) {
                            Mycelium other = candidate.strand();
                            if (other.owner.ownerIdentity() == strand.owner.ownerIdentity()
                                    || other.tipPos().squaredDistanceTo(tip)
                                    >= contactRadius * contactRadius) continue;

                            // Minecraft advances two Rain World updates per tick.
                            // Preserve the original 5% chance on both 40 Hz steps.
                            for (int substep = 0; substep < 2; substep++) {
                                if (state.sparkRandom.nextFloat() < 0.05f) {
                                    Vec3d midpoint = tip.add(other.tipPos()).multiply(0.5);
                                    long seed = state.sparkRandom.nextLong()
                                            ^ ((long) candidate.index() << 32) ^ index ^ substep;
                                    float lifeTimeTicks = 0.5f + state.sparkRandom.nextFloat() * 1.5f;
                                    state.neuronSparks.add(new NeuronSpark(
                                            midpoint, tick, lifeTimeTicks, seed));
                                }
                            }
                        }
                    }
                }
            }
            nearby.computeIfAbsent(cell, ignored -> new ArrayList<>())
                    .add(new IndexedTip(index, strand));
        }
    }

    /** C# wind: random walk by RNV*0.2 and clamp to magnitude one, scaled to blocks. */
    public static Vec3d wind(World world) {
        if (world == null) return Vec3d.ZERO;
        State state = state(world);
        long tick = world.getTime();
        if (state.lastWindTick != tick) {
            state.lastWindTick = tick;
            for (int substep = 0; substep < 2; substep++) {
                Vec3d change = randomUnit(state.random).multiply(0.01 * state.random.nextDouble());
                state.wind = clampMagnitude(state.wind.add(change), 0.05);
            }
        }
        return state.wind;
    }

    public static void clear(World world) {
        if (world == null) return;
        synchronized (STATES) {
            STATES.remove(world);
        }
    }

    public static void clearAll() {
        synchronized (STATES) {
            STATES.clear();
        }
    }

    private static Vec3d randomUnit(Random random) {
        double y = random.nextDouble() * 2.0 - 1.0;
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        return new Vec3d(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
    }

    private static Vec3d clampMagnitude(Vec3d value, double maximum) {
        double lengthSquared = value.lengthSquared();
        if (lengthSquared <= maximum * maximum) return value;
        return value.multiply(maximum / Math.sqrt(lengthSquared));
    }

    private static double hash01(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return ((value >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    public record NeuronSpark(Vec3d position, long spawnTick, float lifeTimeTicks, long seed) {
        public float life(double renderTime) {
            return (float) Math.max(0.0, Math.min(1.0,
                    1.0 - (renderTime - spawnTick) / lifeTimeTicks));
        }
    }

    private record IndexedTip(int index, Mycelium strand) {
    }

    private record TipCell(int x, int y, int z) {
        private static TipCell of(Vec3d position, double inverseCellSize) {
            return new TipCell(
                    (int) Math.floor(position.x * inverseCellSize),
                    (int) Math.floor(position.y * inverseCellSize),
                    (int) Math.floor(position.z * inverseCellSize));
        }

        private TipCell offset(int dx, int dy, int dz) {
            return new TipCell(x + dx, y + dy, z + dz);
        }
    }

    private static final class State {
        private final ArrayList<Mycelium> mycelia = new ArrayList<>();
        private final ArrayList<NeuronSpark> neuronSparks = new ArrayList<>();
        private final Random random;
        private final Random sparkRandom;
        private Vec3d wind;
        private long lastWindTick = Long.MIN_VALUE;
        private long lastSparkContactTick = Long.MIN_VALUE;

        private State(long seed) {
            random = new Random(seed ^ 0x434F52414C42524CL);
            sparkRandom = new Random(seed ^ 0x4E4555524F4E5350L);
            wind = randomUnit(random).multiply(random.nextDouble() * 0.05);
        }
    }
}
