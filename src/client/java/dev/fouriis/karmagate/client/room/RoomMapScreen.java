package dev.fouriis.karmagate.client.room;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C# Rain World-style map screen, translated to a 3D Minecraft room view.
 *
 * Visual rules:
 * - black HUD backdrop
 * - cyan/white revealed geometry
 * - player-centred initial view
 * - gradual reveal wave from the player's world position
 * - per-line fade-in instead of instant radius popping
 */
public class RoomMapScreen extends Screen {

    private static final float SHAPE_UNIT = 16.0f;
    private static final float INV_SHAPE_UNIT = 1.0f / SHAPE_UNIT;

    // Rain World map-ish palette.
    private static final float MAP_R = 0.58f;
    private static final float MAP_G = 0.91f;
    private static final float MAP_B = 1.00f;

    private static final float WHITE_R = 1.00f;
    private static final float WHITE_G = 1.00f;
    private static final float WHITE_B = 1.00f;

    private static final float DIM_R = 0.06f;
    private static final float DIM_G = 0.13f;
    private static final float DIM_B = 0.16f;

    private static final float BACKDROP_R = 0.12f;
    private static final float BACKDROP_G = 0.22f;
    private static final float BACKDROP_B = 0.28f;
    private static final float BACKDROP_A = 0.45f;

    private static final float SOLID_FILL_R = 0.12f;
    private static final float SOLID_FILL_G = 0.22f;
    private static final float SOLID_FILL_B = 0.28f;
    private static final float SOLID_FILL_A = 0.45f;

    private static final float INACTIVE_MAP_R = 0.34f;
    private static final float INACTIVE_MAP_G = 0.45f;
    private static final float INACTIVE_MAP_B = 0.48f;

    private static final float INACTIVE_DIM_R = 0.04f;
    private static final float INACTIVE_DIM_G = 0.07f;
    private static final float INACTIVE_DIM_B = 0.08f;

    private static final float INACTIVE_SOLID_FILL_R = 0.08f;
    private static final float INACTIVE_SOLID_FILL_G = 0.11f;
    private static final float INACTIVE_SOLID_FILL_B = 0.12f;
    private static final float INACTIVE_SOLID_FILL_A = 0.24f;

    // The invisible selection slab used to decide which rooms are active.
    // It follows the map yaw, keeps world-locked vertical pitch, is 6 blocks deep,
    // and extends 1000 blocks from the view center along its width and height.
    private static final double ACTIVE_SLAB_DEPTH_BLOCKS = 6.0;
    private static final double ACTIVE_SLAB_HALF_EXTENT_BLOCKS = 1000.0;

    private static final double ROOM_MASK_STEP = 1.0;

    // The C# map reveals a small patch around the player immediately, then floods outward.
    private static final double INITIAL_REVEAL_RADIUS = 8.0;
    private static final double REVEAL_BLOCKS_PER_TICK = 0.75;
    private static final double REVEAL_FRONT_SOFTNESS = 3.25;
    private static final float LINE_FADE_MIN_TICKS = 30.0f;
    private static final float LINE_FADE_PER_SIZE_FACTOR = 0.018f;

    private static final int PLAYER_RING_SEGMENTS = 48;
    private static final float PLAYER_RING_RADIUS = 0.85f;

    // Draw map lines as screen-space quads instead of RenderLayer.getLines().
    // The vanilla line shader can shimmer or fade out when the rotated map angle
    // changes because its line expansion depends on the supplied normal.
    private static final float MAP_LINE_THICKNESS = 0.5f;

    private final List<MapFace> roomFaces = new ArrayList<>();
    private final List<MapLine> roomLines = new ArrayList<>();
    private final List<RoomBounds> roomBounds = new ArrayList<>();
    private final Map<PlaneKey, List<MapFace>> facesByPlane = new HashMap<>();

    private double minWorldX;
    private double minWorldY;
    private double minWorldZ;
    private double maxWorldX;
    private double maxWorldY;
    private double maxWorldZ;

    private double focusX;
    private double focusY;
    private double focusZ;

    private float scale = 1.0f;
    private float zoom = 1.0f;
    private boolean hasRooms = false;

    private float yaw = 45.0f;
    private float pitch = 35.0f;

    private double panX = 0.0;
    private double panY = 0.0;
    private double panZ = 0.0;

    private double revealCenterX = 0.0;
    private double revealCenterY = 0.0;
    private double revealCenterZ = 0.0;
    private double revealRadius = INITIAL_REVEAL_RADIUS;
    private double lastRevealRadius = INITIAL_REVEAL_RADIUS;
    private double revealMax = INITIAL_REVEAL_RADIUS;
    private int revealTicks = 0;

    private float pulse = 0.0f;
    private float lastPulse = 0.0f;

    private boolean dragging = false;
    private double lastMouseX;
    private double lastMouseY;

    public RoomMapScreen() {
        super(Text.literal("Room Map"));
    }

    @Override
    protected void init() {
        super.init();
        zoom = 1.0f;
        yaw = 45.0f;
        pitch = 35.0f;
        panX = 0.0;
        panY = 0.0;
        panZ = 0.0;
        rebuildGeometry();
        resetReveal();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        lastPulse = pulse;
        pulse += 0.07f;

        lastRevealRadius = revealRadius;
        revealTicks++;
        revealRadius = Math.min(revealMax, INITIAL_REVEAL_RADIUS + revealTicks * REVEAL_BLOCKS_PER_TICK);

        // Match the C# feel: the more pending map there is, the softer the fade.
        float fadeTicks = Math.max(
            LINE_FADE_MIN_TICKS,
            roomLines.size() * LINE_FADE_PER_SIZE_FACTOR
        );
        float fadeStep = 1.0f / fadeTicks;

        for (MapFace face : roomFaces) {
            face.lastReveal = face.reveal;
            if (revealTicks >= face.revealStartTick && face.reveal < 1.0f) {
                face.reveal = Math.min(1.0f, face.reveal + fadeStep);
            }
        }


        for (MapLine line : roomLines) {
            line.lastReveal = line.reveal;
            if (revealTicks >= line.revealStartTick && line.reveal < 1.0f) {
                line.reveal = Math.min(1.0f, line.reveal + fadeStep);
            }
        }

        handleKeyboardPan(client);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Stencil-cutout backdrop outside the room volume.

        if (!hasRooms) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No rooms"), this.width / 2, this.height / 2 - 4, 0xFFFFFFFF);
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // Use our own screen-space quad lines. RenderLayer.getLines() is convenient,
        // but with a rotated GUI-space 3D transform it can become grainy or nearly
        // invisible at certain angles. The projection still uses yaw/pitch, but each
        // line is rasterized as a stable 2D quad after projection.
        RoomActivity activity = computeRoomActivity();

        drawBackdropCutout(matrix, delta, activity);

        // Inactive geometry is drawn first, but with an active-room depth clip.
        // This hides inactive fills/lines wherever an active room stencil exists,
        // without drawing a black color occluder into the framebuffer.
        beginActiveRoomDepthClip(matrix, delta, activity);

        BufferBuilder inactiveSolids = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawSolidBlockFill(inactiveSolids, matrix, delta, activity, false);
        BufferRenderer.drawWithGlobalProgram(inactiveSolids.end());

        BufferBuilder inactiveLines = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawRevealWave(inactiveLines, matrix, delta);
        drawRoomGeometry(inactiveLines, matrix, delta, activity, false);
        BufferRenderer.drawWithGlobalProgram(inactiveLines.end());

        endActiveRoomDepthClip();

        BufferBuilder activeSolids = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawSolidBlockFill(activeSolids, matrix, delta, activity, true);
        BufferRenderer.drawWithGlobalProgram(activeSolids.end());

        BufferBuilder activeLines = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawRoomGeometry(activeLines, matrix, delta, activity, true);
        drawPlayerMarker(activeLines, matrix, delta);
        BufferRenderer.drawWithGlobalProgram(activeLines.end());

        matrices.pop();

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        drawHudHints(context);
    }

    private void applyMapTransform(MatrixStack matrices) {
        matrices.translate(this.width * 0.5, this.height * 0.55, 0.0);
        matrices.scale(scale * zoom, scale * zoom, scale * zoom);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        matrices.translate(-focusX + panX, -focusY + panY, -focusZ + panZ);
    }

