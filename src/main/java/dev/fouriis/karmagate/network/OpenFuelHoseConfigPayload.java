package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record OpenFuelHoseConfigPayload(
        String dimensionId,
        int startX,
        int startY,
        int startZ,
        int endX,
        int endY,
        int endZ
) implements CustomPayload {
    public static final CustomPayload.Id<OpenFuelHoseConfigPayload> ID =
            new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "open_fuel_hose_config"));

    public static final PacketCodec<RegistryByteBuf, OpenFuelHoseConfigPayload> CODEC = new PacketCodec<>() {
        @Override
        public OpenFuelHoseConfigPayload decode(RegistryByteBuf buf) {
            return new OpenFuelHoseConfigPayload(
                    PacketCodecs.STRING.decode(buf),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt()
            );
        }

        @Override
        public void encode(RegistryByteBuf buf, OpenFuelHoseConfigPayload payload) {
            PacketCodecs.STRING.encode(buf, payload.dimensionId());
            buf.writeInt(payload.startX());
            buf.writeInt(payload.startY());
            buf.writeInt(payload.startZ());
            buf.writeInt(payload.endX());
            buf.writeInt(payload.endY());
            buf.writeInt(payload.endZ());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}