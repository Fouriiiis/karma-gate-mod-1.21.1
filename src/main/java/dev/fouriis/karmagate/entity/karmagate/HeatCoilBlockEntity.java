package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.particle.ModParticles;
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

public class HeatCoilBlockEntity extends BlockEntity {

    // server-authoritative heat [0..1]
    private float heat = 0f;

    // heater toggle (server)
    private boolean enabled = false;
    private boolean gateManaged = false;

    /*
     * Exact state supplied by KarmaGateController while gateManaged.
     *
     * heat remains the source "current heat" so existing renderers/getHeat()
     * continue to work. The additional fields expose the rest of
     * RegionGateGraphics' heater state instead of recomputing it locally.
     */
    private float gateTargetHeat = 0.0f;
    private float gateLightAlpha = 0.0f;
    private float gateLightRadius = 0.0f;
    private float gateDistortionAlpha = 0.0f;
    private long gateSteamPuffSequence = 0L;
    private float gateSteamPuffIntensity = 0.0f;

    // all external/additional contributions for the *current* server tick
    private float pendingDelta = 0f;

    // rates per tick
    private static final float HEAT_RATE_ON     = 0.015f;  // tripled as you asked earlier
    private static final float PASSIVE_COOL_RATE= 0.0025f; // when not enabled
    private static final float EPS              = 0.0001f; // change threshold to sync

