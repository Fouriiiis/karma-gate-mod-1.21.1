package dev.fouriis.karmagate.room;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.entity.Entity;
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
 * Server-side manager for rooms.
 * Persists rooms with the world save data using Minecraft's PersistentState system.
 */
public class RoomManager extends PersistentState {
    private static final String DATA_NAME = KarmaGateMod.MOD_ID + "_rooms";

    private final Map<String, RoomData> rooms = new HashMap<>();
    private boolean geometryLoaded = false;

    public RoomManager() {
        super();
    }

    /**
     * Adds or updates a room.
     * @return true if this was a new room, false if it replaced an existing one
     */
    public boolean addRoom(RoomData room) {
        boolean isNew = !rooms.containsKey(room.name());
        rooms.put(room.name(), room);
        markDirty();
        return isNew;
    }

    public boolean addRoom(MinecraftServer server, RoomData room) {
        RoomData storedRoom = RoomGeometryStorage.saveRoom(server, room);
        boolean isNew = !rooms.containsKey(storedRoom.name());
        rooms.put(storedRoom.name(), storedRoom);
        markDirty();
        return isNew;
    }

    /**
     * Removes a room by name.
     * @return the removed room, or empty if not found
     */
    public Optional<RoomData> removeRoom(String name) {
        RoomData removed = rooms.remove(name);
        if (removed != null) {
            markDirty();
        }
        return Optional.ofNullable(removed);
    }

    public Optional<RoomData> removeRoom(MinecraftServer server, String name) {
        Optional<RoomData> removed = removeRoom(name);
        removed.ifPresent(room -> RoomGeometryStorage.deleteRoom(server, room.name()));
        return removed;
    }

    /**
     * Gets all rooms.
     */
    public Collection<RoomData> getAllRooms() {
        return rooms.values();
    }

    /**
     * Gets the room containing the given block position, if any.
     */
    public Optional<RoomData> getRoomAt(BlockPos pos) {
        for (RoomData room : rooms.values()) {
            if (room.contains(pos)) {
                return Optional.of(room);
            }
        }
        return Optional.empty();
    }

    /**
     * Gets the room containing the given entity, if any.
     */
    public Optional<RoomData> getRoomForEntity(Entity entity) {
        return getRoomAt(entity.getBlockPos());
    }

    /**
     * Gets all room names.
     */
    public Collection<String> getRoomNames() {
        return rooms.keySet();
    }

    /**
     * Gets the number of rooms.
     */
    public int getRoomCount() {
        return rooms.size();
    }

    /**
     * Checks if a room exists.
     */
    public boolean hasRoom(String name) {
        return rooms.containsKey(name);
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList roomList = new NbtList();
        for (RoomData room : rooms.values()) {
            NbtCompound roomNbt = new NbtCompound();
            roomNbt.putString("name", room.name());
            roomNbt.putInt("x1", room.corner1().getX());
            roomNbt.putInt("y1", room.corner1().getY());
            roomNbt.putInt("z1", room.corner1().getZ());
            roomNbt.putInt("x2", room.corner2().getX());
            roomNbt.putInt("y2", room.corner2().getY());
            roomNbt.putInt("z2", room.corner2().getZ());
            roomNbt.putString("dangerType", room.dangerType().name());
            roomList.add(roomNbt);
        }
        nbt.put("rooms", roomList);
        return nbt;
    }

    public static RoomManager createFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        RoomManager manager = new RoomManager();
        NbtList roomList = nbt.getList("rooms", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < roomList.size(); i++) {
            NbtCompound roomNbt = roomList.getCompound(i);
            String name = roomNbt.getString("name");
            int x1 = roomNbt.getInt("x1");
            int y1 = roomNbt.getInt("y1");
            int z1 = roomNbt.getInt("z1");
            int x2 = roomNbt.getInt("x2");
            int y2 = roomNbt.getInt("y2");
            int z2 = roomNbt.getInt("z2");
            DangerType dangerType = DangerType.fromSerialized(roomNbt.getString("dangerType"));
            manager.rooms.put(name, RoomData.of(name, x1, y1, z1, x2, y2, z2, dangerType));
        }
        return manager;
    }

    private static final Type<RoomManager> TYPE = new Type<>(
        RoomManager::new,
        RoomManager::createFromNbt,
        null
    );

    /**
     * Gets the RoomManager for a server world.
     * Uses the Overworld's persistent state to ensure rooms are shared across dimensions.
     */
    public static RoomManager get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("Overworld not available");
        }
        PersistentStateManager stateManager = overworld.getPersistentStateManager();
        RoomManager manager = stateManager.getOrCreate(TYPE, DATA_NAME);
        manager.ensureGeometryLoaded(server);
        return manager;
    }

    /**
     * Gets the RoomManager from a ServerWorld.
     */
    public static RoomManager get(ServerWorld world) {
        return get(world.getServer());
    }

    private void ensureGeometryLoaded(MinecraftServer server) {
        if (geometryLoaded) {
            return;
        }

        Map<String, RoomData> updatedRooms = new HashMap<>();
        for (RoomData room : rooms.values()) {
            updatedRooms.put(room.name(), RoomGeometryStorage.loadOrCreateRoom(server, room));
        }

        rooms.clear();
        rooms.putAll(updatedRooms);
        geometryLoaded = true;
    }
}
