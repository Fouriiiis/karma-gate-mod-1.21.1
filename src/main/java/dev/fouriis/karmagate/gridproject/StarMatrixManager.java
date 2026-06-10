package dev.fouriis.karmagate.gridproject;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persistent server-side manager for StarMatrices.
 */
public class StarMatrixManager extends PersistentState {
    private static final String DATA_NAME = KarmaGateMod.MOD_ID + "_star_matrices";

    private final Map<String, StarMatrixData> matrices = new HashMap<>();

    public boolean addMatrix(StarMatrixData matrix) {
        boolean isNew = !matrices.containsKey(matrix.name());
        matrices.put(matrix.name(), matrix);
        markDirty();
        return isNew;
    }

    public Optional<StarMatrixData> removeMatrix(String name) {
        StarMatrixData removed = matrices.remove(name);
        if (removed != null) {
            markDirty();
        }
        return Optional.ofNullable(removed);
    }

    public int removeMatricesForZone(String zoneName) {
        int before = matrices.size();
        if (matrices.entrySet().removeIf(entry -> entry.getValue().zoneName().equals(zoneName))) {
            markDirty();
        }
        return before - matrices.size();
    }

    public Optional<StarMatrixData> getMatrix(String name) {
        return Optional.ofNullable(matrices.get(name));
    }

    public Collection<StarMatrixData> getAllMatrices() {
        return matrices.values();
    }

    public Collection<String> getMatrixNames() {
        return matrices.keySet();
    }

    public int getMatrixCount() {
        return matrices.size();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList list = new NbtList();
        for (StarMatrixData matrix : matrices.values()) {
            NbtCompound entry = new NbtCompound();
            entry.putString("name", matrix.name());
            entry.putString("zoneName", matrix.zoneName());
            entry.putInt("x", matrix.position().getX());
            entry.putInt("y", matrix.position().getY());
            entry.putInt("z", matrix.position().getZ());
            list.add(entry);
        }
        nbt.put("starMatrices", list);
        return nbt;
    }

    public static StarMatrixManager createFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        StarMatrixManager manager = new StarMatrixManager();
        NbtList list = nbt.getList("starMatrices", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i);
            String name = entry.getString("name");
            String zoneName = entry.getString("zoneName");
            int x = entry.getInt("x");
            int y = entry.getInt("y");
            int z = entry.getInt("z");
            manager.matrices.put(name, StarMatrixData.of(name, zoneName, x, y, z));
        }
        return manager;
    }

    private static final Type<StarMatrixManager> TYPE = new Type<>(
        StarMatrixManager::new,
        StarMatrixManager::createFromNbt,
        null
    );

    public static StarMatrixManager get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("Overworld not available");
        }
        PersistentStateManager stateManager = overworld.getPersistentStateManager();
        return stateManager.getOrCreate(TYPE, DATA_NAME);
    }
}
