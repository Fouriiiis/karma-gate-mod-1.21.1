package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages looping video textures for graffiti entities.
 *
 * <p>MP4 files are placed alongside PNG graffiti assets at:
 * {@code assets/karma-gate-mod/textures/graffiti/yourclip.mp4}
 *
 * <p>Each video is decoded with jcodec (pure Java, no native libraries)
 * into a {@link NativeImageBackedTexture} that is updated every render
 * frame and loops seamlessly when the clip ends.
 *
 * <p><b>Thread safety:</b> all public methods must be called on the
 * Minecraft render/GL thread.
 */
public final class VideoTextureManager {

    /** texture-path → active VideoState. */
    private static final Map<String, VideoState> CACHE = new LinkedHashMap<>();

    private VideoTextureManager() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns the Minecraft {@link Identifier} of the dynamic GL texture
     * for the given video path (relative to {@code textures/graffiti/}).
     * The video is loaded lazily on the first call.
     *
     * @return identifier, or {@code null} if loading failed
     */
    public static Identifier getOrCreate(String texturePath) {
        return CACHE.computeIfAbsent(texturePath, VideoTextureManager::load).identifier;
    }

    /**
     * Advances all active video textures by the real wall-clock elapsed time.
     * Must be called once per render frame on the GL thread.
     */
    /**
     * Called every render frame (not every game tick).
     * Uploads the next pre-decoded frame for each active video without blocking.
     */
    public static void tick() {
        long nowNs = System.nanoTime();
        for (VideoState state : CACHE.values()) {
            if (!state.isPlaying()) continue;
            if (state.lastNs == 0L) {
                state.lastNs = nowNs;
                continue;
            }
            double elapsed = (nowNs - state.lastNs) / 1_000_000_000.0;
            state.lastNs = nowNs;
            state.advanceRender(elapsed);
        }
    }

