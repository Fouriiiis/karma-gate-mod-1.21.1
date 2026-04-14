package dev.fouriis.karmagate.client.weather;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
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

    private static final int SHADOW_RED = 255;
    private static final int SHADOW_GREEN = 0;
    private static final int SHADOW_BLUE = 0;
    private static final int SHADOW_ALPHA = 96;

    private static final boolean ALONG_X = false;
    private static final boolean ALONG_Z = true;

    private DeathRainWeatherRenderer() {
    }

    public static void render(World world, Camera camera, float tickDelta, MatrixStack matrices) {
        if (world == null || camera == null || matrices == null || !isDeathRainActive()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer quads = immediate.getBuffer(RenderLayer.getDebugQuads());

        Vec3d camPos = camera.getPos();
        int baseX = MathHelper.floor(camPos.x);
        int baseY = MathHelper.floor(camPos.y);
        int baseZ = MathHelper.floor(camPos.z);
        int radiusSq = RADIUS_BLOCKS * RADIUS_BLOCKS;

        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f mat = matrices.peek().getPositionMatrix();

        for (int x = baseX - RADIUS_BLOCKS; x <= baseX + RADIUS_BLOCKS; x++) {
            for (int y = baseY - RADIUS_BLOCKS; y <= baseY + RADIUS_BLOCKS; y++) {
                for (int z = baseZ - RADIUS_BLOCKS; z <= baseZ + RADIUS_BLOCKS; z++) {
                    if (!isInsideRadius(camPos, radiusSq, x, y, z) || !isOpaqueFullCube(world, x, y, z)) {
                        continue;
                    }

                    boolean topCaster = isTopShadowCaster(world, x, y, z);
                    boolean bottomCaster = isBottomShadowCaster(world, x, y, z);

                    if (topCaster) {
                        emitTopPerimeterShadows(world, quads, mat, x, y, z);
                    }
                    if (bottomCaster) {
                        emitBottomPerimeterShadows(world, quads, mat, x, y, z);
                    }
                    if (topCaster && bottomCaster) {
                        emitReceiverStitchShadow(world, quads, mat, x, y, z);
                    }
                }
            }
        }

        matrices.pop();
        immediate.draw();
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

    private static void emitTopPerimeterShadows(World world, VertexConsumer vc, Matrix4f mat, int x, int y, int z) {
        float sourceY = y + 1.0f;

        emitEdgeIfOpen(world, vc, mat, x - 1, y, z, ALONG_Z, x, sourceY, z, z + 1.0f, false, y);
        emitEdgeIfOpen(world, vc, mat, x + 1, y, z, ALONG_Z, x + 1.0f, sourceY, z, z + 1.0f, false, y);
        emitEdgeIfOpen(world, vc, mat, x, y, z - 1, ALONG_X, z, sourceY, x, x + 1.0f, false, y);
        emitEdgeIfOpen(world, vc, mat, x, y, z + 1, ALONG_X, z + 1.0f, sourceY, x, x + 1.0f, false, y);
    }

    private static void emitBottomPerimeterShadows(World world, VertexConsumer vc, Matrix4f mat, int x, int y, int z) {
        float sourceY = y;

        if (LIGHT_VECTOR_X > EPSILON) {
            emitEdgeIfOpen(world, vc, mat, x - 1, y, z, ALONG_Z, x, sourceY, z, z + 1.0f, true, y);
        }
        if (LIGHT_VECTOR_X < -EPSILON) {
            emitEdgeIfOpen(world, vc, mat, x + 1, y, z, ALONG_Z, x + 1.0f, sourceY, z, z + 1.0f, true, y);
        }
        if (LIGHT_VECTOR_Z > EPSILON) {
            emitEdgeIfOpen(world, vc, mat, x, y, z - 1, ALONG_X, z, sourceY, x, x + 1.0f, true, y);
        }
        if (LIGHT_VECTOR_Z < -EPSILON) {
            emitEdgeIfOpen(world, vc, mat, x, y, z + 1, ALONG_X, z + 1.0f, sourceY, x, x + 1.0f, true, y);
        }
    }

    private static void emitEdgeIfOpen(
            World world,
            VertexConsumer vc,
            Matrix4f mat,
            int neighborX,
            int neighborY,
            int neighborZ,
            boolean alongZ,
            float fixedAxis,
            float sourceY,
            float edgeStart,
            float edgeEnd,
            boolean bottomPass,
            int layerY
    ) {
        if (!isOpaqueFullCube(world, neighborX, neighborY, neighborZ)) {
            castEdge(world, vc, mat, alongZ, fixedAxis, sourceY, edgeStart, edgeEnd, bottomPass, layerY);
        }
    }

    private static void emitReceiverStitchShadow(World world, VertexConsumer vc, Matrix4f mat, int x, int y, int z) {
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

        emitCornerPatch(vc, mat, first);
        emitCornerPatch(vc, mat, second);
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
            VertexConsumer vc,
            Matrix4f mat,
            boolean alongZ,
            float fixedAxis,
            float sourceY,
            float edgeStart,
            float edgeEnd,
            boolean bottomPass,
            int layerY
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

                emitQuad(
                        vc,
                        mat,
                        fixedAxis, sourceY, start,
                        fixedAxis, sourceY, end,
                        recv1.x, recv1.y, recv1.z,
                        recv0.x, recv0.y, recv0.z
                );
            } else {
                float exitZ = fixedAxis + LIGHT_VECTOR_Z;
                recv0 = projectToReceiver(world, start + LIGHT_VECTOR_X, exitY, exitZ);
                recv1 = projectToReceiver(world, end + LIGHT_VECTOR_X, exitY, exitZ);
                if (recv0 == null || recv1 == null) {
                    continue;
                }

                emitQuad(
                        vc,
                        mat,
                        start, sourceY, fixedAxis,
                        end, sourceY, fixedAxis,
                        recv1.x, recv1.y, recv1.z,
                        recv0.x, recv0.y, recv0.z
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

    private static boolean isDiagonalSplitCorner(World world, int layerY, float cornerX, float cornerZ) {
        if (!isNearInteger(cornerX) || !isNearInteger(cornerZ)) {
            return false;
        }

        int gridX = Math.round(cornerX);
        int gridZ = Math.round(cornerZ);

        boolean nw = isOpaqueFullCube(world, gridX - 1, layerY, gridZ - 1);
        boolean ne = isOpaqueFullCube(world, gridX, layerY, gridZ - 1);
        boolean sw = isOpaqueFullCube(world, gridX - 1, layerY, gridZ);
        boolean se = isOpaqueFullCube(world, gridX, layerY, gridZ);

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

    private static void emitCornerPatch(VertexConsumer vc, Matrix4f mat, CornerPatch patch) {
        if (patch == null) {
            return;
        }

        emitQuad(
                vc,
                mat,
                patch.topSource.x, patch.topSource.y, patch.topSource.z,
                patch.bottomSource.x, patch.bottomSource.y, patch.bottomSource.z,
                patch.bottomReceiver.x, patch.bottomReceiver.y, patch.bottomReceiver.z,
                patch.topReceiver.x, patch.topReceiver.y, patch.topReceiver.z
        );
    }

    private static void emitQuad(
            VertexConsumer vc,
            Matrix4f mat,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float cx,
            float cy,
            float cz,
            float dx,
            float dy,
            float dz
    ) {
        float abx = bx - ax;
        float aby = by - ay;
        float abz = bz - az;
        float adx = dx - ax;
        float ady = dy - ay;
        float adz = dz - az;

        float nx = aby * adz - abz * ady;
        float ny = abz * adx - abx * adz;
        float nz = abx * ady - aby * adx;

        float lenSq = nx * nx + ny * ny + nz * nz;
        if (lenSq > 1.0e-6f) {
            float invLen = MathHelper.inverseSqrt(lenSq);
            nx *= invLen;
            ny *= invLen;
            nz *= invLen;
        } else {
            nx = 0.0f;
            ny = 1.0f;
            nz = 0.0f;
        }

        emitQuadVertex(vc, mat, ax, ay, az, nx, ny, nz);
        emitQuadVertex(vc, mat, bx, by, bz, nx, ny, nz);
        emitQuadVertex(vc, mat, cx, cy, cz, nx, ny, nz);
        emitQuadVertex(vc, mat, dx, dy, dz, nx, ny, nz);

        emitQuadVertex(vc, mat, dx, dy, dz, -nx, -ny, -nz);
        emitQuadVertex(vc, mat, cx, cy, cz, -nx, -ny, -nz);
        emitQuadVertex(vc, mat, bx, by, bz, -nx, -ny, -nz);
        emitQuadVertex(vc, mat, ax, ay, az, -nx, -ny, -nz);
    }

    private static void emitQuadVertex(
            VertexConsumer vc,
            Matrix4f mat,
            float x,
            float y,
            float z,
            float nx,
            float ny,
            float nz
    ) {
        vc.vertex(mat, x, y, z)
                .color(SHADOW_RED, SHADOW_GREEN, SHADOW_BLUE, SHADOW_ALPHA)
                .normal(nx, ny, nz);
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
