package dev.fouriis.karmagate.client.gridproject;

import dev.fouriis.karmagate.client.swarmer.NeuronSwarmer;
import net.minecraft.util.math.random.Random;

import java.util.*;

/**
 * Manages projected circles for all projection zones.
 * Circles are attached to neuron swarmers and projected onto walls via shader.
 * Based on Rain World's IOwnProjectedCircles and ProjectedCircle system.
 */
public class ProjectedCirclePatternManager {
    private static final ProjectedCirclePatternManager INSTANCE = new ProjectedCirclePatternManager();
    
    // Maximum circles per zone for GPU uniform limits
    public static final int MAX_CIRCLES = 32;
    
    // Circles per zone
    private final Map<String, List<ProjectedCircleInstance>> circlesByZone = new HashMap<>();
    
    // Packed data caches per zone
    private final Map<String, PackedCircleData> packedDataByZone = new HashMap<>();
    
    // Spawn probability per swarmer per tick
    private static final float CIRCLE_SPAWN_CHANCE = 1f / 170f; // ~0.6% per tick, from SSOracleSwarmer
    
    // Maximum circles per zone
    private static final int MAX_CIRCLES_PER_ZONE = 24;
    
    private final Random random = Random.create();
    
    private ProjectedCirclePatternManager() {}
    
    public static ProjectedCirclePatternManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Container for packed circle data ready for shader upload.
     */
    public static class PackedCircleData {
        public int count;
        public final float[] circles;  // [u, y, radius, blink] per circle
        public final float[] extras;   // [rotationDeg, spokes, reserved, alphaScale] per circle
        
        public PackedCircleData() {
            this.count = 0;
            this.circles = new float[MAX_CIRCLES * 4];
            this.extras = new float[MAX_CIRCLES * 4];
        }
        
        public void clear() {
            count = 0;
            Arrays.fill(circles, 0f);
            Arrays.fill(extras, 0f);
        }
    }
    
    /**
     * Called each tick to update circles for a zone.
     * Should be called from NeuronSwarmerManager.tick() after swarmers are updated.
     * 
     * @param zone The projection zone
     * @param swarmers List of swarmers in this zone
     * @param cameraX Camera X position for projection
     * @param cameraY Camera Y position for projection
     * @param cameraZ Camera Z position for projection
     * @param tickDelta Partial tick for interpolation
     */
    public void tickZone(ProjectionZone zone, List<NeuronSwarmer> swarmers, 
                         double cameraX, double cameraY, double cameraZ, float tickDelta) {
        if (zone == null) return;
        
        String zoneName = zone.getName();
        List<ProjectedCircleInstance> circles = circlesByZone.computeIfAbsent(
            zoneName, 
            k -> new ArrayList<>()
        );
        
        // Remove circles whose owners are gone or marked for removal
        circles.removeIf(c -> c.markedForRemoval || c.getOwner() == null || c.getOwner().isMarkedForRemoval());
        
        // Tick existing circles with camera position
        for (ProjectedCircleInstance circle : circles) {
            circle.tick(zone, cameraX, cameraY, cameraZ);
        }
        
        // Potentially spawn new circles from swarmers
        if (circles.size() < MAX_CIRCLES_PER_ZONE && swarmers != null) {
            for (NeuronSwarmer swarmer : swarmers) {
                if (swarmer.isMarkedForRemoval()) continue;
                
                // Check if this swarmer already owns a circle
                boolean hasCircle = circles.stream()
                    .anyMatch(c -> c.getOwner() == swarmer);
                
                if (!hasCircle && random.nextFloat() < CIRCLE_SPAWN_CHANCE) {
                    // Spawn a new circle for this swarmer
                    float size = random.nextFloat();
                    ProjectedCircleInstance newCircle = new ProjectedCircleInstance(swarmer, size);
                    circles.add(newCircle);
                    
                    // Limit circles
                    if (circles.size() >= MAX_CIRCLES_PER_ZONE) break;
                }
            }
        }
        
        // Pack data for shader upload
        packCircleData(zoneName, circles, zone, tickDelta);
    }
    
