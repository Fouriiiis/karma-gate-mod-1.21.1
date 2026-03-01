package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.entity.GraffitiEntity;
import dev.fouriis.karmagate.item.ModItems;
import dev.fouriis.karmagate.network.UpdateGraffitiPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.OptionalDouble;
import java.util.List;

/**
 * Handles client-side corner selection and dragging for graffiti editing.
 * Click a corner to select/start moving it. Click again (or release attack key) to place it.
 */
public class GraffitiCornerHandler {
    private static final float CORNER_SELECT_RADIUS = 0.15f;
    private static final float MAX_EDIT_DISTANCE = 10f;

    private static final RenderLayer EDIT_LINES = RenderLayer.of(
        "graffiti_edit_lines",
        VertexFormats.POSITION_COLOR,
        VertexFormat.DrawMode.LINES,
        256,
        false,
        false,
        RenderLayer.MultiPhaseParameters.builder()
            .program(RenderPhase.LINES_PROGRAM)
            .lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(1.0)))
            .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
            .depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
            .writeMaskState(RenderPhase.COLOR_MASK)
            .cull(RenderPhase.DISABLE_CULLING)
            .build(false)
    );

    private static GraffitiEntity selectedEntity = null;
    private static int selectedCorner = -1;
    /** True when a corner has been clicked and is being moved. */
    public static boolean isDragging = false;
    private static int hoveredCorner = -1;
    private static GraffitiEntity hoveredEntity = null;

    /** Track whether the attack key was pressed last tick to detect rising edges. */
    private static boolean wasAttackPressed = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(GraffitiCornerHandler::onClientTick);
        WorldRenderEvents.AFTER_ENTITIES.register(GraffitiCornerHandler::onWorldRender);
    }

    private static void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            reset();
            wasAttackPressed = false;
            return;
        }

        // Check if holding graffiti item
        boolean holdingGraffiti = client.player.getMainHandStack().isOf(ModItems.GRAFFITI_PLACER) ||
                                  client.player.getOffHandStack().isOf(ModItems.GRAFFITI_PLACER);

        if (!holdingGraffiti) {
            if (isDragging) sendCornerUpdate(selectedEntity);
            reset();
            wasAttackPressed = false;
            return;
        }

        // Find nearby graffiti entities
        Vec3d playerPos = client.player.getEyePos();
        List<GraffitiEntity> nearbyGraffiti = client.world.getEntitiesByClass(
            GraffitiEntity.class,
            new Box(playerPos.subtract(MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE),
                    playerPos.add(MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE)),
            e -> true
        );

        Vec3d lookDir = client.player.getRotationVec(1.0f);

        // Always update hover state
        hoveredCorner = -1;
        hoveredEntity = null;
        double closestDist = Double.MAX_VALUE;

        for (GraffitiEntity graffiti : nearbyGraffiti) {
            float[][] corners = graffiti.getCorners();
            for (int i = 0; i < 4; i++) {
                Vec3d cornerWorld = getCornerWorldPos(graffiti, corners[i][0], corners[i][1]);
                double dist = distanceToRay(playerPos, lookDir, cornerWorld);
                double playerDist = playerPos.distanceTo(cornerWorld);
                float selectRadius = CORNER_SELECT_RADIUS * (float)(1.0 + playerDist * 0.1);
                if (dist < selectRadius && playerDist < closestDist) {
                    closestDist = playerDist;
                    hoveredCorner = i;
                    hoveredEntity = graffiti;
                }
            }
        }

        // Detect left-click press (rising edge only)
        boolean attackPressed = client.options.attackKey.isPressed();
        boolean clickedThisTick = attackPressed && !wasAttackPressed;
        wasAttackPressed = attackPressed;

        if (clickedThisTick) {
            if (!isDragging) {
                // Start dragging the hovered corner if there is one
                if (hoveredCorner >= 0 && hoveredEntity != null) {
                    isDragging = true;
                    selectedCorner = hoveredCorner;
                    selectedEntity = hoveredEntity;
                }
            } else {
                // Second click: drop the corner in place, send update, stop dragging
                sendCornerUpdate(selectedEntity);
                isDragging = false;
                selectedCorner = -1;
                selectedEntity = null;
            }
        }

        // Update corner position each tick while dragging
        if (isDragging && selectedEntity != null && selectedCorner >= 0) {
            updateDraggedCorner(client, selectedEntity, selectedCorner);
        }
    }

    private static void updateDraggedCorner(MinecraftClient client, GraffitiEntity graffiti, int corner) {
        if (client.player == null) return;
        Vec3d playerPos = client.player.getEyePos();
        Vec3d lookDir = client.player.getRotationVec(1.0f);
        Direction facing = graffiti.getFacing();
        Vec3d entityPos = graffiti.getPos();
        Vec3d planeNormal = new Vec3d(facing.getOffsetX(), facing.getOffsetY(), facing.getOffsetZ());
        Vec3d hitPoint = rayPlaneIntersect(playerPos, lookDir, entityPos, planeNormal);
        if (hitPoint == null) return;
        Direction rightDir = facing.rotateYClockwise();
        Vec3d relative = hitPoint.subtract(entityPos);
        float h = (float)(relative.x * rightDir.getOffsetX() + relative.z * rightDir.getOffsetZ());
        float v = (float)relative.y;
        graffiti.setCorner(corner, h, v);
    }

    private static Vec3d getCornerWorldPos(GraffitiEntity graffiti, float h, float v) {
        Direction facing = graffiti.getFacing();
        Direction rightDir = facing.rotateYClockwise();
        return graffiti.getPos().add(
            rightDir.getOffsetX() * h,
            v,
            rightDir.getOffsetZ() * h
        );
    }

    private static double distanceToRay(Vec3d rayOrigin, Vec3d rayDir, Vec3d point) {
        Vec3d toPoint = point.subtract(rayOrigin);
        double t = toPoint.dotProduct(rayDir);
        if (t < 0) return Double.MAX_VALUE;
        Vec3d closest = rayOrigin.add(rayDir.multiply(t));
        return closest.distanceTo(point);
    }

    private static Vec3d rayPlaneIntersect(Vec3d rayOrigin, Vec3d rayDir, Vec3d planePoint, Vec3d planeNormal) {
        double denom = rayDir.dotProduct(planeNormal);
        if (Math.abs(denom) < 0.0001) return null;
        double t = planePoint.subtract(rayOrigin).dotProduct(planeNormal) / denom;
        if (t < 0) return null;
        return rayOrigin.add(rayDir.multiply(t));
    }

    private static void reset() {
        selectedEntity = null;
        selectedCorner = -1;
        isDragging = false;
        hoveredCorner = -1;
        hoveredEntity = null;
    }

    private static void onWorldRender(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        boolean holdingGraffiti = client.player.getMainHandStack().isOf(ModItems.GRAFFITI_PLACER) ||
                                  client.player.getOffHandStack().isOf(ModItems.GRAFFITI_PLACER);
        if (!holdingGraffiti) return;

        Vec3d playerPos = client.player.getEyePos();
        List<GraffitiEntity> nearbyGraffiti = client.world.getEntitiesByClass(
            GraffitiEntity.class,
            new Box(playerPos.subtract(MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE),
                    playerPos.add(MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE)),
            e -> true
        );

        MatrixStack matrices = context.matrixStack();
        if (matrices == null) return;
        Vec3d cameraPos = context.camera().getPos();
        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer lineBuffer = immediate.getBuffer(EDIT_LINES);
        for (GraffitiEntity graffiti : nearbyGraffiti) {
            renderGraffitiBorder(matrices, lineBuffer, graffiti, cameraPos);
        }
        immediate.draw(EDIT_LINES);
    }

    private static void renderGraffitiBorder(MatrixStack matrices, VertexConsumer buffer,
                                             GraffitiEntity graffiti, Vec3d cameraPos) {
        float[][] corners = graffiti.getCorners();
        Direction facing = graffiti.getFacing();
        Direction rightDir = facing.rotateYClockwise();
        Vec3d entityPos = graffiti.getPos();
        Vec3d[] worldCorners = new Vec3d[4];
        for (int i = 0; i < 4; i++) {
            worldCorners[i] = entityPos.add(
                rightDir.getOffsetX() * corners[i][0] - facing.getOffsetX() * 0.01,
                corners[i][1],
                rightDir.getOffsetZ() * corners[i][0] - facing.getOffsetZ() * 0.01
            ).subtract(cameraPos);
        }
        matrices.push();
        Matrix4f mat = matrices.peek().getPositionMatrix();
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            drawLine(buffer, mat, worldCorners[i], worldCorners[j], 1f, 1f, 1f, 1f);
        }
        for (int i = 0; i < 4; i++) {
            boolean isHovered = (hoveredEntity == graffiti && hoveredCorner == i);
            boolean isSelected = (selectedEntity == graffiti && selectedCorner == i && isDragging);
            float r = isSelected ? 0f  : (isHovered ? 1f  : 0.5f);
            float g = isSelected ? 1f  : (isHovered ? 1f  : 0.5f);
            float b = isSelected ? 0f  : (isHovered ? 0f  : 0.5f);
            drawCornerMarker(buffer, mat, worldCorners[i], CORNER_SELECT_RADIUS * 0.5f, r, g, b);
        }
        matrices.pop();
    }

    private static void drawLine(VertexConsumer buffer, Matrix4f mat, Vec3d from, Vec3d to,
                                 float r, float g, float b, float a) {
        buffer.vertex(mat, (float)from.x, (float)from.y, (float)from.z).color(r, g, b, a);
        buffer.vertex(mat, (float)to.x,   (float)to.y,   (float)to.z  ).color(r, g, b, a);
    }

    private static void drawCornerMarker(VertexConsumer buffer, Matrix4f mat, Vec3d pos, float size,
                                         float r, float g, float b) {
        drawLine(buffer, mat, pos.add(-size, 0, 0), pos.add(size, 0, 0), r, g, b, 1f);
        drawLine(buffer, mat, pos.add(0, -size, 0), pos.add(0, size, 0), r, g, b, 1f);
        drawLine(buffer, mat, pos.add(0, 0, -size), pos.add(0, 0, size), r, g, b, 1f);
    }

    // Getters
    public static boolean isDragging() { return isDragging; }
    public static GraffitiEntity getSelectedEntity() { return selectedEntity; }
    public static int getSelectedCorner() { return selectedCorner; }

    private static void sendCornerUpdate(GraffitiEntity graffiti) {
        if (graffiti == null) return;
        float[] opacities = new float[4];
        float[] melts     = new float[4];
        float[] cornerH   = new float[4];
        float[] cornerV   = new float[4];
        for (int i = 0; i < 4; i++) {
            opacities[i] = graffiti.getCornerOpacity(i);
            melts[i]     = graffiti.getCornerMelt(i);
            cornerH[i]   = graffiti.getCornerH(i);
            cornerV[i]   = graffiti.getCornerV(i);
        }
        ClientPlayNetworking.send(new UpdateGraffitiPayload(
            graffiti.getId(), graffiti.getTexturePath(),
            opacities, melts, cornerH, cornerV
        ));
    }
}
