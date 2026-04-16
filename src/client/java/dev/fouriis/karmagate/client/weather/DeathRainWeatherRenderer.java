package dev.fouriis.karmagate.client.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.rain.GlobalRain;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

public final class DeathRainWeatherRenderer {

    private static final int RADIUS_BLOCKS = 12;
    private static final int HALF_HEIGHT_BLOCKS = 12;
    private static final float MIN_RENDER_INTENSITY = 0.01f;

    private static final Identifier LEVEL_TEXTURE = Identifier.of("librainworldmc", "grabtex");
    private static final Identifier NOISE_TEXTURE = Identifier.of("librainworldmc", "textures/rainworld/palettes/noise_hq.png");
    private static final Identifier RAIN_TEXTURE = Identifier.of("minecraft", "textures/block/water_flow.png");

    private DeathRainWeatherRenderer() {
    }

    public static void render(ClientWorld world, Camera camera, float tickDelta, MatrixStack matrices) {
        if (world == null || camera == null || matrices == null || !isDeathRainActive()) {
            return;
        }

        float rainIntensity = resolveGlobalRainIntensity();
        if (rainIntensity < MIN_RENDER_INTENSITY) {
            return;
        }

        float rainDirection = resolveGlobalRainDirection();
        int alpha = computeRenderAlpha(rainIntensity);

        Vec3d cameraPos = camera.getPos();
        BlockPos center = BlockPos.ofFloored(cameraPos);
        int radiusSq = RADIUS_BLOCKS * RADIUS_BLOCKS;
        int minY = center.getY() - HALF_HEIGHT_BLOCKS;
        int maxY = center.getY() + HALF_HEIGHT_BLOCKS;

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        bindDeathRainShader(world, camera, center, rainIntensity, rainDirection);
        beginWorldRainSheetState();

        try {
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
            );

            // Faces on x + 1/2 planes, between (x, y, z) and (x + 1, y, z)
            for (int dx = -RADIUS_BLOCKS; dx < RADIUS_BLOCKS; dx++) {
                for (int dz = -RADIUS_BLOCKS; dz <= RADIUS_BLOCKS; dz++) {
                    if (!insideRadius(dx, dz, radiusSq) || !insideRadius(dx + 1, dz, radiusSq)) {
                        continue;
                    }

                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;

                    int y = minY;
                    while (y <= maxY) {
                        int inward = anchoredBorderDirectionEastWest(world, x, y, z);
                        if (inward == 0) {
                            y++;
                            continue;
                        }

                        int startY = y;
                        y++;

                        while (y <= maxY && borderDirectionEastWest(world, x, y, z) == inward) {
                            y++;
                        }

                        emitEastWestQuad(buffer, matrix, x + 1.0, startY, y, z, inward, alpha);
                    }
                }
            }

            // Faces on z + 1/2 planes, between (x, y, z) and (x, y, z + 1)
            for (int dx = -RADIUS_BLOCKS; dx <= RADIUS_BLOCKS; dx++) {
                for (int dz = -RADIUS_BLOCKS; dz < RADIUS_BLOCKS; dz++) {
                    if (!insideRadius(dx, dz, radiusSq) || !insideRadius(dx, dz + 1, radiusSq)) {
                        continue;
                    }

                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;

                    int y = minY;
                    while (y <= maxY) {
                        int inward = anchoredBorderDirectionNorthSouth(world, x, y, z);
                        if (inward == 0) {
                            y++;
                            continue;
                        }

                        int startY = y;
                        y++;

                        while (y <= maxY && borderDirectionNorthSouth(world, x, y, z) == inward) {
                            y++;
                        }

                        emitNorthSouthQuad(buffer, matrix, x, startY, y, z + 1.0, inward, alpha);
                    }
                }
            }

            BuiltBuffer builtBuffer = buffer.endNullable();
            if (builtBuffer != null) {
                BufferRenderer.drawWithGlobalProgram(builtBuffer);
            }
        } finally {
            endWorldRainSheetState();
            matrices.pop();
        }
    }

    private static boolean insideRadius(int dx, int dz, int radiusSq) {
        return dx * dx + dz * dz <= radiusSq;
    }

    /**
     * Returns:
     *  0  = no border
     * -1  = covered side is the west cell  (face inward toward -X)
     * +1  = covered side is the east cell  (face inward toward +X)
     */
    private static int borderDirectionEastWest(ClientWorld world, int x, int y, int z) {
        if (!isAir(world, x, y, z) || !isAir(world, x + 1, y, z)) {
            return 0;
        }

        boolean skyWest = world.isSkyVisible(new BlockPos(x, y, z));
        boolean skyEast = world.isSkyVisible(new BlockPos(x + 1, y, z));

        if (skyWest == skyEast) {
            return 0;
        }

        return skyWest ? 1 : -1;
    }

    /**
     * Returns:
     *  0  = no border
     * -1  = covered side is the north cell (face inward toward -Z)
     * +1  = covered side is the south cell (face inward toward +Z)
     */
    private static int borderDirectionNorthSouth(ClientWorld world, int x, int y, int z) {
        if (!isAir(world, x, y, z) || !isAir(world, x, y, z + 1)) {
            return 0;
        }

        boolean skyNorth = world.isSkyVisible(new BlockPos(x, y, z));
        boolean skySouth = world.isSkyVisible(new BlockPos(x, y, z + 1));

        if (skyNorth == skySouth) {
            return 0;
        }

        return skyNorth ? 1 : -1;
    }

    private static int anchoredBorderDirectionEastWest(ClientWorld world, int x, int y, int z) {
        int inward = borderDirectionEastWest(world, x, y, z);
        if (inward == 0) {
            return 0;
        }

        boolean anchored = !isAir(world, x, y - 1, z) || !isAir(world, x + 1, y - 1, z);
        return anchored ? inward : 0;
    }

    private static int anchoredBorderDirectionNorthSouth(ClientWorld world, int x, int y, int z) {
        int inward = borderDirectionNorthSouth(world, x, y, z);
        if (inward == 0) {
            return 0;
        }

        boolean anchored = !isAir(world, x, y - 1, z) || !isAir(world, x, y - 1, z + 1);
        return anchored ? inward : 0;
    }

    private static boolean isAir(ClientWorld world, int x, int y, int z) {
        if (world.isOutOfHeightLimit(y)) {
            return false;
        }

        BlockPos pos = new BlockPos(x, y, z);
        return !world.getBlockState(pos).isOpaqueFullCube(world, pos);
    }

    private static void emitEastWestQuad(
            BufferBuilder buffer,
            Matrix4f matrix,
            double faceX,
            double y0,
            double y1,
            double z,
            int inward,
            int alpha
    ) {
        if (inward > 0) {
            // Face inward toward +X
            emitQuad(
                    buffer,
                    matrix,
                    faceX, y0, z,
                    faceX, y1, z,
                    faceX, y1, z + 1.0,
                    faceX, y0, z + 1.0,
                    1.0f, 0.0f, 0.0f,
                    alpha
            );
        } else {
            // Face inward toward -X
            emitQuad(
                    buffer,
                    matrix,
                    faceX, y0, z + 1.0,
                    faceX, y1, z + 1.0,
                    faceX, y1, z,
                    faceX, y0, z,
                    -1.0f, 0.0f, 0.0f,
                    alpha
            );
        }
    }

    private static void emitNorthSouthQuad(
            BufferBuilder buffer,
            Matrix4f matrix,
            double x,
            double y0,
            double y1,
            double faceZ,
            int inward,
            int alpha
    ) {
        if (inward > 0) {
            // Face inward toward +Z
            emitQuad(
                    buffer,
                    matrix,
                    x, y0, faceZ,
                    x + 1.0, y0, faceZ,
                    x + 1.0, y1, faceZ,
                    x, y1, faceZ,
                    0.0f, 0.0f, 1.0f,
                    alpha
            );
        } else {
            // Face inward toward -Z
            emitQuad(
                    buffer,
                    matrix,
                    x + 1.0, y0, faceZ,
                    x, y0, faceZ,
                    x, y1, faceZ,
                    x + 1.0, y1, faceZ,
                    0.0f, 0.0f, -1.0f,
                    alpha
            );
        }
    }

    private static void emitQuad(
            BufferBuilder buffer,
            Matrix4f matrix,
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            double x3, double y3, double z3,
            float nx, float ny, float nz,
            int alpha
    ) {
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        buffer.vertex(matrix, (float) x0, (float) y0, (float) z0)
                .color(255, 255, 255, alpha)
                .texture(0.0f, 0.0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);

        buffer.vertex(matrix, (float) x1, (float) y1, (float) z1)
                .color(255, 255, 255, alpha)
                .texture(0.0f, 1.0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);

        buffer.vertex(matrix, (float) x2, (float) y2, (float) z2)
                .color(255, 255, 255, alpha)
                .texture(1.0f, 1.0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);

        buffer.vertex(matrix, (float) x3, (float) y3, (float) z3)
                .color(255, 255, 255, alpha)
                .texture(1.0f, 0.0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);
    }

    private static void beginWorldRainSheetState() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    private static void endWorldRainSheetState() {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    private static void bindDeathRainShader(
            ClientWorld world,
            Camera camera,
            BlockPos tintPos,
            float rainIntensity01,
            float rainDirectionSigned
    ) {
        float[] spriteRect = new float[]{0.0f, 0.0f, 1.0f, 1.0f};
        float[] rippleGold = new float[]{0.0f, 0.0f, 0.0f, 0.0f};

        float rainIntensity = clamp01(rainIntensity01);
        float scale = lerp(12.0f, 8.0f, rainIntensity);
        float pitchStretch = computePitchStretch(camera);

        CoreShaderRenderer.bindShader$DeathRain(
                spriteRect,
                rippleGold,
                rainDirectionSigned,
                1.0f,
                rainIntensity,
                0.0f,
                scale,
                pitchStretch,
                1.0f,
                0.0f,
                0.0f,
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

        int waterColor = BiomeColors.getWaterColor(world, tintPos);
        float red = ((waterColor >> 16) & 0xFF) / 255.0f;
        float green = ((waterColor >> 8) & 0xFF) / 255.0f;
        float blue = (waterColor & 0xFF) / 255.0f;
        RenderSystem.setShaderColor(red, green, blue, 1.0f);
    }

    private static boolean isDeathRainActive() {
        if (GlobalRainClientState.hasSync()) {
            return true;
        }
        return MinecraftClient.getInstance().getServer() != null;
    }

    private static float resolveGlobalRainIntensity() {
        if (GlobalRainClientState.hasSync()) {
            return clamp01(GlobalRainClientState.intensity());
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getServer() != null) {
            return clamp01(GlobalRain.get(client.getServer()).getIntensity());
        }

        return 0.0f;
    }

    private static float resolveGlobalRainDirection() {
        if (GlobalRainClientState.hasSync()) {
            return GlobalRainClientState.rainDirection();
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getServer() != null) {
            return GlobalRain.get(client.getServer()).getRainDirection();
        }

        return 0.0f;
    }

    private static int computeRenderAlpha(float rainIntensity) {
        return (int) (80.0f + 175.0f * clamp01(rainIntensity));
    }

    private static float computePitchStretch(Camera camera) {
        float pitch = Math.abs(camera.getPitch());
        return 1.0f + (pitch / 90.0f) * 2.0f;
    }

    private static float clamp01(float value) {
        return MathHelper.clamp(value, 0.0f, 1.0f);
    }

    private static float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }
}