    /**
     * Packs circle data into arrays for shader upload.
     */
    private void packCircleData(String zoneName, List<ProjectedCircleInstance> circles, 
                                ProjectionZone zone, float tickDelta) {
        PackedCircleData packed = packedDataByZone.computeIfAbsent(
            zoneName,
            k -> new PackedCircleData()
        );
        packed.clear();
        
        int count = Math.min(circles.size(), MAX_CIRCLES);
        packed.count = count;
        
        for (int i = 0; i < count; i++) {
            ProjectedCircleInstance c = circles.get(i);
            int base = i * 4;
            
            // Main data: u, y, radius, blink
            packed.circles[base + 0] = c.getCenterU();
            packed.circles[base + 1] = c.getCenterY();
            packed.circles[base + 2] = c.getEffectiveRadius(tickDelta);
            packed.circles[base + 3] = c.getBlink(tickDelta);
            
            // Extra data: rotationDeg, spokes, reserved, alphaScale
            packed.extras[base + 0] = c.getRotation(tickDelta);
            packed.extras[base + 1] = (float) c.getSpokes();
            packed.extras[base + 2] = 0f; // reserved
            packed.extras[base + 3] = c.getAlphaScale();
        }
    }
    
    /**
     * Gets packed circle data for a zone.
     * Returns null if no data available.
     */
    public PackedCircleData getPackedData(String zoneName) {
        return packedDataByZone.get(zoneName);
    }
    
    /**
     * Gets packed circle data for a zone, creating if needed.
     */
    public PackedCircleData getOrCreatePackedData(String zoneName) {
        return packedDataByZone.computeIfAbsent(zoneName, k -> new PackedCircleData());
    }
    
    /**
     * Gets all circles for a zone.
     */
    public List<ProjectedCircleInstance> getCircles(String zoneName) {
        return circlesByZone.getOrDefault(zoneName, Collections.emptyList());
    }
    
    /**
     * Gets mutable circle list for a zone, creating if needed.
     * Used internally for adding circles.
     */
    private List<ProjectedCircleInstance> getOrCreateCircles(String zoneName) {
        return circlesByZone.computeIfAbsent(zoneName, k -> new ArrayList<>());
    }
    
    /**
     * Spawns a circle for a generic circle owner (NeuronSwarmer or CoralNeuronEndpointOwner).
     * Returns true if spawned, false if at capacity or owner already has a circle.
     * 
     * @param zoneName The zone to spawn in
     * @param owner The circle owner
     * @return True if spawned successfully
     */
    public boolean spawnCircleForOwner(String zoneName, IProjectedCircleOwner owner) {
        if (owner == null || owner.isMarkedForRemoval()) {
            return false;
        }
        
        List<ProjectedCircleInstance> circles = getOrCreateCircles(zoneName);
        
        // Check capacity
        if (circles.size() >= MAX_CIRCLES_PER_ZONE) {
            return false;
        }
        
        // Check if owner already has a circle
        boolean hasCircle = circles.stream()
            .anyMatch(c -> c.getOwner() == owner);
        
        if (hasCircle) {
            return false;
        }
        
        // Spawn new circle
        float size = random.nextFloat();
        ProjectedCircleInstance newCircle = new ProjectedCircleInstance(owner, size);
        circles.add(newCircle);
        
        return true;
    }
    
    /**
     * Gets total circle count across all zones.
     */
    public int getTotalCircleCount() {
        int total = 0;
        for (List<ProjectedCircleInstance> circles : circlesByZone.values()) {
            total += circles.size();
        }
        return total;
    }
    
    /**
     * Removes circles for a zone.
     */
    public void removeZone(String zoneName) {
        circlesByZone.remove(zoneName);
        packedDataByZone.remove(zoneName);
    }
    
    /**
     * Clears all circles.
     */
    public void clear() {
        circlesByZone.clear();
        packedDataByZone.clear();
    }
    
    /**
     * Cleans up zones that no longer exist.
     */
    public void cleanupOrphanedZones(Set<String> validZoneNames) {
        circlesByZone.keySet().removeIf(name -> !validZoneNames.contains(name));
        packedDataByZone.keySet().removeIf(name -> !validZoneNames.contains(name));
    }
}
