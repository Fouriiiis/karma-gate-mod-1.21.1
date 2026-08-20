package dev.fouriis.karmagate.entity.karmagate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;
// Client-only sound helpers (safe to reference inside client-guarded code paths)
import dev.fouriis.karmagate.sound.ModSounds;

public class KarmaGateBlockEntity extends BlockEntity implements GeoBlockEntity {
    private static final String ANIM_OPEN       = "open";
    private static final String ANIM_CLOSE      = "close";
    private static final String ANIM_OPEN_IDLE  = "open_idle";
    private static final String ANIM_CLOSE_IDLE = "close_idle";

    // ==========================================================================

    // Controller state
    boolean isController = false;   // package-private for controller convenience
    private UUID airlockId = null;

    // Controller logic is now here:
    private final KarmaGateController controller = new KarmaGateController(this);

    // ==========================================================================

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Persisted logical state used by collision/legacy callers.
    // Source parity: a door is traversable only when closedFac reaches 0.
    private boolean open = false;

    /*
     * Exact gate-door state supplied by KarmaGateController.
     *
     * closedFac is the actual source progress:
     *   0 = fully open
     *   1 = fully closed
     *
     * goalClosedFac is the current source goal, and gateDoorStalled is set
     * while RegionGateGraphics' clamp mechanics are preventing closedFac from
     * advancing.
     */
    private float gateClosedFac = 1.0f;
    private float gateGoalClosedFac = 1.0f;
    private boolean gateDoorStalled = false;

    /*
     * Controller resource state. Only the controller block receives this; side
     * door blocks keep the default BROKEN/1.0 values.
     */
    private KarmaGateController.GateType gateResourceType =
            KarmaGateController.GateType.BROKEN;
    private float gateResource = 1.0f;
    private boolean gateResourceChanging = false;
    private boolean gateRecharging = false;
    private float gateRechargeProgress = 0.0f;

    // Client interpolation endpoint for source closedFac.
    private float previousClientGateClosedFac = 1.0f;

    // Client-only: track first pose-after-NBT
    private boolean clientInitialized = false;

