package dev.fouriis.karmagate.hologram;

import com.google.gson.*;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Similar to {@link HoloFrameIndex} but reads the sprite sheet image to determine
 * the sheet size automatically and provides a helper to extract a cropped
 * {@link NativeImage} for a named frame from the sheet.
 */
public final class RainWorldFrameIndex {
    public static final class Frame {
        public final float u0, v0, u1, v1;
        public final int x, y, w, h;
        public final int sourceW, sourceH;
        public final int offX, offY;

        Frame(int x, int y, int w, int h,
                     int sourceW, int sourceH,
                     int offX, int offY,
                     float u0, float v0, float u1, float v1) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.sourceW = sourceW; this.sourceH = sourceH;
            this.offX = offX; this.offY = offY;
            this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
        }
    }

    private final Map<String, Frame> map = new HashMap<>();
    private final NativeImage sheetImg;
    private final int sheetW, sheetH;

    private RainWorldFrameIndex(NativeImage sheetImg) {
        this.sheetImg = sheetImg;
        this.sheetW = sheetImg.getWidth();
        this.sheetH = sheetImg.getHeight();
    }

    public Frame get(String name) { return map.get(name); }

    /**
     * Load index from the provided sheet PNG resource and frames JSON resource.
     * Both paths are resource identifiers in the form "namespace:path". For the
     * PNG make sure to include the "textures/..." path (e.g.
     * "karma-gate-mod:textures/hologram/rainworld.png"). For the JSON include
     * the asset path (e.g. "karma-gate-mod:hologram/rainWorld.json").
     */
    public static RainWorldFrameIndex load(String sheetPngPath, String framesJsonPath) {
        Identifier sheetId = Identifier.of(sheetPngPath);
        Identifier jsonId = Identifier.of(framesJsonPath);

        try (InputStream sheetIs = RainWorldFrameIndex.class.getClassLoader().getResourceAsStream("assets/" + sheetId.getNamespace() + "/" + sheetId.getPath())) {
            if (sheetIs == null) throw new RuntimeException("Missing sheet PNG: " + sheetPngPath);
            NativeImage sheet = NativeImage.read(sheetIs);
            RainWorldFrameIndex idx = new RainWorldFrameIndex(sheet);

            try (InputStream js = RainWorldFrameIndex.class.getClassLoader().getResourceAsStream("assets/" + jsonId.getNamespace() + "/" + jsonId.getPath())) {
                if (js == null) throw new RuntimeException("Missing frames JSON: " + framesJsonPath);
                JsonObject root = JsonParser.parseReader(new InputStreamReader(js)).getAsJsonObject();
                JsonObject frames = root.getAsJsonObject("frames");

                for (var e : frames.entrySet()) {
                    String key = e.getKey();
                    JsonObject f = e.getValue().getAsJsonObject();
                    JsonObject fr = f.getAsJsonObject("frame");
                    int x = fr.get("x").getAsInt();
                    int y = fr.get("y").getAsInt();
                    int w = fr.get("w").getAsInt();
                    int h = fr.get("h").getAsInt();

                    int sourceW = w, sourceH = h, offX = 0, offY = 0;
                    if (f.has("sourceSize")) {
                        JsonObject ss = f.getAsJsonObject("sourceSize");
                        if (ss.has("w")) sourceW = ss.get("w").getAsInt();
                        if (ss.has("h")) sourceH = ss.get("h").getAsInt();
                    }
                    if (f.has("spriteSourceSize")) {
                        JsonObject sss = f.getAsJsonObject("spriteSourceSize");
                        if (sss.has("x")) offX = sss.get("x").getAsInt();
                        if (sss.has("y")) offY = sss.get("y").getAsInt();
                    }

                    float u0 = (float)x / idx.sheetW;
                    float v0 = (float)y / idx.sheetH;
                    float u1 = (float)(x + w) / idx.sheetW;
                    float v1 = (float)(y + h) / idx.sheetH;

                    idx.map.put(key, new Frame(x, y, w, h, sourceW, sourceH, offX, offY, u0, v0, u1, v1));
                }
            }

            return idx;
        } catch (Exception ex) {
            throw new RuntimeException("Failed loading rain world frames", ex);
        }
    }

    /**
     * Convenience loader for the project's rainWorld assets.
     */
    public static RainWorldFrameIndex loadDefault() {
        return load("karma-gate-mod:textures/hologram/rainworld.png", "karma-gate-mod:hologram/rainWorld.json");
    }

    /**
     * Returns a new {@link NativeImage} containing the cropped frame pixels for the
     * named frame. Caller is responsible for closing the returned image when done.
     * Returns null if the frame name does not exist.
     */
    public NativeImage getImage(String name) {
        Frame f = get(name);
        if (f == null) return null;
        NativeImage out = new NativeImage(f.w, f.h, true);
        int srcX = f.x;
        int srcY = f.y;
        for (int yy = 0; yy < f.h; yy++) {
            for (int xx = 0; xx < f.w; xx++) {
                int color = sheetImg.getColor(srcX + xx, srcY + yy);
                out.setColor(xx, yy, color);
            }
        }
        return out;
    }

    /**
     * Close underlying sheet image to free memory. After calling this the index
     * should not be used for getImage or similar calls.
     */
    public void close() {
        if (sheetImg != null) sheetImg.close();
    }
}
