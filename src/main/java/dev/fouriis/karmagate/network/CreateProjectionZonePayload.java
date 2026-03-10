package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client-to-server payload for creating a ProjectionZone from the selection tool.
 * Carries the zone name, two corners, and optional swarmer/visual settings.
 */
public record CreateProjectionZonePayload(
        String name,
        int x1, int y1, int z1,
        int x2, int y2, int z2,
        int swarmerCount,
        boolean drawCircles,
        boolean drawGrid
) implements CustomPayload {

    public static final CustomPayload.Id<CreateProjectionZonePayload> ID =
            new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "create_projection_zone"));

    public static final PacketCodec<RegistryByteBuf, CreateProjectionZonePayload> CODEC = new PacketCodec<>() {
        @Override
        public CreateProjectionZonePayload decode(RegistryByteBuf buf) {
            String name = PacketCodecs.STRING.decode(buf);
            int x1 = buf.readInt();
            int y1 = buf.readInt();
            int z1 = buf.readInt();
            int x2 = buf.readInt();
            int y2 = buf.readInt();
            int z2 = buf.readInt();
            int swarmerCount = buf.readInt();
            boolean drawCircles = buf.readBoolean();
            boolean drawGrid = buf.readBoolean();
            return new CreateProjectionZonePayload(name, x1, y1, z1, x2, y2, z2, swarmerCount, drawCircles, drawGrid);
        }

        @Override
        public void encode(RegistryByteBuf buf, CreateProjectionZonePayload payload) {
            PacketCodecs.STRING.encode(buf, payload.name());
            buf.writeInt(payload.x1());
            buf.writeInt(payload.y1());
            buf.writeInt(payload.z1());
            buf.writeInt(payload.x2());
            buf.writeInt(payload.y2());
            buf.writeInt(payload.z2());
            buf.writeInt(payload.swarmerCount());
            buf.writeBoolean(payload.drawCircles());
            buf.writeBoolean(payload.drawGrid());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}