    public KarmaGateBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.KARMA_GATE_BLOCK_ENTITY, pos, state);
    }

    public KarmaGateBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /* ===================== Public server API ===================== */

    public void toggle() {
        if (world == null || world.isClient) return;
        if (open) close(); else open();
    }

    public void open() {
        setOpen(true);
    }

    public void close() {
        setOpen(false);
    }

    /**
     * Legacy/manual state setter.
     *
     * <p>Gate-controller operation uses setGateDoorState instead. Manual calls
     * snap the source state directly to its requested endpoint.</p>
     */
    public void setOpen(boolean value) {
        if (world == null || world.isClient) return;

        float target = value ? 0.0f : 1.0f;
        setGateDoorState(target, target, false);
    }

    /**
     * Exact door state from KarmaGateController.
     *
     * <p>This method deliberately does not run its own timer. The controller's
     * closedFac contains the Rain World 40 Hz movement and clamp stalls, while
     * the GeckoLib open/close animation is allowed to play continuously once
     * triggered.</p>
     */
    public void setGateDoorState(
            float closedFac,
            float goalClosedFac,
            boolean stalled
    ) {
        if (world != null && world.isClient) return;

        float nextClosed = clamp01(closedFac);
        float nextGoal = clamp01(goalClosedFac);

        boolean goalChanged =
                Math.abs(nextGoal - gateGoalClosedFac) > 1.0e-6f;

        boolean nextOpen =
                nextClosed <= 1.0e-6f;

        boolean changed =
                Math.abs(nextClosed - gateClosedFac) > 1.0e-6f
                        || goalChanged
                        || stalled != gateDoorStalled
                        || nextOpen != open;

        gateClosedFac = nextClosed;
        gateGoalClosedFac = nextGoal;
        gateDoorStalled = stalled;
        open = nextOpen;

        if (!changed) return;

        markDirtySync();

        /*
         * Trigger the normal GeckoLib animation once when the goal changes.
         * The animation then plays continuously at normal speed even if the
         * logical C# door timing is temporarily stalled by clamp mechanics.
         */
        if (goalChanged) {
            this.triggerAnim(
                    "controller",
                    nextGoal < 0.5f
                            ? "open"
                            : "close"
            );
        }
    }

    /**
     * Resource state used by the water-level / electric-battery visuals.
     */
    public void setGateResourceState(
            KarmaGateController.GateType type,
            float resource,
            boolean changing,
            boolean recharging,
            float rechargeProgress
    ) {
        if (world != null && world.isClient) return;

        KarmaGateController.GateType nextType =
                type == null
                        ? KarmaGateController.GateType.BROKEN
                        : type;

        float nextResource = clamp01(resource);
        float nextRecharge = clamp01(rechargeProgress);

        boolean changed =
                gateResourceType != nextType
                        || Math.abs(nextResource - gateResource) > 1.0e-6f
                        || gateResourceChanging != changing
                        || gateRecharging != recharging
                        || Math.abs(nextRecharge - gateRechargeProgress) > 1.0e-6f;

        gateResourceType = nextType;
        gateResource = nextResource;
        gateResourceChanging = changing;
        gateRecharging = recharging;
        gateRechargeProgress = nextRecharge;

        if (changed) {
            markDirtySync();
        }
    }

    /** Source-compatible collision state: true only at closedFac == 0. */
    public boolean isOpen() {
        return open;
    }

    public float getGateClosedFac() {
        return gateClosedFac;
    }

    public float getGateGoalClosedFac() {
        return gateGoalClosedFac;
    }

    public boolean isGateDoorStalled() {
        return gateDoorStalled;
    }

    public float getInterpolatedGateClosedFac(float tickDelta) {
        if (world == null || !world.isClient) {
            return gateClosedFac;
        }

        return previousClientGateClosedFac
                + (gateClosedFac - previousClientGateClosedFac)
                * clamp01(tickDelta);
    }

    public KarmaGateController.GateType getGateResourceType() {
        return gateResourceType;
    }

    public float getGateResource() {
        return gateResource;
    }

    public boolean isGateResourceChanging() {
        return gateResourceChanging;
    }

    public boolean isGateRecharging() {
        return gateRecharging;
    }

    public float getGateRechargeProgress() {
        return gateRechargeProgress;
    }

    /**
     * Raw Rain World battery-meter width before renderer scaling.
     *
     * <p>The meter sprite is centered, so a renderer should position its two
     * ends at +/- width/2 around the meter centre. The source subtracts up to
     * five pixels of jitter while batteryChanging.</p>
     */
    public float getGateBatteryMeterScaleX() {
        if (gateResourceType != KarmaGateController.GateType.ELECTRIC) {
            return 0.0f;
        }

        float jitter =
                gateResourceChanging
                        ? (float) Math.random() * 5.0f
                        : 0.0f;

        return Math.max(
                0.0f,
                420.0f * gateResource - jitter
        );
    }

    /* ===================== Controller Binding ===================== */

    /** OP action: make this a controller and bind nearest two non-controller gates. */
    public int configureAsControllerAndBindNearest(int radius) {
        if (world == null || world.isClient) return 0;

        isController = true;
        if (airlockId == null) airlockId = UUID.randomUUID();

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos origin = this.pos;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = origin.add(dx, dy, dz);
                    BlockEntity be = world.getBlockEntity(p);
                    if (be instanceof KarmaGateBlockEntity g && !g.isController) {
                        candidates.add(p);
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        int bound = Math.min(2, candidates.size());
        BlockPos gate1 = bound >= 1 ? candidates.get(0) : null;
        BlockPos gate2 = bound >= 2 ? candidates.get(1) : null;

        // Open side gates on bind (optional but keeps flow predictable)
        if (gate1 != null) {
            BlockEntity be1 = world.getBlockEntity(gate1);
            if (be1 instanceof KarmaGateBlockEntity g1) {
                g1.setGateDoorState(0.0f, 0.0f, false);
            }
        }
        if (gate2 != null) {
            BlockEntity be2 = world.getBlockEntity(gate2);
            if (be2 instanceof KarmaGateBlockEntity g2) {
                g2.setGateDoorState(0.0f, 0.0f, false);
            }
        }

        controller.setGates(gate1, gate2);
        // Bind components first: their combination determines whether this is
        // an electric gate, a water gate, or an incomplete/broken assembly.
        final int lightRadius = Math.max(15, radius); // reuse radius (or expand a bit) for convenience
        controller.bindLightsAndEffects(world, this.pos, this.getCachedState(), lightRadius);
        controller.resetOnBind();

        KarmaGateMod.LOGGER.info("Controller {} @{} bound {} gate(s): gate1={}, gate2={}",
                airlockId, origin, bound, gate1, gate2);
        markDirtySync();
        return bound;
    }

    /* ===================== GeckoLib ===================== */

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        AnimationController<KarmaGateBlockEntity> controller =
            new AnimationController<>(this, "controller", 0, this::predicate);

        controller
            .triggerableAnim("open",
                RawAnimation.begin()
                    .then(ANIM_OPEN, Animation.LoopType.PLAY_ONCE)
                    .then(ANIM_OPEN_IDLE, Animation.LoopType.LOOP))
            .triggerableAnim("close",
                RawAnimation.begin()
                    .then(ANIM_CLOSE, Animation.LoopType.PLAY_ONCE)
                    .then(ANIM_CLOSE_IDLE, Animation.LoopType.LOOP));

        // Dispatch custom timeline events from animations (GeckoLib 'timeline' keyframes)
        controller.setCustomInstructionKeyframeHandler(evt -> {
            // Client-only safety: only play sounds on the client
            if (this.world == null || !this.world.isClient) return;

            try {
                // GeckoLib passes a list of instruction strings; use reflection for API stability
                var m = evt.getClass().getMethod("instructions");
                Object o = m.invoke(evt);
                if (o instanceof java.util.List<?> list) {
                    for (Object v : list) if (v instanceof String s) dispatchTimelineEvent(s);
                }
            } catch (Throwable ignored) {
                try {
                    var m2 = evt.getClass().getMethod("getKeyframeData");
                    Object o2 = m2.invoke(evt);
                    if (o2 instanceof String s) dispatchTimelineEvent(s);
                } catch (Throwable alsoIgnored) {
                    // no-op: not critical if no events
                }
            }
        });

        // Handle sound keyframes from animation JSON (sound_effects)
        controller.setSoundKeyframeHandler(evt -> {
            if (this.world == null || !this.world.isClient) return;
            try {
                var data = evt.getKeyframeData();
                if (data == null) return;
                String soundStr = null;
                float volume = 1.0f;
                float pitch = 1.0f;

                // Access via reflection to be resilient across GeckoLib versions
                try {
                    Object sObj = data.getClass().getMethod("getSound").invoke(data);
                    if (sObj instanceof String s) soundStr = s;
                } catch (Throwable ignored) {}
                try {
                    Object vObj = data.getClass().getMethod("getVolume").invoke(data);
                    if (vObj instanceof Number n) volume = n.floatValue();
                } catch (Throwable ignored) {}
                try {
                    Object pObj = data.getClass().getMethod("getPitch").invoke(data);
                    if (pObj instanceof Number n) pitch = n.floatValue();
                } catch (Throwable ignored) {}

                if (soundStr == null || soundStr.isEmpty()) return;
                Identifier id = Identifier.tryParse(soundStr);
                if (id == null) return;
                // Route to client audio implementation (lets us centralize behavior/volume/category)
                ModSounds.onSoundKeyframe(this.pos, id, volume, pitch);
                KarmaGateMod.LOGGER.info("[GateAudio] Played keyframe sound '{}' v={} p={} at {}", soundStr, volume, pitch, this.pos);
            } catch (Throwable t) {
                KarmaGateMod.LOGGER.warn("[GateAudio] Failed to handle sound keyframe: {}", t.toString());
            }
        });

        // Handle particle keyframes (optional: currently no-op, silences warnings)
        controller.setParticleKeyframeHandler(evt -> {
            // You can map evt.getKeyframeData().getParticle() to your particle system here if desired
            // For now, just acknowledge to avoid GeckoLib warnings
        });

        registrar.add(controller);
    }

    private PlayState predicate(AnimationState<KarmaGateBlockEntity> state) {
        if (world != null && world.isClient && !clientInitialized) {
            final AnimationController<KarmaGateBlockEntity> ctrl = state.getController();
            ctrl.forceAnimationReset();

            if (gateClosedFac <= 1.0e-6f) {
                ctrl.setAnimation(
                        RawAnimation.begin()
                                .then(
                                        ANIM_OPEN_IDLE,
                                        Animation.LoopType.LOOP
                                )
                );
            } else if (gateClosedFac >= 1.0f - 1.0e-6f) {
                ctrl.setAnimation(
                        RawAnimation.begin()
                                .then(
                                        ANIM_CLOSE_IDLE,
                                        Animation.LoopType.LOOP
                                )
                );
            } else {
                /*
                 * Mid-cycle chunk load: start the animation matching the
                 * current goal. Once triggered, the animation runs at its
                 * normal speed continuously; clamp stalls affect only the
                 * controller's logical/source timing.
                 */
                ctrl.setAnimation(
                        RawAnimation.begin()
                                .then(
                                        gateGoalClosedFac < 0.5f
                                                ? ANIM_OPEN
                                                : ANIM_CLOSE,
                                        Animation.LoopType.HOLD_ON_LAST_FRAME
                                )
                );
            }

            clientInitialized = true;
        }

        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    // Map timeline tokens to audio specs and play (client-side only)
    private void dispatchTimelineEvent(String token) {
        if (token == null || token.isEmpty()) return;
        if (this.world == null || !this.world.isClient) return;

        // Forward to client audio implementation
        ModSounds.onTimelineEvent(this.pos, token);
    }

    /* ===================== Tick (server) ===================== */

    /** Called every tick via BlockEntityTicker (from KarmaGateBlock#getTicker). */
    public void tick(World world, BlockPos pos, BlockState state, KarmaGateBlockEntity be) {
        if (world == null || world.isClient) return;

        if (isController) {
            // Delegate all airlock/cycle + light logic to the controller
            controller.tick(world, pos, state);
        }
    }

    /* ===================== Misc helpers ===================== */

    void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld sw) sw.getChunkManager().markForUpdate(pos);
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    /* ===================== NBT & Packets ===================== */

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putBoolean("open", open);
        nbt.putFloat("gateClosedFac", gateClosedFac);
        nbt.putFloat("gateGoalClosedFac", gateGoalClosedFac);
        nbt.putBoolean("gateDoorStalled", gateDoorStalled);

        nbt.putString("gateResourceType", gateResourceType.name());
        nbt.putFloat("gateResource", gateResource);
        nbt.putBoolean("gateResourceChanging", gateResourceChanging);
        nbt.putBoolean("gateRecharging", gateRecharging);
        nbt.putFloat("gateRechargeProgress", gateRechargeProgress);

        nbt.putBoolean("isController", isController);
        if (airlockId != null) nbt.putUuid("airlockId", airlockId);

        // Persist controller state if we are a controller
        if (isController) controller.writeNbt(nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);

        float legacyClosed =
                nbt.getBoolean("open")
                        ? 0.0f
                        : 1.0f;

        float incomingClosed =
                nbt.contains("gateClosedFac")
                        ? clamp01(nbt.getFloat("gateClosedFac"))
                        : legacyClosed;

        if (world != null && world.isClient) {
            previousClientGateClosedFac =
                    clientInitialized
                            ? gateClosedFac
                            : incomingClosed;
        }

        this.gateClosedFac = incomingClosed;
        this.gateGoalClosedFac = nbt.contains("gateGoalClosedFac")
                ? clamp01(nbt.getFloat("gateGoalClosedFac"))
                : incomingClosed;
        this.gateDoorStalled = nbt.getBoolean("gateDoorStalled");

        // Source ChangeDoorStatus treats any closedFac > 0 as closed.
        this.open = incomingClosed <= 1.0e-6f;

        this.gateResourceType = readGateType(
                nbt.getString("gateResourceType")
        );
        this.gateResource = nbt.contains("gateResource")
                ? clamp01(nbt.getFloat("gateResource"))
                : 1.0f;
        this.gateResourceChanging = nbt.getBoolean("gateResourceChanging");
        this.gateRecharging = nbt.getBoolean("gateRecharging");
        this.gateRechargeProgress = nbt.contains("gateRechargeProgress")
                ? clamp01(nbt.getFloat("gateRechargeProgress"))
                : 0.0f;

        this.isController = nbt.getBoolean("isController");
        this.airlockId = nbt.containsUuid("airlockId") ? nbt.getUuid("airlockId") : null;

        /*
         * Full saved NBT contains controllerDataVersion/gateType. The compact
         * client sync NBT below intentionally does not, so don't deserialize
         * the enormous persistent controller/clamp simulation on every visual
         * update packet.
         */
        boolean hasControllerPayload =
                nbt.contains("controllerDataVersion")
                        || nbt.contains("gateType")
                        || nbt.contains("startCounter");

        if (isController && hasControllerPayload) {
            controller.readNbt(nbt);
        }

        /*
         * Do not clear clientInitialized on every update packet. closedFac is
         * synchronized continuously now; resetting here would restart the
         * GeckoLib animation every server tick.
         *
         * A freshly constructed client BE already starts with
         * clientInitialized=false, so initial chunk loading is still posed
         * correctly by predicate().
         */
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        /*
         * BlockEntityUpdateS2CPacket.create(this) uses this observable-data
         * payload. Keep it intentionally small: the server's persisted
         * DoorMechanics/clamp state is not useful to clients and would
         * otherwise be resent every time closedFac changes.
         */
        NbtCompound nbt = new NbtCompound();

        nbt.putBoolean("open", open);
        nbt.putFloat("gateClosedFac", gateClosedFac);
        nbt.putFloat("gateGoalClosedFac", gateGoalClosedFac);
        nbt.putBoolean("gateDoorStalled", gateDoorStalled);

        nbt.putString("gateResourceType", gateResourceType.name());
        nbt.putFloat("gateResource", gateResource);
        nbt.putBoolean("gateResourceChanging", gateResourceChanging);
        nbt.putBoolean("gateRecharging", gateRecharging);
        nbt.putFloat("gateRechargeProgress", gateRechargeProgress);

        nbt.putBoolean("isController", isController);
        if (airlockId != null) {
            nbt.putUuid("airlockId", airlockId);
        }

        return nbt;
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    private static KarmaGateController.GateType readGateType(String value) {
        try {
            return KarmaGateController.GateType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return KarmaGateController.GateType.BROKEN;
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    /* ===================== Static utils used by controller ===================== */

    static boolean anyPlayerInSquare(World world, double cx, double cz, double halfSide) {
        if (world == null) return false;
        double minX = cx - halfSide, maxX = cx + halfSide;
        double minZ = cz - halfSide, maxZ = cz + halfSide;
        for (PlayerEntity p : world.getPlayers()) {
            double px = p.getX(), pz = p.getZ();
            if (px >= minX && px <= maxX && pz >= minZ && pz <= maxZ) return true;
        }
        return false;
    }

    public KarmaGateController getController() {
        return controller;
    }
}
