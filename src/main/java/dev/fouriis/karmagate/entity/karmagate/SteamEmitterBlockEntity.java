// dev/fouriis/karmagate/entity/SteamEmitterBlockEntity.java
package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.sound.ModSounds;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Random;

/**
 * Steam emitter used by both standalone effects and KarmaGateController.
 *
 * <p>When gateManaged, the controller owns the source randomness. This entity
 * receives a continuous steam-loop level plus an explicit number of source
 * puff events. It must not independently roll Rain World's particle
 * probability again or puffs would be double-randomized.</p>
 */
public class SteamEmitterBlockEntity extends BlockEntity {
    private static final float EPS = 1.0e-4f;

    /*
     * Legacy/standalone mode.
     */
    private boolean enabled = false;
    private float flow = 0.0f;

    /*
     * New controller-driven mode.
     */
    private boolean gateManaged = false;
    private float gateContinuousLevel = 0.0f;

    /*
     * Monotonic event sequence. Using a sequence instead of synchronizing only
     * "puffCount this tick" guarantees that identical puff counts on adjacent
     * server ticks still produce distinct client events.
     */
    private long gatePuffSequence = 0L;
    private float gatePuffIntensity = 0.0f;

    private final Random rng = new Random();

    /*
     * Client-only consumption state.
     */
    private float clientSteamPressure = 0.0f;
    private long clientConsumedPuffSequence = 0L;
    private boolean clientGateSequenceInitialized = false;

