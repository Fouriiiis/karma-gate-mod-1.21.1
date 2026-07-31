package dev.fouriis.karmagate.client;

import net.minecraft.util.math.MathHelper;

import java.util.Arrays;

public final class AtcCloseCloudVolumeBuilder {
    public static final int PROFILE_COUNT = 3;
    public static final int VARIANTS_PER_PAIR = 3;

    private static final int[][] CORNERS = {
            {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0},
            {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}
    };
    private static final int[][] TETRAS = {
            {0, 5, 1, 6},
            {0, 1, 2, 6},
            {0, 2, 3, 6},
            {0, 3, 7, 6},
            {0, 7, 4, 6},
            {0, 4, 5, 6}
    };

    public AtcCloudMesh build(AtcCloudProfile front,
                              AtcCloudProfile side,
                              AtcCloudNoiseMap noise,
                              int frontIndex,
                              int sideIndex,
                              int variantIndex,
                              int resolution,
                              float isoLevel,
                              float breakupAmount,
                              float warpAmount,
                              float depthScale) {
        int sizeX = MathHelper.clamp(resolution, 32, 160);
        int sizeY = MathHelper.clamp(Math.round(resolution * 0.54f), 28, 96);
        int sizeZ = MathHelper.clamp(Math.round(resolution * MathHelper.clamp(depthScale, 0.45f, 1.25f)), 32, 144);
        long seed = hash(frontIndex + 17, sideIndex + 31, variantIndex + 73);
        FieldData fields = buildField(
                front,
                side,
                noise,
                seed,
                sizeX,
                sizeY,
                sizeZ,
                breakupAmount,
                warpAmount
        );
        smoothField(fields.density, sizeX, sizeY, sizeZ, 1);
        return extractSurface(fields.density, fields.shade, sizeX, sizeY, sizeZ, isoLevel);
    }

    private FieldData buildField(AtcCloudProfile front,
                                 AtcCloudProfile side,
                                 AtcCloudNoiseMap noise,
                                 long seed,
                                 int sizeX,
                                 int sizeY,
                                 int sizeZ,
                                 float breakupAmount,
                                 float warpAmount) {
        float[] field = new float[sizeX * sizeY * sizeZ];
        float[] shadeField = new float[field.length];
        float phaseX = hashUnit(seed ^ 0x43A31D7BL) * 32.0f;
        float phaseZ = hashUnit(seed ^ 0x7F4A7C15L) * 32.0f;
        float[] warpedU = new float[sizeX * sizeZ];
        float[] warpedW = new float[sizeX * sizeZ];
        float[] columnNoise = new float[sizeX * sizeZ];
        float distortionStrength = MathHelper.clamp(warpAmount, 0.0f, 1.0f) * 0.55f;
        float breakupStrength = breakupAmount * MathHelper.clamp(warpAmount, 0.0f, 1.0f);

        // Build one horizontal warp map and reuse it for every Y sample. This
        // bends the X/Z layout without stretching, shifting, or otherwise
        // modifying the vertical cloud profiles.
        for (int z = 0; z < sizeZ; z++) {
            float w0 = z / (float) (sizeZ - 1);
            for (int x = 0; x < sizeX; x++) {
                float u0 = x / (float) (sizeX - 1);
                float noiseX = noise.sample(
                        u0 + phaseX,
                        w0 + phaseZ
                );
                float noiseZ = noise.sample(
                        u0 + phaseX + 0.37f,
                        w0 + phaseZ + 0.61f
                );
                int horizontalIndex = z * sizeX + x;
                warpedU[horizontalIndex] = u0
                        + (noiseX * 2.0f - 1.0f) * distortionStrength;
                warpedW[horizontalIndex] = w0
                        + (noiseZ * 2.0f - 1.0f) * distortionStrength;
                columnNoise[horizontalIndex] = noiseX;
            }
        }

        for (int z = 0; z < sizeZ; z++) {
            for (int y = 0; y < sizeY; y++) {
                float localY = y / (float) (sizeY - 1);
                float v0 = 1.0f - localY;
                for (int x = 0; x < sizeX; x++) {
                    int horizontalIndex = z * sizeX + x;
                    float sampleU = warpedU[horizontalIndex];
                    float sampleW = warpedW[horizontalIndex];

                    // Exactly two source samplers define the volume: the front
                    // profile controls X/Y and the side profile controls Z/Y.
                    // noise-hq.png only offsets their horizontal coordinates.
                    float frontDensity = front.sampleDensityWrappedU(sampleU, v0);
                    float sideDensity = side.sampleDensityWrappedU(sampleW, v0);
                    float frontShade = front.sampleShadeWrappedU(sampleU, v0);
                    float sideShade = side.sampleShadeWrappedU(sampleW, v0);
                    float intersection = (float) Math.sqrt(Math.max(0.0f, frontDensity * sideDensity));
                    float support = intersection * 0.80f
                            + Math.min(frontDensity, sideDensity) * 0.20f;
                    float density = smoothstep(0.04f, 0.72f, support);
                    if (density > 0.001f) {
                        density += (columnNoise[horizontalIndex] * 2.0f - 1.0f)
                                * breakupStrength;
                    }

                    float verticalCap = smoothstep(0.015f, 0.06f, localY)
                            * (1.0f - smoothstep(0.985f, 1.0f, localY));
                    int fieldIndex = index(x, y, z, sizeX, sizeY);
                    field[fieldIndex] = MathHelper.clamp(density * verticalCap, 0.0f, 1.0f);
                    shadeField[fieldIndex] = MathHelper.clamp(
                            (frontShade + sideShade) * 0.5f,
                            0.0f,
                            1.0f
                    );
                }
            }
        }
        return new FieldData(field, shadeField);
    }

