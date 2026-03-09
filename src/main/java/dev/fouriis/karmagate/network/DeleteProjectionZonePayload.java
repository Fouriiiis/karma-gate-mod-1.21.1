package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client-to-server payload for deleting a ProjectionZone by name from the selection tool.
 */
public record DeleteProjectionZonePayload(String name) implements CustomPayload {

    public static final CustomPayload.Id<DeleteProjectionZonePayload> ID =
            new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "delete_projection_zone"));

    public static final PacketCodec<RegistryByteBuf, DeleteProjectionZonePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, DeleteProjectionZonePayload::name,
            DeleteProjectionZonePayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

