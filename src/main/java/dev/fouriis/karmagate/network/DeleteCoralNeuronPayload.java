package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client-to-server payload for deleting a CoralNeuron by name from the selection tool.
 */
public record DeleteCoralNeuronPayload(String name) implements CustomPayload {

    public static final CustomPayload.Id<DeleteCoralNeuronPayload> ID =
            new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "delete_coral_neuron"));

    public static final PacketCodec<RegistryByteBuf, DeleteCoralNeuronPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, DeleteCoralNeuronPayload::name,
            DeleteCoralNeuronPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

