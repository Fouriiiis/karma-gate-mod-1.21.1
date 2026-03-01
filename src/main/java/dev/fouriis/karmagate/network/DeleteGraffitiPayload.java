package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record DeleteGraffitiPayload(int entityId) implements CustomPayload {

    public static final CustomPayload.Id<DeleteGraffitiPayload> ID =
        new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "delete_graffiti"));

    public static final PacketCodec<RegistryByteBuf, DeleteGraffitiPayload> CODEC = new PacketCodec<>() {
        @Override
        public DeleteGraffitiPayload decode(RegistryByteBuf buf) {
            return new DeleteGraffitiPayload(buf.readInt());
        }

        @Override
        public void encode(RegistryByteBuf buf, DeleteGraffitiPayload payload) {
            buf.writeInt(payload.entityId());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

