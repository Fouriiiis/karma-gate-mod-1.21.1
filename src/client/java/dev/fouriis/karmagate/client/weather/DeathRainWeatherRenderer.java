package dev.fouriis.karmagate.client.weather;

import dev.fouriis.karmagate.rain.GlobalRain;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DeathRainWeatherRenderer {

    private static final int RADIUS_BLOCKS = 12;
    private static final int HALF_HEIGHT_BLOCKS = 12;
    private static final float MIN_RENDER_INTENSITY = 0.01f;

    private static final double CYLINDER_RADIUS_BLOCKS = 0.45;
    private static final double CYLINDER_VERTICAL_RANGE_BLOCKS = 1024.0;
    private static final double CYLINDER_TARGET_FACE_WIDTH_BLOCKS = 1.0;
    private static final double CYLINDER_SHADER_HEIGHT_BLOCKS = HALF_HEIGHT_BLOCKS * 2.0;
    private static final double TWO_PI = Math.PI * 2.0;

    private static final double FACE_VISIBILITY_EPSILON = 1.0E-4;
    private static final double CYLINDER_CLIP_EPSILON = 1.0E-7;
    private static final double MIN_CYLINDER_SPAN_LENGTH_SQ = 1.0E-8;

    private static final Identifier MAIN_TEXTURE = Identifier.of("librainworldmc", "atlas_elements/rainworld/rainworld_white");
    private static final Identifier NOISE_TEXTURE = Identifier.of("librainworldmc", "textures/rainworld/palettes/noise_hq.png");
    private static final Identifier GRAB_TEXTURE = Identifier.of("librainworldmc", "grabtex");

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
        float alpha = computeRenderAlpha(rainIntensity);
        int waterColor = BiomeColors.getWaterColor(world, BlockPos.ofFloored(camera.getPos()));
        float red = ((waterColor >> 16) & 0xFF) / 255.0f;
        float green = ((waterColor >> 8) & 0xFF) / 255.0f;
        float blue = (waterColor & 0xFF) / 255.0f;

        Vec3d cameraPos = camera.getPos();
        BlockPos center = BlockPos.ofFloored(cameraPos);
        int radiusSq = RADIUS_BLOCKS * RADIUS_BLOCKS;
        int minY = center.getY() - HALF_HEIGHT_BLOCKS;
        int maxY = center.getY() + HALF_HEIGHT_BLOCKS;

        // Use separate shader binders for east-west and north-south faces.
        // Flip the sign for east-west faces so the shader streaks align
        // with the intended world-space fall direction on those quads.
        RenderUtils.ShaderBinder shaderBinderEW = createDeathRainShaderBinder(rainIntensity, -rainDirection);
        RenderUtils.ShaderBinder shaderBinderNS = createDeathRainShaderBinder(rainIntensity, rainDirection);

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

                    emitEastWestQuad(
                            shaderBinderEW,
                            cameraPos,
                            x + 1.0,
                            startY,
                            y,
                            z,
                            inward,
                            red,
                            green,
                            blue,
                            alpha
                    );
                }
            }
        }

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

                    emitNorthSouthQuad(
                            shaderBinderNS,
                            cameraPos,
                            x,
                            startY,
                            y,
                            z + 1.0,
                            inward,
                            red,
                            green,
                            blue,
                            alpha
                    );
                }
            }
        }

        emitCameraCenteredRainCylinder(
                world,
                cameraPos,
                shaderBinderEW,
                shaderBinderNS,
                red,
                green,
                blue,
                alpha
        );
    }

    private static void emitCameraCenteredRainCylinder(
            ClientWorld world,
            Vec3d cameraPos,
            RenderUtils.ShaderBinder shaderBinderEW,
            RenderUtils.ShaderBinder shaderBinderNS,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        int sampleY = MathHelper.floor(cameraPos.y);

        if (world.isOutOfHeightLimit(sampleY)) {
            return;
        }

        int segmentCount = computeCylinderSegmentCount();

        double yMin = cameraPos.y - CYLINDER_VERTICAL_RANGE_BLOCKS;
        double yMax = cameraPos.y + CYLINDER_VERTICAL_RANGE_BLOCKS;

        for (int segment = 0; segment < segmentCount; segment++) {
            double angle0 = TWO_PI * segment / segmentCount;
            double angle1 = TWO_PI * (segment + 1) / segmentCount;

            double x0 = cameraPos.x + Math.cos(angle0) * CYLINDER_RADIUS_BLOCKS;
            double z0 = cameraPos.z + Math.sin(angle0) * CYLINDER_RADIUS_BLOCKS;
            double x1 = cameraPos.x + Math.cos(angle1) * CYLINDER_RADIUS_BLOCKS;
            double z1 = cameraPos.z + Math.sin(angle1) * CYLINDER_RADIUS_BLOCKS;

            emitClippedCylinderSegment(
                    world,
                    sampleY,
                    shaderBinderEW,
                    shaderBinderNS,
                    x0,
                    z0,
                    x1,
                    z1,
                    yMin,
                    yMax,
                    red,
                    green,
                    blue,
                    alpha
            );
        }
    }

    private static void emitClippedCylinderSegment(
            ClientWorld world,
            int sampleY,
            RenderUtils.ShaderBinder shaderBinderEW,
            RenderUtils.ShaderBinder shaderBinderNS,
            double x0,
            double z0,
            double x1,
            double z1,
            double yMin,
            double yMax,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        List<Double> splitPoints = new ArrayList<>();
        splitPoints.add(0.0);
        splitPoints.add(1.0);

        addBlockGridIntersections(splitPoints, x0, x1);
        addBlockGridIntersections(splitPoints, z0, z1);

        Collections.sort(splitPoints);

        double previousT = splitPoints.get(0);

        for (int i = 1; i < splitPoints.size(); i++) {
            double currentT = splitPoints.get(i);

            if (currentT - previousT <= CYLINDER_CLIP_EPSILON) {
                continue;
            }

            double midT = (previousT + currentT) * 0.5;
            double sampleX = lerp(midT, x0, x1);
            double sampleZ = lerp(midT, z0, z1);

            if (shouldRenderRainCylinderSpan(world, sampleX, sampleY, sampleZ)) {
                double clippedX0 = lerp(previousT, x0, x1);
                double clippedZ0 = lerp(previousT, z0, z1);
                double clippedX1 = lerp(currentT, x0, x1);
                double clippedZ1 = lerp(currentT, z0, z1);

                if (squaredHorizontalDistance(clippedX0, clippedZ0, clippedX1, clippedZ1) >= MIN_CYLINDER_SPAN_LENGTH_SQ) {
                    RenderUtils.ShaderBinder segmentBinder =
                            Math.abs(clippedZ1 - clippedZ0) >= Math.abs(clippedX1 - clippedX0)
                                    ? shaderBinderEW
                                    : shaderBinderNS;

                    for (double chunkY0 = yMin; chunkY0 < yMax; chunkY0 += CYLINDER_SHADER_HEIGHT_BLOCKS) {
                        double chunkY1 = Math.min(chunkY0 + CYLINDER_SHADER_HEIGHT_BLOCKS, yMax);

                        emitCylinderSpan(
                                segmentBinder,
                                clippedX0,
                                clippedZ0,
                                clippedX1,
                                clippedZ1,
                                chunkY0,
                                chunkY1,
                                red,
                                green,
                                blue,
                                alpha
                        );
                    }
                }
            }

            previousT = currentT;
        }
    }

    private static void addBlockGridIntersections(List<Double> splitPoints, double start, double end) {
        double delta = end - start;

        if (Math.abs(delta) <= CYLINDER_CLIP_EPSILON) {
            return;
        }

        double min = Math.min(start, end);
        double max = Math.max(start, end);

        int firstGridLine = MathHelper.floor(min) + 1;
        int lastGridLine = MathHelper.floor(max);

        for (int gridLine = firstGridLine; gridLine <= lastGridLine; gridLine++) {
            double t = (gridLine - start) / delta;

            if (t > CYLINDER_CLIP_EPSILON && t < 1.0 - CYLINDER_CLIP_EPSILON) {
                splitPoints.add(t);
            }
        }
    }

    private static double squaredHorizontalDistance(double x0, double z0, double x1, double z1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        return dx * dx + dz * dz;
    }

    private static double lerp(double t, double start, double end) {
        return start + (end - start) * t;
    }

    private static int computeCylinderSegmentCount() {
        int segmentCount = Math.max(
                8,
                (int) Math.ceil(TWO_PI * CYLINDER_RADIUS_BLOCKS / CYLINDER_TARGET_FACE_WIDTH_BLOCKS)
        );

        // Keep this even so standing directly on a straight border can split the cylinder cleanly.
        if ((segmentCount & 1) != 0) {
            segmentCount++;
        }

        return segmentCount;
    }

    private static boolean shouldRenderRainCylinderSpan(ClientWorld world, double x, int y, double z) {
        int blockX = MathHelper.floor(x);
        int blockZ = MathHelper.floor(z);

        if (!isAir(world, blockX, y, blockZ)) {
            return false;
        }

        return world.isSkyVisible(new BlockPos(blockX, y, blockZ));
    }

    private static void emitCylinderSpan(
            RenderUtils.ShaderBinder shaderBinder,
            double x0,
            double z0,
            double x1,
            double z1,
            double y0,
            double y1,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        // Reversed winding points the cylinder inward, toward the camera.
        emitQuad(
                shaderBinder,
                new Vec3d(x1, y0, z1),
                new Vec3d(x0, y0, z0),
                new Vec3d(x0, y1, z0),
                new Vec3d(x1, y1, z1),
                red,
                green,
                blue,
                alpha
        );
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
            RenderUtils.ShaderBinder shaderBinder,
            Vec3d cameraPos,
            double faceX,
            double y0,
            double y1,
            double z,
            int inward,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        if (!isCameraOnEastWestInwardSide(cameraPos, faceX, inward)) {
            return;
        }

        if (inward > 0) {
            // Inward side is +X.
            // Arrange vertices so texture U runs along +Z and V runs along +Y.
            emitQuad(
                    shaderBinder,
                    new Vec3d(faceX, y0, z),
                    new Vec3d(faceX, y0, z + 1.0),
                    new Vec3d(faceX, y1, z + 1.0),
                    new Vec3d(faceX, y1, z),
                    red,
                    green,
                    blue,
                    alpha
            );
        } else {
            // Inward side is -X.
            emitQuad(
                    shaderBinder,
                    new Vec3d(faceX, y0, z + 1.0),
                    new Vec3d(faceX, y0, z),
                    new Vec3d(faceX, y1, z),
                    new Vec3d(faceX, y1, z + 1.0),
                    red,
                    green,
                    blue,
                    alpha
            );
        }
    }

    private static void emitNorthSouthQuad(
            RenderUtils.ShaderBinder shaderBinder,
            Vec3d cameraPos,
            double x,
            double y0,
            double y1,
            double faceZ,
            int inward,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        if (!isCameraOnNorthSouthInwardSide(cameraPos, faceZ, inward)) {
            return;
        }

        if (inward > 0) {
            // Inward side is +Z.
            emitQuad(
                    shaderBinder,
                    new Vec3d(x, y0, faceZ),
                    new Vec3d(x + 1.0, y0, faceZ),
                    new Vec3d(x + 1.0, y1, faceZ),
                    new Vec3d(x, y1, faceZ),
                    red,
                    green,
                    blue,
                    alpha
            );
        } else {
            // Inward side is -Z.
            emitQuad(
                    shaderBinder,
                    new Vec3d(x + 1.0, y0, faceZ),
                    new Vec3d(x, y0, faceZ),
                    new Vec3d(x, y1, faceZ),
                    new Vec3d(x + 1.0, y1, faceZ),
                    red,
                    green,
                    blue,
                    alpha
            );
        }
    }

    private static boolean isCameraOnEastWestInwardSide(Vec3d cameraPos, double faceX, int inward) {
        if (inward > 0) {
            return cameraPos.x >= faceX - FACE_VISIBILITY_EPSILON;
        }

        return cameraPos.x <= faceX + FACE_VISIBILITY_EPSILON;
    }

    private static boolean isCameraOnNorthSouthInwardSide(Vec3d cameraPos, double faceZ, int inward) {
        if (inward > 0) {
            return cameraPos.z >= faceZ - FACE_VISIBILITY_EPSILON;
        }

        return cameraPos.z <= faceZ + FACE_VISIBILITY_EPSILON;
    }

    private static void emitQuad(
            RenderUtils.ShaderBinder shaderBinder,
            Vec3d a,
            Vec3d b,
            Vec3d c,
            Vec3d d,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        RenderUtils.drawQuad3D(shaderBinder, a, b, c, d, red, green, blue, alpha);
    }

    private static RenderUtils.ShaderBinder createDeathRainShaderBinder(float rainIntensity01, float rainDirectionSigned) {
        return () -> bindDeathRainShader(rainIntensity01, rainDirectionSigned);
    }

    private static void bindDeathRainShader(float rainIntensity01, float rainDirectionSigned) {
        float[] spriteRect = new float[]{0.0f, 0.0f, 1.0f, 1.0f};
        float rainIntensity = clamp01(rainIntensity01);

        CoreShaderRenderer.bindShader$DeathRain(
                spriteRect,
                0.0f,
                new float[]{rainDirectionSigned},
                0.0f,
                rainIntensity,
                1.0f,
                MAIN_TEXTURE,
                NOISE_TEXTURE,
                GRAB_TEXTURE,
                GRAB_TEXTURE,
                GRAB_TEXTURE,
                false,
                false,
                false,
                false
        );
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

    private static float computeRenderAlpha(float rainIntensity) {
        return (80.0f + 175.0f * clamp01(rainIntensity)) / 255.0f;
    }

    private static float clamp01(float value) {
        return MathHelper.clamp(value, 0.0f, 1.0f);
    }

}