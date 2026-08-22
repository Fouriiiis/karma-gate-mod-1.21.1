package dev.fouriis.karmagate.client.rot;

import dev.fouriis.karmagate.block.ModBlocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores rot block positions per chunk (packed to long).
 * Used by the RotWorldRenderer to know where to render corruption visuals.
 */
public final class RotRenderCache {
    // key = ChunkPos.toLong(x,z), value = list of packed BlockPos longs.
    private static final Map<Long, List<Long>> CHUNK_TO_POSITIONS = new HashMap<>();

    public static void onChunkLoad(ClientWorld world, Chunk chunk) {
        long key = ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z);

        List<Long> positions = new ArrayList<>();

        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) continue;

            int sectionBaseY = bottomY + (sectionIndex * 16);

            for (int localY = 0; localY < 16; localY++) {
                int y = sectionBaseY + localY;

                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        var state = section.getBlockState(localX, localY, localZ);
                        if (state.getBlock() == ModBlocks.ROT_BLOCK) {
                            int x = (chunk.getPos().x << 4) + localX;
                            int z = (chunk.getPos().z << 4) + localZ;
                            positions.add(BlockPos.asLong(x, y, z));
                        }
                    }
                }
            }
        }

        if (positions.isEmpty()) {
            CHUNK_TO_POSITIONS.remove(key);
        } else {
            CHUNK_TO_POSITIONS.put(key, positions);
        }
        RotWorldRenderer.markDirty();
    }

    public static void onChunkUnload(ClientWorld world, Chunk chunk) {
        long key = ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z);
        CHUNK_TO_POSITIONS.remove(key);
        RotWorldRenderer.markDirty();
    }

    public static List<Long> getPositionsForChunk(int chunkX, int chunkZ) {
        long key = ChunkPos.toLong(chunkX, chunkZ);
        List<Long> list = CHUNK_TO_POSITIONS.get(key);
        return list == null ? Collections.emptyList() : list;
    }

    public static void onBlockAdded(ClientWorld world, BlockPos pos) {
        if (world == null) return;
        if (world.getBlockState(pos).getBlock() != ModBlocks.ROT_BLOCK) return;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long key = ChunkPos.toLong(cx, cz);
        List<Long> list = CHUNK_TO_POSITIONS.get(key);
        if (list == null) {
            list = new ArrayList<>();
            CHUNK_TO_POSITIONS.put(key, list);
        }
        long lp = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
        if (!list.contains(lp)) list.add(lp);
        RotWorldRenderer.markDirty();
    }

    public static void onBlockRemoved(ClientWorld world, BlockPos pos) {
        if (world == null) return;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long key = ChunkPos.toLong(cx, cz);
        List<Long> list = CHUNK_TO_POSITIONS.get(key);
        if (list == null) return;
        long lp = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
        list.remove(lp);
        if (list.isEmpty()) CHUNK_TO_POSITIONS.remove(key);
        RotWorldRenderer.markDirty();
    }

    public static void clearAll() {
        CHUNK_TO_POSITIONS.clear();
    }

    private RotRenderCache() {}
}