    /**
     * Destroys all cached video textures.
     * Call on resource reload or client shutdown (GL thread).
     */
    public static void closeAll() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (VideoState state : CACHE.values()) {
            // identifier is always non-null (placeholder set on failure)
            try { client.getTextureManager().destroyTexture(state.identifier); }
            catch (Exception ignored) {}
            state.dispose();
        }
        CACHE.clear();
    }

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

    private static VideoState load(String texturePath) {
        MinecraftClient client = MinecraftClient.getInstance();
        Path tempFile = null;
        try {
            Identifier resourceId = Identifier.of(
                    KarmaGateMod.MOD_ID, "textures/graffiti/" + texturePath);

            var resource = client.getResourceManager().getResource(resourceId);
            if (resource.isEmpty()) {
                KarmaGateMod.LOGGER.error("[VideoTexture] Resource not found: {}", texturePath);
                return VideoState.makeBroken(texturePath);
            }

            // jcodec needs a SeekableByteChannel → dump to a temp file.
            byte[] bytes;
            try (InputStream in = resource.get().getInputStream()) {
                bytes = in.readAllBytes();
            }

            tempFile = Files.createTempFile("graffiti_vid_", ".mp4");
            tempFile.toFile().deleteOnExit();
            Files.write(tempFile, bytes);

            // ---- detect dimensions & FPS from the first frame ----
            FrameGrab probe = FrameGrab.createFrameGrab(NIOUtils.readableChannel(tempFile.toFile()));
            Picture firstPic = probe.getNativeFrame();
            if (firstPic == null) {
                KarmaGateMod.LOGGER.error("[VideoTexture] No frames found in: {}", texturePath);
                Files.deleteIfExists(tempFile);
                return VideoState.makeBroken(texturePath);
            }

            // Convert via BufferedImage first: its dimensions reflect the DISPLAY
            // size, not jcodec's internal padded (macroblock-aligned) size.
            BufferedImage firstBi = AWTUtil.toBufferedImage(firstPic);
            int w = firstBi.getWidth();
            int h = firstBi.getHeight();

            double fps = 24.0;
            try {
                var meta = probe.getVideoTrack().getMeta();
                int totalFrames = meta.getTotalFrames();
                double totalDur  = meta.getTotalDuration();
                if (totalFrames > 0 && totalDur > 0.0) {
                    fps = totalFrames / totalDur;
                }
            } catch (Exception ignored) { /* fallback 24 fps */ }

            // ---- build the initial NativeImage from the first frame ----
            NativeImage frameImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            blitToNativeImage(firstBi, frameImage);

            // Register with Minecraft's texture manager
            Identifier texId = Identifier.of(KarmaGateMod.MOD_ID,
                    "video/" + sanitize(texturePath));
            NativeImageBackedTexture texture = new NativeImageBackedTexture(frameImage);
            client.getTextureManager().registerTexture(texId, texture);

            // Fresh grab for the actual playback loop (starts from frame 0)
            FrameGrab playGrab = FrameGrab.createFrameGrab(
                    NIOUtils.readableChannel(tempFile.toFile()));

            KarmaGateMod.LOGGER.info("[VideoTexture] Loaded '{}' ({}x{}, {} fps)",
                    texturePath, w, h, String.format("%.2f", fps));

            return new VideoState(texId, texture, frameImage, tempFile, playGrab, fps, w, h);

        } catch (Exception e) {
            KarmaGateMod.LOGGER.error("[VideoTexture] Failed to load '{}': {}",
                    texturePath, e.getMessage(), e);
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
            return VideoState.makeBroken(texturePath);
        }
    }

    // -----------------------------------------------------------------------
    // Pixel helpers
    // -----------------------------------------------------------------------

    /**
     * Copies pixels from a {@link BufferedImage} (returned by jcodec AWTUtil)
     * into a pre-allocated {@link NativeImage}.
     * Uses the BufferedImage's own dimensions as the authoritative bounds.
     * Alpha is forced fully opaque because H.264 has no alpha channel.
     */
    private static void blitToNativeImage(BufferedImage src, NativeImage dst) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        // Clamp to dst dimensions in case of any minor size mismatch
        int w = Math.min(srcW, dst.getWidth());
        int h = Math.min(srcH, dst.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                dst.setColor(x, y, nativeRgba(r, g, b, 255));
            }
        }
    }

    private static int nativeRgba(int r, int g, int b, int a) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /** Makes a texture-path safe for use in a Minecraft Identifier. */
    private static String sanitize(String path) {
        return path.replace(".mp4", "")
                   .replaceAll("[^a-z0-9_./-]", "_")
                   .toLowerCase();
    }

    // -----------------------------------------------------------------------
    // VideoState
    // -----------------------------------------------------------------------

    private static final class VideoState {

        // How many decoded frames to buffer ahead of the render thread.
        // At 30 fps, 8 frames ≈ 267 ms of headroom.
        private static final int QUEUE_CAPACITY = 8;

        final Identifier               identifier;
        final NativeImageBackedTexture texture;
        final NativeImage              frameImage;
        final Path                     tempFile;
        final double                   fps;
        final int                      w, h;

        // Background decoder, null for broken/placeholder states
        private final LinkedBlockingQueue<BufferedImage> frameQueue;
        private final AtomicBoolean                      running;
        private final Thread                             decoderThread;

        // Render-thread timing
        long   lastNs      = 0L;
        double accumulator = 0.0;

        // ---- broken/placeholder factory ----------------------------------------

        static VideoState makeBroken(String texturePath) {
            Identifier placeholderId = Identifier.of(
                    KarmaGateMod.MOD_ID, "video/broken/" + sanitize(texturePath));
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            img.setColor(0, 0, 0xFF000000);
            NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
            try {
                MinecraftClient.getInstance()
                        .getTextureManager().registerTexture(placeholderId, tex);
            } catch (Exception ignored) {}
            // Pass null grab → no decoder thread started
            return new VideoState(placeholderId, tex, img, null, null, 24, 1, 1);
        }

        // ---- constructor -------------------------------------------------------

        VideoState(Identifier id, NativeImageBackedTexture tex, NativeImage img,
                   Path tmp, FrameGrab initialGrab, double fps, int w, int h) {
            this.identifier  = id;
            this.texture     = tex;
            this.frameImage  = img;
            this.tempFile    = tmp;
            this.fps         = fps;
            this.w           = w;
            this.h           = h;

            if (initialGrab == null) {
                // Broken / placeholder: no background thread
                this.frameQueue    = null;
                this.running       = null;
                this.decoderThread = null;
                return;
            }

            this.frameQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
            this.running    = new AtomicBoolean(true);

            // Capture for lambda (must be effectively-final)
            final Path tmpFinal = tmp;

            this.decoderThread = new Thread(() -> {
                FrameGrab grab = initialGrab;
                while (running.get()) {
                    try {
                        Picture pic = grab.getNativeFrame();
                        if (pic == null) {
                            // End of clip → loop from the beginning
                            grab = FrameGrab.createFrameGrab(
                                    NIOUtils.readableChannel(tmpFinal.toFile()));
                            pic = grab.getNativeFrame();
                            if (pic == null) break; // empty file, give up
                        }
                        // Convert on the decoder thread; blit/upload stays on GL thread
                        BufferedImage bi = AWTUtil.toBufferedImage(pic);
                        // put() applies back-pressure: blocks until the render
                        // thread has consumed a slot, keeping decoder in sync
                        frameQueue.put(bi);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        KarmaGateMod.LOGGER.warn("[VideoTexture] Decoder error: {}", e.getMessage());
                        try { Thread.sleep(50); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }, "graffiti-video-decoder");
            this.decoderThread.setDaemon(true);
            this.decoderThread.start();
        }

        // ---- render-thread API -------------------------------------------------

        boolean isPlaying() {
            return frameQueue != null;
        }

        /**
         * Called from the GL/render thread every rendered frame.
         * Non-blocking: polls a pre-decoded frame from the queue and uploads it.
         * If the decoder hasn't produced a new frame yet, the previous frame stays.
         */
        void advanceRender(double elapsed) {
            if (!isPlaying() || texture == null || frameImage == null) return;

            accumulator += elapsed;
            double frameTime = 1.0 / fps;
            if (accumulator < frameTime) return;

            accumulator -= frameTime;
            // Cap drift to avoid a burst of uploads after a hiccup (e.g. GC pause)
            if (accumulator > frameTime * 4) accumulator = 0.0;

            BufferedImage next = frameQueue.poll(); // non-blocking
            if (next == null) return;               // decoder behind; show previous frame

            blitToNativeImage(next, frameImage);
            texture.upload();
        }

        // ---- lifecycle ---------------------------------------------------------

        void dispose() {
            if (running != null) running.set(false);
            if (decoderThread != null) decoderThread.interrupt();
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }
}
