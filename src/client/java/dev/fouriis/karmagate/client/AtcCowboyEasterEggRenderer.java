package dev.fouriis.karmagate.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.mixin.client.GameRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jcodec.api.FrameGrab;
import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.codecs.aac.AACUtils;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.AudioBuffer;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.client.render.RenderPhase.COLOR_MASK;
import static net.minecraft.client.render.RenderPhase.DISABLE_CULLING;
import static net.minecraft.client.render.RenderPhase.ENABLE_LIGHTMAP;
import static net.minecraft.client.render.RenderPhase.LEQUAL_DEPTH_TEST;
import static net.minecraft.client.render.RenderPhase.POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM;
import static net.minecraft.client.render.RenderPhase.TRANSLUCENT_TRANSPARENCY;

public final class AtcCowboyEasterEggRenderer {
    private static final String VIDEO_RESOURCE = "structures/big_enough.mp4";
    private static final Identifier VIDEO_ID = Identifier.of(KarmaGateMod.MOD_ID, VIDEO_RESOURCE);
    private static final int FULL_BRIGHT = LightmapTextureManager.pack(15, 15);
    private static final float BILLBOARD_WIDTH = 1600.0f;

    private static VideoPlayback active;

    private AtcCowboyEasterEggRenderer() {}

    public static void trigger() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            stop();
            active = VideoPlayback.load(client);
            if (active != null) {
                active.start();
            }
        });
    }

    public static void stop() {
        if (active != null) {
            active.dispose();
            active = null;
        }
    }

    public static void render(float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || camera == null || active == null) return;
        if (!active.advanceRender()) {
            stop();
            return;
        }

        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        Matrix4f savedModelView = new Matrix4f(mvStack);
        mvStack.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection(mc, camera, tickDelta), VertexSorter.BY_DISTANCE);

        MatrixStack bobStack = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(bobStack, tickDelta);
        }
        bobStack.peek().getPositionMatrix().mul(viewMatrix(camera));
        Matrix4f view = new Matrix4f(bobStack.peek().getPositionMatrix());

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        try {
            emitBillboard(immediate, active, camera, view);
        } finally {
            mvStack.set(savedModelView);
            mvStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
        }
    }

    private static void emitBillboard(VertexConsumerProvider.Immediate immediate, VideoPlayback playback, Camera camera, Matrix4f view) {
        float aspect = playback.width / (float) Math.max(1, playback.height);
        float width = BILLBOARD_WIDTH;
        float height = width / Math.max(0.1f, aspect);
        float halfW = width * 0.5f;
        float halfH = height * 0.5f;

        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f).rotate(camera.getRotation());
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f).rotate(camera.getRotation());
        Vector3f center = new Vector3f(
                AtcCloudVolumeRenderer.COWBOY_EASTER_EGG_X.value(),
                AtcCloudVolumeRenderer.cloudBottomY() + (halfH / 2),
                AtcCloudVolumeRenderer.COWBOY_EASTER_EGG_Z.value()
        );

        Vector3f bl = corner(center, right, up, -halfW, -halfH);
        Vector3f br = corner(center, right, up, halfW, -halfH);
        Vector3f tr = corner(center, right, up, halfW, halfH);
        Vector3f tl = corner(center, right, up, -halfW, halfH);

        RenderLayer layer = playback.layer();
        VertexConsumer vc = immediate.getBuffer(layer);
        vertex(vc, view, bl, 0.0f, 1.0f);
        vertex(vc, view, br, 1.0f, 1.0f);
        vertex(vc, view, tr, 1.0f, 0.0f);
        vertex(vc, view, tl, 0.0f, 0.0f);
        immediate.draw(layer);
    }

    private static Vector3f corner(Vector3f center, Vector3f right, Vector3f up, float rx, float uy) {
        return new Vector3f(center)
                .add(new Vector3f(right).mul(rx))
                .add(new Vector3f(up).mul(uy));
    }

    private static void vertex(VertexConsumer vc, Matrix4f view, Vector3f pos, float u, float v) {
        vc.vertex(view, pos.x, pos.y, pos.z).color(255, 255, 255, 255).texture(u, v).light(FULL_BRIGHT);
    }

    private static Matrix4f viewMatrix(Camera camera) {
        Vec3d camPos = camera.getPos();
        return new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
    }

    private static Matrix4f projection(MinecraftClient mc, Camera camera, float tickDelta) {
        double dynFovDeg = ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeGetFov(camera, tickDelta, true);
        float fovRad = (float) Math.toRadians(dynFovDeg);
        float aspect = (float) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());
        float far = Math.max(128.0f, (float) mc.options.getClampedViewDistance() * 16.0f) * 100.0f;
        return new Matrix4f().setPerspective(fovRad, aspect, 0.0001f, far);
    }

    private static RenderLayer layer(Identifier texture) {
        RenderLayer.MultiPhaseParameters params = RenderLayer.MultiPhaseParameters.builder()
                .program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM)
                .texture(new RenderPhase.Texture(texture, false, false))
                .transparency(TRANSLUCENT_TRANSPARENCY)
                .cull(DISABLE_CULLING)
                .lightmap(ENABLE_LIGHTMAP)
                .depthTest(LEQUAL_DEPTH_TEST)
                .writeMaskState(COLOR_MASK)
                .build(false);

        return RenderLayer.of(
                "karma_atc_cowboy_video",
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
                VertexFormat.DrawMode.QUADS,
                256,
                true,
                true,
                params
        );
    }

    private record DecodedFrame(int index, BufferedImage image) {}

    private static final class VideoPlayback {
        private static final int QUEUE_CAPACITY = 8;

        final Identifier textureId;
        final NativeImageBackedTexture texture;
        final NativeImage frameImage;
        final Path tempFile;
        final double fps;
        final double duration;
        final int width;
        final int height;
        final RenderLayer layer;
        final LinkedBlockingQueue<DecodedFrame> frameQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        final AtomicBoolean running = new AtomicBoolean(true);

        volatile boolean decoderDone;
        long startNs;
        int currentFrame = -1;
        Thread videoThread;
        Thread audioThread;

        private VideoPlayback(Identifier textureId, NativeImageBackedTexture texture, NativeImage frameImage,
                              Path tempFile, double fps, double duration, int width, int height) {
            this.textureId = textureId;
            this.texture = texture;
            this.frameImage = frameImage;
            this.tempFile = tempFile;
            this.fps = fps;
            this.duration = duration;
            this.width = width;
            this.height = height;
            this.layer = AtcCowboyEasterEggRenderer.layer(textureId);
        }

        static VideoPlayback load(MinecraftClient client) {
            Path tempFile = null;
            try {
                Resource resource = client.getResourceManager().getResource(VIDEO_ID)
                        .orElseThrow(() -> new IOException("missing resource " + VIDEO_ID));
                byte[] bytes;
                try (InputStream in = resource.getInputStream()) {
                    bytes = in.readAllBytes();
                }

                tempFile = Files.createTempFile("atc_cowboy_", ".mp4");
                tempFile.toFile().deleteOnExit();
                Files.write(tempFile, bytes);

                FrameGrab probe = FrameGrab.createFrameGrab(NIOUtils.readableChannel(tempFile.toFile()));
                Picture firstPic = probe.getNativeFrame();
                if (firstPic == null) {
                    throw new IOException("no video frames in " + VIDEO_ID);
                }

                BufferedImage first = org.jcodec.scale.AWTUtil.toBufferedImage(firstPic);
                int width = first.getWidth();
                int height = first.getHeight();
                double fps = 24.0;
                double duration = 0.0;
                try {
                    var meta = probe.getVideoTrack().getMeta();
                    int totalFrames = meta.getTotalFrames();
                    duration = meta.getTotalDuration();
                    if (totalFrames > 0 && duration > 0.0) {
                        fps = totalFrames / duration;
                    }
                } catch (Exception ignored) {
                }
                if (duration <= 0.0) {
                    duration = 10.0;
                }

                NativeImage frameImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
                chromaKeyBlit(first, frameImage);
                Identifier textureId = Identifier.of(KarmaGateMod.MOD_ID, "video/atc_cowboy_big_enough");
                NativeImageBackedTexture texture = new NativeImageBackedTexture(frameImage);
                client.getTextureManager().registerTexture(textureId, texture);

                return new VideoPlayback(textureId, texture, frameImage, tempFile, fps, duration, width, height);
            } catch (Exception e) {
                KarmaGateMod.LOGGER.warn("[AtC Cowboy] Failed to load {}: {}", VIDEO_ID, e.toString());
                if (tempFile != null) {
                    try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                }
                return null;
            }
        }

        void start() {
            startNs = System.nanoTime();
            startVideoDecoder();
            startAudioDecoder();
        }

        boolean advanceRender() {
            if (!running.get()) {
                return false;
            }

            double elapsed = (System.nanoTime() - startNs) / 1_000_000_000.0;
            int targetFrame = Math.max(0, (int) Math.floor(elapsed * fps));
            DecodedFrame selected = null;
            while (true) {
                DecodedFrame peek = frameQueue.peek();
                if (peek == null || peek.index > targetFrame) {
                    break;
                }
                selected = frameQueue.poll();
            }
            if (selected != null && selected.index > currentFrame) {
                chromaKeyBlit(selected.image, frameImage);
                texture.upload();
                currentFrame = selected.index;
            }

            return elapsed <= duration + 0.35 || !decoderDone || !frameQueue.isEmpty();
        }

        RenderLayer layer() {
            return layer;
        }

        private void startVideoDecoder() {
            videoThread = new Thread(() -> {
                try {
                    FrameGrab grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(tempFile.toFile()));
                    int frame = 0;
                    while (running.get()) {
                        Picture pic = grab.getNativeFrame();
                        if (pic == null) {
                            break;
                        }
                        frameQueue.put(new DecodedFrame(frame++, org.jcodec.scale.AWTUtil.toBufferedImage(pic)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    KarmaGateMod.LOGGER.warn("[AtC Cowboy] Video decoder stopped: {}", e.toString());
                } finally {
                    decoderDone = true;
                }
            }, "atc-cowboy-video-decoder");
            videoThread.setDaemon(true);
            videoThread.start();
        }

        private void startAudioDecoder() {
            audioThread = new Thread(() -> {
                SourceDataLine line = null;
                try {
                    MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(NIOUtils.readableChannel(tempFile.toFile()));
                    List<DemuxerTrack> tracks = demuxer.getAudioTracks();
                    if (tracks.isEmpty() || !(tracks.get(0) instanceof AbstractMP4DemuxerTrack track)) {
                        return;
                    }
                    ByteBuffer privateData = AACUtils.getCodecPrivate(track.getSampleEntries()[0]);
                    if (privateData == null) {
                        return;
                    }
                    AACDecoder decoder = new AACDecoder(privateData.duplicate());
                    ByteBuffer packetBuffer = ByteBuffer.allocate(1 << 20);
                    ByteBuffer decodeBuffer = ByteBuffer.allocate(1 << 20);

                    Packet packet;
                    while (running.get() && (packet = track.getNextFrame(packetBuffer)) != null) {
                        decodeBuffer.clear();
                        AudioBuffer decoded = decoder.decodeFrame(packet.getData(), decodeBuffer);
                        org.jcodec.common.AudioFormat format = decoded.getFormat();
                        if (line == null) {
                            javax.sound.sampled.AudioFormat javaFormat = new javax.sound.sampled.AudioFormat(
                                    format.getSampleRate(),
                                    format.getSampleSizeInBits(),
                                    format.getChannels(),
                                    format.isSigned(),
                                    format.isBigEndian()
                            );
                            DataLine.Info info = new DataLine.Info(SourceDataLine.class, javaFormat);
                            line = (SourceDataLine) javax.sound.sampled.AudioSystem.getLine(info);
                            line.open(javaFormat);
                            line.start();
                        }
                        ByteBuffer data = decoded.getData();
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        line.write(bytes, 0, bytes.length);
                        packetBuffer.clear();
                    }
                    if (line != null) {
                        line.drain();
                    }
                } catch (Exception e) {
                    KarmaGateMod.LOGGER.warn("[AtC Cowboy] Audio playback unavailable: {}", e.toString());
                } finally {
                    if (line != null) {
                        line.stop();
                        line.close();
                    }
                }
            }, "atc-cowboy-audio");
            audioThread.setDaemon(true);
            audioThread.start();
        }

        void dispose() {
            running.set(false);
            if (videoThread != null) videoThread.interrupt();
            if (audioThread != null) audioThread.interrupt();
            MinecraftClient client = MinecraftClient.getInstance();
            try { client.getTextureManager().destroyTexture(textureId); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    private static void chromaKeyBlit(BufferedImage src, NativeImage dst) {
        int w = Math.min(src.getWidth(), dst.getWidth());
        int h = Math.min(src.getHeight(), dst.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                float greenDominance = g - Math.max(r, b);
                float alpha = 1.0f - smoothstep(22.0f, 92.0f, greenDominance) * smoothstep(70.0f, 140.0f, g);
                int a = MathHelper.clamp((int) (alpha * 255.0f), 0, 255);
                if (a < 8) {
                    a = 0;
                }
                dst.setColor(x, y, nativeRgba(r, g, b, a));
            }
        }
    }

    private static int nativeRgba(int r, int g, int b, int a) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = MathHelper.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
