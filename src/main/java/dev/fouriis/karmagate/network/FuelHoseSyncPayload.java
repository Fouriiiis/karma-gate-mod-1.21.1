package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.hose.FuelHoseData;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public record FuelHoseSyncPayload(List<HoseEntry> hoses) implements CustomPayload {
    public static final CustomPayload.Id<FuelHoseSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "fuel_hose_sync"));

    public static final PacketCodec<RegistryByteBuf, FuelHoseSyncPayload> CODEC = PacketCodec.tuple(
            HoseEntry.LIST_CODEC, FuelHoseSyncPayload::hoses,
            FuelHoseSyncPayload::new
    );

    public static FuelHoseSyncPayload fromHoses(Iterable<FuelHoseData> hoseData) {
        List<HoseEntry> entries = new ArrayList<>();
        for (FuelHoseData hose : hoseData) {
            entries.add(HoseEntry.fromData(hose));
        }
        return new FuelHoseSyncPayload(entries);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record HoseEntry(
            String id,
            String dimensionId,
            int startX,
            int startY,
            int startZ,
            int endX,
            int endY,
            int endZ,
            int segmentCount,
            int simulationTicks,
            float gravity,
            List<PointEntry> points
    ) {
        public static final PacketCodec<RegistryByteBuf, HoseEntry> CODEC = new PacketCodec<>() {
            @Override
            public HoseEntry decode(RegistryByteBuf buf) {
                String id = PacketCodecs.STRING.decode(buf);
                String dimensionId = PacketCodecs.STRING.decode(buf);
                int startX = buf.readInt();
                int startY = buf.readInt();
                int startZ = buf.readInt();
                int endX = buf.readInt();
                int endY = buf.readInt();
                int endZ = buf.readInt();
                int segmentCount = buf.readInt();
                int simulationTicks = buf.readInt();
                float gravity = buf.readFloat();
                List<PointEntry> points = PointEntry.LIST_CODEC.decode(buf);
                return new HoseEntry(id, dimensionId, startX, startY, startZ, endX, endY, endZ, segmentCount, simulationTicks, gravity, points);
            }

            @Override
            public void encode(RegistryByteBuf buf, HoseEntry entry) {
                PacketCodecs.STRING.encode(buf, entry.id());
                PacketCodecs.STRING.encode(buf, entry.dimensionId());
                buf.writeInt(entry.startX());
                buf.writeInt(entry.startY());
                buf.writeInt(entry.startZ());
                buf.writeInt(entry.endX());
                buf.writeInt(entry.endY());
                buf.writeInt(entry.endZ());
                buf.writeInt(entry.segmentCount());
                buf.writeInt(entry.simulationTicks());
                buf.writeFloat(entry.gravity());
                PointEntry.LIST_CODEC.encode(buf, entry.points());
            }
        };

        public static final PacketCodec<RegistryByteBuf, List<HoseEntry>> LIST_CODEC = CODEC.collect(PacketCodecs.toList());

        public static HoseEntry fromData(FuelHoseData hose) {
            List<PointEntry> points = new ArrayList<>();
            for (Vec3d point : hose.points()) {
                points.add(new PointEntry(point.x, point.y, point.z));
            }
            return new HoseEntry(
                    hose.id(),
                    hose.dimension().getValue().toString(),
                    hose.startPos().getX(), hose.startPos().getY(), hose.startPos().getZ(),
                    hose.endPos().getX(), hose.endPos().getY(), hose.endPos().getZ(),
                    hose.segmentCount(), hose.simulationTicks(), hose.gravity(),
                    points
            );
        }

        public BlockPos startPos() {
            return new BlockPos(startX, startY, startZ);
        }

        public BlockPos endPos() {
            return new BlockPos(endX, endY, endZ);
        }
    }

    public record PointEntry(double x, double y, double z) {
        public static final PacketCodec<RegistryByteBuf, PointEntry> CODEC = new PacketCodec<>() {
            @Override
            public PointEntry decode(RegistryByteBuf buf) {
                return new PointEntry(buf.readDouble(), buf.readDouble(), buf.readDouble());
            }

            @Override
            public void encode(RegistryByteBuf buf, PointEntry entry) {
                buf.writeDouble(entry.x());
                buf.writeDouble(entry.y());
                buf.writeDouble(entry.z());
            }
        };

        public static final PacketCodec<RegistryByteBuf, List<PointEntry>> LIST_CODEC = CODEC.collect(PacketCodecs.toList());
    }
}