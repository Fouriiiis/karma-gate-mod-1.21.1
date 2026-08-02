package dev.fouriis.karmagate.client;

import net.minecraft.util.math.MathHelper;

import java.util.Arrays;
import java.util.BitSet;

/** Builds a block-accurate cloud volume at the source profile's native resolution. */
public final class AtcCloseCloudVolumeBuilder {
    public static final int PROFILE_COUNT = 3;
    public static final int VARIANTS_PER_PAIR = 1;
    private static final float[] SHELL_OPACITY = {0.25f, 0.50f, 0.75f, 1.0f};

    public AtcCloudMesh build(AtcCloudProfile front,
                              AtcCloudProfile side,
                              AtcCloudNoiseMap noise,
                              int frontIndex,
                              int sideIndex,
                              int variantIndex,
                              int ignoredResolution,
                              float isoLevel,
                              float breakupAmount,
                              float warpAmount,
                              float noiseInfluence,
                              float rounding,
                              float ignoredDepthScale) {
        int sizeX = front.width();
        int sizeY = Math.min(front.height(), side.height());
        int sizeZ = side.width();
        long seed = variantSeed(frontIndex, sideIndex, variantIndex);
        BitSet occupied = buildOccupancy(
                front,
                side,
                noise,
                sizeX,
                sizeY,
                sizeZ,
                seed,
                isoLevel,
                breakupAmount,
                warpAmount,
                noiseInfluence,
                rounding
        );
        return greedyMesh(occupied, sizeX, sizeY, sizeZ);
    }

    private BitSet buildOccupancy(AtcCloudProfile front,
                                  AtcCloudProfile side,
                                  AtcCloudNoiseMap noise,
                                  int sizeX,
                                  int sizeY,
                                  int sizeZ,
                                  long seed,
                                  float isoLevel,
                                  float breakupAmount,
                                  float warpAmount,
                                  float noiseInfluence,
                                  float rounding) {
        int horizontalSize = Math.multiplyExact(sizeX, sizeZ);
        short[] warpedX = new short[horizontalSize];
        short[] warpedZ = new short[horizontalSize];
        byte[] breakup = new byte[horizontalSize];
        float phaseX = hashUnit(seed ^ 0x43A31D7BL) * 32.0f;
        float phaseZ = hashUnit(seed ^ 0x7F4A7C15L) * 32.0f;
        float noiseStrength = MathHelper.clamp(noiseInfluence, 0.0f, 4.0f);
        int maxWarpX = Math.round(
                MathHelper.clamp(warpAmount, 0.0f, 1.0f) * noiseStrength * sizeX * 0.08f
        );
        int maxWarpZ = Math.round(
                MathHelper.clamp(warpAmount, 0.0f, 1.0f) * noiseStrength * sizeZ * 0.08f
        );

        for (int z = 0; z < sizeZ; z++) {
            float w = (z + 0.5f) / sizeZ;
            for (int x = 0; x < sizeX; x++) {
                float u = (x + 0.5f) / sizeX;
                float noiseX = noise.sample(u + phaseX, w + phaseZ);
                float noiseZ = noise.sample(u + phaseX + 0.37f, w + phaseZ + 0.61f);
                int horizontalIndex = z * sizeX + x;
                int offsetX = Math.round((noiseX * 2.0f - 1.0f) * maxWarpX);
                int offsetZ = Math.round((noiseZ * 2.0f - 1.0f) * maxWarpZ);
                warpedX[horizontalIndex] = (short) Math.floorMod(x + offsetX, sizeX);
                warpedZ[horizontalIndex] = (short) Math.floorMod(z + offsetZ, sizeZ);
                breakup[horizontalIndex] = (byte) MathHelper.clamp(
                        Math.round(noiseX * 255.0f),
                        0,
                        255
                );
            }
        }

        int voxelCount = Math.multiplyExact(horizontalSize, sizeY);
        BitSet occupied = new BitSet(voxelCount);
        float threshold = MathHelper.clamp(isoLevel, 0.05f, 0.90f);
        float breakupStrength = MathHelper.clamp(breakupAmount, 0.0f, 0.35f) * noiseStrength;
        float roundingStrength = MathHelper.clamp(rounding, 0.0f, 1.0f);
        float[] frontPixels = front.densityPixels();
        float[] sidePixels = side.densityPixels();
        for (int y = 0; y < sizeY; y++) {
            int sourceY = sizeY - 1 - y;
            int frontRow = sourceY * sizeX;
            int sideRow = sourceY * sizeZ;
            for (int z = 0; z < sizeZ; z++) {
                int horizontalBase = z * sizeX;
                for (int x = 0; x < sizeX; x++) {
                    int horizontalIndex = horizontalBase + x;
                    int profileX = warpedX[horizontalIndex] & 0xFFFF;
                    int profileZ = warpedZ[horizontalIndex] & 0xFFFF;
                    float frontDensity = frontPixels[frontRow + profileX];
                    float sideDensity = sidePixels[sideRow + profileZ];
                    float product = Math.max(0.0f, frontDensity * sideDensity);
                    float intersection = (float) Math.sqrt(product);
                    float roundedIntersection = MathHelper.lerp(
                            roundingStrength * 0.55f,
                            intersection,
                            product
                    );
                    float support = roundedIntersection * 0.80f
                            + Math.min(frontDensity, sideDensity) * 0.20f;
                    float density = smoothstep(0.04f, 0.72f, support);
                    if (density > 0.001f && breakupStrength > 0.0f) {
                        float signedNoise = ((breakup[horizontalIndex] & 0xFF) / 255.0f) * 2.0f - 1.0f;
                        float surfaceWeight = 1.0f - Math.abs(density * 2.0f - 1.0f);
                        density += signedNoise
                                * breakupStrength
                                * (0.20f + surfaceWeight * 0.80f);
                    }
                    if (density >= threshold) {
                        occupied.set(index(x, y, z, sizeX, sizeY));
                    }
                }
            }
        }
        return occupied;
    }

