package dev.fouriis.karmagate.client.gridproject;

import dev.fouriis.karmagate.CoralNeuronEntity;
import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.*;

/**
 * Manages projected circles for CoralNeuronEntity endpoints.
 * Non-anchored (floating) endpoints of CoralNeurons can project circles onto the shader wall,
 * similar to how NeuronSwarmers project circles.
 * 
 * This manager:
 * - Tracks all CoralNeuronEntity instances in loaded chunks
 * - Creates CoralNeuronEndpointOwner instances for non-anchored endpoints
 * - Manages circle spawning probability and lifecycle
 */
public class CoralNeuronCircleManager {
    private static final CoralNeuronCircleManager INSTANCE = new CoralNeuronCircleManager();
    
    // Tracked endpoint owners by zone
    private final Map<String, List<CoralNeuronEndpointOwner>> ownersByZone = new HashMap<>();
    
    // Map from endpoint key to its owner for quick lookup
    private final Map<String, CoralNeuronEndpointOwner> ownersByKey = new HashMap<>();
    
    // Random for spawn probability
    private final Random random = Random.create();
    
    // Spawn probability per floating endpoint per tick
    // Set to 1.0 to always spawn immediately (for testing), normally would be ~1/80
    private static final float CIRCLE_SPAWN_CHANCE = 1.0f;
    
    private CoralNeuronCircleManager() {}
    
