package dev.fouriis.karmagate.client.room;

import com.mojang.blaze3d.systems.RenderSystem;
import net.brickcraftdream.librainworldmc.client.render.capture.FramebufferCaptureHelper;
import net.brickcraftdream.librainworldmc.client.render.capture.FramebufferRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.brickcraftdream.librainworldmc.client.util.TextureUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.*;
import net.minecraft.client.texture.NativeImage;
import rainworld.mechanics.common.block.pipes.PipeBlockEntity;
import rainworld.mechanics.common.block.pipes.PipeEntrance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.brickcraftdream.librainworldmc.Librainworldmc.MOD_ID;
import static net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC;

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

    // Reveal cells mimic the Rain World flood-fill map reveal.
    private static final int DISCOVERY_CELL_SIZE = 1;
    private static final int DISCOVERY_RADIUS_BLOCKS = 8;

    // Rain World updates its map reveal at 40 ticks per second, while Minecraft client
    // screen ticks run at 20 ticks per second. Scale the reveal work so one Minecraft
    // tick performs roughly two Rain World reveal ticks, then apply a small feel boost.
    private static final float RAIN_WORLD_TPS = 40.0f;
    private static final float MINECRAFT_TPS = 20.0f;
    private static final float REVEAL_TPS_SCALE = RAIN_WORLD_TPS / MINECRAFT_TPS;
    private static final float REVEAL_SPEED_FEEL = 1.35f;

    // The source reveal spreads over a 2D texture. This version spreads through 3D
    // room cells, so the tick-adjusted speed is squared to compensate for volume
    // growth instead of area growth. With the defaults: (40 / 20 * 1.35)^2 = 7.29x.
    private static final float REVEAL_LINEAR_SPEED = REVEAL_TPS_SCALE * REVEAL_SPEED_FEEL;
    private static final float REVEAL_VOLUME_SPEED = REVEAL_LINEAR_SPEED * REVEAL_LINEAR_SPEED;

    private static final float REVEAL_FRONTIER_SPEED = REVEAL_VOLUME_SPEED;
    private static final float REVEAL_FADE_SPEED = REVEAL_VOLUME_SPEED;

    // 3D reveal frontiers grow larger than Rain World's 2D pixel frontier. Keep the
    // pending-cell slowdown, but make it less punishing so large rooms do not crawl.
    private static final float REVEAL_3D_PENDING_PENALTY_SCALE = 0.5f;

    private static final int[][] REVEAL_DIRS = {
        { 1, 0, 0}, {-1, 0, 0},
        { 0, 1, 0}, { 0,-1, 0},
        { 0, 0, 1}, { 0, 0,-1}
    };

    private static final int PLAYER_RING_SEGMENTS = 48;
    private static final float PLAYER_RING_RADIUS = 0.85f;

    // Draw map lines as screen-space quads instead of RenderLayer.getLines().
    // The vanilla line shader can shimmer or fade out when the rotated map angle
    // changes because its line expansion depends on the supplied normal.
    private static final float MAP_LINE_THICKNESS = 0.5f;
    private static final double PIPE_DASH_LENGTH = 1.35;
    private static final double PIPE_DASH_GAP = 0.85;
    private static final double PIPE_DASH_SPEED = 0.125;
    private static final float PIPE_LINK_ALPHA = 0.75f;

    private final List<MapFace> roomFaces = new ArrayList<>();
    private final List<MapLine> roomLines = new ArrayList<>();
    private final List<PipeLink> pipeLinks = new ArrayList<>();
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
    private int revealTicks = 0;

    private static final Map<String, java.util.HashSet<LocalCell>> discoveredCellsByRoom = new HashMap<>();

    private final Map<RoomCell, Float> revealCells = new HashMap<>();
    private final List<RoomCell> revealFrontier = new ArrayList<>();
    private final List<RoomCell> revealFadeCells = new ArrayList<>();
    private final java.util.Random revealRandom = new java.util.Random();

    private final List<String> roomKeys = new ArrayList<>();
    private final Map<String, RoomClientState.RoomEntry> roomByKey = new HashMap<>();

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

        revealTicks++;
        updateDiscoveredAreaFromPlayer();
        if (!revealFrontier.isEmpty()) {
            revealRoutine();
        }
        if (!revealFadeCells.isEmpty()) {
            fadeRoutine();
        }
        updatePipeReveals();
        updateAggregateRevealValues();

        handleKeyboardPan(client);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Stencil-cutout backdrop outside the room volume.

        FramebufferRenderer fbRenderer = new FramebufferRenderer("room_map");

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        fbRenderer.render(drawContext -> {
            if (!hasRooms) {
                drawContext.drawCenteredTextWithShadow(textRenderer, Text.literal("No rooms"), this.width / 2, this.height / 2 - 4, 0xFFFFFFFF);
                return;
            }

            MatrixStack matrices = drawContext.getMatrices();
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
            BuiltBuffer inactiveSolidBuffer = inactiveSolids.endNullable();
            if(inactiveSolidBuffer != null) {
                BufferRenderer.drawWithGlobalProgram(inactiveSolidBuffer);
            }

            BufferBuilder inactiveLines = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            drawRevealWave(inactiveLines, matrix, delta);
            drawRoomGeometry(inactiveLines, matrix, delta, activity, false);
            drawPipeLinks(inactiveLines, matrix, delta, activity, false);
            BuiltBuffer inactiveLineBuffer = inactiveLines.endNullable();
            if(inactiveLineBuffer != null) {
                BufferRenderer.drawWithGlobalProgram(inactiveLineBuffer);
            }

            endActiveRoomDepthClip();

            BufferBuilder activeSolids = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            drawSolidBlockFill(activeSolids, matrix, delta, activity, true);
            BuiltBuffer activeSolidBuffer = activeSolids.endNullable();
            if(activeSolidBuffer != null) {
                BufferRenderer.drawWithGlobalProgram(activeSolidBuffer);
            }

            BufferBuilder activeLines = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            drawRoomGeometry(activeLines, matrix, delta, activity, true);
            drawPipeLinks(activeLines, matrix, delta, activity, true);
            drawPlayerMarker(activeLines, matrix, delta);
            BuiltBuffer activeLineBuffer = activeLines.endNullable();
            if(activeLineBuffer != null) {
                BufferRenderer.drawWithGlobalProgram(activeLineBuffer);
            }

            matrices.pop();
        });

        //you know what's funny? This will always be false cause people on macs can't use the main shader so it wouldn't make sense for them to play
        fbRenderer.finish(IS_SYSTEM_MAC);

        //Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
        //NativeImage color = FramebufferCaptureHelper.captureColorAttachment(fb, false, true, false);
        //if(color != null) {
        //    TextureUtils.registerNativeTexture(Identifier.of("karma-gate-mod", "mapgrabtex"), color);
        //}

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        BufferBuilder overlayBuffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        overlayBuffer.vertex(matrix, 0, 0, 0).color(0xffffffaa);
        overlayBuffer.vertex(matrix, 0, this.height, 0).color(0xffffffff);
        overlayBuffer.vertex(matrix, this.width, this.height, 0).color(0xffffffff);
        overlayBuffer.vertex(matrix, this.width, 0, 0).color(0xffffffff);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        //↓ comment this out if you want to remove the screen warping ↓
        CoreShaderRenderer.bindShader$SceneFisheye(Identifier.of("librainworldmc", "framebuffer/room_map"));

        BufferRenderer.drawWithGlobalProgram(overlayBuffer.end());
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
        for (MapLine line : roomLines) {
            if (isLineActive(line, activity) != activePass) {
                continue;
            }
            if (!lineHasFacingFace(line)) {
                continue;
            }
            drawPartiallyRevealedLine(buffer, matrix, line, delta, activePass);
        }
    }

    private void drawPartiallyRevealedLine(VertexConsumer buffer,
                                           Matrix4f matrix,
                                           MapLine line,
                                           float delta,
                                           boolean active) {
        double ax = line.x1World();
        double ay = line.y1World();
        double az = line.z1World();
        double bx = line.x2World();
        double by = line.y2World();
        double bz = line.z2World();

        double fullLength = Math.sqrt(
            (bx - ax) * (bx - ax) +
            (by - ay) * (by - ay) +
            (bz - az) * (bz - az)
        );
        if (fullLength <= 1.0e-6) {
            return;
        }

        int pieces = Math.max(1, Math.min(12, (int) Math.ceil(fullLength / 2.0)));

        double prevT = 0.0;
        double prevX = ax;
        double prevY = ay;
        double prevZ = az;
        PointReveal prevReveal = adjustedPointReveal(pointRevealInRoom(line.roomKey(), prevX, prevY, prevZ), active);

        for (int i = 1; i <= pieces; i++) {
            double nextT = (double) i / pieces;
            double nextX = lerpDouble(ax, bx, nextT);
            double nextY = lerpDouble(ay, by, nextT);
            double nextZ = lerpDouble(az, bz, nextT);
            PointReveal nextReveal = adjustedPointReveal(pointRevealInRoom(line.roomKey(), nextX, nextY, nextZ), active);

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

    private void drawPipeLinks(VertexConsumer buffer, Matrix4f matrix, float delta, RoomActivity activity, boolean activePass) {
        if (pipeLinks.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        List<RoomClientState.RoomEntry> rooms = RoomClientState.getRooms();
        int playerRoomIndex = -1;
        if (client.player != null) {
            playerRoomIndex = findRoomIndex(rooms, client.player.getBlockPos());
        }
        int[] distances = computeRoomDistances(rooms.size(), playerRoomIndex);

        float time = revealTicks + delta;

        for (PipeLink link : pipeLinks) {
            boolean startActive = isRoomActive(activity, link.startRoomIndex());
            boolean endActive = isRoomActive(activity, link.endRoomIndex());
            boolean linkActive = startActive || endActive;
            if (linkActive != activePass) {
                continue;
            }

            BlockPos start = link.start();
            BlockPos end = link.end();
            int startIndex = link.startRoomIndex();
            int endIndex = link.endRoomIndex();
            Direction startDir = link.startDirection();
            Direction endDir = link.endDirection();
            if (!shouldOrientAwayFromPlayer(startIndex, endIndex, distances)) {
                BlockPos swap = start;
                start = end;
                end = swap;

                int swapIndex = startIndex;
                startIndex = endIndex;
                endIndex = swapIndex;

                boolean swapActive = startActive;
                startActive = endActive;
                endActive = swapActive;

                Direction swapDir = startDir;
                startDir = endDir;
                endDir = swapDir;
            }

            double sx = start.getX() + 0.5;
            double sy = start.getY() + 0.5;
            double sz = start.getZ() + 0.5;
            double ex = end.getX() + 0.5;
            double ey = end.getY() + 0.5;
            double ez = end.getZ() + 0.5;

            double dx = ex - sx;
            double dy = ey - sy;
            double dz = ez - sz;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length <= 1.0e-6) {
                continue;
            }

            double[] startVec = resolveDirectionVector(startDir, dx, dy, dz, length, true);
            double[] endVec = resolveDirectionVector(endDir, dx, dy, dz, length, false);

            boolean drawingStartIsOriginalStart = start.equals(link.start());
            float aReveal = drawingStartIsOriginalStart
                ? MathHelper.lerp(delta, link.lastRevealA, link.revealA)
                : MathHelper.lerp(delta, link.lastRevealB, link.revealB);
            float bReveal = drawingStartIsOriginalStart
                ? MathHelper.lerp(delta, link.lastRevealB, link.revealB)
                : MathHelper.lerp(delta, link.lastRevealA, link.revealA);

            drawDashedBezier(buffer, matrix, sx, sy, sz, ex, ey, ez,
                startVec[0], startVec[1], startVec[2],
                endVec[0], endVec[1], endVec[2],
                startActive, endActive,
                aReveal, bReveal, time);
        }
    }

    private static String roomKey(RoomClientState.RoomEntry room) {
        BlockPos min = room.min();
        BlockPos max = room.max();
        return min.getX() + "," + min.getY() + "," + min.getZ()
            + "->"
            + max.getX() + "," + max.getY() + "," + max.getZ();
    }

    private void updateDiscoveredAreaFromPlayer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        List<RoomClientState.RoomEntry> rooms = RoomClientState.getRooms();
        if (rooms.isEmpty()) {
            return;
        }

        BlockPos playerPos = client.player.getBlockPos();
        revealCenterX = client.player.getX();
        revealCenterY = client.player.getY() + 0.9;
        revealCenterZ = client.player.getZ();

        int roomIndex = findRoomIndex(rooms, playerPos);

        // Do not discover or reveal anything when the player is between registered rooms.
        // This is what prevents nearby rooms from leaking into each other.
        if (roomIndex < 0) {
            return;
        }

        RoomClientState.RoomEntry room = rooms.get(roomIndex);
        addDiscoveredPatch(room, playerPos);
        seedRevealAround(room, playerPos, 1);
    }

    private void addDiscoveredPatch(RoomClientState.RoomEntry room, BlockPos center) {
        String key = roomKey(room);
        java.util.HashSet<LocalCell> cells = discoveredCellsByRoom.computeIfAbsent(key, ignored -> new java.util.HashSet<>());

        BlockPos min = room.min();
        BlockPos max = room.max();

        for (int dx = -DISCOVERY_RADIUS_BLOCKS; dx <= DISCOVERY_RADIUS_BLOCKS; dx++) {
            for (int dy = -DISCOVERY_RADIUS_BLOCKS; dy <= DISCOVERY_RADIUS_BLOCKS; dy++) {
                for (int dz = -DISCOVERY_RADIUS_BLOCKS; dz <= DISCOVERY_RADIUS_BLOCKS; dz++) {
                    int wx = center.getX() + dx;
                    int wy = center.getY() + dy;
                    int wz = center.getZ() + dz;

                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > DISCOVERY_RADIUS_BLOCKS * DISCOVERY_RADIUS_BLOCKS) {
                        continue;
                    }

                    // Hard room-border clamp. Close rooms never share discovered cells.
                    if (!worldBlockInsideRoom(room, wx, wy, wz)) {
                        continue;
                    }

                    cells.add(toLocalCell(room, wx, wy, wz));
                }
            }
        }
    }

    private void seedRevealAround(RoomClientState.RoomEntry room, BlockPos center, int radiusCells) {
        String key = roomKey(room);
        LocalCell local = toLocalCell(room, center.getX(), center.getY(), center.getZ());

        for (int dx = -radiusCells; dx <= radiusCells; dx++) {
            for (int dy = -radiusCells; dy <= radiusCells; dy++) {
                for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                    RoomCell cell = new RoomCell(key, local.x() + dx, local.y() + dy, local.z() + dz);
                    if (shouldRevealCell(cell)) {
                        addCellToRevealList(cell);
                    }
                }
            }
        }

        RoomCell centerCell = new RoomCell(key, local.x(), local.y(), local.z());
        if (isDiscovered(centerCell) && revealCells.getOrDefault(centerCell, 0.0f) < 1.0f) {
            revealCells.put(centerCell, 1.0f);
        }
    }

    private static boolean worldBlockInsideRoom(RoomClientState.RoomEntry room, int wx, int wy, int wz) {
        BlockPos min = room.min();
        BlockPos max = room.max();
        return wx >= min.getX() && wx <= max.getX()
            && wy >= min.getY() && wy <= max.getY()
            && wz >= min.getZ() && wz <= max.getZ();
    }

    private LocalCell toLocalCell(RoomClientState.RoomEntry room, int wx, int wy, int wz) {
        BlockPos min = room.min();
        return new LocalCell(
            (wx - min.getX()) / DISCOVERY_CELL_SIZE,
            (wy - min.getY()) / DISCOVERY_CELL_SIZE,
            (wz - min.getZ()) / DISCOVERY_CELL_SIZE
        );
    }

    private boolean isDiscovered(RoomCell cell) {
        java.util.HashSet<LocalCell> cells = discoveredCellsByRoom.get(cell.roomKey());
        if (cells == null) {
            return false;
        }
        return cells.contains(new LocalCell(cell.x(), cell.y(), cell.z()));
    }

    private boolean roomHasDiscoveredCells(String roomKey) {
        java.util.HashSet<LocalCell> cells = discoveredCellsByRoom.get(roomKey);
        return cells != null && !cells.isEmpty();
    }

    private RoomCell nearestDiscoveredCellInRoom(RoomCell preferredCell) {
        if (preferredCell == null) {
            return null;
        }

        java.util.HashSet<LocalCell> cells = discoveredCellsByRoom.get(preferredCell.roomKey());
        if (cells == null || cells.isEmpty()) {
            return null;
        }

        LocalCell preferredLocal = new LocalCell(preferredCell.x(), preferredCell.y(), preferredCell.z());
        if (cells.contains(preferredLocal)) {
            return preferredCell;
        }

        LocalCell best = null;
        long bestDistance = Long.MAX_VALUE;
        for (LocalCell candidate : cells) {
            long dx = (long) candidate.x() - preferredCell.x();
            long dy = (long) candidate.y() - preferredCell.y();
            long dz = (long) candidate.z() - preferredCell.z();
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        if (best == null) {
            return null;
        }
        return new RoomCell(preferredCell.roomKey(), best.x(), best.y(), best.z());
    }

    private boolean shouldRevealCell(RoomCell cell) {
        return isDiscovered(cell) && revealCells.getOrDefault(cell, 0.0f) == 0.0f;
    }

    private boolean addCellToRevealList(RoomCell cell) {
        if (cell == null || !isDiscovered(cell) || revealCells.getOrDefault(cell, 0.0f) != 0.0f) {
            return false;
        }

        revealCells.put(cell, 0.05f);
        revealFrontier.add(cell);
        revealFadeCells.add(cell);
        return true;
    }

    private boolean seedRevealAtNearestDiscoveredCell(RoomCell preferredCell) {
        RoomCell seed = nearestDiscoveredCellInRoom(preferredCell);
        return seed != null && addCellToRevealList(seed);
    }

    private void assignPipeRevealCells() {
        for (PipeLink link : pipeLinks) {
            if (link.startRoomIndex() < 0 || link.startRoomIndex() >= roomKeys.size()
                || link.endRoomIndex() < 0 || link.endRoomIndex() >= roomKeys.size()) {
                continue;
            }
            String startKey = roomKeys.get(link.startRoomIndex());
            String endKey = roomKeys.get(link.endRoomIndex());
            RoomClientState.RoomEntry startRoom = roomByKey.get(startKey);
            RoomClientState.RoomEntry endRoom = roomByKey.get(endKey);
            if (startRoom == null || endRoom == null) {
                continue;
            }

            BlockPos start = link.start();
            BlockPos end = link.end();
            LocalCell startLocal = toLocalCell(startRoom, start.getX(), start.getY(), start.getZ());
            LocalCell endLocal = toLocalCell(endRoom, end.getX(), end.getY(), end.getZ());

            link.setRevealCells(new RoomCell(startKey, startLocal.x(), startLocal.y(), startLocal.z()),
                new RoomCell(endKey, endLocal.x(), endLocal.y(), endLocal.z()));
        }
    }

    private void revealRoutine() {
        int count = revealFrontier.size();
        int baseRevealCount = (int) lerpDouble(
            6.0,
            1.0,
            MathHelper.clamp((count - 1.0f) / 99.0f, 0.0f, 1.0f)
        );
        int revealCount = Math.max(1, Math.round(baseRevealCount * REVEAL_FRONTIER_SPEED));

        for (int i = 0; i < revealCount && !revealFrontier.isEmpty(); i++) {
            int index = revealRandom.nextInt(revealFrontier.size());
            RoomCell cell = revealFrontier.remove(index);
            revealCell(cell);
        }

        int closest = nextRevealCellClosestToView();
        if (closest >= 0) {
            RoomCell cell = revealFrontier.remove(closest);
            revealCell(cell);
        }
    }

    private int nextRevealCellClosestToView() {
        if (revealFrontier.isEmpty()) {
            return -1;
        }

        double viewX = focusX - panX;
        double viewY = focusY - panY;
        double viewZ = focusZ - panZ;

        int bestIndex = -1;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (int i = 0; i < revealFrontier.size(); i++) {
            RoomCell cell = revealFrontier.get(i);
            RoomClientState.RoomEntry room = roomByKey.get(cell.roomKey());
            if (room == null) {
                continue;
            }
            BlockPos min = room.min();
            double cx = min.getX() + cell.x() + 0.5;
            double cy = min.getY() + cell.y() + 0.5;
            double cz = min.getZ() + cell.z() + 0.5;
            double dx = cx - viewX;
            double dy = cy - viewY;
            double dz = cz - viewZ;
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDistance) {
                bestDistance = dist;
                bestIndex = i;
            }
        }

        return bestIndex;
    }

    private void revealCell(RoomCell cell) {
        for (int[] dir : REVEAL_DIRS) {
            RoomCell next = new RoomCell(
                cell.roomKey(),
                cell.x() + dir[0],
                cell.y() + dir[1],
                cell.z() + dir[2]
            );
            if (shouldRevealCell(next)) {
                addCellToRevealList(next);
            }
        }

        revealPipeEndpointIfNeeded(cell);
    }

    private void revealPipeEndpointIfNeeded(RoomCell cell) {
        for (PipeLink link : pipeLinks) {
            if (link.startRevealCell == null || link.endRevealCell == null) {
                continue;
            }

            if (cell.equals(link.startRevealCell)) {
                revealPipeAcrossDiscoveredRoom(link, true);
            } else if (cell.equals(link.endRevealCell)) {
                revealPipeAcrossDiscoveredRoom(link, false);
            }
        }
    }

    private void revealPipeAcrossDiscoveredRoom(PipeLink link, boolean fromStartToEnd) {
        RoomCell source = fromStartToEnd ? link.startRevealCell : link.endRevealCell;
        RoomCell destination = fromStartToEnd ? link.endRevealCell : link.startRevealCell;

        if (source == null || destination == null) {
            return;
        }

        // Important: do not require the destination pipe entrance cell itself to be discovered.
        // In Minecraft the entrance block may sit on the room shell or outside the player's
        // local discovery sphere, even when the destination room was already explored. Rain
        // World's 2D map uses a texture pixel at the shortcut endpoint; here we jump to the
        // nearest discovered cell in that destination room instead.
        boolean destinationRoomKnown = roomHasDiscoveredCells(destination.roomKey());
        RoomCell destinationSeed = nearestDiscoveredCellInRoom(destination);
        if (destinationSeed != null) {
            addCellToRevealList(destinationSeed);
        }

        int startDelay = Math.max(1, 1 + revealFrontier.size());
        int endDelay = Math.max(startDelay + 1, 1 + revealFrontier.size());

        if (fromStartToEnd) {
            if (link.startRevealA < 0) {
                link.startRevealA = startDelay;
                if (link.startRevealB < 0) {
                    link.direction = 0.0f;
                }
            }
            if (destinationRoomKnown && link.startRevealB < 0) {
                link.startRevealB = endDelay;
            }
        } else {
            if (link.startRevealB < 0) {
                link.startRevealB = startDelay;
                if (link.startRevealA < 0) {
                    link.direction = 1.0f;
                }
            }
            if (destinationRoomKnown && link.startRevealA < 0) {
                link.startRevealA = endDelay;
            }
        }
    }

    private void fadeRoutine() {
        float pendingPenalty = 1.0f + revealFrontier.size() * REVEAL_3D_PENDING_PENALTY_SCALE;

        for (int i = revealFadeCells.size() - 1; i >= 0; i--) {
            RoomCell cell = revealFadeCells.get(i);
            float target = 1.0f;
            float current = revealCells.getOrDefault(cell, 0.0f);

            current += REVEAL_FADE_SPEED * revealRandom.nextFloat() / pendingPenalty;
            current = Math.min(current, target);
            revealCells.put(cell, current);

            if (current >= target) {
                revealFadeCells.remove(i);
            }
        }
    }

    private void updateAggregateRevealValues() {
        for (MapFace face : roomFaces) {
            face.lastReveal = face.reveal;
            face.reveal = MathHelper.clamp(sampleRevealInRoom(face.roomKey(), face.centerX(), face.centerY(), face.centerZ()), 0.0f, 1.0f);
        }
        for (MapLine line : roomLines) {
            line.lastReveal = line.reveal;
            double mx = (line.x1World() + line.x2World()) * 0.5;
            double my = (line.y1World() + line.y2World()) * 0.5;
            double mz = (line.z1World() + line.z2World()) * 0.5;
            line.reveal = MathHelper.clamp(sampleRevealInRoom(line.roomKey(), mx, my, mz), 0.0f, 1.0f);
        }
    }

    private void updatePipeReveals() {
        float denom = Math.max(1.0f, (float) lerpDouble(30.0, revealFrontier.size(), 0.5));
        int delayStep = Math.max(1, Math.round(REVEAL_FRONTIER_SPEED));

        for (PipeLink link : pipeLinks) {
            link.lastRevealA = link.revealA;
            link.lastRevealB = link.revealB;

            if (link.startRevealA > 0) {
                link.startRevealA = Math.max(0, link.startRevealA - delayStep);
            } else if (link.startRevealA == 0 && link.revealA < 1.0f) {
                link.revealA = Math.min(1.0f, link.revealA + REVEAL_FADE_SPEED / denom);
            }

            if (link.startRevealB > 0) {
                link.startRevealB = Math.max(0, link.startRevealB - delayStep);
            } else if (link.startRevealB == 0 && link.revealB < 1.0f) {
                link.revealB = Math.min(1.0f, link.revealB + REVEAL_FADE_SPEED / denom);
            }
        }
    }

    private static boolean isRoomActive(RoomActivity activity, int roomIndex) {
        if (activity == null || activity.activeRooms == null) {
            return false;
        }
        return roomIndex >= 0 && roomIndex < activity.activeRooms.length && activity.activeRooms[roomIndex];
    }

    private boolean shouldOrientAwayFromPlayer(int startIndex, int endIndex, int[] distances) {
        if (distances == null) {
            return true;
        }

        int startDistance = getRoomDistance(distances, startIndex);
        int endDistance = getRoomDistance(distances, endIndex);
        if (startDistance < 0 && endDistance < 0) {
            return true;
        }
        if (startDistance < 0) {
            return false;
        }
        if (endDistance < 0) {
            return true;
        }
        if (startDistance == endDistance) {
            return true;
        }
        return startDistance < endDistance;
    }

    private static int getRoomDistance(int[] distances, int index) {
        if (distances == null || index < 0 || index >= distances.length) {
            return -1;
        }
        return distances[index];
    }

    private int[] computeRoomDistances(int roomCount, int startRoomIndex) {
        int[] distances = new int[roomCount];
        Arrays.fill(distances, -1);
        if (startRoomIndex < 0 || startRoomIndex >= roomCount) {
            return distances;
        }

        List<List<Integer>> adjacency = new ArrayList<>(roomCount);
        for (int i = 0; i < roomCount; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (PipeLink link : pipeLinks) {
            int a = link.startRoomIndex();
            int b = link.endRoomIndex();
            if (a < 0 || b < 0 || a >= roomCount || b >= roomCount) {
                continue;
            }
            adjacency.get(a).add(b);
            adjacency.get(b).add(a);
        }

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        distances[startRoomIndex] = 0;
        queue.add(startRoomIndex);

        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int nextDistance = distances[current] + 1;
            for (int neighbor : adjacency.get(current)) {
                if (distances[neighbor] >= 0) {
                    continue;
                }
                distances[neighbor] = nextDistance;
                queue.addLast(neighbor);
            }
        }

        return distances;
    }

    private void drawDashedBezier(VertexConsumer buffer,
                                  Matrix4f matrix,
                                  double x1, double y1, double z1,
                                  double x2, double y2, double z2,
                                  double startDirX, double startDirY, double startDirZ,
                                  double endDirX, double endDirY, double endDirZ,
                                  boolean startActive,
                                  boolean endActive,
                                  float aReveal,
                                  float bReveal,
                                  float time) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0e-6) {
            return;
        }

        double handle = Math.min(6.0, Math.max(1.25, length * 0.35));
        double c1x = x1 + startDirX * handle;
        double c1y = y1 + startDirY * handle;
        double c1z = z1 + startDirZ * handle;
        double c2x = x2 - endDirX * handle;
        double c2y = y2 - endDirY * handle;
        double c2z = z2 - endDirZ * handle;

        int sampleCount = clampInt((int) Math.ceil(length * 2.0), 12, 40);
        double[] xs = new double[sampleCount + 1];
        double[] ys = new double[sampleCount + 1];
        double[] zs = new double[sampleCount + 1];
        double[] lengths = new double[sampleCount + 1];

        double lastX = x1;
        double lastY = y1;
        double lastZ = z1;
        xs[0] = lastX;
        ys[0] = lastY;
        zs[0] = lastZ;
        lengths[0] = 0.0;

        for (int i = 1; i <= sampleCount; i++) {
            double t = (double) i / sampleCount;
            double ix = cubicBezier(x1, c1x, c2x, x2, t);
            double iy = cubicBezier(y1, c1y, c2y, y2, t);
            double iz = cubicBezier(z1, c1z, c2z, z2, t);
            xs[i] = ix;
            ys[i] = iy;
            zs[i] = iz;
            double segDx = ix - lastX;
            double segDy = iy - lastY;
            double segDz = iz - lastZ;
            lengths[i] = lengths[i - 1] + Math.sqrt(segDx * segDx + segDy * segDy + segDz * segDz);
            lastX = ix;
            lastY = iy;
            lastZ = iz;
        }

        double totalLength = lengths[sampleCount];
        if (totalLength <= 1.0e-6) {
            return;
        }

        double step = PIPE_DASH_LENGTH + PIPE_DASH_GAP;
        double offset = (time * PIPE_DASH_SPEED) % step;

        for (double dashStart = offset; dashStart < totalLength; dashStart += step) {
            double dashEnd = Math.min(dashStart + PIPE_DASH_LENGTH, totalLength);
            if (dashEnd <= dashStart) {
                continue;
            }

            CurvePoint p0 = sampleCurvePoint(xs, ys, zs, lengths, dashStart);
            CurvePoint p1 = sampleCurvePoint(xs, ys, zs, lengths, dashEnd);

            float reveal0 = lerp(aReveal, bReveal, (float) p0.t);
            float reveal1 = lerp(aReveal, bReveal, (float) p1.t);
            ColorSample c0 = blendPipeColor(startActive, endActive, (float) p0.t, reveal0);
            ColorSample c1 = blendPipeColor(startActive, endActive, (float) p1.t, reveal1);
            if (c0.a <= 0.001f && c1.a <= 0.001f) {
                continue;
            }

            drawRawLineGradient(
                buffer,
                matrix,
                p0.x, p0.y, p0.z,
                p1.x, p1.y, p1.z,
                c0.r, c0.g, c0.b, c0.a,
                c1.r, c1.g, c1.b, c1.a
            );
        }
    }

    private ColorSample blendPipeColor(boolean startActive, boolean endActive, float t, float reveal) {
        PointReveal base = new PointReveal(MAP_R, MAP_G, MAP_B, 1.0f, 1.0f);
        PointReveal startColor = adjustedPointReveal(base, startActive);
        PointReveal endColor = adjustedPointReveal(base, endActive);
        float r = lerp(startColor.r, endColor.r, t);
        float g = lerp(startColor.g, endColor.g, t);
        float b = lerp(startColor.b, endColor.b, t);
        float a = lerp(startColor.alpha, endColor.alpha, t) * PIPE_LINK_ALPHA * reveal;
        return new ColorSample(r, g, b, a);
    }

    private static double[] resolveDirectionVector(Direction dir,
                                                   double dx,
                                                   double dy,
                                                   double dz,
                                                   double length,
                                                   boolean isStart) {
        double vx;
        double vy;
        double vz;

        if (dir != null) {
            vx = -dir.getOffsetX();
            vy = -dir.getOffsetY();
            vz = -dir.getOffsetZ();
        } else {
            if (length <= 1.0e-6) {
                vx = isStart ? 1.0 : -1.0;
                vy = 0.0;
                vz = 0.0;
            } else {
                vx = dx / length;
                vy = dy / length;
                vz = dz / length;
            }
        }

        double vLen = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vLen <= 1.0e-6) {
            return new double[] {isStart ? 1.0 : -1.0, 0.0, 0.0};
        }
        return new double[] {vx / vLen, vy / vLen, vz / vLen};
    }

    private static CurvePoint sampleCurvePoint(double[] xs, double[] ys, double[] zs, double[] lengths, double distance) {
        int count = lengths.length - 1;
        if (distance <= 0.0) {
            return new CurvePoint(xs[0], ys[0], zs[0], 0.0);
        }
        if (distance >= lengths[count]) {
            return new CurvePoint(xs[count], ys[count], zs[count], 1.0);
        }

        int segment = 0;
        while (segment < count && lengths[segment + 1] < distance) {
            segment++;
        }
        double segStart = lengths[segment];
        double segEnd = lengths[segment + 1];
        double segLength = Math.max(1.0e-9, segEnd - segStart);
        double localT = (distance - segStart) / segLength;
        double x = lerpDouble(xs[segment], xs[segment + 1], localT);
        double y = lerpDouble(ys[segment], ys[segment + 1], localT);
        double z = lerpDouble(zs[segment], zs[segment + 1], localT);
        double t = (segment + localT) / count;
        return new CurvePoint(x, y, z, t);
    }

    private static double cubicBezier(double a, double b, double c, double d, double t) {
        double it = 1.0 - t;
        double it2 = it * it;
        double t2 = t * t;
        return it2 * it * a + 3.0 * it2 * t * b + 3.0 * it * t2 * c + t2 * t * d;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float sampleRevealInRoom(String roomKey, double wx, double wy, double wz) {
        RoomClientState.RoomEntry room = roomByKey.get(roomKey);
        if (room == null) {
            return 0.0f;
        }

        LocalCell base = toLocalCell(room,
            (int) Math.floor(wx),
            (int) Math.floor(wy),
            (int) Math.floor(wz));
        float best = 0.0f;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    RoomCell cell = new RoomCell(roomKey, base.x() + dx, base.y() + dy, base.z() + dz);
                    best = Math.max(best, revealCells.getOrDefault(cell, 0.0f));
                }
            }
        }

        return best;
    }

    private PointReveal pointRevealInRoom(String roomKey, double wx, double wy, double wz) {
        float v = sampleRevealInRoom(roomKey, wx, wy, wz);
        if (v <= 0.001f) {
            return PointReveal.INVISIBLE;
        }

        float smooth = smoothStep(MathHelper.clamp(v, 0.0f, 1.0f));
        float fresh = 1.0f - smooth;
        float r = lerp(MAP_R, WHITE_R, fresh);
        float g = lerp(MAP_G, WHITE_G, fresh);
        float b = lerp(MAP_B, WHITE_B, fresh);
        float a = smooth * (0.25f + 0.75f * smooth);
        return new PointReveal(r, g, b, a, smooth);
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
        // The Rain World-style flood reveal does not use a radial wave indicator.
        return;
    }

    private void drawHudHints(DrawContext context) {
        String progress = discoveredCellsByRoom.isEmpty()
            ? "0%"
            : (int) (100.0f * revealedFraction()) + "%";
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("WASD pan  •  Space/Shift height  •  Drag rotate  •  Wheel zoom  •  R rebuild/reveal  •  " + progress),
            10,
            this.height - 18,
            0x88FFFFFF
        );
    }

    private float revealedFraction() {
        int total = 0;
        float sum = 0.0f;
        for (Map.Entry<String, java.util.HashSet<LocalCell>> entry : discoveredCellsByRoom.entrySet()) {
            total += entry.getValue().size();
        }
        if (total == 0) {
            return 0.0f;
        }

        for (Map.Entry<RoomCell, Float> entry : revealCells.entrySet()) {
            sum += MathHelper.clamp(entry.getValue(), 0.0f, 1.0f);
        }

        return MathHelper.clamp(sum / total, 0.0f, 1.0f);
    }

    private void rebuildGeometry() {
        roomFaces.clear();
        roomLines.clear();
        pipeLinks.clear();
        roomBounds.clear();
        facesByPlane.clear();
        roomKeys.clear();
        roomByKey.clear();
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

        Map<PipeLinkKey, PipeLink> uniquePipeLinks = new HashMap<>();

        minWorldX = Double.POSITIVE_INFINITY;
        minWorldY = Double.POSITIVE_INFINITY;
        minWorldZ = Double.POSITIVE_INFINITY;
        maxWorldX = Double.NEGATIVE_INFINITY;
        maxWorldY = Double.NEGATIVE_INFINITY;
        maxWorldZ = Double.NEGATIVE_INFINITY;

        Map<FaceKey, Face> exteriorFaces = new HashMap<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int roomIndex = 0; roomIndex < rooms.size(); roomIndex++) {
            RoomClientState.RoomEntry room = rooms.get(roomIndex);
            String key = roomKey(room);
            roomKeys.add(key);
            roomByKey.put(key, room);
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

                        if (state.getBlock() instanceof PipeEntrance) {
                            BlockPos exitPos = RoomPipeMapHelper.findOtherEntrance(world, mutable);
                            if (exitPos != null) {
                                int exitRoomIndex = findRoomIndex(rooms, exitPos);
                                if (exitRoomIndex >= 0 && exitRoomIndex != roomIndex) {
                                    BlockPos startPos = mutable.toImmutable();
                                    PipeLinkKey linkKey = pipeLinkKey(startPos, exitPos);
                                    Direction startDir = getEntranceDirection(state);
                                    Direction endDir = getEntranceDirection(world.getBlockState(exitPos));
                                    uniquePipeLinks.putIfAbsent(
                                        linkKey,
                                        new PipeLink(startPos, exitPos, roomIndex, exitRoomIndex, startDir, endDir)
                                    );
                                }
                            }
                        }

                        // Use only the physical collision shape for map geometry.
                        // Blocks such as tall grass have an outline/selection shape but no
                        // collision, so using getOutlineShape() would make decorative plants
                        // appear as solid map geometry. Pipe links are detected above before
                        // this filter, so non-solid pipe entrance blocks can still contribute
                        // shortcut connections without drawing as room solids.
                        VoxelShape shape = state.getCollisionShape(world, mutable);
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
            MapFace mapFace = new MapFace(face, findRoomKeyForFace(face));
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
            roomLines.add(new MapLine(edge, findRoomKeyForEdge(edge)));
        }
        hasRooms = !roomLines.isEmpty();
        pipeLinks.addAll(uniquePipeLinks.values());

        assignPipeRevealCells();

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
        revealCells.clear();
        revealFrontier.clear();
        revealFadeCells.clear();
        for (MapFace face : roomFaces) {
            face.reveal = 0.0f;
            face.lastReveal = 0.0f;
        }
        for (MapLine line : roomLines) {
            line.reveal = 0.0f;
            line.lastReveal = 0.0f;
        }

        for (PipeLink link : pipeLinks) {
            link.resetReveal();
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        List<RoomClientState.RoomEntry> rooms = RoomClientState.getRooms();
        int roomIndex = findRoomIndex(rooms, client.player.getBlockPos());
        if (roomIndex < 0) {
            return;
        }

        RoomClientState.RoomEntry room = rooms.get(roomIndex);
        BlockPos playerPos = client.player.getBlockPos();

        addDiscoveredPatch(room, playerPos);
        seedRevealAround(room, playerPos, 1);
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

    private static int findRoomIndex(List<RoomClientState.RoomEntry> rooms, BlockPos pos) {
        for (int i = 0; i < rooms.size(); i++) {
            RoomClientState.RoomEntry room = rooms.get(i);
            BlockPos min = room.min();
            BlockPos max = room.max();
            if (pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ()) {
                return i;
            }
        }
        return -1;
    }

    private String findRoomKeyForFace(Face face) {
        double cx;
        double cy;
        double cz;

        switch (face.axis) {
            case X -> {
                cx = face.plane * INV_SHAPE_UNIT;
                cy = (face.a0 + face.a1) * 0.5 * INV_SHAPE_UNIT;
                cz = (face.b0 + face.b1) * 0.5 * INV_SHAPE_UNIT;
            }
            case Y -> {
                cx = (face.a0 + face.a1) * 0.5 * INV_SHAPE_UNIT;
                cy = face.plane * INV_SHAPE_UNIT;
                cz = (face.b0 + face.b1) * 0.5 * INV_SHAPE_UNIT;
            }
            case Z -> {
                cx = (face.a0 + face.a1) * 0.5 * INV_SHAPE_UNIT;
                cy = (face.b0 + face.b1) * 0.5 * INV_SHAPE_UNIT;
                cz = face.plane * INV_SHAPE_UNIT;
            }
            default -> {
                return null;
            }
        }

        String key = findRoomKeyForWorldPoint(cx, cy, cz);
        if (key != null) {
            return key;
        }

        for (int i = 0; i < roomBounds.size(); i++) {
            if (faceIntersectsRoom(face, roomBounds.get(i))) {
                return roomKeys.get(i);
            }
        }
        return null;
    }

    private String findRoomKeyForEdge(EdgeKey edge) {
        double cx = (edge.x1 + edge.x2) * 0.5 * INV_SHAPE_UNIT;
        double cy = (edge.y1 + edge.y2) * 0.5 * INV_SHAPE_UNIT;
        double cz = (edge.z1 + edge.z2) * 0.5 * INV_SHAPE_UNIT;

        String key = findRoomKeyForWorldPoint(cx, cy, cz);
        if (key != null) {
            return key;
        }

        for (int i = 0; i < roomBounds.size(); i++) {
            if (edgeIntersectsRoom(edge, roomBounds.get(i))) {
                return roomKeys.get(i);
            }
        }
        return null;
    }

    private String findRoomKeyForWorldPoint(double x, double y, double z) {
        for (int i = 0; i < roomBounds.size(); i++) {
            if (pointInsideRoomBounds(x, y, z, roomBounds.get(i))) {
                return roomKeys.get(i);
            }
        }
        return null;
    }

    private static boolean pointInsideRoomBounds(double x, double y, double z, RoomBounds room) {
        double epsilon = 1.0e-5;
        return x >= room.x0World() - epsilon && x <= room.x1World() + epsilon
            && y >= room.y0World() - epsilon && y <= room.y1World() + epsilon
            && z >= room.z0World() - epsilon && z <= room.z1World() + epsilon;
    }

    private static Direction getEntranceDirection(BlockState state) {
        if (!(state.getBlock() instanceof PipeEntrance)) {
            return null;
        }

        PipeEntrance.Orientation orientation = state.get(PipeEntrance.ORIENTATION);
        return orientation == null ? null : orientation.getDirection();
    }

    private static PipeLinkKey pipeLinkKey(BlockPos a, BlockPos b) {
        if (compareBlockPos(a, b) > 0) {
            BlockPos temp = a;
            a = b;
            b = temp;
        }
        return new PipeLinkKey(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) {
            return Integer.compare(a.getX(), b.getX());
        }
        if (a.getY() != b.getY()) {
            return Integer.compare(a.getY(), b.getY());
        }
        return Integer.compare(a.getZ(), b.getZ());
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
        edges.putIfAbsent(key, new MapLine(key, null));
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

    private static boolean faceIntersectsRoom(Face face, RoomBounds room) {
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;

        switch (face.axis) {
            case X -> {
                minX = face.plane * INV_SHAPE_UNIT;
                maxX = minX;
                minY = Math.min(face.a0, face.a1) * INV_SHAPE_UNIT;
                maxY = Math.max(face.a0, face.a1) * INV_SHAPE_UNIT;
                minZ = Math.min(face.b0, face.b1) * INV_SHAPE_UNIT;
                maxZ = Math.max(face.b0, face.b1) * INV_SHAPE_UNIT;
            }
            case Y -> {
                minX = Math.min(face.a0, face.a1) * INV_SHAPE_UNIT;
                maxX = Math.max(face.a0, face.a1) * INV_SHAPE_UNIT;
                minY = face.plane * INV_SHAPE_UNIT;
                maxY = minY;
                minZ = Math.min(face.b0, face.b1) * INV_SHAPE_UNIT;
                maxZ = Math.max(face.b0, face.b1) * INV_SHAPE_UNIT;
            }
            case Z -> {
                minX = Math.min(face.a0, face.a1) * INV_SHAPE_UNIT;
                maxX = Math.max(face.a0, face.a1) * INV_SHAPE_UNIT;
                minY = Math.min(face.b0, face.b1) * INV_SHAPE_UNIT;
                maxY = Math.max(face.b0, face.b1) * INV_SHAPE_UNIT;
                minZ = face.plane * INV_SHAPE_UNIT;
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

    private static boolean edgeIntersectsRoom(EdgeKey edge, RoomBounds room) {
        double minX = Math.min(edge.x1, edge.x2) * INV_SHAPE_UNIT;
        double minY = Math.min(edge.y1, edge.y2) * INV_SHAPE_UNIT;
        double minZ = Math.min(edge.z1, edge.z2) * INV_SHAPE_UNIT;
        double maxX = Math.max(edge.x1, edge.x2) * INV_SHAPE_UNIT;
        double maxY = Math.max(edge.y1, edge.y2) * INV_SHAPE_UNIT;
        double maxZ = Math.max(edge.z1, edge.z2) * INV_SHAPE_UNIT;

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

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
        GL11.glDepthFunc(GL11.GL_LESS);
        RenderSystem.colorMask(false, false, false, false);

        BufferBuilder mask = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawRoomStencilMask(mask, matrix, delta, activity, false);
        drawRoomStencilMask(mask, matrix, delta, activity, true);
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
        // Use the active room mask only as a depth clip. This prevents inactive
        // geometry from drawing through active room cutouts, but it does not paint
        // the active stencil black into the color buffer.
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
        GL11.glDepthFunc(GL11.GL_LESS);
        RenderSystem.colorMask(false, false, false, false);

        BufferBuilder mask = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        drawRoomStencilMask(mask, matrix, delta, activity, true);
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
                                     float delta,
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
            String roomKey = roomKeys.get(i);
            double x0 = room.x0 * INV_SHAPE_UNIT;
            double y0 = room.y0 * INV_SHAPE_UNIT;
            double z0 = room.z0 * INV_SHAPE_UNIT;
            double x1 = room.x1 * INV_SHAPE_UNIT;
            double y1 = room.y1 * INV_SHAPE_UNIT;
            double z1 = room.z1 * INV_SHAPE_UNIT;

            // -X
            drawRoomFaceMask(buffer, matrix, roomKey, Axis.X, x0, y0, z0, y1, z1, delta);
            // +X
            drawRoomFaceMask(buffer, matrix, roomKey, Axis.X, x1, y0, z0, y1, z1, delta);
            // -Y
            drawRoomFaceMask(buffer, matrix, roomKey, Axis.Y, y0, x0, z0, x1, z1, delta);
            // +Y
            drawRoomFaceMask(buffer, matrix, roomKey, Axis.Y, y1, x0, z0, x1, z1, delta);
            // -Z
            drawRoomFaceMask(buffer, matrix, roomKey, Axis.Z, z0, x0, y0, x1, y1, delta);
            // +Z
            drawRoomFaceMask(buffer, matrix, roomKey, Axis.Z, z1, x0, y0, x1, y1, delta);
        }
    }

    private void drawRoomFaceMask(VertexConsumer buffer, Matrix4f matrix,
                                  String roomKey,
                                  Axis axis,
                                  double fixed,
                                  double a0, double b0,
                                  double a1, double b1,
                                  float delta) {
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

                PointReveal reveal = pointRevealInRoom(roomKey, cx, cy, cz);
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
        private final String roomKey;
        private final Axis axis;
        private final int plane;
        private final int a0;
        private final int b0;
        private final int a1;
        private final int b1;
        private final int normalSign;

        private float reveal = 0.0f;
        private float lastReveal = 0.0f;

        private MapFace(Face face, String roomKey) {
            this.roomKey = roomKey;
            this.axis = face.axis;
            this.plane = face.plane;
            this.a0 = face.a0;
            this.b0 = face.b0;
            this.a1 = face.a1;
            this.b1 = face.b1;
            this.normalSign = face.normalSign;
        }

        private String roomKey() {
            return roomKey;
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
        private final String roomKey;
        private final int x1;
        private final int y1;
        private final int z1;
        private final int x2;
        private final int y2;
        private final int z2;

        private float reveal = 0.0f;
        private float lastReveal = 0.0f;

        private MapLine(EdgeKey key, String roomKey) {
            this.roomKey = roomKey;
            this.x1 = key.x1;
            this.y1 = key.y1;
            this.z1 = key.z1;
            this.x2 = key.x2;
            this.y2 = key.y2;
            this.z2 = key.z2;
        }

        private String roomKey() {
            return roomKey;
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

    private record LocalCell(int x, int y, int z) {
    }

    private record RoomCell(String roomKey, int x, int y, int z) {
    }

    private static final class PipeLink {
        private final BlockPos start;
        private final BlockPos end;
        private final int startRoomIndex;
        private final int endRoomIndex;
        private final Direction startDirection;
        private final Direction endDirection;

        private RoomCell startRevealCell;
        private RoomCell endRevealCell;

        private float revealA = 0.0f;
        private float lastRevealA = 0.0f;
        private float revealB = 0.0f;
        private float lastRevealB = 0.0f;
        private int startRevealA = -1;
        private int startRevealB = -1;
        private float direction = 0.0f;

        private PipeLink(BlockPos start,
                         BlockPos end,
                         int startRoomIndex,
                         int endRoomIndex,
                         Direction startDirection,
                         Direction endDirection) {
            this.start = start;
            this.end = end;
            this.startRoomIndex = startRoomIndex;
            this.endRoomIndex = endRoomIndex;
            this.startDirection = startDirection;
            this.endDirection = endDirection;
        }

        private BlockPos start() {
            return start;
        }

        private BlockPos end() {
            return end;
        }

        private int startRoomIndex() {
            return startRoomIndex;
        }

        private int endRoomIndex() {
            return endRoomIndex;
        }

        private Direction startDirection() {
            return startDirection;
        }

        private Direction endDirection() {
            return endDirection;
        }

        private void setRevealCells(RoomCell startCell, RoomCell endCell) {
            this.startRevealCell = startCell;
            this.endRevealCell = endCell;
        }

        private void resetReveal() {
            revealA = 0.0f;
            lastRevealA = 0.0f;
            revealB = 0.0f;
            lastRevealB = 0.0f;
            startRevealA = -1;
            startRevealB = -1;
            direction = 0.0f;
        }
    }

    private record PipeLinkKey(int ax, int ay, int az, int bx, int by, int bz) {
    }

    private record CurvePoint(double x, double y, double z, double t) {
    }

    private record ColorSample(float r, float g, float b, float a) {
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