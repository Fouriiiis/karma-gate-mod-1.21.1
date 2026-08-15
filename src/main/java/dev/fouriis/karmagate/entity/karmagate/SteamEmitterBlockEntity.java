// dev/fouriis/karmagate/entity/SteamEmitterBlockEntity.java
package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.sound.ModSounds;
import dev.fouriis.karmagate.block.karmagate.SteamEmitterBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Random;

public class SteamEmitterBlockEntity extends BlockEntity {
    private boolean enabled = false;
    private final Random rng = new Random();
    private float clientSteamPressure = 0.0f;

    public SteamEmitterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STEAM_EMITTER_BLOCK_ENTITY, pos, state);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        System.out.println("SteamEmitterBlockEntity: setEnabled " + enabled);
        if (this.enabled != enabled) {
            this.enabled = enabled;
            markDirty();
            if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    public static void tick(World world, BlockPos pos, BlockState state, SteamEmitterBlockEntity be) {
        if (!world.isClient) {
            return;
        }

        // Rain World updates at 40 Hz, so run two gate updates for every
        // Minecraft 20 Hz game tick.
        for (int step = 0; step < 2; step++) {
            if (state.get(SteamEmitterBlock.ENABLED)) {
                be.clientSteamPressure = Math.min(1.0f, be.clientSteamPressure + 0.025f);
            } else {
                be.clientSteamPressure = Math.max(0.0f, be.clientSteamPressure - 0.025f);
            }

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
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        enabled = nbt.getBoolean("enabled");
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = super.toInitialChunkDataNbt(lookup);
        nbt.putBoolean("enabled", enabled);
        return nbt;
    }
}
