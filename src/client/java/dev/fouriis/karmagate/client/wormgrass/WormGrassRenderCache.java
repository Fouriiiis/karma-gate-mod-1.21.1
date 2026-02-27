package dev.fouriis.karmagate.client.wormgrass;

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
 * Stores wormgrass block positions per chunk (packed to long).
 * Simple implementation using standard Java collections so it compiles without extra deps.
 */
public final class WormGrassRenderCache {
    // key = ChunkPos.toLong(x,z), value = list of packed BlockPos longs.
    private static final Map<Long, List<Long>> CHUNK_TO_POSITIONS = new HashMap<>();

    /** Monotonically increasing version — bumped on every chunk/block change. */
    private static volatile int dirtyVersion = 0;

    public static int getDirtyVersion() {
        return dirtyVersion;
    }

    public static void onChunkLoad(ClientWorld world, Chunk chunk) {
        long key = ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z);

        List<Long> positions = new ArrayList<>();

        // Scan the chunk sections and record worm grass blocks.
        ChunkSection[] sections = chunk.getSectionArray(); // available in 1.21.1
        int bottomY = chunk.getBottomY(); // base y of chunk
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) continue;

            int sectionBaseY = bottomY + (sectionIndex * 16);

            for (int localY = 0; localY < 16; localY++) {
                int y = sectionBaseY + localY;

                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        var state = section.getBlockState(localX, localY, localZ);
                        if (state.getBlock() == ModBlocks.WORM_GRASS) {
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
        dirtyVersion++;
    }

    public static void onChunkUnload(ClientWorld world, Chunk chunk) {
        long key = ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z);
        CHUNK_TO_POSITIONS.remove(key);
        dirtyVersion++;
    }

    public static List<Long> getPositionsForChunk(int chunkX, int chunkZ) {
        long key = ChunkPos.toLong(chunkX, chunkZ);
        List<Long> list = CHUNK_TO_POSITIONS.get(key);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * Called when a wormgrass block is placed client-side to add the single
     * position to the chunk cache without rescanning the whole chunk.
     */
    public static void onBlockAdded(ClientWorld world, BlockPos pos) {
        if (world == null) return;
        if (world.getBlockState(pos).getBlock() != dev.fouriis.karmagate.block.ModBlocks.WORM_GRASS) return;
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
        dirtyVersion++;
    }

    /**
     * Called when a wormgrass block is removed client-side to remove the single
     * position from the chunk cache.
     */
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
        dirtyVersion++;
    }

    /**
     * Check whether a packed block position exists anywhere in loaded chunks.
     * Used by the renderer to distinguish "real patch edge" from "edge of analysis set".
     */
    public static boolean hasPosition(int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        long key = ChunkPos.toLong(cx, cz);
        List<Long> list = CHUNK_TO_POSITIONS.get(key);
        if (list == null) return false;
        long packed = BlockPos.asLong(x, y, z);
        // Typically ≤256 entries per chunk-layer, so linear scan is fine.
        return list.contains(packed);
    }

    public static void clearAll() {
        CHUNK_TO_POSITIONS.clear();
        dirtyVersion++;
    }

    private WormGrassRenderCache() {}
}
