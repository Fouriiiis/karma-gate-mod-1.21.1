package dev.fouriis.karmagate.gridproject;

import net.minecraft.util.math.BlockPos;

/**
 * Server-side data container for a named projection zone.
 * This is the authoritative source of truth for projection zones.
 */
public record ProjectionZoneData(String name, BlockPos corner1, BlockPos corner2,
                                  int swarmerCount, boolean drawCircles, boolean drawGrid, boolean drawStarMatrix) {
    
    /** Default swarmer count when not specified. */
    public static final int DEFAULT_SWARMER_COUNT = 600;
    
    /**
     * Creates zone data from coordinate values with all parameters.
     */
    public static ProjectionZoneData of(String name, int x1, int y1, int z1, int x2, int y2, int z2,
                                         int swarmerCount, boolean drawCircles, boolean drawGrid, boolean drawStarMatrix) {
        return new ProjectionZoneData(name, new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2),
                                      swarmerCount, drawCircles, drawGrid, drawStarMatrix);
    }
    
    /**
     * Creates zone data from coordinate values with defaults (backwards compatible).
     */
    public static ProjectionZoneData of(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        return of(name, x1, y1, z1, x2, y2, z2, DEFAULT_SWARMER_COUNT, true, true, false);
    }
    
    /**
     * Gets the minimum corner (normalized).
     */
    public BlockPos getMin() {
        return new BlockPos(
            Math.min(corner1.getX(), corner2.getX()),
            Math.min(corner1.getY(), corner2.getY()),
            Math.min(corner1.getZ(), corner2.getZ())
        );
    }
    
    /**
     * Gets the maximum corner (normalized).
     */
    public BlockPos getMax() {
        return new BlockPos(
            Math.max(corner1.getX(), corner2.getX()),
            Math.max(corner1.getY(), corner2.getY()),
            Math.max(corner1.getZ(), corner2.getZ())
        );
    }
}
