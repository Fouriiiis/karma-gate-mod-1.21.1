package dev.fouriis.karmagate.gridproject;

import net.minecraft.util.math.BlockPos;

/**
 * Server-side data for a projected StarMatrix.
 * Each StarMatrix belongs to a projection zone and is anchored at a world position.
 */
public record StarMatrixData(String name, String zoneName, BlockPos position) {

    public static StarMatrixData of(String name, String zoneName, int x, int y, int z) {
        return new StarMatrixData(name, zoneName, new BlockPos(x, y, z));
    }
}
