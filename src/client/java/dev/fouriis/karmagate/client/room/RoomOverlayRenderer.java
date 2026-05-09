package dev.fouriis.karmagate.client.room;

import dev.fouriis.karmagate.item.ModItems;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Renders room bounds and selection overlay while holding the Room Tool.
 */
public final class RoomOverlayRenderer {
    private static final float ROOM_R = 0.2f;
    private static final float ROOM_G = 0.85f;
    private static final float ROOM_B = 0.95f;
    private static final float SEL_R = 1.0f;
    private static final float SEL_G = 0.85f;
    private static final float SEL_B = 0.25f;

    private RoomOverlayRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(RoomOverlayRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        boolean holdingTool = client.player.getMainHandStack().isOf(ModItems.ROOM_TOOL)
            || client.player.getOffHandStack().isOf(ModItems.ROOM_TOOL);
        if (!holdingTool) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }

        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer lineBuffer = immediate.getBuffer(RenderLayer.getLines());
        Vec3d cameraPos = context.camera().getPos();

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        for (RoomClientState.RoomEntry room : RoomClientState.getRooms()) {
            WorldRenderer.drawBox(matrices, lineBuffer, room.bounds(), ROOM_R, ROOM_G, ROOM_B, 1.0f);
            Vec3d labelPos = getLabelPos(room.min(), room.max());
            renderLabel(matrices, immediate, context.camera(), labelPos, Text.literal(room.name()), 0xE6F7FFFF);
        }

        BlockPos corner1 = RoomSelectionClientState.getCorner1();
        BlockPos corner2 = RoomSelectionClientState.getCorner2();
        if (corner1 != null && corner2 != null) {
            Box box = toBox(corner1, corner2);
            WorldRenderer.drawBox(matrices, lineBuffer, box, SEL_R, SEL_G, SEL_B, 1.0f);
            Vec3d labelPos = getLabelPos(box);
            renderLabel(matrices, immediate, context.camera(), labelPos, Text.literal("Selection"), 0xFFF8E46A);
        } else if (corner1 != null) {
            Box box = toBox(corner1, corner1);
            WorldRenderer.drawBox(matrices, lineBuffer, box, SEL_R, SEL_G, SEL_B, 1.0f);
            Vec3d labelPos = getLabelPos(box);
            renderLabel(matrices, immediate, context.camera(), labelPos, Text.literal("Corner A"), 0xFFF8E46A);
        } else if (corner2 != null) {
            Box box = toBox(corner2, corner2);
            WorldRenderer.drawBox(matrices, lineBuffer, box, SEL_R, SEL_G, SEL_B, 1.0f);
            Vec3d labelPos = getLabelPos(box);
            renderLabel(matrices, immediate, context.camera(), labelPos, Text.literal("Corner B"), 0xFFF8E46A);
        }

        matrices.pop();
        immediate.draw();
    }

    private static Vec3d getLabelPos(BlockPos min, BlockPos max) {
        double x = (min.getX() + max.getX() + 1) * 0.5;
        double y = max.getY() + 1.25;
        double z = (min.getZ() + max.getZ() + 1) * 0.5;
        return new Vec3d(x, y, z);
    }

    private static Vec3d getLabelPos(Box box) {
        return new Vec3d(
            (box.minX + box.maxX) * 0.5,
            box.maxY + 0.25,
            (box.minZ + box.maxZ) * 0.5
        );
    }

    private static Box toBox(BlockPos corner1, BlockPos corner2) {
        int minX = Math.min(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());
        return new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    private static void renderLabel(MatrixStack matrices,
                                    VertexConsumerProvider consumers,
                                    Camera camera,
                                    Vec3d pos,
                                    Text text,
                                    int color) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer renderer = client.textRenderer;

        matrices.push();
        matrices.translate(pos.x, pos.y, pos.z);
        matrices.multiply(camera.getRotation());

        float scale = 0.025f;
        matrices.scale(-scale, -scale, scale);

        float x = -renderer.getWidth(text) * 0.5f;
        renderer.draw(
            text,
            x,
            0.0f,
            color,
            false,
            matrices.peek().getPositionMatrix(),
            consumers,
            TextRenderer.TextLayerType.SEE_THROUGH,
            0,
            LightmapTextureManager.MAX_LIGHT_COORDINATE
        );
        matrices.pop();
    }
}
