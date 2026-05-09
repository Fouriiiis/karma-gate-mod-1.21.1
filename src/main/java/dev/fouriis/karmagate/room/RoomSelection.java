package dev.fouriis.karmagate.room;

import net.minecraft.util.math.BlockPos;

/**
 * Holds two selection corners for a room.
 */
public record RoomSelection(BlockPos corner1, BlockPos corner2) {

    public boolean isComplete() {
        return corner1 != null && corner2 != null;
    }

    public RoomSelection withCorner1(BlockPos pos) {
        return new RoomSelection(pos, corner2);
    }

    public RoomSelection withCorner2(BlockPos pos) {
        return new RoomSelection(corner1, pos);
    }
}
