package dev.fouriis.karmagate.room;

import net.minecraft.util.math.BlockPos;

/**
 * Server-side data container for a named room.
 */
public record RoomData(String name, BlockPos corner1, BlockPos corner2, DangerType dangerType) {

    public static RoomData of(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        return of(name, x1, y1, z1, x2, y2, z2, DangerType.None);
    }

    public static RoomData of(
        String name,
        int x1,
        int y1,
        int z1,
        int x2,
        int y2,
        int z2,
        DangerType dangerType
    ) {
        DangerType resolvedDangerType = dangerType == null ? DangerType.None : dangerType;
        return new RoomData(
            name,
            new BlockPos(x1, y1, z1),
            new BlockPos(x2, y2, z2),
            resolvedDangerType
        );
    }

    public BlockPos getMin() {
        return new BlockPos(
            Math.min(corner1.getX(), corner2.getX()),
            Math.min(corner1.getY(), corner2.getY()),
            Math.min(corner1.getZ(), corner2.getZ())
        );
    }

    public BlockPos getMax() {
        return new BlockPos(
            Math.max(corner1.getX(), corner2.getX()),
            Math.max(corner1.getY(), corner2.getY()),
            Math.max(corner1.getZ(), corner2.getZ())
        );
    }

    public boolean contains(BlockPos pos) {
        BlockPos min = getMin();
        BlockPos max = getMax();
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
            && pos.getY() >= min.getY() && pos.getY() <= max.getY()
            && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
}
