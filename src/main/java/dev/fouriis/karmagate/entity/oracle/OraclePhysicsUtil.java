package dev.fouriis.karmagate.entity.oracle;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OraclePhysicsUtil {
    private static final double COLLISION_EPSILON = 1.0E-4;

    private OraclePhysicsUtil() {
    }

    public static Vec3d collidePoint(World world, Vec3d point, double radius) {
        return collidePoint(world, point, radius, null);
    }

    public static Vec3d collidePoint(World world, Vec3d point, double radius, CollisionCache cache) {
        if (world == null || radius <= 0.0) {
            return point;
        }

        Vec3d result = point;
        BlockPos center = BlockPos.ofFloored(point);
        int reach = Math.max(1, (int) Math.ceil(radius));
        for (int x = -reach; x <= reach; x++) {
            for (int y = -reach; y <= reach; y++) {
                for (int z = -reach; z <= reach; z++) {
                    BlockPos blockPos = center.add(x, y, z);
                    List<Box> boxes = cache == null ? collisionBoxes(world, blockPos) : cache.collisionBoxes(world, blockPos);
                    for (Box localBox : boxes) {
                        result = pushPointOutOfBox(result, localBox, radius);
                    }
                }
            }
        }
        return result;
    }

    public static Vec3d segmentCollisionCorrection(World world, Vec3d start, Vec3d end,
                                                   double radius, double sampleSpacing, CollisionCache cache) {
        Vec3d segment = end.subtract(start);
        double length = segment.length();
        if (length < 1.0E-6 || sampleSpacing <= 0.0) {
            return Vec3d.ZERO;
        }

        Vec3d strongest = Vec3d.ZERO;
        double strongestLengthSquared = 0.0;
        int intervals = Math.max(2, (int) Math.ceil(length / sampleSpacing));
        for (int i = 1; i < intervals; i++) {
            double t = i / (double) intervals;
            Vec3d sample = start.lerp(end, t);
            Vec3d collided = collidePoint(world, sample, radius, cache);
            Vec3d correction = collided.subtract(sample);
            double correctionLengthSquared = correction.lengthSquared();
            if (correctionLengthSquared > strongestLengthSquared) {
                strongest = correction;
                strongestLengthSquared = correctionLengthSquared;
            }
        }
        return strongest;
    }

    private static List<Box> collisionBoxes(World world, BlockPos blockPos) {
        VoxelShape shape = world.getBlockState(blockPos).getCollisionShape(world, blockPos);
        if (shape.isEmpty()) {
            return List.of();
        }
        List<Box> boxes = new ArrayList<>();
        for (Box localBox : shape.getBoundingBoxes()) {
            boxes.add(localBox.offset(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
        }
        return boxes;
    }

    private static Vec3d pushPointOutOfBox(Vec3d point, Box box, double radius) {
        Box expanded = box.expand(radius);
        if (!contains(expanded, point)) {
            return point;
        }

        double minX = point.x - expanded.minX;
        double maxX = expanded.maxX - point.x;
        double minY = point.y - expanded.minY;
        double maxY = expanded.maxY - point.y;
        double minZ = point.z - expanded.minZ;
        double maxZ = expanded.maxZ - point.z;

        double nearest = minX;
        int face = 0;
        if (maxX < nearest) {
            nearest = maxX;
            face = 1;
        }
        if (minY < nearest) {
            nearest = minY;
            face = 2;
        }
        if (maxY < nearest) {
            nearest = maxY;
            face = 3;
        }
        if (minZ < nearest) {
            nearest = minZ;
            face = 4;
        }
        if (maxZ < nearest) {
            face = 5;
        }

        return switch (face) {
            case 0 -> new Vec3d(expanded.minX - COLLISION_EPSILON, point.y, point.z);
            case 1 -> new Vec3d(expanded.maxX + COLLISION_EPSILON, point.y, point.z);
            case 2 -> new Vec3d(point.x, expanded.minY - COLLISION_EPSILON, point.z);
            case 3 -> new Vec3d(point.x, expanded.maxY + COLLISION_EPSILON, point.z);
            case 4 -> new Vec3d(point.x, point.y, expanded.minZ - COLLISION_EPSILON);
            default -> new Vec3d(point.x, point.y, expanded.maxZ + COLLISION_EPSILON);
        };
    }

    private static boolean contains(Box box, Vec3d point) {
        return point.x >= box.minX && point.x <= box.maxX
                && point.y >= box.minY && point.y <= box.maxY
                && point.z >= box.minZ && point.z <= box.maxZ;
    }

    public static final class CollisionCache {
        private final Map<BlockPos, List<Box>> boxesByBlock = new HashMap<>();
        private BlockPos preloadedCenter = BlockPos.ORIGIN;
        private int preloadedRadius = -1;
        private boolean preloaded;

        public void clear() {
            boxesByBlock.clear();
            preloaded = false;
            preloadedRadius = -1;
            preloadedCenter = BlockPos.ORIGIN;
        }

        public void preloadCube(World world, BlockPos center, int radius) {
            if (world == null || center == null || radius < 0) {
                return;
            }
            center = center.toImmutable();
            if (preloaded && preloadedRadius == radius && preloadedCenter.equals(center)) {
                return;
            }

            boxesByBlock.clear();
            preloaded = true;
            preloadedCenter = center;
            preloadedRadius = radius;
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        BlockPos blockPos = center.add(x, y, z).toImmutable();
                        List<Box> boxes = OraclePhysicsUtil.collisionBoxes(world, blockPos);
                        if (!boxes.isEmpty()) {
                            boxesByBlock.put(blockPos, boxes);
                        }
                    }
                }
            }
        }

        private List<Box> collisionBoxes(World world, BlockPos blockPos) {
            if (preloaded) {
                if (Math.abs(blockPos.getX() - preloadedCenter.getX()) > preloadedRadius
                        || Math.abs(blockPos.getY() - preloadedCenter.getY()) > preloadedRadius
                        || Math.abs(blockPos.getZ() - preloadedCenter.getZ()) > preloadedRadius) {
                    return List.of();
                }
                return boxesByBlock.getOrDefault(blockPos, List.of());
            }
            return boxesByBlock.computeIfAbsent(blockPos.toImmutable(), pos -> OraclePhysicsUtil.collisionBoxes(world, pos));
        }
    }
}
