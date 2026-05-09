package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.room.RoomData;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Network payload for syncing all rooms to clients.
 * Sent when a player joins or when rooms are modified.
 */
public record RoomSyncPayload(List<RoomEntry> rooms) implements CustomPayload {

    public static final CustomPayload.Id<RoomSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "room_sync"));

    public static final PacketCodec<RegistryByteBuf, RoomSyncPayload> CODEC = PacketCodec.tuple(
        RoomEntry.LIST_CODEC, RoomSyncPayload::rooms,
        RoomSyncPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * Creates a sync payload from a collection of room data.
     */
    public static RoomSyncPayload fromRooms(Iterable<RoomData> roomData) {
        List<RoomEntry> entries = new ArrayList<>();
        for (RoomData room : roomData) {
            entries.add(new RoomEntry(
                room.name(),
                room.corner1().getX(), room.corner1().getY(), room.corner1().getZ(),
                room.corner2().getX(), room.corner2().getY(), room.corner2().getZ()
            ));
        }
        return new RoomSyncPayload(entries);
    }

    /**
     * A single room entry for network transmission.
     */
    public record RoomEntry(String name, int x1, int y1, int z1, int x2, int y2, int z2) {

        public static final PacketCodec<RegistryByteBuf, RoomEntry> CODEC = new PacketCodec<>() {
            @Override
            public RoomEntry decode(RegistryByteBuf buf) {
                String name = PacketCodecs.STRING.decode(buf);
                int x1 = buf.readInt();
                int y1 = buf.readInt();
                int z1 = buf.readInt();
                int x2 = buf.readInt();
                int y2 = buf.readInt();
                int z2 = buf.readInt();
                return new RoomEntry(name, x1, y1, z1, x2, y2, z2);
            }

            @Override
            public void encode(RegistryByteBuf buf, RoomEntry entry) {
                PacketCodecs.STRING.encode(buf, entry.name());
                buf.writeInt(entry.x1());
                buf.writeInt(entry.y1());
                buf.writeInt(entry.z1());
                buf.writeInt(entry.x2());
                buf.writeInt(entry.y2());
                buf.writeInt(entry.z2());
            }
        };

        public static final PacketCodec<RegistryByteBuf, List<RoomEntry>> LIST_CODEC =
            CODEC.collect(PacketCodecs.toList());
    }
}
