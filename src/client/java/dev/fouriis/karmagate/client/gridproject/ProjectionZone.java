package dev.fouriis.karmagate.client.gridproject;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a 3D projection zone defined by two corner BlockPos positions.
 * The pattern is projected outward from the center XZ of the zone onto visible block faces,
 * as if there were 4 flat projectors facing outward from the center.
 * 
 * Pre-computes many values at construction time for render performance.
 */
public final class ProjectionZone {
    private final String name;
    private final BlockPos min;
    private final BlockPos max;
    private final Box bounds;
    
    // Zone parameters
    private final int swarmerCount;
    private final boolean drawCircles;
    private final boolean drawGrid;
    
    // Pre-computed values for rendering (avoid per-frame calculation)
    private final double centerX;
    private final double centerZ;
    private final double radius;
    private final float centerXf;
    private final float centerZf;
    private final float radiusf;
    
    // Pre-computed bounds as primitives (avoid BlockPos method calls)
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    
    // Pre-computed wall thickness boundaries
    private static final int WALL_THICKNESS = 20;
    private final int wallMinX, wallMaxX;
    private final int wallMinY, wallMaxY;
    private final int wallMinZ, wallMaxZ;
    
    // Iteration ranges for shell-only rendering (6 slabs)
    // Each slab is a region of the shell to iterate
    private final int[][] shellSlabs;

    // Static list of all active projection zones
    private static final List<ProjectionZone> ZONES = new ArrayList<>();

    /**
     * Creates a named projection zone with all parameters.
     */
    public ProjectionZone(String name, BlockPos corner1, BlockPos corner2,
                          int swarmerCount, boolean drawCircles, boolean drawGrid) {
        this.name = name;
        this.swarmerCount = swarmerCount;
        this.drawCircles = drawCircles;
        this.drawGrid = drawGrid;
        // Normalize to ensure min < max
        this.minX = Math.min(corner1.getX(), corner2.getX());
        this.minY = Math.min(corner1.getY(), corner2.getY());
        this.minZ = Math.min(corner1.getZ(), corner2.getZ());
        this.maxX = Math.max(corner1.getX(), corner2.getX());
        this.maxY = Math.max(corner1.getY(), corner2.getY());
        this.maxZ = Math.max(corner1.getZ(), corner2.getZ());

        this.min = new BlockPos(minX, minY, minZ);
        this.max = new BlockPos(maxX, maxY, maxZ);

        // Center XZ of the zone (projection origin)
        this.centerX = (minX + maxX + 1) / 2.0;
        this.centerZ = (minZ + maxZ + 1) / 2.0;
        this.centerXf = (float) centerX;
        this.centerZf = (float) centerZ;

        // Pre-compute radius
        double maxDx = Math.max(Math.abs(minX - centerX), Math.abs((maxX + 1) - centerX));
        double maxDz = Math.max(Math.abs(minZ - centerZ), Math.abs((maxZ + 1) - centerZ));
        this.radius = Math.max(maxDx, maxDz);
        this.radiusf = (float) radius;

        // Bounding box for containment checks
        this.bounds = new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        
        // Pre-compute wall boundaries
        this.wallMinX = minX + WALL_THICKNESS;
        this.wallMaxX = maxX - WALL_THICKNESS;
        this.wallMinY = minY + WALL_THICKNESS;
        this.wallMaxY = maxY - WALL_THICKNESS;
        this.wallMinZ = minZ + WALL_THICKNESS;
        this.wallMaxZ = maxZ - WALL_THICKNESS;
        
        // Pre-compute shell slabs for efficient iteration
        // Instead of iterating the entire volume and skipping interior,
        // we iterate only the 6 shell regions (slabs along each face)
        this.shellSlabs = computeShellSlabs();
    }
    
