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

    private DeathRainWeatherRenderer() {
    }

    public static void render(World world, Camera camera, float tickDelta, MatrixStack matrices) {
        if (world == null || camera == null || matrices == null) {
            return;
        }

        if (!isDeathRainActive()) {
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
                    double cx = x + 0.5;
                    double cy = y + 0.5;
                    double cz = z + 0.5;

                    if (camPos.squaredDistanceTo(cx, cy, cz) > radiusSq) {
                        continue;
                    }

                    if (!isOpaqueFullCube(world, x, y, z)) {
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

    private static boolean isDeathRainActive() {
        if (GlobalRainClientState.hasSync()) {
            return true;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        return client.getServer() != null;
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

        BlockPos above = new BlockPos(x, y + 1, z);
        return isAir(world, x, y + 1, z) && world.isSkyVisible(above);
    }

    private static boolean isBottomShadowCaster(World world, int x, int y, int z) {
        return isAir(world, x, y - 1, z);
    }

    private static boolean isBottomFaceExposed(World world, int x, int y, int z) {
        return isOpaqueFullCube(world, x, y, z) && isAir(world, x, y - 1, z);
    }

    private static void emitTopPerimeterShadows(
            World world,
            VertexConsumer vc,
            Matrix4f mat,
            int x,
            int y,
            int z
    ) {
        float sourceY = y + 1.0f;

        if (!isOpaqueFullCube(world, x - 1, y, z)) {
            castEdgeAlongZ(world, vc, mat, x, sourceY, z, z + 1.0f, false, y);
        }
        if (!isOpaqueFullCube(world, x + 1, y, z)) {
            castEdgeAlongZ(world, vc, mat, x + 1.0f, sourceY, z, z + 1.0f, false, y);
        }
        if (!isOpaqueFullCube(world, x, y, z - 1)) {
            castEdgeAlongX(world, vc, mat, z, sourceY, x, x + 1.0f, false, y);
        }
        if (!isOpaqueFullCube(world, x, y, z + 1)) {
            castEdgeAlongX(world, vc, mat, z + 1.0f, sourceY, x, x + 1.0f, false, y);
        }
    }

    private static void emitBottomPerimeterShadows(
            World world,
            VertexConsumer vc,
            Matrix4f mat,
            int x,
            int y,
            int z
    ) {
        float sourceY = y;

        if (LIGHT_VECTOR_X > EPSILON && !isOpaqueFullCube(world, x - 1, y, z)) {
            castEdgeAlongZ(world, vc, mat, x, sourceY, z, z + 1.0f, true, y);
        }
        if (LIGHT_VECTOR_X < -EPSILON && !isOpaqueFullCube(world, x + 1, y, z)) {
            castEdgeAlongZ(world, vc, mat, x + 1.0f, sourceY, z, z + 1.0f, true, y);
        }
        if (LIGHT_VECTOR_Z > EPSILON && !isOpaqueFullCube(world, x, y, z - 1)) {
            castEdgeAlongX(world, vc, mat, z, sourceY, x, x + 1.0f, true, y);
        }
        if (LIGHT_VECTOR_Z < -EPSILON && !isOpaqueFullCube(world, x, y, z + 1)) {
            castEdgeAlongX(world, vc, mat, z + 1.0f, sourceY, x, x + 1.0f, true, y);
        }
    }

    private static void emitReceiverStitchShadow(
            World world,
            VertexConsumer vc,
            Matrix4f mat,
            int x,
            int y,
            int z
    ) {
        if (Math.abs(LIGHT_VECTOR_X) <= EPSILON || Math.abs(LIGHT_VECTOR_Z) <= EPSILON) {
            return;
        }

        CornerPatch first = null;
        CornerPatch second = null;

        if (LIGHT_VECTOR_X > EPSILON && LIGHT_VECTOR_Z > EPSILON) {
            if (!isOpaqueFullCube(world, x - 1, y, z)) {
                first = findCornerPatchAlongZ(world, x, y, z, z + 1.0f, false);
            }
            if (!isOpaqueFullCube(world, x, y, z - 1)) {
                second = findCornerPatchAlongX(world, z, y, x, x + 1.0f, false);
            }
        } else if (LIGHT_VECTOR_X > EPSILON) {
            if (!isOpaqueFullCube(world, x - 1, y, z)) {
                first = findCornerPatchAlongZ(world, x, y, z, z + 1.0f, true);
            }
            if (!isOpaqueFullCube(world, x, y, z + 1)) {
                second = findCornerPatchAlongX(world, z + 1.0f, y, x, x + 1.0f, false);
            }
        } else if (LIGHT_VECTOR_Z > EPSILON) {
            if (!isOpaqueFullCube(world, x + 1, y, z)) {
                first = findCornerPatchAlongZ(world, x + 1.0f, y, z, z + 1.0f, false);
            }
            if (!isOpaqueFullCube(world, x, y, z - 1)) {
                second = findCornerPatchAlongX(world, z, y, x, x + 1.0f, true);
            }
        } else {
            if (!isOpaqueFullCube(world, x + 1, y, z)) {
                first = findCornerPatchAlongZ(world, x + 1.0f, y, z, z + 1.0f, true);
            }
            if (!isOpaqueFullCube(world, x, y, z + 1)) {
                second = findCornerPatchAlongX(world, z + 1.0f, y, x, x + 1.0f, true);
            }
        }

        emitCornerPatch(vc, mat, first);
        emitCornerPatch(vc, mat, second);
    }

    private static CornerPatch findCornerPatchAlongZ(
            World world,
            float x,
            int layerY,
            float z0,
            float z1,
            boolean useMinEndpoint
    ) {
        float minZ = Math.min(z0, z1);
        float maxZ = Math.max(z0, z1);
        float edgeLen = maxZ - minZ;
        if (edgeLen <= EPSILON) {
            return null;
        }

        List<Interval> topVisible = computeVisibleIntervalsAlongZ(world, x, minZ, edgeLen, layerY);
        List<Interval> bottomVisible = computeVisibleIntervalsAlongZ(world, x, minZ, edgeLen, layerY - 1);

        for (Interval overlap : intersectIntervals(topVisible, bottomVisible)) {
            float t0 = snap(overlap.min);
            float t1 = snap(overlap.max);
            if (t1 - t0 <= EPSILON) {
                continue;
            }

            float topSrcZ = minZ + edgeLen * (useMinEndpoint ? t0 : t1);
            float bottomSrcZ = minZ + edgeLen * (useMinEndpoint ? t0 : t1);

            bottomSrcZ += useMinEndpoint
                    ? computeBottomInsetAlongZ(world, layerY, x, bottomSrcZ, true)
                    : -computeBottomInsetAlongZ(world, layerY, x, bottomSrcZ, false);

            Vector3f topSource = new Vector3f(x, layerY + 1.0f, topSrcZ);
            Vector3f bottomSource = new Vector3f(x, layerY, bottomSrcZ);
            Vector3f topRecv = projectToReceiver(world, x + LIGHT_VECTOR_X, layerY, topSrcZ + LIGHT_VECTOR_Z);
            Vector3f botRecv = projectToReceiver(world, x + LIGHT_VECTOR_X, layerY - 1, bottomSrcZ + LIGHT_VECTOR_Z);

            if (topRecv != null && botRecv != null
                    && !isDiagonalSplitCorner(world, layerY, topSource.x, topSource.z)) {
                return new CornerPatch(topSource, bottomSource, topRecv, botRecv);
            }
        }

        return null;
    }

    private static CornerPatch findCornerPatchAlongX(
            World world,
            float z,
            int layerY,
            float x0,
            float x1,
            boolean useMinEndpoint
    ) {
        float minX = Math.min(x0, x1);
        float maxX = Math.max(x0, x1);
        float edgeLen = maxX - minX;
        if (edgeLen <= EPSILON) {
            return null;
        }

        List<Interval> topVisible = computeVisibleIntervalsAlongX(world, z, minX, edgeLen, layerY);
        List<Interval> bottomVisible = computeVisibleIntervalsAlongX(world, z, minX, edgeLen, layerY - 1);

        for (Interval overlap : intersectIntervals(topVisible, bottomVisible)) {
            float t0 = snap(overlap.min);
            float t1 = snap(overlap.max);
            if (t1 - t0 <= EPSILON) {
                continue;
            }

            float topSrcX = minX + edgeLen * (useMinEndpoint ? t0 : t1);
            float bottomSrcX = minX + edgeLen * (useMinEndpoint ? t0 : t1);

            bottomSrcX += useMinEndpoint
                    ? computeBottomInsetAlongX(world, layerY, bottomSrcX, z, true)
                    : -computeBottomInsetAlongX(world, layerY, bottomSrcX, z, false);

            Vector3f topSource = new Vector3f(topSrcX, layerY + 1.0f, z);
            Vector3f bottomSource = new Vector3f(bottomSrcX, layerY, z);
            Vector3f topRecv = projectToReceiver(world, topSrcX + LIGHT_VECTOR_X, layerY, z + LIGHT_VECTOR_Z);
            Vector3f botRecv = projectToReceiver(world, bottomSrcX + LIGHT_VECTOR_X, layerY - 1, z + LIGHT_VECTOR_Z);

            if (topRecv != null && botRecv != null
                    && !isDiagonalSplitCorner(world, layerY, topSource.x, topSource.z)) {
                return new CornerPatch(topSource, bottomSource, topRecv, botRecv);
            }
        }

        return null;
    }

    private static void castEdgeAlongZ(
            World world,
            VertexConsumer vc,
            Matrix4f mat,
            float x,
            float sourceY,
            float z0,
            float z1,
            boolean bottomPass,
            int layerY
    ) {
        float minZ = Math.min(z0, z1);
        float maxZ = Math.max(z0, z1);
        float edgeLen = maxZ - minZ;
        if (edgeLen <= EPSILON) {
            return;
        }

        List<Interval> visible = computeVisibleIntervalsAlongZ(
                world,
                x,
                minZ,
                edgeLen,
                MathHelper.floor(sourceY - EPSILON)
        );

        if (visible.isEmpty()) {
            return;
        }

        for (Interval interval : visible) {
            float t0 = snap(interval.min);
            float t1 = snap(interval.max);
            if (t1 - t0 <= EPSILON) {
                continue;
            }

            float srcZ0 = minZ + edgeLen * t0;
            float srcZ1 = minZ + edgeLen * t1;

            if (bottomPass) {
                srcZ0 += computeBottomInsetAlongZ(world, layerY, x, srcZ0, true);
                srcZ1 -= computeBottomInsetAlongZ(world, layerY, x, srcZ1, false);

                if (srcZ1 - srcZ0 <= EPSILON) {
                    continue;
                }
            }

            float exitX = x + LIGHT_VECTOR_X;
            float exitY = sourceY - 1.0f;
            float exitZ0 = srcZ0 + LIGHT_VECTOR_Z;
            float exitZ1 = srcZ1 + LIGHT_VECTOR_Z;

            Vector3f recv0 = projectToReceiver(world, exitX, exitY, exitZ0);
            Vector3f recv1 = projectToReceiver(world, exitX, exitY, exitZ1);
            if (recv0 == null || recv1 == null) {
                continue;
            }

            emitQuad(
                    vc,
                    mat,
                    x, sourceY, srcZ0,
                    x, sourceY, srcZ1,
                    recv1.x, recv1.y, recv1.z,
                    recv0.x, recv0.y, recv0.z
            );
        }
    }

    private static void castEdgeAlongX(
            World world,
            VertexConsumer vc,
            Matrix4f mat,
            float z,
            float sourceY,
            float x0,
            float x1,
            boolean bottomPass,
            int layerY
    ) {
        float minX = Math.min(x0, x1);
        float maxX = Math.max(x0, x1);
        float edgeLen = maxX - minX;
        if (edgeLen <= EPSILON) {
            return;
        }

        List<Interval> visible = computeVisibleIntervalsAlongX(
                world,
                z,
                minX,
                edgeLen,
                MathHelper.floor(sourceY - EPSILON)
        );

        if (visible.isEmpty()) {
            return;
        }

        for (Interval interval : visible) {
            float t0 = snap(interval.min);
            float t1 = snap(interval.max);
            if (t1 - t0 <= EPSILON) {
                continue;
            }

            float srcX0 = minX + edgeLen * t0;
            float srcX1 = minX + edgeLen * t1;

            if (bottomPass) {
                srcX0 += computeBottomInsetAlongX(world, layerY, srcX0, z, true);
                srcX1 -= computeBottomInsetAlongX(world, layerY, srcX1, z, false);

                if (srcX1 - srcX0 <= EPSILON) {
                    continue;
                }
            }

            float exitY = sourceY - 1.0f;
            float exitZ = z + LIGHT_VECTOR_Z;
            float exitX0 = srcX0 + LIGHT_VECTOR_X;
            float exitX1 = srcX1 + LIGHT_VECTOR_X;

            Vector3f recv0 = projectToReceiver(world, exitX0, exitY, exitZ);
            Vector3f recv1 = projectToReceiver(world, exitX1, exitY, exitZ);
            if (recv0 == null || recv1 == null) {
                continue;
            }

            emitQuad(
                    vc,
                    mat,
                    srcX0, sourceY, z,
                    srcX1, sourceY, z,
                    recv1.x, recv1.y, recv1.z,
                    recv0.x, recv0.y, recv0.z
            );
        }
    }

    private static List<Interval> computeVisibleIntervalsAlongZ(
            World world,
            float x,
            float minZ,
            float edgeLen,
            int clipLayerY
    ) {
        List<Interval> visible = new ArrayList<>();
        visible.add(new Interval(0.0f, 1.0f));

        float sweepMinX = Math.min(x, x + LIGHT_VECTOR_X);
        float sweepMaxX = Math.max(x, x + LIGHT_VECTOR_X);
        float sweepMinZ = Math.min(minZ, minZ + LIGHT_VECTOR_Z);
        float sweepMaxZ = Math.max(minZ + edgeLen, minZ + edgeLen + LIGHT_VECTOR_Z);

        int minCellX = MathHelper.floor(sweepMinX) - 1;
        int maxCellX = MathHelper.floor(sweepMaxX) + 1;
        int minCellZ = MathHelper.floor(sweepMinZ) - 1;
        int maxCellZ = MathHelper.floor(sweepMaxZ) + 1;

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                Interval blocked = clipSweepAgainstBlock(world, x, minZ, edgeLen, clipLayerY, cellX, cellZ, true);
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

    private static List<Interval> computeVisibleIntervalsAlongX(
            World world,
            float z,
            float minX,
            float edgeLen,
            int clipLayerY
    ) {
        List<Interval> visible = new ArrayList<>();
        visible.add(new Interval(0.0f, 1.0f));

        float sweepMinX = Math.min(minX, minX + LIGHT_VECTOR_X);
        float sweepMaxX = Math.max(minX + edgeLen, minX + edgeLen + LIGHT_VECTOR_X);
        float sweepMinZ = Math.min(z, z + LIGHT_VECTOR_Z);
        float sweepMaxZ = Math.max(z, z + LIGHT_VECTOR_Z);

        int minCellX = MathHelper.floor(sweepMinX) - 1;
        int maxCellX = MathHelper.floor(sweepMaxX) + 1;
        int minCellZ = MathHelper.floor(sweepMinZ) - 1;
        int maxCellZ = MathHelper.floor(sweepMaxZ) + 1;

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                Interval blocked = clipSweepAgainstBlock(world, z, minX, edgeLen, clipLayerY, cellX, cellZ, false);
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

    private static float computeBottomInsetAlongZ(
            World world,
            int layerY,
            float cornerX,
            float cornerZ,
            boolean minSide
    ) {
        if (!isBlockedBottomCorner(world, layerY, cornerX, cornerZ)) {
            return 0.0f;
        }

        return minSide
                ? Math.max(0.0f, LIGHT_VECTOR_Z)
                : Math.max(0.0f, -LIGHT_VECTOR_Z);
    }

    private static float computeBottomInsetAlongX(
            World world,
            int layerY,
            float cornerX,
            float cornerZ,
            boolean minSide
    ) {
        if (!isBlockedBottomCorner(world, layerY, cornerX, cornerZ)) {
            return 0.0f;
        }

        return minSide
                ? Math.max(0.0f, LIGHT_VECTOR_X)
                : Math.max(0.0f, -LIGHT_VECTOR_X);
    }

    private static boolean isBlockedBottomCorner(
            World world,
            int layerY,
            float cornerX,
            float cornerZ
    ) {
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

        if (count != 3) {
            return false;
        }

        float sampleX = cornerX + Math.signum(LIGHT_VECTOR_X) * BLOCK_EPSILON;
        float sampleZ = cornerZ + Math.signum(LIGHT_VECTOR_Z) * BLOCK_EPSILON;

        int targetX = MathHelper.floor(sampleX);
        int targetZ = MathHelper.floor(sampleZ);

        return isBottomFaceExposed(world, targetX, layerY, targetZ);
    }

    private static boolean isNearInteger(float value) {
        return Math.abs(value - Math.round(value)) <= EPSILON;
    }

    private static boolean isDiagonalSplitCorner(
            World world,
            int layerY,
            float cornerX,
            float cornerZ
    ) {
        if (!isNearInteger(cornerX) || !isNearInteger(cornerZ)) {
            return false;
        }

        int gridX = Math.round(cornerX);
        int gridZ = Math.round(cornerZ);

        boolean nw = isOpaqueFullCube(world, gridX - 1, layerY, gridZ - 1);
        boolean ne = isOpaqueFullCube(world, gridX,     layerY, gridZ - 1);
        boolean sw = isOpaqueFullCube(world, gridX - 1, layerY, gridZ);
        boolean se = isOpaqueFullCube(world, gridX,     layerY, gridZ);

        boolean diagonalA = nw && se && !ne && !sw;
        boolean diagonalB = ne && sw && !nw && !se;
        return diagonalA || diagonalB;
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
            if (tMax <= tMin + EPSILON) {
                return null;
            }

            return new Interval(tMin, tMax);
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
        if (tMax <= tMin + EPSILON) {
            return null;
        }

        return new Interval(tMin, tMax);
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