    public SteamEmitterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STEAM_EMITTER_BLOCK_ENTITY, pos, state);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isGateManaged() {
        return gateManaged;
    }

    public float getFlow() {
        return gateManaged ? gateContinuousLevel : flow;
    }

    public float getGateContinuousLevel() {
        return gateContinuousLevel;
    }

    public long getGatePuffSequence() {
        return gatePuffSequence;
    }

    public float getGatePuffIntensity() {
        return gatePuffIntensity;
    }

    public void setEnabled(boolean enabled) {
        setFlow(enabled ? 1.0f : 0.0f);
    }

    /**
     * Legacy/standalone pressure setter.
     *
     * <p>Calling this explicitly exits gate-managed mode.</p>
     */
    public void setFlow(float value) {
        if (world != null && world.isClient) return;

        float next = clamp01(value);
        boolean nextEnabled = next > EPS;

        boolean changed =
                gateManaged
                        || Math.abs(next - flow) > EPS
                        || enabled != nextEnabled;

        gateManaged = false;
        flow = next;
        gateContinuousLevel = 0.0f;
        enabled = nextEnabled;

        if (changed) {
            markDirtySync();
        }
    }

    /**
     * Exact gate output supplied once per Minecraft tick.
     *
     * @param continuousLevel source steam-loop level for this physical emitter
     * @param puffCount       number of source puff events accumulated during
     *                        the two 40 Hz controller updates this MC tick
     * @param puffIntensity   strongest puff intensity in that batch
     */
    public void setGateSteamState(
            float continuousLevel,
            int puffCount,
            float puffIntensity
    ) {
        if (world != null && world.isClient) return;

        float nextContinuous = clamp01(continuousLevel);
        float nextIntensity = clamp01(puffIntensity);
        int safePuffCount = Math.max(0, puffCount);

        boolean changed =
                !gateManaged
                        || Math.abs(
                        nextContinuous - gateContinuousLevel
                ) > EPS
                        || safePuffCount > 0
                        || (
                        safePuffCount > 0
                                && Math.abs(
                                nextIntensity - gatePuffIntensity
                        ) > EPS
                );

        gateManaged = true;
        gateContinuousLevel = nextContinuous;
        flow = nextContinuous;
        enabled = nextContinuous > EPS;

        if (safePuffCount > 0) {
            gatePuffSequence += safePuffCount;
            gatePuffIntensity = nextIntensity;
        }

        if (changed) {
            markDirtySync();
        }
    }

    public static void tick(
            World world,
            BlockPos pos,
            BlockState state,
            SteamEmitterBlockEntity be
    ) {
        if (!world.isClient) {
            return;
        }

        if (be.gateManaged) {
            be.tickGateManagedClient(world, pos);
        } else {
            be.tickLegacyClient(world, pos);
        }
    }

    private void tickGateManagedClient(
            World world,
            BlockPos pos
    ) {
        clientSteamPressure = gateContinuousLevel;

        /*
         * Initial chunk NBT can contain a historical cumulative sequence. The
         * first client observation establishes the baseline rather than
         * replaying all old puffs.
         */
        if (!clientGateSequenceInitialized) {
            clientConsumedPuffSequence =
                    gatePuffSequence;
            clientGateSequenceInitialized = true;
        } else {
            long pending =
                    gatePuffSequence
                            - clientConsumedPuffSequence;

            if (pending < 0L) {
                /*
                 * Save reload / controller reset.
                 */
                clientConsumedPuffSequence =
                        gatePuffSequence;
                pending = 0L;
            }

            /*
             * A normal controller tick can only accumulate a handful of
             * source puffs. Cap pathological packet catch-up so reconnecting
             * cannot produce hundreds of particles in one frame.
             */
            int emitCount =
                    (int) Math.min(
                            16L,
                            pending
                    );

            for (int i = 0; i < emitCount; i++) {
                emitSteamParticle(
                        world,
                        pos,
                        gatePuffIntensity
                );
            }

            clientConsumedPuffSequence =
                    gatePuffSequence;
        }

        if (clientSteamPressure > 0.0f) {
            ModSounds.onSteamBurst(
                    pos,
                    clientSteamPressure,
                    ModSounds.STEAM_LOOP_2_EVENT
            );
        }
    }

    private void tickLegacyClient(
            World world,
            BlockPos pos
    ) {
        clientGateSequenceInitialized = false;
        clientSteamPressure = flow;

        /*
         * Preserve the old standalone behavior: two Rain World-frequency
         * emission attempts per Minecraft tick.
         */
        for (int step = 0; step < 2; step++) {
            float pressure =
                    clientSteamPressure;

            if (pressure <= 0.0f) {
                continue;
            }

            if (
                    Math.pow(
                            rng.nextDouble(),
                            1.5
                    ) < pressure * 2.0f
            ) {
                emitSteamParticle(
                        world,
                        pos,
                        (float) Math.pow(
                                pressure,
                                0.75
                        )
                );
            }
        }

        if (clientSteamPressure > 0.5f) {
            ModSounds.onSteamBurst(
                    pos,
                    clientSteamPressure,
                    ModSounds.STEAM_LOOP_2_EVENT
            );
        }
    }

    private void emitSteamParticle(
            World world,
            BlockPos pos,
            float intensity
    ) {
        double centerX =
                pos.getX() + 0.5;

        double centerZ =
                pos.getZ() + 0.5;

        double px =
                centerX
                        + (
                        rng.nextDouble()
                                * 2.0
                                - 1.0
                ) * 0.75;

        double py =
                pos.getY()
                        + 1.5
                        + (
                        rng.nextDouble()
                                * 2.0
                                - 1.0
                ) * 0.5;

        double pz =
                centerZ
                        + (
                        rng.nextDouble()
                                * 2.0
                                - 1.0
                ) * 0.75;

        float safeIntensity =
                clamp01(intensity);

        world.addParticle(
                ModParticles.STEAM,
                px,
                py,
                pz,
                centerX - px,
                safeIntensity,
                centerZ - pz
        );
    }

    @Override
    protected void writeNbt(
            NbtCompound nbt,
            RegistryWrapper.WrapperLookup lookup
    ) {
        super.writeNbt(nbt, lookup);

        nbt.putBoolean(
                "enabled",
                enabled
        );

        nbt.putFloat(
                "flow",
                flow
        );

        nbt.putBoolean(
                "gateManaged",
                gateManaged
        );

        nbt.putFloat(
                "gateContinuousLevel",
                gateContinuousLevel
        );

        nbt.putLong(
                "gatePuffSequence",
                gatePuffSequence
        );

        nbt.putFloat(
                "gatePuffIntensity",
                gatePuffIntensity
        );
    }

    @Override
    public void readNbt(
            NbtCompound nbt,
            RegistryWrapper.WrapperLookup lookup
    ) {
        super.readNbt(nbt, lookup);

        float legacyFlow =
                nbt.contains("flow")
                        ? clamp01(
                        nbt.getFloat("flow")
                )
                        : nbt.getBoolean("enabled")
                        ? 1.0f
                        : 0.0f;

        boolean incomingGateManaged =
                nbt.getBoolean("gateManaged");

        float incomingContinuous =
                nbt.contains("gateContinuousLevel")
                        ? clamp01(
                        nbt.getFloat(
                                "gateContinuousLevel"
                        )
                )
                        : legacyFlow;

        long incomingSequence =
                nbt.contains("gatePuffSequence")
                        ? nbt.getLong(
                        "gatePuffSequence"
                )
                        : 0L;

        float incomingIntensity =
                nbt.contains("gatePuffIntensity")
                        ? clamp01(
                        nbt.getFloat(
                                "gatePuffIntensity"
                        )
                )
                        : 0.0f;

        if (
                world != null
                        && world.isClient
                        && incomingGateManaged
        ) {
            if (!clientGateSequenceInitialized) {
                clientConsumedPuffSequence =
                        incomingSequence;
                clientGateSequenceInitialized = true;
            }
        }

        gateManaged = incomingGateManaged;
        gateContinuousLevel =
                incomingContinuous;

        gatePuffSequence =
                incomingSequence;

        gatePuffIntensity =
                incomingIntensity;

        flow =
                gateManaged
                        ? gateContinuousLevel
                        : legacyFlow;

        enabled =
                gateManaged
                        ? gateContinuousLevel > EPS
                        : flow > EPS;

        if (!gateManaged && world != null && world.isClient) {
            clientGateSequenceInitialized = false;
        }
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(
            RegistryWrapper.WrapperLookup lookup
    ) {
        return createNbt(lookup);
    }

    private void markDirtySync() {
        markDirty();

        if (world instanceof ServerWorld serverWorld) {
            serverWorld
                    .getChunkManager()
                    .markForUpdate(pos);
        }

        if (world != null) {
            world.updateListeners(
                    pos,
                    getCachedState(),
                    getCachedState(),
                    3
            );
        }
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    private static float clamp01(float value) {
        return Math.max(
                0.0f,
                Math.min(
                        1.0f,
                        value
                )
        );
    }
}
