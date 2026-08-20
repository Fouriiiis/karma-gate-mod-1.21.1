package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Gate waterfall stream with the richer source state supplied by
 * KarmaGateController.
 *
 * <p>The inherited WaterfallBlockEntity flow remains the authoritative actual
 * source flow for compatibility with the existing renderer. The extra fields
 * expose Rain World's separate requested flow, visual density and moving
 * waterfall fronts.</p>
 */
public class WaterStreamBlockEntity extends WaterfallBlockEntity {
    private static final float EPS = 1.0e-4f;

    private boolean gateManaged = false;

    private float requestedFlow = 0.0f;
    private float visualDensity = 0.0f;

    /*
     * Normalized source-space front positions:
     * 0 = strike/bottom level
     * 1 = emitter/start level
     */
    private float topProgress = 0.0f;
    private float bottomProgress = 0.0f;

    /*
     * Client interpolation endpoints. The controller updates at 20 network
     * samples/sec after internally advancing two 40 Hz source steps.
     */
    private float previousTopProgress = 0.0f;
    private float previousBottomProgress = 0.0f;
    private float previousVisualDensity = 0.0f;
    private boolean clientGateVisualInitialized = false;

    public WaterStreamBlockEntity(
            BlockPos pos,
            BlockState state
    ) {
        super(
                ModBlockEntities.WATER_STREAM_BLOCK_ENTITY,
                pos,
                state
        );

        // Keep the inherited actual-flow channel at zero until controlled.
        super.setFlow(0.0f);
    }

    public static void tick(
            World world,
            BlockPos pos,
            BlockState state,
            WaterStreamBlockEntity be
    ) {
        WaterfallBlockEntity.clientTick(
                world,
                pos,
                state,
                be
        );
    }

    public boolean isGateManaged() {
        return gateManaged;
    }

    public float getRequestedFlow() {
        return requestedFlow;
    }

    public float getActualFlow() {
        return getFlow();
    }

    public float getGateVisualDensity() {
        return visualDensity;
    }

    public float getGateTopProgress() {
        return topProgress;
    }

    public float getGateBottomProgress() {
        return bottomProgress;
    }

    public float getInterpolatedGateVisualDensity(
            float tickDelta
    ) {
        if (!clientGateVisualInitialized) {
            return visualDensity;
        }

        return lerp(
                previousVisualDensity,
                visualDensity,
                clamp01(tickDelta)
        );
    }

    public float getInterpolatedGateTopProgress(
            float tickDelta
    ) {
        if (!clientGateVisualInitialized) {
            return topProgress;
        }

        return lerp(
                previousTopProgress,
                topProgress,
                clamp01(tickDelta)
        );
    }

    public float getInterpolatedGateBottomProgress(
            float tickDelta
    ) {
        if (!clientGateVisualInitialized) {
            return bottomProgress;
        }

        return lerp(
                previousBottomProgress,
                bottomProgress,
                clamp01(tickDelta)
        );
    }

    /**
     * Exact WaterGate/WaterFall state from KarmaGateController.
     *
     * <p>No local flow ramp is performed here. requestedFlow and actualFlow are
     * deliberately distinct because the controller already runs WaterFall's
     * source update and front propagation.</p>
     */
    public void setGateWaterState(
            float requestedFlow,
            float actualFlow,
            float visualDensity,
            float topProgress,
            float bottomProgress
    ) {
        if (world != null && world.isClient) {
            return;
        }

        float nextRequested =
                clamp01(requestedFlow);

        float nextActual =
                clamp01(actualFlow);

        float nextDensity =
                clamp01(visualDensity);

        float nextTop =
                clamp01(topProgress);

        float nextBottom =
                clamp01(bottomProgress);

        boolean extraChanged =
                !gateManaged
                        || Math.abs(
                        this.requestedFlow
                                - nextRequested
                ) > EPS
                        || Math.abs(
                        this.visualDensity
                                - nextDensity
                ) > EPS
                        || Math.abs(
                        this.topProgress
                                - nextTop
                ) > EPS
                        || Math.abs(
                        this.bottomProgress
                                - nextBottom
                ) > EPS;

        boolean flowChanged =
                Math.abs(
                        getFlow() - nextActual
                ) > EPS;

        gateManaged = true;
        this.requestedFlow = nextRequested;
        this.visualDensity = nextDensity;
        this.topProgress = nextTop;
        this.bottomProgress = nextBottom;

        if (flowChanged) {
            /*
             * Keep the inherited actual-flow value synchronized so existing
             * WaterfallBlockRenderer code continues to see the controller's
             * actual source flow.
             */
            super.setFlow(nextActual);
        } else if (extraChanged) {
            markDirtySync();
        }
    }

