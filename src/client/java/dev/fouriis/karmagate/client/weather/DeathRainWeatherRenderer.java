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

    private static final int DEBUG_RADIUS = 12;
    private static final float FACE_EPSILON = 0.001f;
    private static final float SCREEN_RAIN_TRANSITION_DISTANCE = 1.25f;
    private static final float MIN_RENDER_INTENSITY = 0.01f;
    private static final float CURTAIN_JOIN_EPSILON = 0.01f;
    private static final float TOP_LIP_CLEARANCE_THRESHOLD = 0.999f;

    private static final float MAX_CURTAIN_LEAN = 3.0f;

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
        float coverage = computeScreenRainCoverage(transition);

        renderBorderCurtains(world, camera, matrices, rainIntensity, rainDirection);

        if (coverage > 0.0f) {
            float axis = 0.0f;
            float flip = transition.rainOnPositiveSide ? 0.0f : 1.0f;
            renderScreenRainOverlay(world, camera, matrices, coverage, axis, flip, rainIntensity, rainDirection);
        }
    }

    private static void renderBorderCurtains(World world,
                                             Camera camera,
                                             MatrixStack matrices,
                                             float rainIntensity,
                                             float rainDirection) {
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
                        CoverSpan cover = getCoverSpan(world, worldX, worldZ, playerY);
                        if (cover != null) {
                            float bottomY = getDryFloorTopY(world, worldX, worldZ, playerY);
                            CurtainAnchor currentAnchor = computeCurtainAnchor(cover, rainIntensity, rainDirection);
                            float topY = currentAnchor.topY();

                            if (topY > bottomY + 0.05f) {
                                float borderX = worldX + 1.0f - FACE_EPSILON;

                                renderRainCurtainImmediate(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderX,
                                        worldZ,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        CurtainOrientation.NORTH_SOUTH,
                                        false,
                                        rainIntensity,
                                        rainDirection
                                );

                                renderNorthSouthJoinIfNeeded(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderX,
                                        worldX,
                                        worldZ,
                                        playerY,
                                        false,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        rainIntensity,
                                        rainDirection
                                );

                                renderNorthSouthEndCapsIfNeeded(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderX,
                                        worldX,
                                        worldZ,
                                        playerY,
                                        false,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        rainIntensity,
                                        rainDirection
                                );
                            }
                        }
                    } else {
                        CoverSpan cover = getCoverSpan(world, worldX + 1, worldZ, playerY);
                        if (cover != null) {
                            float bottomY = getDryFloorTopY(world, worldX + 1, worldZ, playerY);
                            CurtainAnchor currentAnchor = computeCurtainAnchor(cover, rainIntensity, rainDirection);
                            float topY = currentAnchor.topY();

                            if (topY > bottomY + 0.05f) {
                                float borderX = worldX + 1.0f + FACE_EPSILON;

                                renderRainCurtainImmediate(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderX,
                                        worldZ,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        CurtainOrientation.NORTH_SOUTH,
                                        true,
                                        rainIntensity,
                                        rainDirection
                                );

                                renderNorthSouthJoinIfNeeded(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderX,
                                        worldX,
                                        worldZ,
                                        playerY,
                                        true,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        rainIntensity,
                                        rainDirection
                                );

                                renderNorthSouthEndCapsIfNeeded(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderX,
                                        worldX,
                                        worldZ,
                                        playerY,
                                        true,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        rainIntensity,
                                        rainDirection
                                );
                            }
                        }
                    }
                }

                neighbor.set(worldX, playerY, worldZ + 1);
                boolean openSouth = isOpenToSky(world, neighbor);

                if (openHere != openSouth) {
                    if (!openHere) {
                        CoverSpan cover = getCoverSpan(world, worldX, worldZ, playerY);
                        if (cover != null) {
                            float bottomY = getDryFloorTopY(world, worldX, worldZ, playerY);
                            CurtainAnchor currentAnchor = computeCurtainAnchor(cover, rainIntensity, rainDirection);
                            float topY = currentAnchor.topY();

                            if (topY > bottomY + 0.05f) {
                                float borderZ = worldZ + 1.0f - FACE_EPSILON;

                                renderRainCurtainImmediate(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        worldX,
                                        borderZ,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        CurtainOrientation.EAST_WEST,
                                        true,
                                        rainIntensity,
                                        rainDirection
                                );

                                renderEastWestJoinIfNeeded(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderZ,
                                        worldX,
                                        worldZ,
                                        playerY,
                                        true,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        rainIntensity,
                                        rainDirection
                                );

                                renderEastWestEndCapsIfNeeded(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderZ,
                                        worldX,
                                        worldZ,
                                        playerY,
                                        true,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        rainIntensity,
                                        rainDirection
                                );
                            }
                        }
                    } else {
                        CoverSpan cover = getCoverSpan(world, worldX, worldZ + 1, playerY);
                        if (cover != null) {
                            float bottomY = getDryFloorTopY(world, worldX, worldZ + 1, playerY);
                            CurtainAnchor currentAnchor = computeCurtainAnchor(cover, rainIntensity, rainDirection);
                            float topY = currentAnchor.topY();

                            if (topY > bottomY + 0.05f) {
                                float borderZ = worldZ + 1.0f + FACE_EPSILON;

                                renderRainCurtainImmediate(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        worldX,
                                        borderZ,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        CurtainOrientation.EAST_WEST,
                                        false,
                                        rainIntensity,
                                        rainDirection
                                );

                                renderEastWestJoinIfNeeded(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderZ,
                                        worldX,
                                        worldZ,
                                        playerY,
                                        false,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        rainIntensity,
                                        rainDirection
                                );

                                renderEastWestEndCapsIfNeeded(
                                        world,
                                        matrices,
                                        camera,
                                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                        borderZ,
                                        worldX,
                                        worldZ,
                                        playerY,
                                        false,
                                        topY,
                                        bottomY,
                                        currentAnchor.topX(),
                                        currentAnchor.topZ(),
                                        rainIntensity,
                                        rainDirection
                                );
                            }
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

    private record CoverSpan(float undersideY, float topY) {
    }

    private record CurtainAnchor(float topY, float topX, float topZ) {
    }

    /**
     * Compatibility mode:
     * - X carries the current signed rain direction
     * - Z stays locked to 0 until rain direction becomes 3D
     * - top edge anchors either to the underside lip or, when angle permits,
     *   to the roof-top lip of the covering block
     */
    private record CurtainShear(float topX, float bottomX, float topZ, float bottomZ) {
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

    private static CurtainAnchor computeCurtainAnchor(CoverSpan cover, float rainIntensity, float rainDirection) {
        float intensity = clamp01(rainIntensity);
        float dirX = MathHelper.clamp(rainDirection, -1.0f, 1.0f);

        if (Math.abs(dirX) < 0.0001f) {
            return new CurtainAnchor(cover.undersideY(), 0.0f, 0.0f);
        }

        float leanPerBlock = lerp(0.16f, 0.30f, intensity);
        float coverThickness = Math.max(0.0f, cover.topY() - cover.undersideY());
        float horizontalTravelAcrossCover = Math.abs(dirX * leanPerBlock * coverThickness);

        // Only promote to the roof-top lip if the slanted path can actually clear
        // the full cover thickness horizontally.
        if (horizontalTravelAcrossCover >= TOP_LIP_CLEARANCE_THRESHOLD) {
            return new CurtainAnchor(
                    cover.topY(),
                    -Math.signum(dirX),
                    0.0f
            );
        }

        return new CurtainAnchor(cover.undersideY(), 0.0f, 0.0f);
    }

    private static CurtainShear computeCurtainShear(
            float topY,
            float bottomY,
            float topX,
            float topZ,
            float rainIntensity,
            float rainDirection
    ) {
        float height = Math.max(0.0f, topY - bottomY);
        float intensity = clamp01(rainIntensity);

        float dirX = MathHelper.clamp(rainDirection, -1.0f, 1.0f);
        float leanPerBlock = lerp(0.16f, 0.30f, intensity);

        float bottomX = MathHelper.clamp(
                topX + dirX * height * leanPerBlock,
                -MAX_CURTAIN_LEAN,
                MAX_CURTAIN_LEAN
        );

        float bottomZ = topZ;

        return new CurtainShear(topX, bottomX, topZ, bottomZ);
    }

    private static boolean hasNorthSouthCurtainAt(World world, int worldX, int worldZ, int playerY, boolean positiveFacing) {
        BlockPos.Mutable here = new BlockPos.Mutable(worldX, playerY, worldZ);
        BlockPos.Mutable east = new BlockPos.Mutable(worldX + 1, playerY, worldZ);

        boolean openHere = isOpenToSky(world, here);
        boolean openEast = isOpenToSky(world, east);

        return positiveFacing ? (openHere && !openEast) : (!openHere && openEast);
    }

    private static boolean hasEastWestCurtainAt(World world, int worldX, int worldZ, int playerY, boolean positiveFacing) {
        BlockPos.Mutable here = new BlockPos.Mutable(worldX, playerY, worldZ);
        BlockPos.Mutable south = new BlockPos.Mutable(worldX, playerY, worldZ + 1);

        boolean openHere = isOpenToSky(world, here);
        boolean openSouth = isOpenToSky(world, south);

        return positiveFacing ? (!openHere && openSouth) : (openHere && !openSouth);
    }

    private static boolean isNorthSouthEndSheltered(World world, int worldX, int worldZ, int playerY, boolean positiveFacing, boolean positiveEnd) {
        int coveredX = positiveFacing ? (worldX + 1) : worldX;
        int sideZ = positiveEnd ? (worldZ + 1) : (worldZ - 1);

        BlockPos.Mutable pos = new BlockPos.Mutable(coveredX, playerY, sideZ);
        return !isOpenToSky(world, pos);
    }

    private static boolean isEastWestEndSheltered(World world, int worldX, int worldZ, int playerY, boolean positiveFacing, boolean positiveEnd) {
        int coveredZ = positiveFacing ? worldZ : (worldZ + 1);
        int sideX = positiveEnd ? (worldX + 1) : (worldX - 1);

        BlockPos.Mutable pos = new BlockPos.Mutable(sideX, playerY, coveredZ);
        return !isOpenToSky(world, pos);
    }

    private static void beginWorldRainSheetState() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    private static void endWorldRainSheetState() {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
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
        float[] spriteRect = new float[]{0f, 0f, 1f, 1f};
        float[] rippleGold = new float[]{0f, 0f, 0f, 0f};

        float rainDirection = rainDirectionSigned;
        float rainEverywhere = 1.0f;
        float rainIntensity = clamp01(rainIntensity01);
        float waterLevel = 0.0f;
        float scale = lerp(12.0f, 8.0f, rainIntensity);
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
            float topX,
            float topZ,
            CurtainOrientation orientation,
            boolean positiveFacing,
            float rainIntensity,
            float rainDirection
    ) {
        try {
            bindDeathRainShader(
                    world,
                    camera,
                    worldX,
                    (topY + bottomY) * 0.5f,
                    worldZ,
                    1.0f,
                    0.0f,
                    0.0f,
                    rainIntensity,
                    rainDirection
            );

            int r = 255;
            int g = 255;
            int b = 255;
            int a = computeRenderAlpha(rainIntensity);

            CurtainShear shear = computeCurtainShear(topY, bottomY, topX, topZ, rainIntensity, rainDirection);

            beginWorldRainSheetState();

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
                        buffer,
                        m,
                        topY,
                        bottomY,
                        packedLight,
                        r, g, b, a,
                        positiveFacing,
                        shear
                );
            } else {
                emitCurtainPlaneEW(
                        buffer,
                        m,
                        topY,
                        bottomY,
                        packedLight,
                        r, g, b, a,
                        positiveFacing,
                        shear
                );
            }

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            endWorldRainSheetState();
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering rain curtain at "
                    + worldX + ", " + worldZ
                    + " topY=" + topY
                    + " bottomY=" + bottomY
                    + " orientation=" + orientation
                    + " positiveFacing=" + positiveFacing);
            t.printStackTrace();
            endWorldRainSheetState();
        }
    }

    private static void renderNorthSouthJoinIfNeeded(
            World world,
            MatrixStack matrices,
            Camera camera,
            int packedLight,
            float borderX,
            int worldX,
            int worldZ,
            int playerY,
            boolean positiveFacing,
            float currentTopY,
            float currentBottomY,
            float currentTopX,
            float currentTopZ,
            float rainIntensity,
            float rainDirection
    ) {
        int nextZ = worldZ + 1;

        BlockPos.Mutable here = new BlockPos.Mutable(worldX, playerY, nextZ);
        BlockPos.Mutable east = new BlockPos.Mutable(worldX + 1, playerY, nextZ);

        boolean openHere = isOpenToSky(world, here);
        boolean openEast = isOpenToSky(world, east);

        if (openHere == openEast) {
            return;
        }

        CoverSpan nextCover;
        float nextBottomY;

        if (positiveFacing) {
            if (!(openHere && !openEast)) {
                return;
            }

            nextCover = getCoverSpan(world, worldX + 1, nextZ, playerY);
            if (nextCover == null) {
                return;
            }
            nextBottomY = getDryFloorTopY(world, worldX + 1, nextZ, playerY);
        } else {
            if (!(!openHere && openEast)) {
                return;
            }

            nextCover = getCoverSpan(world, worldX, nextZ, playerY);
            if (nextCover == null) {
                return;
            }
            nextBottomY = getDryFloorTopY(world, worldX, nextZ, playerY);
        }

        CurtainAnchor nextAnchor = computeCurtainAnchor(nextCover, rainIntensity, rainDirection);
        float nextTopY = nextAnchor.topY();
        float nextTopX = nextAnchor.topX();
        float nextTopZ = nextAnchor.topZ();

        if (nextTopY <= nextBottomY + 0.05f) {
            return;
        }

        if (Math.abs(currentTopY - nextTopY) < CURTAIN_JOIN_EPSILON
                && Math.abs(currentBottomY - nextBottomY) < CURTAIN_JOIN_EPSILON
                && Math.abs(currentTopX - nextTopX) < CURTAIN_JOIN_EPSILON) {
            return;
        }

        CurtainShear currentShear = computeCurtainShear(currentTopY, currentBottomY, currentTopX, currentTopZ, rainIntensity, rainDirection);
        CurtainShear nextShear = computeCurtainShear(nextTopY, nextBottomY, nextTopX, nextTopZ, rainIntensity, rainDirection);

        renderNorthSouthJoinImmediate(
                world,
                matrices,
                camera,
                packedLight,
                borderX,
                worldZ + 1.0f,
                currentTopY,
                currentBottomY,
                nextTopY,
                nextBottomY,
                currentShear,
                nextShear,
                rainIntensity,
                rainDirection
        );
    }

    private static void renderEastWestJoinIfNeeded(
            World world,
            MatrixStack matrices,
            Camera camera,
            int packedLight,
            float borderZ,
            int worldX,
            int worldZ,
            int playerY,
            boolean positiveFacing,
            float currentTopY,
            float currentBottomY,
            float currentTopX,
            float currentTopZ,
            float rainIntensity,
            float rainDirection
    ) {
        int nextX = worldX + 1;

        BlockPos.Mutable here = new BlockPos.Mutable(nextX, playerY, worldZ);
        BlockPos.Mutable south = new BlockPos.Mutable(nextX, playerY, worldZ + 1);

        boolean openHere = isOpenToSky(world, here);
        boolean openSouth = isOpenToSky(world, south);

        if (openHere == openSouth) {
            return;
        }

        CoverSpan nextCover;
        float nextBottomY;

        if (positiveFacing) {
            if (!(!openHere && openSouth)) {
                return;
            }

            nextCover = getCoverSpan(world, nextX, worldZ, playerY);
            if (nextCover == null) {
                return;
            }
            nextBottomY = getDryFloorTopY(world, nextX, worldZ, playerY);
        } else {
            if (!(openHere && !openSouth)) {
                return;
            }

            nextCover = getCoverSpan(world, nextX, worldZ + 1, playerY);
            if (nextCover == null) {
                return;
            }
            nextBottomY = getDryFloorTopY(world, nextX, worldZ + 1, playerY);
        }

        CurtainAnchor nextAnchor = computeCurtainAnchor(nextCover, rainIntensity, rainDirection);
        float nextTopY = nextAnchor.topY();
        float nextTopX = nextAnchor.topX();
        float nextTopZ = nextAnchor.topZ();

        if (nextTopY <= nextBottomY + 0.05f) {
            return;
        }

        if (Math.abs(currentTopY - nextTopY) < CURTAIN_JOIN_EPSILON
                && Math.abs(currentBottomY - nextBottomY) < CURTAIN_JOIN_EPSILON
                && Math.abs(currentTopX - nextTopX) < CURTAIN_JOIN_EPSILON) {
            return;
        }

        CurtainShear currentShear = computeCurtainShear(currentTopY, currentBottomY, currentTopX, currentTopZ, rainIntensity, rainDirection);
        CurtainShear nextShear = computeCurtainShear(nextTopY, nextBottomY, nextTopX, nextTopZ, rainIntensity, rainDirection);

        renderEastWestJoinImmediate(
                world,
                matrices,
                camera,
                packedLight,
                worldX + 1.0f,
                borderZ,
                currentTopY,
                currentBottomY,
                nextTopY,
                nextBottomY,
                currentShear,
                nextShear,
                rainIntensity,
                rainDirection
        );
    }

    private static void renderNorthSouthJoinImmediate(
            World world,
            MatrixStack matrices,
            Camera camera,
            int packedLight,
            float worldX,
            float worldZ,
            float currentTopY,
            float currentBottomY,
            float nextTopY,
            float nextBottomY,
            CurtainShear currentShear,
            CurtainShear nextShear,
            float rainIntensity,
            float rainDirection
    ) {
        try {
            bindDeathRainShader(
                    world,
                    camera,
                    worldX,
                    (currentTopY + currentBottomY + nextTopY + nextBottomY) * 0.25f,
                    worldZ,
                    1.0f,
                    0.0f,
                    0.0f,
                    rainIntensity,
                    rainDirection
            );

            int r = 255;
            int g = 255;
            int b = 255;
            int a = computeRenderAlpha(rainIntensity);

            beginWorldRainSheetState();

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

            buffer.vertex(m, currentShear.topX(), currentTopY, currentShear.topZ())
                    .color(r, g, b, a).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                    .light(packedLight).normal(0.0f, 0.0f, 1.0f);

            buffer.vertex(m, nextShear.topX(), nextTopY, nextShear.topZ())
                    .color(r, g, b, a).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                    .light(packedLight).normal(0.0f, 0.0f, 1.0f);

            buffer.vertex(m, nextShear.bottomX(), nextBottomY, nextShear.bottomZ())
                    .color(r, g, b, a).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                    .light(packedLight).normal(0.0f, 0.0f, 1.0f);

            buffer.vertex(m, currentShear.bottomX(), currentBottomY, currentShear.bottomZ())
                    .color(r, g, b, a).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                    .light(packedLight).normal(0.0f, 0.0f, 1.0f);

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            endWorldRainSheetState();
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering NS join quad");
            t.printStackTrace();
            endWorldRainSheetState();
        }
    }

    private static void renderEastWestJoinImmediate(
            World world,
            MatrixStack matrices,
            Camera camera,
            int packedLight,
            float worldX,
            float worldZ,
            float currentTopY,
            float currentBottomY,
            float nextTopY,
            float nextBottomY,
            CurtainShear currentShear,
            CurtainShear nextShear,
            float rainIntensity,
            float rainDirection
    ) {
        try {
            bindDeathRainShader(
                    world,
                    camera,
                    worldX,
                    (currentTopY + currentBottomY + nextTopY + nextBottomY) * 0.25f,
                    worldZ,
                    1.0f,
                    0.0f,
                    0.0f,
                    rainIntensity,
                    rainDirection
            );

            int r = 255;
            int g = 255;
            int b = 255;
            int a = computeRenderAlpha(rainIntensity);

            beginWorldRainSheetState();

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

            buffer.vertex(m, currentShear.topX(), currentTopY, currentShear.topZ())
                    .color(r, g, b, a).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                    .light(packedLight).normal(1.0f, 0.0f, 0.0f);

            buffer.vertex(m, nextShear.topX(), nextTopY, nextShear.topZ())
                    .color(r, g, b, a).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                    .light(packedLight).normal(1.0f, 0.0f, 0.0f);

            buffer.vertex(m, nextShear.bottomX(), nextBottomY, nextShear.bottomZ())
                    .color(r, g, b, a).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                    .light(packedLight).normal(1.0f, 0.0f, 0.0f);

            buffer.vertex(m, currentShear.bottomX(), currentBottomY, currentShear.bottomZ())
                    .color(r, g, b, a).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                    .light(packedLight).normal(1.0f, 0.0f, 0.0f);

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            endWorldRainSheetState();
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering EW join quad");
            t.printStackTrace();
            endWorldRainSheetState();
        }
    }

    private static void renderNorthSouthEndCapsIfNeeded(
            World world,
            MatrixStack matrices,
            Camera camera,
            int packedLight,
            float borderX,
            int worldX,
            int worldZ,
            int playerY,
            boolean positiveFacing,
            float topY,
            float bottomY,
            float topX,
            float topZ,
            float rainIntensity,
            float rainDirection
    ) {
        CurtainShear shear = computeCurtainShear(topY, bottomY, topX, topZ, rainIntensity, rainDirection);

        boolean northContinues = hasNorthSouthCurtainAt(world, worldX, worldZ - 1, playerY, positiveFacing);
        boolean southContinues = hasNorthSouthCurtainAt(world, worldX, worldZ + 1, playerY, positiveFacing);

        boolean northSheltered = isNorthSouthEndSheltered(world, worldX, worldZ, playerY, positiveFacing, false);
        boolean southSheltered = isNorthSouthEndSheltered(world, worldX, worldZ, playerY, positiveFacing, true);

        if (!northContinues && northSheltered) {
            renderNorthSouthEndCapImmediate(
                    world,
                    matrices,
                    camera,
                    packedLight,
                    borderX,
                    worldZ,
                    topY,
                    bottomY,
                    shear,
                    false,
                    rainIntensity,
                    rainDirection
            );
        }

        if (!southContinues && southSheltered) {
            renderNorthSouthEndCapImmediate(
                    world,
                    matrices,
                    camera,
                    packedLight,
                    borderX,
                    worldZ + 1.0f,
                    topY,
                    bottomY,
                    shear,
                    true,
                    rainIntensity,
                    rainDirection
            );
        }
    }

    private static void renderEastWestEndCapsIfNeeded(
            World world,
            MatrixStack matrices,
            Camera camera,
            int packedLight,
            float borderZ,
            int worldX,
            int worldZ,
            int playerY,
            boolean positiveFacing,
            float topY,
            float bottomY,
            float topX,
            float topZ,
            float rainIntensity,
            float rainDirection
    ) {
        CurtainShear shear = computeCurtainShear(topY, bottomY, topX, topZ, rainIntensity, rainDirection);

        boolean westContinues = hasEastWestCurtainAt(world, worldX - 1, worldZ, playerY, positiveFacing);
        boolean eastContinues = hasEastWestCurtainAt(world, worldX + 1, worldZ, playerY, positiveFacing);

        boolean westSheltered = isEastWestEndSheltered(world, worldX, worldZ, playerY, positiveFacing, false);
        boolean eastSheltered = isEastWestEndSheltered(world, worldX, worldZ, playerY, positiveFacing, true);

        if (!westContinues && westSheltered) {
            renderEastWestEndCapImmediate(
                    world,
                    matrices,
                    camera,
                    packedLight,
                    worldX,
                    borderZ,
                    topY,
                    bottomY,
                    shear,
                    false,
                    rainIntensity,
                    rainDirection
            );
        }

        if (!eastContinues && eastSheltered) {
            renderEastWestEndCapImmediate(
                    world,
                    matrices,
                    camera,
                    packedLight,
                    worldX + 1.0f,
                    borderZ,
                    topY,
                    bottomY,
                    shear,
                    true,
                    rainIntensity,
                    rainDirection
            );
        }
    }

    private static void renderNorthSouthEndCapImmediate(
            World world,
            MatrixStack matrices,
            Camera camera,
            int packedLight,
            float worldX,
            float worldZ,
            float topY,
            float bottomY,
            CurtainShear shear,
            boolean positiveEnd,
            float rainIntensity,
            float rainDirection
    ) {
        try {
            bindDeathRainShader(
                    world,
                    camera,
                    worldX,
                    (topY + bottomY) * 0.5f,
                    worldZ,
                    1.0f,
                    0.0f,
                    0.0f,
                    rainIntensity,
                    rainDirection
            );

            int r = 255;
            int g = 255;
            int b = 255;
            int a = computeRenderAlpha(rainIntensity);

            beginWorldRainSheetState();

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

            float nx = 0.0f;
            float ny = 0.0f;
            float nz = positiveEnd ? 1.0f : -1.0f;

            if (positiveEnd) {
                buffer.vertex(m, 0.0f,             topY,    0.0f)
                        .color(r, g, b, a).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, shear.topX(),     topY,    shear.topZ())
                        .color(r, g, b, a).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, shear.bottomX(),  bottomY, shear.bottomZ())
                        .color(r, g, b, a).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, 0.0f,             bottomY, 0.0f)
                        .color(r, g, b, a).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
            } else {
                buffer.vertex(m, shear.topX(),     topY,    shear.topZ())
                        .color(r, g, b, a).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, 0.0f,             topY,    0.0f)
                        .color(r, g, b, a).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, 0.0f,             bottomY, 0.0f)
                        .color(r, g, b, a).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, shear.bottomX(),  bottomY, shear.bottomZ())
                        .color(r, g, b, a).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
            }

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            endWorldRainSheetState();
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering NS end cap");
            t.printStackTrace();
            endWorldRainSheetState();
        }
    }

    private static void renderEastWestEndCapImmediate(
            World world,
            MatrixStack matrices,
            Camera camera,
            int packedLight,
            float worldX,
            float worldZ,
            float topY,
            float bottomY,
            CurtainShear shear,
            boolean positiveEnd,
            float rainIntensity,
            float rainDirection
    ) {
        try {
            bindDeathRainShader(
                    world,
                    camera,
                    worldX,
                    (topY + bottomY) * 0.5f,
                    worldZ,
                    1.0f,
                    0.0f,
                    0.0f,
                    rainIntensity,
                    rainDirection
            );

            int r = 255;
            int g = 255;
            int b = 255;
            int a = computeRenderAlpha(rainIntensity);

            beginWorldRainSheetState();

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

            float nx = positiveEnd ? 1.0f : -1.0f;
            float ny = 0.0f;
            float nz = 0.0f;

            if (positiveEnd) {
                buffer.vertex(m, 0.0f,             topY,    0.0f)
                        .color(r, g, b, a).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, shear.topX(),     topY,    shear.topZ())
                        .color(r, g, b, a).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, shear.bottomX(),  bottomY, shear.bottomZ())
                        .color(r, g, b, a).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, 0.0f,             bottomY, 0.0f)
                        .color(r, g, b, a).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
            } else {
                buffer.vertex(m, shear.topX(),     topY,    shear.topZ())
                        .color(r, g, b, a).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, 0.0f,             topY,    0.0f)
                        .color(r, g, b, a).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, 0.0f,             bottomY, 0.0f)
                        .color(r, g, b, a).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
                buffer.vertex(m, shear.bottomX(),  bottomY, shear.bottomZ())
                        .color(r, g, b, a).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV)
                        .light(packedLight).normal(nx, ny, nz);
            }

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            endWorldRainSheetState();
        } catch (Throwable t) {
            System.err.println("[Karmagate/DeathRain] Exception while rendering EW end cap");
            t.printStackTrace();
            endWorldRainSheetState();
        }
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
            Matrix4f m = matrices.peek().getPositionMatrix();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
            );

            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            int alpha = computeRenderAlpha(rainIntensity);

            buffer.vertex(m, -1.0f,  1.0f, 0.0f)
                    .color(255, 255, 255, alpha)
                    .texture(0f, 0f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            buffer.vertex(m,  1.0f,  1.0f, 0.0f)
                    .color(255, 255, 255, alpha)
                    .texture(1f, 0f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            buffer.vertex(m,  1.0f, -1.0f, 0.0f)
                    .color(255, 255, 255, alpha)
                    .texture(1f, 1f)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(0.0f, 0.0f, -1.0f);

            buffer.vertex(m, -1.0f, -1.0f, 0.0f)
                    .color(255, 255, 255, alpha)
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
            boolean positiveFacing,
            CurtainShear shear
    ) {
        float xTop = shear.topX();
        float xBottom = shear.bottomX();

        float z0 = 0.0f + shear.topZ();
        float z1 = 1.0f + shear.topZ();
        float z0b = 0.0f + shear.bottomZ();
        float z1b = 1.0f + shear.bottomZ();

        float u0 = 0.0f;
        float u1 = 1.0f;
        float v0 = 0.0f;
        float v1 = 1.0f;

        float nx = positiveFacing ? 1.0f : -1.0f;
        float ny = 0.0f;
        float nz = 0.0f;

        if (positiveFacing) {
            buffer.vertex(m, xTop,    topY,    z0 ).color(r, g, b, a).texture(u0, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, xTop,    topY,    z1 ).color(r, g, b, a).texture(u1, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, xBottom, bottomY, z1b).color(r, g, b, a).texture(u1, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, xBottom, bottomY, z0b).color(r, g, b, a).texture(u0, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        } else {
            buffer.vertex(m, xTop,    topY,    z1 ).color(r, g, b, a).texture(u0, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, xTop,    topY,    z0 ).color(r, g, b, a).texture(u1, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, xBottom, bottomY, z0b).color(r, g, b, a).texture(u1, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, xBottom, bottomY, z1b).color(r, g, b, a).texture(u0, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        }
    }

    private static void emitCurtainPlaneEW(
            BufferBuilder buffer,
            Matrix4f m,
            float topY,
            float bottomY,
            int light,
            int r, int g, int b, int a,
            boolean positiveFacing,
            CurtainShear shear
    ) {
        float x0Top = 0.0f + shear.topX();
        float x1Top = 1.0f + shear.topX();
        float x0Bottom = 0.0f + shear.bottomX();
        float x1Bottom = 1.0f + shear.bottomX();

        float zTop = shear.topZ();
        float zBottom = shear.bottomZ();

        float u0 = 0.0f;
        float u1 = 1.0f;
        float v0 = 0.0f;
        float v1 = 1.0f;

        float nx = 0.0f;
        float ny = 0.0f;
        float nz = positiveFacing ? 1.0f : -1.0f;

        if (positiveFacing) {
            buffer.vertex(m, x0Top,    topY,    zTop   ).color(r, g, b, a).texture(u0, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x1Top,    topY,    zTop   ).color(r, g, b, a).texture(u1, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x1Bottom, bottomY, zBottom).color(r, g, b, a).texture(u1, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x0Bottom, bottomY, zBottom).color(r, g, b, a).texture(u0, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        } else {
            buffer.vertex(m, x1Top,    topY,    zTop   ).color(r, g, b, a).texture(u0, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x0Top,    topY,    zTop   ).color(r, g, b, a).texture(u1, v0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x0Bottom, bottomY, zBottom).color(r, g, b, a).texture(u1, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
            buffer.vertex(m, x1Bottom, bottomY, zBottom).color(r, g, b, a).texture(u0, v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
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