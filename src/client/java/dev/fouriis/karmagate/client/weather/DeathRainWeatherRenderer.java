package dev.fouriis.karmagate.client.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.rain.GlobalRain;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.joml.Matrix4f;

public final class DeathRainWeatherRenderer {

    private static final int RENDER_RADIUS = 12;
    private static final float FACE_EPSILON = 0.001f;
    private static final float MIN_RENDER_INTENSITY = 0.01f;
    private static final float SCREEN_RAIN_TRANSITION_DISTANCE = 1.25f;
    private static final float MAX_CURTAIN_LEAN = 3.0f;
    private static final float MIN_START_HEIGHT_ABOVE_FLOOR = 0.05f;
    private static final float MIN_COVER_THICKNESS = 0.05f;
    private static final double JOIN_EPSILON = 1.0e-4;

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

        float rainIntensity = resolveGlobalRainIntensity();
        if (rainIntensity < MIN_RENDER_INTENSITY) {
            return;
        }

        float rainDirection = resolveGlobalRainDirection();
        RainBorderTransition transition = findNearestRainBorderTransition(world, camera);

        renderBorderVolumes(world, camera, matrices, rainIntensity, rainDirection);

        float coverage = computeScreenRainCoverage(transition);
        if (coverage > 0.0f) {
            float flip = transition.rainOnPositiveSide() ? 0.0f : 1.0f;
            renderScreenRainOverlay(world, camera, matrices, coverage, 0.0f, flip, rainIntensity, rainDirection);
        }
    }

    private static void renderBorderVolumes(
            World world,
            Camera camera,
            MatrixStack matrices,
            float rainIntensity,
            float rainDirection
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        BlockPos anchor = client.player.getBlockPos();
        int playerY = anchor.getY();

        BlockPos.Mutable here = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();
        Vec3d rainVector = resolveHorizontalRainVector(rainDirection);

        for (int dz = -RENDER_RADIUS; dz <= RENDER_RADIUS; dz++) {
            for (int dx = -RENDER_RADIUS; dx <= RENDER_RADIUS; dx++) {
                int worldX = anchor.getX() + dx;
                int worldZ = anchor.getZ() + dz;

                here.set(worldX, playerY, worldZ);
                boolean openHere = isOpenToSky(world, here);

                neighbor.set(worldX + 1, playerY, worldZ);
                boolean openEast = isOpenToSky(world, neighbor);
                if (openHere != openEast) {
                    int dryX = openHere ? worldX + 1 : worldX;
                    int dryZ = worldZ;
                    double borderX = worldX + 1.0 + (openHere ? FACE_EPSILON : -FACE_EPSILON);
                    Vec3d borderNormal = openHere ? new Vec3d(-1.0, 0.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);

                    ProjectedQuad current = buildProjectedBorderQuad(
                            world,
                            playerY,
                            dryX,
                            dryZ,
                            new Vec3d(borderX, 0.0, worldZ),
                            new Vec3d(borderX, 0.0, worldZ + 1.0),
                            borderNormal,
                            rainIntensity,
                            rainVector
                    );

                    if (current != null) {
                        ProjectedQuad sourceCap = buildSourceCapQuad(
                                world,
                                playerY,
                                dryX,
                                dryZ,
                                new Vec3d(borderX, 0.0, worldZ),
                                new Vec3d(borderX, 0.0, worldZ + 1.0),
                                borderNormal,
                                rainIntensity,
                                rainVector
                        );

                        if (sourceCap != null) {
                            renderQuadImmediate(world, camera, matrices, sourceCap.a(), sourceCap.b(), sourceCap.c(), sourceCap.d(), rainIntensity, rainDirection);
                        }

                        renderQuadImmediate(world, camera, matrices, current.a(), current.b(), current.c(), current.d(), rainIntensity, rainDirection);

                        ProjectedQuad next = buildNorthSouthNeighborQuad(
                                world,
                                playerY,
                                worldX,
                                worldZ + 1,
                                borderNormal,
                                rainIntensity,
                                rainVector
                        );

                        if (next != null) {
                            renderJoinIfNeeded(world, camera, matrices, current.b(), next.a(), next.d(), current.c(), rainIntensity, rainDirection);
                        }

                        renderNorthSouthCornerSeams(
                                world,
                                camera,
                                matrices,
                                playerY,
                                dryX,
                                dryZ,
                                borderNormal,
                                current,
                                rainIntensity,
                                rainDirection,
                                rainVector
                        );
                    }
                }

                neighbor.set(worldX, playerY, worldZ + 1);
                boolean openSouth = isOpenToSky(world, neighbor);
                if (openHere != openSouth) {
                    int dryX = worldX;
                    int dryZ = openHere ? worldZ + 1 : worldZ;
                    double borderZ = worldZ + 1.0 + (openHere ? FACE_EPSILON : -FACE_EPSILON);
                    Vec3d borderNormal = openHere ? new Vec3d(0.0, 0.0, -1.0) : new Vec3d(0.0, 0.0, 1.0);

                    ProjectedQuad current = buildProjectedBorderQuad(
                            world,
                            playerY,
                            dryX,
                            dryZ,
                            new Vec3d(worldX, 0.0, borderZ),
                            new Vec3d(worldX + 1.0, 0.0, borderZ),
                            borderNormal,
                            rainIntensity,
                            rainVector
                    );

                    if (current != null) {
                        ProjectedQuad sourceCap = buildSourceCapQuad(
                                world,
                                playerY,
                                dryX,
                                dryZ,
                                new Vec3d(worldX, 0.0, borderZ),
                                new Vec3d(worldX + 1.0, 0.0, borderZ),
                                borderNormal,
                                rainIntensity,
                                rainVector
                        );

                        if (sourceCap != null) {
                            renderQuadImmediate(world, camera, matrices, sourceCap.a(), sourceCap.b(), sourceCap.c(), sourceCap.d(), rainIntensity, rainDirection);
                        }

                        renderQuadImmediate(world, camera, matrices, current.a(), current.b(), current.c(), current.d(), rainIntensity, rainDirection);

                        ProjectedQuad next = buildEastWestNeighborQuad(
                                world,
                                playerY,
                                worldX + 1,
                                worldZ,
                                borderNormal,
                                rainIntensity,
                                rainVector
                        );

                        if (next != null) {
                            renderJoinIfNeeded(world, camera, matrices, current.b(), next.a(), next.d(), current.c(), rainIntensity, rainDirection);
                        }
                    }
                }
            }
        }
    }

    private static void renderNorthSouthCornerSeams(
            World world,
            Camera camera,
            MatrixStack matrices,
            int playerY,
            int dryX,
            int dryZ,
            Vec3d borderNormal,
            ProjectedQuad current,
            float rainIntensity,
            float rainDirection,
            Vec3d rainVector
    ) {
        boolean westFace = borderNormal.x < 0.0;
        DryFace primaryFace = westFace ? DryFace.WEST : DryFace.EAST;

        boolean continuesNorth = hasOpenNeighborOnFace(world, playerY, dryX, dryZ - 1, primaryFace);
        if (!continuesNorth) {
            ProjectedQuad north = buildFaceProjectedQuad(
                    world,
                    playerY,
                    dryX,
                    dryZ,
                    DryFace.NORTH,
                    rainIntensity,
                    rainVector
            );

            if (north != null && shouldRenderCornerSeam(world, playerY, dryX, dryZ, primaryFace, DryFace.NORTH)) {
                Vec3d northTop = westFace ? north.a() : north.b();
                Vec3d northBottom = westFace ? north.d() : north.c();
                renderJoinIfNeeded(world, camera, matrices, current.a(), northTop, northBottom, current.d(), rainIntensity, rainDirection);
            }
        }

        boolean continuesSouth = hasOpenNeighborOnFace(world, playerY, dryX, dryZ + 1, primaryFace);
        if (continuesSouth) {
            return;
        }

        ProjectedQuad south = buildFaceProjectedQuad(
                world,
                playerY,
                dryX,
                dryZ,
                DryFace.SOUTH,
                rainIntensity,
                rainVector
        );

        if (south != null && shouldRenderCornerSeam(world, playerY, dryX, dryZ, primaryFace, DryFace.SOUTH)) {
            Vec3d southTop = westFace ? south.a() : south.b();
            Vec3d southBottom = westFace ? south.d() : south.c();
            renderJoinIfNeeded(world, camera, matrices, current.b(), southTop, southBottom, current.c(), rainIntensity, rainDirection);
        }
    }

    private static boolean shouldRenderCornerSeam(
            World world,
            int playerY,
            int dryX,
            int dryZ,
            DryFace primaryFace,
            DryFace secondaryFace
    ) {
        int diagonalX = dryX + faceStepX(primaryFace) + faceStepX(secondaryFace);
        int diagonalZ = dryZ + faceStepZ(primaryFace) + faceStepZ(secondaryFace);
        return isOpenToSky(world, new BlockPos(diagonalX, playerY, diagonalZ));
    }

    private static int faceStepX(DryFace face) {
        return switch (face) {
            case WEST -> -1;
            case EAST -> 1;
            case NORTH, SOUTH -> 0;
        };
    }

    private static int faceStepZ(DryFace face) {
        return switch (face) {
            case NORTH -> -1;
            case SOUTH -> 1;
            case WEST, EAST -> 0;
        };
    }

    private static ProjectedQuad buildFaceProjectedQuad(
            World world,
            int playerY,
            int dryX,
            int dryZ,
            DryFace face,
            float rainIntensity,
            Vec3d rainVector
    ) {
        if (!hasOpenNeighborOnFace(world, playerY, dryX, dryZ, face)) {
            return null;
        }

        Vec3d borderNormal;
        Vec3d edgeStart;
        Vec3d edgeEnd;

        switch (face) {
            case WEST -> {
                double x = dryX + FACE_EPSILON;
                borderNormal = new Vec3d(-1.0, 0.0, 0.0);
                edgeStart = new Vec3d(x, 0.0, dryZ);
                edgeEnd = new Vec3d(x, 0.0, dryZ + 1.0);
            }
            case EAST -> {
                double x = dryX + 1.0 - FACE_EPSILON;
                borderNormal = new Vec3d(1.0, 0.0, 0.0);
                edgeStart = new Vec3d(x, 0.0, dryZ);
                edgeEnd = new Vec3d(x, 0.0, dryZ + 1.0);
            }
            case NORTH -> {
                double z = dryZ + FACE_EPSILON;
                borderNormal = new Vec3d(0.0, 0.0, -1.0);
                edgeStart = new Vec3d(dryX, 0.0, z);
                edgeEnd = new Vec3d(dryX + 1.0, 0.0, z);
            }
            case SOUTH -> {
                double z = dryZ + 1.0 - FACE_EPSILON;
                borderNormal = new Vec3d(0.0, 0.0, 1.0);
                edgeStart = new Vec3d(dryX, 0.0, z);
                edgeEnd = new Vec3d(dryX + 1.0, 0.0, z);
            }
            default -> {
                return null;
            }
        }

        return buildProjectedBorderQuad(
                world,
                playerY,
                dryX,
                dryZ,
                edgeStart,
                edgeEnd,
                borderNormal,
                rainIntensity,
                rainVector
        );
    }

    private static boolean hasOpenNeighborOnFace(World world, int playerY, int dryX, int dryZ, DryFace face) {
        BlockPos dryPos = new BlockPos(dryX, playerY, dryZ);
        if (isOpenToSky(world, dryPos)) {
            return false;
        }

        BlockPos neighborPos = switch (face) {
            case WEST -> new BlockPos(dryX - 1, playerY, dryZ);
            case EAST -> new BlockPos(dryX + 1, playerY, dryZ);
            case NORTH -> new BlockPos(dryX, playerY, dryZ - 1);
            case SOUTH -> new BlockPos(dryX, playerY, dryZ + 1);
        };

        return isOpenToSky(world, neighborPos);
    }

    private static ProjectedQuad buildProjectedBorderQuad(
            World world,
            int playerY,
            int dryX,
            int dryZ,
            Vec3d footprintEdgeStart,
            Vec3d footprintEdgeEnd,
            Vec3d borderNormal,
            float rainIntensity,
            Vec3d rainVector
    ) {
        CoverSpan cover = getCoverSpan(world, dryX, dryZ, playerY);
        if (cover == null) {
            return null;
        }

        float floorY = getDryFloorTopY(world, dryX, dryZ, playerY);
        float edgeStartY = chooseSourceEdgeY(cover, dryX, dryZ, footprintEdgeStart, footprintEdgeEnd, rainVector);
        float startY0 = edgeStartY;
        float startY1 = edgeStartY;

        if (startY0 <= floorY + MIN_START_HEIGHT_ABOVE_FLOOR
                && startY1 <= floorY + MIN_START_HEIGHT_ABOVE_FLOOR) {
            return null;
        }

        Vec3d projectedOffset0 = computeProjectedOffset(rainVector, startY0 - floorY, rainIntensity);
        Vec3d projectedOffset1 = computeProjectedOffset(rainVector, startY1 - floorY, rainIntensity);

        Vec3d a = footprintEdgeStart.add(0.0, startY0, 0.0);
        Vec3d b = footprintEdgeEnd.add(0.0, startY1, 0.0);
        Vec3d c = footprintEdgeEnd.add(projectedOffset1).add(0.0, floorY, 0.0);
        Vec3d d = footprintEdgeStart.add(projectedOffset0).add(0.0, floorY, 0.0);
        return new ProjectedQuad(a, b, c, d);
    }

    private static ProjectedQuad buildSourceCapQuad(
            World world,
            int playerY,
            int dryX,
            int dryZ,
            Vec3d footprintEdgeStart,
            Vec3d footprintEdgeEnd,
            Vec3d borderNormal,
            float rainIntensity,
            Vec3d rainVector
    ) {
        CoverSpan cover = getCoverSpan(world, dryX, dryZ, playerY);
        if (cover == null) {
            return null;
        }

        if (cover.topY() - cover.undersideY() <= MIN_COVER_THICKNESS) {
            return null;
        }

        float floorY = getDryFloorTopY(world, dryX, dryZ, playerY);
        float edgeStartY = chooseSourceEdgeY(cover, dryX, dryZ, footprintEdgeStart, footprintEdgeEnd, rainVector);
        float startY0 = edgeStartY;
        float startY1 = edgeStartY;

        if (startY0 <= floorY + MIN_START_HEIGHT_ABOVE_FLOOR
                && startY1 <= floorY + MIN_START_HEIGHT_ABOVE_FLOOR) {
            return null;
        }

        boolean needsCap0 = Math.abs(startY0 - cover.undersideY()) <= 1.0e-4f;
        boolean needsCap1 = Math.abs(startY1 - cover.undersideY()) <= 1.0e-4f;
        if (!needsCap0 && !needsCap1) {
            return null;
        }

        Vec3d a = footprintEdgeStart.add(0.0, cover.topY(), 0.0);
        Vec3d b = footprintEdgeEnd.add(0.0, cover.topY(), 0.0);
        Vec3d c = footprintEdgeEnd.add(0.0, startY1, 0.0);
        Vec3d d = footprintEdgeStart.add(0.0, startY0, 0.0);
        return new ProjectedQuad(a, b, c, d);
    }

    private static ProjectedQuad buildNorthSouthNeighborQuad(
            World world,
            int playerY,
            int worldX,
            int worldZ,
            Vec3d expectedBorderNormal,
            float rainIntensity,
            Vec3d rainVector
    ) {
        BlockPos.Mutable here = new BlockPos.Mutable(worldX, playerY, worldZ);
        BlockPos.Mutable east = new BlockPos.Mutable(worldX + 1, playerY, worldZ);

        boolean openHere = isOpenToSky(world, here);
        boolean openEast = isOpenToSky(world, east);
        if (openHere == openEast) {
            return null;
        }

        Vec3d borderNormal = openHere ? new Vec3d(-1.0, 0.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);
        if (!sameDirection(borderNormal, expectedBorderNormal)) {
            return null;
        }

        int dryX = openHere ? worldX + 1 : worldX;
        int dryZ = worldZ;
        double borderX = worldX + 1.0 + (openHere ? FACE_EPSILON : -FACE_EPSILON);

        return buildProjectedBorderQuad(
                world,
                playerY,
                dryX,
                dryZ,
                new Vec3d(borderX, 0.0, worldZ),
                new Vec3d(borderX, 0.0, worldZ + 1.0),
                borderNormal,
                rainIntensity,
                rainVector
        );
    }

    private static ProjectedQuad buildEastWestNeighborQuad(
            World world,
            int playerY,
            int worldX,
            int worldZ,
            Vec3d expectedBorderNormal,
            float rainIntensity,
            Vec3d rainVector
    ) {
        BlockPos.Mutable here = new BlockPos.Mutable(worldX, playerY, worldZ);
        BlockPos.Mutable south = new BlockPos.Mutable(worldX, playerY, worldZ + 1);

        boolean openHere = isOpenToSky(world, here);
        boolean openSouth = isOpenToSky(world, south);
        if (openHere == openSouth) {
            return null;
        }

        Vec3d borderNormal = openHere ? new Vec3d(0.0, 0.0, -1.0) : new Vec3d(0.0, 0.0, 1.0);
        if (!sameDirection(borderNormal, expectedBorderNormal)) {
            return null;
        }

        int dryX = worldX;
        int dryZ = openHere ? worldZ + 1 : worldZ;
        double borderZ = worldZ + 1.0 + (openHere ? FACE_EPSILON : -FACE_EPSILON);

        return buildProjectedBorderQuad(
                world,
                playerY,
                dryX,
                dryZ,
                new Vec3d(worldX, 0.0, borderZ),
                new Vec3d(worldX + 1.0, 0.0, borderZ),
                borderNormal,
                rainIntensity,
                rainVector
        );
    }

    private static void renderJoinIfNeeded(
            World world,
            Camera camera,
            MatrixStack matrices,
            Vec3d currentTop,
            Vec3d nextTop,
            Vec3d nextBottom,
            Vec3d currentBottom,
            float rainIntensity,
            float rainDirection
    ) {
        if (pointsMatch(currentTop, nextTop) && pointsMatch(currentBottom, nextBottom)) {
            return;
        }

        renderQuadImmediate(
                world,
                camera,
                matrices,
                currentTop,
                nextTop,
                nextBottom,
                currentBottom,
                rainIntensity,
                rainDirection
        );
    }

    private static float chooseSourceEdgeY(
            CoverSpan cover,
            int dryX,
            int dryZ,
            Vec3d edgeStart,
            Vec3d edgeEnd,
            Vec3d rainVector
    ) {
        if (cover.topY() - cover.undersideY() <= MIN_COVER_THICKNESS) {
            return cover.topY();
        }

        double centerX = dryX + 0.5;
        double centerZ = dryZ + 0.5;
        double midX = (edgeStart.x + edgeEnd.x) * 0.5;
        double midZ = (edgeStart.z + edgeEnd.z) * 0.5;
        double dx = midX - centerX;
        double dz = midZ - centerZ;
        double horizontalDot = dx * rainVector.x + dz * rainVector.z;
        return horizontalDot >= 0.0 ? cover.topY() : cover.undersideY();
    }

    private static Vec3d computeProjectedOffset(Vec3d rainVector, float height, float rainIntensity) {
        if (height <= 0.0f || rainVector.lengthSquared() < 1.0e-6) {
            return Vec3d.ZERO;
        }

        float leanPerBlock = lerp(0.16f, 0.30f, clamp01(rainIntensity));
        Vec3d offset = rainVector.multiply(height * leanPerBlock);

        double length = offset.length();
        if (length > MAX_CURTAIN_LEAN) {
            offset = offset.multiply(MAX_CURTAIN_LEAN / length);
        }

        return offset;
    }

    private static Vec3d resolveHorizontalRainVector(float rainDirection) {
        float diagonal = MathHelper.clamp(rainDirection, -1.0f, 1.0f);
        double axisComponent = diagonal * 0.7071067811865476;
        return new Vec3d(axisComponent, 0.0, axisComponent);
    }

    private static boolean sameDirection(Vec3d a, Vec3d b) {
        return a.squaredDistanceTo(b) <= JOIN_EPSILON;
    }

    private static boolean pointsMatch(Vec3d a, Vec3d b) {
        return a.squaredDistanceTo(b) <= JOIN_EPSILON;
    }

    private static void renderQuadImmediate(
            World world,
            Camera camera,
            MatrixStack matrices,
            Vec3d a,
            Vec3d b,
            Vec3d c,
            Vec3d d,
            float rainIntensity,
            float rainDirection
    ) {
        try {
            float centerX = (float) ((a.x + b.x + c.x + d.x) * 0.25);
            float centerY = (float) ((a.y + b.y + c.y + d.y) * 0.25);
            float centerZ = (float) ((a.z + b.z + c.z + d.z) * 0.25);

            bindDeathRainShader(
                    world,
                    camera,
                    centerX,
                    centerY,
                    centerZ,
                    1.0f,
                    0.0f,
                    0.0f,
                    rainIntensity,
                    rainDirection
            );

            beginWorldRainSheetState();

            matrices.push();
            Vec3d cam = camera.getPos();
            matrices.translate(-cam.x, -cam.y, -cam.z);
            Matrix4f matrix = matrices.peek().getPositionMatrix();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
            );

            emitQuad(
                    buffer,
                    matrix,
                    a,
                    b,
                    c,
                    d,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE,
                    255,
                    255,
                    255,
                    computeRenderAlpha(rainIntensity)
            );

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();
            endWorldRainSheetState();
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering rain border quad");
            t.printStackTrace();
            endWorldRainSheetState();
        }
    }

    private static void emitQuad(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3d a,
            Vec3d b,
            Vec3d c,
            Vec3d d,
            int light,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        Vec3d normal = b.subtract(a).crossProduct(d.subtract(a));
        if (normal.lengthSquared() < 1.0e-6) {
            normal = new Vec3d(0.0, 1.0, 0.0);
        } else {
            normal = normal.normalize();
        }

        float nx = (float) normal.x;
        float ny = (float) normal.y;
        float nz = (float) normal.z;

        buffer.vertex(matrix, (float) a.x, (float) a.y, (float) a.z)
                .color(red, green, blue, alpha)
                .texture(0.0f, 0.0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);

        buffer.vertex(matrix, (float) b.x, (float) b.y, (float) b.z)
                .color(red, green, blue, alpha)
                .texture(1.0f, 0.0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);

        buffer.vertex(matrix, (float) c.x, (float) c.y, (float) c.z)
                .color(red, green, blue, alpha)
                .texture(1.0f, 1.0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);

        buffer.vertex(matrix, (float) d.x, (float) d.y, (float) d.z)
                .color(red, green, blue, alpha)
                .texture(0.0f, 1.0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);
    }

    private enum BorderAxis {
        X,
        Z
    }

    private enum DryFace {
        WEST,
        EAST,
        NORTH,
        SOUTH
    }

    private record CoverSpan(float undersideY, float topY) {
    }

    private record ProjectedQuad(Vec3d a, Vec3d b, Vec3d c, Vec3d d) {
    }

    private record RainBorderTransition(boolean valid, BorderAxis axis, boolean rainOnPositiveSide, float penetration) {
    }

    private static RainBorderTransition findNearestRainBorderTransition(World world, Camera camera) {
        Vec3d cam = camera.getPos();
        BlockPos camBlock = BlockPos.ofFloored(cam);

        int centerX = camBlock.getX();
        int centerY = camBlock.getY();
        int centerZ = camBlock.getZ();

        BlockPos.Mutable here = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();

        float bestPenetration = Float.MAX_VALUE;
        RainBorderTransition best = new RainBorderTransition(false, BorderAxis.X, false, 0.0f);

        for (int dz = -RENDER_RADIUS; dz <= RENDER_RADIUS; dz++) {
            for (int dx = -RENDER_RADIUS; dx <= RENDER_RADIUS; dx++) {
                int worldX = centerX + dx;
                int worldZ = centerZ + dz;

                here.set(worldX, centerY, worldZ);
                boolean openHere = isOpenToSky(world, here);

                neighbor.set(worldX + 1, centerY, worldZ);
                boolean openEast = isOpenToSky(world, neighbor);
                if (openHere != openEast) {
                    float borderX = worldX + 1.0f;
                    boolean rainOnPositive = openEast;
                    float penetration = rainOnPositive ? (float) cam.x - borderX : borderX - (float) cam.x;
                    if (penetration >= 0.0f && penetration < bestPenetration) {
                        bestPenetration = penetration;
                        best = new RainBorderTransition(true, BorderAxis.X, rainOnPositive, penetration);
                    }
                }

                neighbor.set(worldX, centerY, worldZ + 1);
                boolean openSouth = isOpenToSky(world, neighbor);
                if (openHere != openSouth) {
                    float borderZ = worldZ + 1.0f;
                    boolean rainOnPositive = openSouth;
                    float penetration = rainOnPositive ? (float) cam.z - borderZ : borderZ - (float) cam.z;
                    if (penetration >= 0.0f && penetration < bestPenetration) {
                        bestPenetration = penetration;
                        best = new RainBorderTransition(true, BorderAxis.Z, rainOnPositive, penetration);
                    }
                }
            }
        }

        return best;
    }

    private static float computeScreenRainCoverage(RainBorderTransition transition) {
        if (!transition.valid()) {
            return 0.0f;
        }
        return Math.min(1.0f, transition.penetration() / SCREEN_RAIN_TRANSITION_DISTANCE);
    }

    private static void beginWorldRainSheetState() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    private static void endWorldRainSheetState() {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    private static void bindDeathRainShader(
            World world,
            Camera camera,
            float worldX,
            float worldY,
            float worldZ,
            float screenCoverage,
            float screenCoverageAxis,
            float screenCoverageFlip,
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

    private static float computePitchStretch(Camera camera) {
        float pitch = Math.abs(camera.getPitch());
        return 1.0f + (pitch / 90.0f) * 2.0f;
    }

    private static void renderScreenRainOverlay(
            World world,
            Camera camera,
            MatrixStack matrices,
            float coverage,
            float coverageAxis,
            float coverageFlip,
            float rainIntensity,
            float rainDirection
    ) {
        try {
            Vec3d camPos = camera.getPos();

            bindDeathRainShader(
                    world,
                    camera,
                    (float) camPos.x,
                    (float) camPos.y,
                    (float) camPos.z,
                    coverage * rainIntensity,
                    coverageAxis,
                    coverageFlip,
                    rainIntensity,
                    rainDirection
            );

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);

            matrices.push();
            matrices.loadIdentity();
            Matrix4f matrix = matrices.peek().getPositionMatrix();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
            );

            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            int alpha = computeRenderAlpha(rainIntensity);

            buffer.vertex(matrix, -1.0f, 1.0f, 0.0f)
                    .color(255, 255, 255, alpha)
                    .texture(0.0f, 0.0f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            buffer.vertex(matrix, 1.0f, 1.0f, 0.0f)
                    .color(255, 255, 255, alpha)
                    .texture(1.0f, 0.0f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            buffer.vertex(matrix, 1.0f, -1.0f, 0.0f)
                    .color(255, 255, 255, alpha)
                    .texture(1.0f, 1.0f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            buffer.vertex(matrix, -1.0f, -1.0f, 0.0f)
                    .color(255, 255, 255, alpha)
                    .texture(0.0f, 1.0f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering screen rain overlay");
            t.printStackTrace();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
        }
    }

    private static boolean isOpenToSky(World world, BlockPos pos) {
        return pos.getY() >= world.getBottomY() && world.isSkyVisible(pos);
    }

    private static CoverSpan getCoverSpan(World world, int x, int z, int playerY) {
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

            float undersideY = (float) (y + shape.getMin(Direction.Axis.Y));
            float topY = (float) (y + shape.getMax(Direction.Axis.Y));
            return new CoverSpan(undersideY, topY);
        }

        return null;
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

            return (float) (y + shape.getMax(Direction.Axis.Y));
        }

        return world.getBottomY();
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

    private static float clamp01(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }

    private static float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }
}
