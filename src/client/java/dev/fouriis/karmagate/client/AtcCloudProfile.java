package dev.fouriis.karmagate.client;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
 
public final class AtcCloudProfile {
    public record PuffFeature(float centerU,
                              float halfWidth,
                              float rootV,
                              float topV,
                              float prominence,
                              float mass) {
    }

    private static final float DEFAULT_PLANE_V = 0.68f;
    private static final float COVERAGE_THRESHOLD = 0.12f;

    private final String name;
    private final int width;
    private final int height;
    private final float[] coverage;
    private final float[] density;
    private final float[] shade;
    private final float planeV;
    private final List<PuffFeature> puffs;

    private AtcCloudProfile(String name,
                            int width,
                            int height,
                            float[] coverage,
                            float[] density,
                            float[] shade,
                            float planeV,
                            List<PuffFeature> puffs) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.coverage = coverage;
        this.density = density;
        this.shade = shade;
        this.planeV = planeV;
        this.puffs = List.copyOf(puffs);
    }

    public static AtcCloudProfile load(ResourceManager manager, Identifier id, String name) throws IOException {
        Resource resource = manager.getResource(id)
                .orElseThrow(() -> new IOException("Missing cloud profile " + id));
        try (InputStream in = resource.getInputStream(); NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth();
            int height = image.getHeight();
            float[] coverage = new float[width * height];
            float[] density = new float[width * height];
            float[] shade = new float[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int abgr = image.getColor(x, y);
                    float r = (abgr & 0xFF) / 255.0f;
                    float g = ((abgr >>> 8) & 0xFF) / 255.0f;
                    float b = ((abgr >>> 16) & 0xFF) / 255.0f;
                    float a = ((abgr >>> 24) & 0xFF) / 255.0f;
                    float greenDominance = g - Math.max(r, b);
                    float background = smoothstep(0.06f, 0.28f, greenDominance);
                    float c = (1.0f - background) * a;
                    coverage[y * width + x] = c;
                    density[y * width + x] = c * (0.68f + r * 0.32f);
                    shade[y * width + x] = g;
                }
            }

            float planeV = detectPlane(coverage, width, height);
            if (planeV < 0.45f || planeV > 0.88f || Float.isNaN(planeV)) {
                planeV = DEFAULT_PLANE_V;
            }
            List<PuffFeature> puffs = detectPuffs(coverage, width, height, planeV);
            KarmaGateMod.LOGGER.info("{} planeV = {}", name, String.format(java.util.Locale.ROOT, "%.3f", planeV));
            return new AtcCloudProfile(name, width, height, coverage, density, shade, planeV, puffs);
        }
    }

    public float sampleCoverage(float u, float v) {
        return sample(coverage, u, v);
    }

    public float sampleDensity(float u, float v) {
        return sample(density, u, v);
    }

    public float sampleCoverageWrappedU(float u, float v) {
        return sampleWrappedU(coverage, u, v);
    }

    public float sampleDensityWrappedU(float u, float v) {
        return sampleWrappedU(density, u, v);
    }

    public float sampleShadeWrappedU(float u, float v) {
        return sampleWrappedU(shade, u, v);
    }

    public float planeV() {
        return planeV;
    }

    public List<PuffFeature> puffs() {
        return puffs;
    }

    public String name() {
        return name;
    }

    private float sample(float[] values, float u, float v) {
        if (u < 0.0f || u > 1.0f || v < 0.0f || v > 1.0f) {
            return 0.0f;
        }
        float x = u * (width - 1);
        float y = v * (height - 1);
        int x0 = MathHelper.clamp((int) Math.floor(x), 0, width - 1);
        int y0 = MathHelper.clamp((int) Math.floor(y), 0, height - 1);
        int x1 = Math.min(x0 + 1, width - 1);
        int y1 = Math.min(y0 + 1, height - 1);
        float tx = x - x0;
        float ty = y - y0;
        float a = values[y0 * width + x0];
        float b = values[y0 * width + x1];
        float c = values[y1 * width + x0];
        float d = values[y1 * width + x1];
        return MathHelper.lerp(ty, MathHelper.lerp(tx, a, b), MathHelper.lerp(tx, c, d));
    }

    private float sampleWrappedU(float[] values, float u, float v) {
        if (v < 0.0f || v > 1.0f) {
            return 0.0f;
        }
        float wrappedU = u - (float) Math.floor(u);
        float x = wrappedU * width;
        float y = v * (height - 1);
        int x0 = Math.floorMod((int) Math.floor(x), width);
        int x1 = (x0 + 1) % width;
        int y0 = MathHelper.clamp((int) Math.floor(y), 0, height - 1);
        int y1 = Math.min(y0 + 1, height - 1);
        float tx = x - (float) Math.floor(x);
        float ty = y - y0;
        float a = values[y0 * width + x0];
        float b = values[y0 * width + x1];
        float c = values[y1 * width + x0];
        float d = values[y1 * width + x1];
        return MathHelper.lerp(ty, MathHelper.lerp(tx, a, b), MathHelper.lerp(tx, c, d));
    }

    private static float detectPlane(float[] coverage, int width, int height) {
        int rows = 128;
        int samples = 160;
        float[] occupancy = new float[rows];
        for (int row = 0; row < rows; row++) {
            float v = row / (float) (rows - 1);
            int y = MathHelper.clamp(Math.round(v * (height - 1)), 0, height - 1);
            int occupied = 0;
            for (int sample = 0; sample < samples; sample++) {
                int x = MathHelper.clamp(Math.round(sample * (width - 1) / (float) (samples - 1)), 0, width - 1);
                if (coverage[y * width + x] > COVERAGE_THRESHOLD) {
                    occupied++;
                }
            }
            occupancy[row] = occupied / (float) samples;
        }

        float[] smooth = new float[rows];
        for (int i = 0; i < rows; i++) {
            float sum = 0.0f;
            float weight = 0.0f;
            for (int k = -3; k <= 3; k++) {
                int idx = MathHelper.clamp(i + k, 0, rows - 1);
                float w = 4.0f - Math.abs(k);
                sum += occupancy[idx] * w;
                weight += w;
            }
            smooth[i] = sum / weight;
        }

        int best = -1;
        float bestScore = -1.0f;
        int start = Math.round(rows * 0.52f);
        int end = Math.round(rows * 0.86f);
        for (int i = start; i <= end; i++) {
            float v = i / (float) (rows - 1);
            float bottomPenalty = smoothstep(0.80f, 0.90f, v) * 0.20f;
            float score = smooth[i] - bottomPenalty;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0 || bestScore < 0.08f) {
            return DEFAULT_PLANE_V;
        }
        return best / (float) (rows - 1);
    }

    private static List<PuffFeature> detectPuffs(float[] coverage, int width, int height, float planeV) {
        int columns = 128;
        float[] prominence = new float[columns];
        float[] mass = new float[columns];
        for (int col = 0; col < columns; col++) {
            float u = col / (float) (columns - 1);
            int x = MathHelper.clamp(Math.round(u * (width - 1)), 0, width - 1);
            float topV = planeV;
            float columnMass = 0.0f;
            int maxY = MathHelper.clamp(Math.round(planeV * (height - 1)), 0, height - 1);
            for (int y = 0; y <= maxY; y++) {
                float c = coverage[y * width + x];
                if (c > COVERAGE_THRESHOLD) {
                    topV = y / (float) (height - 1);
                    columnMass += c;
                    break;
                }
            }
            for (int y = 0; y <= maxY; y++) {
                columnMass += coverage[y * width + x];
            }
            prominence[col] = Math.max(0.0f, planeV - topV);
            mass[col] = columnMass / Math.max(1, maxY + 1);
        }

        smooth(prominence, 4);
        smooth(mass, 3);

        ArrayList<Integer> peaks = new ArrayList<>();
        int minSeparation = Math.max(8, columns / 14);
        for (int i = 2; i < columns - 2; i++) {
            if (prominence[i] < 0.11f || mass[i] < 0.05f) {
                continue;
            }
            if (prominence[i] >= prominence[i - 1] && prominence[i] >= prominence[i + 1]) {
                boolean separated = true;
                for (int peak : peaks) {
                    if (Math.abs(peak - i) < minSeparation) {
                        separated = false;
                        if (prominence[i] > prominence[peak]) {
                            peaks.remove((Integer) peak);
                            peaks.add(i);
                        }
                        break;
                    }
                }
                if (separated) {
                    peaks.add(i);
                }
            }
        }

        peaks.sort(Comparator.comparingDouble(i -> -prominence[i]));
        if (peaks.size() > 8) {
            peaks.subList(8, peaks.size()).clear();
        }
        peaks.sort(Integer::compareTo);

        ArrayList<PuffFeature> result = new ArrayList<>();
        for (int peak : peaks) {
            int left = peak;
            while (left > 0 && prominence[left] > prominence[peak] * 0.38f) {
                left--;
            }
            int right = peak;
            while (right < columns - 1 && prominence[right] > prominence[peak] * 0.38f) {
                right++;
            }
            float centerU = peak / (float) (columns - 1);
            float halfWidth = MathHelper.clamp((right - left) / (float) (columns - 1) * 0.58f, 0.045f, 0.22f);
            float topV = MathHelper.clamp(planeV - prominence[peak], 0.02f, planeV - 0.02f);
            result.add(new PuffFeature(centerU, halfWidth, planeV, topV, prominence[peak], mass[peak]));
        }
        return result;
    }

    private static void smooth(float[] values, int radius) {
        float[] copy = values.clone();
        for (int i = 0; i < values.length; i++) {
            float sum = 0.0f;
            float weight = 0.0f;
            for (int k = -radius; k <= radius; k++) {
                int idx = MathHelper.clamp(i + k, 0, values.length - 1);
                float w = radius + 1.0f - Math.abs(k);
                sum += copy[idx] * w;
                weight += w;
            }
            values[i] = sum / weight;
        }
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = MathHelper.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
