package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public record CreateFuelHosePayload(
        int startX,
        int startY,
        int startZ,
        int endX,
        int endY,
        int endZ,
        int segmentCount,
        int simulationTicks,
        float gravity
) implements CustomPayload {
    public static final CustomPayload.Id<CreateFuelHosePayload> ID =
            new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "create_fuel_hose"));

    public static final PacketCodec<RegistryByteBuf, CreateFuelHosePayload> CODEC = new PacketCodec<>() {
        @Override
        public CreateFuelHosePayload decode(RegistryByteBuf buf) {
            return new CreateFuelHosePayload(
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readFloat()
            );
        }

        @Override
        public void encode(RegistryByteBuf buf, CreateFuelHosePayload payload) {
            buf.writeInt(payload.startX());
            buf.writeInt(payload.startY());
            buf.writeInt(payload.startZ());
            buf.writeInt(payload.endX());
            buf.writeInt(payload.endY());
            buf.writeInt(payload.endZ());
            buf.writeInt(payload.segmentCount());
            buf.writeInt(payload.simulationTicks());
            buf.writeFloat(payload.gravity());
        }
    };

    public BlockPos startPos() {
        return new BlockPos(startX, startY, startZ);
    }

    public BlockPos endPos() {
        return new BlockPos(endX, endY, endZ);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}