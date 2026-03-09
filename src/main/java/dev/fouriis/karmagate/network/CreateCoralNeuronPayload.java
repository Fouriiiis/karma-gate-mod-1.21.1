package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client-to-server payload for creating a CoralNeuron from the selection tool.
 * Carries the name, two anchor positions, and anchor flags for both endpoints.
 */
public record CreateCoralNeuronPayload(
        String name,
        double x1, double y1, double z1,
        double x2, double y2, double z2,
        boolean anchoredA,
        boolean anchoredB
) implements CustomPayload {

    public static final CustomPayload.Id<CreateCoralNeuronPayload> ID =
            new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "create_coral_neuron"));

    public static final PacketCodec<RegistryByteBuf, CreateCoralNeuronPayload> CODEC = new PacketCodec<>() {
        @Override
        public CreateCoralNeuronPayload decode(RegistryByteBuf buf) {
            String name = PacketCodecs.STRING.decode(buf);
            double x1 = buf.readDouble();
            double y1 = buf.readDouble();
            double z1 = buf.readDouble();
            double x2 = buf.readDouble();
            double y2 = buf.readDouble();
            double z2 = buf.readDouble();
            boolean anchoredA = buf.readBoolean();
            boolean anchoredB = buf.readBoolean();
            return new CreateCoralNeuronPayload(name, x1, y1, z1, x2, y2, z2, anchoredA, anchoredB);
        }

        @Override
        public void encode(RegistryByteBuf buf, CreateCoralNeuronPayload payload) {
            PacketCodecs.STRING.encode(buf, payload.name());
            buf.writeDouble(payload.x1());
            buf.writeDouble(payload.y1());
            buf.writeDouble(payload.z1());
            buf.writeDouble(payload.x2());
            buf.writeDouble(payload.y2());
            buf.writeDouble(payload.z2());
            buf.writeBoolean(payload.anchoredA());
            buf.writeBoolean(payload.anchoredB());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

