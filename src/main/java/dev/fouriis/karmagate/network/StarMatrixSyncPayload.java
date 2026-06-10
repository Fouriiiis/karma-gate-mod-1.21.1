package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.gridproject.StarMatrixData;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Syncs all server StarMatrices to the client.
 */
public record StarMatrixSyncPayload(List<Entry> matrices) implements CustomPayload {

    public static final CustomPayload.Id<StarMatrixSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "star_matrix_sync"));

    public static final PacketCodec<RegistryByteBuf, StarMatrixSyncPayload> CODEC = PacketCodec.tuple(
        Entry.LIST_CODEC, StarMatrixSyncPayload::matrices,
        StarMatrixSyncPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static StarMatrixSyncPayload fromMatrices(Iterable<StarMatrixData> matrices) {
        List<Entry> entries = new ArrayList<>();
        for (StarMatrixData matrix : matrices) {
            entries.add(new Entry(
                matrix.name(),
                matrix.zoneName(),
                matrix.position().getX(),
                matrix.position().getY(),
                matrix.position().getZ()
            ));
        }
        return new StarMatrixSyncPayload(entries);
    }

    public record Entry(String name, String zoneName, int x, int y, int z) {
        public static final PacketCodec<RegistryByteBuf, Entry> CODEC = new PacketCodec<>() {
            @Override
            public Entry decode(RegistryByteBuf buf) {
                String name = PacketCodecs.STRING.decode(buf);
                String zoneName = PacketCodecs.STRING.decode(buf);
                int x = buf.readInt();
                int y = buf.readInt();
                int z = buf.readInt();
                return new Entry(name, zoneName, x, y, z);
            }

            @Override
            public void encode(RegistryByteBuf buf, Entry value) {
                PacketCodecs.STRING.encode(buf, value.name());
                PacketCodecs.STRING.encode(buf, value.zoneName());
                buf.writeInt(value.x());
                buf.writeInt(value.y());
                buf.writeInt(value.z());
            }
        };

        public static final PacketCodec<RegistryByteBuf, List<Entry>> LIST_CODEC =
            CODEC.collect(PacketCodecs.toList());
    }
}
