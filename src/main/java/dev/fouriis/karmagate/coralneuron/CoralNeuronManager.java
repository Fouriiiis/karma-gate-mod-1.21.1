package dev.fouriis.karmagate.coralneuron;

import dev.fouriis.karmagate.CoralNeuronEntity;
import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.util.*;

/**
 * Server-side manager for named CoralNeuron entities.
 * Persists entity references with the world save data using Minecraft's PersistentState system.
 */
public class CoralNeuronManager extends PersistentState {
    private static final String DATA_NAME = KarmaGateMod.MOD_ID + "_coral_neurons";

    private final Map<String, CoralNeuronData> neurons = new HashMap<>();

    public CoralNeuronManager() {
        super();
    }

    /**
     * Registers a CoralNeuron entity with a name.
     * @return true if this was a new entry, false if it replaced an existing one
     */
    public boolean addNeuron(CoralNeuronData data) {
        boolean isNew = !neurons.containsKey(data.name());
        neurons.put(data.name(), data);
        markDirty();
        return isNew;
    }

    /**
     * Removes a CoralNeuron entry by name.
     * @return the removed data, or empty if not found
     */
    public Optional<CoralNeuronData> removeNeuron(String name) {
        CoralNeuronData removed = neurons.remove(name);
        if (removed != null) {
            markDirty();
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Gets a neuron entry by name.
     */
    public Optional<CoralNeuronData> getNeuron(String name) {
        return Optional.ofNullable(neurons.get(name));
    }

    /**
     * Gets all neuron entries.
     */
    public Collection<CoralNeuronData> getAllNeurons() {
        return neurons.values();
    }

    /**
     * Gets all neuron names.
     */
    public Collection<String> getNeuronNames() {
        return neurons.keySet();
    }

    /**
     * Checks if a neuron exists.
     */
    public boolean hasNeuron(String name) {
        return neurons.containsKey(name);
    }

    /**
     * Gets the number of neurons.
     */
    public int getNeuronCount() {
        return neurons.size();
    }

    /**
     * Finds the actual CoralNeuronEntity in the world by UUID.
     * Searches all loaded worlds.
     */
    public Optional<CoralNeuronEntity> findEntity(MinecraftServer server, UUID uuid) {
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(uuid);
            if (entity instanceof CoralNeuronEntity neuron) {
                return Optional.of(neuron);
            }
        }
        return Optional.empty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList neuronList = new NbtList();
        for (CoralNeuronData data : neurons.values()) {
            NbtCompound neuronNbt = new NbtCompound();
            neuronNbt.putString("name", data.name());
            neuronNbt.putUuid("uuid", data.entityUuid());
            neuronNbt.putDouble("ax", data.anchorA().x);
            neuronNbt.putDouble("ay", data.anchorA().y);
            neuronNbt.putDouble("az", data.anchorA().z);
            neuronNbt.putDouble("bx", data.anchorB().x);
            neuronNbt.putDouble("by", data.anchorB().y);
            neuronNbt.putDouble("bz", data.anchorB().z);
            neuronNbt.putBoolean("anchoredA", data.anchoredA());
            neuronNbt.putBoolean("anchoredB", data.anchoredB());
            neuronList.add(neuronNbt);
        }
        nbt.put("neurons", neuronList);
        return nbt;
    }

    public static CoralNeuronManager createFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        CoralNeuronManager manager = new CoralNeuronManager();
        NbtList neuronList = nbt.getList("neurons", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < neuronList.size(); i++) {
            NbtCompound neuronNbt = neuronList.getCompound(i);
            String name = neuronNbt.getString("name");
            UUID uuid = neuronNbt.getUuid("uuid");
            double ax = neuronNbt.getDouble("ax");
            double ay = neuronNbt.getDouble("ay");
            double az = neuronNbt.getDouble("az");
            double bx = neuronNbt.getDouble("bx");
            double by = neuronNbt.getDouble("by");
            double bz = neuronNbt.getDouble("bz");
            boolean anchoredA = neuronNbt.getBoolean("anchoredA");
            boolean anchoredB = neuronNbt.getBoolean("anchoredB");
            manager.neurons.put(name, CoralNeuronData.of(name, uuid, ax, ay, az, bx, by, bz, anchoredA, anchoredB));
        }
        return manager;
    }

    private static Type<CoralNeuronManager> TYPE = new Type<>(
            CoralNeuronManager::new,
            CoralNeuronManager::createFromNbt,
            null // No data fixer needed
    );

    /**
     * Gets the CoralNeuronManager for a server world.
     * Uses the Overworld's persistent state to ensure data is shared across dimensions.
     */
    public static CoralNeuronManager get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("Overworld not available");
        }
        PersistentStateManager stateManager = overworld.getPersistentStateManager();
        return stateManager.getOrCreate(TYPE, DATA_NAME);
    }

    /**
     * Gets the CoralNeuronManager from a ServerWorld.
     */
    public static CoralNeuronManager get(ServerWorld world) {
        return get(world.getServer());
    }
}