    public static CoralNeuronCircleManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Called each tick to update coral neuron endpoint tracking.
     * Scans for CoralNeuronEntity instances within projection zones and
     * creates/removes endpoint owners as needed.
     */
    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) {
            clear();
            return;
        }
        
        // Get all active projection zones
        List<ProjectionZone> zones = ProjectionZone.getZones();
        if (zones.isEmpty()) {
            clear();
            return;
        }
        
        // Track valid zone names for cleanup
        Set<String> validZoneNames = new HashSet<>();
        
        // Get camera position for circle ticking
        net.minecraft.client.render.Camera camera = client.gameRenderer.getCamera();
        double camX = camera.getPos().x;
        double camY = camera.getPos().y;
        double camZ = camera.getPos().z;
        float tickDelta = client.getRenderTickCounter().getTickDelta(true);
        
        for (ProjectionZone zone : zones) {
            String zoneName = zone.getName();
            validZoneNames.add(zoneName);
            
            // Get or create owner list for this zone
            List<CoralNeuronEndpointOwner> owners = ownersByZone.computeIfAbsent(
                zoneName, k -> new ArrayList<>()
            );
            
            // Scan for CoralNeuronEntity instances in this zone
            Box zoneBounds = new Box(
                zone.getMinX(), zone.getMinY(), zone.getMinZ(),
                zone.getMaxX() + 1, zone.getMaxY() + 1, zone.getMaxZ() + 1
            );
            
            List<CoralNeuronEntity> entities = world.getEntitiesByClass(
                CoralNeuronEntity.class,
                zoneBounds,
                e -> !e.isRemoved()
            );
            
            // Debug: log entity count
            if (!entities.isEmpty()) {
                //KarmaGateMod.LOGGER.info("[CoralNeuronCircle] Found {} CoralNeuronEntity in zone {}", entities.size(), zoneName);
            }
            
            // Process each entity's endpoints
            Set<String> activeKeys = new HashSet<>();
            
            for (CoralNeuronEntity entity : entities) {
                processEntityEndpoints(entity, zoneName, owners, activeKeys);
            }
            
            // Remove owners for endpoints that are no longer active
            Iterator<CoralNeuronEndpointOwner> ownerIter = owners.iterator();
            while (ownerIter.hasNext()) {
                CoralNeuronEndpointOwner owner = ownerIter.next();
                if (!activeKeys.contains(owner.getKey()) || owner.isMarkedForRemoval()) {
                    owner.markForRemoval();
                    ownersByKey.remove(owner.getKey());
                    ownerIter.remove();
                }
            }
            
            // Tick circles for coral neuron endpoints in this zone
            tickCirclesForZone(zone, owners, camX, camY, camZ, tickDelta);
        }
        
        // Remove owners for zones that no longer exist
        ownersByZone.keySet().removeIf(name -> !validZoneNames.contains(name));
    }
    
    /**
     * Processes the endpoints of a single CoralNeuronEntity.
     * Creates owners for non-anchored endpoints.
     */
    private void processEntityEndpoints(
            CoralNeuronEntity entity,
            String zoneName,
            List<CoralNeuronEndpointOwner> owners,
            Set<String> activeKeys
    ) {
        int entityId = entity.getId();
        
        // Check endpoint A (if not pinned, it can project a circle)
        if (!entity.isAnchorAPinned()) {
            String keyA = entityId + "_A";
            activeKeys.add(keyA);
            
            CoralNeuronEndpointOwner ownerA = ownersByKey.get(keyA);
            if (ownerA == null) {
                // Create new owner for this endpoint
                ownerA = new CoralNeuronEndpointOwner(entityId, true);
                owners.add(ownerA);
                ownersByKey.put(keyA, ownerA);
                KarmaGateMod.LOGGER.info("[CoralNeuronCircle] Created owner for endpoint A of entity {}", entityId);
            }
            
            // Update position from entity
            ownerA.updateFromEntity(entity);
        }
        
        // Check endpoint B (if not pinned, it can project a circle)
        if (!entity.isAnchorBPinned()) {
            String keyB = entityId + "_B";
            activeKeys.add(keyB);
            
            CoralNeuronEndpointOwner ownerB = ownersByKey.get(keyB);
            if (ownerB == null) {
                // Create new owner for this endpoint
                ownerB = new CoralNeuronEndpointOwner(entityId, false);
                owners.add(ownerB);
                ownersByKey.put(keyB, ownerB);
                KarmaGateMod.LOGGER.info("[CoralNeuronCircle] Created owner for endpoint B of entity {}", entityId);
            }
            
            // Update position from entity
            ownerB.updateFromEntity(entity);
        }
    }
    
    /**
     * Ticks circles for coral neuron endpoints in a zone.
     * Delegates to ProjectedCirclePatternManager for actual circle management.
     */
    private void tickCirclesForZone(
            ProjectionZone zone,
            List<CoralNeuronEndpointOwner> owners,
            double cameraX, double cameraY, double cameraZ,
            float tickDelta
    ) {
        if (owners.isEmpty()) {
            return;
        }
        
        ProjectedCirclePatternManager circleManager = ProjectedCirclePatternManager.getInstance();
        String zoneName = zone.getName();
        
        // Get existing circles for this zone
        List<ProjectedCircleInstance> circles = circleManager.getCircles(zoneName);
        
        // Try to spawn circles for owners that don't have one yet
        for (CoralNeuronEndpointOwner owner : owners) {
            if (owner.isMarkedForRemoval()) continue;
            
            // Check if this owner already has a circle
            boolean hasCircle = circles.stream()
                .anyMatch(c -> c.getOwner() == owner);
            
            if (!hasCircle && random.nextFloat() < CIRCLE_SPAWN_CHANCE) {
                // Spawn a new circle for this endpoint
                boolean spawned = circleManager.spawnCircleForOwner(zoneName, owner);
                //if (spawned) {
                //    KarmaGateMod.LOGGER.info("[CoralNeuronCircle] Spawned circle for owner {} at position {}",
                //        owner.getKey(), owner.getCirclePosition());
                //}
            }
        }
    }
    
    /**
     * Gets all endpoint owners for a specific zone.
     */
    public List<CoralNeuronEndpointOwner> getOwnersForZone(String zoneName) {
        return ownersByZone.getOrDefault(zoneName, Collections.emptyList());
    }
    
    /**
     * Gets total number of tracked floating endpoints.
     */
    public int getTotalEndpointCount() {
        int total = 0;
        for (List<CoralNeuronEndpointOwner> owners : ownersByZone.values()) {
            total += owners.size();
        }
        return total;
    }
    
    /**
     * Clears all tracked owners.
     */
    public void clear() {
        ownersByZone.clear();
        ownersByKey.clear();
    }
}