    private void handleKeyboardPan(MinecraftClient client) {
        long window = client.getWindow().getHandle();

        boolean forward = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_W);
        boolean back = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_S);
        boolean left = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_A);
        boolean right = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_D);
        boolean up = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_SPACE);
        boolean down = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT);

        double move = 0.40 / Math.max(0.001, scale * zoom);
        double yawRad = Math.toRadians(yaw);
        double forwardX = Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        if (forward) {
            panX -= forwardX * move;
            panZ -= forwardZ * move;
        }
        if (back) {
            panX += forwardX * move;
            panZ += forwardZ * move;
        }
        if (left) {
            panX -= rightX * move;
            panZ -= rightZ * move;
        }
        if (right) {
            panX += rightX * move;
            panZ += rightZ * move;
        }
        // Screen Y is flipped in projectPoint(), so positive world Y moves upward on screen.
        if (up) {
            panY += move;
        }
        if (down) {
            panY -= move;
        }
    }


    private void drawRoomGeometry(VertexConsumer buffer, Matrix4f matrix, float delta, RoomActivity activity, boolean activePass) {
        double visibleRadius = lerpDouble(lastRevealRadius, revealRadius, delta);
        float fadeTicks = Math.max(
            LINE_FADE_MIN_TICKS,
            roomLines.size() * LINE_FADE_PER_SIZE_FACTOR
        );

        for (MapLine line : roomLines) {
            if (isLineActive(line, activity) != activePass) {
                continue;
            }
            if (!lineHasFacingFace(line)) {
                continue;
            }
            drawPartiallyRevealedLine(buffer, matrix, line, visibleRadius, delta, fadeTicks, activePass);
        }
    }

    private void drawPartiallyRevealedLine(VertexConsumer buffer,
                                           Matrix4f matrix,
                                           MapLine line,
                                           double visibleRadius,
                                           float delta,
                                           float fadeTicks,
                                           boolean active) {
        double ax = line.x1World();
        double ay = line.y1World();
        double az = line.z1World();
        double bx = line.x2World();
        double by = line.y2World();
        double bz = line.z2World();

        double[] interval = revealedIntervalOnSegment(
            revealCenterX, revealCenterY, revealCenterZ,
            visibleRadius,
            ax, ay, az,
            bx, by, bz
        );
        if (interval == null) {
            return;
        }

        double t0 = interval[0];
        double t1 = interval[1];
        if (t1 - t0 <= 1.0e-6) {
            return;
        }

        double fullLength = Math.sqrt(
            (bx - ax) * (bx - ax) +
            (by - ay) * (by - ay) +
            (bz - az) * (bz - az)
        );
        int pieces = Math.max(1, Math.min(12, (int) Math.ceil(fullLength * (t1 - t0) / 2.0)));

        double prevT = t0;
        double prevX = lerpDouble(ax, bx, prevT);
        double prevY = lerpDouble(ay, by, prevT);
        double prevZ = lerpDouble(az, bz, prevT);
        PointReveal prevReveal = adjustedPointReveal(pointReveal(prevX, prevY, prevZ, visibleRadius, delta, fadeTicks), active);

        for (int i = 1; i <= pieces; i++) {
            double nextT = lerpDouble(t0, t1, (double) i / pieces);
            double nextX = lerpDouble(ax, bx, nextT);
            double nextY = lerpDouble(ay, by, nextT);
            double nextZ = lerpDouble(az, bz, nextT);
            PointReveal nextReveal = adjustedPointReveal(pointReveal(nextX, nextY, nextZ, visibleRadius, delta, fadeTicks), active);

            if (prevReveal.alpha > 0.001f || nextReveal.alpha > 0.001f) {
                drawRawLineGradient(
                    buffer,
                    matrix,
                    prevX, prevY, prevZ,
                    nextX, nextY, nextZ,
                    prevReveal.r, prevReveal.g, prevReveal.b, prevReveal.alpha,
                    nextReveal.r, nextReveal.g, nextReveal.b, nextReveal.alpha
                );

                // A subtle delayed shadow makes the reveal front read like the C# map's soft fade pixels.
                float shadowScale = active ? 0.055f : 0.030f;
                float shadowA0 = prevReveal.front * shadowScale;
                float shadowA1 = nextReveal.front * shadowScale;
                if (shadowA0 > 0.001f || shadowA1 > 0.001f) {
                    float shadowR = active ? DIM_R : INACTIVE_DIM_R;
                    float shadowG = active ? DIM_G : INACTIVE_DIM_G;
                    float shadowB = active ? DIM_B : INACTIVE_DIM_B;
                    drawRawLineGradient(
                        buffer,
                        matrix,
                        prevX, prevY, prevZ,
                        nextX, nextY, nextZ,
                        shadowR, shadowG, shadowB, shadowA0,
                        shadowR, shadowG, shadowB, shadowA1
                    );
                }
            }

            prevT = nextT;
            prevX = nextX;
            prevY = nextY;
            prevZ = nextZ;
            prevReveal = nextReveal;
        }
    }

    private PointReveal pointReveal(double x, double y, double z, double visibleRadius, float delta, float fadeTicks) {
        double dx = x - revealCenterX;
        double dy = y - revealCenterY;
        double dz = z - revealCenterZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double inside = visibleRadius - distance;
        if (inside < -1.0e-6) {
            return PointReveal.INVISIBLE;
        }

        // The visible sphere is the hard discovery frontier. This separate front factor
        // softens the edge so long lines do not pop to full opacity when the radius hits them.
        float front = smoothStep(MathHelper.clamp((float) (inside / REVEAL_FRONT_SOFTNESS), 0.0f, 1.0f));

        double startTick = Math.max(0.0, (distance - INITIAL_REVEAL_RADIUS) / REVEAL_BLOCKS_PER_TICK);
        float age = Math.max(0.0f, revealTicks + delta - (float) startTick);
        float fade = smoothStep(MathHelper.clamp(age / fadeTicks, 0.0f, 1.0f));
        float fresh = 1.0f - smoothStep(MathHelper.clamp(age / 45.0f, 0.0f, 1.0f));
        float visibility = front * fade;

        float r = lerp(MAP_R, WHITE_R, fresh);
        float g = lerp(MAP_G, WHITE_G, fresh);
        float b = lerp(MAP_B, WHITE_B, fresh);
        float a = visibility * (0.22f + 0.73f * fade + 0.18f * fresh);
        return new PointReveal(r, g, b, a, front);
    }

    private PointReveal adjustedPointReveal(PointReveal reveal, boolean active) {
        if (active || reveal.alpha <= 0.0f) {
            return reveal;
        }

        float gray = reveal.r * 0.2126f + reveal.g * 0.7152f + reveal.b * 0.0722f;
        float r = lerp(gray, INACTIVE_MAP_R, 0.62f);
        float g = lerp(gray, INACTIVE_MAP_G, 0.62f);
        float b = lerp(gray, INACTIVE_MAP_B, 0.62f);
        return new PointReveal(r, g, b, reveal.alpha * 0.48f, reveal.front * 0.65f);
    }

    private void drawPlayerMarker(VertexConsumer buffer, Matrix4f matrix, float delta) {
        float p = MathHelper.lerp(delta, lastPulse, pulse);
        float breathe = 0.5f + 0.5f * (float) Math.sin(p);
        float radius = PLAYER_RING_RADIUS + breathe * 0.20f;
        float alpha = 0.80f;

        drawRing(buffer, matrix, revealCenterX, revealCenterY, revealCenterZ, radius, Axis.Y, WHITE_R, WHITE_G, WHITE_B, alpha);
        drawRing(buffer, matrix, revealCenterX, revealCenterY, revealCenterZ, radius * 0.72f, Axis.X, MAP_R, MAP_G, MAP_B, alpha * 0.45f);
        drawRing(buffer, matrix, revealCenterX, revealCenterY, revealCenterZ, radius * 0.72f, Axis.Z, MAP_R, MAP_G, MAP_B, alpha * 0.45f);

        double s = 0.55 + breathe * 0.25;
        drawRawLine(buffer, matrix, revealCenterX - s, revealCenterY, revealCenterZ, revealCenterX + s, revealCenterY, revealCenterZ, WHITE_R, WHITE_G, WHITE_B, 0.90f);
        drawRawLine(buffer, matrix, revealCenterX, revealCenterY - s, revealCenterZ, revealCenterX, revealCenterY + s, revealCenterZ, WHITE_R, WHITE_G, WHITE_B, 0.90f);
        drawRawLine(buffer, matrix, revealCenterX, revealCenterY, revealCenterZ - s, revealCenterX, revealCenterY, revealCenterZ + s, WHITE_R, WHITE_G, WHITE_B, 0.90f);
    }

    private void drawRevealWave(VertexConsumer buffer, Matrix4f matrix, float delta) {
        if (revealRadius >= revealMax - 0.001) {
            return;
        }

        float p = MathHelper.lerp(delta, lastPulse, pulse);
        float alpha = 0.10f + 0.04f * (0.5f + 0.5f * (float) Math.sin(p * 1.8f));

        double visibleRadius = lerpDouble(lastRevealRadius, revealRadius, delta);
        drawRing(buffer, matrix, revealCenterX, revealCenterY, revealCenterZ, visibleRadius, Axis.Y, MAP_R, MAP_G, MAP_B, alpha);
        drawRing(buffer, matrix, revealCenterX, revealCenterY, revealCenterZ, visibleRadius, Axis.X, MAP_R, MAP_G, MAP_B, alpha * 0.55f);
        drawRing(buffer, matrix, revealCenterX, revealCenterY, revealCenterZ, visibleRadius, Axis.Z, MAP_R, MAP_G, MAP_B, alpha * 0.55f);
    }

    private void drawHudHints(DrawContext context) {
        String progress = roomLines.isEmpty()
            ? "0%"
            : (int) (100.0f * revealedLineFraction()) + "%";
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("WASD pan  •  Space/Shift height  •  Drag rotate  •  Wheel zoom  •  R rebuild/reveal  •  " + progress),
            10,
            this.height - 18,
            0x88FFFFFF
        );
    }

    private float revealedLineFraction() {
        if (roomLines.isEmpty()) {
            return 0.0f;
        }
        float sum = 0.0f;
        for (MapLine line : roomLines) {
            sum += line.reveal;
        }
        return MathHelper.clamp(sum / roomLines.size(), 0.0f, 1.0f);
    }

    private void rebuildGeometry() {
        roomFaces.clear();
        roomLines.clear();
        roomBounds.clear();
        facesByPlane.clear();
        hasRooms = false;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }

        List<RoomClientState.RoomEntry> rooms = RoomClientState.getRooms();
        if (rooms.isEmpty()) {
            return;
        }

        minWorldX = Double.POSITIVE_INFINITY;
        minWorldY = Double.POSITIVE_INFINITY;
        minWorldZ = Double.POSITIVE_INFINITY;
        maxWorldX = Double.NEGATIVE_INFINITY;
        maxWorldY = Double.NEGATIVE_INFINITY;
        maxWorldZ = Double.NEGATIVE_INFINITY;

        Map<FaceKey, Face> exteriorFaces = new HashMap<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (RoomClientState.RoomEntry room : rooms) {
            BlockPos min = room.min();
            BlockPos max = room.max();

            minWorldX = Math.min(minWorldX, min.getX());
            minWorldY = Math.min(minWorldY, min.getY());
            minWorldZ = Math.min(minWorldZ, min.getZ());
            maxWorldX = Math.max(maxWorldX, max.getX() + 1.0);
            maxWorldY = Math.max(maxWorldY, max.getY() + 1.0);
            maxWorldZ = Math.max(maxWorldZ, max.getZ() + 1.0);

            roomBounds.add(new RoomBounds(
                toUnit(min.getX()),
                toUnit(min.getY()),
                toUnit(min.getZ()),
                toUnit(max.getX() + 1.0),
                toUnit(max.getY() + 1.0),
                toUnit(max.getZ() + 1.0)
            ));

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        mutable.set(x, y, z);
                        BlockState state = world.getBlockState(mutable);
                        if (state.isAir()) {
                            continue;
                        }

                        VoxelShape shape = state.getOutlineShape(world, mutable);
                        if (shape.isEmpty()) {
                            shape = state.getCollisionShape(world, mutable);
                        }
                        if (shape.isEmpty()) {
                            continue;
                        }

                        final int blockX = x;
                        final int blockY = y;
                        final int blockZ = z;

                        shape.forEachBox((boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ) -> {
                            int x0 = toUnit(blockX + boxMinX);
                            int y0 = toUnit(blockY + boxMinY);
                            int z0 = toUnit(blockZ + boxMinZ);
                            int x1 = toUnit(blockX + boxMaxX);
                            int y1 = toUnit(blockY + boxMaxY);
                            int z1 = toUnit(blockZ + boxMaxZ);

                            if (x0 == x1 || y0 == y1 || z0 == z1) {
                                return;
                            }

                            addBoxFaces(exteriorFaces, x0, y0, z0, x1, y1, z1);
                        });
                    }
                }
            }
        }

        for (Face face : exteriorFaces.values()) {
            MapFace mapFace = new MapFace(face);
            roomFaces.add(mapFace);
            facesByPlane.computeIfAbsent(new PlaneKey(mapFace.axis, mapFace.plane), ignored -> new ArrayList<>()).add(mapFace);
        }

        // Build wire lines from merged coplanar surface outlines.
        // Then add only the missing room-shell axes. This keeps coplanar block seams
        // removed, but still closes the 3D cuboid outline when the block mesh only
        // supplied two opposite wall planes.
        List<EdgeKey> outlineEdges = buildMergedSurfaceEdges(exteriorFaces.values());
        addMissingRoomShellAxes(outlineEdges, roomBounds);

        for (EdgeKey edge : mergeCollinearEdges(outlineEdges)) {
            roomLines.add(new MapLine(edge));
        }
        hasRooms = !roomLines.isEmpty();

        if (client.player != null) {
            revealCenterX = client.player.getX();
            revealCenterY = client.player.getY() + 0.9;
            revealCenterZ = client.player.getZ();

            // Start focused on the player, like the C# map opens on the owner's room position.
            focusX = revealCenterX;
            focusY = revealCenterY;
            focusZ = revealCenterZ;
        } else {
            focusX = (minWorldX + maxWorldX) * 0.5;
            focusY = (minWorldY + maxWorldY) * 0.5;
            focusZ = (minWorldZ + maxWorldZ) * 0.5;
        }

        revealMax = computeRevealMax();
        assignRevealStartTicks();

        double sizeX = Math.max(1.0, maxWorldX - minWorldX);
        double sizeY = Math.max(1.0, maxWorldY - minWorldY);
        double sizeZ = Math.max(1.0, maxWorldZ - minWorldZ);
        double maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));

        // Keep the C# map's roomy scale but preserve a readable 3D block silhouette.
        float target = (float) Math.min(this.width, this.height) * 0.46f;
        scale = target / (float) maxDim;
        scale = MathHelper.clamp(scale, 2.5f, 34.0f);
    }

    private void resetReveal() {
        revealTicks = 0;
        revealRadius = INITIAL_REVEAL_RADIUS;
        lastRevealRadius = INITIAL_REVEAL_RADIUS;
        for (MapFace face : roomFaces) {
            face.reveal = 0.0f;
            face.lastReveal = 0.0f;
        }
        for (MapLine line : roomLines) {
            line.reveal = 0.0f;
            line.lastReveal = 0.0f;
        }
    }

    private void assignRevealStartTicks() {
        for (MapFace face : roomFaces) {
            double dx = face.centerX() - revealCenterX;
            double dy = face.centerY() - revealCenterY;
            double dz = face.centerZ() - revealCenterZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double delayedDistance = Math.max(0.0, distance - INITIAL_REVEAL_RADIUS);
            face.revealStartTick = (int) Math.floor(delayedDistance / REVEAL_BLOCKS_PER_TICK);
        }

        for (MapLine line : roomLines) {
            double distance = Math.sqrt(distanceSqToSegment(
                revealCenterX, revealCenterY, revealCenterZ,
                line.x1World(), line.y1World(), line.z1World(),
                line.x2World(), line.y2World(), line.z2World()
            ));

            double delayedDistance = Math.max(0.0, distance - INITIAL_REVEAL_RADIUS);
            line.revealStartTick = (int) Math.floor(delayedDistance / REVEAL_BLOCKS_PER_TICK);
        }
    }

    private double computeRevealMax() {
        double best = INITIAL_REVEAL_RADIUS;
        double[] xs = {minWorldX, maxWorldX};
        double[] ys = {minWorldY, maxWorldY};
        double[] zs = {minWorldZ, maxWorldZ};

        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    double dx = x - revealCenterX;
                    double dy = y - revealCenterY;
                    double dz = z - revealCenterZ;
                    best = Math.max(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }
        }

        return best + 4.0;
    }


    private static void addMissingRoomShellAxes(List<EdgeKey> edges, List<RoomBounds> rooms) {
        Map<EdgeKey, EdgeKey> unique = new HashMap<>();
        for (EdgeKey edge : edges) {
            unique.put(edge, edge);
        }

        for (RoomBounds room : rooms) {
            // If an extracted block mesh has no lines on one world axis, the map reads as
            // two floating 2D rectangles. Add just that room's four missing cuboid edges
            // on the absent axis, rather than reintroducing every block seam.
            for (int axis = 0; axis < 3; axis++) {
                if (!hasAxisEdgeOnRoomShell(unique.keySet(), room, axis)) {
                    addRoomShellAxisEdges(unique, room, axis);
                }
            }
        }

        edges.clear();
        edges.addAll(unique.keySet());
    }

    private static boolean hasAxisEdgeOnRoomShell(Iterable<EdgeKey> edges, RoomBounds room, int axis) {
        for (EdgeKey edge : edges) {
            if (edgeAxis(edge) != axis) {
                continue;
            }

            switch (axis) {
                case 0 -> {
                    int y = edge.y1;
                    int z = edge.z1;
                    if (isBoundary(y, room.y0, room.y1) && isBoundary(z, room.z0, room.z1)
                        && rangesOverlap(Math.min(edge.x1, edge.x2), Math.max(edge.x1, edge.x2), room.x0, room.x1)) {
                        return true;
                    }
                }
                case 1 -> {
                    int x = edge.x1;
                    int z = edge.z1;
                    if (isBoundary(x, room.x0, room.x1) && isBoundary(z, room.z0, room.z1)
                        && rangesOverlap(Math.min(edge.y1, edge.y2), Math.max(edge.y1, edge.y2), room.y0, room.y1)) {
                        return true;
                    }
                }
                case 2 -> {
                    int x = edge.x1;
                    int y = edge.y1;
                    if (isBoundary(x, room.x0, room.x1) && isBoundary(y, room.y0, room.y1)
                        && rangesOverlap(Math.min(edge.z1, edge.z2), Math.max(edge.z1, edge.z2), room.z0, room.z1)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void addRoomShellAxisEdges(Map<EdgeKey, EdgeKey> edges, RoomBounds room, int axis) {
        switch (axis) {
            case 0 -> {
                putEdge(edges, room.x0, room.y0, room.z0, room.x1, room.y0, room.z0);
                putEdge(edges, room.x0, room.y0, room.z1, room.x1, room.y0, room.z1);
                putEdge(edges, room.x0, room.y1, room.z0, room.x1, room.y1, room.z0);
                putEdge(edges, room.x0, room.y1, room.z1, room.x1, room.y1, room.z1);
            }
            case 1 -> {
                putEdge(edges, room.x0, room.y0, room.z0, room.x0, room.y1, room.z0);
                putEdge(edges, room.x0, room.y0, room.z1, room.x0, room.y1, room.z1);
                putEdge(edges, room.x1, room.y0, room.z0, room.x1, room.y1, room.z0);
                putEdge(edges, room.x1, room.y0, room.z1, room.x1, room.y1, room.z1);
            }
            case 2 -> {
                putEdge(edges, room.x0, room.y0, room.z0, room.x0, room.y0, room.z1);
                putEdge(edges, room.x0, room.y1, room.z0, room.x0, room.y1, room.z1);
                putEdge(edges, room.x1, room.y0, room.z0, room.x1, room.y0, room.z1);
                putEdge(edges, room.x1, room.y1, room.z0, room.x1, room.y1, room.z1);
            }
        }
    }

    private static int edgeAxis(EdgeKey edge) {
        if (edge.x1 != edge.x2) {
            return 0;
        }
        if (edge.y1 != edge.y2) {
            return 1;
        }
        if (edge.z1 != edge.z2) {
            return 2;
        }
        return -1;
    }

    private static boolean isBoundary(int value, int low, int high) {
        return value == low || value == high;
    }

    private static boolean rangesOverlap(int a0, int a1, int b0, int b1) {
        return Math.max(a0, b0) < Math.min(a1, b1);
    }

    private static void putEdge(Map<EdgeKey, EdgeKey> edges,
                                int x1, int y1, int z1,
                                int x2, int y2, int z2) {
        EdgeKey key = edgeKey(x1, y1, z1, x2, y2, z2);
        edges.put(key, key);
    }

    private static void addBoxFaces(Map<FaceKey, Face> faces,
                                    int x0, int y0, int z0,
                                    int x1, int y1, int z1) {
        toggleFace(faces, Axis.X, x0, y0, z0, y1, z1, -1);
        toggleFace(faces, Axis.X, x1, y0, z0, y1, z1, 1);

        toggleFace(faces, Axis.Y, y0, x0, z0, x1, z1, -1);
        toggleFace(faces, Axis.Y, y1, x0, z0, x1, z1, 1);

        toggleFace(faces, Axis.Z, z0, x0, y0, x1, y1, -1);
        toggleFace(faces, Axis.Z, z1, x0, y0, x1, y1, 1);
    }

    private static List<EdgeKey> buildMergedSurfaceEdges(Iterable<Face> faces) {
        Map<EdgeKey, EdgeUse> edgeUses = new HashMap<>();

        for (Face face : faces) {
            addFaceEdgeUses(edgeUses, face);
        }

        List<EdgeKey> outlineEdges = new ArrayList<>();
        for (Map.Entry<EdgeKey, EdgeUse> entry : edgeUses.entrySet()) {
            if (entry.getValue().isVisibleOutline()) {
                outlineEdges.add(entry.getKey());
            }
        }

        return mergeCollinearEdges(outlineEdges);
    }

    private static void addFaceEdgeUses(Map<EdgeKey, EdgeUse> edgeUses, Face face) {
        PlaneKey plane = new PlaneKey(face.axis, face.plane);
        switch (face.axis) {
            case X -> {
                int x = face.plane;
                addEdgeUse(edgeUses, plane, x, face.a0, face.b0, x, face.a1, face.b0);
                addEdgeUse(edgeUses, plane, x, face.a1, face.b0, x, face.a1, face.b1);
                addEdgeUse(edgeUses, plane, x, face.a1, face.b1, x, face.a0, face.b1);
                addEdgeUse(edgeUses, plane, x, face.a0, face.b1, x, face.a0, face.b0);
            }
            case Y -> {
                int y = face.plane;
                addEdgeUse(edgeUses, plane, face.a0, y, face.b0, face.a1, y, face.b0);
                addEdgeUse(edgeUses, plane, face.a1, y, face.b0, face.a1, y, face.b1);
                addEdgeUse(edgeUses, plane, face.a1, y, face.b1, face.a0, y, face.b1);
                addEdgeUse(edgeUses, plane, face.a0, y, face.b1, face.a0, y, face.b0);
            }
            case Z -> {
                int z = face.plane;
                addEdgeUse(edgeUses, plane, face.a0, face.b0, z, face.a1, face.b0, z);
                addEdgeUse(edgeUses, plane, face.a1, face.b0, z, face.a1, face.b1, z);
                addEdgeUse(edgeUses, plane, face.a1, face.b1, z, face.a0, face.b1, z);
                addEdgeUse(edgeUses, plane, face.a0, face.b1, z, face.a0, face.b0, z);
            }
        }
    }

    private static void addEdgeUse(Map<EdgeKey, EdgeUse> edgeUses,
                                   PlaneKey plane,
                                   int x1, int y1, int z1,
                                   int x2, int y2, int z2) {
        EdgeKey key = edgeKey(x1, y1, z1, x2, y2, z2);
        edgeUses.computeIfAbsent(key, ignored -> new EdgeUse()).add(plane);
    }

    private static void toggleFaceEdges(Map<EdgeKey, EdgeKey> edges, Face face) {
        switch (face.axis) {
            case X -> {
                int x = face.plane;
                toggleEdge(edges, x, face.a0, face.b0, x, face.a1, face.b0);
                toggleEdge(edges, x, face.a1, face.b0, x, face.a1, face.b1);
                toggleEdge(edges, x, face.a1, face.b1, x, face.a0, face.b1);
                toggleEdge(edges, x, face.a0, face.b1, x, face.a0, face.b0);
            }
            case Y -> {
                int y = face.plane;
                toggleEdge(edges, face.a0, y, face.b0, face.a1, y, face.b0);
                toggleEdge(edges, face.a1, y, face.b0, face.a1, y, face.b1);
                toggleEdge(edges, face.a1, y, face.b1, face.a0, y, face.b1);
                toggleEdge(edges, face.a0, y, face.b1, face.a0, y, face.b0);
            }
            case Z -> {
                int z = face.plane;
                toggleEdge(edges, face.a0, face.b0, z, face.a1, face.b0, z);
                toggleEdge(edges, face.a1, face.b0, z, face.a1, face.b1, z);
                toggleEdge(edges, face.a1, face.b1, z, face.a0, face.b1, z);
                toggleEdge(edges, face.a0, face.b1, z, face.a0, face.b0, z);
            }
        }
    }

    private static void toggleEdge(Map<EdgeKey, EdgeKey> edges,
                                   int x1, int y1, int z1,
                                   int x2, int y2, int z2) {
        EdgeKey key = edgeKey(x1, y1, z1, x2, y2, z2);
        if (edges.containsKey(key)) {
            edges.remove(key);
        } else {
            edges.put(key, key);
        }
    }

    private static List<EdgeKey> mergeCollinearEdges(List<EdgeKey> edges) {
        Map<LineKey, List<IntRange>> rangesByLine = new HashMap<>();

        for (EdgeKey edge : edges) {
            if (edge.x1 == edge.x2 && edge.y1 == edge.y2 && edge.z1 == edge.z2) {
                continue;
            }

            LineKey line;
            int start;
            int end;

            if (edge.x1 != edge.x2) {
                line = new LineKey(0, edge.y1, edge.z1);
                start = Math.min(edge.x1, edge.x2);
                end = Math.max(edge.x1, edge.x2);
            } else if (edge.y1 != edge.y2) {
                line = new LineKey(1, edge.x1, edge.z1);
                start = Math.min(edge.y1, edge.y2);
                end = Math.max(edge.y1, edge.y2);
            } else {
                line = new LineKey(2, edge.x1, edge.y1);
                start = Math.min(edge.z1, edge.z2);
                end = Math.max(edge.z1, edge.z2);
            }

            rangesByLine.computeIfAbsent(line, ignored -> new ArrayList<>()).add(new IntRange(start, end));
        }

        List<EdgeKey> merged = new ArrayList<>();
        for (Map.Entry<LineKey, List<IntRange>> entry : rangesByLine.entrySet()) {
            LineKey line = entry.getKey();
            List<IntRange> ranges = entry.getValue();
            ranges.sort((a, b) -> {
                if (a.start != b.start) {
                    return Integer.compare(a.start, b.start);
                }
                return Integer.compare(a.end, b.end);
            });

            int currentStart = Integer.MIN_VALUE;
            int currentEnd = Integer.MIN_VALUE;

            for (IntRange range : ranges) {
                if (currentStart == Integer.MIN_VALUE) {
                    currentStart = range.start;
                    currentEnd = range.end;
                    continue;
                }

                if (range.start <= currentEnd) {
                    currentEnd = Math.max(currentEnd, range.end);
                } else {
                    addMergedLine(merged, line, currentStart, currentEnd);
                    currentStart = range.start;
                    currentEnd = range.end;
                }
            }

            if (currentStart != Integer.MIN_VALUE) {
                addMergedLine(merged, line, currentStart, currentEnd);
            }
        }

        return merged;
    }

    private static void addMergedLine(List<EdgeKey> merged, LineKey line, int start, int end) {
        if (start == end) {
            return;
        }

        switch (line.axis) {
            case 0 -> merged.add(edgeKey(start, line.fixedA, line.fixedB, end, line.fixedA, line.fixedB));
            case 1 -> merged.add(edgeKey(line.fixedA, start, line.fixedB, line.fixedA, end, line.fixedB));
            case 2 -> merged.add(edgeKey(line.fixedA, line.fixedB, start, line.fixedA, line.fixedB, end));
        }
    }

    private static void toggleFace(Map<FaceKey, Face> faces,
                                   Axis axis,
                                   int plane,
                                   int a0,
                                   int b0,
                                   int a1,
                                   int b1,
                                   int normalSign) {
        FaceKey key = new FaceKey(axis, plane, a0, b0, a1, b1);
        if (faces.containsKey(key)) {
            faces.remove(key);
        } else {
            faces.put(key, new Face(axis, plane, a0, b0, a1, b1, normalSign));
        }
    }

    private boolean lineHasFacingFace(MapLine line) {
        int axis = lineAxis(line);
        if (axis < 0) {
            return false;
        }

        int minX = Math.min(line.x1, line.x2);
        int maxX = Math.max(line.x1, line.x2);
        int minY = Math.min(line.y1, line.y2);
        int maxY = Math.max(line.y1, line.y2);
        int minZ = Math.min(line.z1, line.z2);
        int maxZ = Math.max(line.z1, line.z2);

        switch (axis) {
            case 0 -> {
                if (lineTouchesFacingYFace(line.y1, line.z1, minX, maxX)) {
                    return true;
                }
                return lineTouchesFacingZFace(line.z1, line.y1, minX, maxX);
            }
            case 1 -> {
                if (lineTouchesFacingXFace(line.x1, line.z1, minY, maxY)) {
                    return true;
                }
                return lineTouchesFacingZFaceAtX(line.z1, line.x1, minY, maxY);
            }
            case 2 -> {
                if (lineTouchesFacingXFaceAtY(line.x1, line.y1, minZ, maxZ)) {
                    return true;
                }
                return lineTouchesFacingYFaceAtX(line.y1, line.x1, minZ, maxZ);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean lineTouchesFacingYFace(int planeY, int fixedZ, int lineStart, int lineEnd) {
        List<MapFace> faces = facesByPlane.get(new PlaneKey(Axis.Y, planeY));
        if (faces == null) {
            return false;
        }

        for (MapFace face : faces) {
            if (!isFaceFacingCamera(face)) {
                continue;
            }
            if ((fixedZ == face.b0 || fixedZ == face.b1)
                && rangesOverlap(lineStart, lineEnd, Math.min(face.a0, face.a1), Math.max(face.a0, face.a1))) {
                return true;
            }
        }
        return false;
    }

    private boolean lineTouchesFacingZFace(int planeZ, int fixedY, int lineStart, int lineEnd) {
        List<MapFace> faces = facesByPlane.get(new PlaneKey(Axis.Z, planeZ));
        if (faces == null) {
            return false;
        }

        for (MapFace face : faces) {
            if (!isFaceFacingCamera(face)) {
                continue;
            }
            if ((fixedY == face.b0 || fixedY == face.b1)
                && rangesOverlap(lineStart, lineEnd, Math.min(face.a0, face.a1), Math.max(face.a0, face.a1))) {
                return true;
            }
        }
        return false;
    }

    private boolean lineTouchesFacingXFace(int planeX, int fixedZ, int lineStart, int lineEnd) {
        List<MapFace> faces = facesByPlane.get(new PlaneKey(Axis.X, planeX));
        if (faces == null) {
            return false;
        }

        for (MapFace face : faces) {
            if (!isFaceFacingCamera(face)) {
                continue;
            }
            if ((fixedZ == face.b0 || fixedZ == face.b1)
                && rangesOverlap(lineStart, lineEnd, Math.min(face.a0, face.a1), Math.max(face.a0, face.a1))) {
                return true;
            }
        }
        return false;
    }

    private boolean lineTouchesFacingZFaceAtX(int planeZ, int fixedX, int lineStart, int lineEnd) {
        List<MapFace> faces = facesByPlane.get(new PlaneKey(Axis.Z, planeZ));
        if (faces == null) {
            return false;
        }

        for (MapFace face : faces) {
            if (!isFaceFacingCamera(face)) {
                continue;
            }
            if ((fixedX == face.a0 || fixedX == face.a1)
                && rangesOverlap(lineStart, lineEnd, Math.min(face.b0, face.b1), Math.max(face.b0, face.b1))) {
                return true;
            }
        }
        return false;
    }

    private boolean lineTouchesFacingXFaceAtY(int planeX, int fixedY, int lineStart, int lineEnd) {
        List<MapFace> faces = facesByPlane.get(new PlaneKey(Axis.X, planeX));
        if (faces == null) {
            return false;
        }

        for (MapFace face : faces) {
            if (!isFaceFacingCamera(face)) {
                continue;
            }
            if ((fixedY == face.a0 || fixedY == face.a1)
                && rangesOverlap(lineStart, lineEnd, Math.min(face.b0, face.b1), Math.max(face.b0, face.b1))) {
                return true;
            }
        }
        return false;
    }

    private boolean lineTouchesFacingYFaceAtX(int planeY, int fixedX, int lineStart, int lineEnd) {
        List<MapFace> faces = facesByPlane.get(new PlaneKey(Axis.Y, planeY));
        if (faces == null) {
            return false;
        }

        for (MapFace face : faces) {
            if (!isFaceFacingCamera(face)) {
                continue;
            }
            if ((fixedX == face.a0 || fixedX == face.a1)
                && rangesOverlap(lineStart, lineEnd, Math.min(face.b0, face.b1), Math.max(face.b0, face.b1))) {
                return true;
            }
        }
        return false;
    }

    private boolean isFaceFacingCamera(MapFace face) {
        double nx = 0.0;
        double ny = 0.0;
        double nz = 0.0;

        switch (face.axis) {
            case X -> nx = face.normalSign;
            case Y -> ny = face.normalSign;
            case Z -> nz = face.normalSign;
        }

        double yawRad = Math.toRadians(yaw);
        double yawCos = Math.cos(yawRad);
        double yawSin = Math.sin(yawRad);

        double yawX = nx * yawCos + nz * yawSin;
        double yawZ = -nx * yawSin + nz * yawCos;

        double pitchRad = Math.toRadians(pitch);
        double pitchCos = Math.cos(pitchRad);
        double pitchSin = Math.sin(pitchRad);

        double viewZ = ny * pitchSin + yawZ * pitchCos;
        return viewZ > 0.001;
    }

    private static int lineAxis(MapLine line) {
        if (line.x1 != line.x2) {
            return 0;
        }
        if (line.y1 != line.y2) {
            return 1;
        }
        if (line.z1 != line.z2) {
            return 2;
        }
        return -1;
    }

    private static void addFaceEdges(Map<EdgeKey, MapLine> edges, Face face) {
        switch (face.axis) {
            case X -> {
                int x = face.plane;
                addEdge(edges, x, face.a0, face.b0, x, face.a1, face.b0);
                addEdge(edges, x, face.a1, face.b0, x, face.a1, face.b1);
                addEdge(edges, x, face.a1, face.b1, x, face.a0, face.b1);
                addEdge(edges, x, face.a0, face.b1, x, face.a0, face.b0);
            }
            case Y -> {
                int y = face.plane;
                addEdge(edges, face.a0, y, face.b0, face.a1, y, face.b0);
                addEdge(edges, face.a1, y, face.b0, face.a1, y, face.b1);
                addEdge(edges, face.a1, y, face.b1, face.a0, y, face.b1);
                addEdge(edges, face.a0, y, face.b1, face.a0, y, face.b0);
            }
            case Z -> {
                int z = face.plane;
                addEdge(edges, face.a0, face.b0, z, face.a1, face.b0, z);
                addEdge(edges, face.a1, face.b0, z, face.a1, face.b1, z);
                addEdge(edges, face.a1, face.b1, z, face.a0, face.b1, z);
                addEdge(edges, face.a0, face.b1, z, face.a0, face.b0, z);
            }
        }
    }

    private static void addEdge(Map<EdgeKey, MapLine> edges,
                                int x1, int y1, int z1,
                                int x2, int y2, int z2) {
        EdgeKey key = edgeKey(x1, y1, z1, x2, y2, z2);
        edges.putIfAbsent(key, new MapLine(key));
    }

    private static EdgeKey edgeKey(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (compare(x1, y1, z1, x2, y2, z2) > 0) {
            int tx = x1;
            int ty = y1;
            int tz = z1;
            x1 = x2;
            y1 = y2;
            z1 = z2;
            x2 = tx;
            y2 = ty;
            z2 = tz;
        }
        return new EdgeKey(x1, y1, z1, x2, y2, z2);
    }

    private static int compare(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (x1 != x2) return Integer.compare(x1, x2);
        if (y1 != y2) return Integer.compare(y1, y2);
        return Integer.compare(z1, z2);
    }

    private static int toUnit(double value) {
        return (int) Math.round(value * SHAPE_UNIT);
    }


    private void drawRawLine(VertexConsumer buffer, Matrix4f matrix,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             float r, float g, float b, float a) {
        drawRawLineGradient(
            buffer,
            matrix,
            x1, y1, z1,
            x2, y2, z2,
            r, g, b, a,
            r, g, b, a
        );
    }

    private void drawRawLineGradient(VertexConsumer buffer, Matrix4f matrix,
                                     double x1, double y1, double z1,
                                     double x2, double y2, double z2,
                                     float r1, float g1, float b1, float a1,
                                     float r2, float g2, float b2, float a2) {
        ProjectedPoint p1 = projectPoint(x1, y1, z1);
        ProjectedPoint p2 = projectPoint(x2, y2, z2);
        drawScreenLineGradient(
            buffer,
            matrix,
            p1.x, p1.y,
            p2.x, p2.y,
            MAP_LINE_THICKNESS,
            r1, g1, b1, MathHelper.clamp(a1, 0.0f, 1.0f),
            r2, g2, b2, MathHelper.clamp(a2, 0.0f, 1.0f)
        );
    }

    private ProjectedPoint projectPoint(double worldX, double worldY, double worldZ) {
        double x = worldX - focusX + panX;
        double y = worldY - focusY + panY;
        double z = worldZ - focusZ + panZ;

        double yawRad = Math.toRadians(yaw);
        double yawCos = Math.cos(yawRad);
        double yawSin = Math.sin(yawRad);

        // Match the 3D map orbit, but keep it in our own projection so line thickness
        // stays stable.
        double yawX = x * yawCos + z * yawSin;
        double yawZ = -x * yawSin + z * yawCos;

        double pitchRad = Math.toRadians(pitch);
        double pitchCos = Math.cos(pitchRad);
        double pitchSin = Math.sin(pitchRad);
        double pitchY = y * pitchCos - yawZ * pitchSin;

        double screenScale = scale * zoom;
        float sx = (float) (this.width * 0.5 + yawX * screenScale);

        // GUI Y grows downward; subtract so world +Y renders upward instead of the
        // whole map appearing upside down.
        float sy = (float) (this.height * 0.55 - pitchY * screenScale);
        return new ProjectedPoint(sx, sy);
    }

    private RoomActivity computeRoomActivity() {
        boolean[] activeRooms = new boolean[roomBounds.size()];
        for (int i = 0; i < roomBounds.size(); i++) {
            activeRooms[i] = roomIntersectsActiveSlab(roomBounds.get(i));
        }
        return new RoomActivity(activeRooms);
    }

    private boolean roomIntersectsActiveSlab(RoomBounds room) {
        // projectPoint() centers a world coordinate when:
        // world - focus + pan == 0, so the current map view center is focus - pan.
        double centerX = focusX - panX;
        double centerY = focusY - panY;
        double centerZ = focusZ - panZ;

        double yawRad = Math.toRadians(yaw);
        double yawCos = Math.cos(yawRad);
        double yawSin = Math.sin(yawRad);

        // Same yaw basis as the map projection, but with world-locked vertical pitch.
        double rightX = yawCos;
        double rightY = 0.0;
        double rightZ = yawSin;
        double depthX = -yawSin;
        double depthY = 0.0;
        double depthZ = yawCos;
        double upX = 0.0;
        double upY = 1.0;
        double upZ = 0.0;

        double roomCenterX = (room.x0 + room.x1) * INV_SHAPE_UNIT * 0.5;
        double roomCenterY = (room.y0 + room.y1) * INV_SHAPE_UNIT * 0.5;
        double roomCenterZ = (room.z0 + room.z1) * INV_SHAPE_UNIT * 0.5;
        double extentX = Math.max(0.0, (room.x1 - room.x0) * INV_SHAPE_UNIT * 0.5);
        double extentY = Math.max(0.0, (room.y1 - room.y0) * INV_SHAPE_UNIT * 0.5);
        double extentZ = Math.max(0.0, (room.z1 - room.z0) * INV_SHAPE_UNIT * 0.5);

        double dx = roomCenterX - centerX;
        double dy = roomCenterY - centerY;
        double dz = roomCenterZ - centerZ;

        double roomDepthCenter = dx * depthX + dy * depthY + dz * depthZ;
        double roomRightCenter = dx * rightX + dy * rightY + dz * rightZ;
        double roomUpCenter = dx * upX + dy * upY + dz * upZ;

        double roomDepthExtent = Math.abs(depthX) * extentX + Math.abs(depthY) * extentY + Math.abs(depthZ) * extentZ;
        double roomRightExtent = Math.abs(rightX) * extentX + Math.abs(rightY) * extentY + Math.abs(rightZ) * extentZ;
        double roomUpExtent = Math.abs(upX) * extentX + Math.abs(upY) * extentY + Math.abs(upZ) * extentZ;

        double halfDepth = ACTIVE_SLAB_DEPTH_BLOCKS * 0.5;
        double halfExtent = ACTIVE_SLAB_HALF_EXTENT_BLOCKS;

        return Math.abs(roomDepthCenter) <= halfDepth + roomDepthExtent
            && Math.abs(roomRightCenter) <= halfExtent + roomRightExtent
            && Math.abs(roomUpCenter) <= halfExtent + roomUpExtent;
    }

    private boolean isFaceActive(MapFace face, RoomActivity activity) {
        for (int i = 0; i < roomBounds.size(); i++) {
            if (activity.activeRooms[i] && faceIntersectsRoom(face, roomBounds.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isLineActive(MapLine line, RoomActivity activity) {
        for (int i = 0; i < roomBounds.size(); i++) {
            if (activity.activeRooms[i] && lineIntersectsRoom(line, roomBounds.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean faceIntersectsRoom(MapFace face, RoomBounds room) {
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;

        switch (face.axis) {
            case X -> {
                minX = face.planeWorld();
                maxX = minX;
                minY = Math.min(face.a0World(), face.a1World());
                maxY = Math.max(face.a0World(), face.a1World());
                minZ = Math.min(face.b0World(), face.b1World());
                maxZ = Math.max(face.b0World(), face.b1World());
            }
            case Y -> {
                minX = Math.min(face.a0World(), face.a1World());
                maxX = Math.max(face.a0World(), face.a1World());
                minY = face.planeWorld();
                maxY = minY;
                minZ = Math.min(face.b0World(), face.b1World());
                maxZ = Math.max(face.b0World(), face.b1World());
            }
            case Z -> {
                minX = Math.min(face.a0World(), face.a1World());
                maxX = Math.max(face.a0World(), face.a1World());
                minY = Math.min(face.b0World(), face.b1World());
                maxY = Math.max(face.b0World(), face.b1World());
                minZ = face.planeWorld();
                maxZ = minZ;
            }
            default -> {
                return false;
            }
        }

        return boxesOverlapInclusive(
            minX, minY, minZ,
            maxX, maxY, maxZ,
            room.x0World(), room.y0World(), room.z0World(),
            room.x1World(), room.y1World(), room.z1World()
        );
    }

    private boolean lineIntersectsRoom(MapLine line, RoomBounds room) {
        double minX = Math.min(line.x1World(), line.x2World());
        double minY = Math.min(line.y1World(), line.y2World());
        double minZ = Math.min(line.z1World(), line.z2World());
        double maxX = Math.max(line.x1World(), line.x2World());
        double maxY = Math.max(line.y1World(), line.y2World());
        double maxZ = Math.max(line.z1World(), line.z2World());

        return boxesOverlapInclusive(
            minX, minY, minZ,
            maxX, maxY, maxZ,
            room.x0World(), room.y0World(), room.z0World(),
            room.x1World(), room.y1World(), room.z1World()
        );
    }

    private static boolean boxesOverlapInclusive(double ax0, double ay0, double az0,
                                                 double ax1, double ay1, double az1,
                                                 double bx0, double by0, double bz0,
                                                 double bx1, double by1, double bz1) {
        double epsilon = 1.0e-5;
        return ax0 <= bx1 + epsilon && ax1 + epsilon >= bx0
            && ay0 <= by1 + epsilon && ay1 + epsilon >= by0
            && az0 <= bz1 + epsilon && az1 + epsilon >= bz0;
    }

    private void drawBackdropCutout(Matrix4f matrix, float delta, RoomActivity activity) {
        if (roomBounds.isEmpty()) {
            drawBackdropOnly(matrix);
            return;
        }

        double visibleRadius = lerpDouble(lastRevealRadius, revealRadius, delta);
        float fadeTicks = Math.max(
            LINE_FADE_MIN_TICKS,
            roomLines.size() * LINE_FADE_PER_SIZE_FACTOR
        );

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
        GL11.glDepthFunc(GL11.GL_LESS);
        RenderSystem.colorMask(false, false, false, false);

        BufferBuilder mask = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawRoomStencilMask(mask, matrix, visibleRadius, delta, fadeTicks, activity, false);
        drawRoomStencilMask(mask, matrix, visibleRadius, delta, fadeTicks, activity, true);
        BufferRenderer.drawWithGlobalProgram(mask.end());

        GL11.glDepthFunc(GL11.GL_ALWAYS);
        BufferBuilder solids = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawSolidBlockDepthReset(solids, matrix, delta, activity, false);
        drawSolidBlockDepthReset(solids, matrix, delta, activity, true);
        BufferRenderer.drawWithGlobalProgram(solids.end());
        GL11.glDepthFunc(GL11.GL_LESS);

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);

        BufferBuilder backdrop = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawScreenQuad(backdrop, matrix, 0.0f, 0.0f, this.width, this.height,
            BACKDROP_R, BACKDROP_G, BACKDROP_B, BACKDROP_A);
        BufferRenderer.drawWithGlobalProgram(backdrop.end());

        RenderSystem.depthMask(true);
        RenderSystem.disableDepthTest();
    }

    private void drawBackdropOnly(Matrix4f matrix) {
        BufferBuilder backdrop = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawScreenQuad(backdrop, matrix, 0.0f, 0.0f, this.width, this.height,
            BACKDROP_R, BACKDROP_G, BACKDROP_B, BACKDROP_A);
        BufferRenderer.drawWithGlobalProgram(backdrop.end());
    }

    private void beginActiveRoomDepthClip(Matrix4f matrix, float delta, RoomActivity activity) {
        double visibleRadius = lerpDouble(lastRevealRadius, revealRadius, delta);
        float fadeTicks = Math.max(
            LINE_FADE_MIN_TICKS,
            roomLines.size() * LINE_FADE_PER_SIZE_FACTOR
        );

        // Use the active room mask only as a depth clip. This prevents inactive
        // geometry from drawing through active room cutouts, but it does not paint
        // the active stencil black into the color buffer.
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
        GL11.glDepthFunc(GL11.GL_LESS);
        RenderSystem.colorMask(false, false, false, false);

        BufferBuilder mask = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawRoomStencilMask(mask, matrix, visibleRadius, delta, fadeTicks, activity, true);
        BufferRenderer.drawWithGlobalProgram(mask.end());

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);
        GL11.glDepthFunc(GL11.GL_LESS);
    }

    private void endActiveRoomDepthClip() {
        RenderSystem.depthMask(true);
        RenderSystem.disableDepthTest();
    }

    private void drawRoomStencilMask(VertexConsumer buffer, Matrix4f matrix,
                                     double visibleRadius, float delta, float fadeTicks,
                                     RoomActivity activity, boolean activePass) {
        buffer.vertex(matrix, -10000.0f, -10000.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10000.0f, -10001.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10001.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10000.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);

        for (int i = 0; i < roomBounds.size(); i++) {
            if (activity.activeRooms[i] != activePass) {
                continue;
            }

            RoomBounds room = roomBounds.get(i);
            double x0 = room.x0 * INV_SHAPE_UNIT;
            double y0 = room.y0 * INV_SHAPE_UNIT;
            double z0 = room.z0 * INV_SHAPE_UNIT;
            double x1 = room.x1 * INV_SHAPE_UNIT;
            double y1 = room.y1 * INV_SHAPE_UNIT;
            double z1 = room.z1 * INV_SHAPE_UNIT;

            // -X
            drawRoomFaceMask(buffer, matrix, Axis.X, x0, y0, z0, y1, z1, visibleRadius, delta, fadeTicks);
            // +X
            drawRoomFaceMask(buffer, matrix, Axis.X, x1, y0, z0, y1, z1, visibleRadius, delta, fadeTicks);
            // -Y
            drawRoomFaceMask(buffer, matrix, Axis.Y, y0, x0, z0, x1, z1, visibleRadius, delta, fadeTicks);
            // +Y
            drawRoomFaceMask(buffer, matrix, Axis.Y, y1, x0, z0, x1, z1, visibleRadius, delta, fadeTicks);
            // -Z
            drawRoomFaceMask(buffer, matrix, Axis.Z, z0, x0, y0, x1, y1, visibleRadius, delta, fadeTicks);
            // +Z
            drawRoomFaceMask(buffer, matrix, Axis.Z, z1, x0, y0, x1, y1, visibleRadius, delta, fadeTicks);
        }
    }

    private void drawRoomFaceMask(VertexConsumer buffer, Matrix4f matrix,
                                  Axis axis,
                                  double fixed,
                                  double a0, double b0,
                                  double a1, double b1,
                                  double visibleRadius,
                                  float delta,
                                  float fadeTicks) {
        double step = ROOM_MASK_STEP;

        for (double a = a0; a < a1 - 1.0e-6; a += step) {
            double an = Math.min(a + step, a1);
            for (double b = b0; b < b1 - 1.0e-6; b += step) {
                double bn = Math.min(b + step, b1);

                double cx;
                double cy;
                double cz;

                switch (axis) {
                    case X -> {
                        cx = fixed;
                        cy = (a + an) * 0.5;
                        cz = (b + bn) * 0.5;
                    }
                    case Y -> {
                        cx = (a + an) * 0.5;
                        cy = fixed;
                        cz = (b + bn) * 0.5;
                    }
                    case Z -> {
                        cx = (a + an) * 0.5;
                        cy = (b + bn) * 0.5;
                        cz = fixed;
                    }
                    default -> {
                        cx = 0.0;
                        cy = 0.0;
                        cz = 0.0;
                    }
                }

                PointReveal reveal = pointReveal(cx, cy, cz, visibleRadius, delta, fadeTicks);
                if (reveal.alpha <= 0.02f) {
                    continue;
                }

                switch (axis) {
                    case X -> drawRoomFace(buffer, matrix,
                        fixed, a, b,
                        fixed, an, b,
                        fixed, an, bn,
                        fixed, a, bn,
                        0.0f, 0.0f, 0.0f, 1.0f);
                    case Y -> drawRoomFace(buffer, matrix,
                        a, fixed, b,
                        an, fixed, b,
                        an, fixed, bn,
                        a, fixed, bn,
                        0.0f, 0.0f, 0.0f, 1.0f);
                    case Z -> drawRoomFace(buffer, matrix,
                        a, b, fixed,
                        an, b, fixed,
                        an, bn, fixed,
                        a, bn, fixed,
                        0.0f, 0.0f, 0.0f, 1.0f);
                }
            }
        }
    }

    private void drawSolidBlockDepthReset(VertexConsumer buffer, Matrix4f matrix, float delta, RoomActivity activity, boolean activePass) {
        buffer.vertex(matrix, -10000.0f, -10000.0f, 1.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10000.0f, -10001.0f, 1.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10001.0f, 1.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10000.0f, 1.0f).color(0.0f, 0.0f, 0.0f, 0.0f);

        for (MapFace face : roomFaces) {
            if (isFaceActive(face, activity) != activePass) {
                continue;
            }

            float reveal = MathHelper.lerp(delta, face.lastReveal, face.reveal);
            if (reveal <= 0.01f) {
                continue;
            }

            switch (face.axis) {
                case X -> {
                    double x = face.planeWorld();
                    double y0 = face.a0World();
                    double y1 = face.a1World();
                    double z0 = face.b0World();
                    double z1 = face.b1World();
                    drawRoomFaceDepth(buffer, matrix,
                        x, y0, z0,
                        x, y1, z0,
                        x, y1, z1,
                        x, y0, z1,
                        1.0f);
                }
                case Y -> {
                    double y = face.planeWorld();
                    double x0 = face.a0World();
                    double x1 = face.a1World();
                    double z0 = face.b0World();
                    double z1 = face.b1World();
                    drawRoomFaceDepth(buffer, matrix,
                        x0, y, z0,
                        x1, y, z0,
                        x1, y, z1,
                        x0, y, z1,
                        1.0f);
                }
                case Z -> {
                    double z = face.planeWorld();
                    double x0 = face.a0World();
                    double x1 = face.a1World();
                    double y0 = face.b0World();
                    double y1 = face.b1World();
                    drawRoomFaceDepth(buffer, matrix,
                        x0, y0, z,
                        x1, y0, z,
                        x1, y1, z,
                        x0, y1, z,
                        1.0f);
                }
            }
        }
    }

    private void drawSolidBlockFill(VertexConsumer buffer, Matrix4f matrix, float delta, RoomActivity activity, boolean activePass) {
        buffer.vertex(matrix, -10000.0f, -10000.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10000.0f, -10001.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10001.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10000.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);

        for (MapFace face : roomFaces) {
            if (isFaceActive(face, activity) != activePass) {
                continue;
            }

            float reveal = MathHelper.lerp(delta, face.lastReveal, face.reveal);
            if (reveal <= 0.01f) {
                continue;
            }

            float fillR = activePass ? SOLID_FILL_R : INACTIVE_SOLID_FILL_R;
            float fillG = activePass ? SOLID_FILL_G : INACTIVE_SOLID_FILL_G;
            float fillB = activePass ? SOLID_FILL_B : INACTIVE_SOLID_FILL_B;
            float alpha = (activePass ? SOLID_FILL_A : INACTIVE_SOLID_FILL_A) * reveal;

            switch (face.axis) {
                case X -> {
                    double x = face.planeWorld();
                    double y0 = face.a0World();
                    double y1 = face.a1World();
                    double z0 = face.b0World();
                    double z1 = face.b1World();
                    drawRoomFace(buffer, matrix,
                        x, y0, z0,
                        x, y1, z0,
                        x, y1, z1,
                        x, y0, z1,
                        fillR, fillG, fillB, alpha);
                }
                case Y -> {
                    double y = face.planeWorld();
                    double x0 = face.a0World();
                    double x1 = face.a1World();
                    double z0 = face.b0World();
                    double z1 = face.b1World();
                    drawRoomFace(buffer, matrix,
                        x0, y, z0,
                        x1, y, z0,
                        x1, y, z1,
                        x0, y, z1,
                        fillR, fillG, fillB, alpha);
                }
                case Z -> {
                    double z = face.planeWorld();
                    double x0 = face.a0World();
                    double x1 = face.a1World();
                    double y0 = face.b0World();
                    double y1 = face.b1World();
                    drawRoomFace(buffer, matrix,
                        x0, y0, z,
                        x1, y0, z,
                        x1, y1, z,
                        x0, y1, z,
                        fillR, fillG, fillB, alpha);
                }
            }
        }
    }

    private void drawScreenQuad(VertexConsumer buffer, Matrix4f matrix,
                                float x0, float y0, float x1, float y1,
                                float r, float g, float b, float a) {
        buffer.vertex(matrix, x0, y0, 0.0f).color(r, g, b, a);
        buffer.vertex(matrix, x0, y1, 0.0f).color(r, g, b, a);
        buffer.vertex(matrix, x1, y1, 0.0f).color(r, g, b, a);
        buffer.vertex(matrix, x1, y0, 0.0f).color(r, g, b, a);
    }

    private void drawRoomFace(VertexConsumer buffer, Matrix4f matrix,
                              double x1, double y1, double z1,
                              double x2, double y2, double z2,
                              double x3, double y3, double z3,
                              double x4, double y4, double z4,
                              float r, float g, float b, float a) {
        ProjectedPoint p1 = projectPoint(x1, y1, z1);
        ProjectedPoint p2 = projectPoint(x2, y2, z2);
        ProjectedPoint p3 = projectPoint(x3, y3, z3);
        ProjectedPoint p4 = projectPoint(x4, y4, z4);

        buffer.vertex(matrix, p1.x, p1.y, 0.0f).color(r, g, b, a);
        buffer.vertex(matrix, p2.x, p2.y, 0.0f).color(r, g, b, a);
        buffer.vertex(matrix, p3.x, p3.y, 0.0f).color(r, g, b, a);
        buffer.vertex(matrix, p4.x, p4.y, 0.0f).color(r, g, b, a);
    }

    private void drawRoomFaceDepth(VertexConsumer buffer, Matrix4f matrix,
                                   double x1, double y1, double z1,
                                   double x2, double y2, double z2,
                                   double x3, double y3, double z3,
                                   double x4, double y4, double z4,
                                   float depth) {
        ProjectedPoint p1 = projectPoint(x1, y1, z1);
        ProjectedPoint p2 = projectPoint(x2, y2, z2);
        ProjectedPoint p3 = projectPoint(x3, y3, z3);
        ProjectedPoint p4 = projectPoint(x4, y4, z4);

        buffer.vertex(matrix, p1.x, p1.y, depth).color(0.0f, 0.0f, 0.0f, 1.0f);
        buffer.vertex(matrix, p2.x, p2.y, depth).color(0.0f, 0.0f, 0.0f, 1.0f);
        buffer.vertex(matrix, p3.x, p3.y, depth).color(0.0f, 0.0f, 0.0f, 1.0f);
        buffer.vertex(matrix, p4.x, p4.y, depth).color(0.0f, 0.0f, 0.0f, 1.0f);
    }

    private static void drawScreenLineGradient(VertexConsumer buffer, Matrix4f matrix,
                                               float x1, float y1,
                                               float x2, float y2,
                                               float thickness,
                                               float r1, float g1, float b1, float a1,
                                               float r2, float g2, float b2, float a2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length <= 0.01f || (a1 <= 0.001f && a2 <= 0.001f)) {
            return;
        }

        float half = thickness * 0.5f;
        float ox = -dy / length * half;
        float oy = dx / length * half;

        buffer.vertex(matrix, x1 + ox, y1 + oy, 0.0f).color(r1, g1, b1, a1);
        buffer.vertex(matrix, x1 - ox, y1 - oy, 0.0f).color(r1, g1, b1, a1);
        buffer.vertex(matrix, x2 - ox, y2 - oy, 0.0f).color(r2, g2, b2, a2);
        buffer.vertex(matrix, x2 + ox, y2 + oy, 0.0f).color(r2, g2, b2, a2);
    }

    private void drawRing(VertexConsumer buffer, Matrix4f matrix,
                          double cx, double cy, double cz,
                          double radius,
                          Axis axis,
                          float r, float g, float b, float a) {
        if (radius <= 0.0) {
            return;
        }

        double previousX = 0.0;
        double previousY = 0.0;
        double previousZ = 0.0;

        for (int i = 0; i <= PLAYER_RING_SEGMENTS; i++) {
            double angle = (Math.PI * 2.0 * i) / PLAYER_RING_SEGMENTS;
            double ca = Math.cos(angle) * radius;
            double sa = Math.sin(angle) * radius;

            double x = cx;
            double y = cy;
            double z = cz;

            switch (axis) {
                case X -> {
                    y += ca;
                    z += sa;
                }
                case Y -> {
                    x += ca;
                    z += sa;
                }
                case Z -> {
                    x += ca;
                    y += sa;
                }
            }

            if (i > 0) {
                drawRawLine(buffer, matrix, previousX, previousY, previousZ, x, y, z, r, g, b, a);
            }

            previousX = x;
            previousY = y;
            previousZ = z;
        }
    }

    private static double[] revealedIntervalOnSegment(double cx, double cy, double cz,
                                                        double radius,
                                                        double ax, double ay, double az,
                                                        double bx, double by, double bz) {
        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double acx = ax - cx;
        double acy = ay - cy;
        double acz = az - cz;

        double a = abx * abx + aby * aby + abz * abz;
        if (a <= 1.0e-12) {
            double d2 = acx * acx + acy * acy + acz * acz;
            return d2 <= radius * radius ? new double[] {0.0, 1.0} : null;
        }

        double b = 2.0 * (acx * abx + acy * aby + acz * abz);
        double c = acx * acx + acy * acy + acz * acz - radius * radius;
        double discriminant = b * b - 4.0 * a * c;

        if (discriminant < 0.0) {
            // Entire segment is either inside or outside. Test the midpoint.
            double mx = (ax + bx) * 0.5;
            double my = (ay + by) * 0.5;
            double mz = (az + bz) * 0.5;
            double mdx = mx - cx;
            double mdy = my - cy;
            double mdz = mz - cz;
            return (mdx * mdx + mdy * mdy + mdz * mdz <= radius * radius) ? new double[] {0.0, 1.0} : null;
        }

        double sqrt = Math.sqrt(discriminant);
        double tEnter = (-b - sqrt) / (2.0 * a);
        double tExit = (-b + sqrt) / (2.0 * a);
        double t0 = Math.max(0.0, Math.min(tEnter, tExit));
        double t1 = Math.min(1.0, Math.max(tEnter, tExit));
        if (t1 < 0.0 || t0 > 1.0 || t1 < t0) {
            return null;
        }
        return new double[] {t0, t1};
    }

    private static double lerpDouble(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private static double distanceSqToSegment(double px, double py, double pz,
                                              double ax, double ay, double az,
                                              double bx, double by, double bz) {
        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double apx = px - ax;
        double apy = py - ay;
        double apz = pz - az;
        double abLen2 = abx * abx + aby * aby + abz * abz;

        if (abLen2 <= 1.0e-9) {
            double dx = px - ax;
            double dy = py - ay;
            double dz = pz - az;
            return dx * dx + dy * dy + dz * dz;
        }

        double t = (apx * abx + apy * aby + apz * abz) / abLen2;
        t = MathHelper.clamp(t, 0.0, 1.0);

        double cx = ax + abx * t;
        double cy = ay + aby * t;
        double cz = az + abz * t;

        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static float smoothStep(float value) {
        float t = MathHelper.clamp(value, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging && button == 0) {
            double dx = mouseX - lastMouseX;
            double dy = mouseY - lastMouseY;

            yaw += (float) (dx * 0.35f);
            pitch += (float) (dy * 0.35f);
            pitch = MathHelper.clamp(pitch, -85.0f, 85.0f);

            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        double factor = Math.pow(1.12, verticalAmount);
        zoom = MathHelper.clamp((float) (zoom * factor), 0.05f, 40.0f);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            rebuildGeometry();
            resetReveal();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_C) {
            focusX = revealCenterX;
            focusY = revealCenterY;
            focusZ = revealCenterZ;
            panX = 0.0;
            panY = 0.0;
            panZ = 0.0;
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private enum Axis {
        X,
        Y,
        Z
    }


    private static final class MapFace {
        private final Axis axis;
        private final int plane;
        private final int a0;
        private final int b0;
        private final int a1;
        private final int b1;
        private final int normalSign;

        private float reveal = 0.0f;
        private float lastReveal = 0.0f;
        private int revealStartTick = 0;

        private MapFace(Face face) {
            this.axis = face.axis;
            this.plane = face.plane;
            this.a0 = face.a0;
            this.b0 = face.b0;
            this.a1 = face.a1;
            this.b1 = face.b1;
            this.normalSign = face.normalSign;
        }

        private double planeWorld() {
            return plane * INV_SHAPE_UNIT;
        }

        private double a0World() {
            return a0 * INV_SHAPE_UNIT;
        }

        private double b0World() {
            return b0 * INV_SHAPE_UNIT;
        }

        private double a1World() {
            return a1 * INV_SHAPE_UNIT;
        }

        private double b1World() {
            return b1 * INV_SHAPE_UNIT;
        }

        private double centerX() {
            return switch (axis) {
                case X -> planeWorld();
                case Y, Z -> (a0World() + a1World()) * 0.5;
            };
        }

        private double centerY() {
            return switch (axis) {
                case Y -> planeWorld();
                case X -> (a0World() + a1World()) * 0.5;
                case Z -> (b0World() + b1World()) * 0.5;
            };
        }

        private double centerZ() {
            return switch (axis) {
                case Z -> planeWorld();
                case X, Y -> (b0World() + b1World()) * 0.5;
            };
        }
    }

    private static final class MapLine {
        private final int x1;
        private final int y1;
        private final int z1;
        private final int x2;
        private final int y2;
        private final int z2;

        private float reveal = 0.0f;
        private float lastReveal = 0.0f;
        private int revealStartTick = 0;

        private MapLine(EdgeKey key) {
            this.x1 = key.x1;
            this.y1 = key.y1;
            this.z1 = key.z1;
            this.x2 = key.x2;
            this.y2 = key.y2;
            this.z2 = key.z2;
        }

        private double x1World() {
            return x1 * INV_SHAPE_UNIT;
        }

        private double y1World() {
            return y1 * INV_SHAPE_UNIT;
        }

        private double z1World() {
            return z1 * INV_SHAPE_UNIT;
        }

        private double x2World() {
            return x2 * INV_SHAPE_UNIT;
        }

        private double y2World() {
            return y2 * INV_SHAPE_UNIT;
        }

        private double z2World() {
            return z2 * INV_SHAPE_UNIT;
        }
    }

    private record Face(Axis axis, int plane, int a0, int b0, int a1, int b1, int normalSign) {
    }

    private record FaceKey(Axis axis, int plane, int a0, int b0, int a1, int b1) {
    }

    private record PlaneKey(Axis axis, int plane) {
    }

    private record LineKey(int axis, int fixedA, int fixedB) {
    }

    private record RoomBounds(int x0, int y0, int z0, int x1, int y1, int z1) {
        private double x0World() {
            return x0 * INV_SHAPE_UNIT;
        }

        private double y0World() {
            return y0 * INV_SHAPE_UNIT;
        }

        private double z0World() {
            return z0 * INV_SHAPE_UNIT;
        }

        private double x1World() {
            return x1 * INV_SHAPE_UNIT;
        }

        private double y1World() {
            return y1 * INV_SHAPE_UNIT;
        }

        private double z1World() {
            return z1 * INV_SHAPE_UNIT;
        }
    }

    private record RoomActivity(boolean[] activeRooms) {
    }

    private record IntRange(int start, int end) {
    }

    private static final class EdgeUse {
        private final Map<PlaneKey, Integer> planes = new HashMap<>();

        private void add(PlaneKey plane) {
            planes.merge(plane, 1, Integer::sum);
        }

        private boolean isVisibleOutline() {
            for (int count : planes.values()) {
                if ((count & 1) == 1) {
                    return true;
                }
            }
            return false;
        }
    }

    private record ProjectedPoint(float x, float y) {
    }

    private record PointReveal(float r, float g, float b, float alpha, float front) {
        private static final PointReveal INVISIBLE = new PointReveal(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    private record EdgeKey(int x1, int y1, int z1, int x2, int y2, int z2) {
    }
}