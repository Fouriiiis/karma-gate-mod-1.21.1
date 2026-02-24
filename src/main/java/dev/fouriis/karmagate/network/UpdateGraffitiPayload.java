package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record UpdateGraffitiPayload(
    int entityId,
    String texturePath,
    float[] cornerOpacity,
    float[] cornerMelt,
    float[] cornerH,
    float[] cornerV
) implements CustomPayload {

    public static final CustomPayload.Id<UpdateGraffitiPayload> ID =
        new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "update_graffiti"));

    public static final PacketCodec<RegistryByteBuf, UpdateGraffitiPayload> CODEC = new PacketCodec<>() {
        @Override
        public UpdateGraffitiPayload decode(RegistryByteBuf buf) {
            int entityId = buf.readInt();
            String texturePath = PacketCodecs.STRING.decode(buf);
            float[] opacity = new float[4];
            float[] melt = new float[4];
            float[] cornerH = new float[4];
            float[] cornerV = new float[4];
            for (int i = 0; i < 4; i++) {
                opacity[i] = buf.readFloat();
            }
            for (int i = 0; i < 4; i++) {
                melt[i] = buf.readFloat();
            }
            for (int i = 0; i < 4; i++) {
                cornerH[i] = buf.readFloat();
            }
            for (int i = 0; i < 4; i++) {
                cornerV[i] = buf.readFloat();
            }
            return new UpdateGraffitiPayload(entityId, texturePath, opacity, melt, cornerH, cornerV);
        }

        @Override
        public void encode(RegistryByteBuf buf, UpdateGraffitiPayload payload) {
            buf.writeInt(payload.entityId());
            PacketCodecs.STRING.encode(buf, payload.texturePath());
            for (int i = 0; i < 4; i++) {
                buf.writeFloat(payload.cornerOpacity()[i]);
            }
            for (int i = 0; i < 4; i++) {
                buf.writeFloat(payload.cornerMelt()[i]);
            }
            for (int i = 0; i < 4; i++) {
                buf.writeFloat(payload.cornerH()[i]);
            }
            for (int i = 0; i < 4; i++) {
                buf.writeFloat(payload.cornerV()[i]);
            }
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
