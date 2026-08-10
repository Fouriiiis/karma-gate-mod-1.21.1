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
    private float intensity = 0.9f; // base
    private final Random rng = new Random();
    private int clientSteamCooldown = 0;

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
    // Use synced block state property so client sees the toggle without BE sync
    if (!state.get(SteamEmitterBlock.ENABLED)) return;

    // CLIENT: spawn steam & drive loop volume
    if (world.isClient) {
            // vary intensity a bit so the loop breathes
            float jitter = (float)(be.rng.nextGaussian() * 0.08);
            float inten = Math.max(0f, Math.min(1f, be.intensity + jitter));

            if (be.clientSteamCooldown-- <= 0) {
                be.clientSteamCooldown = 9 + be.rng.nextInt(8);
                // Spawn anywhere within the block's X/Z bounds (inclusive of lower edge, exclusive of upper), never outside
                double ox = pos.getX() + be.rng.nextDouble();
                double oy = pos.getY() + 0.6 + be.rng.nextDouble() * 0.2;
                double oz = pos.getZ() + be.rng.nextDouble();
                world.addParticle(ModParticles.STEAM, ox, oy, oz, 0, inten, 0); // vy carries intensity
            }

            // tell the audio controller to run/boost the emitter loop here
            ModSounds.onSteamBurst(pos, inten, ModSounds.STEAM_LOOP_2_EVENT);
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putBoolean("enabled", enabled);
        nbt.putFloat("intensity", intensity);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        enabled = nbt.getBoolean("enabled");
        intensity = nbt.contains("intensity") ? nbt.getFloat("intensity") : 0.6f;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = super.toInitialChunkDataNbt(lookup);
        nbt.putBoolean("enabled", enabled);
        nbt.putFloat("intensity", intensity);
        return nbt;
    }
}