    private void smoothField(float[] field, int sizeX, int sizeY, int sizeZ, int passes) {
        float[] scratch = new float[field.length];
        for (int pass = 0; pass < passes; pass++) {
            System.arraycopy(field, 0, scratch, 0, field.length);
            for (int z = 1; z < sizeZ - 1; z++) {
                for (int y = 1; y < sizeY - 1; y++) {
                    for (int x = 1; x < sizeX - 1; x++) {
                        float center = scratch[index(x, y, z, sizeX, sizeY)];
                        float axial =
                                scratch[index(x - 1, y, z, sizeX, sizeY)]
                                        + scratch[index(x + 1, y, z, sizeX, sizeY)]
                                        + scratch[index(x, y - 1, z, sizeX, sizeY)]
                                        + scratch[index(x, y + 1, z, sizeX, sizeY)]
                                        + scratch[index(x, y, z - 1, sizeX, sizeY)]
                                        + scratch[index(x, y, z + 1, sizeX, sizeY)];
                        field[index(x, y, z, sizeX, sizeY)] = center * 0.58f + axial * (0.42f / 6.0f);
                    }
                }
            }
        }
    }

    private AtcCloudMesh extractSurface(float[] field,
                                        float[] shadeField,
                                        int sizeX,
                                        int sizeY,
                                        int sizeZ,
                                        float isoLevel) {
        FloatList positions = new FloatList();
        FloatList normals = new FloatList();
        FloatList heights = new FloatList();
        FloatList shades = new FloatList();
        IntList indices = new IntList();

        // With translucent depth writes disabled, triangle submission order is
        // also blend order. Submit the lower deck before the formations so it
        // remains visually behind them when viewed from above.
        appendBaseShelf(positions, normals, heights, shades, indices);

        for (int z = 0; z < sizeZ - 1; z++) {
            for (int y = 0; y < sizeY - 1; y++) {
                for (int x = 0; x < sizeX - 1; x++) {
                    float[] values = new float[8];
                    for (int i = 0; i < 8; i++) {
                        int gx = x + CORNERS[i][0];
                        int gy = y + CORNERS[i][1];
                        int gz = z + CORNERS[i][2];
                        values[i] = field[index(gx, gy, gz, sizeX, sizeY)];
                    }
                    emitCellTetras(
                            field,
                            shadeField,
                            sizeX,
                            sizeY,
                            sizeZ,
                            x,
                            y,
                            z,
                            values,
                            isoLevel,
                            positions,
                            normals,
                            heights,
                            shades,
                            indices
                    );
                }
            }
        }

        return new AtcCloudMesh(
                positions.toArray(),
                normals.toArray(),
                heights.toArray(),
                shades.toArray(),
                indices.toArray(),
                1.0f,
                1.0f,
                1.0f,
                field,
                sizeX,
                sizeY,
                sizeZ
        );
    }