    private AtcCloudMesh greedyMesh(BitSet occupied, int sizeX, int sizeY, int sizeZ) {
        BitSet[] layers = new BitSet[SHELL_OPACITY.length];
        layers[0] = occupied;
        for (int layer = 1; layer < layers.length; layer++) {
            layers[layer] = erode(layers[layer - 1], sizeX, sizeY, sizeZ);
        }
        int[] dimensions = {sizeX, sizeY, sizeZ};
        int maxMaskSize = Math.max(
                sizeX * sizeY,
                Math.max(sizeX * sizeZ, sizeY * sizeZ)
        );
        byte[] mask = new byte[maxMaskSize];
        FloatList positions = new FloatList();
        FloatList normals = new FloatList();
        FloatList heights = new FloatList();
        FloatList shades = new FloatList();
        FloatList thicknesses = new FloatList();
        IntList[] layerIndices = new IntList[SHELL_OPACITY.length];
        for (int layer = 0; layer < layerIndices.length; layer++) {
            layerIndices[layer] = new IntList();
        }

        int[] x = new int[3];
        int[] q = new int[3];
        int[] du = new int[3];
        int[] dv = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
            q[0] = q[1] = q[2] = 0;
            q[axis] = 1;
            x[axis] = -1;
            while (x[axis] < dimensions[axis]) {
                int maskIndex = 0;
                for (x[v] = 0; x[v] < dimensions[v]; x[v]++) {
                    for (x[u] = 0; x[u] < dimensions[u]; x[u]++) {
                        int a = x[axis] >= 0
                                ? shellDepth(layers, x[0], x[1], x[2], sizeX, sizeY, sizeZ)
                                : -1;
                        int b = x[axis] < dimensions[axis] - 1
                                ? shellDepth(
                                layers,
                                x[0] + q[0],
                                x[1] + q[1],
                                x[2] + q[2],
                                sizeX,
                                sizeY,
                                sizeZ
                        )
                                : -1;
                        mask[maskIndex++] = a == b
                                ? 0
                                : (byte) (a > b ? a + 1 : -(b + 1));
                    }
                }
                x[axis]++;

                maskIndex = 0;
                for (int j = 0; j < dimensions[v]; j++) {
                    for (int i = 0; i < dimensions[u]; ) {
                        byte face = mask[maskIndex];
                        if (face == 0) {
                            i++;
                            maskIndex++;
                            continue;
                        }
                        int width = 1;
                        while (i + width < dimensions[u]
                                && mask[maskIndex + width] == face) {
                            width++;
                        }
                        int height = 1;
                        heightLoop:
                        while (j + height < dimensions[v]) {
                            int row = maskIndex + height * dimensions[u];
                            for (int k = 0; k < width; k++) {
                                if (mask[row + k] != face) {
                                    break heightLoop;
                                }
                            }
                            height++;
                        }

                        x[u] = i;
                        x[v] = j;
                        du[0] = du[1] = du[2] = 0;
                        dv[0] = dv[1] = dv[2] = 0;
                        du[u] = width;
                        dv[v] = height;
                        appendQuad(
                                positions,
                                normals,
                                heights,
                                shades,
                                thicknesses,
                                layerIndices,
                                x,
                                du,
                                dv,
                                axis,
                                face,
                                dimensions
                        );

                        for (int h = 0; h < height; h++) {
                            Arrays.fill(
                                    mask,
                                    maskIndex + h * dimensions[u],
                                    maskIndex + h * dimensions[u] + width,
                                    (byte) 0
                            );
                        }
                        i += width;
                        maskIndex += width;
                    }
                }
            }
        }