    public HeatCoilBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HEAT_COIL_BLOCK_ENTITY, pos, state);
    }

    /* ================= client-side visual flicker ================= */
    // A short-lived, client-only dip in perceived heat used to visually flicker when water hits.
    // None of these fields are networked or persisted.
    private long clientFlickerStartTick = 0L;
    private int clientFlickerDuration = 0;
    private float clientFlickerDip = 0f; // absolute dip amount from baseAtStart
    private float clientFlickerBaseAtStart = 0f; // base heat when pulse started
    private int clientFlickerHoldTicks = 0; // initial ticks to hold at min
    private long lastClientSteamEmissionTick = Long.MIN_VALUE;
    private boolean clientHeaterVisualInitialized;
    private float clientHeaterHeat;
    private float previousClientHeaterHeat;
    private float clientLightFlicker = 1.0f;
    private float clientLightColorFlicker = 1.0f;
    private long clientConsumedSteamPuffSequence = 0L;
    private boolean clientSteamSequenceInitialized;

    /**
     * Limits water-gate-style steam to one emission attempt per heater per tick,
     * matching RegionGateGraphics.Update rather than the render frame rate or
     * the number of individual water particles touching the coil.
     */
    public boolean beginClientSteamEmissionTick() {
        if (world == null || !world.isClient) return false;
        long now = world.getTime();
        if (lastClientSteamEmissionTick == now) return false;
        lastClientSteamEmissionTick = now;
        return true;
    }

    /**
     * Client-only: briefly reduce the visual heat by up to {@code peakDip} and ease it back over {@code durationTicks}.
     * Safe to call from client particle effects/audio handlers.
     */
    public void clientPulseCool(float peakDip, int durationTicks) {
        if (world == null || !world.isClient) return; // visual-only on client
        if (peakDip <= 0f || durationTicks <= 0) return;
        long now = world.getTime();
        float baseNow = clamp01(this.heat);

        // Choose a random lower visual heat target between 20%..60% of current base heat
        // Then convert to a dip amount, capped by peakDip
        float minFactor = 0.20f;
        float maxFactor = 0.60f;
        float factor = (float)(minFactor + (maxFactor - minFactor) * Math.random());
        float targetVisual = baseNow * factor;
        float desiredDip = Math.max(0f, baseNow - targetVisual);
        float dip = Math.min(peakDip, desiredDip);
        if (dip <= 0f) return;

        // If a pulse is active, deepen dip if this one is stronger and extend duration
        if (this.clientFlickerDuration > 0) {
            // Keep earliest start so we don't pop upwards; just extend remaining time
            long elapsed = Math.max(0L, now - this.clientFlickerStartTick);
            int remaining = Math.max(0, this.clientFlickerDuration - (int)elapsed);
            this.clientFlickerDuration = remaining + durationTicks;
            this.clientFlickerDip = Math.max(this.clientFlickerDip, dip);
            // Refresh hold for a snappier repeated hit (1-3 ticks)
            this.clientFlickerHoldTicks = 1 + (int)(Math.random() * 3);
        } else {
            this.clientFlickerStartTick = now;
            this.clientFlickerDuration = durationTicks;
            this.clientFlickerBaseAtStart = baseNow;
            this.clientFlickerDip = dip;
            this.clientFlickerHoldTicks = 1 + (int)(Math.random() * 3); // brief min hold
        }
    }

    /**
     * Heat used by client-side visuals (base server heat minus any active client flicker dip).
     */
    public float getVisualHeat() {
        float baseNow = this.heat;

        // Gate puffs already cool currentHeat in KarmaGateController using the
        // source stochastic formula. Do not apply the old client-only cooling
        // pulse a second time to gate-managed heaters.
        if (gateManaged) return baseNow;

        if (world == null || !world.isClient || clientFlickerDuration <= 0 || clientFlickerDip <= 0f) return baseNow;
        long now = world.getTime();
        int elapsed = (int)Math.max(0L, now - clientFlickerStartTick);
        if (elapsed >= clientFlickerDuration) {
            // reset when complete
            clientFlickerDuration = 0;
            clientFlickerDip = 0f;
            clientFlickerHoldTicks = 0;
            return baseNow;
        }

        // Hold at the minimum for the first few ticks for a snappy aggressive dip
        if (elapsed < clientFlickerHoldTicks) {
            float minVisual = clientFlickerBaseAtStart - clientFlickerDip;
            return clamp01(minVisual);
        }

        // Ease back quickly after the hold using an ease-out cubic with a subtle ripple
        float t = (elapsed - clientFlickerHoldTicks) / (float)Math.max(1, clientFlickerDuration - clientFlickerHoldTicks); // 0..1
        // easeOutCubic: 1 - (1 - t)^3
        float ease = 1f - (float)Math.pow(1f - t, 3.0);
        float ripple = 0.92f + 0.08f * (float)Math.sin(t * Math.PI * 2.5);
        float dipNow = clientFlickerDip * (1f - ease) * ripple;
        return clamp01(baseNow - dipNow);
    }

    /** Rain World's 40 Hz smoothed heat used by the heater mesh. */
    public float getInterpolatedHeaterHeat(float tickDelta) {
        if (!clientHeaterVisualInitialized) return getVisualHeat();
        return previousClientHeaterHeat
                + (clientHeaterHeat - previousClientHeaterHeat) * clamp01(tickDelta);
    }

    public float getClientLightFlicker() {
        return clientLightFlicker;
    }

    public float getClientLightColorFlicker() {
        return clientLightColorFlicker;
    }

    /* ================= public API (SERVER) ================= */

    /** Enqueue a heat contribution to be applied this tick (positive heats, negative cools). */
    public void addHeat(float delta) {
        if (world != null && world.isClient) return; // server-authoritative
        pendingDelta += delta;
    }

    /** Convenience: remove heat due to steam generation, etc. */
    public void drainHeat(float amount) {
        if (amount <= 0f) return;
        addHeat(-amount);
    }

    /** Toggle the built-in heater on/off. */
    public void setEnabled(boolean on) {
        if (world != null && world.isClient) return; // server only
        this.gateManaged = false;
        this.enabled = on;
        // no immediate sync needed; heat itself will sync when it changes
    }

    public boolean isEnabled() { return enabled; }

    /** Server-authoritative current heat (client reads the synced value). */
    public float getHeat() { return heat; }

    public boolean isGateManaged() { return gateManaged; }
    public float getGateTargetHeat() { return gateTargetHeat; }
    public float getGateLightAlpha() { return gateLightAlpha; }
    public float getGateLightRadius() { return gateLightRadius; }
    public float getGateDistortionAlpha() { return gateDistortionAlpha; }

    /**
     * Exact RegionGateGraphics state supplied by the 40 Hz gate simulation.
     *
     * <p>No heating/cooling is performed here. The controller is authoritative
     * for current heat, target heat, water-induced cooling, light flicker and
     * distortion intensity.</p>
     */
    public void setGateHeatState(
            float currentHeat,
            float targetHeat,
            float lightAlpha,
            float lightRadius,
            float distortionAlpha
    ) {
        setGateHeatState(
                currentHeat,
                targetHeat,
                lightAlpha,
                lightRadius,
                distortionAlpha,
                0,
                0.0f
        );
    }

    /**
     * Gate state plus the exact SteamSmoke emission events accumulated by the
     * controller's two Rain World updates this Minecraft tick.
     */
    public void setGateHeatState(
            float currentHeat,
            float targetHeat,
            float lightAlpha,
            float lightRadius,
            float distortionAlpha,
            int steamPuffCount,
            float steamPuffIntensity
    ) {
        if (world != null && world.isClient) return;

        float nextHeat = clamp01(currentHeat);
        float nextTarget = clamp01(targetHeat);
        float nextLightAlpha = clamp01(lightAlpha);
        float nextLightRadius = Math.max(0.0f, lightRadius);
        float nextDistortionAlpha = clamp01(distortionAlpha);
        int safeSteamPuffCount = Math.max(0, steamPuffCount);
        float nextSteamPuffIntensity = clamp01(steamPuffIntensity);

        boolean changed =
                !gateManaged
                || Math.abs(nextHeat - heat) > EPS
                || Math.abs(nextTarget - gateTargetHeat) > EPS
                || Math.abs(nextLightAlpha - gateLightAlpha) > EPS
                || Math.abs(nextLightRadius - gateLightRadius) > EPS
                || Math.abs(nextDistortionAlpha - gateDistortionAlpha) > EPS
                || safeSteamPuffCount > 0;

        gateManaged = true;
        enabled = false;
        pendingDelta = 0.0f;

        heat = nextHeat;
        gateTargetHeat = nextTarget;
        gateLightAlpha = nextLightAlpha;
        gateLightRadius = nextLightRadius;
        gateDistortionAlpha = nextDistortionAlpha;
        if (safeSteamPuffCount > 0) {
            gateSteamPuffSequence += safeSteamPuffCount;
            gateSteamPuffIntensity = nextSteamPuffIntensity;
        }

        if (changed) {
            markDirtySync();
        }
    }

    /**
     * Legacy single-value gate hook retained for old callers. New controller
     * code should use {@link #setGateHeatState(float, float, float, float, float)}.
     */
    public void setGateHeat(float value) {
        setGateHeatState(
                value,
                value,
                0.0f,
                0.0f,
                0.0f
        );
    }

    /**
     * Exact RegionGateGraphics heat supplied by the 40 Hz gate simulation.
     * This bypasses the older generic on/off heater rate while the coil is
     * bound to a gate.
     */
    /* ================= ticking ================= */

    /** Server tick: accumulate all contributions and apply once. */
    public void tick(World world, BlockPos pos, BlockState state) {
        if (world.isClient) {
            tickClientVisuals(world);
            return;
        }

        if (gateManaged) {
            pendingDelta = 0.0f;
            return;
        }

        float delta = pendingDelta;
        pendingDelta = 0f; // consume for this tick

        if (enabled) {
            delta += HEAT_RATE_ON;
        } else {
            // passive cooling only when we have heat to shed
            if (heat > 0f) delta -= PASSIVE_COOL_RATE;
        }

        float newHeat = clamp01(heat + delta);
        if (Math.abs(newHeat - heat) > EPS) {
            heat = newHeat;
            markDirtySync();
        }
    }

    private void tickClientVisuals(World world) {
        float target = clamp01(getVisualHeat());
        if (!clientHeaterVisualInitialized) {
            clientHeaterVisualInitialized = true;
            clientHeaterHeat = target;
            previousClientHeaterHeat = target;
            return;
        }

        if (gateManaged) {
            /*
             * readNbt() advances previousClientHeaterHeat -> clientHeaterHeat
             * whenever a new authoritative sample arrives. Do not overwrite
             * those endpoints in the client tick or render interpolation would
             * collapse to a constant value.
             */
            clientLightFlicker = 1.0f;
            clientLightColorFlicker = 1.0f;
            consumeGateSteamPuffs(world);
            return;
        }

        previousClientHeaterHeat = clientHeaterHeat;

        // Preserve the old standalone-heater visual behavior.
        for (int step = 0; step < 2; step++) {
            clientHeaterHeat += (target - clientHeaterHeat) * 0.7f;
            clientLightFlicker = 0.8f + 0.2f * world.random.nextFloat();
            clientLightColorFlicker = 0.7f + 0.3f * world.random.nextFloat();
        }
    }

    private void consumeGateSteamPuffs(World world) {
        if (!clientSteamSequenceInitialized) {
            clientConsumedSteamPuffSequence = gateSteamPuffSequence;
            clientSteamSequenceInitialized = true;
            return;
        }

        long pending = gateSteamPuffSequence - clientConsumedSteamPuffSequence;
        if (pending < 0L) {
            clientConsumedSteamPuffSequence = gateSteamPuffSequence;
            return;
        }

        int emitCount = (int) Math.min(16L, pending);
        for (int i = 0; i < emitCount; i++) {
            emitGateSteamPuff(world, gateSteamPuffIntensity);
        }
        clientConsumedSteamPuffSequence = gateSteamPuffSequence;
    }

    private void emitGateSteamPuff(World world, float intensity) {
        // RegionGateGraphics: heaterPositions[k] + random(-15..15, -10..10).
        // With 20 px/block this is +/-0.75 horizontally and +/-0.5 vertically.
        double centerX = pos.getX() + 0.5;
        double centerY = pos.getY() + 0.5;
        double centerZ = pos.getZ() + 0.5;
        double px = centerX + (world.random.nextDouble() * 2.0 - 1.0) * 0.75;
        double py = centerY + (world.random.nextDouble() * 2.0 - 1.0) * 0.5;
        double pz = centerZ + (world.random.nextDouble() * 2.0 - 1.0) * 0.75;

        world.addParticle(
                ModParticles.STEAM,
                px,
                py,
                pz,
                centerX - px,
                clamp01(intensity),
                centerZ - pz
        );
    }

    /* ================= sync & NBT ================= */

    private void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld sw) sw.getChunkManager().markForUpdate(pos);
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putFloat("heat", heat);
        nbt.putBoolean("enabled", enabled);
        nbt.putBoolean("gateManaged", gateManaged);
        nbt.putFloat("gateTargetHeat", gateTargetHeat);
        nbt.putFloat("gateLightAlpha", gateLightAlpha);
        nbt.putFloat("gateLightRadius", gateLightRadius);
        nbt.putFloat("gateDistortionAlpha", gateDistortionAlpha);
        nbt.putLong("gateSteamPuffSequence", gateSteamPuffSequence);
        nbt.putFloat("gateSteamPuffIntensity", gateSteamPuffIntensity);
        // pendingDelta is transient per tick and not persisted
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);

        float incomingHeat = clamp01(nbt.getFloat("heat"));

        if (world != null && world.isClient && clientHeaterVisualInitialized) {
            previousClientHeaterHeat = clientHeaterHeat;
            clientHeaterHeat = incomingHeat;
        }

        this.heat = incomingHeat;
        this.enabled = nbt.getBoolean("enabled");
        this.gateManaged = nbt.getBoolean("gateManaged");
        this.gateTargetHeat = nbt.contains("gateTargetHeat")
                ? clamp01(nbt.getFloat("gateTargetHeat"))
                : this.heat;
        this.gateLightAlpha = nbt.contains("gateLightAlpha")
                ? clamp01(nbt.getFloat("gateLightAlpha"))
                : 0.0f;
        this.gateLightRadius = nbt.contains("gateLightRadius")
                ? Math.max(0.0f, nbt.getFloat("gateLightRadius"))
                : 0.0f;
        this.gateDistortionAlpha = nbt.contains("gateDistortionAlpha")
                ? clamp01(nbt.getFloat("gateDistortionAlpha"))
                : 0.0f;
        this.gateSteamPuffSequence = nbt.contains("gateSteamPuffSequence")
                ? Math.max(0L, nbt.getLong("gateSteamPuffSequence"))
                : 0L;
        this.gateSteamPuffIntensity = nbt.contains("gateSteamPuffIntensity")
                ? clamp01(nbt.getFloat("gateSteamPuffIntensity"))
                : 0.0f;
        this.pendingDelta = 0f;
    }

    @Override public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) { return createNbt(lookup); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }

    /* ================= util ================= */

    private static float clamp01(float v) {
        return (v < 0f) ? 0f : (v > 1f ? 1f : v);
    }
}
