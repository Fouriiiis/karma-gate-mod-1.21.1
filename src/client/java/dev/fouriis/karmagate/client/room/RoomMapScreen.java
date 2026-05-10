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
        // C# map starts from a pure black screen.
        context.fill(0, 0, this.width, this.height, 0xFF000000);

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
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawRevealWave(buffer, matrix, delta);
        drawRoomGeometry(buffer, matrix, delta);
        drawPlayerMarker(buffer, matrix, delta);
        BufferRenderer.drawWithGlobalProgram(buffer.end());

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


    private void drawRoomGeometry(VertexConsumer buffer, Matrix4f matrix, float delta) {
        double visibleRadius = lerpDouble(lastRevealRadius, revealRadius, delta);
        float fadeTicks = Math.max(
            LINE_FADE_MIN_TICKS,
            roomLines.size() * LINE_FADE_PER_SIZE_FACTOR
        );

        for (MapLine line : roomLines) {
            drawPartiallyRevealedLine(buffer, matrix, line, visibleRadius, delta, fadeTicks);
        }
    }

    private void drawPartiallyRevealedLine(VertexConsumer buffer,
                                           Matrix4f matrix,
                                           MapLine line,
                                           double visibleRadius,
                                           float delta,
                                           float fadeTicks) {
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
        PointReveal prevReveal = pointReveal(prevX, prevY, prevZ, visibleRadius, delta, fadeTicks);

        for (int i = 1; i <= pieces; i++) {
            double nextT = lerpDouble(t0, t1, (double) i / pieces);
            double nextX = lerpDouble(ax, bx, nextT);
            double nextY = lerpDouble(ay, by, nextT);
            double nextZ = lerpDouble(az, bz, nextT);
            PointReveal nextReveal = pointReveal(nextX, nextY, nextZ, visibleRadius, delta, fadeTicks);

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
                float shadowA0 = prevReveal.front * 0.055f;
                float shadowA1 = nextReveal.front * 0.055f;
                if (shadowA0 > 0.001f || shadowA1 > 0.001f) {
                    drawRawLineGradient(
                        buffer,
                        matrix,
                        prevX, prevY, prevZ,
                        nextX, nextY, nextZ,
                        DIM_R, DIM_G, DIM_B, shadowA0,
                        DIM_R, DIM_G, DIM_B, shadowA1
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
        List<RoomBounds> roomBounds = new ArrayList<>();

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
            roomFaces.add(new MapFace(face));
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
        toggleFace(faces, Axis.X, x0, y0, z0, y1, z1);
        toggleFace(faces, Axis.X, x1, y0, z0, y1, z1);

        toggleFace(faces, Axis.Y, y0, x0, z0, x1, z1);
        toggleFace(faces, Axis.Y, y1, x0, z0, x1, z1);

        toggleFace(faces, Axis.Z, z0, x0, y0, x1, y1);
        toggleFace(faces, Axis.Z, z1, x0, y0, x1, y1);
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
                                   int b1) {
        FaceKey key = new FaceKey(axis, plane, a0, b0, a1, b1);
        if (faces.containsKey(key)) {
            faces.remove(key);
        } else {
            faces.put(key, new Face(axis, plane, a0, b0, a1, b1));
        }
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

    private record Face(Axis axis, int plane, int a0, int b0, int a1, int b1) {
    }

    private record FaceKey(Axis axis, int plane, int a0, int b0, int a1, int b1) {
    }

    private record PlaneKey(Axis axis, int plane) {
    }

    private record LineKey(int axis, int fixedA, int fixedB) {
    }

    private record RoomBounds(int x0, int y0, int z0, int x1, int y1, int z1) {
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