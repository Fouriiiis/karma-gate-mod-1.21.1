package dev.fouriis.karmagate.client.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.world.BiomeColors;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.joml.Matrix4f;

public final class DeathRainWeatherRenderer {

    private static final int DEBUG_RADIUS = 12;
    private static final float FACE_EPSILON = 0.001f;
    private static final float SCREEN_RAIN_TRANSITION_DISTANCE = 1.25f;

    private static final Identifier LEVEL_TEXTURE =
            Identifier.of("librainworldmc", "grabtex");

    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise_hq.png");

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

        RainBorderTransition transition = findNearestRainBorderTransition(world, camera);
        float coverage = computeScreenRainCoverage(transition);

        renderBorderCurtains(world, camera, matrices);

        if (coverage > 0.0f) {
            float axis = 0.0f; // reveal left/right
            float flip = transition.rainOnPositiveSide ? 0.0f : 1.0f;
            renderScreenRainOverlay(world, camera, matrices, coverage, axis, flip);
        }
    }

    private static void renderBorderCurtains(World world, Camera camera, MatrixStack matrices) {
        MinecraftClient client = MinecraftClient.getInstance();
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

                neighbor.set(worldX + 1, playerY, worldZ);
                boolean openEast = isOpenToSky(world, neighbor);

                if (openHere != openEast) {
                    if (!openHere) {
                        float topY = getCoverUndersideY(world, worldX, worldZ, playerY);
                        float bottomY = getDryFloorTopY(world, worldX, worldZ, playerY);

                        if (topY > bottomY + 0.05f) {
                            renderRainCurtainImmediate(
                                    world,
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
                        float topY = getCoverUndersideY(world, worldX + 1, worldZ, playerY);
                        float bottomY = getDryFloorTopY(world, worldX + 1, worldZ, playerY);

                        if (topY > bottomY + 0.05f) {
                            renderRainCurtainImmediate(
                                    world,
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

                neighbor.set(worldX, playerY, worldZ + 1);
                boolean openSouth = isOpenToSky(world, neighbor);

                if (openHere != openSouth) {
                    if (!openHere) {
                        float topY = getCoverUndersideY(world, worldX, worldZ, playerY);
                        float bottomY = getDryFloorTopY(world, worldX, worldZ, playerY);

                        if (topY > bottomY + 0.05f) {
                            renderRainCurtainImmediate(
                                    world,
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
                        float topY = getCoverUndersideY(world, worldX, worldZ + 1, playerY);
                        float bottomY = getDryFloorTopY(world, worldX, worldZ + 1, playerY);

                        if (topY > bottomY + 0.05f) {
                            renderRainCurtainImmediate(
                                    world,
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

    private static final class RainBorderTransition {
        final boolean valid;
        final CurtainOrientation orientation;
        final boolean rainOnPositiveSide;
        final float borderCoord;
        final float penetration;

        private RainBorderTransition(boolean valid, CurtainOrientation orientation, boolean rainOnPositiveSide, float borderCoord, float penetration) {
            this.valid = valid;
            this.orientation = orientation;
            this.rainOnPositiveSide = rainOnPositiveSide;
            this.borderCoord = borderCoord;
            this.penetration = penetration;
        }
    }

    private static RainBorderTransition findNearestRainBorderTransition(World world, Camera camera) {
        Vec3d cam = camera.getPos();
        BlockPos camBlock = BlockPos.ofFloored(cam);

        int centerX = camBlock.getX();
        int centerY = camBlock.getY();
        int centerZ = camBlock.getZ();

        BlockPos.Mutable here = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();

        float bestAbsPenetration = Float.MAX_VALUE;
        RainBorderTransition best = new RainBorderTransition(false, CurtainOrientation.NORTH_SOUTH, false, 0.0f, 0.0f);

        for (int dz = -DEBUG_RADIUS; dz <= DEBUG_RADIUS; dz++) {
            for (int dx = -DEBUG_RADIUS; dx <= DEBUG_RADIUS; dx++) {
                int worldX = centerX + dx;
                int worldZ = centerZ + dz;

                here.set(worldX, centerY, worldZ);
                boolean openHere = isOpenToSky(world, here);

                neighbor.set(worldX + 1, centerY, worldZ);
                boolean openEast = isOpenToSky(world, neighbor);

                if (openHere != openEast) {
                    float borderX = worldX + 1.0f;
                    boolean rainOnPositive = openEast;
                    float signedPenetration = rainOnPositive ? (float) cam.x - borderX : borderX - (float) cam.x;

                    if (signedPenetration >= 0.0f && signedPenetration < bestAbsPenetration) {
                        bestAbsPenetration = signedPenetration;
                        best = new RainBorderTransition(true, CurtainOrientation.NORTH_SOUTH, rainOnPositive, borderX, signedPenetration);
                    }
                }

                neighbor.set(worldX, centerY, worldZ + 1);
                boolean openSouth = isOpenToSky(world, neighbor);

                if (openHere != openSouth) {
                    float borderZ = worldZ + 1.0f;
                    boolean rainOnPositive = openSouth;
                    float signedPenetration = rainOnPositive ? (float) cam.z - borderZ : borderZ - (float) cam.z;

                    if (signedPenetration >= 0.0f && signedPenetration < bestAbsPenetration) {
                        bestAbsPenetration = signedPenetration;
                        best = new RainBorderTransition(true, CurtainOrientation.EAST_WEST, rainOnPositive, borderZ, signedPenetration);
                    }
                }
            }
        }

        return best;
    }

    private static float computeScreenRainCoverage(RainBorderTransition transition) {
        if (!transition.valid) return 0.0f;
        return Math.min(1.0f, transition.penetration / SCREEN_RAIN_TRANSITION_DISTANCE);
    }

    private static float computePitchStretch(Camera camera) {
        float pitch = Math.abs(camera.getPitch());
        return 1.0f + (pitch / 90.0f) * 2.0f;
    }

    private static void bindDeathRainShader(
            World world,
            Camera camera,
            float worldX,
            float worldY,
            float worldZ,
            float screenCoverage,
            float screenCoverageAxis,
            float screenCoverageFlip
    ) {
        float[] spriteRect = new float[]{0f, 0f, 1f, 1f};
        float[] rippleGold = new float[]{0f, 0f, 0f, 0f};

        float rainDirection = 1.5f;
        float rainEverywhere = 1.0f;
        float rainIntensity = 1.0f;
        float waterLevel = 0.0f;
        float scale = 10.0f;
        float pitchStretch = computePitchStretch(camera);

        CoreShaderRenderer.bindShader$DeathRain(
                spriteRect,
                rippleGold,
                rainDirection,
                rainEverywhere,
                rainIntensity,
                waterLevel,
                scale,
                pitchStretch,
                screenCoverage,
                screenCoverageAxis,
                screenCoverageFlip,
                RAIN_TEXTURE,
                NOISE_TEXTURE,
                LEVEL_TEXTURE,
                null,
                null,
                false,
                false,
                false,
                false
        );

        BlockPos tintPos = BlockPos.ofFloored(worldX, worldY, worldZ);
        int waterColor = BiomeColors.getWaterColor(world, tintPos);

        float red = ((waterColor >> 16) & 0xFF) / 255.0f;
        float green = ((waterColor >> 8) & 0xFF) / 255.0f;
        float blue = (waterColor & 0xFF) / 255.0f;

        RenderSystem.setShaderColor(red, green, blue, 1.0f);
    }

    private static void renderRainCurtainImmediate(
            World world,
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
            bindDeathRainShader(world, camera, worldX, (topY + bottomY) * 0.5f, worldZ, 1.0f, 0.0f, 0.0f);

            int r = 255;
            int g = 255;
            int b = 255;
            int a = 255;

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);

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
                emitCurtainPlaneNS(buffer, m, topY, bottomY, packedLight, r, g, b, a, positiveFacing);
            } else {
                emitCurtainPlaneEW(buffer, m, topY, bottomY, packedLight, r, g, b, a, positiveFacing);
            }

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering rain curtain at "
                    + worldX + ", " + worldZ
                    + " topY=" + topY
                    + " bottomY=" + bottomY
                    + " orientation=" + orientation
                    + " positiveFacing=" + positiveFacing);
            t.printStackTrace();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
        }
    }

    private static void renderScreenRainOverlay(
            World world,
            Camera camera,
            MatrixStack matrices,
            float coverage,
            float coverageAxis,
            float coverageFlip
    ) {
        try {
            Vec3d camPos = camera.getPos();

            bindDeathRainShader(
                    world,
                    camera,
                    (float) camPos.x,
                    (float) camPos.y,
                    (float) camPos.z,
                    coverage,
                    coverageAxis,
                    coverageFlip
            );

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);

            matrices.push();
            matrices.loadIdentity();
            Matrix4f m = matrices.peek().getPositionMatrix();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
            );

            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

            buffer.vertex(m, -1.0f,  1.0f, 0.0f)
                    .color(255, 255, 255, 255)
                    .texture(0f, 0f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            buffer.vertex(m,  1.0f,  1.0f, 0.0f)
                    .color(255, 255, 255, 255)
                    .texture(1f, 0f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            buffer.vertex(m,  1.0f, -1.0f, 0.0f)
                    .color(255, 255, 255, 255)
                    .texture(1f, 1f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            buffer.vertex(m, -1.0f, -1.0f, 0.0f)
                    .color(255, 255, 255, 255)
                    .texture(0f, 1f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering screen rain overlay");
            t.printStackTrace();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
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