package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.GraffitiEntity;
import dev.fouriis.karmagate.gridproject.ProjectionZoneManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Direction;

/**
 * Handles server-side networking for projection zones.
 */
public class ModNetworking {
    
    /**
     * Registers all network payloads and handlers.
     * Call this during mod initialization.
     */
    public static void register() {
        // Register the sync payload type (server -> client)
        PayloadTypeRegistry.playS2C().register(
            ProjectionZoneSyncPayload.ID, 
            ProjectionZoneSyncPayload.CODEC
        );
        
        // Register the graffiti spawn payload type (client -> server)
        PayloadTypeRegistry.playC2S().register(
            SpawnGraffitiPayload.ID,
            SpawnGraffitiPayload.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
            UpdateGraffitiPayload.ID,
            UpdateGraffitiPayload.CODEC
        );
        
        // Handle graffiti spawn requests from clients
        ServerPlayNetworking.registerGlobalReceiver(SpawnGraffitiPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                // Spawn the graffiti entity on the server
                GraffitiEntity graffiti = new GraffitiEntity(KarmaGateMod.GRAFFITI_ENTITY_TYPE, player.getWorld());
                graffiti.setPosition(payload.x(), payload.y(), payload.z());
                graffiti.setFacing(Direction.byId(payload.facingId()));
                graffiti.setTexturePath(payload.texturePath());
                
                player.getWorld().spawnEntity(graffiti);
                
                KarmaGateMod.LOGGER.info("Spawned graffiti at ({}, {}, {}) with texture {} for player {}", 
                    payload.x(), payload.y(), payload.z(), payload.texturePath(), player.getName().getString());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UpdateGraffitiPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                var entity = player.getWorld().getEntityById(payload.entityId());
                if (!(entity instanceof GraffitiEntity graffiti)) return;
                if (!graffiti.isAlive()) return;

                double distSq = player.squaredDistanceTo(graffiti.getPos());
                if (distSq > 100.0) return;

                graffiti.setTexturePath(payload.texturePath());

                float[] opacity = payload.cornerOpacity();
                float[] melt = payload.cornerMelt();
                float[] cornerH = payload.cornerH();
                float[] cornerV = payload.cornerV();
                for (int i = 0; i < 4; i++) {
                    graffiti.setCornerOpacity(i, opacity[i]);
                    graffiti.setCornerMelt(i, melt[i]);
                    graffiti.setCorner(i, cornerH[i], cornerV[i]);
                }
            });
        });
        
        // Sync zones to players when they join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            syncToPlayer(handler.getPlayer());
        });
    }
    
    /**
     * Syncs all projection zones to a specific player.
     */
    public static void syncToPlayer(ServerPlayerEntity player) {
        ProjectionZoneManager manager = ProjectionZoneManager.get(player.getServer());
        ProjectionZoneSyncPayload payload = ProjectionZoneSyncPayload.fromZones(manager.getAllZones());
        ServerPlayNetworking.send(player, payload);
    }
    
    /**
     * Syncs all projection zones to all players on the server.
     */
    public static void syncToAll(net.minecraft.server.MinecraftServer server) {
        ProjectionZoneManager manager = ProjectionZoneManager.get(server);
        ProjectionZoneSyncPayload payload = ProjectionZoneSyncPayload.fromZones(manager.getAllZones());
        
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
