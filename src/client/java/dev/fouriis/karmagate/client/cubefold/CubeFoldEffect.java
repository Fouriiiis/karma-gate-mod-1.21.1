package dev.fouriis.karmagate.client.cubefold;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.sound.ModSounds;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class CubeFoldEffect {
    private static final int SUBDIV = 24;
    private static final int CAPTURE_FOV_DEGREES = 90;

    private static final float CAPTURE_DEPTH = 1.65f;
    private static final float GROUND_Y_OFFSET = 0.04f;

    private static final long HOLD_MS = 2_000L;
    private static final long UNFOLD_MS = 4_400L;

    // Empirical correction: Minecraft's captured perspective lands slightly wider
    // than the reconstructed cube projection, so nudge UV projection inward.
    private static final float CAPTURE_FIT_SCALE = 1.00f;

    private static final double NEAR_EPSILON = 0.0001;

    private static final Vec3d EAST = new Vec3d(1.0, 0.0, 0.0);
    private static final Vec3d SOUTH = new Vec3d(0.0, 0.0, 1.0);
    private static final Identifier FOLDBACK_SKYBOX_TEXTURE = Identifier.of("karma-gate-mod", "textures/skybox/8-1.png");
    private static final int BARRIER_PLATFORM_RADIUS = 1;
    private static final double SKY_SPHERE_RADIUS = 24.0;
    private static final int SKY_LAT_SEGMENTS = 16;
    private static final int SKY_LON_SEGMENTS = 32;
    private static final SoundCategory FOLD_SONG_CATEGORY = SoundCategory.MUSIC;

    private static boolean active = false;
    private static boolean pendingCapture = false;
    private static int captureStep = -1;
    private static long startMs = 0L;

    private static final EnumMap<Face, NativeImageBackedTexture> captureTextures = new EnumMap<>(Face.class);
    private static final EnumMap<Face, Identifier> captureTextureIds = new EnumMap<>(Face.class);
    private static final EnumMap<Face, FaceBasis> faceBases = new EnumMap<>(Face.class);
    private static final EnumMap<Face, Vec3d> faceEyes = new EnumMap<>(Face.class);

    private static Vec3d eyeAnchor = Vec3d.ZERO;
    // Anchor used for rendering the fold geometry in world space.
    private static Vec3d groundAnchor = Vec3d.ZERO;
    // Anchor frozen at capture time for UV reprojection math.
    private static Vec3d captureGroundAnchor = Vec3d.ZERO;

    private static CaptureRestore captureRestore;
    private static NativeImageBackedTexture captureOverlayTexture;
    private static Identifier captureOverlayTextureId;
    private static boolean captureOverlayVisible = false;
    private static int captureOverlayWidth = 1;
    private static int captureOverlayHeight = 1;
    private static NativeImage queuedOverlayImage;
    private static int queuedOverlayWidth = 1;
    private static int queuedOverlayHeight = 1;
    private static float captureTanHalfX = 1.0f;
    private static float captureTanHalfY = 1.0f;
    private static boolean captureVelocityFrozen = false;
    private static Vec3d savedCaptureVelocity = Vec3d.ZERO;
    private static boolean foldSongPlaying = false;
    private static boolean sideLandParticlesSpawned = false;
    private static boolean pendingReturnToSavedPos = false;

    private static boolean isolatedSceneActive = false;
    private static Vec3d savedPlayerPos = Vec3d.ZERO;
    private static Vec3d savedPlayerVelocity = Vec3d.ZERO;
    private static float savedPlayerYaw = 0.0f;
    private static float savedPlayerPitch = 0.0f;
    private static CloudRenderMode savedCloudRenderMode;
    private static Boolean savedViewBobbing;
    private static Integer savedViewDistance;
    private static final Set<BlockPos> placedBarrierPlatform = new HashSet<>();

    private static float cubeHalfSize;

    private static final CaptureView[] CAPTURE_VIEWS = {
            new CaptureView(Face.FRONT, 180.0f, 0.0f),
            new CaptureView(Face.BACK, 0.0f, 0.0f),
            new CaptureView(Face.LEFT, 90.0f, 0.0f),
            new CaptureView(Face.RIGHT, -90.0f, 0.0f),
            new CaptureView(Face.TOP, 0.0f, -90.0f),
            new CaptureView(Face.BOTTOM, 0.0f, 90.0f)
    };

    private CubeFoldEffect() {
    }

    public static void trigger(MinecraftClient client) {
        if (active || pendingCapture) return;
        if (client.world == null || client.player == null || client.getFramebuffer() == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (today.getMonthValue() != 4 || today.getDayOfMonth() != 1) {
            return;
        }

        if (queuedOverlayImage != null) {
            queuedOverlayImage.close();
            queuedOverlayImage = null;
        }
        queuedOverlayWidth = Math.max(1, client.getWindow().getScaledWidth());
        queuedOverlayHeight = Math.max(1, client.getWindow().getScaledHeight());
        queuedOverlayImage = resampleToSize(
                ScreenshotRecorder.takeScreenshot(client.getFramebuffer()),
                queuedOverlayWidth,
                queuedOverlayHeight
        );

        pendingCapture = true;
    }

    public static boolean shouldSuppressExtraWorldRender() {
        return active || pendingCapture;
    }

    public static void tick(MinecraftClient client) {
        applyPendingReturn(client);

        if (!active) return;
        if (client.world == null) {
            clearForWorldTransition();
            return;
        }

        if (client.player == null || !client.player.isAlive()) {
            clearForWorldTransition();
            return;
        }

        updateBarrierPlatformAroundPlayer(client);

        AnimationState state = animationState();
        if (!foldSongPlaying && state.unfoldT() >= 1.0f) {
            if (!sideLandParticlesSpawned) {
                spawnSideLandParticles(client);
                sideLandParticlesSpawned = true;
            }
            startFoldSong(client);
        }
    }

    public static void clear() {
        clearInternal();
    }

    public static void clearForWorldTransition() {
        pendingReturnToSavedPos = pendingReturnToSavedPos || isolatedSceneActive;
        clearInternal();
    }

    private static void clearInternal() {
        MinecraftClient client = MinecraftClient.getInstance();
        stopFoldSong(client);
        if (savedCloudRenderMode != null && client != null) {
            client.options.getCloudRenderMode().setValue(savedCloudRenderMode);
            savedCloudRenderMode = null;
        }
        if (savedViewBobbing != null && client != null) {
            client.options.getBobView().setValue(savedViewBobbing);
            savedViewBobbing = null;
        }
        restoreCaptureVelocity(client);
        exitIsolatedScene(client);
        restoreCaptureState(client);
        active = false;
        pendingCapture = false;
        captureStep = -1;
        startMs = 0L;
        clearCaptureTextures();
        faceBases.clear();
        faceEyes.clear();
        groundAnchor = Vec3d.ZERO;
        captureGroundAnchor = Vec3d.ZERO;
        sideLandParticlesSpawned = false;
    }

    private static void beginCapture(MinecraftClient client) {
        clearCaptureTextures();
        faceBases.clear();
        faceEyes.clear();

        Entity cameraEntity = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
        if (cameraEntity == null) {
            clear();
            return;
        }

        captureRestore = new CaptureRestore(
                cameraEntity,
                cameraEntity.getYaw(1.0f),
                cameraEntity.getPitch(1.0f),
                client.options.hudHidden,
                client.options.getFov().getValue()
        );

        // Freeze the visible frame (including HUD/hand) before the rotation capture starts.
        NativeImage overlayImage;
        if (queuedOverlayImage != null) {
            overlayImage = queuedOverlayImage;
            captureOverlayWidth = queuedOverlayWidth;
            captureOverlayHeight = queuedOverlayHeight;
            queuedOverlayImage = null;
            queuedOverlayWidth = 1;
            queuedOverlayHeight = 1;
        } else {
            int scaledW = Math.max(1, client.getWindow().getScaledWidth());
            int scaledH = Math.max(1, client.getWindow().getScaledHeight());
            overlayImage = resampleToSize(ScreenshotRecorder.takeScreenshot(client.getFramebuffer()), scaledW, scaledH);
            captureOverlayWidth = scaledW;
            captureOverlayHeight = scaledH;
        }
        captureOverlayTexture = new NativeImageBackedTexture(overlayImage);
        captureOverlayTexture.upload();
        captureOverlayTexture.setFilter(false, false);
        captureOverlayTextureId = client.getTextureManager().registerDynamicTexture(
                "karmagate_cube_fold_overlay",
                captureOverlayTexture
        );
        captureOverlayVisible = true;

        client.options.hudHidden = true;
        client.options.getFov().setValue(CAPTURE_FOV_DEGREES);

        Entity player = client.player;
        if (player == null) {
            clear();
            return;
        }

        // Freeze velocity while the six faces are captured to prevent movement tearing.
        savedCaptureVelocity = player.getVelocity();
        captureVelocityFrozen = true;
        player.setVelocity(Vec3d.ZERO);

        eyeAnchor = cameraEntity.getCameraPosVec(1.0f);
        groundAnchor = player.getPos().add(0.0, GROUND_Y_OFFSET, 0.0);
        captureGroundAnchor = groundAnchor;

        double tanHalfY = Math.tan(Math.toRadians(CAPTURE_FOV_DEGREES) * 0.5);
        double tanHalfX = Math.tan(Math.toRadians(CAPTURE_FOV_DEGREES) * 0.5);

        // Captures are center-cropped to square, so horizontal and vertical
        // projection use the same 90-degree half-angle.
        cubeHalfSize = (float) (CAPTURE_DEPTH * tanHalfY);
        captureTanHalfY = (float) (tanHalfY * CAPTURE_FIT_SCALE);
        captureTanHalfX = (float) (tanHalfX * CAPTURE_FIT_SCALE);

        captureStep = 0;
        orientCameraForCapture(cameraEntity, CAPTURE_VIEWS[captureStep]);
    }

    private static void captureCurrentView(MinecraftClient client, WorldRenderContext context) {
        if (captureStep < 0 || captureStep >= CAPTURE_VIEWS.length) {
            return;
        }

        CaptureView view = CAPTURE_VIEWS[captureStep];
        NativeImage image = centerCropSquare(ScreenshotRecorder.takeScreenshot(client.getFramebuffer()));
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        texture.upload();
        texture.setFilter(false, false);

        NativeImageBackedTexture old = captureTextures.put(view.face(), texture);
        if (old != null) {
            old.close();
        }

        Identifier textureId = client.getTextureManager().registerDynamicTexture(
                "karmagate_cube_fold_" + view.face().name().toLowerCase(),
                texture
        );
        captureTextureIds.put(view.face(), textureId);
        faceBases.put(view.face(), basisFor(view.yaw(), view.pitch()));
        faceEyes.put(view.face(), context.camera().getPos());
    }

    private static void clearCaptureTextures() {
        for (NativeImageBackedTexture texture : captureTextures.values()) {
            texture.close();
        }
        captureTextures.clear();
        captureTextureIds.clear();

        if (captureOverlayTexture != null) {
            captureOverlayTexture.close();
            captureOverlayTexture = null;
        }
        if (queuedOverlayImage != null) {
            queuedOverlayImage.close();
            queuedOverlayImage = null;
        }
        captureOverlayTextureId = null;
        captureOverlayVisible = false;
        captureOverlayWidth = 1;
        captureOverlayHeight = 1;
        queuedOverlayWidth = 1;
        queuedOverlayHeight = 1;
    }

    private static NativeImage centerCropSquare(NativeImage src) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int side = Math.max(1, Math.min(srcW, srcH));

        int offsetX = (srcW - side) / 2;
        int offsetY = (srcH - side) / 2;

        NativeImage out = new NativeImage(side, side, true);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int color = src.getColor(offsetX + x, offsetY + y) | 0xFF000000;
                out.setColor(x, y, color);
            }
        }

        src.close();
        return out;
    }

    private static NativeImage resampleToSize(NativeImage src, int targetW, int targetH) {
        NativeImage out = new NativeImage(targetW, targetH, true);
        int srcW = Math.max(1, src.getWidth());
        int srcH = Math.max(1, src.getHeight());

        for (int y = 0; y < targetH; y++) {
            int srcY = Math.min(srcH - 1, (int) ((long) y * srcH / targetH));
            for (int x = 0; x < targetW; x++) {
                int srcX = Math.min(srcW - 1, (int) ((long) x * srcW / targetW));
                int color = src.getColor(srcX, srcY) | 0xFF000000;
                out.setColor(x, y, color);
            }
        }

        src.close();
        return out;
    }

    private static void orientCameraForCapture(Entity cameraEntity, CaptureView view) {
        cameraEntity.setYaw(view.yaw());
        cameraEntity.setPitch(view.pitch());
    }

    private static void restoreCaptureState(MinecraftClient client) {
        if (captureRestore == null) {
            return;
        }

        client.options.hudHidden = captureRestore.hudHidden();
        client.options.getFov().setValue(captureRestore.fov());

        Entity cameraEntity = captureRestore.cameraEntity();
        if (cameraEntity != null) {
            cameraEntity.setYaw(captureRestore.yaw());
            cameraEntity.setPitch(captureRestore.pitch());
        }

        captureRestore = null;
    }

    private static void restoreCaptureVelocity(MinecraftClient client) {
        if (!captureVelocityFrozen) {
            return;
        }

        if (client.player != null) {
            client.player.setVelocity(savedCaptureVelocity);
        }

        captureVelocityFrozen = false;
        savedCaptureVelocity = Vec3d.ZERO;
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || context.camera() == null || context.matrixStack() == null) {
            if (active || pendingCapture) {
                clearForWorldTransition();
            }
            return;
        }

        // Capture is deferred to END so shader pipelines (e.g. Iris) have already
        // composed the final world image into the framebuffer.
        if (pendingCapture) {
            return;
        }

        if (!active || captureTextureIds.isEmpty()) {
            return;
        }

        if (captureOverlayVisible) {
            captureOverlayVisible = false;
        }

        AnimationState state = animationState();

        Vec3d camPos = context.camera().getPos();
        Matrix4f positionMatrix = context.matrixStack().peek().getPositionMatrix();
        renderFallbackSkybox(camPos, positionMatrix);
        renderFoldGeometry(camPos, positionMatrix, state.unfoldT(), state.alpha());
    }

    private static AnimationState animationState() {
        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed < 0L) elapsed = 0L;

        float unfoldT;
        if (elapsed <= HOLD_MS) {
            unfoldT = 0.0f;
        } else {
            unfoldT = (elapsed - HOLD_MS) / (float) UNFOLD_MS;
            unfoldT = MathHelper.clamp(unfoldT, 0.0f, 1.0f);
        }

        return new AnimationState(unfoldT, 1.0f);
    }

    private static void renderFallbackSkybox(Vec3d camPos, Matrix4f positionMatrix) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, Identifier.of("karma-gate-mod", "textures/skybox/planet6.png"));

        BufferBuilder skyBuffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.TRIANGLES,
                VertexFormats.POSITION_TEXTURE_COLOR
        );

        for (int lat = 0; lat < SKY_LAT_SEGMENTS; lat++) {
            float v0 = lat / (float) SKY_LAT_SEGMENTS;
            float v1 = (lat + 1) / (float) SKY_LAT_SEGMENTS;
            double phi0 = v0 * Math.PI;
            double phi1 = v1 * Math.PI;

            for (int lon = 0; lon < SKY_LON_SEGMENTS; lon++) {
                float u0 = lon / (float) SKY_LON_SEGMENTS;
                float u1 = (lon + 1) / (float) SKY_LON_SEGMENTS;
                double theta0 = u0 * Math.PI * 2.0;
                double theta1 = u1 * Math.PI * 2.0;

                Vec3d p00 = skyPoint(camPos, phi0, theta0);
                Vec3d p10 = skyPoint(camPos, phi0, theta1);
                Vec3d p11 = skyPoint(camPos, phi1, theta1);
                Vec3d p01 = skyPoint(camPos, phi1, theta0);

                putTex(skyBuffer, positionMatrix, camPos, p00, u0, v0, 255);
                putTex(skyBuffer, positionMatrix, camPos, p10, u1, v0, 255);
                putTex(skyBuffer, positionMatrix, camPos, p11, u1, v1, 255);

                putTex(skyBuffer, positionMatrix, camPos, p00, u0, v0, 255);
                putTex(skyBuffer, positionMatrix, camPos, p11, u1, v1, 255);
                putTex(skyBuffer, positionMatrix, camPos, p01, u0, v1, 255);
            }
        }

        BufferRenderer.drawWithGlobalProgram(skyBuffer.end());
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static Vec3d skyPoint(Vec3d center, double phi, double theta) {
        double sinPhi = Math.sin(phi);
        double x = sinPhi * Math.sin(theta);
        double y = Math.cos(phi);
        double z = sinPhi * Math.cos(theta);
        return center.add(x * SKY_SPHERE_RADIUS, y * SKY_SPHERE_RADIUS, z * SKY_SPHERE_RADIUS);
    }

    private static void renderFoldGeometry(Vec3d camPos, Matrix4f positionMatrix, float unfoldT, float alpha) {
        Vec3d bottomAnchor = groundAnchor;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // Shader packs can leave first-person depth in the buffer even when hand color is absent.
        // Render fold panels without depth testing to avoid hand-shaped cutouts through the cube.
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder blackBuffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.TRIANGLES,
                VertexFormats.POSITION_COLOR
        );
        boolean wroteBlack = false;

        for (Face face : Face.values()) {
            for (int ix = 0; ix < SUBDIV; ix++) {
                float s0 = -1.0f + 2.0f * ix / (float) SUBDIV;
                float s1 = -1.0f + 2.0f * (ix + 1) / (float) SUBDIV;

                for (int iy = 0; iy < SUBDIV; iy++) {
                    float t0 = -1.0f + 2.0f * iy / (float) SUBDIV;
                    float t1 = -1.0f + 2.0f * (iy + 1) / (float) SUBDIV;

                    PanelVertex v00 = buildVertex(face, s0, t0, unfoldT, bottomAnchor);
                    PanelVertex v10 = buildVertex(face, s1, t0, unfoldT, bottomAnchor);
                    PanelVertex v11 = buildVertex(face, s1, t1, unfoldT, bottomAnchor);
                    PanelVertex v01 = buildVertex(face, s0, t1, unfoldT, bottomAnchor);

                    int shellAlpha = (int) (alpha * 255.0f);
                    if (shellAlpha <= 0) continue;

                    putColor(blackBuffer, positionMatrix, camPos, v00.worldPos(), shellAlpha);
                    putColor(blackBuffer, positionMatrix, camPos, v10.worldPos(), shellAlpha);
                    putColor(blackBuffer, positionMatrix, camPos, v11.worldPos(), shellAlpha);

                    putColor(blackBuffer, positionMatrix, camPos, v00.worldPos(), shellAlpha);
                    putColor(blackBuffer, positionMatrix, camPos, v11.worldPos(), shellAlpha);
                    putColor(blackBuffer, positionMatrix, camPos, v01.worldPos(), shellAlpha);

                    wroteBlack = true;
                }
            }
        }

        if (wroteBlack) {
            BufferRenderer.drawWithGlobalProgram(blackBuffer.end());
        }

        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        int texAlpha = (int) (alpha * 255.0f);

        for (Face face : Face.values()) {
            Identifier textureId = captureTextureIds.get(face);
            if (textureId == null) {
                continue;
            }

            RenderSystem.setShaderTexture(0, textureId);
            BufferBuilder texBuffer = Tessellator.getInstance().begin(
                    VertexFormat.DrawMode.TRIANGLES,
                    VertexFormats.POSITION_TEXTURE_COLOR
            );

            for (int ix = 0; ix < SUBDIV; ix++) {
                float s0 = -1.0f + 2.0f * ix / (float) SUBDIV;
                float s1 = -1.0f + 2.0f * (ix + 1) / (float) SUBDIV;

                for (int iy = 0; iy < SUBDIV; iy++) {
                    float t0 = -1.0f + 2.0f * iy / (float) SUBDIV;
                    float t1 = -1.0f + 2.0f * (iy + 1) / (float) SUBDIV;

                    PanelVertex v00 = buildVertex(face, s0, t0, unfoldT, bottomAnchor);
                    PanelVertex v10 = buildVertex(face, s1, t0, unfoldT, bottomAnchor);
                    PanelVertex v11 = buildVertex(face, s1, t1, unfoldT, bottomAnchor);
                    PanelVertex v01 = buildVertex(face, s0, t1, unfoldT, bottomAnchor);

                    putTex(texBuffer, positionMatrix, camPos, v00.worldPos(), v00.u(), v00.v(), texAlpha);
                    putTex(texBuffer, positionMatrix, camPos, v10.worldPos(), v10.u(), v10.v(), texAlpha);
                    putTex(texBuffer, positionMatrix, camPos, v11.worldPos(), v11.u(), v11.v(), texAlpha);

                    putTex(texBuffer, positionMatrix, camPos, v00.worldPos(), v00.u(), v00.v(), texAlpha);
                    putTex(texBuffer, positionMatrix, camPos, v11.worldPos(), v11.u(), v11.v(), texAlpha);
                    putTex(texBuffer, positionMatrix, camPos, v01.worldPos(), v01.u(), v01.v(), texAlpha);
                }
            }

            BufferRenderer.drawWithGlobalProgram(texBuffer.end());
        }

        RenderSystem.disableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void enterIsolatedScene(MinecraftClient client) {
        if (isolatedSceneActive || client.player == null || client.world == null) {
            return;
        }

        Entity player = client.player;
        savedPlayerPos = player.getPos();
        savedPlayerVelocity = player.getVelocity();
        savedPlayerYaw = player.getYaw();
        savedPlayerPitch = player.getPitch();
        savedCloudRenderMode = client.options.getCloudRenderMode().getValue();
        savedViewBobbing = client.options.getBobView().getValue();
        savedViewDistance = client.options.getViewDistance().getValue();

        int supportY = client.world.getTopY() - 1;
        int centerX = MathHelper.floor(savedPlayerPos.x);
        int centerZ = MathHelper.floor(savedPlayerPos.z);
        BlockPos supportCenter = new BlockPos(centerX, supportY, centerZ);
        updateBarrierPlatformAroundCenter(client, supportCenter);

        player.setVelocity(Vec3d.ZERO);
        player.setPosition(savedPlayerPos.x, supportY + 1.0, savedPlayerPos.z);
        client.options.getCloudRenderMode().setValue(CloudRenderMode.OFF);
        client.options.getBobView().setValue(false);
        client.options.getViewDistance().setValue(4);
        groundAnchor = player.getPos().add(0.0, GROUND_Y_OFFSET, 0.0);
        isolatedSceneActive = true;
        pendingReturnToSavedPos = false;
    }

    private static void exitIsolatedScene(MinecraftClient client) {
        clearBarrierPlatform(client);

        if (!isolatedSceneActive || client.player == null) {
            isolatedSceneActive = false;
            return;
        }

        if (savedCloudRenderMode != null) {
            client.options.getCloudRenderMode().setValue(savedCloudRenderMode);
            savedCloudRenderMode = null;
        }
        if (savedViewBobbing != null) {
            client.options.getBobView().setValue(savedViewBobbing);
            savedViewBobbing = null;
        }
        isolatedSceneActive = false;
    }

    private static void updateBarrierPlatformAroundPlayer(MinecraftClient client) {
        if (!isolatedSceneActive || client.player == null || client.world == null) {
            return;
        }

        int y = client.world.getTopY() - 1;
        // If barrier blocks are broken and the player drops, snap them back to the support plane.
        if (client.player.getY() < y + 1.0) {
            client.player.setVelocity(0.0, Math.max(0.0, client.player.getVelocity().y), 0.0);
            client.player.setPosition(client.player.getX(), y + 1.0, client.player.getZ());
        }

        BlockPos center = new BlockPos(MathHelper.floor(client.player.getX()), y, MathHelper.floor(client.player.getZ()));
        updateBarrierPlatformAroundCenter(client, center);
    }

    private static void updateBarrierPlatformAroundCenter(MinecraftClient client, BlockPos center) {
        if (client.world == null) {
            return;
        }

        Set<BlockPos> nextPlatform = new HashSet<>();

        for (int dx = -BARRIER_PLATFORM_RADIUS; dx <= BARRIER_PLATFORM_RADIUS; dx++) {
            for (int dz = -BARRIER_PLATFORM_RADIUS; dz <= BARRIER_PLATFORM_RADIUS; dz++) {
                nextPlatform.add(center.add(dx, 0, dz).toImmutable());
            }
        }

        // Remove old barrier blocks from the client-side tracked set
        for (BlockPos pos : placedBarrierPlatform) {
            if (!nextPlatform.contains(pos) && client.world.getBlockState(pos).isOf(Blocks.BARRIER)) {
                client.world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            }
        }

        // Send the platform update to the server
        dev.fouriis.karmagate.client.network.ClientNetworking.sendUpdateBarrierPlatform(center, nextPlatform);

        placedBarrierPlatform.clear();
        placedBarrierPlatform.addAll(nextPlatform);
    }

    private static void applyPendingReturn(MinecraftClient client) {
        if (!pendingReturnToSavedPos || client.world == null || client.player == null || !client.player.isAlive()) {
            return;
        }

        Entity player = client.player;
        player.setPosition(savedPlayerPos.x, savedPlayerPos.y, savedPlayerPos.z);
        player.setVelocity(savedPlayerVelocity);
        player.setYaw(savedPlayerYaw);
        player.setPitch(savedPlayerPitch);
        pendingReturnToSavedPos = false;

        if (savedCloudRenderMode != null) {
            client.options.getCloudRenderMode().setValue(savedCloudRenderMode);
            savedCloudRenderMode = null;
        }
        if (savedViewBobbing != null) {
            client.options.getBobView().setValue(savedViewBobbing);
            savedViewBobbing = null;
        }
        if (savedViewDistance != null) {
            client.options.getViewDistance().setValue(savedViewDistance);
            savedViewDistance = null;
        }
    }

    private static void spawnSideLandParticles(MinecraftClient client) {
        if (client.world == null) {
            return;
        }

        double y = groundAnchor.y + 0.03;
        double h = cubeHalfSize;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Four side panels land as horizontal strips around the center panel.
        for (int i = 0; i < 80; i++) {
            double s = random.nextDouble(-h, h);
            double t = random.nextDouble(-h, h);

            addCampfirePuff(client, groundAnchor.x + s, y, groundAnchor.z - 2.0 * h + t);
            addCampfirePuff(client, groundAnchor.x + s, y, groundAnchor.z + 2.0 * h + t);
            addCampfirePuff(client, groundAnchor.x - 2.0 * h + t, y, groundAnchor.z + s);
            addCampfirePuff(client, groundAnchor.x + 2.0 * h + t, y, groundAnchor.z + s);

            // Also trail on the center (bottom) panel.
            addCampfirePuff(client, groundAnchor.x + s, y, groundAnchor.z + t);
        }
    }

    private static void addCampfirePuff(MinecraftClient client, double x, double y, double z) {
        double vx = (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.01;
        double vy = -0.02 + ThreadLocalRandom.current().nextDouble() * 0.02;
        double vz = (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.01;
        client.world.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, vx, vy, vz);
    }

    private static void clearBarrierPlatform(MinecraftClient client) {
        if (client.world == null || placedBarrierPlatform.isEmpty()) {
            placedBarrierPlatform.clear();
            return;
        }

        for (BlockPos pos : placedBarrierPlatform) {
            if (client.world.getBlockState(pos).isOf(Blocks.BARRIER)) {
                client.world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            }
        }
        placedBarrierPlatform.clear();
    }

    public static void renderCaptureOverlay(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (!captureOverlayVisible || captureOverlayTextureId == null) {
            return;
        }

        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        context.drawTexture(captureOverlayTextureId, 0, 0, 0, 0, width, height, captureOverlayWidth, captureOverlayHeight);
    }

    public static void onEndFrame(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!pendingCapture) {
            return;
        }

        if (client.world == null || client.player == null || context.camera() == null) {
            clearForWorldTransition();
            return;
        }

        Entity cameraEntity = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
        if (cameraEntity == null) {
            clearForWorldTransition();
            return;
        }

        if (captureStep < 0) {
            beginCapture(client);
            return;
        }

        captureCurrentView(client, context);
        captureStep++;

        if (captureStep >= CAPTURE_VIEWS.length) {
            restoreCaptureState(client);
            restoreCaptureVelocity(client);
            pendingCapture = false;
            active = true;
            enterIsolatedScene(client);
            captureStep = -1;
            startMs = System.currentTimeMillis();
            return;
        }

        orientCameraForCapture(cameraEntity, CAPTURE_VIEWS[captureStep]);
    }

    private static PanelVertex buildVertex(Face face, float s, float t, float unfoldT, Vec3d bottomAnchor) {
        Vec3d localNow = localPose(face, s, t, unfoldT);
        Vec3d worldNow = bottomAnchor.add(localNow);

        FaceBasis basis = faceBases.get(face);
        if (basis == null) {
            return new PanelVertex(worldNow, 0.5f, 0.5f);
        }

        Vec3d closedWorld = captureGroundAnchor.add(localPose(face, s, t, 0.0f));
        Vec3d faceEye = faceEyes.getOrDefault(face, eyeAnchor);
        Vec3d d = closedWorld.subtract(faceEye);

        double camX = d.dotProduct(basis.right());
        double camY = d.dotProduct(basis.up());
        double camZ = Math.max(NEAR_EPSILON, d.dotProduct(basis.forward()));

        float u = (float) (0.5 + (camX / (2.0 * camZ * captureTanHalfX)));
        float v = (float) (0.5 - (camY / (2.0 * camZ * captureTanHalfY)));

        return new PanelVertex(worldNow, MathHelper.clamp(u, 0.0f, 1.0f), MathHelper.clamp(v, 0.0f, 1.0f));
    }

    /**
     * Local coordinates relative to the bottom-face center.
     *
     * Unfolded net:
     *          TOP
     *         FRONT
     * LEFT   BOTTOM   RIGHT
     *         BACK
     */
    private static Vec3d localPose(Face face, float s, float t, float unfoldT) {
        float h = cubeHalfSize;
        float x = s * h;

        float angle = 90.0f * unfoldT;

        return switch (face) {
            case BOTTOM -> new Vec3d(x, 0.0, -t * h);

            case FRONT -> {
                Vec3d pClosed = new Vec3d(x, h + (t * h), -h);
                yield rotateAroundLine(pClosed, new Vec3d(0.0, 0.0, -h), EAST, -angle);
            }

            case BACK -> {
                Vec3d pClosed = new Vec3d(-x, h + (t * h), h);
                yield rotateAroundLine(pClosed, new Vec3d(0.0, 0.0, h), EAST, angle);
            }

            case LEFT -> {
                Vec3d pClosed = new Vec3d(-h, h + (t * h), -x);
                yield rotateAroundLine(pClosed, new Vec3d(-h, 0.0, 0.0), SOUTH, angle);
            }

            case RIGHT -> {
                Vec3d pClosed = new Vec3d(h, h + (t * h), x);
                yield rotateAroundLine(pClosed, new Vec3d(h, 0.0, 0.0), SOUTH, -angle);
            }

            case TOP -> {
                Vec3d pClosed = new Vec3d(x, 2.0f * h, t * h);

                Vec3d carried = rotateAroundLine(
                        pClosed,
                        new Vec3d(0.0, 0.0, -h),
                        EAST,
                        -angle
                );

                Vec3d movedTopHingePoint = rotateAroundLine(
                        new Vec3d(0.0, 2.0f * h, -h),
                        new Vec3d(0.0, 0.0, -h),
                        EAST,
                        -angle
                );

                yield rotateAroundLine(carried, movedTopHingePoint, EAST, -angle);
            }
        };
    }

    private static Vec3d rotateAroundLine(Vec3d point, Vec3d linePoint, Vec3d axis, float angleDeg) {
        Vec3d rel = point.subtract(linePoint);
        Vec3d rot = rotateAroundAxis(rel, axis, angleDeg);
        return linePoint.add(rot);
    }

    private static Vec3d rotateAroundAxis(Vec3d v, Vec3d axis, float angleDeg) {
        Vec3d k = axis.normalize();
        double rad = Math.toRadians(angleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        Vec3d term1 = v.multiply(cos);
        Vec3d term2 = k.crossProduct(v).multiply(sin);
        Vec3d term3 = k.multiply(k.dotProduct(v) * (1.0 - cos));
        return term1.add(term2).add(term3);
    }

    private static FaceBasis basisFor(float yaw, float pitch) {
        Vec3d forward = Vec3d.fromPolar(pitch, yaw).normalize();
        Vec3d right = Vec3d.fromPolar(0.0f, yaw + 90.0f).normalize();
        Vec3d up = right.crossProduct(forward).normalize();
        return new FaceBasis(forward, right, up);
    }

    private static void putTex(BufferBuilder buffer,
                               Matrix4f mat,
                               Vec3d cameraPos,
                               Vec3d worldPos,
                               float u,
                               float v,
                               int alpha) {
        float x = (float) (worldPos.x - cameraPos.x);
        float y = (float) (worldPos.y - cameraPos.y);
        float z = (float) (worldPos.z - cameraPos.z);

        buffer.vertex(mat, x, y, z)
                .texture(u, v)
                .color(255, 255, 255, alpha);
    }

    private static void putColor(BufferBuilder buffer,
                                 Matrix4f mat,
                                 Vec3d cameraPos,
                                 Vec3d worldPos,
                                 int alpha) {
        float x = (float) (worldPos.x - cameraPos.x);
        float y = (float) (worldPos.y - cameraPos.y);
        float z = (float) (worldPos.z - cameraPos.z);

        buffer.vertex(mat, x, y, z)
                .color(0, 0, 0, alpha);
    }


    private static void startFoldSong(MinecraftClient client) {
        if (foldSongPlaying || client == null || client.getSoundManager() == null) {
            return;
        }

        client.getSoundManager().stopSounds(
                ModSounds.THE_HORIZON_OF_EVENTS_WITHOUT_BURNIN_IN_QUESTION_FROM_A_PIERCE_HER,
                FOLD_SONG_CATEGORY
        );
        client.getSoundManager().play(
                net.minecraft.client.sound.PositionedSoundInstance.master(
                        ModSounds.THE_HORIZON_OF_EVENTS_WITHOUT_BURNIN_IN_QUESTION_FROM_A_PIERCE_HER_EVENT,
                        1.0f,
                        1.0f
                )
        );
        foldSongPlaying = true;
    }

    private static void stopFoldSong(MinecraftClient client) {
        if (!foldSongPlaying || client == null || client.getSoundManager() == null) {
            foldSongPlaying = false;
            return;
        }

        client.getSoundManager().stopSounds(
                ModSounds.THE_HORIZON_OF_EVENTS_WITHOUT_BURNIN_IN_QUESTION_FROM_A_PIERCE_HER,
                FOLD_SONG_CATEGORY
        );
        foldSongPlaying = false;
    }

    private enum Face {
        FRONT,
        BACK,
        LEFT,
        RIGHT,
        TOP,
        BOTTOM
    }

    private record CaptureView(Face face, float yaw, float pitch) {
    }

    private record CaptureRestore(Entity cameraEntity, float yaw, float pitch, boolean hudHidden, int fov) {
    }

    private record FaceBasis(Vec3d forward, Vec3d right, Vec3d up) {
    }

    private record AnimationState(float unfoldT, float alpha) {
    }

    private record PanelVertex(Vec3d worldPos, float u, float v) {
    }
}