package dev.fouriis.karmagate.client.cubefold;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public final class CubeFoldEffect {
    private static final int SUBDIV = 24;

    private static final float CAPTURE_DEPTH = 1.65f;
    private static final float GROUND_Y_OFFSET = 0.04f;

    private static final long HOLD_MS = 150L;
    private static final long UNFOLD_MS = 10_000L;
    private static final long FADE_MS = 450L;
    private static final long TOTAL_MS = HOLD_MS + UNFOLD_MS + FADE_MS;

    // Slightly oversize the projected frustum so the captured image covers any
    // tiny precision mismatch instead of exposing a 1px dark seam.
    private static final float CAPTURE_FIT_SCALE = 1.0035f;

    private static final double NEAR_EPSILON = 0.0001;

    private static final Vec3d EAST = new Vec3d(1.0, 0.0, 0.0);
    private static final Vec3d SOUTH = new Vec3d(0.0, 0.0, 1.0);

    private static boolean active = false;
    private static boolean pendingCapture = false;
    private static long startMs = 0L;

    private static NativeImageBackedTexture captureTexture;
    private static Identifier captureTextureId;

    private static Vec3d eyeAnchor = Vec3d.ZERO;
    private static Vec3d groundAnchor = Vec3d.ZERO;

    private static float captureYaw;
    private static float capturePitch;
    private static float captureFovY;
    private static float captureAspect;

    private static Vec3d captureForward = Vec3d.ZERO;
    private static Vec3d captureRight = Vec3d.ZERO;
    private static Vec3d captureUp = Vec3d.ZERO;

    private static float captureTanHalfX;
    private static float captureTanHalfY;

    private static float cubeHalfSize;

    private CubeFoldEffect() {
    }

    public static void trigger(MinecraftClient client) {
        if (active || pendingCapture) return;
        if (client.world == null || client.player == null || client.getFramebuffer() == null) {
            return;
        }

        pendingCapture = true;
    }

    public static void tick(MinecraftClient client) {
        if (!active) return;

        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed > TOTAL_MS) {
            clear();
        }
    }

    public static void clear() {
        active = false;
        pendingCapture = false;
        startMs = 0L;

        if (captureTexture != null) {
            captureTexture.close();
            captureTexture = null;
        }

        captureTextureId = null;
    }

    private static void captureFromCurrentWorldFramebuffer(MinecraftClient client) {
        if (captureTexture != null) {
            captureTexture.close();
            captureTexture = null;
            captureTextureId = null;
        }

        NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
        captureTexture = new NativeImageBackedTexture(image);
        captureTexture.upload();
        captureTexture.setFilter(false, false);
        captureTextureId = client.getTextureManager().registerDynamicTexture("karmagate_cube_fold_capture", captureTexture);

        Entity cameraEntity = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
        eyeAnchor = cameraEntity.getCameraPosVec(1.0f);
        groundAnchor = client.player.getPos().add(0.0, GROUND_Y_OFFSET, 0.0);

        captureYaw = cameraEntity.getYaw(1.0f);
        capturePitch = cameraEntity.getPitch(1.0f);

        int fbw = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbh = Math.max(1, client.getWindow().getFramebufferHeight());

        captureFovY = (float) client.options.getFov().getValue();
        captureAspect = (float) fbw / (float) fbh;

        captureForward = Vec3d.fromPolar(capturePitch, captureYaw).normalize();
        captureRight = Vec3d.fromPolar(0.0f, captureYaw + 90.0f).normalize();
        captureUp = captureRight.crossProduct(captureForward).normalize();

        double tanHalfY = Math.tan(Math.toRadians(captureFovY) * 0.5);
        float halfHeightAtDepth = (float) (CAPTURE_DEPTH * tanHalfY);
        float halfWidthAtDepth = halfHeightAtDepth * captureAspect;

        cubeHalfSize = Math.max(halfWidthAtDepth, halfHeightAtDepth);

        captureTanHalfY = (float) (tanHalfY * CAPTURE_FIT_SCALE);
        captureTanHalfX = captureTanHalfY * captureAspect;
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || context.camera() == null || context.matrixStack() == null) {
            if (active || pendingCapture) {
                clear();
            }
            return;
        }

        // Capture is deferred to END so shader pipelines (e.g. Iris) have already
        // composed the final world image into the framebuffer.
        if (pendingCapture) {
            return;
        }

        if (!active || captureTextureId == null) {
            return;
        }

        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed < 0L || elapsed > TOTAL_MS) {
            clear();
            return;
        }

        float unfoldT;
        if (elapsed <= HOLD_MS) {
            unfoldT = 0.0f;
        } else {
            unfoldT = (elapsed - HOLD_MS) / (float) UNFOLD_MS;
            unfoldT = MathHelper.clamp(unfoldT, 0.0f, 1.0f);
            unfoldT = easeInOutCubic(unfoldT);
        }

        float alpha = 1.0f;
        if (elapsed > HOLD_MS + UNFOLD_MS) {
            float fadeT = (elapsed - HOLD_MS - UNFOLD_MS) / (float) FADE_MS;
            alpha = 1.0f - MathHelper.clamp(fadeT, 0.0f, 1.0f);
        }

        Vec3d bottomAnchor = groundAnchor;
        Vec3d camPos = context.camera().getPos();
        Matrix4f positionMatrix = context.matrixStack().peek().getPositionMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
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
        RenderSystem.setShaderTexture(0, captureTextureId);

        BufferBuilder texBuffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.TRIANGLES,
                VertexFormats.POSITION_TEXTURE_COLOR
        );
        boolean wroteTex = false;
        int texAlpha = (int) (alpha * 255.0f);

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

                    if (emitClippedTriangle(texBuffer, positionMatrix, camPos, v00, v10, v11, texAlpha)) {
                        wroteTex = true;
                    }
                    if (emitClippedTriangle(texBuffer, positionMatrix, camPos, v00, v11, v01, texAlpha)) {
                        wroteTex = true;
                    }
                }
            }
        }

        if (wroteTex) {
            BufferRenderer.drawWithGlobalProgram(texBuffer.end());
        }

        RenderSystem.disableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    public static void onEndFrame(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!pendingCapture) {
            return;
        }

        if (client.world == null || client.player == null || context.camera() == null) {
            clear();
            return;
        }

        captureFromCurrentWorldFramebuffer(client);
        pendingCapture = false;
        active = true;
        startMs = System.currentTimeMillis();
    }

    private static boolean emitClippedTriangle(BufferBuilder buffer,
                                               Matrix4f mat,
                                               Vec3d cameraPos,
                                               PanelVertex a,
                                               PanelVertex b,
                                               PanelVertex c,
                                               int alpha) {
        if (alpha <= 0) return false;

        List<PanelVertex> poly = new ArrayList<>(3);
        poly.add(a);
        poly.add(b);
        poly.add(c);

        poly = clipAgainstPlane(poly, ClipPlane.NEAR);
        poly = clipAgainstPlane(poly, ClipPlane.LEFT);
        poly = clipAgainstPlane(poly, ClipPlane.RIGHT);
        poly = clipAgainstPlane(poly, ClipPlane.BOTTOM);
        poly = clipAgainstPlane(poly, ClipPlane.TOP);

        if (poly.size() < 3) {
            return false;
        }

        PanelVertex v0 = poly.get(0);
        for (int i = 1; i < poly.size() - 1; i++) {
            PanelVertex v1 = poly.get(i);
            PanelVertex v2 = poly.get(i + 1);

            putTex(buffer, mat, cameraPos, v0.worldPos(), uOf(v0), vOf(v0), alpha);
            putTex(buffer, mat, cameraPos, v1.worldPos(), uOf(v1), vOf(v1), alpha);
            putTex(buffer, mat, cameraPos, v2.worldPos(), uOf(v2), vOf(v2), alpha);
        }

        return true;
    }

    private static List<PanelVertex> clipAgainstPlane(List<PanelVertex> input, ClipPlane plane) {
        if (input.isEmpty()) {
            return input;
        }

        List<PanelVertex> output = new ArrayList<>(input.size() + 2);
        PanelVertex prev = input.get(input.size() - 1);
        double prevDist = planeDistance(prev, plane);
        boolean prevInside = prevDist >= 0.0;

        for (PanelVertex curr : input) {
            double currDist = planeDistance(curr, plane);
            boolean currInside = currDist >= 0.0;

            if (prevInside && currInside) {
                output.add(curr);
            } else if (prevInside && !currInside) {
                output.add(intersect(prev, curr, prevDist, currDist));
            } else if (!prevInside && currInside) {
                output.add(intersect(prev, curr, prevDist, currDist));
                output.add(curr);
            }

            prev = curr;
            prevDist = currDist;
            prevInside = currInside;
        }

        return output;
    }

    private static double planeDistance(PanelVertex v, ClipPlane plane) {
        double x = v.camX();
        double y = v.camY();
        double z = v.camZ();

        return switch (plane) {
            case NEAR -> z - NEAR_EPSILON;
            case LEFT -> x + z * captureTanHalfX;
            case RIGHT -> -x + z * captureTanHalfX;
            case BOTTOM -> y + z * captureTanHalfY;
            case TOP -> -y + z * captureTanHalfY;
        };
    }

    private static PanelVertex intersect(PanelVertex a,
                                         PanelVertex b,
                                         double da,
                                         double db) {
        double denom = da - db;
        double t = Math.abs(denom) < 1.0e-9 ? 0.0 : da / denom;
        t = MathHelper.clamp((float) t, 0.0f, 1.0f);

        Vec3d worldPos = lerp(a.worldPos(), b.worldPos(), t);
        double camX = MathHelper.lerp((float) t, (float) a.camX(), (float) b.camX());
        double camY = MathHelper.lerp((float) t, (float) a.camY(), (float) b.camY());
        double camZ = MathHelper.lerp((float) t, (float) a.camZ(), (float) b.camZ());

        return new PanelVertex(worldPos, camX, camY, camZ);
    }

    private static Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                MathHelper.lerp((float) t, (float) a.x, (float) b.x),
                MathHelper.lerp((float) t, (float) a.y, (float) b.y),
                MathHelper.lerp((float) t, (float) a.z, (float) b.z)
        );
    }

    private static float uOf(PanelVertex v) {
        float u = (float) (0.5 + (v.camX() / (2.0 * v.camZ() * captureTanHalfX)));
        return MathHelper.clamp(u, 0.0f, 1.0f);
    }

    private static float vOf(PanelVertex v) {
        float vv = (float) (0.5 - (v.camY() / (2.0 * v.camZ() * captureTanHalfY)));
        return MathHelper.clamp(vv, 0.0f, 1.0f);
    }

    private static PanelVertex buildVertex(Face face, float s, float t, float unfoldT, Vec3d bottomAnchor) {
        Vec3d localNow = localPose(face, s, t, unfoldT);
        Vec3d worldNow = bottomAnchor.add(localNow);

        Vec3d closedWorld = bottomAnchor.add(localPose(face, s, t, 0.0f));
        Vec3d d = closedWorld.subtract(eyeAnchor);

        double camX = d.dotProduct(captureRight);
        double camY = d.dotProduct(captureUp);
        double camZ = d.dotProduct(captureForward);

        return new PanelVertex(worldNow, camX, camY, camZ);
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

    private static float easeInOutCubic(float t) {
        return t < 0.5f
                ? 4.0f * t * t * t
                : 1.0f - (float) Math.pow(-2.0f * t + 2.0f, 3.0) / 2.0f;
    }

    private enum Face {
        FRONT,
        BACK,
        LEFT,
        RIGHT,
        TOP,
        BOTTOM
    }

    private enum ClipPlane {
        NEAR,
        LEFT,
        RIGHT,
        BOTTOM,
        TOP
    }

    private record PanelVertex(Vec3d worldPos, double camX, double camY, double camZ) {
    }
}