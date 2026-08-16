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

public class SteamEmitterBlockEntity extends BlockEntity {
    private boolean enabled = false;
    private float flow = 0.0f;
    private final Random rng = new Random();
    private float clientSteamPressure = 0.0f;

    public SteamEmitterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STEAM_EMITTER_BLOCK_ENTITY, pos, state);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        setFlow(enabled ? 1.0f : 0.0f);
    }

    public float getFlow() { return flow; }

    /** Fractional RegionGate electric-steam pressure in the range 0..1. */
    public void setFlow(float value) {
        if (world != null && world.isClient) return;
        float next = Math.max(0.0f, Math.min(1.0f, value));
        boolean nextEnabled = next > 1.0e-4f;
        if (Math.abs(next - flow) <= 1.0e-4f && enabled == nextEnabled) return;
        flow = next;
        enabled = nextEnabled;
        markDirtySync();
    }

    public static void tick(World world, BlockPos pos, BlockState state, SteamEmitterBlockEntity be) {
        if (!world.isClient) {
            return;
        }

        be.clientSteamPressure = be.flow;
        // Two emission attempts preserve Rain World's 40 Hz particle cadence.
        for (int step = 0; step < 2; step++) {
            float pressure = be.clientSteamPressure;
            if (pressure <= 0.0f) {
                continue;
            }

            // RegionGateSteamDemo: random^1.5 < electricSteam * 2.
            if (Math.pow(be.rng.nextDouble(), 1.5) < pressure * 2.0f) {
                double centerX = pos.getX() + 0.5;
                double centerZ = pos.getZ() + 0.5;
                double px = centerX + (be.rng.nextDouble() * 2.0 - 1.0) * 0.75;
                double py = pos.getY() + 1.5 + (be.rng.nextDouble() * 2.0 - 1.0) * 0.5;
                double pz = centerZ + (be.rng.nextDouble() * 2.0 - 1.0) * 0.75;
                float puffIntensity = (float) Math.pow(pressure, 0.75);

                // X/Z encode the offset back to the vent center; Y is intensity.
                world.addParticle(
                        ModParticles.STEAM,
                        px, py, pz,
                        centerX - px, puffIntensity, centerZ - pz
                );
            }
        }

        if (be.clientSteamPressure > 0.5f) {
            ModSounds.onSteamBurst(pos, be.clientSteamPressure, ModSounds.STEAM_LOOP_2_EVENT);
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putBoolean("enabled", enabled);
        nbt.putFloat("flow", flow);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        if (nbt.contains("flow")) flow = Math.max(0.0f, Math.min(1.0f, nbt.getFloat("flow")));
        else flow = nbt.getBoolean("enabled") ? 1.0f : 0.0f;
        enabled = flow > 1.0e-4f;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = super.toInitialChunkDataNbt(lookup);
        nbt.putBoolean("enabled", enabled);
        nbt.putFloat("flow", flow);
        return nbt;
    }

    private void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld serverWorld) serverWorld.getChunkManager().markForUpdate(pos);
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}
