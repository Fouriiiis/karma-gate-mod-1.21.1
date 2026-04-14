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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class DeathRainWeatherRenderer {

    private static final int RADIUS_BLOCKS = 12;

    // Horizontal travel per 1 block of vertical drop.
    private static final float LIGHT_VECTOR_X = (float) Math.tan(Math.toRadians(10.0f));
    private static final float LIGHT_VECTOR_Z = (float) Math.tan(Math.toRadians(10.0f));

    private static final float EPSILON = 1.0e-4f;
    private static final float BLOCK_EPSILON = 0.001f;
    private static final float MIN_RENDER_INTENSITY = 0.01f;

    private static final boolean ALONG_X = false;
    private static final boolean ALONG_Z = true;

    private static final Identifier LEVEL_TEXTURE = Identifier.of("librainworldmc", "grabtex");
    private static final Identifier NOISE_TEXTURE = Identifier.of("librainworldmc", "textures/rainworld/palettes/noise_hq.png");
    private static final Identifier RAIN_TEXTURE = Identifier.of("minecraft", "textures/block/water_flow.png");

    private DeathRainWeatherRenderer() {
    }

    public static void render(World world, Camera camera, float tickDelta, MatrixStack matrices) {
        if (world == null || camera == null || matrices == null || !isDeathRainActive()) {
            return;
        }

        float rainIntensity = resolveGlobalRainIntensity();
        if (rainIntensity < MIN_RENDER_INTENSITY) {
            return;
        }
        float rainDirection = resolveGlobalRainDirection();

        Vec3d camPos = camera.getPos();
        int baseX = MathHelper.floor(camPos.x);
        int baseY = MathHelper.floor(camPos.y);
        int baseZ = MathHelper.floor(camPos.z);
        int radiusSq = RADIUS_BLOCKS * RADIUS_BLOCKS;

        for (int x = baseX - RADIUS_BLOCKS; x <= baseX + RADIUS_BLOCKS; x++) {
            for (int y = baseY - RADIUS_BLOCKS; y <= baseY + RADIUS_BLOCKS; y++) {
                for (int z = baseZ - RADIUS_BLOCKS; z <= baseZ + RADIUS_BLOCKS; z++) {
                    if (!isInsideRadius(camPos, radiusSq, x, y, z) || !isOpaqueFullCube(world, x, y, z)) {
                        continue;
                    }

                    boolean topCaster = isTopShadowCaster(world, x, y, z);
                    boolean bottomCaster = isBottomShadowCaster(world, x, y, z);

                    if (topCaster) {
                        emitTopPerimeterShadows(world, camera, matrices, x, y, z, rainIntensity, rainDirection);
                    }
                    if (bottomCaster) {
                        emitBottomPerimeterShadows(world, camera, matrices, x, y, z, rainIntensity, rainDirection);
                    }
                    if (topCaster && bottomCaster) {
                        emitReceiverStitchShadow(world, camera, matrices, x, y, z, rainIntensity, rainDirection);
                    }
                }
            }
        }
    }

    private static boolean isInsideRadius(Vec3d camPos, int radiusSq, int x, int y, int z) {
        return camPos.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5) <= radiusSq;
    }

    private static boolean isDeathRainActive() {
        if (GlobalRainClientState.hasSync()) {
            return true;
        }

        return MinecraftClient.getInstance().getServer() != null;
    }

    private static boolean isOpaqueFullCube(World world, int x, int y, int z) {
        if (world.isOutOfHeightLimit(y)) {
            return false;
        }

        BlockPos pos = new BlockPos(x, y, z);
        return world.getBlockState(pos).isOpaqueFullCube(world, pos);
    }

    private static boolean isAir(World world, int x, int y, int z) {
        if (world.isOutOfHeightLimit(y)) {
            return false;
        }

        BlockPos pos = new BlockPos(x, y, z);
        return !world.getBlockState(pos).isOpaqueFullCube(world, pos);
    }

    private static boolean isTopShadowCaster(World world, int x, int y, int z) {
        if (!isOpaqueFullCube(world, x, y, z)) {
            return false;
        }

        return isAir(world, x, y + 1, z) && world.isSkyVisible(new BlockPos(x, y + 1, z));
    }

    private static boolean isBottomShadowCaster(World world, int x, int y, int z) {
        return isAir(world, x, y - 1, z);
    }

    private static boolean isBottomFaceExposed(World world, int x, int y, int z) {
        return isOpaqueFullCube(world, x, y, z) && isAir(world, x, y - 1, z);
    }

    private static void emitTopPerimeterShadows(
            World world,
            Camera camera,
            MatrixStack matrices,
            int x,
            int y,
            int z,
            float rainIntensity,
            float rainDirection
    ) {
        float sourceY = y + 1.0f;

        emitEdgeIfOpen(world, camera, matrices, x - 1, y, z, ALONG_Z, x, sourceY, z, z + 1.0f, false, y, rainIntensity, rainDirection);
        emitEdgeIfOpen(world, camera, matrices, x + 1, y, z, ALONG_Z, x + 1.0f, sourceY, z, z + 1.0f, false, y, rainIntensity, rainDirection);
        emitEdgeIfOpen(world, camera, matrices, x, y, z - 1, ALONG_X, z, sourceY, x, x + 1.0f, false, y, rainIntensity, rainDirection);
        emitEdgeIfOpen(world, camera, matrices, x, y, z + 1, ALONG_X, z + 1.0f, sourceY, x, x + 1.0f, false, y, rainIntensity, rainDirection);
    }

    private static void emitBottomPerimeterShadows(
            World world,
            Camera camera,
            MatrixStack matrices,
            int x,
            int y,
            int z,
            float rainIntensity,
            float rainDirection
    ) {
        float sourceY = y;

        if (LIGHT_VECTOR_X > EPSILON) {
            emitEdgeIfOpen(world, camera, matrices, x - 1, y, z, ALONG_Z, x, sourceY, z, z + 1.0f, true, y, rainIntensity, rainDirection);
        }
        if (LIGHT_VECTOR_X < -EPSILON) {
            emitEdgeIfOpen(world, camera, matrices, x + 1, y, z, ALONG_Z, x + 1.0f, sourceY, z, z + 1.0f, true, y, rainIntensity, rainDirection);
        }
        if (LIGHT_VECTOR_Z > EPSILON) {
            emitEdgeIfOpen(world, camera, matrices, x, y, z - 1, ALONG_X, z, sourceY, x, x + 1.0f, true, y, rainIntensity, rainDirection);
        }
        if (LIGHT_VECTOR_Z < -EPSILON) {
            emitEdgeIfOpen(world, camera, matrices, x, y, z + 1, ALONG_X, z + 1.0f, sourceY, x, x + 1.0f, true, y, rainIntensity, rainDirection);
        }
    }

    private static void emitEdgeIfOpen(
            World world,
            Camera camera,
            MatrixStack matrices,
            int neighborX,
            int neighborY,
            int neighborZ,
            boolean alongZ,
            float fixedAxis,
            float sourceY,
            float edgeStart,
            float edgeEnd,
            boolean bottomPass,
            int layerY,
            float rainIntensity,
            float rainDirection
    ) {
        if (!isOpaqueFullCube(world, neighborX, neighborY, neighborZ)) {
            castEdge(world, camera, matrices, alongZ, fixedAxis, sourceY, edgeStart, edgeEnd, bottomPass, layerY, rainIntensity, rainDirection);
        }
    }

    private static void emitReceiverStitchShadow(
            World world,
            Camera camera,
            MatrixStack matrices,
            int x,
            int y,
            int z,
            float rainIntensity,
            float rainDirection
    ) {
        if (Math.abs(LIGHT_VECTOR_X) <= EPSILON || Math.abs(LIGHT_VECTOR_Z) <= EPSILON) {
            return;
        }

        CornerPatch first = null;
        CornerPatch second = null;

        if (LIGHT_VECTOR_X > EPSILON && LIGHT_VECTOR_Z > EPSILON) {
            if (!isOpaqueFullCube(world, x - 1, y, z)) {
                first = findCornerPatch(world, ALONG_Z, x, y, z, z + 1.0f, false);
            }
            if (!isOpaqueFullCube(world, x, y, z - 1)) {
                second = findCornerPatch(world, ALONG_X, z, y, x, x + 1.0f, false);
            }
        } else if (LIGHT_VECTOR_X > EPSILON) {
            if (!isOpaqueFullCube(world, x - 1, y, z)) {
                first = findCornerPatch(world, ALONG_Z, x, y, z, z + 1.0f, true);
            }
            if (!isOpaqueFullCube(world, x, y, z + 1)) {
                second = findCornerPatch(world, ALONG_X, z + 1.0f, y, x, x + 1.0f, false);
            }
        } else if (LIGHT_VECTOR_Z > EPSILON) {
            if (!isOpaqueFullCube(world, x + 1, y, z)) {
                first = findCornerPatch(world, ALONG_Z, x + 1.0f, y, z, z + 1.0f, false);
            }
            if (!isOpaqueFullCube(world, x, y, z - 1)) {
                second = findCornerPatch(world, ALONG_X, z, y, x, x + 1.0f, true);
            }
        } else {
            if (!isOpaqueFullCube(world, x + 1, y, z)) {
                first = findCornerPatch(world, ALONG_Z, x + 1.0f, y, z, z + 1.0f, true);
            }
            if (!isOpaqueFullCube(world, x, y, z + 1)) {
                second = findCornerPatch(world, ALONG_X, z + 1.0f, y, x, x + 1.0f, true);
            }
        }

        emitCornerPatch(world, camera, matrices, first, rainIntensity, rainDirection);
        emitCornerPatch(world, camera, matrices, second, rainIntensity, rainDirection);
    }

    private static CornerPatch findCornerPatch(
            World world,
            boolean alongZ,
            float fixedAxis,
            int layerY,
            float edgeStart,
            float edgeEnd,
            boolean useMinEndpoint
    ) {
        float min = Math.min(edgeStart, edgeEnd);
        float max = Math.max(edgeStart, edgeEnd);
        float edgeLen = max - min;
        if (edgeLen <= EPSILON) {
            return null;
        }

        List<Interval> topVisible = computeVisibleIntervals(world, alongZ, fixedAxis, min, edgeLen, layerY);
        List<Interval> bottomVisible = computeVisibleIntervals(world, alongZ, fixedAxis, min, edgeLen, layerY - 1);

        for (Interval overlap : intersectIntervals(topVisible, bottomVisible)) {
            float t0 = snap(overlap.min);
            float t1 = snap(overlap.max);
            if (t1 - t0 <= EPSILON) {
                continue;
            }

            float sourceEdgeValue = min + edgeLen * (useMinEndpoint ? t0 : t1);
            float bottomEdgeValue = sourceEdgeValue;

            if (alongZ) {
                bottomEdgeValue += useMinEndpoint
                        ? computeBottomInset(world, layerY, fixedAxis, bottomEdgeValue, ALONG_Z, true)
                        : -computeBottomInset(world, layerY, fixedAxis, bottomEdgeValue, ALONG_Z, false);
            } else {
                bottomEdgeValue += useMinEndpoint
                        ? computeBottomInset(world, layerY, bottomEdgeValue, fixedAxis, ALONG_X, true)
                        : -computeBottomInset(world, layerY, bottomEdgeValue, fixedAxis, ALONG_X, false);
            }

            Vector3f topSource;
            Vector3f bottomSource;
            Vector3f topReceiver;
            Vector3f bottomReceiver;

            if (alongZ) {
                topSource = new Vector3f(fixedAxis, layerY + 1.0f, sourceEdgeValue);
                bottomSource = new Vector3f(fixedAxis, layerY, bottomEdgeValue);
                topReceiver = projectToReceiver(world, fixedAxis + LIGHT_VECTOR_X, layerY, sourceEdgeValue + LIGHT_VECTOR_Z);
                bottomReceiver = projectToReceiver(world, fixedAxis + LIGHT_VECTOR_X, layerY - 1, bottomEdgeValue + LIGHT_VECTOR_Z);
            } else {
                topSource = new Vector3f(sourceEdgeValue, layerY + 1.0f, fixedAxis);
                bottomSource = new Vector3f(bottomEdgeValue, layerY, fixedAxis);
                topReceiver = projectToReceiver(world, sourceEdgeValue + LIGHT_VECTOR_X, layerY, fixedAxis + LIGHT_VECTOR_Z);
                bottomReceiver = projectToReceiver(world, bottomEdgeValue + LIGHT_VECTOR_X, layerY - 1, fixedAxis + LIGHT_VECTOR_Z);
            }

            if (topReceiver != null
                    && bottomReceiver != null
                    && !isDiagonalBottomHolePair(world, layerY, topSource.x, topSource.z)) {
                return new CornerPatch(topSource, bottomSource, topReceiver, bottomReceiver);
            }
        }

        return null;
    }

    private static void castEdge(
            World world,
            Camera camera,
            MatrixStack matrices,
            boolean alongZ,
            float fixedAxis,
            float sourceY,
            float edgeStart,
            float edgeEnd,
            boolean bottomPass,
            int layerY,
            float rainIntensity,
            float rainDirection
    ) {
        float min = Math.min(edgeStart, edgeEnd);
        float max = Math.max(edgeStart, edgeEnd);
        float edgeLen = max - min;
        if (edgeLen <= EPSILON) {
            return;
        }

        List<Interval> visible = computeVisibleIntervals(
                world,
                alongZ,
                fixedAxis,
                min,
                edgeLen,
                MathHelper.floor(sourceY - EPSILON)
        );

        for (Interval interval : visible) {
            float t0 = snap(interval.min);
            float t1 = snap(interval.max);
            if (t1 - t0 <= EPSILON) {
                continue;
            }

            float start = min + edgeLen * t0;
            float end = min + edgeLen * t1;

            if (bottomPass) {
                if (alongZ) {
                    start += computeBottomInset(world, layerY, fixedAxis, start, ALONG_Z, true);
                    end -= computeBottomInset(world, layerY, fixedAxis, end, ALONG_Z, false);
                } else {
                    start += computeBottomInset(world, layerY, start, fixedAxis, ALONG_X, true);
                    end -= computeBottomInset(world, layerY, end, fixedAxis, ALONG_X, false);
                }

                if (end - start <= EPSILON) {
                    continue;
                }
            }

            float exitY = sourceY - 1.0f;
            Vector3f recv0;
            Vector3f recv1;

            if (alongZ) {
                float exitX = fixedAxis + LIGHT_VECTOR_X;
                recv0 = projectToReceiver(world, exitX, exitY, start + LIGHT_VECTOR_Z);
                recv1 = projectToReceiver(world, exitX, exitY, end + LIGHT_VECTOR_Z);
                if (recv0 == null || recv1 == null) {
                    continue;
                }

                renderQuadImmediate(
                        world,
                        camera,
                        matrices,
                        new Vec3d(fixedAxis, sourceY, start),
                        new Vec3d(fixedAxis, sourceY, end),
                        new Vec3d(recv1.x, recv1.y, recv1.z),
                        new Vec3d(recv0.x, recv0.y, recv0.z),
                        rainIntensity,
                        rainDirection
                );
            } else {
                float exitZ = fixedAxis + LIGHT_VECTOR_Z;
                recv0 = projectToReceiver(world, start + LIGHT_VECTOR_X, exitY, exitZ);
                recv1 = projectToReceiver(world, end + LIGHT_VECTOR_X, exitY, exitZ);
                if (recv0 == null || recv1 == null) {
                    continue;
                }

                renderQuadImmediate(
                        world,
                        camera,
                        matrices,
                        new Vec3d(start, sourceY, fixedAxis),
                        new Vec3d(end, sourceY, fixedAxis),
                        new Vec3d(recv1.x, recv1.y, recv1.z),
                        new Vec3d(recv0.x, recv0.y, recv0.z),
                        rainIntensity,
                        rainDirection
                );
            }
        }
    }

    private static List<Interval> computeVisibleIntervals(
            World world,
            boolean alongZ,
            float fixedAxis,
            float edgeStart,
            float edgeLen,
            int clipLayerY
    ) {
        List<Interval> visible = new ArrayList<>();
        visible.add(new Interval(0.0f, 1.0f));

        float sweepMinX;
        float sweepMaxX;
        float sweepMinZ;
        float sweepMaxZ;

        if (alongZ) {
            sweepMinX = Math.min(fixedAxis, fixedAxis + LIGHT_VECTOR_X);
            sweepMaxX = Math.max(fixedAxis, fixedAxis + LIGHT_VECTOR_X);
            sweepMinZ = Math.min(edgeStart, edgeStart + LIGHT_VECTOR_Z);
            sweepMaxZ = Math.max(edgeStart + edgeLen, edgeStart + edgeLen + LIGHT_VECTOR_Z);
        } else {
            sweepMinX = Math.min(edgeStart, edgeStart + LIGHT_VECTOR_X);
            sweepMaxX = Math.max(edgeStart + edgeLen, edgeStart + edgeLen + LIGHT_VECTOR_X);
            sweepMinZ = Math.min(fixedAxis, fixedAxis + LIGHT_VECTOR_Z);
            sweepMaxZ = Math.max(fixedAxis, fixedAxis + LIGHT_VECTOR_Z);
        }

        int minCellX = MathHelper.floor(sweepMinX) - 1;
        int maxCellX = MathHelper.floor(sweepMaxX) + 1;
        int minCellZ = MathHelper.floor(sweepMinZ) - 1;
        int maxCellZ = MathHelper.floor(sweepMaxZ) + 1;

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                Interval blocked = clipSweepAgainstBlock(world, fixedAxis, edgeStart, edgeLen, clipLayerY, cellX, cellZ, alongZ);
                if (blocked != null) {
                    subtract(visible, blocked);
                    if (visible.isEmpty()) {
                        return visible;
                    }
                }
            }
        }

        return visible;
    }

    private static List<Interval> intersectIntervals(List<Interval> a, List<Interval> b) {
        List<Interval> out = new ArrayList<>();

        for (Interval ia : a) {
            for (Interval ib : b) {
                float min = Math.max(ia.min, ib.min);
                float max = Math.min(ia.max, ib.max);
                if (max - min > EPSILON) {
                    out.add(new Interval(min, max));
                }
            }
        }

        return out;
    }

    private static float computeBottomInset(
            World world,
            int layerY,
            float cornerX,
            float cornerZ,
            boolean alongZ,
            boolean minSide
    ) {
        if (!isBlockedBottomCorner(world, layerY, cornerX, cornerZ)) {
            return 0.0f;
        }

        float light = alongZ ? LIGHT_VECTOR_Z : LIGHT_VECTOR_X;
        return minSide ? Math.max(0.0f, light) : Math.max(0.0f, -light);
    }

    private static boolean isBlockedBottomCorner(World world, int layerY, float cornerX, float cornerZ) {
        if (!isNearInteger(cornerX) || !isNearInteger(cornerZ)) {
            return false;
        }

        int gridX = Math.round(cornerX);
        int gridZ = Math.round(cornerZ);

        boolean nw = isBottomFaceExposed(world, gridX - 1, layerY, gridZ - 1);
        boolean ne = isBottomFaceExposed(world, gridX, layerY, gridZ - 1);
        boolean sw = isBottomFaceExposed(world, gridX - 1, layerY, gridZ);
        boolean se = isBottomFaceExposed(world, gridX, layerY, gridZ);

        int count = 0;
        if (nw) count++;
        if (ne) count++;
        if (sw) count++;
        if (se) count++;

        boolean diagonalPair = isDiagonalBottomHolePair(world, layerY, cornerX, cornerZ);
        if (count != 3 && !diagonalPair) {
            return false;
        }

        float sampleX = cornerX + Math.signum(LIGHT_VECTOR_X) * BLOCK_EPSILON;
        float sampleZ = cornerZ + Math.signum(LIGHT_VECTOR_Z) * BLOCK_EPSILON;
        return isBottomFaceExposed(world, MathHelper.floor(sampleX), layerY, MathHelper.floor(sampleZ));
    }

    private static boolean isNearInteger(float value) {
        return Math.abs(value - Math.round(value)) <= EPSILON;
    }

    private static boolean isDiagonalBottomHolePair(World world, int layerY, float cornerX, float cornerZ) {
        if (!isNearInteger(cornerX) || !isNearInteger(cornerZ)) {
            return false;
        }

        int gridX = Math.round(cornerX);
        int gridZ = Math.round(cornerZ);

        boolean nw = isBottomFaceExposed(world, gridX - 1, layerY, gridZ - 1);
        boolean ne = isBottomFaceExposed(world, gridX, layerY, gridZ - 1);
        boolean sw = isBottomFaceExposed(world, gridX - 1, layerY, gridZ);
        boolean se = isBottomFaceExposed(world, gridX, layerY, gridZ);

        return (nw && se && !ne && !sw) || (ne && sw && !nw && !se);
    }

    private static Interval clipSweepAgainstBlock(
            World world,
            float fixedAxis,
            float edgeStart,
            float edgeLen,
            int layerY,
            int blockX,
            int blockZ,
            boolean edgeRunsAlongZ
    ) {
        if (!isOpaqueFullCube(world, blockX, layerY, blockZ)) {
            return null;
        }

        float blockMinX = blockX + BLOCK_EPSILON;
        float blockMaxX = blockX + 1.0f - BLOCK_EPSILON;
        float blockMinZ = blockZ + BLOCK_EPSILON;
        float blockMaxZ = blockZ + 1.0f - BLOCK_EPSILON;

        float uMin = EPSILON;
        float uMax = 1.0f - EPSILON;

        if (edgeRunsAlongZ) {
            if (Math.abs(LIGHT_VECTOR_X) < 1.0e-6f) {
                if (!(fixedAxis > blockMinX && fixedAxis < blockMaxX)) {
                    return null;
                }
            } else {
                float ux0 = (blockMinX - fixedAxis) / LIGHT_VECTOR_X;
                float ux1 = (blockMaxX - fixedAxis) / LIGHT_VECTOR_X;
                uMin = Math.max(uMin, Math.min(ux0, ux1));
                uMax = Math.min(uMax, Math.max(ux0, ux1));
                if (uMax <= uMin + EPSILON) {
                    return null;
                }
            }

            float shift0 = LIGHT_VECTOR_Z * uMin;
            float shift1 = LIGHT_VECTOR_Z * uMax;
            float minShift = Math.min(shift0, shift1);
            float maxShift = Math.max(shift0, shift1);

            float tMin = (blockMinZ - edgeStart - maxShift) / edgeLen;
            float tMax = (blockMaxZ - edgeStart - minShift) / edgeLen;
            tMin = Math.max(0.0f, tMin);
            tMax = Math.min(1.0f, tMax);
            return tMax <= tMin + EPSILON ? null : new Interval(tMin, tMax);
        }

        if (Math.abs(LIGHT_VECTOR_Z) < 1.0e-6f) {
            if (!(fixedAxis > blockMinZ && fixedAxis < blockMaxZ)) {
                return null;
            }
        } else {
            float uz0 = (blockMinZ - fixedAxis) / LIGHT_VECTOR_Z;
            float uz1 = (blockMaxZ - fixedAxis) / LIGHT_VECTOR_Z;
            uMin = Math.max(uMin, Math.min(uz0, uz1));
            uMax = Math.min(uMax, Math.max(uz0, uz1));
            if (uMax <= uMin + EPSILON) {
                return null;
            }
        }

        float shift0 = LIGHT_VECTOR_X * uMin;
        float shift1 = LIGHT_VECTOR_X * uMax;
        float minShift = Math.min(shift0, shift1);
        float maxShift = Math.max(shift0, shift1);

        float tMin = (blockMinX - edgeStart - maxShift) / edgeLen;
        float tMax = (blockMaxX - edgeStart - minShift) / edgeLen;
        tMin = Math.max(0.0f, tMin);
        tMax = Math.min(1.0f, tMax);
        return tMax <= tMin + EPSILON ? null : new Interval(tMin, tMax);
    }

    private static void subtract(List<Interval> visible, Interval blocked) {
        for (int i = 0; i < visible.size(); i++) {
            Interval keep = visible.get(i);
            if (blocked.max <= keep.min + EPSILON || blocked.min >= keep.max - EPSILON) {
                continue;
            }

            visible.remove(i);
            if (blocked.min > keep.min + EPSILON) {
                visible.add(i++, new Interval(keep.min, blocked.min));
            }
            if (blocked.max < keep.max - EPSILON) {
                visible.add(i, new Interval(blocked.max, keep.max));
            }
            i--;
        }
    }

    private static float snap(float value) {
        if (Math.abs(value) <= EPSILON) {
            return 0.0f;
        }
        if (Math.abs(1.0f - value) <= EPSILON) {
            return 1.0f;
        }
        return value;
    }

    private static Vector3f projectToReceiver(World world, float startX, float startY, float startZ) {
        int sampleX = MathHelper.floor(startX + Math.signum(LIGHT_VECTOR_X) * BLOCK_EPSILON);
        int sampleZ = MathHelper.floor(startZ + Math.signum(LIGHT_VECTOR_Z) * BLOCK_EPSILON);

        for (int y = MathHelper.floor(startY) - 1; !world.isOutOfHeightLimit(y); y--) {
            if (isOpaqueFullCube(world, sampleX, y, sampleZ)) {
                float drop = startY - (y + 1.0f);
                if (drop <= 0.0f) {
                    return null;
                }

                return new Vector3f(
                        startX + LIGHT_VECTOR_X * drop,
                        startY - drop,
                        startZ + LIGHT_VECTOR_Z * drop
                );
            }
        }

        return null;
    }

    private static void emitCornerPatch(
            World world,
            Camera camera,
            MatrixStack matrices,
            CornerPatch patch,
            float rainIntensity,
            float rainDirection
    ) {
        if (patch == null) {
            return;
        }

        renderQuadImmediate(
                world,
                camera,
                matrices,
                new Vec3d(patch.topSource.x, patch.topSource.y, patch.topSource.z),
                new Vec3d(patch.bottomSource.x, patch.bottomSource.y, patch.bottomSource.z),
                new Vec3d(patch.bottomReceiver.x, patch.bottomReceiver.y, patch.bottomReceiver.z),
                new Vec3d(patch.topReceiver.x, patch.topReceiver.y, patch.topReceiver.z),
                rainIntensity,
                rainDirection
        );
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

            emitQuadVertices(
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

    private static void emitQuadVertices(
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

    private static final class Interval {
        final float min;
        final float max;

        Interval(float min, float max) {
            this.min = min;
            this.max = max;
        }
    }

    private static final class CornerPatch {
        final Vector3f topSource;
        final Vector3f bottomSource;
        final Vector3f topReceiver;
        final Vector3f bottomReceiver;

        CornerPatch(Vector3f topSource, Vector3f bottomSource, Vector3f topReceiver, Vector3f bottomReceiver) {
            this.topSource = topSource;
            this.bottomSource = bottomSource;
            this.topReceiver = topReceiver;
            this.bottomReceiver = bottomReceiver;
        }
    }
}
