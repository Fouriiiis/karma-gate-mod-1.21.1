package dev.fouriis.karmagate.client;

public final class AtcCloudMesh {
    public final float[] positions;
    public final float[] normals;
    public final float[] heights;
    public final float[] shades;
    public final float[] thicknesses;
    public final int[] indices;
    public final float localWidth;
    public final float localHeight;
    public final float localDepth;
    private final byte[] densityField;
    private final int densitySizeX;
    private final int densitySizeY;
    private final int densitySizeZ;

    public AtcCloudMesh(float[] positions,
                        float[] normals,
                        float[] heights,
                        float[] shades,
                        float[] thicknesses,
                        int[] indices,
                        float localWidth,
                        float localHeight,
                        float localDepth,
                        float[] densityField,
                        int densitySizeX,
                        int densitySizeY,
                        int densitySizeZ) {
        this.positions = positions;
        this.normals = normals;
        this.heights = heights;
        this.shades = shades;
        this.thicknesses = thicknesses;
        this.indices = indices;
        this.localWidth = localWidth;
        this.localHeight = localHeight;
        this.localDepth = localDepth;
        this.densityField = compressDensity(densityField);
        this.densitySizeX = densitySizeX;
        this.densitySizeY = densitySizeY;
        this.densitySizeZ = densitySizeZ;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    public int gridSizeX() {
        return densitySizeX;
    }

    public int gridSizeY() {
        return densitySizeY;
    }

    public int gridSizeZ() {
        return densitySizeZ;
    }

    /** Samples the same cached scalar field used to extract this mesh. */
    public float sampleDensity(float u, float v, float w) {
        if (densityField.length == 0
                || u < 0.0f || u > 1.0f
                || v < 0.0f || v > 1.0f
                || w < 0.0f || w > 1.0f) {
            return 0.0f;
        }
        float x = u * (densitySizeX - 1.0f);
        float y = v * (densitySizeY - 1.0f);
        float z = w * (densitySizeZ - 1.0f);
        int x0 = Math.max(0, Math.min((int) Math.floor(x), densitySizeX - 1));
        int y0 = Math.max(0, Math.min((int) Math.floor(y), densitySizeY - 1));
        int z0 = Math.max(0, Math.min((int) Math.floor(z), densitySizeZ - 1));
        int x1 = Math.min(x0 + 1, densitySizeX - 1);
        int y1 = Math.min(y0 + 1, densitySizeY - 1);
        int z1 = Math.min(z0 + 1, densitySizeZ - 1);
        float tx = x - x0;
        float ty = y - y0;
        float tz = z - z0;
        float c00 = lerp(tx, density(x0, y0, z0), density(x1, y0, z0));
        float c10 = lerp(tx, density(x0, y1, z0), density(x1, y1, z0));
        float c01 = lerp(tx, density(x0, y0, z1), density(x1, y0, z1));
        float c11 = lerp(tx, density(x0, y1, z1), density(x1, y1, z1));
        float sampled = lerp(tz, lerp(ty, c00, c10), lerp(ty, c01, c11));
        float shelfTop = 0.105f
                + (float) Math.sin(u * Math.PI * 2.0) * (float) Math.sin(w * Math.PI * 2.0) * 0.014f
                + (float) Math.sin(u * Math.PI * 4.0) * (float) Math.sin(w * Math.PI * 4.0) * 0.005f;
        float shelf = smoothstep(0.015f, 0.045f, v)
                * (1.0f - smoothstep(shelfTop - 0.025f, shelfTop + 0.012f, v))
                * 0.92f;
        return Math.max(sampled, shelf);
    }

    private float density(int x, int y, int z) {
        int index = (z * densitySizeY + y) * densitySizeX + x;
        return (densityField[index] & 0xFF) / 255.0f;
    }

    private static byte[] compressDensity(float[] source) {
        if (source == null || source.length == 0) {
            return new byte[0];
        }
        byte[] compressed = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            compressed[i] = (byte) Math.max(0, Math.min(255, Math.round(source[i] * 255.0f)));
        }
        return compressed;
    }

    private static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / Math.max(edge1 - edge0, 0.0001f)));
        return t * t * (3.0f - 2.0f * t);
    }
}
