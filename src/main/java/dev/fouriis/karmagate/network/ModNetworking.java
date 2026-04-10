package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.CoralNeuronEntity;
import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.coralneuron.CoralNeuronData;
import dev.fouriis.karmagate.coralneuron.CoralNeuronManager;
import dev.fouriis.karmagate.entity.GraffitiEntity;
import dev.fouriis.karmagate.gridproject.ProjectionZoneData;
import dev.fouriis.karmagate.gridproject.ProjectionZoneManager;
import dev.fouriis.karmagate.rain.GlobalRain;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

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

        PayloadTypeRegistry.playS2C().register(
            GlobalRainSyncPayload.ID,
            GlobalRainSyncPayload.CODEC
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

        PayloadTypeRegistry.playC2S().register(
            DeleteGraffitiPayload.ID,
            DeleteGraffitiPayload.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
            CreateCoralNeuronPayload.ID,
            CreateCoralNeuronPayload.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
            DeleteCoralNeuronPayload.ID,
            DeleteCoralNeuronPayload.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
            CreateProjectionZonePayload.ID,
            CreateProjectionZonePayload.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
            DeleteProjectionZonePayload.ID,
            DeleteProjectionZonePayload.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
            UpdateBarrierPlatformPayload.ID,
            UpdateBarrierPlatformPayload.CODEC
        );

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
            GlobalRain rain = GlobalRain.get(server);
            syncGlobalRainToPlayer(
                    handler.getPlayer(),
                    rain.getIntensity(),
                    rain.getRainDirection(),
                    rain.getBulletRainDensity(),
                    rain.getRumbleSound(),
                    rain.getScreenShake(),
                    rain.getMicroScreenShake()
            );
        });

        ServerPlayNetworking.registerGlobalReceiver(DeleteGraffitiPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                var entity = player.getWorld().getEntityById(payload.entityId());
                if (!(entity instanceof GraffitiEntity graffiti)) return;
                if (!graffiti.isAlive()) return;

                double distSq = player.squaredDistanceTo(graffiti.getPos());
                if (distSq > 100.0) return;

                graffiti.discard();
                KarmaGateMod.LOGGER.info("Deleted graffiti entity {} for player {}",
                    payload.entityId(), player.getName().getString());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(CreateCoralNeuronPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (!player.hasPermissionLevel(2)) return;
            context.server().execute(() -> {
                CoralNeuronManager manager = CoralNeuronManager.get(context.server());
                ServerWorld world = player.getServerWorld();

                Vec3d anchorA = new Vec3d(payload.x1(), payload.y1(), payload.z1());
                Vec3d anchorB = new Vec3d(payload.x2(), payload.y2(), payload.z2());

                if (manager.hasNeuron(payload.name())) {
                    Optional<CoralNeuronData> existing = manager.removeNeuron(payload.name());
                    existing.flatMap(d -> manager.findEntity(context.server(), d.entityUuid()))
                            .ifPresent(CoralNeuronEntity::discard);
                }

                CoralNeuronEntity entity = new CoralNeuronEntity(
                        KarmaGateMod.VINE_ENTITY_TYPE,
                        world,
                        anchorA,
                        anchorB,
                        payload.anchoredA(),
                        payload.anchoredB()
                );
                world.spawnEntity(entity);

                CoralNeuronData data = CoralNeuronData.of(
                        payload.name(),
                        entity.getUuid(),
                        payload.x1(), payload.y1(), payload.z1(),
                        payload.x2(), payload.y2(), payload.z2(),
                        payload.anchoredA(),
                        payload.anchoredB()
                );
                manager.addNeuron(data);

                KarmaGateMod.LOGGER.info("Tool created CoralNeuron '{}' for player {}",
                        payload.name(), player.getName().getString());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(DeleteCoralNeuronPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (!player.hasPermissionLevel(2)) return;
            context.server().execute(() -> {
                CoralNeuronManager manager = CoralNeuronManager.get(context.server());
                Optional<CoralNeuronData> removed = manager.removeNeuron(payload.name());
                removed.flatMap(d -> manager.findEntity(context.server(), d.entityUuid()))
                        .ifPresent(CoralNeuronEntity::discard);
                KarmaGateMod.LOGGER.info("Tool deleted CoralNeuron '{}' for player {}",
                        payload.name(), player.getName().getString());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(CreateProjectionZonePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (!player.hasPermissionLevel(2)) return;
            context.server().execute(() -> {
                ProjectionZoneManager manager = ProjectionZoneManager.get(context.server());
                ProjectionZoneData zone = ProjectionZoneData.of(
                        payload.name(),
                        payload.x1(), payload.y1(), payload.z1(),
                        payload.x2(), payload.y2(), payload.z2(),
                        payload.swarmerCount(),
                        payload.drawCircles(),
                        payload.drawGrid()
                );
                manager.addZone(zone);
                syncToAll(context.server());
                KarmaGateMod.LOGGER.info("Tool created ProjectionZone '{}' for player {}",
                        payload.name(), player.getName().getString());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(DeleteProjectionZonePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (!player.hasPermissionLevel(2)) return;
            context.server().execute(() -> {
                ProjectionZoneManager manager = ProjectionZoneManager.get(context.server());
                manager.removeZone(payload.name());
                syncToAll(context.server());
                KarmaGateMod.LOGGER.info("Tool deleted ProjectionZone '{}' for player {}",
                        payload.name(), player.getName().getString());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UpdateBarrierPlatformPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                ServerWorld world = player.getServerWorld();
                
                // Place barrier blocks at the specified positions
                for (net.minecraft.util.math.BlockPos pos : payload.platformPositions()) {
                    if (world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, net.minecraft.block.Blocks.BARRIER.getDefaultState(), 3);
                    }
                }
            });
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

    public static void syncGlobalRainToPlayer(ServerPlayerEntity player,
                                              float intensity,
                                              float rainDirection,
                                              float bulletRainDensity,
                                              float rumbleSound,
                                              float screenShake,
                                              float microScreenShake) {
        ServerPlayNetworking.send(
                player,
                new GlobalRainSyncPayload(
                        intensity,
                        rainDirection,
                        bulletRainDensity,
                        rumbleSound,
                        screenShake,
                        microScreenShake
                )
        );
    }

    public static void syncGlobalRainToAll(net.minecraft.server.MinecraftServer server,
                                           float intensity,
                                           float rainDirection,
                                           float bulletRainDensity,
                                           float rumbleSound,
                                           float screenShake,
                                           float microScreenShake) {
        GlobalRainSyncPayload payload = new GlobalRainSyncPayload(
                intensity,
                rainDirection,
                bulletRainDensity,
                rumbleSound,
                screenShake,
                microScreenShake
        );
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
