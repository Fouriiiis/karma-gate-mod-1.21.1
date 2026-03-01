package dev.fouriis.karmagate.gridproject;

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

/**
 * Server-side manager for projection zones.
 * Persists zones with the world save data using Minecraft's PersistentState system.
 */
public class ProjectionZoneManager extends PersistentState {
    private static final String DATA_NAME = KarmaGateMod.MOD_ID + "_projection_zones";
    
    private final Map<String, ProjectionZoneData> zones = new HashMap<>();
    
    public ProjectionZoneManager() {
        super();
    }
    
    /**
     * Adds or updates a projection zone.
     * @return true if this was a new zone, false if it replaced an existing one
     */
    public boolean addZone(ProjectionZoneData zone) {
        boolean isNew = !zones.containsKey(zone.name());
        zones.put(zone.name(), zone);
        markDirty();
        return isNew;
    }
    
    /**
     * Removes a projection zone by name.
     * @return the removed zone, or empty if not found
     */
    public Optional<ProjectionZoneData> removeZone(String name) {
        ProjectionZoneData removed = zones.remove(name);
        if (removed != null) {
            markDirty();
        }
        return Optional.ofNullable(removed);
    }
    
    /**
     * Gets a zone by name.
     */
    public Optional<ProjectionZoneData> getZone(String name) {
        return Optional.ofNullable(zones.get(name));
    }
    
    /**
     * Gets all zones.
     */
    public Collection<ProjectionZoneData> getAllZones() {
        return zones.values();
    }
    
    /**
     * Gets all zone names.
     */
    public Collection<String> getZoneNames() {
        return zones.keySet();
    }
    
    /**
     * Checks if a zone exists.
     */
    public boolean hasZone(String name) {
        return zones.containsKey(name);
    }
    
    /**
     * Gets the number of zones.
     */
    public int getZoneCount() {
        return zones.size();
    }
    
    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList zoneList = new NbtList();
        for (ProjectionZoneData zone : zones.values()) {
            NbtCompound zoneNbt = new NbtCompound();
            zoneNbt.putString("name", zone.name());
            zoneNbt.putInt("x1", zone.corner1().getX());
            zoneNbt.putInt("y1", zone.corner1().getY());
            zoneNbt.putInt("z1", zone.corner1().getZ());
            zoneNbt.putInt("x2", zone.corner2().getX());
            zoneNbt.putInt("y2", zone.corner2().getY());
            zoneNbt.putInt("z2", zone.corner2().getZ());
            zoneNbt.putInt("swarmerCount", zone.swarmerCount());
            zoneNbt.putBoolean("drawCircles", zone.drawCircles());
            zoneNbt.putBoolean("drawGrid", zone.drawGrid());
            zoneList.add(zoneNbt);
        }
        nbt.put("zones", zoneList);
        return nbt;
    }
    
    public static ProjectionZoneManager createFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        ProjectionZoneManager manager = new ProjectionZoneManager();
        NbtList zoneList = nbt.getList("zones", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < zoneList.size(); i++) {
            NbtCompound zoneNbt = zoneList.getCompound(i);
            String name = zoneNbt.getString("name");
            int x1 = zoneNbt.getInt("x1");
            int y1 = zoneNbt.getInt("y1");
            int z1 = zoneNbt.getInt("z1");
            int x2 = zoneNbt.getInt("x2");
            int y2 = zoneNbt.getInt("y2");
            int z2 = zoneNbt.getInt("z2");
            int swarmerCount = zoneNbt.contains("swarmerCount") ? zoneNbt.getInt("swarmerCount") : ProjectionZoneData.DEFAULT_SWARMER_COUNT;
            boolean drawCircles = !zoneNbt.contains("drawCircles") || zoneNbt.getBoolean("drawCircles");
            boolean drawGrid = !zoneNbt.contains("drawGrid") || zoneNbt.getBoolean("drawGrid");
            manager.zones.put(name, ProjectionZoneData.of(name, x1, y1, z1, x2, y2, z2, swarmerCount, drawCircles, drawGrid));
        }
        return manager;
    }
    
    private static Type<ProjectionZoneManager> TYPE = new Type<>(
        ProjectionZoneManager::new,
        ProjectionZoneManager::createFromNbt,
        null // No data fixer needed
    );
    
    /**
     * Gets the ProjectionZoneManager for a server world.
     * Uses the Overworld's persistent state to ensure zones are shared across dimensions.
     */
    public static ProjectionZoneManager get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("Overworld not available");
        }
        PersistentStateManager stateManager = overworld.getPersistentStateManager();
        return stateManager.getOrCreate(TYPE, DATA_NAME);
    }
    
    /**
     * Gets the ProjectionZoneManager from a ServerWorld.
     */
    public static ProjectionZoneManager get(ServerWorld world) {
        return get(world.getServer());
    }
}