        IntList indices = new IntList();
        for (int layer = layerIndices.length - 1; layer >= 0; layer--) {
            indices.addAll(layerIndices[layer]);
        }

        return new AtcCloudMesh(
                positions.toArray(),
                normals.toArray(),
                heights.toArray(),
                shades.toArray(),
                thicknesses.toArray(),
                indices.toArray(),
                1.0f,
                1.0f,
                1.0f,
                new float[0],
                0,
                0,
                0
        );
    }

    private void appendQuad(FloatList positions,
                            FloatList normals,
                            FloatList heights,
                            FloatList shades,
                            FloatList thicknesses,
                            IntList[] layerIndices,
                            int[] origin,
                            int[] du,
                            int[] dv,
                            int axis,
                            byte face,
                            int[] dimensions) {
        int layer = Math.abs(face) - 1;
        int direction = face > 0 ? 1 : -1;
        int[][] corners;
        if (direction > 0) {
            corners = new int[][]{
                    copy(origin),
                    add(origin, du),
                    add(add(origin, du), dv),
                    add(origin, dv)
            };
        } else {
            corners = new int[][]{
                    copy(origin),
                    add(origin, dv),
                    add(add(origin, du), dv),
                    add(origin, du)
            };
        }
        int baseVertex = positions.size() / 3;
        float nx = axis == 0 ? direction : 0.0f;
        float ny = axis == 1 ? direction : 0.0f;
        float nz = axis == 2 ? direction : 0.0f;
        for (int[] corner : corners) {
            float localX = corner[0] / (float) dimensions[0] - 0.5f;
            float localY = corner[1] / (float) dimensions[1];
            float localZ = corner[2] / (float) dimensions[2] - 0.5f;
            positions.add(localX, localY, localZ);
            normals.add(nx, ny, nz);
            heights.add(localY);
            shades.add(1.0f);
            thicknesses.add(SHELL_OPACITY[layer]);
        }
        IntList indices = layerIndices[layer];
        indices.add(baseVertex, baseVertex + 1, baseVertex + 2);
        indices.add(baseVertex, baseVertex + 2, baseVertex + 3);
    }

    private static int shellDepth(BitSet[] layers,
                                  int x,
                                  int y,
                                  int z,
                                  int sizeX,
                                  int sizeY,
                                  int sizeZ) {
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            return -1;
        }
        int voxelIndex = index(x, y, z, sizeX, sizeY);
        for (int layer = layers.length - 1; layer >= 0; layer--) {
            if (layers[layer].get(voxelIndex)) {
                return layer;
            }
        }
        return -1;
    }

    private static BitSet erode(BitSet source, int sizeX, int sizeY, int sizeZ) {
        int planeSize = Math.multiplyExact(sizeX, sizeY);
        int voxelCount = Math.multiplyExact(planeSize, sizeZ);
        int wordCount = (voxelCount + 63) >>> 6;
        long[] sourceWords = Arrays.copyOf(source.toLongArray(), wordCount);
        long[] resultWords = Arrays.copyOf(sourceWords, wordCount);
        andShiftedNeighbor(resultWords, sourceWords, 1);
        andShiftedNeighbor(resultWords, sourceWords, -1);
        andShiftedNeighbor(resultWords, sourceWords, sizeX);
        andShiftedNeighbor(resultWords, sourceWords, -sizeX);
        andShiftedNeighbor(resultWords, sourceWords, planeSize);
        andShiftedNeighbor(resultWords, sourceWords, -planeSize);

        BitSet result = BitSet.valueOf(resultWords);
        result.clear(0, Math.min(planeSize, voxelCount));
        result.clear(Math.max(0, voxelCount - planeSize), voxelCount);
        for (int z = 1; z < sizeZ - 1; z++) {
            int plane = z * planeSize;
            result.clear(plane, plane + sizeX);
            result.clear(plane + (sizeY - 1) * sizeX, plane + sizeY * sizeX);
            for (int y = 1; y < sizeY - 1; y++) {
                int row = plane + y * sizeX;
                result.clear(row);
                result.clear(row + sizeX - 1);
            }
        }
        return result;
    }

    /** result[i] &= source[i + offset], using word shifts instead of voxel loops. */
    private static void andShiftedNeighbor(long[] result, long[] source, int offset) {
        int amount = Math.abs(offset);
        int wordOffset = amount >>> 6;
        int bitOffset = amount & 63;
        for (int word = 0; word < result.length; word++) {
            long shifted;
            if (offset > 0) {
                int sourceWord = word + wordOffset;
                shifted = sourceWord < source.length
                        ? source[sourceWord] >>> bitOffset
                        : 0L;
                if (bitOffset != 0 && sourceWord + 1 < source.length) {
                    shifted |= source[sourceWord + 1] << (64 - bitOffset);
                }
            } else {
                int sourceWord = word - wordOffset;
                shifted = sourceWord >= 0
                        ? source[sourceWord] << bitOffset
                        : 0L;
                if (bitOffset != 0 && sourceWord - 1 >= 0) {
                    shifted |= source[sourceWord - 1] >>> (64 - bitOffset);
                }
            }
            result[word] &= shifted;
        }
    }

    private static int index(int x, int y, int z, int sizeX, int sizeY) {
        return (z * sizeY + y) * sizeX + x;
    }

    private static int[] copy(int[] value) {
        return new int[]{value[0], value[1], value[2]};
    }

    private static int[] add(int[] left, int[] right) {
        return new int[]{left[0] + right[0], left[1] + right[1], left[2] + right[2]};
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = MathHelper.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static long hash(int x, int y, int z) {
        long h = 1469598103934665603L;
        h = (h ^ x) * 1099511628211L;
        h = (h ^ y) * 1099511628211L;
        h = (h ^ z) * 1099511628211L;
        return h;
    }

    public static float warpPhaseX(int frontIndex, int sideIndex, int variantIndex) {
        return hashUnit(variantSeed(frontIndex, sideIndex, variantIndex) ^ 0x43A31D7BL) * 32.0f;
    }

    public static float warpPhaseZ(int frontIndex, int sideIndex, int variantIndex) {
        return hashUnit(variantSeed(frontIndex, sideIndex, variantIndex) ^ 0x7F4A7C15L) * 32.0f;
    }

    private static long variantSeed(int frontIndex, int sideIndex, int variantIndex) {
        return hash(frontIndex + 17, sideIndex + 31, variantIndex + 73);
    }

    private static float hashUnit(long seed) {
        long h = seed;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1 << 24);
    }

    private static final class FloatList {
        private float[] values = new float[4096];
        private int size;

        private void add(float value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private void add(float a, float b, float c) {
            ensureCapacity(size + 3);
            values[size++] = a;
            values[size++] = b;
            values[size++] = c;
        }

        private int size() {
            return size;
        }

        private void ensureCapacity(int required) {
            if (required > values.length) {
                int next = values.length;
                while (next < required) next *= 2;
                values = Arrays.copyOf(values, next);
            }
        }

        private float[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    private static final class IntList {
        private int[] values = new int[8192];
        private int size;

        private void add(int a, int b, int c) {
            ensureCapacity(size + 3);
            values[size++] = a;
            values[size++] = b;
            values[size++] = c;
        }

        private void addAll(IntList other) {
            ensureCapacity(size + other.size);
            System.arraycopy(other.values, 0, values, size, other.size);
            size += other.size;
        }

        private void ensureCapacity(int required) {
            if (required > values.length) {
                int next = values.length;
                while (next < required) next *= 2;
                values = Arrays.copyOf(values, next);
            }
        }

        private int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