    /**
     * Legacy standalone setter retained for non-gate waterfalls.
     */
    public void setTargetFlow(float value) {
        gateManaged = false;

        float next =
                clamp01(value);

        requestedFlow = next;
        visualDensity = next;
        topProgress = next > EPS ? 1.0f : 0.0f;
        bottomProgress = next > EPS ? 0.0f : 0.0f;

        super.setFlow(next);
    }

    /**
     * In gate-managed mode, the controller has already simulated the source
     * WaterFall propagation/front state. Do not add the inherited artificial
     * per-height flow delay on top of it.
     */
    @Override
    public float getEffectiveFlow(
            double clientTimeTicks,
            double distanceBlocks
    ) {
        if (gateManaged) {
            return getFlow();
        }

        return super.getEffectiveFlow(
                clientTimeTicks,
                distanceBlocks
        );
    }

    @Override
    protected void writeNbt(
            NbtCompound nbt,
            RegistryWrapper.WrapperLookup lookup
    ) {
        super.writeNbt(nbt, lookup);

        nbt.putBoolean(
                "gateManaged",
                gateManaged
        );

        nbt.putFloat(
                "requestedFlow",
                requestedFlow
        );

        nbt.putFloat(
                "visualDensity",
                visualDensity
        );

        nbt.putFloat(
                "topProgress",
                topProgress
        );

        nbt.putFloat(
                "bottomProgress",
                bottomProgress
        );

        /*
         * Legacy key kept for old worlds/tools. It now means requested flow,
         * not actual flow.
         */
        nbt.putFloat(
                "targetFlow",
                requestedFlow
        );
    }

    @Override
    public void readNbt(
            NbtCompound nbt,
            RegistryWrapper.WrapperLookup lookup
    ) {
        float oldTop =
                topProgress;

        float oldBottom =
                bottomProgress;

        float oldDensity =
                visualDensity;

        super.readNbt(nbt, lookup);

        boolean incomingGateManaged =
                nbt.getBoolean("gateManaged");

        float incomingRequested =
                nbt.contains("requestedFlow")
                        ? clamp01(
                        nbt.getFloat(
                                "requestedFlow"
                        )
                )
                        : nbt.contains("targetFlow")
                        ? clamp01(
                        nbt.getFloat(
                                "targetFlow"
                        )
                )
                        : getFlow();

        float incomingDensity =
                nbt.contains("visualDensity")
                        ? clamp01(
                        nbt.getFloat(
                                "visualDensity"
                        )
                )
                        : getFlow();

        float incomingTop =
                nbt.contains("topProgress")
                        ? clamp01(
                        nbt.getFloat(
                                "topProgress"
                        )
                )
                        : getFlow() > EPS
                        ? 1.0f
                        : 0.0f;

        float incomingBottom =
                nbt.contains("bottomProgress")
                        ? clamp01(
                        nbt.getFloat(
                                "bottomProgress"
                        )
                )
                        : 0.0f;

        if (world != null && world.isClient) {
            if (!clientGateVisualInitialized) {
                previousTopProgress =
                        incomingTop;

                previousBottomProgress =
                        incomingBottom;

                previousVisualDensity =
                        incomingDensity;

                clientGateVisualInitialized = true;
            } else {
                previousTopProgress =
                        oldTop;

                previousBottomProgress =
                        oldBottom;

                previousVisualDensity =
                        oldDensity;
            }
        }

        gateManaged =
                incomingGateManaged;

        requestedFlow =
                incomingRequested;

        visualDensity =
                incomingDensity;

        topProgress =
                incomingTop;

        bottomProgress =
                incomingBottom;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(
            RegistryWrapper.WrapperLookup lookup
    ) {
        return createNbt(lookup);
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
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

    private static float clamp01(float value) {
        return Math.max(
                0.0f,
                Math.min(
                        1.0f,
                        value
                )
        );
    }

    private static float lerp(
            float from,
            float to,
            float amount
    ) {
        return from
                + (to - from)
                * amount;
    }
}
