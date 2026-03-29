package dev.fouriis.karmagate.client.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.joml.Matrix4f;

public final class DeathRainWeatherRenderer {

    private static final int DEBUG_RADIUS = 12;
    private static final float FACE_EPSILON = 0.001f;

    private static final Identifier LEVEL_TEXTURE =
            Identifier.of("librainworldmc", "grabtex");

    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise.png");

    private static final Identifier RAIN_TEXTURE =
            Identifier.of("minecraft", "textures/block/water_flow.png");

    private DeathRainWeatherRenderer() {
    }

    public static void render(World world, Camera camera, float tickDelta, MatrixStack matrices) {
        if (world == null || camera == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        BlockPos anchor = client.player.getBlockPos();
        int playerY = anchor.getY();

        BlockPos.Mutable here = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();

        for (int dz = -DEBUG_RADIUS; dz <= DEBUG_RADIUS; dz++) {
            for (int dx = -DEBUG_RADIUS; dx <= DEBUG_RADIUS; dx++) {
                int worldX = anchor.getX() + dx;
                int worldZ = anchor.getZ() + dz;

                here.set(worldX, playerY, worldZ);
                boolean openHere = isOpenToSky(world, here);

                // Boundary between (x,z) and (x+1,z)
                neighbor.set(worldX + 1, playerY, worldZ);
                boolean openEast = isOpenToSky(world, neighbor);

                if (openHere != openEast) {
    if (!openHere) {
        // current cell is dry => west side of boundary
        float topY = getCoverUndersideY(world, worldX, worldZ, playerY);
        float bottomY = getDryFloorTopY(world, worldX, worldZ, playerY);

        if (topY > bottomY + 0.05f) {
            renderRainCurtainImmediate(
                    matrices,
                    camera,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE,
                    worldX + 1.0f - FACE_EPSILON,
                    worldZ,
                    topY,
                    bottomY,
                    CurtainOrientation.NORTH_SOUTH,
                    false
            );
        }
    } else {
        // east neighbor is dry => east side of boundary
        float topY = getCoverUndersideY(world, worldX + 1, worldZ, playerY);
        float bottomY = getDryFloorTopY(world, worldX + 1, worldZ, playerY);

        if (topY > bottomY + 0.05f) {
            renderRainCurtainImmediate(
                    matrices,
                    camera,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE,
                    worldX + 1.0f + FACE_EPSILON,
                    worldZ,
                    topY,
                    bottomY,
                    CurtainOrientation.NORTH_SOUTH,
                    true
            );
        }
    }
}

                // Boundary between (x,z) and (x,z+1)
                neighbor.set(worldX, playerY, worldZ + 1);
                boolean openSouth = isOpenToSky(world, neighbor);

                if (openHere != openSouth) {
                    if (!openHere) {
                        // current cell is dry => north side of boundary, face inward (+Z)
                        float topY = getCoverUndersideY(world, worldX, worldZ, playerY);
                        float bottomY = getDryFloorTopY(world, worldX, worldZ, playerY);

                        if (topY > bottomY + 0.05f) {
                            renderRainCurtainImmediate(
                                    matrices,
                                    camera,
                                    LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                    worldX,
                                    worldZ + 1.0f - FACE_EPSILON,
                                    topY,
                                    bottomY,
                                    CurtainOrientation.EAST_WEST,
                                    true
                            );
                        }
                    } else {
                        // south neighbor is dry => south side of boundary, face inward (-Z)
                        float topY = getCoverUndersideY(world, worldX, worldZ + 1, playerY);
                        float bottomY = getDryFloorTopY(world, worldX, worldZ + 1, playerY);

                        if (topY > bottomY + 0.05f) {
                            renderRainCurtainImmediate(
                                    matrices,
                                    camera,
                                    LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                    worldX,
                                    worldZ + 1.0f + FACE_EPSILON,
                                    topY,
                                    bottomY,
                                    CurtainOrientation.EAST_WEST,
                                    false
                            );
                        }
                    }
                }
            }
        }
    }

    private enum CurtainOrientation {
        NORTH_SOUTH,
        EAST_WEST
    }

    private static void renderRainCurtainImmediate(
            MatrixStack matrices,
            Camera camera,
            int packedLight,
            float worldX,
            float worldZ,
            float topY,
            float bottomY,
            CurtainOrientation orientation,
            boolean positiveFacing
    ) {
        try {
            float[] spriteRect = new float[]{0f, 0f, 1f, 1f};

            CoreShaderRenderer.bindShader$WaterFall(
                    spriteRect,
                    10,
                    LEVEL_TEXTURE,
                    NOISE_TEXTURE,
                    RAIN_TEXTURE,
                    null,
                    null,
                    false
            );

            int r = 255;
            int g = 255;
            int b = 255;
            int a = 255;

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.setShaderTexture(0, RAIN_TEXTURE);

            matrices.push();

            matrices.translate(
                    worldX - camera.getPos().x,
                    -camera.getPos().y,
                    worldZ - camera.getPos().z
            );

            Matrix4f m = matrices.peek().getPositionMatrix();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
            );

            if (orientation == CurtainOrientation.NORTH_SOUTH) {
                emitCurtainPlaneNS(
                        buffer, m,
                        topY, bottomY, packedLight,
                        r, g, b, a,
                        positiveFacing
                );
            } else {
                emitCurtainPlaneEW(
                        buffer, m,
                        topY, bottomY, packedLight,
                        r, g, b, a,
                        positiveFacing
                );
            }

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            RenderSystem.depthMask(true);
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering rain curtain at "
                    + worldX + ", " + worldZ
                    + " topY=" + topY
                    + " bottomY=" + bottomY
                    + " orientation=" + orientation
                    + " positiveFacing=" + positiveFacing);
            t.printStackTrace();
            RenderSystem.depthMask(true);
        }
    }

    private static void emitCurtainPlaneNS(
            BufferBuilder buffer,
            Matrix4f m,
            float topY,
            float bottomY,
            int light,
            int r, int g, int b, int a,
            boolean positiveFacing
    ) {
        float x = 0.0f;
        float z0 = 0.0f;
        float z1 = 1.0f;

        float u0 = 0.0f;
        float u1 = 1.0f;
        float v0 = 0.0f;
        float v1 = 1.0f;

        float nx = positiveFacing ? 1.0f : -1.0f;
        float ny = 0.0f;
        float nz = 0.0f;

        if (positiveFacing) {
            buffer.vertex(m, x, topY, z0).color(r, g, b, a).texture(u0, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x, topY, z1).color(r, g, b, a).texture(u1, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x, bottomY, z1).color(r, g, b, a).texture(u1, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x, bottomY, z0).color(r, g, b, a).texture(u0, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        } else {
            buffer.vertex(m, x, topY, z1).color(r, g, b, a).texture(u0, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x, topY, z0).color(r, g, b, a).texture(u1, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x, bottomY, z0).color(r, g, b, a).texture(u1, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x, bottomY, z1).color(r, g, b, a).texture(u0, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        }
    }

    private static void emitCurtainPlaneEW(
            BufferBuilder buffer,
            Matrix4f m,
            float topY,
            float bottomY,
            int light,
            int r, int g, int b, int a,
            boolean positiveFacing
    ) {
        float x0 = 0.0f;
        float x1 = 1.0f;
        float z = 0.0f;

        float u0 = 0.0f;
        float u1 = 1.0f;
        float v0 = 0.0f;
        float v1 = 1.0f;

        float nx = 0.0f;
        float ny = 0.0f;
        float nz = positiveFacing ? 1.0f : -1.0f;

        if (positiveFacing) {
            buffer.vertex(m, x0, topY, z).color(r, g, b, a).texture(u0, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x1, topY, z).color(r, g, b, a).texture(u1, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x1, bottomY, z).color(r, g, b, a).texture(u1, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x0, bottomY, z).color(r, g, b, a).texture(u0, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        } else {
            buffer.vertex(m, x1, topY, z).color(r, g, b, a).texture(u0, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x0, topY, z).color(r, g, b, a).texture(u1, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x0, bottomY, z).color(r, g, b, a).texture(u1, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x1, bottomY, z).color(r, g, b, a).texture(u0, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        }
    }

    private static boolean isOpenToSky(World world, BlockPos pos) {
        return pos.getY() >= world.getBottomY() && world.isSkyVisible(pos);
    }

    private static float getCoverUndersideY(World world, int x, int z, int playerY) {
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int y = playerY + 1; y <= world.getTopY(); y++) {
            pos.set(x, y, z);

            if (world.getBlockState(pos).isAir()) {
                continue;
            }

            VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
            if (shape.isEmpty()) {
                continue;
            }

            double minY = shape.getMin(Direction.Axis.Y);
            return (float) (y + minY);
        }

        return playerY + 1.0f;
    }

    private static float getDryFloorTopY(World world, int x, int z, int playerY) {
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int y = playerY; y >= world.getBottomY(); y--) {
            pos.set(x, y, z);

            if (world.getBlockState(pos).isAir()) {
                continue;
            }

            VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
            if (shape.isEmpty()) {
                continue;
            }

            double maxY = shape.getMax(Direction.Axis.Y);
            return (float) (y + maxY);
        }

        return world.getBottomY();
    }
}