    private void appendBaseShelf(FloatList positions,
                                 FloatList normals,
                                 FloatList heights,
                                 FloatList shades,
                                 IntList indices) {
        final int segments = 8;
        for (int z = 0; z < segments; z++) {
            float w0 = z / (float) segments;
            float w1 = (z + 1) / (float) segments;
            for (int x = 0; x < segments; x++) {
                float u0 = x / (float) segments;
                float u1 = (x + 1) / (float) segments;
                int a = addShelfVertex(u0, w0, positions, normals, heights, shades);
                int b = addShelfVertex(u0, w1, positions, normals, heights, shades);
                int c = addShelfVertex(u1, w1, positions, normals, heights, shades);
                int d = addShelfVertex(u1, w0, positions, normals, heights, shades);
                indices.add(a);
                indices.add(b);
                indices.add(c);
                indices.add(a);
                indices.add(c);
                indices.add(d);
            }
        }
    }

    private int addShelfVertex(float u,
                               float w,
                               FloatList positions,
                               FloatList normals,
                               FloatList heights,
                               FloatList shades) {
        float tau = (float) (Math.PI * 2.0);
        float sinU = MathHelper.sin(u * tau);
        float sinW = MathHelper.sin(w * tau);
        float height = 0.105f + sinU * sinW * 0.014f
                + MathHelper.sin(u * tau * 2.0f) * MathHelper.sin(w * tau * 2.0f) * 0.005f;
        float dhdu = tau * MathHelper.cos(u * tau) * sinW * 0.014f
                + tau * 2.0f * MathHelper.cos(u * tau * 2.0f)
                * MathHelper.sin(w * tau * 2.0f) * 0.005f;
        float dhdw = tau * sinU * MathHelper.cos(w * tau) * 0.014f
                + tau * 2.0f * MathHelper.sin(u * tau * 2.0f)
                * MathHelper.cos(w * tau * 2.0f) * 0.005f;
        float nx = -dhdu;
        float ny = 1.0f;
        float nz = -dhdw;
        float inverseLength = 1.0f / MathHelper.sqrt(nx * nx + ny * ny + nz * nz);
        int vertex = positions.size() / 3;
        positions.add(u - 0.5f);
        positions.add(height);
        positions.add(w - 0.5f);
        normals.add(nx * inverseLength);
        normals.add(ny * inverseLength);
        normals.add(nz * inverseLength);
        heights.add(height);
        shades.add(0.48f + (height - 0.105f) * 3.0f);
        return vertex;
    }

