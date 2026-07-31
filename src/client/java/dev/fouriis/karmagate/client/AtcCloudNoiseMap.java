package dev.fouriis.karmagate.client;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.io.IOException;
import java.io.InputStream;

/**
 * CPU copy of the cloud domain-warp texture. The image is decoded only while
 * rebuilding the close-cloud cache; normal frames only draw cached meshes.
 */
public final class AtcCloudNoiseMap {
    private final int width;
    private final int height;
    private final float[] values;

    private AtcCloudNoiseMap(int width, int height, float[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
    }

    public static AtcCloudNoiseMap load(ResourceManager manager, Identifier id) throws IOException {
        Resource resource = manager.getResource(id)
                .orElseThrow(() -> new IOException("Missing cloud noise texture " + id));
        try (InputStream in = resource.getInputStream(); NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth();
            int height = image.getHeight();
            float[] values = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int abgr = image.getColor(x, y);
                    float r = (abgr & 0xFF) / 255.0f;
                    float g = ((abgr >>> 8) & 0xFF) / 255.0f;
                    float b = ((abgr >>> 16) & 0xFF) / 255.0f;
                    float value = r * 0.2126f + g * 0.7152f + b * 0.0722f;
                    values[y * width + x] = value;
                }
            }
            return new AtcCloudNoiseMap(width, height, values);
        }
    }

    /**
     * Bilinearly samples the original 0..1 texture with repeat wrapping.
     */
    public float sample(float u, float v) {
        float wrappedU = u - (float) Math.floor(u);
        float wrappedV = v - (float) Math.floor(v);
        float x = wrappedU * width;
        float y = wrappedV * height;
        int x0 = Math.floorMod((int) Math.floor(x), width);
        int y0 = Math.floorMod((int) Math.floor(y), height);
        int x1 = (x0 + 1) % width;
        int y1 = (y0 + 1) % height;
        float tx = x - (float) Math.floor(x);
        float ty = y - (float) Math.floor(y);
        float a = values[y0 * width + x0];
        float b = values[y0 * width + x1];
        float c = values[y1 * width + x0];
        float d = values[y1 * width + x1];
        return MathHelper.lerp(ty, MathHelper.lerp(tx, a, b), MathHelper.lerp(tx, c, d));
    }
}
