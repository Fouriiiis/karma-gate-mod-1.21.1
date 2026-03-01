package dev.fouriis.karmagate.client.network;

import dev.fouriis.karmagate.client.gridproject.ProjectionZone;
import dev.fouriis.karmagate.network.ProjectionZoneSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.math.BlockPos;

/**
 * Handles client-side networking for projection zones.
 */
public class ClientNetworking {
    
    /**
     * Registers all client-side network handlers.
     * Call this during client initialization.
     */
    public static void register() {
        // Handle zone sync from server
        ClientPlayNetworking.registerGlobalReceiver(
            ProjectionZoneSyncPayload.ID,
            (payload, context) -> {
                // Process on the main client thread
                context.client().execute(() -> {
                    applyZoneSync(payload);
                });
            }
        );
    }
    
    /**
     * Applies a zone sync payload, replacing all client-side zones.
     */
    private static void applyZoneSync(ProjectionZoneSyncPayload payload) {
        // Clear existing zones
        ProjectionZone.clearZones();
        
        // Add all zones from the server
        for (ProjectionZoneSyncPayload.ZoneEntry entry : payload.zones()) {
            BlockPos corner1 = new BlockPos(entry.x1(), entry.y1(), entry.z1());
            BlockPos corner2 = new BlockPos(entry.x2(), entry.y2(), entry.z2());
            ProjectionZone zone = new ProjectionZone(entry.name(), corner1, corner2,
                entry.swarmerCount(), entry.drawCircles(), entry.drawGrid());
            ProjectionZone.addZone(zone);
        }
        
        dev.fouriis.karmagate.KarmaGateMod.LOGGER.info(
            "Synced {} projection zone(s) from server", 
            payload.zones().size()
        );
    }
}
