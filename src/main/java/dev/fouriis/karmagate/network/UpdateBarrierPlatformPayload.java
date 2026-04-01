package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

public record UpdateBarrierPlatformPayload(BlockPos center, Set<BlockPos> platformPositions) implements CustomPayload {
    public static final CustomPayload.Id<UpdateBarrierPlatformPayload> ID = 
        new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "update_barrier_platform"));
    
    public static final PacketCodec<RegistryByteBuf, UpdateBarrierPlatformPayload> CODEC = new PacketCodec<>() {
        @Override
        public UpdateBarrierPlatformPayload decode(RegistryByteBuf buf) {
            BlockPos center = BlockPos.fromLong(buf.readLong());
            int size = buf.readInt();
            Set<BlockPos> positions = new HashSet<>();
            for (int i = 0; i < size; i++) {
                positions.add(BlockPos.fromLong(buf.readLong()));
            }
            return new UpdateBarrierPlatformPayload(center, positions);
        }

        @Override
        public void encode(RegistryByteBuf buf, UpdateBarrierPlatformPayload payload) {
            buf.writeLong(payload.center().asLong());
            buf.writeInt(payload.platformPositions().size());
            for (BlockPos pos : payload.platformPositions()) {
                buf.writeLong(pos.asLong());
            }
        }
    };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
