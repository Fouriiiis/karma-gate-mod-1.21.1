package dev.fouriis.karmagate.hose;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class FuelHoseManager extends PersistentState {
    private static final String DATA_NAME = KarmaGateMod.MOD_ID + "_fuel_hoses";

    private final Map<String, FuelHoseData> hoses = new HashMap<>();

    public void addHose(FuelHoseData hose) {
        hoses.put(hose.id(), hose);
        markDirty();
    }

    public Optional<FuelHoseData> getHose(String id) {
        return Optional.ofNullable(hoses.get(id));
    }

    public Collection<FuelHoseData> getAllHoses() {
        return hoses.values();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList hoseList = new NbtList();
        for (FuelHoseData hose : hoses.values()) {
            hoseList.add(hose.toNbt(registryLookup));
        }
        nbt.put("hoses", hoseList);
        return nbt;
    }

    public static FuelHoseManager createFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        FuelHoseManager manager = new FuelHoseManager();
        NbtList hoseList = nbt.getList("hoses", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < hoseList.size(); i++) {
            FuelHoseData hose = FuelHoseData.fromNbt(hoseList.getCompound(i), registryLookup);
            manager.hoses.put(hose.id(), hose);
        }
        return manager;
    }

    private static final Type<FuelHoseManager> TYPE = new Type<>(
            FuelHoseManager::new,
            FuelHoseManager::createFromNbt,
            null
    );

    public static FuelHoseManager get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("Overworld not available");
        }
        PersistentStateManager stateManager = overworld.getPersistentStateManager();
        return stateManager.getOrCreate(TYPE, DATA_NAME);
    }
}