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
    private static final float LIGHT_VECTOR_X = (float) Math.tan(Math.toRadians(20.0f));
    private static final float LIGHT_VECTOR_Z = (float) Math.tan(Math.toRadians(20.0f));

    private static final float EPSILON = 1.0e-4f;
    private static final float BLOCK_EPSILON = 0.001f;

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
        VertexConsumer lines = immediate.getBuffer(RenderLayer.LINES);

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

                    if (isTopShadowCaster(world, x, y, z)) {
                        emitTopPerimeterShadows(world, lines, mat, x, y, z);
                    }

                    if (isBottomShadowCaster(world, x, y, z)) {
                        emitBottomPerimeterShadows(world, lines, mat, x, y, z);
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

        // Bottom silhouette gating.
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

    // Edge parallel to Z at constant X.
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

        List<Interval> visible = new ArrayList<>();
        visible.add(new Interval(0.0f, 1.0f));

        int clipLayerY = MathHelper.floor(sourceY - EPSILON);
        float sweepMinX = Math.min(x, x + LIGHT_VECTOR_X);
        float sweepMaxX = Math.max(x, x + LIGHT_VECTOR_X);
        float sweepMinZ = Math.min(minZ, minZ + LIGHT_VECTOR_Z);
        float sweepMaxZ = Math.max(maxZ, maxZ + LIGHT_VECTOR_Z);

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
                        return;
                    }
                }
            }
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
            float exitZ0 = srcZ0 + LIGHT_VECTOR_Z;
            float exitZ1 = srcZ1 + LIGHT_VECTOR_Z;
            float exitY = sourceY - 1.0f;

            Vector3f recv0 = projectToReceiver(world, exitX, exitY, exitZ0);
            Vector3f recv1 = projectToReceiver(world, exitX, exitY, exitZ1);
            if (recv0 == null || recv1 == null) {
                continue;
            }

            emitLine(vc, mat, x, sourceY, srcZ0, x, sourceY, srcZ1);
            emitLine(vc, mat, x, sourceY, srcZ0, exitX, exitY, exitZ0);
            emitLine(vc, mat, x, sourceY, srcZ1, exitX, exitY, exitZ1);
            emitLine(vc, mat, exitX, exitY, exitZ0, exitX, exitY, exitZ1);
            emitLine(vc, mat, exitX, exitY, exitZ0, recv0.x, recv0.y, recv0.z);
            emitLine(vc, mat, exitX, exitY, exitZ1, recv1.x, recv1.y, recv1.z);
            emitLine(vc, mat, recv0.x, recv0.y, recv0.z, recv1.x, recv1.y, recv1.z);
        }
    }

    // Edge parallel to X at constant Z.
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

        List<Interval> visible = new ArrayList<>();
        visible.add(new Interval(0.0f, 1.0f));

        int clipLayerY = MathHelper.floor(sourceY - EPSILON);
        float sweepMinX = Math.min(minX, minX + LIGHT_VECTOR_X);
        float sweepMaxX = Math.max(maxX, maxX + LIGHT_VECTOR_X);
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
                        return;
                    }
                }
            }
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

            float exitX0 = srcX0 + LIGHT_VECTOR_X;
            float exitX1 = srcX1 + LIGHT_VECTOR_X;
            float exitZ = z + LIGHT_VECTOR_Z;
            float exitY = sourceY - 1.0f;

            Vector3f recv0 = projectToReceiver(world, exitX0, exitY, exitZ);
            Vector3f recv1 = projectToReceiver(world, exitX1, exitY, exitZ);
            if (recv0 == null || recv1 == null) {
                continue;
            }

            emitLine(vc, mat, srcX0, sourceY, z, srcX1, sourceY, z);
            emitLine(vc, mat, srcX0, sourceY, z, exitX0, exitY, exitZ);
            emitLine(vc, mat, srcX1, sourceY, z, exitX1, exitY, exitZ);
            emitLine(vc, mat, exitX0, exitY, exitZ, exitX1, exitY, exitZ);
            emitLine(vc, mat, exitX0, exitY, exitZ, recv0.x, recv0.y, recv0.z);
            emitLine(vc, mat, exitX1, exitY, exitZ, recv1.x, recv1.y, recv1.z);
            emitLine(vc, mat, recv0.x, recv0.y, recv0.z, recv1.x, recv1.y, recv1.z);
        }
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
        boolean ne = isBottomFaceExposed(world, gridX,     layerY, gridZ - 1);
        boolean sw = isBottomFaceExposed(world, gridX - 1, layerY, gridZ);
        boolean se = isBottomFaceExposed(world, gridX,     layerY, gridZ);

        int count = 0;
        if (nw) count++;
        if (ne) count++;
        if (sw) count++;
        if (se) count++;

        // Only treat exposed-bottom L-corners specially.
        if (count != 3) {
            return false;
        }

        float sampleX = cornerX + Math.signum(LIGHT_VECTOR_X) * BLOCK_EPSILON;
        float sampleZ = cornerZ + Math.signum(LIGHT_VECTOR_Z) * BLOCK_EPSILON;

        int targetX = MathHelper.floor(sampleX);
        int targetZ = MathHelper.floor(sampleZ);

        // If the light leaves through another exposed-bottom block in the local 2x2,
        // the raw corner is invalid and must be inset along the edge direction.
        return isBottomFaceExposed(world, targetX, layerY, targetZ);
    }

    private static boolean isNearInteger(float value) {
        return Math.abs(value - Math.round(value)) <= EPSILON;
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

    private static void emitLine(
            VertexConsumer vc,
            Matrix4f mat,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2
    ) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;

        float lenSq = dx * dx + dy * dy + dz * dz;
        float nx = 0.0f;
        float ny = 1.0f;
        float nz = 0.0f;
        if (lenSq > 1.0e-6f) {
            float invLen = MathHelper.inverseSqrt(lenSq);
            nx = dx * invLen;
            ny = dy * invLen;
            nz = dz * invLen;
        }

        vc.vertex(mat, x1, y1, z1)
                .color(255, 0, 0, 255)
                .normal(nx, ny, nz);

        vc.vertex(mat, x2, y2, z2)
                .color(255, 0, 0, 255)
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
}