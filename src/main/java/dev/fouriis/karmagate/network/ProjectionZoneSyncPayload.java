package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.gridproject.ProjectionZoneData;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Network payload for syncing all projection zones to clients.
 * Sent when a player joins or when zones are modified.
 */
public record ProjectionZoneSyncPayload(List<ZoneEntry> zones) implements CustomPayload {
    
    public static final CustomPayload.Id<ProjectionZoneSyncPayload> ID = 
        new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "projection_zone_sync"));
    
    public static final PacketCodec<RegistryByteBuf, ProjectionZoneSyncPayload> CODEC = PacketCodec.tuple(
        ZoneEntry.LIST_CODEC, ProjectionZoneSyncPayload::zones,
        ProjectionZoneSyncPayload::new
    );
    
    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
    
    /**
     * Creates a sync payload from a collection of zone data.
     */
    public static ProjectionZoneSyncPayload fromZones(Iterable<ProjectionZoneData> zoneData) {
        List<ZoneEntry> entries = new ArrayList<>();
        for (ProjectionZoneData zone : zoneData) {
            entries.add(new ZoneEntry(
                zone.name(),
                zone.corner1().getX(), zone.corner1().getY(), zone.corner1().getZ(),
                zone.corner2().getX(), zone.corner2().getY(), zone.corner2().getZ(),
                zone.swarmerCount(), zone.drawCircles(), zone.drawGrid()
            ));
        }
        return new ProjectionZoneSyncPayload(entries);
    }
    
    /**
     * A single zone entry for network transmission.
     */
    public record ZoneEntry(String name, int x1, int y1, int z1, int x2, int y2, int z2,
                              int swarmerCount, boolean drawCircles, boolean drawGrid) {
        
        // Custom codec for ZoneEntry since tuple() only supports up to 6 fields
        public static final PacketCodec<RegistryByteBuf, ZoneEntry> CODEC = new PacketCodec<>() {
            @Override
            public ZoneEntry decode(RegistryByteBuf buf) {
                String name = PacketCodecs.STRING.decode(buf);
                int x1 = buf.readInt();
                int y1 = buf.readInt();
                int z1 = buf.readInt();
                int x2 = buf.readInt();
                int y2 = buf.readInt();
                int z2 = buf.readInt();
                int swarmerCount = buf.readInt();
                boolean drawCircles = buf.readBoolean();
                boolean drawGrid = buf.readBoolean();
                return new ZoneEntry(name, x1, y1, z1, x2, y2, z2, swarmerCount, drawCircles, drawGrid);
            }
            
            @Override
            public void encode(RegistryByteBuf buf, ZoneEntry entry) {
                PacketCodecs.STRING.encode(buf, entry.name());
                buf.writeInt(entry.x1());
                buf.writeInt(entry.y1());
                buf.writeInt(entry.z1());
                buf.writeInt(entry.x2());
                buf.writeInt(entry.y2());
                buf.writeInt(entry.z2());
                buf.writeInt(entry.swarmerCount());
                buf.writeBoolean(entry.drawCircles());
                buf.writeBoolean(entry.drawGrid());
            }
        };
        
        public static final PacketCodec<RegistryByteBuf, List<ZoneEntry>> LIST_CODEC = 
            CODEC.collect(PacketCodecs.toList());
    }
}
