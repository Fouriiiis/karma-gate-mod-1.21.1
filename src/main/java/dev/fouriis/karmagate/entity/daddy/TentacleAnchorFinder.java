package dev.fouriis.karmagate.entity.daddy;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public final class TentacleAnchorFinder {
    private TentacleAnchorFinder() {
    }

    public static Vec3d findBestAnchor(
            World world,
            Entity entity,
            Vec3d bodyPos,
            Vec3d socketPos,
            Vec3d targetPos,
            Vec3d idealAnchorPos,
            DaddyVariantConfig config,
            boolean supportTentacle,
            Random random,
            float extraForwardBias
    ) {
        Vec3d toTarget = targetPos.subtract(bodyPos);
        Vec3d targetDir = toTarget.lengthSquared() < 1.0e-5 ? Vec3d.ZERO : toTarget.normalize();
        double searchRadius = config.anchorSearchRadius() + (extraForwardBias * 0.75f);
        Vec3d bestAnchor = null;
        double bestScore = -1.0e9;

        int attempts = 44;
        for (int i = 0; i < attempts; i++) {
            Vec3d sample = idealAnchorPos.add(
                    (random.nextFloat() * 2f - 1f) * searchRadius,
                    (random.nextFloat() * 2f - 1f) * Math.max(2.0f, searchRadius * 0.7f),
                    (random.nextFloat() * 2f - 1f) * searchRadius
            );
            BlockPos solid = BlockPos.ofFloored(sample);
            if (!world.getBlockState(solid).isOpaqueFullCube(world, solid)) {
                continue;
            }

            for (Direction face : Direction.values()) {
                BlockPos neighbor = solid.offset(face);
                BlockState adjacent = world.getBlockState(neighbor);
                if (adjacent.isOpaqueFullCube(world, neighbor)) {
                    continue;
                }

                Vec3d anchor = Vec3d.ofCenter(solid).add(Vec3d.of(face.getVector()).multiply(0.52));
                if (isInsideSolid(world, anchor)) {
                    continue;
                }

                double distToSocket = anchor.distanceTo(socketPos);
                if (distToSocket > config.tentacleLength() * 1.25f) {
                    continue;
                }

                double score = scoreAnchor(
                        world,
                        entity,
                        bodyPos,
                        socketPos,
                        anchor,
                        targetDir,
                        idealAnchorPos,
                        supportTentacle,
                        config,
                        extraForwardBias
                );
                if (score > bestScore) {
                    bestScore = score;
                    bestAnchor = anchor;
                }
            }
        }

        return bestAnchor;
    }

    public static int countAnchorableFaces(World world, Vec3d center, int radius) {
        BlockPos min = BlockPos.ofFloored(center).add(-radius, -radius, -radius);
        BlockPos max = BlockPos.ofFloored(center).add(radius, radius, radius);
        int count = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!world.getBlockState(p).isOpaqueFullCube(world, p)) {
                        continue;
                    }
                    for (Direction face : Direction.values()) {
                        BlockPos n = p.offset(face);
                        if (!world.getBlockState(n).isOpaqueFullCube(world, n)) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    public static Vec3d pushOutOfSolid(World world, Vec3d pos) {
        if (!isInsideSolid(world, pos)) {
            return pos;
        }
        BlockPos b = BlockPos.ofFloored(pos);
        double lx = pos.x - b.getX();
        double ly = pos.y - b.getY();
        double lz = pos.z - b.getZ();

        double[] dists = {ly, 1 - ly, lz, 1 - lz, lx, 1 - lx};
        Direction[] dirs = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

        double best = Double.MAX_VALUE;
        Vec3d bestPos = pos;
        for (int i = 0; i < dirs.length; i++) {
            BlockPos n = b.offset(dirs[i]);
            if (world.getBlockState(n).isOpaqueFullCube(world, n)) {
                continue;
            }
            if (dists[i] < best) {
                best = dists[i];
                Vec3d normal = Vec3d.of(dirs[i].getVector());
                bestPos = Vec3d.ofCenter(b).add(normal.multiply(0.52));
            }
        }
        return bestPos;
    }

    public static boolean isInsideSolid(World world, Vec3d pos) {
        BlockPos b = BlockPos.ofFloored(pos);
        return world.getBlockState(b).isOpaqueFullCube(world, b);
    }

    private static double scoreAnchor(
            World world,
            Entity entity,
            Vec3d bodyPos,
            Vec3d socketPos,
            Vec3d anchor,
            Vec3d targetDir,
            Vec3d idealAnchor,
            boolean supportTentacle,
            DaddyVariantConfig config,
            float extraForwardBias
    ) {
        double distIdeal = anchor.distanceTo(idealAnchor);
        double score = -distIdeal * 1.15;

        if (targetDir.lengthSquared() > 1.0e-5) {
            double forward = anchor.subtract(bodyPos).normalize().dotProduct(targetDir);
            score += forward * (supportTentacle ? 1.2 : (2.8 + extraForwardBias));
        }

        if (supportTentacle) {
            double below = Math.max(0.0, bodyPos.y - anchor.y);
            score += below * 0.9;
        } else {
            double above = Math.max(0.0, anchor.y - bodyPos.y);
            score += above * 0.25;
        }

        double distSocket = socketPos.distanceTo(anchor);
        score -= Math.max(0.0, distSocket - config.tentacleLength()) * 2.1;

        RaycastContext rc = new RaycastContext(socketPos, anchor, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity);
        if (world.raycast(rc).getType() == HitResult.Type.MISS) {
            score += 3.4;
        } else {
            score -= 2.2;
        }

        return score;
    }
}
