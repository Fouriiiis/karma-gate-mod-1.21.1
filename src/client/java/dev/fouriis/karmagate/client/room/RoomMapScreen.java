package dev.fouriis.karmagate.client.room;

import com.mojang.blaze3d.systems.RenderSystem;
import net.brickcraftdream.librainworldmc.client.render.capture.FramebufferCaptureHelper;
import net.brickcraftdream.librainworldmc.client.render.capture.FramebufferRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.brickcraftdream.librainworldmc.client.util.TextureUtils;
import dev.fouriis.karmagate.room.RoomGeometry;
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

    // Full-effects defaults. The previous FPS-only build disabled these; this build
    // keeps the screen warp/backdrop/depth treatment and gets its speed from reducing
    // the amount of geometry rendered each frame.
    private static final boolean ENABLE_SCREEN_WARP = true;
    private static final boolean ENABLE_ROOM_BACKDROP_CUTOUT = true;
    private static final boolean ENABLE_ACTIVE_ROOM_DEPTH_CLIP = true;
    private static final boolean ENABLE_INACTIVE_GEOMETRY = true;
    private static final boolean ENABLE_ACTIVE_SOLID_FILLS = true;
    private static final boolean ENABLE_INACTIVE_SOLID_FILLS = true;

    // Use the room bounds as the mask volume, instead of only the optimized solid
    // surface mesh. This restores the original backdrop/depth cutout behavior for
    // the whole registered room area, even after the geometry optimizer removes
    // interior/empty-space faces.
    private static final boolean ENABLE_ROOM_VOLUME_STENCIL = true;

    // Draw a subtle shell for the registered empty room volume so carved-out/open
    // areas are still readable after unnecessary internal geometry is removed.
    private static final boolean ENABLE_ROOM_VOLUME_EMPTY_FILL = true;

    private static final float ROOM_VOLUME_FILL_R = 0.03f;
    private static final float ROOM_VOLUME_FILL_G = 0.36f;
    private static final float ROOM_VOLUME_FILL_B = 0.42f;
    private static final float ROOM_VOLUME_FILL_A = 0.18f;

    private static final float INACTIVE_ROOM_VOLUME_FILL_R = 0.03f;
    private static final float INACTIVE_ROOM_VOLUME_FILL_G = 0.14f;
    private static final float INACTIVE_ROOM_VOLUME_FILL_B = 0.16f;
    private static final float INACTIVE_ROOM_VOLUME_FILL_A = 0.10f;

    // Keep the expensive visual effects, but aggressively shrink the source mesh.
    // This turns many tiny adjacent face/line fragments into larger rectangles and
    // long outline segments before the render loop ever sees them.
    private static final boolean ENABLE_TOPOLOGY_MESH_OPTIMIZATION = true;
    private static final boolean REBUILD_LINES_FROM_OPTIMIZED_FACES = true;
    private static final boolean CULL_UNATTACHED_LINES = true;

    // Remove single-plane interior hole/seam outlines. True solid creases have two
    // perpendicular face planes touching the line; pure flat empty-space boundaries do not.
    private static final boolean CULL_FLAT_INTERIOR_BOUNDARY_LINES = true;

    private static final float SCREEN_CULL_MARGIN = 48.0f;

    private static final float INACTIVE_PIPE_GRAY = MAP_R * 0.2126f + MAP_G * 0.7152f + MAP_B * 0.0722f;
    private static final float INACTIVE_PIPE_R_FAST = INACTIVE_PIPE_GRAY + (INACTIVE_MAP_R - INACTIVE_PIPE_GRAY) * 0.62f;
    private static final float INACTIVE_PIPE_G_FAST = INACTIVE_PIPE_GRAY + (INACTIVE_MAP_G - INACTIVE_PIPE_GRAY) * 0.62f;
    private static final float INACTIVE_PIPE_B_FAST = INACTIVE_PIPE_GRAY + (INACTIVE_MAP_B - INACTIVE_PIPE_GRAY) * 0.62f;
    private static final float INACTIVE_PIPE_A_FAST = 0.48f;

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

    // Reused render objects/caches. The old render path allocated a framebuffer renderer,
    // projected records, pipe distance graphs and trig state every frame. That is extremely
    // visible while dragging the map angle. Keep the heavy state hot and update only numbers.
    private FramebufferRenderer roomMapFramebuffer;
    private final RenderFrameCache renderCache = new RenderFrameCache();
    private final RoomActivity roomActivityCache = new RoomActivity();
    private boolean[] activeRoomCache = new boolean[0];
    private int[] cachedRoomDistances = new int[0];
    private int cachedRoomDistancesStartRoom = Integer.MIN_VALUE;
    private int cachedRoomDistancesRoomCount = -1;

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

        handleKeyboardPan(client);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (ENABLE_SCREEN_WARP) {
            renderWithScreenWarp(context, delta);
        } else {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            renderMapScene(context, delta);
        }

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        drawHudHints(context);
    }

    private void renderWithScreenWarp(DrawContext context, float delta) {
        FramebufferRenderer fbRenderer = roomMapFramebuffer();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        fbRenderer.render(drawContext -> renderMapScene(drawContext, delta));
        fbRenderer.finish(IS_SYSTEM_MAC);

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        BufferBuilder overlayBuffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        overlayBuffer.vertex(matrix, 0, 0, 0).color(0xffffffaa);
        overlayBuffer.vertex(matrix, 0, this.height, 0).color(0xffffffff);
        overlayBuffer.vertex(matrix, this.width, this.height, 0).color(0xffffffff);
        overlayBuffer.vertex(matrix, this.width, 0, 0).color(0xffffffff);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        CoreShaderRenderer.bindShader$SceneFisheye(Identifier.of("librainworldmc", "framebuffer/room_map"));
        BufferRenderer.drawWithGlobalProgram(overlayBuffer.end());
        matrices.pop();
    }

    private void renderMapScene(DrawContext drawContext, float delta) {
        if (!hasRooms) {
            drawContext.drawCenteredTextWithShadow(textRenderer, Text.literal("No rooms"), this.width / 2, this.height / 2 - 4, 0xFFFFFFFF);
            return;
        }

        MatrixStack matrices = drawContext.getMatrices();
        matrices.push();
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        updateRenderFrameCache();
        RoomActivity activity = computeRoomActivity();
        refreshProjectionCache();
        int[] pipeDistances = computeCurrentRoomDistances();

        boolean drawSimpleBackdropInGeometryBuffer = !ENABLE_ROOM_BACKDROP_CUTOUT && !ENABLE_ACTIVE_ROOM_DEPTH_CLIP;
        if (ENABLE_ROOM_BACKDROP_CUTOUT) {
            drawBackdropCutout(matrix, delta, activity);
        } else if (!drawSimpleBackdropInGeometryBuffer) {
            drawBackdropOnly(matrix);
        }

        if (ENABLE_INACTIVE_GEOMETRY) {
            if (ENABLE_ACTIVE_ROOM_DEPTH_CLIP) {
                beginActiveRoomDepthClip(matrix, delta, activity);
            }

            BufferBuilder inactiveBuffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            if (drawSimpleBackdropInGeometryBuffer) {
                drawScreenQuad(inactiveBuffer, matrix, 0.0f, 0.0f, this.width, this.height,
                    BACKDROP_R, BACKDROP_G, BACKDROP_B, BACKDROP_A);
                drawSimpleBackdropInGeometryBuffer = false;
            }
            if (ENABLE_ROOM_VOLUME_EMPTY_FILL) {
                drawRoomVolumeEmptyFill(inactiveBuffer, matrix, activity, false);
            }
            if (ENABLE_INACTIVE_SOLID_FILLS) {
                drawSolidBlockFill(inactiveBuffer, matrix, delta, activity, false);
            }
            drawRevealWave(inactiveBuffer, matrix, delta);
            drawRoomGeometry(inactiveBuffer, matrix, delta, activity, false);
            drawPipeLinks(inactiveBuffer, matrix, delta, activity, false, pipeDistances);
            BuiltBuffer inactiveBuiltBuffer = inactiveBuffer.endNullable();
            if (inactiveBuiltBuffer != null) {
                BufferRenderer.drawWithGlobalProgram(inactiveBuiltBuffer);
            }

            if (ENABLE_ACTIVE_ROOM_DEPTH_CLIP) {
                endActiveRoomDepthClip();
            }
        }

        BufferBuilder activeBuffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        if (drawSimpleBackdropInGeometryBuffer) {
            drawScreenQuad(activeBuffer, matrix, 0.0f, 0.0f, this.width, this.height,
                BACKDROP_R, BACKDROP_G, BACKDROP_B, BACKDROP_A);
        }
        if (ENABLE_ROOM_VOLUME_EMPTY_FILL) {
            drawRoomVolumeEmptyFill(activeBuffer, matrix, activity, true);
        }
        if (ENABLE_ACTIVE_SOLID_FILLS) {
            drawSolidBlockFill(activeBuffer, matrix, delta, activity, true);
        }
        drawRoomGeometry(activeBuffer, matrix, delta, activity, true);
        drawPipeLinks(activeBuffer, matrix, delta, activity, true, pipeDistances);
        drawPlayerMarker(activeBuffer, matrix, delta);
        BuiltBuffer activeBuiltBuffer = activeBuffer.endNullable();
        if (activeBuiltBuffer != null) {
            BufferRenderer.drawWithGlobalProgram(activeBuiltBuffer);
        }

        matrices.pop();
    }

    private FramebufferRenderer roomMapFramebuffer() {
        if (roomMapFramebuffer == null) {
            roomMapFramebuffer = new FramebufferRenderer("room_map");
        }
        return roomMapFramebuffer;
    }

    private void updateRenderFrameCache() {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        renderCache.yawCos = Math.cos(yawRad);
        renderCache.yawSin = Math.sin(yawRad);
        renderCache.pitchCos = Math.cos(pitchRad);
        renderCache.pitchSin = Math.sin(pitchRad);
        renderCache.screenScale = scale * zoom;
        renderCache.centerX = focusX - panX;
        renderCache.centerY = focusY - panY;
        renderCache.centerZ = focusZ - panZ;
        renderCache.screenCenterX = this.width * 0.5;
        renderCache.screenCenterY = this.height * 0.55;

        // Existing camera-facing test reduced to three axis formulas.
        renderCache.faceXP = (-renderCache.yawSin) * renderCache.pitchCos;
        renderCache.faceXN = -renderCache.faceXP;
        renderCache.faceYP = renderCache.pitchSin;
        renderCache.faceYN = -renderCache.pitchSin;
        renderCache.faceZP = renderCache.yawCos * renderCache.pitchCos;
        renderCache.faceZN = -renderCache.faceZP;
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
        float lineR = activePass ? MAP_R : INACTIVE_MAP_R;
        float lineG = activePass ? MAP_G : INACTIVE_MAP_G;
        float lineB = activePass ? MAP_B : INACTIVE_MAP_B;
        float lineA = activePass ? 0.96f : 0.72f;

        for (MapLine line : roomLines) {
            if (!line.visibleOnScreen || !line.hasFacingFace || isLineActive(line, activity) != activePass) {
                continue;
            }

            drawScreenLine(buffer, matrix,
                line.projectedX1, line.projectedY1,
                line.projectedX2, line.projectedY2,
                lineR, lineG, lineB, lineA);
        }
    }

    private void drawPipeLinks(VertexConsumer buffer, Matrix4f matrix, float delta, RoomActivity activity, boolean activePass, int[] distances) {
        if (pipeLinks.isEmpty()) {
            return;
        }

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

            boolean drawingStartIsOriginalStart = start.equals(link.start());
            float aReveal = drawingStartIsOriginalStart
                ? MathHelper.lerp(delta, link.lastRevealA, link.revealA)
                : MathHelper.lerp(delta, link.lastRevealB, link.revealB);
            float bReveal = drawingStartIsOriginalStart
                ? MathHelper.lerp(delta, link.lastRevealB, link.revealB)
                : MathHelper.lerp(delta, link.lastRevealA, link.revealA);

            PipeCurve curve = drawingStartIsOriginalStart ? link.forwardCurve : link.reverseCurve;
            drawDashedPipeCurve(buffer, matrix, curve, startActive, endActive, aReveal, bReveal, time);
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

    private void rebuildPipeCurves() {
        for (PipeLink link : pipeLinks) {
            link.forwardCurve = buildPipeCurve(link.start(), link.end(), link.startDirection(), link.endDirection());
            link.reverseCurve = buildPipeCurve(link.end(), link.start(), link.endDirection(), link.startDirection());
        }
    }

    private PipeCurve buildPipeCurve(BlockPos start, BlockPos end, Direction startDir, Direction endDir) {
        double x1 = start.getX() + 0.5;
        double y1 = start.getY() + 0.5;
        double z1 = start.getZ() + 0.5;
        double x2 = end.getX() + 0.5;
        double y2 = end.getY() + 0.5;
        double z2 = end.getZ() + 0.5;

        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0e-6) {
            return null;
        }

        double[] startVec = resolveDirectionVector(startDir, dx, dy, dz, length, true);
        double[] endVec = resolveDirectionVector(endDir, dx, dy, dz, length, false);

        double handle = Math.min(6.0, Math.max(1.25, length * 0.35));
        double c1x = x1 + startVec[0] * handle;
        double c1y = y1 + startVec[1] * handle;
        double c1z = z1 + startVec[2] * handle;
        double c2x = x2 - endVec[0] * handle;
        double c2y = y2 - endVec[1] * handle;
        double c2z = z2 - endVec[2] * handle;

        int sampleCount = clampInt((int) Math.ceil(length * 2.0), 12, 40);
        PipeCurve curve = new PipeCurve(sampleCount);

        double lastX = x1;
        double lastY = y1;
        double lastZ = z1;
        curve.xs[0] = lastX;
        curve.ys[0] = lastY;
        curve.zs[0] = lastZ;
        curve.lengths[0] = 0.0;

        for (int i = 1; i <= sampleCount; i++) {
            double t = (double) i / sampleCount;
            double ix = cubicBezier(x1, c1x, c2x, x2, t);
            double iy = cubicBezier(y1, c1y, c2y, y2, t);
            double iz = cubicBezier(z1, c1z, c2z, z2, t);
            curve.xs[i] = ix;
            curve.ys[i] = iy;
            curve.zs[i] = iz;
            double segDx = ix - lastX;
            double segDy = iy - lastY;
            double segDz = iz - lastZ;
            curve.lengths[i] = curve.lengths[i - 1] + Math.sqrt(segDx * segDx + segDy * segDy + segDz * segDz);
            lastX = ix;
            lastY = iy;
            lastZ = iz;
        }

        curve.totalLength = curve.lengths[sampleCount];
        return curve.totalLength <= 1.0e-6 ? null : curve;
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
            face.lastReveal = 1.0f;
            face.reveal = 1.0f;
        }
        for (MapLine line : roomLines) {
            line.lastReveal = 1.0f;
            line.reveal = 1.0f;
        }
    }

    private void updatePipeReveals() {
        for (PipeLink link : pipeLinks) {
            link.lastRevealA = 1.0f;
            link.lastRevealB = 1.0f;
            link.revealA = 1.0f;
            link.revealB = 1.0f;
            link.startRevealA = 0;
            link.startRevealB = 0;
            link.direction = 0.0f;
        }
    }

    private void refreshProjectionCache() {
        for (MapFace face : roomFaces) {
            face.facingCamera = isFaceFacingCameraFast(face);

            projectToScratch(face.wx1, face.wy1, face.wz1);
            face.projectedX1 = renderCache.projectedX;
            face.projectedY1 = renderCache.projectedY;
            projectToScratch(face.wx2, face.wy2, face.wz2);
            face.projectedX2 = renderCache.projectedX;
            face.projectedY2 = renderCache.projectedY;
            projectToScratch(face.wx3, face.wy3, face.wz3);
            face.projectedX3 = renderCache.projectedX;
            face.projectedY3 = renderCache.projectedY;
            projectToScratch(face.wx4, face.wy4, face.wz4);
            face.projectedX4 = renderCache.projectedX;
            face.projectedY4 = renderCache.projectedY;
            face.visibleOnScreen = projectedQuadOnScreen(
                face.projectedX1, face.projectedY1,
                face.projectedX2, face.projectedY2,
                face.projectedX3, face.projectedY3,
                face.projectedX4, face.projectedY4);
        }
        for (MapLine line : roomLines) {
            projectToScratch(line.wx1, line.wy1, line.wz1);
            line.projectedX1 = renderCache.projectedX;
            line.projectedY1 = renderCache.projectedY;
            projectToScratch(line.wx2, line.wy2, line.wz2);
            line.projectedX2 = renderCache.projectedX;
            line.projectedY2 = renderCache.projectedY;
            line.visibleOnScreen = projectedSegmentOnScreen(line.projectedX1, line.projectedY1, line.projectedX2, line.projectedY2);
            line.hasFacingFace = false;
            for (MapFace face : line.adjacentFaces) {
                if (face.facingCamera) {
                    line.hasFacingFace = true;
                    break;
                }
            }
        }
    }

    private boolean isFaceFacingCameraFast(MapFace face) {
        float viewZ = switch (face.axis) {
            case X -> face.normalSign > 0 ? (float) renderCache.faceXP : (float) renderCache.faceXN;
            case Y -> face.normalSign > 0 ? (float) renderCache.faceYP : (float) renderCache.faceYN;
            case Z -> face.normalSign > 0 ? (float) renderCache.faceZP : (float) renderCache.faceZN;
        };
        return viewZ > 0.001f;
    }

    private void projectToScratch(double worldX, double worldY, double worldZ) {
        double x = worldX - renderCache.centerX;
        double y = worldY - renderCache.centerY;
        double z = worldZ - renderCache.centerZ;
        double yawX = x * renderCache.yawCos + z * renderCache.yawSin;
        double yawZ = -x * renderCache.yawSin + z * renderCache.yawCos;
        double pitchY = y * renderCache.pitchCos - yawZ * renderCache.pitchSin;
        renderCache.projectedX = (float) (renderCache.screenCenterX + yawX * renderCache.screenScale);
        renderCache.projectedY = (float) (renderCache.screenCenterY - pitchY * renderCache.screenScale);
    }

    private float projectX(double worldX, double worldY, double worldZ) {
        double x = worldX - renderCache.centerX;
        double z = worldZ - renderCache.centerZ;
        double yawX = x * renderCache.yawCos + z * renderCache.yawSin;
        return (float) (renderCache.screenCenterX + yawX * renderCache.screenScale);
    }

    private float projectY(double worldX, double worldY, double worldZ) {
        double x = worldX - renderCache.centerX;
        double y = worldY - renderCache.centerY;
        double z = worldZ - renderCache.centerZ;
        double yawZ = -x * renderCache.yawSin + z * renderCache.yawCos;
        double pitchY = y * renderCache.pitchCos - yawZ * renderCache.pitchSin;
        return (float) (renderCache.screenCenterY - pitchY * renderCache.screenScale);
    }

    private boolean projectedSegmentOnScreen(float x1, float y1, float x2, float y2) {
        return Math.max(x1, x2) >= -SCREEN_CULL_MARGIN
            && Math.min(x1, x2) <= this.width + SCREEN_CULL_MARGIN
            && Math.max(y1, y2) >= -SCREEN_CULL_MARGIN
            && Math.min(y1, y2) <= this.height + SCREEN_CULL_MARGIN;
    }

    private boolean projectedQuadOnScreen(float x1, float y1,
                                          float x2, float y2,
                                          float x3, float y3,
                                          float x4, float y4) {
        float minX = Math.min(Math.min(x1, x2), Math.min(x3, x4));
        float maxX = Math.max(Math.max(x1, x2), Math.max(x3, x4));
        float minY = Math.min(Math.min(y1, y2), Math.min(y3, y4));
        float maxY = Math.max(Math.max(y1, y2), Math.max(y3, y4));
        return maxX >= -SCREEN_CULL_MARGIN
            && minX <= this.width + SCREEN_CULL_MARGIN
            && maxY >= -SCREEN_CULL_MARGIN
            && minY <= this.height + SCREEN_CULL_MARGIN;
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

    private int[] computeCurrentRoomDistances() {
        if (pipeLinks.isEmpty()) {
            return cachedRoomDistances;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        List<RoomClientState.RoomEntry> rooms = RoomClientState.getRooms();
        int playerRoomIndex = -1;
        if (client.player != null) {
            playerRoomIndex = findRoomIndex(rooms, client.player.getBlockPos());
        }

        if (cachedRoomDistancesRoomCount != rooms.size() || cachedRoomDistancesStartRoom != playerRoomIndex) {
            cachedRoomDistances = computeRoomDistances(rooms.size(), playerRoomIndex);
            cachedRoomDistancesRoomCount = rooms.size();
            cachedRoomDistancesStartRoom = playerRoomIndex;
        }
        return cachedRoomDistances;
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

    private void drawDashedPipeCurve(VertexConsumer buffer,
                                      Matrix4f matrix,
                                      PipeCurve curve,
                                      boolean startActive,
                                      boolean endActive,
                                      float aReveal,
                                      float bReveal,
                                      float time) {
        if (curve == null || curve.totalLength <= 1.0e-6) {
            return;
        }

        float startR = startActive ? MAP_R : INACTIVE_PIPE_R_FAST;
        float startG = startActive ? MAP_G : INACTIVE_PIPE_G_FAST;
        float startB = startActive ? MAP_B : INACTIVE_PIPE_B_FAST;
        float startA = startActive ? 1.0f : INACTIVE_PIPE_A_FAST;
        float endR = endActive ? MAP_R : INACTIVE_PIPE_R_FAST;
        float endG = endActive ? MAP_G : INACTIVE_PIPE_G_FAST;
        float endB = endActive ? MAP_B : INACTIVE_PIPE_B_FAST;
        float endA = endActive ? 1.0f : INACTIVE_PIPE_A_FAST;

        double step = PIPE_DASH_LENGTH + PIPE_DASH_GAP;
        double offset = (time * PIPE_DASH_SPEED) % step;
        MutableCurvePoint p0 = curve.p0;
        MutableCurvePoint p1 = curve.p1;

        for (double dashStart = offset; dashStart < curve.totalLength; dashStart += step) {
            double dashEnd = Math.min(dashStart + PIPE_DASH_LENGTH, curve.totalLength);
            if (dashEnd <= dashStart) {
                continue;
            }

            sampleCurvePointInto(curve, dashStart, p0);
            sampleCurvePointInto(curve, dashEnd, p1);

            float t0 = (float) p0.t;
            float t1 = (float) p1.t;
            float reveal0 = lerp(aReveal, bReveal, t0);
            float reveal1 = lerp(aReveal, bReveal, t1);
            float a0 = lerp(startA, endA, t0) * PIPE_LINK_ALPHA * reveal0;
            float a1 = lerp(startA, endA, t1) * PIPE_LINK_ALPHA * reveal1;
            if (a0 <= 0.001f && a1 <= 0.001f) {
                continue;
            }

            drawRawLineGradient(
                buffer,
                matrix,
                p0.x, p0.y, p0.z,
                p1.x, p1.y, p1.z,
                lerp(startR, endR, t0), lerp(startG, endG, t0), lerp(startB, endB, t0), a0,
                lerp(startR, endR, t1), lerp(startG, endG, t1), lerp(startB, endB, t1), a1
            );
        }
    }

    private static void sampleCurvePointInto(PipeCurve curve, double distance, MutableCurvePoint out) {
        int count = curve.sampleCount;
        if (distance <= 0.0) {
            out.set(curve.xs[0], curve.ys[0], curve.zs[0], 0.0);
            return;
        }
        if (distance >= curve.lengths[count]) {
            out.set(curve.xs[count], curve.ys[count], curve.zs[count], 1.0);
            return;
        }

        int low = 0;
        int high = count - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (curve.lengths[mid + 1] < distance) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        int segment = low;
        double segStart = curve.lengths[segment];
        double segEnd = curve.lengths[segment + 1];
        double segLength = Math.max(1.0e-9, segEnd - segStart);
        double localT = (distance - segStart) / segLength;
        double x = lerpDouble(curve.xs[segment], curve.xs[segment + 1], localT);
        double y = lerpDouble(curve.ys[segment], curve.ys[segment + 1], localT);
        double z = lerpDouble(curve.zs[segment], curve.zs[segment + 1], localT);
        double t = (segment + localT) / count;
        out.set(x, y, z, t);
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

            RoomGeometry geometry = room.geometry();
            if (geometry == null || geometry.isEmpty()) {
                continue;
            }

            RoomBounds currentRoomBounds = roomBounds.get(roomBounds.size() - 1);
            List<Face> optimizedFaces = collectOptimizedRoomFaces(geometry, currentRoomBounds);
            for (Face face : optimizedFaces) {
                MapFace mapFace = new MapFace(face, key, roomIndex);
                roomFaces.add(mapFace);
                facesByPlane.computeIfAbsent(new PlaneKey(mapFace.axis, mapFace.plane), ignored -> new ArrayList<>()).add(mapFace);
            }

            List<EdgeKey> optimizedLines = collectOptimizedRoomLines(geometry, optimizedFaces, currentRoomBounds);
            for (EdgeKey line : optimizedLines) {
                roomLines.add(new MapLine(line, key, roomIndex));
            }

            for (RoomGeometry.PipeLinkData link : geometry.pipeLinks()) {
                BlockPos start = new BlockPos(link.startX(), link.startY(), link.startZ());
                BlockPos end = new BlockPos(link.endX(), link.endY(), link.endZ());
                int startRoomIndex = findRoomIndex(rooms, start);
                int endRoomIndex = findRoomIndex(rooms, end);
                PipeLinkKey linkKey = pipeLinkKey(start, end);
                uniquePipeLinks.putIfAbsent(
                    linkKey,
                    new PipeLink(
                        start,
                        end,
                        startRoomIndex,
                        endRoomIndex,
                        parseDirection(link.startDirection()),
                        parseDirection(link.endDirection())
                    )
                );
            }
        }

        rebuildLineFaceAdjacency();
        if (CULL_UNATTACHED_LINES) {
            removeLinesWithoutAdjacentFaces();
        }
        pipeLinks.addAll(uniquePipeLinks.values());
        rebuildPipeCurves();
        hasRooms = !roomLines.isEmpty();
        assignPipeRevealCells();
        cachedRoomDistances = new int[0];
        cachedRoomDistancesStartRoom = Integer.MIN_VALUE;
        cachedRoomDistancesRoomCount = -1;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            revealCenterX = client.player.getX();
            revealCenterY = client.player.getY() + 0.9;
            revealCenterZ = client.player.getZ();

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

        float target = (float) Math.min(this.width, this.height) * 0.46f;
        scale = target / (float) maxDim;
        scale = MathHelper.clamp(scale, 2.5f, 34.0f);
    }

    private static List<Face> collectOptimizedRoomFaces(RoomGeometry geometry, RoomBounds roomBounds) {
        List<Face> rawFaces = new ArrayList<>();
        for (RoomGeometry.FaceData face : geometry.faces()) {
            Face normalized = normalizeFace(new Face(
                Axis.valueOf(face.axis()),
                face.plane(),
                face.a0(),
                face.b0(),
                face.a1(),
                face.b1(),
                face.normalSign()
            ));

            if (faceHasArea(normalized) && faceIntersectsRoom(normalized, roomBounds)) {
                rawFaces.add(normalized);
            }
        }

        if (!ENABLE_TOPOLOGY_MESH_OPTIMIZATION) {
            return rawFaces;
        }
        return mergeCoplanarFaces(rawFaces);
    }

    private static List<EdgeKey> collectOptimizedRoomLines(RoomGeometry geometry, List<Face> optimizedFaces, RoomBounds roomBounds) {
        List<EdgeKey> edges;
        if (ENABLE_TOPOLOGY_MESH_OPTIMIZATION && REBUILD_LINES_FROM_OPTIMIZED_FACES && !optimizedFaces.isEmpty()) {
            edges = buildMergedSurfaceEdges(optimizedFaces, roomBounds);
            List<RoomBounds> singleRoomBounds = new ArrayList<>(1);
            singleRoomBounds.add(roomBounds);
            addMissingRoomShellAxes(edges, singleRoomBounds);
        } else {
            edges = collectRawRoomLines(geometry, roomBounds);
        }

        if (edges.isEmpty()) {
            edges = collectRawRoomLines(geometry, roomBounds);
        }

        return mergeCollinearEdges(edges);
    }

    private static List<EdgeKey> collectRawRoomLines(RoomGeometry geometry, RoomBounds roomBounds) {
        List<EdgeKey> edges = new ArrayList<>();
        for (RoomGeometry.LineData line : geometry.lines()) {
            EdgeKey edge = edgeKey(line.x1(), line.y1(), line.z1(), line.x2(), line.y2(), line.z2());
            if (edgeIntersectsRoom(edge, roomBounds)) {
                edges.add(edge);
            }
        }
        return edges;
    }

    private static Face normalizeFace(Face face) {
        return new Face(
            face.axis(),
            face.plane(),
            Math.min(face.a0(), face.a1()),
            Math.min(face.b0(), face.b1()),
            Math.max(face.a0(), face.a1()),
            Math.max(face.b0(), face.b1()),
            face.normalSign()
        );
    }

    private static boolean faceHasArea(Face face) {
        return face.a1() > face.a0() && face.b1() > face.b0();
    }

    private static List<Face> mergeCoplanarFaces(List<Face> faces) {
        Map<FaceMergeKey, List<Face>> byPlane = new HashMap<>();
        for (Face face : faces) {
            Face normalized = normalizeFace(face);
            if (!faceHasArea(normalized)) {
                continue;
            }
            byPlane.computeIfAbsent(
                new FaceMergeKey(normalized.axis(), normalized.plane(), normalized.normalSign()),
                ignored -> new ArrayList<>()
            ).add(normalized);
        }

        List<Face> merged = new ArrayList<>();
        for (Map.Entry<FaceMergeKey, List<Face>> entry : byPlane.entrySet()) {
            merged.addAll(mergeFacePlane(entry.getKey(), entry.getValue()));
        }
        return merged;
    }

    private static List<Face> mergeFacePlane(FaceMergeKey key, List<Face> faces) {
        if (faces.isEmpty()) {
            return new ArrayList<>();
        }
        if (faces.size() == 1) {
            List<Face> single = new ArrayList<>(1);
            single.add(faces.get(0));
            return single;
        }

        List<Integer> bBounds = new ArrayList<>(faces.size() * 2);
        for (Face face : faces) {
            bBounds.add(face.b0());
            bBounds.add(face.b1());
        }
        bBounds.sort(Integer::compareTo);

        List<Integer> uniqueBBounds = new ArrayList<>(bBounds.size());
        int last = Integer.MIN_VALUE;
        boolean hasLast = false;
        for (int value : bBounds) {
            if (!hasLast || value != last) {
                uniqueBBounds.add(value);
                last = value;
                hasLast = true;
            }
        }

        List<MutableFaceRect> rects = new ArrayList<>();
        Map<IntRange, MutableFaceRect> active = new HashMap<>();

        for (int i = 0; i < uniqueBBounds.size() - 1; i++) {
            int stripB0 = uniqueBBounds.get(i);
            int stripB1 = uniqueBBounds.get(i + 1);
            if (stripB1 <= stripB0) {
                continue;
            }

            List<IntRange> intervals = new ArrayList<>();
            for (Face face : faces) {
                if (face.b0() <= stripB0 && face.b1() >= stripB1) {
                    intervals.add(new IntRange(face.a0(), face.a1()));
                }
            }

            intervals = mergeIntervals(intervals);
            Map<IntRange, MutableFaceRect> nextActive = new HashMap<>();
            for (IntRange interval : intervals) {
                if (interval.end() <= interval.start()) {
                    continue;
                }

                MutableFaceRect rect = active.get(interval);
                if (rect != null && rect.b1 == stripB0) {
                    rect.b1 = stripB1;
                } else {
                    rect = new MutableFaceRect(interval.start(), stripB0, interval.end(), stripB1);
                    rects.add(rect);
                }
                nextActive.put(interval, rect);
            }
            active = nextActive;
        }

        List<Face> merged = new ArrayList<>(rects.size());
        for (MutableFaceRect rect : rects) {
            if (rect.a1 > rect.a0 && rect.b1 > rect.b0) {
                merged.add(new Face(key.axis(), key.plane(), rect.a0, rect.b0, rect.a1, rect.b1, key.normalSign()));
            }
        }
        return merged;
    }

    private static List<IntRange> mergeIntervals(List<IntRange> intervals) {
        if (intervals.isEmpty()) {
            return intervals;
        }

        intervals.sort((a, b) -> {
            if (a.start() != b.start()) {
                return Integer.compare(a.start(), b.start());
            }
            return Integer.compare(a.end(), b.end());
        });

        List<IntRange> merged = new ArrayList<>();
        int currentStart = Integer.MIN_VALUE;
        int currentEnd = Integer.MIN_VALUE;
        for (IntRange interval : intervals) {
            if (interval.end() <= interval.start()) {
                continue;
            }

            if (currentStart == Integer.MIN_VALUE) {
                currentStart = interval.start();
                currentEnd = interval.end();
                continue;
            }

            if (interval.start() <= currentEnd) {
                currentEnd = Math.max(currentEnd, interval.end());
            } else {
                merged.add(new IntRange(currentStart, currentEnd));
                currentStart = interval.start();
                currentEnd = interval.end();
            }
        }

        if (currentStart != Integer.MIN_VALUE) {
            merged.add(new IntRange(currentStart, currentEnd));
        }
        return merged;
    }

    private static Direction parseDirection(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Direction.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void resetReveal() {
        revealTicks = 0;
        revealCells.clear();
        revealFrontier.clear();
        revealFadeCells.clear();
        for (MapFace face : roomFaces) {
            face.reveal = 1.0f;
            face.lastReveal = 1.0f;
        }
        for (MapLine line : roomLines) {
            line.reveal = 1.0f;
            line.lastReveal = 1.0f;
        }

        for (PipeLink link : pipeLinks) {
            link.resetReveal();
            link.revealA = 1.0f;
            link.lastRevealA = 1.0f;
            link.revealB = 1.0f;
            link.lastRevealB = 1.0f;
            link.startRevealA = 0;
            link.startRevealB = 0;
            link.direction = 0.0f;
        }
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

    private static List<EdgeKey> buildMergedSurfaceEdges(Iterable<Face> faces, RoomBounds roomBounds) {
        // Build outline/crease lines with interval parity instead of exact EdgeKey parity.
        // The older optimizer only cancelled shared edges when two rectangles had identical
        // endpoints. Real merged room geometry often creates T-junctions: a long rectangle
        // border touches several shorter rectangle borders. Exact-key cancellation leaves the
        // overlapped pieces behind as the "extra" lines seen on large flat walls/floors.
        //
        // This pass groups every face edge by its infinite axis-aligned line, splits that line
        // at every endpoint, then applies the same odd/even visibility rule per split interval.
        // Result: partial overlaps cancel correctly, adjacent collinear fragments become one
        // line, and coplanar seams inside one flat surface disappear before rendering.
        Map<LineKey, List<SegmentUse>> usesByLine = new HashMap<>();

        for (Face face : faces) {
            addFaceSegmentUses(usesByLine, face);
        }

        List<EdgeKey> outlineEdges = new ArrayList<>();
        for (Map.Entry<LineKey, List<SegmentUse>> entry : usesByLine.entrySet()) {
            LineKey line = entry.getKey();
            List<SegmentUse> uses = entry.getValue();
            if (uses.isEmpty()) {
                continue;
            }

            List<Integer> cuts = new ArrayList<>(uses.size() * 2);
            for (SegmentUse use : uses) {
                if (use.end <= use.start) {
                    continue;
                }
                cuts.add(use.start);
                cuts.add(use.end);
            }
            if (cuts.size() < 2) {
                continue;
            }
            cuts.sort(Integer::compareTo);

            int lastCut = Integer.MIN_VALUE;
            List<Integer> uniqueCuts = new ArrayList<>(cuts.size());
            for (int cut : cuts) {
                if (cut != lastCut) {
                    uniqueCuts.add(cut);
                    lastCut = cut;
                }
            }

            int currentStart = Integer.MIN_VALUE;
            int currentEnd = Integer.MIN_VALUE;
            for (int i = 0; i < uniqueCuts.size() - 1; i++) {
                int start = uniqueCuts.get(i);
                int end = uniqueCuts.get(i + 1);
                if (end <= start) {
                    continue;
                }

                boolean visible = splitIntervalIsVisibleOutline(uses, line, start, end, roomBounds);
                if (visible) {
                    if (currentStart == Integer.MIN_VALUE) {
                        currentStart = start;
                        currentEnd = end;
                    } else if (start <= currentEnd) {
                        currentEnd = Math.max(currentEnd, end);
                    } else {
                        addMergedLine(outlineEdges, line, currentStart, currentEnd);
                        currentStart = start;
                        currentEnd = end;
                    }
                } else if (currentStart != Integer.MIN_VALUE) {
                    addMergedLine(outlineEdges, line, currentStart, currentEnd);
                    currentStart = Integer.MIN_VALUE;
                    currentEnd = Integer.MIN_VALUE;
                }
            }

            if (currentStart != Integer.MIN_VALUE) {
                addMergedLine(outlineEdges, line, currentStart, currentEnd);
            }
        }

        return mergeCollinearEdges(outlineEdges);
    }

    private static boolean splitIntervalIsVisibleOutline(List<SegmentUse> uses,
                                                          LineKey line,
                                                          int start,
                                                          int end,
                                                          RoomBounds roomBounds) {
        Map<PlaneKey, Integer> countsByPlane = new HashMap<>();
        for (SegmentUse use : uses) {
            if (use.start <= start && use.end >= end) {
                countsByPlane.merge(use.plane, 1, Integer::sum);
            }
        }

        int oddPlaneCount = 0;
        Axis firstOddAxis = null;
        boolean hasDifferentOddAxes = false;
        for (Map.Entry<PlaneKey, Integer> entry : countsByPlane.entrySet()) {
            if ((entry.getValue() & 1) == 0) {
                continue;
            }
            oddPlaneCount++;
            Axis axis = entry.getKey().axis();
            if (firstOddAxis == null) {
                firstOddAxis = axis;
            } else if (firstOddAxis != axis) {
                hasDifferentOddAxes = true;
            }
        }

        if (oddPlaneCount == 0) {
            return false;
        }
        if (!CULL_FLAT_INTERIOR_BOUNDARY_LINES) {
            return true;
        }

        // Keep true 3D creases/silhouettes. They have two different face planes using
        // the same edge interval. These are the important outlines that communicate shape.
        if (oddPlaneCount > 1 || hasDifferentOddAxes) {
            return true;
        }

        // Keep pure one-face edges only when they lie on the coarse room shell. Interior
        // one-face boundaries are usually holes/empty-space cuts from block detail, and are
        // what creates the distracting extra lines on otherwise flat walls/floors.
        return lineIntervalOnRoomShellEdge(line, roomBounds);
    }

    private static boolean lineIntervalOnRoomShellEdge(LineKey line, RoomBounds roomBounds) {
        return switch (line.axis) {
            case 0 -> isBoundary(line.fixedA, roomBounds.y0, roomBounds.y1)
                && isBoundary(line.fixedB, roomBounds.z0, roomBounds.z1);
            case 1 -> isBoundary(line.fixedA, roomBounds.x0, roomBounds.x1)
                && isBoundary(line.fixedB, roomBounds.z0, roomBounds.z1);
            case 2 -> isBoundary(line.fixedA, roomBounds.x0, roomBounds.x1)
                && isBoundary(line.fixedB, roomBounds.y0, roomBounds.y1);
            default -> false;
        };
    }

    private static void addFaceSegmentUses(Map<LineKey, List<SegmentUse>> usesByLine, Face face) {
        PlaneKey plane = new PlaneKey(face.axis, face.plane);
        switch (face.axis) {
            case X -> {
                int x = face.plane;
                addSegmentUse(usesByLine, plane, x, face.a0, face.b0, x, face.a1, face.b0);
                addSegmentUse(usesByLine, plane, x, face.a1, face.b0, x, face.a1, face.b1);
                addSegmentUse(usesByLine, plane, x, face.a1, face.b1, x, face.a0, face.b1);
                addSegmentUse(usesByLine, plane, x, face.a0, face.b1, x, face.a0, face.b0);
            }
            case Y -> {
                int y = face.plane;
                addSegmentUse(usesByLine, plane, face.a0, y, face.b0, face.a1, y, face.b0);
                addSegmentUse(usesByLine, plane, face.a1, y, face.b0, face.a1, y, face.b1);
                addSegmentUse(usesByLine, plane, face.a1, y, face.b1, face.a0, y, face.b1);
                addSegmentUse(usesByLine, plane, face.a0, y, face.b1, face.a0, y, face.b0);
            }
            case Z -> {
                int z = face.plane;
                addSegmentUse(usesByLine, plane, face.a0, face.b0, z, face.a1, face.b0, z);
                addSegmentUse(usesByLine, plane, face.a1, face.b0, z, face.a1, face.b1, z);
                addSegmentUse(usesByLine, plane, face.a1, face.b1, z, face.a0, face.b1, z);
                addSegmentUse(usesByLine, plane, face.a0, face.b1, z, face.a0, face.b0, z);
            }
        }
    }

    private static void addSegmentUse(Map<LineKey, List<SegmentUse>> usesByLine,
                                      PlaneKey plane,
                                      int x1, int y1, int z1,
                                      int x2, int y2, int z2) {
        if (x1 == x2 && y1 == y2 && z1 == z2) {
            return;
        }

        LineKey line;
        int start;
        int end;
        if (x1 != x2) {
            line = new LineKey(0, y1, z1);
            start = Math.min(x1, x2);
            end = Math.max(x1, x2);
        } else if (y1 != y2) {
            line = new LineKey(1, x1, z1);
            start = Math.min(y1, y2);
            end = Math.max(y1, y2);
        } else {
            line = new LineKey(2, x1, y1);
            start = Math.min(z1, z2);
            end = Math.max(z1, z2);
        }

        if (end > start) {
            usesByLine.computeIfAbsent(line, ignored -> new ArrayList<>()).add(new SegmentUse(start, end, plane));
        }
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
        for (MapFace face : line.adjacentFaces) {
            if (face.facingCamera) {
                return true;
            }
        }
        return false;
    }

    private void rebuildLineFaceAdjacency() {
        for (MapLine line : roomLines) {
            line.adjacentFaces.clear();
            addAdjacentFacesForLine(line);
        }
    }

    private void removeLinesWithoutAdjacentFaces() {
        for (int i = roomLines.size() - 1; i >= 0; i--) {
            if (roomLines.get(i).adjacentFaces.isEmpty()) {
                roomLines.remove(i);
            }
        }
    }

    private void addAdjacentFacesForLine(MapLine line) {
        int axis = lineAxis(line);
        if (axis < 0) {
            return;
        }

        int minX = Math.min(line.x1, line.x2);
        int maxX = Math.max(line.x1, line.x2);
        int minY = Math.min(line.y1, line.y2);
        int maxY = Math.max(line.y1, line.y2);
        int minZ = Math.min(line.z1, line.z2);
        int maxZ = Math.max(line.z1, line.z2);

        switch (axis) {
            case 0 -> {
                addLineFacesByB(line, Axis.Y, line.y1, line.z1, minX, maxX, true);
                addLineFacesByB(line, Axis.Z, line.z1, line.y1, minX, maxX, true);
            }
            case 1 -> {
                addLineFacesByB(line, Axis.X, line.x1, line.z1, minY, maxY, true);
                addLineFacesByA(line, Axis.Z, line.z1, line.x1, minY, maxY, false);
            }
            case 2 -> {
                addLineFacesByA(line, Axis.X, line.x1, line.y1, minZ, maxZ, false);
                addLineFacesByA(line, Axis.Y, line.y1, line.x1, minZ, maxZ, false);
            }
        }
    }

    private void addLineFacesByB(MapLine line, Axis planeAxis, int plane, int fixedB, int lineStart, int lineEnd, boolean overlapA) {
        List<MapFace> faces = facesByPlane.get(new PlaneKey(planeAxis, plane));
        if (faces == null) {
            return;
        }

        for (MapFace face : faces) {
            int overlap0 = overlapA ? Math.min(face.a0, face.a1) : Math.min(face.b0, face.b1);
            int overlap1 = overlapA ? Math.max(face.a0, face.a1) : Math.max(face.b0, face.b1);
            if ((fixedB == face.b0 || fixedB == face.b1) && rangesOverlap(lineStart, lineEnd, overlap0, overlap1)) {
                addAdjacentFace(line, face);
            }
        }
    }

    private void addLineFacesByA(MapLine line, Axis planeAxis, int plane, int fixedA, int lineStart, int lineEnd, boolean overlapA) {
        List<MapFace> faces = facesByPlane.get(new PlaneKey(planeAxis, plane));
        if (faces == null) {
            return;
        }

        for (MapFace face : faces) {
            int overlap0 = overlapA ? Math.min(face.a0, face.a1) : Math.min(face.b0, face.b1);
            int overlap1 = overlapA ? Math.max(face.a0, face.a1) : Math.max(face.b0, face.b1);
            if ((fixedA == face.a0 || fixedA == face.a1) && rangesOverlap(lineStart, lineEnd, overlap0, overlap1)) {
                addAdjacentFace(line, face);
            }
        }
    }

    private static void addAdjacentFace(MapLine line, MapFace face) {
        if (!line.adjacentFaces.contains(face)) {
            line.adjacentFaces.add(face);
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
        return face.facingCamera;
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
        edges.putIfAbsent(key, new MapLine(key, null, -1));
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
        projectToScratch(x1, y1, z1);
        float sx1 = renderCache.projectedX;
        float sy1 = renderCache.projectedY;
        projectToScratch(x2, y2, z2);
        float sx2 = renderCache.projectedX;
        float sy2 = renderCache.projectedY;
        if (!projectedSegmentOnScreen(sx1, sy1, sx2, sy2)) {
            return;
        }

        drawScreenLineGradient(
            buffer,
            matrix,
            sx1, sy1,
            sx2, sy2,
            MAP_LINE_THICKNESS,
            r1, g1, b1, MathHelper.clamp(a1, 0.0f, 1.0f),
            r2, g2, b2, MathHelper.clamp(a2, 0.0f, 1.0f)
        );
    }

    private ProjectedPoint projectPoint(double worldX, double worldY, double worldZ) {
        return new ProjectedPoint(projectX(worldX, worldY, worldZ), projectY(worldX, worldY, worldZ));
    }

    private RoomActivity computeRoomActivity() {
        if (activeRoomCache.length != roomBounds.size()) {
            activeRoomCache = new boolean[roomBounds.size()];
        }
        for (int i = 0; i < roomBounds.size(); i++) {
            activeRoomCache[i] = roomIntersectsActiveSlab(roomBounds.get(i));
        }
        roomActivityCache.activeRooms = activeRoomCache;
        return roomActivityCache;
    }

    private boolean roomIntersectsActiveSlab(RoomBounds room) {
        // projectPoint() centers a world coordinate when:
        // world - focus + pan == 0, so the current map view center is focus - pan.
        double centerX = renderCache.centerX;
        double centerY = renderCache.centerY;
        double centerZ = renderCache.centerZ;

        // Same yaw basis as the map projection, but with world-locked vertical pitch.
        double rightX = renderCache.yawCos;
        double rightY = 0.0;
        double rightZ = renderCache.yawSin;
        double depthX = -renderCache.yawSin;
        double depthY = 0.0;
        double depthZ = renderCache.yawCos;
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
        return isRoomActive(activity, face.roomIndex);
    }

    private boolean isLineActive(MapLine line, RoomActivity activity) {
        return isRoomActive(activity, line.roomIndex);
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

        if (ENABLE_ROOM_VOLUME_STENCIL) {
            drawRoomVolumeDepthMask(buffer, matrix, activity, activePass, 0.0f, false);
            return;
        }

        for (MapFace face : roomFaces) {
            if (!face.visibleOnScreen || isFaceActive(face, activity) != activePass) {
                continue;
            }

            switch (face.axis) {
                case X -> drawProjectedFaceDepth(buffer, matrix,
                    face.projectedX1, face.projectedY1,
                    face.projectedX2, face.projectedY2,
                    face.projectedX3, face.projectedY3,
                    face.projectedX4, face.projectedY4,
                    0.0f);
                case Y -> drawProjectedFaceDepth(buffer, matrix,
                    face.projectedX1, face.projectedY1,
                    face.projectedX2, face.projectedY2,
                    face.projectedX3, face.projectedY3,
                    face.projectedX4, face.projectedY4,
                    0.0f);
                case Z -> drawProjectedFaceDepth(buffer, matrix,
                    face.projectedX1, face.projectedY1,
                    face.projectedX2, face.projectedY2,
                    face.projectedX3, face.projectedY3,
                    face.projectedX4, face.projectedY4,
                    0.0f);
            }
        }
    }

    private void drawSolidBlockDepthReset(VertexConsumer buffer, Matrix4f matrix, float delta, RoomActivity activity, boolean activePass) {
        buffer.vertex(matrix, -10000.0f, -10000.0f, 1.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10000.0f, -10001.0f, 1.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10001.0f, 1.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10000.0f, 1.0f).color(0.0f, 0.0f, 0.0f, 0.0f);

        for (MapFace face : roomFaces) {
            if (!face.visibleOnScreen || isFaceActive(face, activity) != activePass) {
                continue;
            }

            float reveal = MathHelper.lerp(delta, face.lastReveal, face.reveal);
            if (reveal <= 0.01f) {
                continue;
            }

            switch (face.axis) {
                case X -> {
                    drawProjectedFaceDepth(buffer, matrix,
                        face.projectedX1, face.projectedY1,
                        face.projectedX2, face.projectedY2,
                        face.projectedX3, face.projectedY3,
                        face.projectedX4, face.projectedY4,
                        1.0f);
                }
                case Y -> {
                    drawProjectedFaceDepth(buffer, matrix,
                        face.projectedX1, face.projectedY1,
                        face.projectedX2, face.projectedY2,
                        face.projectedX3, face.projectedY3,
                        face.projectedX4, face.projectedY4,
                        1.0f);
                }
                case Z -> {
                    drawProjectedFaceDepth(buffer, matrix,
                        face.projectedX1, face.projectedY1,
                        face.projectedX2, face.projectedY2,
                        face.projectedX3, face.projectedY3,
                        face.projectedX4, face.projectedY4,
                        1.0f);
                }
            }
        }
    }

    private void drawRoomVolumeEmptyFill(VertexConsumer buffer, Matrix4f matrix, RoomActivity activity, boolean activePass) {
        buffer.vertex(matrix, -10000.0f, -10000.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10000.0f, -10001.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10001.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10000.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);

        float r = activePass ? ROOM_VOLUME_FILL_R : INACTIVE_ROOM_VOLUME_FILL_R;
        float g = activePass ? ROOM_VOLUME_FILL_G : INACTIVE_ROOM_VOLUME_FILL_G;
        float b = activePass ? ROOM_VOLUME_FILL_B : INACTIVE_ROOM_VOLUME_FILL_B;
        float a = activePass ? ROOM_VOLUME_FILL_A : INACTIVE_ROOM_VOLUME_FILL_A;

        for (int roomIndex = 0; roomIndex < roomBounds.size(); roomIndex++) {
            if (isRoomActive(activity, roomIndex) != activePass) {
                continue;
            }
            drawRoomBoundsFaces(buffer, matrix, roomBounds.get(roomIndex), r, g, b, a, 0.0f, true);
        }
    }

    private void drawRoomVolumeDepthMask(VertexConsumer buffer, Matrix4f matrix,
                                         RoomActivity activity,
                                         boolean activePass,
                                         float depth,
                                         boolean cullToScreen) {
        for (int roomIndex = 0; roomIndex < roomBounds.size(); roomIndex++) {
            if (isRoomActive(activity, roomIndex) != activePass) {
                continue;
            }
            drawRoomBoundsFaces(buffer, matrix, roomBounds.get(roomIndex), 0.0f, 0.0f, 0.0f, 1.0f, depth, cullToScreen);
        }
    }

    private void drawRoomBoundsFaces(VertexConsumer buffer, Matrix4f matrix,
                                     RoomBounds room,
                                     float r, float g, float b, float a,
                                     float depth,
                                     boolean cullToScreen) {
        double x0 = room.x0World();
        double y0 = room.y0World();
        double z0 = room.z0World();
        double x1 = room.x1World();
        double y1 = room.y1World();
        double z1 = room.z1World();

        drawProjectedWorldQuad(buffer, matrix, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, r, g, b, a, depth, cullToScreen);
        drawProjectedWorldQuad(buffer, matrix, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0, r, g, b, a, depth, cullToScreen);
        drawProjectedWorldQuad(buffer, matrix, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, r, g, b, a, depth, cullToScreen);
        drawProjectedWorldQuad(buffer, matrix, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b, a, depth, cullToScreen);
        drawProjectedWorldQuad(buffer, matrix, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b, a, depth, cullToScreen);
        drawProjectedWorldQuad(buffer, matrix, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, r, g, b, a, depth, cullToScreen);
    }

    private void drawProjectedWorldQuad(VertexConsumer buffer, Matrix4f matrix,
                                        double wx1, double wy1, double wz1,
                                        double wx2, double wy2, double wz2,
                                        double wx3, double wy3, double wz3,
                                        double wx4, double wy4, double wz4,
                                        float r, float g, float b, float a,
                                        float depth,
                                        boolean cullToScreen) {
        projectToScratch(wx1, wy1, wz1);
        float x1 = renderCache.projectedX;
        float y1 = renderCache.projectedY;
        projectToScratch(wx2, wy2, wz2);
        float x2 = renderCache.projectedX;
        float y2 = renderCache.projectedY;
        projectToScratch(wx3, wy3, wz3);
        float x3 = renderCache.projectedX;
        float y3 = renderCache.projectedY;
        projectToScratch(wx4, wy4, wz4);
        float x4 = renderCache.projectedX;
        float y4 = renderCache.projectedY;

        if (cullToScreen && !projectedQuadOnScreen(x1, y1, x2, y2, x3, y3, x4, y4)) {
            return;
        }

        buffer.vertex(matrix, x1, y1, depth).color(r, g, b, a);
        buffer.vertex(matrix, x2, y2, depth).color(r, g, b, a);
        buffer.vertex(matrix, x3, y3, depth).color(r, g, b, a);
        buffer.vertex(matrix, x4, y4, depth).color(r, g, b, a);
    }

    private void drawSolidBlockFill(VertexConsumer buffer, Matrix4f matrix, float delta, RoomActivity activity, boolean activePass) {
        buffer.vertex(matrix, -10000.0f, -10000.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10000.0f, -10001.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10001.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);
        buffer.vertex(matrix, -10001.0f, -10000.0f, 0.0f).color(0.0f, 0.0f, 0.0f, 0.0f);

        for (MapFace face : roomFaces) {
            if (!face.visibleOnScreen || isFaceActive(face, activity) != activePass) {
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
                    drawProjectedFace(buffer, matrix,
                        face.projectedX1, face.projectedY1,
                        face.projectedX2, face.projectedY2,
                        face.projectedX3, face.projectedY3,
                        face.projectedX4, face.projectedY4,
                        fillR, fillG, fillB, alpha);
                }
                case Y -> {
                    double y = face.planeWorld();
                    double x0 = face.a0World();
                    double x1 = face.a1World();
                    double z0 = face.b0World();
                    double z1 = face.b1World();
                    drawProjectedFace(buffer, matrix,
                        face.projectedX1, face.projectedY1,
                        face.projectedX2, face.projectedY2,
                        face.projectedX3, face.projectedY3,
                        face.projectedX4, face.projectedY4,
                        fillR, fillG, fillB, alpha);
                }
                case Z -> {
                    double z = face.planeWorld();
                    double x0 = face.a0World();
                    double x1 = face.a1World();
                    double y0 = face.b0World();
                    double y1 = face.b1World();
                    drawProjectedFace(buffer, matrix,
                        face.projectedX1, face.projectedY1,
                        face.projectedX2, face.projectedY2,
                        face.projectedX3, face.projectedY3,
                        face.projectedX4, face.projectedY4,
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

    private void drawProjectedFace(VertexConsumer buffer, Matrix4f matrix,
                                   float x1, float y1,
                                   float x2, float y2,
                                   float x3, float y3,
                                   float x4, float y4,
                                   float r, float g, float b, float a) {
        buffer.vertex(matrix, x1, y1, 0.0f).color(r, g, b, a);
        buffer.vertex(matrix, x2, y2, 0.0f).color(r, g, b, a);
        buffer.vertex(matrix, x3, y3, 0.0f).color(r, g, b, a);
        buffer.vertex(matrix, x4, y4, 0.0f).color(r, g, b, a);
    }

    private void drawProjectedFaceDepth(VertexConsumer buffer, Matrix4f matrix,
                                        float x1, float y1,
                                        float x2, float y2,
                                        float x3, float y3,
                                        float x4, float y4,
                                        float depth) {
        buffer.vertex(matrix, x1, y1, depth).color(0.0f, 0.0f, 0.0f, 1.0f);
        buffer.vertex(matrix, x2, y2, depth).color(0.0f, 0.0f, 0.0f, 1.0f);
        buffer.vertex(matrix, x3, y3, depth).color(0.0f, 0.0f, 0.0f, 1.0f);
        buffer.vertex(matrix, x4, y4, depth).color(0.0f, 0.0f, 0.0f, 1.0f);
    }

    private static void drawScreenLine(VertexConsumer buffer, Matrix4f matrix,
                                       float x1, float y1,
                                       float x2, float y2,
                                       float r, float g, float b, float a) {
        drawScreenLineGradient(buffer, matrix, x1, y1, x2, y2, MAP_LINE_THICKNESS, r, g, b, a, r, g, b, a);
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

    private static final class RenderFrameCache {
        private double yawCos = 1.0;
        private double yawSin = 0.0;
        private double pitchCos = 1.0;
        private double pitchSin = 0.0;
        private double screenScale = 1.0;
        private double centerX = 0.0;
        private double centerY = 0.0;
        private double centerZ = 0.0;
        private double screenCenterX = 0.0;
        private double screenCenterY = 0.0;
        private double faceXP = 0.0;
        private double faceXN = 0.0;
        private double faceYP = 0.0;
        private double faceYN = 0.0;
        private double faceZP = 1.0;
        private double faceZN = -1.0;
        private float projectedX = 0.0f;
        private float projectedY = 0.0f;
    }

    private enum Axis {
        X,
        Y,
        Z
    }


    private static final class MapFace {
        private final String roomKey;
        private final int roomIndex;
        private final Axis axis;
        private final int plane;
        private final int a0;
        private final int b0;
        private final int a1;
        private final int b1;
        private final int normalSign;

        private float reveal = 0.0f;
        private float lastReveal = 0.0f;
        private float projectedX1;
        private float projectedY1;
        private float projectedX2;
        private float projectedY2;
        private float projectedX3;
        private float projectedY3;
        private float projectedX4;
        private float projectedY4;
        private boolean facingCamera;
        private boolean visibleOnScreen;
        private double wx1;
        private double wy1;
        private double wz1;
        private double wx2;
        private double wy2;
        private double wz2;
        private double wx3;
        private double wy3;
        private double wz3;
        private double wx4;
        private double wy4;
        private double wz4;

        private MapFace(Face face, String roomKey, int roomIndex) {
            this.roomKey = roomKey;
            this.roomIndex = roomIndex;
            this.axis = face.axis;
            this.plane = face.plane;
            this.a0 = face.a0;
            this.b0 = face.b0;
            this.a1 = face.a1;
            this.b1 = face.b1;
            this.normalSign = face.normalSign;
            cacheWorldCoordinates();
        }

        private MapFace(RoomGeometry.FaceData face, String roomKey, int roomIndex) {
            this.roomKey = roomKey;
            this.roomIndex = roomIndex;
            this.axis = Axis.valueOf(face.axis());
            this.plane = face.plane();
            this.a0 = face.a0();
            this.b0 = face.b0();
            this.a1 = face.a1();
            this.b1 = face.b1();
            this.normalSign = face.normalSign();
            cacheWorldCoordinates();
        }

        private void cacheWorldCoordinates() {
            double p = plane * INV_SHAPE_UNIT;
            double a0w = a0 * INV_SHAPE_UNIT;
            double b0w = b0 * INV_SHAPE_UNIT;
            double a1w = a1 * INV_SHAPE_UNIT;
            double b1w = b1 * INV_SHAPE_UNIT;

            switch (axis) {
                case X -> {
                    wx1 = p;   wy1 = a0w; wz1 = b0w;
                    wx2 = p;   wy2 = a1w; wz2 = b0w;
                    wx3 = p;   wy3 = a1w; wz3 = b1w;
                    wx4 = p;   wy4 = a0w; wz4 = b1w;
                }
                case Y -> {
                    wx1 = a0w; wy1 = p;   wz1 = b0w;
                    wx2 = a1w; wy2 = p;   wz2 = b0w;
                    wx3 = a1w; wy3 = p;   wz3 = b1w;
                    wx4 = a0w; wy4 = p;   wz4 = b1w;
                }
                case Z -> {
                    wx1 = a0w; wy1 = b0w; wz1 = p;
                    wx2 = a1w; wy2 = b0w; wz2 = p;
                    wx3 = a1w; wy3 = b1w; wz3 = p;
                    wx4 = a0w; wy4 = b1w; wz4 = p;
                }
            }
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

        private double corner1WorldX() {
            return switch (axis) {
                case X -> planeWorld();
                case Y, Z -> a0World();
            };
        }

        private double corner1WorldY() {
            return switch (axis) {
                case Y -> planeWorld();
                case X -> a0World();
                case Z -> b0World();
            };
        }

        private double corner1WorldZ() {
            return switch (axis) {
                case Z -> planeWorld();
                case X, Y -> b0World();
            };
        }

        private double corner2WorldX() {
            return switch (axis) {
                case X -> planeWorld();
                case Y, Z -> a1World();
            };
        }

        private double corner2WorldY() {
            return switch (axis) {
                case Y -> planeWorld();
                case X -> a1World();
                case Z -> b0World();
            };
        }

        private double corner2WorldZ() {
            return switch (axis) {
                case Z -> planeWorld();
                case X, Y -> b0World();
            };
        }

        private double corner3WorldX() {
            return switch (axis) {
                case X -> planeWorld();
                case Y, Z -> a1World();
            };
        }

        private double corner3WorldY() {
            return switch (axis) {
                case Y -> planeWorld();
                case X -> a1World();
                case Z -> b1World();
            };
        }

        private double corner3WorldZ() {
            return switch (axis) {
                case Z -> planeWorld();
                case X, Y -> b1World();
            };
        }

        private double corner4WorldX() {
            return switch (axis) {
                case X -> planeWorld();
                case Y, Z -> a0World();
            };
        }

        private double corner4WorldY() {
            return switch (axis) {
                case Y -> planeWorld();
                case X -> a0World();
                case Z -> b1World();
            };
        }

        private double corner4WorldZ() {
            return switch (axis) {
                case Z -> planeWorld();
                case X, Y -> b1World();
            };
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
        private final int roomIndex;
        private final int x1;
        private final int y1;
        private final int z1;
        private final int x2;
        private final int y2;
        private final int z2;

        private float reveal = 0.0f;
        private float lastReveal = 0.0f;
        private float projectedX1;
        private float projectedY1;
        private float projectedX2;
        private float projectedY2;
        private boolean visibleOnScreen;
        private boolean hasFacingFace;
        private double wx1;
        private double wy1;
        private double wz1;
        private double wx2;
        private double wy2;
        private double wz2;
        private final List<MapFace> adjacentFaces = new ArrayList<>(4);

        private MapLine(EdgeKey key, String roomKey, int roomIndex) {
            this.roomKey = roomKey;
            this.roomIndex = roomIndex;
            this.x1 = key.x1;
            this.y1 = key.y1;
            this.z1 = key.z1;
            this.x2 = key.x2;
            this.y2 = key.y2;
            this.z2 = key.z2;
            cacheWorldCoordinates();
        }

        private MapLine(RoomGeometry.LineData line, String roomKey, int roomIndex) {
            this.roomKey = roomKey;
            this.roomIndex = roomIndex;
            this.x1 = line.x1();
            this.y1 = line.y1();
            this.z1 = line.z1();
            this.x2 = line.x2();
            this.y2 = line.y2();
            this.z2 = line.z2();
            cacheWorldCoordinates();
        }

        private void cacheWorldCoordinates() {
            wx1 = x1 * INV_SHAPE_UNIT;
            wy1 = y1 * INV_SHAPE_UNIT;
            wz1 = z1 * INV_SHAPE_UNIT;
            wx2 = x2 * INV_SHAPE_UNIT;
            wy2 = y2 * INV_SHAPE_UNIT;
            wz2 = z2 * INV_SHAPE_UNIT;
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

    private static final class PipeCurve {
        private final int sampleCount;
        private final double[] xs;
        private final double[] ys;
        private final double[] zs;
        private final double[] lengths;
        private final MutableCurvePoint p0 = new MutableCurvePoint();
        private final MutableCurvePoint p1 = new MutableCurvePoint();
        private double totalLength;

        private PipeCurve(int sampleCount) {
            this.sampleCount = sampleCount;
            this.xs = new double[sampleCount + 1];
            this.ys = new double[sampleCount + 1];
            this.zs = new double[sampleCount + 1];
            this.lengths = new double[sampleCount + 1];
        }
    }

    private static final class MutableCurvePoint {
        private double x;
        private double y;
        private double z;
        private double t;

        private void set(double x, double y, double z, double t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }
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
        private PipeCurve forwardCurve;
        private PipeCurve reverseCurve;

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

    private record FaceMergeKey(Axis axis, int plane, int normalSign) {
    }

    private static final class MutableFaceRect {
        private final int a0;
        private final int a1;
        private final int b0;
        private int b1;

        private MutableFaceRect(int a0, int b0, int a1, int b1) {
            this.a0 = a0;
            this.b0 = b0;
            this.a1 = a1;
            this.b1 = b1;
        }
    }

    private static final class RoomActivity {
        private boolean[] activeRooms = new boolean[0];
    }

    private record IntRange(int start, int end) {
    }

    private record SegmentUse(int start, int end, PlaneKey plane) {
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