    private void emitCellTetras(float[] field,
                                float[] shadeField,
                                int sizeX,
                                int sizeY,
                                int sizeZ,
                                int cellX,
                                int cellY,
                                int cellZ,
                                float[] cubeValues,
                                float isoLevel,
                                FloatList positions,
                                FloatList normals,
                                FloatList heights,
                                FloatList shades,
                                IntList indices) {
        for (int[] tetra : TETRAS) {
            float[][] p = new float[4][3];
            float[] v = new float[4];
            boolean[] inside = new boolean[4];
            int insideCount = 0;
            for (int i = 0; i < 4; i++) {
                int corner = tetra[i];
                p[i][0] = cellX + CORNERS[corner][0];
                p[i][1] = cellY + CORNERS[corner][1];
                p[i][2] = cellZ + CORNERS[corner][2];
                v[i] = cubeValues[corner];
                inside[i] = v[i] >= isoLevel;
                if (inside[i]) {
                    insideCount++;
                }
            }
            if (insideCount == 0 || insideCount == 4) {
                continue;
            }

            int[] in = new int[4];
            int[] out = new int[4];
            int inCount = 0;
            int outCount = 0;
            for (int i = 0; i < 4; i++) {
                if (inside[i]) {
                    in[inCount++] = i;
                } else {
                    out[outCount++] = i;
                }
            }

            if (insideCount == 1) {
                int a = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[in[0]], v[in[0]], p[out[0]], v[out[0]], isoLevel, positions, normals, heights, shades);
                int b = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[in[0]], v[in[0]], p[out[1]], v[out[1]], isoLevel, positions, normals, heights, shades);
                int c = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[in[0]], v[in[0]], p[out[2]], v[out[2]], isoLevel, positions, normals, heights, shades);
                addOrientedTriangle(a, b, c, positions, normals, indices);
            } else if (insideCount == 3) {
                int a = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[out[0]], v[out[0]], p[in[0]], v[in[0]], isoLevel, positions, normals, heights, shades);
                int b = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[out[0]], v[out[0]], p[in[1]], v[in[1]], isoLevel, positions, normals, heights, shades);
                int c = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[out[0]], v[out[0]], p[in[2]], v[in[2]], isoLevel, positions, normals, heights, shades);
                addOrientedTriangle(a, c, b, positions, normals, indices);
            } else {
                int a = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[in[0]], v[in[0]], p[out[0]], v[out[0]], isoLevel, positions, normals, heights, shades);
                int b = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[in[0]], v[in[0]], p[out[1]], v[out[1]], isoLevel, positions, normals, heights, shades);
                int c = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[in[1]], v[in[1]], p[out[1]], v[out[1]], isoLevel, positions, normals, heights, shades);
                int d = surfaceVertex(field, shadeField, sizeX, sizeY, sizeZ, p[in[1]], v[in[1]], p[out[0]], v[out[0]], isoLevel, positions, normals, heights, shades);
                addOrientedTriangle(a, b, c, positions, normals, indices);
                addOrientedTriangle(a, c, d, positions, normals, indices);
            }
        }
    }

    private int surfaceVertex(float[] field,
                              float[] shadeField,
                              int sizeX,
                              int sizeY,
                              int sizeZ,
                              float[] a,
                              float va,
                              float[] b,
                              float vb,
                              float isoLevel,
                              FloatList positions,
                              FloatList normals,
                              FloatList heights,
                              FloatList shades) {
        float denominator = vb - va;
        float t = Math.abs(denominator) < 0.000001f
                ? 0.5f
                : MathHelper.clamp((isoLevel - va) / denominator, 0.0f, 1.0f);
        float px = MathHelper.lerp(t, a[0], b[0]);
        float py = MathHelper.lerp(t, a[1], b[1]);
        float pz = MathHelper.lerp(t, a[2], b[2]);
        float localX = px / (sizeX - 1.0f) - 0.5f;
        float localY = py / (sizeY - 1.0f);
        float localZ = pz / (sizeZ - 1.0f) - 0.5f;
        int vertexIndex = positions.size() / 3;
        positions.add(localX);
        positions.add(localY);
        positions.add(localZ);
        addGradientNormal(field, sizeX, sizeY, sizeZ, px, py, pz, normals);
        heights.add(localY);
        shades.add(sampleTrilinear(shadeField, sizeX, sizeY, sizeZ, px, py, pz));
        return vertexIndex;
    }

    private void addOrientedTriangle(int a,
                                     int b,
                                     int c,
                                     FloatList positions,
                                     FloatList normals,
                                     IntList indices) {
        float ax = positions.get(a * 3);
        float ay = positions.get(a * 3 + 1);
        float az = positions.get(a * 3 + 2);
        float bx = positions.get(b * 3);
        float by = positions.get(b * 3 + 1);
        float bz = positions.get(b * 3 + 2);
        float cx = positions.get(c * 3);
        float cy = positions.get(c * 3 + 1);
        float cz = positions.get(c * 3 + 2);
        float ux = bx - ax;
        float uy = by - ay;
        float uz = bz - az;
        float vx = cx - ax;
        float vy = cy - ay;
        float vz = cz - az;
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float avgNx = normals.get(a * 3) + normals.get(b * 3) + normals.get(c * 3);
        float avgNy = normals.get(a * 3 + 1) + normals.get(b * 3 + 1) + normals.get(c * 3 + 1);
        float avgNz = normals.get(a * 3 + 2) + normals.get(b * 3 + 2) + normals.get(c * 3 + 2);
        if (nx * avgNx + ny * avgNy + nz * avgNz < 0.0f) {
            indices.add(a);
            indices.add(c);
            indices.add(b);
        } else {
            indices.add(a);
            indices.add(b);
            indices.add(c);
        }
    }

    private void addGradientNormal(float[] field,
                                   int sizeX,
                                   int sizeY,
                                   int sizeZ,
                                   float px,
                                   float py,
                                   float pz,
                                   FloatList normals) {
        // Interpolate the density gradient at the actual surface crossing.
        // Rounding to the nearest voxel made every vertex in a cell share a
        // blocky normal even when the extracted positions were smooth.
        float epsilon = 0.72f;
        float nx = sampleTrilinear(field, sizeX, sizeY, sizeZ, px - epsilon, py, pz)
                - sampleTrilinear(field, sizeX, sizeY, sizeZ, px + epsilon, py, pz);
        float ny = sampleTrilinear(field, sizeX, sizeY, sizeZ, px, py - epsilon, pz)
                - sampleTrilinear(field, sizeX, sizeY, sizeZ, px, py + epsilon, pz);
        float nz = sampleTrilinear(field, sizeX, sizeY, sizeZ, px, py, pz - epsilon)
                - sampleTrilinear(field, sizeX, sizeY, sizeZ, px, py, pz + epsilon);
        nx *= sizeX;
        ny *= sizeY;
        nz *= sizeZ;
        float len = MathHelper.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 0.000001f) {
            normals.add(0.0f);
            normals.add(1.0f);
            normals.add(0.0f);
            return;
        }
        normals.add(nx / len);
        normals.add(ny / len);
        normals.add(nz / len);
    }

    private static float sampleField(float[] field, int sizeX, int sizeY, int sizeZ, int x, int y, int z) {
        x = MathHelper.clamp(x, 0, sizeX - 1);
        y = MathHelper.clamp(y, 0, sizeY - 1);
        z = MathHelper.clamp(z, 0, sizeZ - 1);
        return field[index(x, y, z, sizeX, sizeY)];
    }

    private static float sampleTrilinear(float[] field,
                                         int sizeX,
                                         int sizeY,
                                         int sizeZ,
                                         float x,
                                         float y,
                                         float z) {
        x = MathHelper.clamp(x, 0.0f, sizeX - 1.0f);
        y = MathHelper.clamp(y, 0.0f, sizeY - 1.0f);
        z = MathHelper.clamp(z, 0.0f, sizeZ - 1.0f);
        int x0 = MathHelper.clamp((int) Math.floor(x), 0, sizeX - 1);
        int y0 = MathHelper.clamp((int) Math.floor(y), 0, sizeY - 1);
        int z0 = MathHelper.clamp((int) Math.floor(z), 0, sizeZ - 1);
        int x1 = Math.min(x0 + 1, sizeX - 1);
        int y1 = Math.min(y0 + 1, sizeY - 1);
        int z1 = Math.min(z0 + 1, sizeZ - 1);
        float tx = x - x0;
        float ty = y - y0;
        float tz = z - z0;
        float x00 = MathHelper.lerp(tx,
                field[index(x0, y0, z0, sizeX, sizeY)],
                field[index(x1, y0, z0, sizeX, sizeY)]);
        float x10 = MathHelper.lerp(tx,
                field[index(x0, y1, z0, sizeX, sizeY)],
                field[index(x1, y1, z0, sizeX, sizeY)]);
        float x01 = MathHelper.lerp(tx,
                field[index(x0, y0, z1, sizeX, sizeY)],
                field[index(x1, y0, z1, sizeX, sizeY)]);
        float x11 = MathHelper.lerp(tx,
                field[index(x0, y1, z1, sizeX, sizeY)],
                field[index(x1, y1, z1, sizeX, sizeY)]);
        return MathHelper.lerp(tz, MathHelper.lerp(ty, x00, x10), MathHelper.lerp(ty, x01, x11));
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = MathHelper.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static int index(int x, int y, int z, int sizeX, int sizeY) {
        return (z * sizeY + y) * sizeX + x;
    }

    private static long hash(int x, int y, int z) {
        long h = 1469598103934665603L;
        h = (h ^ x) * 1099511628211L;
        h = (h ^ y) * 1099511628211L;
        h = (h ^ z) * 1099511628211L;
        return h;
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

    private record FieldData(float[] density, float[] shade) {
    }

    private static final class FloatList {
        private float[] values = new float[4096];
        private int size;

        private void add(float value) {
            if (size >= values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        private int size() {
            return size;
        }

        private float get(int index) {
            return values[index];
        }

        private float[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    private static final class IntList {
        private int[] values = new int[8192];
        private int size;

        private void add(int value) {
            if (size >= values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        private int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
