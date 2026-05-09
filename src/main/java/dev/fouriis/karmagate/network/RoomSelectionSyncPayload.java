package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.room.RoomSelection;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Network payload for syncing a player's current room selection to the client.
 */
public record RoomSelectionSyncPayload(
    boolean hasCorner1,
    int x1,
    int y1,
    int z1,
    boolean hasCorner2,
    int x2,
    int y2,
    int z2
) implements CustomPayload {

    public static final CustomPayload.Id<RoomSelectionSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "room_selection_sync"));

    public static final PacketCodec<RegistryByteBuf, RoomSelectionSyncPayload> CODEC = new PacketCodec<>() {
        @Override
        public RoomSelectionSyncPayload decode(RegistryByteBuf buf) {
            boolean hasCorner1 = buf.readBoolean();
            int x1 = buf.readInt();
            int y1 = buf.readInt();
            int z1 = buf.readInt();
            boolean hasCorner2 = buf.readBoolean();
            int x2 = buf.readInt();
            int y2 = buf.readInt();
            int z2 = buf.readInt();
            return new RoomSelectionSyncPayload(hasCorner1, x1, y1, z1, hasCorner2, x2, y2, z2);
        }

        @Override
        public void encode(RegistryByteBuf buf, RoomSelectionSyncPayload payload) {
            buf.writeBoolean(payload.hasCorner1());
            buf.writeInt(payload.x1());
            buf.writeInt(payload.y1());
            buf.writeInt(payload.z1());
            buf.writeBoolean(payload.hasCorner2());
            buf.writeInt(payload.x2());
            buf.writeInt(payload.y2());
            buf.writeInt(payload.z2());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static RoomSelectionSyncPayload empty() {
        return new RoomSelectionSyncPayload(false, 0, 0, 0, false, 0, 0, 0);
    }

    public static RoomSelectionSyncPayload fromSelection(RoomSelection selection) {
        if (selection == null) {
            return empty();
        }
        boolean hasCorner1 = selection.corner1() != null;
        boolean hasCorner2 = selection.corner2() != null;
        int x1 = hasCorner1 ? selection.corner1().getX() : 0;
        int y1 = hasCorner1 ? selection.corner1().getY() : 0;
        int z1 = hasCorner1 ? selection.corner1().getZ() : 0;
        int x2 = hasCorner2 ? selection.corner2().getX() : 0;
        int y2 = hasCorner2 ? selection.corner2().getY() : 0;
        int z2 = hasCorner2 ? selection.corner2().getZ() : 0;
        return new RoomSelectionSyncPayload(hasCorner1, x1, y1, z1, hasCorner2, x2, y2, z2);
    }
}
