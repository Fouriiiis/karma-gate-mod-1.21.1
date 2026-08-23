package dev.fouriis.karmagate.entity.coralbrain.client;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.client.gridproject.ProjectedCirclePatternManager;
import dev.fouriis.karmagate.client.gridproject.ProjectionZone;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages all neuron swarmers across all projection zones.
 * Handles spawning, updating, and removal of swarmers based on player presence.
 */
public class NeuronSwarmerManager {
    private static final NeuronSwarmerManager INSTANCE = new NeuronSwarmerManager();
    
    // Swarmers per zone
    private final Map<String, List<NeuronSwarmer>> swarmersByZone = new HashMap<>();
    
    // Configuration
    private static final int SPAWN_BATCH_SIZE = 90;
    private static final double PLAYER_CHECK_RANGE = 200.0; // Player must be within this range for swarmers to be active
    
    private final Random random = Random.create();
    
    private NeuronSwarmerManager() {}
    
    public static NeuronSwarmerManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Called every client tick to update all swarmers.
     */
    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            // No world, clear all swarmers
            swarmersByZone.clear();
            ProjectedCirclePatternManager.getInstance().clear();
            return;
        }
        
        PlayerEntity player = client.player;
        Vec3d playerPos = player.getPos();
        
        // Get all active projection zones
        List<ProjectionZone> zones = ProjectionZone.getZones();
        
        // Track which zones are still valid
        Set<String> validZoneNames = new HashSet<>();
        
        // Get tick delta for circle interpolation
        float tickDelta = client.getRenderTickCounter().getTickDelta(true);
        
        for (ProjectionZone zone : zones) {
            validZoneNames.add(zone.getName());
            
            // Check if player is near this zone
            boolean playerInRange = isPlayerNearZone(playerPos, zone);
            
            List<NeuronSwarmer> swarmers = swarmersByZone.computeIfAbsent(
                zone.getName(), 
                k -> new ArrayList<>()
            );
            
            if (playerInRange) {
                // Spawn swarmers if needed (use zone's swarmerCount)
                int targetCount = zone.getSwarmerCount();
                if (swarmers.size() < targetCount) {
                    int toSpawn = Math.min(SPAWN_BATCH_SIZE, targetCount - swarmers.size());
                    for (int i = 0; i < toSpawn; i++) {
                        Vec3d spawnPos = getRandomPositionInZone(zone, client.world);
                        swarmers.add(new NeuronSwarmer(zone.getName(), spawnPos));
                    }
                } else if (swarmers.size() > targetCount) {
                    // Trim excess swarmers if count was reduced
                    while (swarmers.size() > targetCount) {
                        swarmers.remove(swarmers.size() - 1);
                    }
                }
                
                // Update all swarmers in this zone
                Vec3d zoneMin = new Vec3d(zone.getMinX(), zone.getMinY(), zone.getMinZ());
                Vec3d zoneMax = new Vec3d(zone.getMaxX() + 1, zone.getMaxY() + 1, zone.getMaxZ() + 1);
                
                ClientWorld world = client.world;
                Vec3d[] previousPositions = new Vec3d[swarmers.size()];
                Vec3d[] previousDirections = new Vec3d[swarmers.size()];
                Vec3d[] previousLazyDirections = new Vec3d[swarmers.size()];
                float[] previousRotations = new float[swarmers.size()];
                for (int i = 0; i < swarmers.size(); i++) {
                    NeuronSwarmer swarmer = swarmers.get(i);
                    previousPositions[i] = swarmer.position;
                    previousDirections[i] = swarmer.direction;
                    previousLazyDirections[i] = swarmer.lazyDirection;
                    previousRotations[i] = swarmer.rotation;
                }
                // Rain World runs the CoralBrain room at 40 updates/s.
                for (int substep = 0; substep < 2; substep++) {
                    for (NeuronSwarmer swarmer : swarmers) {
                        swarmer.tick(swarmers, zoneMin, zoneMax, world);
                    }
                }
                for (int i = 0; i < swarmers.size(); i++) {
                    NeuronSwarmer swarmer = swarmers.get(i);
                    swarmer.lastPosition = previousPositions[i];
                    swarmer.lastDirection = previousDirections[i];
                    swarmer.lastLazyDirection = previousLazyDirections[i];
                    swarmer.lastRotation = previousRotations[i];
                }
                
                // Remove any marked for removal (shouldn't happen normally, but safety check)
                swarmers.removeIf(s -> s.markedForRemoval);
            }
            // Note: We don't remove swarmers when player leaves range - they persist
            // They will only be removed when the zone itself is removed
            
            // Tick projected circles for this zone (even if player is out of range, circles should animate)
            // Use camera position for projection onto walls behind neurons
            net.minecraft.client.render.Camera camera = client.gameRenderer.getCamera();
            double camX = camera.getPos().x;
            double camY = camera.getPos().y;
            double camZ = camera.getPos().z;
            ProjectedCirclePatternManager.getInstance().tickZone(zone, swarmers, camX, camY, camZ, tickDelta);
        }
        
        // Remove swarmers for zones that no longer exist
        Iterator<String> zoneIterator = swarmersByZone.keySet().iterator();
        while (zoneIterator.hasNext()) {
            String zoneName = zoneIterator.next();
            if (!validZoneNames.contains(zoneName)) {
                zoneIterator.remove();
            }
        }
        
        // Cleanup orphaned circle zones
        ProjectedCirclePatternManager.getInstance().cleanupOrphanedZones(validZoneNames);
    }
    
    /**
     * Gets all swarmers for rendering.
     */
    public List<NeuronSwarmer> getAllSwarmers() {
        List<NeuronSwarmer> all = new ArrayList<>();
        for (List<NeuronSwarmer> zoneSwarmers : swarmersByZone.values()) {
            all.addAll(zoneSwarmers);
        }
        return all;
    }
    
    /**
     * Gets swarmers for a specific zone.
     */
    public List<NeuronSwarmer> getSwarmersForZone(String zoneName) {
        return swarmersByZone.getOrDefault(zoneName, List.of());
    }
    
    /**
     * Clears all swarmers (called on disconnect).
     */
    public void clear() {
        swarmersByZone.clear();
    }
    
    /**
     * Checks if the player is near a zone (within interaction range).
     */
    private boolean isPlayerNearZone(Vec3d playerPos, ProjectionZone zone) {
        // Check if player is inside or near the zone bounds
        double centerX = zone.getCenterX();
        double centerY = (zone.getMinY() + zone.getMaxY()) / 2.0;
        double centerZ = zone.getCenterZ();
        
        double dx = playerPos.x - centerX;
        double dy = playerPos.y - centerY;
        double dz = playerPos.z - centerZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        
        return distSq < PLAYER_CHECK_RANGE * PLAYER_CHECK_RANGE;
    }
    
    /**
     * Gets a random position within a zone for spawning.
     */
    private Vec3d getRandomPositionInZone(ProjectionZone zone, World world) {
    final int MAX_TRIES = 32;

    for (int i = 0; i < MAX_TRIES; i++) {
        double x = zone.getMinX() + random.nextDouble() * (zone.getMaxX() - zone.getMinX());
        double y = zone.getMinY() + random.nextDouble() * (zone.getMaxY() - zone.getMinY());
        double z = zone.getMinZ() + random.nextDouble() * (zone.getMaxZ() - zone.getMinZ());

        BlockPos bp = BlockPos.ofFloored(x, y, z);

        // Require the sampled point to be in "air-ish" space and not inside any solid collision.
        if (!world.getBlockState(bp).isAir()) continue;

        // Also ensure the point isn't inside a collision shape (covers some non-air cases / shapes)
        if (!world.isSpaceEmpty(null, new Box(x, y, z, x, y, z).expand(0.1))) continue;

        return new Vec3d(x, y, z);
    }

    // Fallback: pick something guaranteed non-solid-ish near the top of the zone
    double x = zone.getMinX() + random.nextDouble() * (zone.getMaxX() - zone.getMinX());
    double z = zone.getMinZ() + random.nextDouble() * (zone.getMaxZ() - zone.getMinZ());
    double y = zone.getMaxY() - 0.5;

    return new Vec3d(x, y, z);
}

    
    /**
     * Gets the count of swarmers in a zone.
     */
    public int getSwarmerCount(String zoneName) {
        List<NeuronSwarmer> swarmers = swarmersByZone.get(zoneName);
        return swarmers != null ? swarmers.size() : 0;
    }
    
    /**
     * Gets total swarmer count across all zones.
     */
    public int getTotalSwarmerCount() {
        int total = 0;
        for (List<NeuronSwarmer> swarmers : swarmersByZone.values()) {
            total += swarmers.size();
        }
        return total;
    }
}
