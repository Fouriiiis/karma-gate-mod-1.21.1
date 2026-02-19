package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Network payload for spawning a graffiti entity from client.
 * Sent when player selects a texture from the picker.
 */
public record SpawnGraffitiPayload(
    double x, double y, double z,
    int facingId,
    String texturePath
) implements CustomPayload {
    
    public static final CustomPayload.Id<SpawnGraffitiPayload> ID = 
        new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "spawn_graffiti"));
    
    public static final PacketCodec<RegistryByteBuf, SpawnGraffitiPayload> CODEC = new PacketCodec<>() {
        @Override
        public SpawnGraffitiPayload decode(RegistryByteBuf buf) {
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            int facingId = buf.readInt();
            String texturePath = PacketCodecs.STRING.decode(buf);
            return new SpawnGraffitiPayload(x, y, z, facingId, texturePath);
        }
        
        @Override
        public void encode(RegistryByteBuf buf, SpawnGraffitiPayload payload) {
            buf.writeDouble(payload.x());
            buf.writeDouble(payload.y());
            buf.writeDouble(payload.z());
            buf.writeInt(payload.facingId());
            PacketCodecs.STRING.encode(buf, payload.texturePath());
        }
    };
    
    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
