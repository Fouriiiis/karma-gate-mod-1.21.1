package dev.fouriis.karmagate.client.room;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Black screen map view that renders room block geometry as a white wireframe.
 */
public class RoomMapScreen extends Screen {

    private static final float SHAPE_UNIT = 16.0f;
    private static final float INV_SHAPE_UNIT = 1.0f / SHAPE_UNIT;

    private final List<EdgeKey> wireSegments = new ArrayList<>();
    private double centerX;
    private double centerY;
    private double centerZ;
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
    private double revealRadius = 0.0;
    private double revealMax = 0.0;
    private double revealSpeed = 0.1;
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
        revealRadius = 0.0;
        rebuildGeometry();
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

        if (revealRadius < revealMax) {
            revealRadius = Math.min(revealMax, revealRadius + revealSpeed);
        }

        long window = client.getWindow().getHandle();
        boolean forward = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_W);
        boolean back = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_S);
        boolean left = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_A);
        boolean right = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_D);

        double move = 0.5 / Math.max(0.001, scale);
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
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xFF000000);

        if (!hasRooms) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No rooms"), this.width / 2, this.height / 2 - 4, 0xFFFFFFFF);
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();

        MatrixStack matrices = context.getMatrices();
        matrices.push();

        matrices.translate(this.width * 0.5, this.height * 0.55, 0.0);
        matrices.scale(scale * zoom, scale * zoom, scale * zoom);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        matrices.translate(-centerX + panX, -centerY + panY, -centerZ + panZ);

        MinecraftClient client = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer lineBuffer = immediate.getBuffer(RenderLayer.getLines());

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        for (EdgeKey edge : wireSegments) {
            if (isEdgeRevealed(edge, revealRadius)) {
                drawLine(lineBuffer, matrix, edge, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        }

        matrices.pop();
        immediate.draw();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
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
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        double factor = Math.pow(1.1, verticalAmount);
        zoom = MathHelper.clamp((float) (zoom * factor), 0.05f, 40.0f);
        return true;
    }

    private void rebuildGeometry() {
        wireSegments.clear();
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

        hasRooms = true;

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (RoomClientState.RoomEntry room : rooms) {
            BlockPos min = room.min();
            BlockPos max = room.max();
            minX = Math.min(minX, min.getX());
            minY = Math.min(minY, min.getY());
            minZ = Math.min(minZ, min.getZ());
            maxX = Math.max(maxX, max.getX());
            maxY = Math.max(maxY, max.getY());
            maxZ = Math.max(maxZ, max.getZ());
        }

        VoxelShape union = VoxelShapes.empty();
        for (RoomClientState.RoomEntry room : rooms) {
            BlockPos min = room.min();
            BlockPos max = room.max();
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        var state = world.getBlockState(pos);
                        VoxelShape shape = state.getCollisionShape(world, pos);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        union = VoxelShapes.union(union, shape.offset(x, y, z));
                    }
                }
            }
        }

        if (!union.isEmpty()) {
            wireSegments.addAll(buildWireframeFromShape(union));
        }

        centerX = (minX + maxX + 1) * 0.5;
        centerY = (minY + maxY + 1) * 0.5;
        centerZ = (minZ + maxZ + 1) * 0.5;

        if (client.player != null) {
            revealCenterX = client.player.getX();
            revealCenterY = client.player.getY();
            revealCenterZ = client.player.getZ();
        }

        revealMax = computeRevealMax(minX, minY, minZ, maxX, maxY, maxZ);
        revealRadius = 0.0;

        double sizeX = maxX - minX + 1;
        double sizeY = maxY - minY + 1;
        double sizeZ = maxZ - minZ + 1;
        double maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));
        if (maxDim < 1.0) {
            maxDim = 1.0;
        }
        float target = (float) Math.min(this.width, this.height) * 0.35f;
        scale = target / (float) maxDim;
    }

    private double computeRevealMax(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        double best = 0.0;
        double[] xs = {minX, maxX + 1};
        double[] ys = {minY, maxY + 1};
        double[] zs = {minZ, maxZ + 1};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    double dx = x - revealCenterX;
                    double dy = y - revealCenterY;
                    double dz = z - revealCenterZ;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > best) {
                        best = dist;
                    }
                }
            }
        }
        return best + 2.0;
    }

    private static List<EdgeKey> buildWireframeFromShape(VoxelShape shape) {
        Set<EdgeKey> edges = new HashSet<>();
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            int x0 = toUnit(minX);
            int y0 = toUnit(minY);
            int z0 = toUnit(minZ);
            int x1 = toUnit(maxX);
            int y1 = toUnit(maxY);
            int z1 = toUnit(maxZ);
            addBoxEdges(edges, x0, y0, z0, x1, y1, z1);
        });
        return new ArrayList<>(edges);
    }

    private static void addBoxEdges(Set<EdgeKey> edges,
                                    int x0, int y0, int z0,
                                    int x1, int y1, int z1) {
        addEdge(edges, x0, y0, z0, x1, y0, z0);
        addEdge(edges, x1, y0, z0, x1, y0, z1);
        addEdge(edges, x1, y0, z1, x0, y0, z1);
        addEdge(edges, x0, y0, z1, x0, y0, z0);

        addEdge(edges, x0, y1, z0, x1, y1, z0);
        addEdge(edges, x1, y1, z0, x1, y1, z1);
        addEdge(edges, x1, y1, z1, x0, y1, z1);
        addEdge(edges, x0, y1, z1, x0, y1, z0);

        addEdge(edges, x0, y0, z0, x0, y1, z0);
        addEdge(edges, x1, y0, z0, x1, y1, z0);
        addEdge(edges, x1, y0, z1, x1, y1, z1);
        addEdge(edges, x0, y0, z1, x0, y1, z1);
    }

    private static void addEdge(Set<EdgeKey> edges,
                                int x1, int y1, int z1,
                                int x2, int y2, int z2) {
        edges.add(edgeKey(x1, y1, z1, x2, y2, z2));
    }

    private static EdgeKey edgeKey(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (compare(x1, y1, z1, x2, y2, z2) > 0) {
            int tx = x1; int ty = y1; int tz = z1;
            x1 = x2; y1 = y2; z1 = z2;
            x2 = tx; y2 = ty; z2 = tz;
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

    private static void drawLine(VertexConsumer buffer, Matrix4f matrix, EdgeKey edge,
                                 float r, float g, float b, float a) {
        float x1 = edge.x1() * INV_SHAPE_UNIT;
        float y1 = edge.y1() * INV_SHAPE_UNIT;
        float z1 = edge.z1() * INV_SHAPE_UNIT;
        float x2 = edge.x2() * INV_SHAPE_UNIT;
        float y2 = edge.y2() * INV_SHAPE_UNIT;
        float z2 = edge.z2() * INV_SHAPE_UNIT;
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(0.0f, 1.0f, 0.0f);
    }

    private boolean isEdgeRevealed(EdgeKey edge, double radius) {
        if (radius <= 0.0) {
            return false;
        }
        double x1 = edge.x1() * INV_SHAPE_UNIT;
        double y1 = edge.y1() * INV_SHAPE_UNIT;
        double z1 = edge.z1() * INV_SHAPE_UNIT;
        double x2 = edge.x2() * INV_SHAPE_UNIT;
        double y2 = edge.y2() * INV_SHAPE_UNIT;
        double z2 = edge.z2() * INV_SHAPE_UNIT;

        double r2 = radius * radius;
        double dist2 = distanceSqToSegment(
            revealCenterX, revealCenterY, revealCenterZ,
            x1, y1, z1,
            x2, y2, z2
        );
        return dist2 <= r2;
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
        if (abLen2 <= 1e-9) {
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

    private record EdgeKey(int x1, int y1, int z1, int x2, int y2, int z2) {}
}
