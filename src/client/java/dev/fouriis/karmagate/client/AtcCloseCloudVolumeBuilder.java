package dev.fouriis.karmagate.client;

import net.minecraft.util.math.MathHelper;

import java.util.Arrays;
import java.util.BitSet;

/** Builds a block-accurate cloud volume at a bounded profile-derived resolution. */
public final class AtcCloseCloudVolumeBuilder {
    public static final int PROFILE_COUNT = 3;
    public static final int VARIANTS_PER_PAIR = 1;
    private static final float[] SHELL_OPACITY = {0.25f, 0.50f, 0.75f, 1.0f};

    public AtcCloudMesh build(AtcCloudProfile south,
                              AtcCloudProfile north,
                              AtcCloudProfile west,
                              AtcCloudProfile east,
                              AtcCloudNoiseMap noise,
                              int variantIndex,
                              int requestedResolution,
                              float isoLevel,
                              float breakupAmount,
                              float warpAmount,
                              float noiseInfluence,
                              float rounding,
                              float ignoredDepthScale) {
        int nativeX = Math.min(south.width(), north.width());
        int nativeY = Math.min(
                Math.min(south.height(), north.height()),
                Math.min(west.height(), east.height())
        );
        int nativeZ = Math.min(west.width(), east.width());
        int sizeX = MathHelper.clamp(requestedResolution, 128, nativeX);
        float resolutionScale = sizeX / (float) Math.max(1, nativeX);
        int sizeY = Math.max(24, Math.round(nativeY * resolutionScale));
        int sizeZ = Math.max(128, Math.round(nativeZ * resolutionScale));
        long seed = variantSeed(variantIndex);
        BitSet occupied = buildOccupancy(
                south,
                north,
                west,
                east,
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

    private BitSet buildOccupancy(AtcCloudProfile south,
                                  AtcCloudProfile north,
                                  AtcCloudProfile west,
                                  AtcCloudProfile east,
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
        short[] edgeBlendX = new short[horizontalSize];
        short[] edgeBlendZ = new short[horizontalSize];
        byte[] breakup = new byte[horizontalSize];
        byte[] puffGate = new byte[horizontalSize];
        float phaseX = hashUnit(seed ^ 0x43A31D7BL) * 32.0f;
        float phaseZ = hashUnit(seed ^ 0x7F4A7C15L) * 32.0f;
        float noiseStrength = MathHelper.clamp(noiseInfluence, 0.0f, 4.0f);
        float horizontalWarp = MathHelper.clamp(
                MathHelper.clamp(warpAmount, 0.0f, 1.0f) * noiseStrength,
                0.0f,
                1.0f
        );
        int maxWarpX = Math.round(
                horizontalWarp * sizeX * 0.22f
        );
        int maxWarpZ = Math.round(
                horizontalWarp * sizeZ * 0.22f
        );

        for (int z = 0; z < sizeZ; z++) {
            float w = z / (float) Math.max(1, sizeZ - 1);
            for (int x = 0; x < sizeX; x++) {
                float u = x / (float) Math.max(1, sizeX - 1);
                float noiseX = fbm2D(noise, u, w, phaseX, phaseZ);
                float noiseZ = fbm2D(noise, u, w, phaseX + 0.37f, phaseZ + 0.61f);
                int horizontalIndex = z * sizeX + x;
                int offsetX = Math.round((noiseX * 2.0f - 1.0f) * maxWarpX);
                int offsetZ = Math.round((noiseZ * 2.0f - 1.0f) * maxWarpZ);
                int warpedGridX = Math.floorMod(x + offsetX, Math.max(1, sizeX - 1));
                int warpedGridZ = Math.floorMod(z + offsetZ, Math.max(1, sizeZ - 1));
                int profileX = Math.round(
                        warpedGridX / (float) Math.max(1, sizeX - 1)
                                * (south.width() - 1)
                );
                int profileZ = Math.round(
                        warpedGridZ / (float) Math.max(1, sizeZ - 1)
                                * (west.width() - 1)
                );
                warpedX[horizontalIndex] = (short) profileX;
                warpedZ[horizontalIndex] = (short) profileZ;
                float edgeLockX = MathHelper.sin((float) Math.PI * u);
                float edgeLockZ = MathHelper.sin((float) Math.PI * w);
                edgeLockX *= edgeLockX;
                edgeLockZ *= edgeLockZ;
                float blendWarp = horizontalWarp * 0.32f;
                float warpedBlendX = MathHelper.clamp(
                        u + (noiseZ * 2.0f - 1.0f) * blendWarp * edgeLockX,
                        0.0f,
                        1.0f
                );
                float warpedBlendZ = MathHelper.clamp(
                        w + (noiseX * 2.0f - 1.0f) * blendWarp * edgeLockZ,
                        0.0f,
                        1.0f
                );
                edgeBlendX[horizontalIndex] = (short) Math.round(warpedBlendX * 65535.0f);
                edgeBlendZ[horizontalIndex] = (short) Math.round(warpedBlendZ * 65535.0f);
                float detailNoise = fbm2D(
                        noise,
                        u,
                        w,
                        phaseX + 1.73f,
                        phaseZ + 4.19f
                );
                breakup[horizontalIndex] = (byte) MathHelper.clamp(
                        Math.round(detailNoise * 255.0f),
                        0,
                        255
                );
                float formationNoise = MathHelper.clamp(
                        detailNoise * 0.55f + noiseX * 0.25f + noiseZ * 0.20f,
                        0.0f,
                        1.0f
                );
                float formationGate = smoothstep(0.18f, 0.78f, formationNoise);
                float gate = MathHelper.lerp(
                        MathHelper.clamp(noiseStrength / 3.0f, 0.0f, 1.0f),
                        1.0f,
                        0.08f + formationGate * 0.92f
                );
                puffGate[horizontalIndex] = (byte) MathHelper.clamp(
                        Math.round(gate * 255.0f),
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
        float[] southPixels = south.densityPixels();
        float[] northPixels = north.densityPixels();
        float[] westPixels = west.densityPixels();
        float[] eastPixels = east.densityPixels();
        for (int y = 0; y < sizeY; y++) {
            float sourceV = (sizeY - 1 - y) / (float) Math.max(1, sizeY - 1);
            int southRow = Math.round(sourceV * (south.height() - 1)) * south.width();
            int northRow = Math.round(sourceV * (north.height() - 1)) * north.width();
            int westRow = Math.round(sourceV * (west.height() - 1)) * west.width();
            int eastRow = Math.round(sourceV * (east.height() - 1)) * east.width();
            for (int z = 0; z < sizeZ; z++) {
                int horizontalBase = z * sizeX;
                for (int x = 0; x < sizeX; x++) {
                    int horizontalIndex = horizontalBase + x;
                    float blendX = (edgeBlendX[horizontalIndex] & 0xFFFF) / 65535.0f;
                    float blendZ = (edgeBlendZ[horizontalIndex] & 0xFFFF) / 65535.0f;
                    int profileX = warpedX[horizontalIndex] & 0xFFFF;
                    int profileZ = warpedZ[horizontalIndex] & 0xFFFF;
                    float frontDensity = MathHelper.lerp(
                            blendZ,
                            southPixels[southRow + profileX],
                            northPixels[northRow + profileX]
                    );
                    float sideDensity = MathHelper.lerp(
                            blendX,
                            westPixels[westRow + profileZ],
                            eastPixels[eastRow + profileZ]
                    );
                    float frontPlane = MathHelper.lerp(
                            blendZ,
                            south.planeV(),
                            north.planeV()
                    );
                    float sidePlane = MathHelper.lerp(
                            blendX,
                            west.planeV(),
                            east.planeV()
                    );
                    float planeV = (frontPlane + sidePlane) * 0.5f;
                    float elevatedWeight = smoothstep(
                            0.025f,
                            0.17f,
                            Math.max(0.0f, planeV - sourceV)
                    );
                    float product = Math.max(0.0f, frontDensity * sideDensity);
                    float intersection = (float) Math.sqrt(product);
                    float roundedIntersection = MathHelper.lerp(
                            roundingStrength * 0.55f,
                            intersection,
                            product
                    );
                    float support = roundedIntersection * 0.80f
                            + Math.min(frontDensity, sideDensity) * 0.20f;
                    float layoutNoise = (breakup[horizontalIndex] & 0xFF) / 255.0f;
                    float noiseSelectedSupport = MathHelper.lerp(
                            smoothstep(0.12f, 0.88f, layoutNoise),
                            frontDensity,
                            sideDensity
                    );
                    float degridStrength = MathHelper.clamp(
                            noiseStrength / 3.0f,
                            0.0f,
                            1.0f
                    );
                    support = Math.max(
                            support,
                            noiseSelectedSupport * 0.66f * degridStrength
                    );
                    // Keep the broad lower shelf connected. Above it, the
                    // shared, non-separable noise gate breaks up the visual-
                    // hull X-by-Z grid while all four authored edge profiles
                    // remain exact at their respective tile boundaries.
                    float broadBaseSupport = (frontDensity + sideDensity) * 0.425f;
                    support = Math.max(
                            support,
                            broadBaseSupport * (1.0f - elevatedWeight)
                    );
                    float density = smoothstep(0.04f, 0.72f, support);
                    float pairedPuffs = (puffGate[horizontalIndex] & 0xFF) / 255.0f;
                    density *= MathHelper.lerp(
                            elevatedWeight,
                            1.0f,
                            0.06f + pairedPuffs * 0.94f
                    );
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

    private static float fbm2D(AtcCloudNoiseMap noise,
                               float u,
                               float v,
                               float phaseU,
                               float phaseV) {
        float first = noise.sample(u + phaseU, v + phaseV);
        float second = noise.sample(
                u * 2.03f + phaseU * 0.71f + 0.19f,
                v * 2.03f + phaseV * 0.71f - 0.27f
        );
        return first * 0.67f + second * 0.33f;
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
                        // Horizontal tile boundaries are sampled explicitly
                        // from shared world-grid line profiles. Both tiles
                        // therefore own identical occupancy on the boundary,
                        // so neither may emit an internal wall there.
                        if ((axis == 0 || axis == 2)
                                && (x[axis] < 0 || x[axis] >= dimensions[axis] - 1)) {
                            mask[maskIndex++] = 0;
                            continue;
                        }
                        int a = shellDepth(
                                layers,
                                x[0],
                                x[1],
                                x[2],
                                sizeX,
                                sizeY,
                                sizeZ
                        );
                        int b = shellDepth(
                                layers,
                                x[0] + q[0],
                                x[1] + q[1],
                                x[2] + q[2],
                                sizeX,
                                sizeY,
                                sizeZ
                        );
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

        int[] indices = flattenLayers(layerIndices);

        return new AtcCloudMesh(
                positions.toArray(),
                normals.toArray(),
                heights.toArray(),
                shades.toArray(),
                thicknesses.toArray(),
                indices,
                1.0f,
                1.0f,
                1.0f,
                new float[0],
                sizeX,
                sizeY,
                sizeZ
        );
    }

    private static int[] flattenLayers(IntList[] layers) {
        int total = 0;
        for (IntList layer : layers) {
            total = Math.addExact(total, layer.size);
        }
        int[] result = new int[total];
        int write = 0;
        for (int layer = layers.length - 1; layer >= 0; layer--) {
            IntList source = layers[layer];
            System.arraycopy(source.values, 0, result, write, source.size);
            write += source.size;
            layers[layer] = null;
        }
        return result;
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
        // Y remains a real volume boundary. X/Z boundary samples are shared
        // by adjacent tiles, so an out-of-tile neighbour has the same state as
        // the boundary voxel instead of empty space.
        for (int z = 0; z < sizeZ; z++) {
            int plane = z * planeSize;
            result.clear(plane, plane + sizeX);
            result.clear(plane + (sizeY - 1) * sizeX, plane + sizeY * sizeX);
        }
        for (int z = 0; z < sizeZ; z++) {
            for (int y = 1; y < sizeY - 1; y++) {
                setSeamErosion(result, source, 0, y, z, sizeX, sizeY, sizeZ);
                setSeamErosion(result, source, sizeX - 1, y, z, sizeX, sizeY, sizeZ);
            }
        }
        for (int x = 0; x < sizeX; x++) {
            for (int y = 1; y < sizeY - 1; y++) {
                setSeamErosion(result, source, x, y, 0, sizeX, sizeY, sizeZ);
                setSeamErosion(result, source, x, y, sizeZ - 1, sizeX, sizeY, sizeZ);
            }
        }
        return result;
    }

    private static void setSeamErosion(BitSet result,
                                       BitSet source,
                                       int x,
                                       int y,
                                       int z,
                                       int sizeX,
                                       int sizeY,
                                       int sizeZ) {
        int center = index(x, y, z, sizeX, sizeY);
        boolean survives = source.get(center)
                && source.get(index(Math.max(0, x - 1), y, z, sizeX, sizeY))
                && source.get(index(Math.min(sizeX - 1, x + 1), y, z, sizeX, sizeY))
                && source.get(index(x, y - 1, z, sizeX, sizeY))
                && source.get(index(x, y + 1, z, sizeX, sizeY))
                && source.get(index(x, y, Math.max(0, z - 1), sizeX, sizeY))
                && source.get(index(x, y, Math.min(sizeZ - 1, z + 1), sizeX, sizeY));
        result.set(center, survives);
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
        return hashUnit(variantSeed(variantIndex) ^ 0x43A31D7BL) * 32.0f;
    }

    public static float warpPhaseZ(int frontIndex, int sideIndex, int variantIndex) {
        return hashUnit(variantSeed(variantIndex) ^ 0x7F4A7C15L) * 32.0f;
    }

    private static long variantSeed(int variantIndex) {
        return hash(17, 31, variantIndex + 73);
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