    /**
     * Computes the 6 shell slabs (non-overlapping regions that cover the outer shell).
     * Returns array of slabs, each slab is {minX, maxX, minY, maxY, minZ, maxZ}
     */
    private int[][] computeShellSlabs() {
        // We decompose the shell into 6 non-overlapping slabs:
        // 1. Bottom slab (Y = minY to wallMinY-1, full XZ)
        // 2. Top slab (Y = wallMaxY+1 to maxY, full XZ)
        // 3. North slab (Z = minZ to wallMinZ-1, middle Y, full X)
        // 4. South slab (Z = wallMaxZ+1 to maxZ, middle Y, full X)
        // 5. West slab (X = minX to wallMinX-1, middle Y, middle Z)
        // 6. East slab (X = wallMaxX+1 to maxX, middle Y, middle Z)
        
        int midYMin = Math.max(wallMinY, minY);
        int midYMax = Math.min(wallMaxY, maxY);
        int midZMin = Math.max(wallMinZ, minZ);
        int midZMax = Math.min(wallMaxZ, maxZ);
        
        List<int[]> slabs = new ArrayList<>();
        
        // Bottom slab
        if (wallMinY > minY) {
            slabs.add(new int[]{minX, maxX, minY, wallMinY - 1, minZ, maxZ});
        }
        
        // Top slab
        if (wallMaxY < maxY) {
            slabs.add(new int[]{minX, maxX, wallMaxY + 1, maxY, minZ, maxZ});
        }
        
        // North slab (only middle Y range to avoid overlap with top/bottom)
        if (wallMinZ > minZ && midYMin <= midYMax) {
            slabs.add(new int[]{minX, maxX, midYMin, midYMax, minZ, wallMinZ - 1});
        }
        
        // South slab
        if (wallMaxZ < maxZ && midYMin <= midYMax) {
            slabs.add(new int[]{minX, maxX, midYMin, midYMax, wallMaxZ + 1, maxZ});
        }
        
        // West slab (middle Y and Z to avoid overlaps)
        if (wallMinX > minX && midYMin <= midYMax && midZMin <= midZMax) {
            slabs.add(new int[]{minX, wallMinX - 1, midYMin, midYMax, midZMin, midZMax});
        }
        
        // East slab
        if (wallMaxX < maxX && midYMin <= midYMax && midZMin <= midZMax) {
            slabs.add(new int[]{wallMaxX + 1, maxX, midYMin, midYMax, midZMin, midZMax});
        }
        
        return slabs.toArray(new int[0][]);
    }
    
    // ========== Pre-computed getters ==========
    
    public String getName() {
        return name;
    }
    
    public int getSwarmerCount() {
        return swarmerCount;
    }
    
    public boolean isDrawCircles() {
        return drawCircles;
    }
    
    public boolean isDrawGrid() {
        return drawGrid;
    }
    
    public int[][] getShellSlabs() {
        return shellSlabs;
    }

    public BlockPos getMin() {
        return min;
    }

    public BlockPos getMax() {
        return max;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterZ() {
        return centerZ;
    }
    
    public float getCenterXf() {
        return centerXf;
    }
    
    public float getCenterZf() {
        return centerZf;
    }
    
    public double getRadius() {
        return radius;
    }
    
    public float getRadiusf() {
        return radiusf;
    }
    
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    public Box getBounds() {
        return bounds;
    }

    /**
     * Checks if a block position is within this zone's bounds.
     */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
            && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /**
     * Checks if a block position is within this zone's XZ bounds (ignoring Y).
     */
    public boolean containsXZ(int x, int z) {
        return x >= minX && x <= maxX
            && z >= minZ && z <= maxZ;
    }

    /**
     * Gets the width (X dimension) of the zone in blocks.
     */
    public int getWidth() {
        return maxX - minX + 1;
    }

    /**
     * Gets the height (Y dimension) of the zone in blocks.
     */
    public int getHeight() {
        return maxY - minY + 1;
    }

    /**
     * Gets the depth (Z dimension) of the zone in blocks.
     */
    public int getDepth() {
        return maxZ - minZ + 1;
    }

    // ========== Static zone management ==========

    /**
     * Registers a new projection zone.
     */
    public static void addZone(ProjectionZone zone) {
        // Remove any existing zone with the same name first
        ZONES.removeIf(z -> z.name.equals(zone.name));
        ZONES.add(zone);
    }

    /**
     * Removes a projection zone.
     */
    public static void removeZone(ProjectionZone zone) {
        ZONES.remove(zone);
    }
    
    /**
     * Removes a projection zone by name.
     * @return true if a zone was removed
     */
    public static boolean removeZoneByName(String name) {
        return ZONES.removeIf(z -> z.name.equals(name));
    }
    
    /**
     * Gets a zone by name.
     * @return the zone, or null if not found
     */
    public static ProjectionZone getZoneByName(String name) {
        for (ProjectionZone zone : ZONES) {
            if (zone.name.equals(name)) {
                return zone;
            }
        }
        return null;
    }

    /**
     * Clears all projection zones.
     */
    public static void clearZones() {
        ZONES.clear();
    }

    /**
     * Gets an unmodifiable list of all active projection zones.
     */
    public static List<ProjectionZone> getZones() {
        return Collections.unmodifiableList(ZONES);
    }

    /**
     * Initializes the default test zones.
     * Room size: 110 (X) x 150 (Y) x 150 (Z)
     */


    @Override
    public String toString() {
        return "ProjectionZone{name='" + name + "', min=" + min + ", max=" + max + ", center=(" + centerX + ", " + centerZ + ")}";
    }
}
