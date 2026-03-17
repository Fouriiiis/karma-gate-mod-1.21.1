package dev.fouriis.karmagate.entity.daddy;

import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public final class TentaclePathSolver {
    private TentaclePathSolver() {
    }

    public static List<Vec3d> solvePath(
            World world,
            Entity entity,
            Vec3d start,
            Vec3d end,
            int segmentCount,
            float maxLength
    ) {
        List<Vec3d> polyline = new ArrayList<>();
        polyline.add(start);
        polyline.add(end);

        int bendBudget = 5;
        for (int i = 0; i < bendBudget; i++) {
            int blockedIndex = findBlockedSegment(world, entity, polyline);
            if (blockedIndex < 0) {
                break;
            }

            Vec3d a = polyline.get(blockedIndex);
            Vec3d b = polyline.get(blockedIndex + 1);
            BlockHitResult hit = world.raycast(new RaycastContext(a, b, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity));
            if (hit.getType() == HitResult.Type.MISS) {
                break;
            }

            Vec3d hitPos = hit.getPos();
            Vec3d normal = Vec3d.of(hit.getSide().getVector());
            Vec3d tangent = b.subtract(a);
            if (tangent.lengthSquared() > 1.0e-5) {
                tangent = tangent.normalize();
            } else {
                tangent = new Vec3d(0, 1, 0);
            }

            Vec3d side = tangent.crossProduct(normal);
            if (side.lengthSquared() < 1.0e-5) {
                side = new Vec3d(normal.z, normal.x, normal.y).normalize();
            } else {
                side = side.normalize();
            }

            Vec3d bend = hitPos
                    .add(normal.multiply(0.62))
                    .add(side.multiply(((blockedIndex & 1) == 0 ? 1 : -1) * 0.35));
            bend = TentacleAnchorFinder.pushOutOfSolid(world, bend);
            polyline.add(blockedIndex + 1, bend);
        }

        List<Vec3d> sampled = resampleByDistance(polyline, segmentCount + 1);
        if (sampled.size() < 2) {
            sampled.clear();
            sampled.add(start);
            sampled.add(end);
        }

        relax(world, sampled, start, end, 4);
        enforceLength(sampled, maxLength, end);

        for (int i = 1; i < sampled.size() - 1; i++) {
            sampled.set(i, TentacleAnchorFinder.pushOutOfSolid(world, sampled.get(i)));
        }
        sampled.set(0, start);
        sampled.set(sampled.size() - 1, end);

        return sampled;
    }

    private static int findBlockedSegment(World world, Entity entity, List<Vec3d> points) {
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3d a = points.get(i);
            Vec3d b = points.get(i + 1);
            RaycastContext rc = new RaycastContext(a, b, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity);
            if (world.raycast(rc).getType() != HitResult.Type.MISS) {
                return i;
            }
        }
        return -1;
    }

    private static List<Vec3d> resampleByDistance(List<Vec3d> src, int sampleCount) {
        List<Vec3d> out = new ArrayList<>(sampleCount);
        if (src.size() < 2 || sampleCount <= 1) {
            out.addAll(src);
            return out;
        }

        double[] accum = new double[src.size()];
        double total = 0;
        accum[0] = 0;
        for (int i = 1; i < src.size(); i++) {
            total += src.get(i).distanceTo(src.get(i - 1));
            accum[i] = total;
        }

        if (total < 1.0e-5) {
            for (int i = 0; i < sampleCount; i++) {
                out.add(src.get(0));
            }
            return out;
        }

        for (int i = 0; i < sampleCount; i++) {
            double d = total * i / (sampleCount - 1);
            int idx = 1;
            while (idx < accum.length && accum[idx] < d) {
                idx++;
            }
            if (idx >= accum.length) {
                out.add(src.get(src.size() - 1));
                continue;
            }
            double prev = accum[idx - 1];
            double segLen = Math.max(1.0e-5, accum[idx] - prev);
            double t = (d - prev) / segLen;
            out.add(src.get(idx - 1).lerp(src.get(idx), t));
        }

        return out;
    }

    private static void relax(World world, List<Vec3d> points, Vec3d start, Vec3d end, int passes) {
        for (int p = 0; p < passes; p++) {
            for (int i = 1; i < points.size() - 1; i++) {
                Vec3d prev = points.get(i - 1);
                Vec3d next = points.get(i + 1);
                Vec3d smooth = prev.add(next).multiply(0.5);
                Vec3d pos = points.get(i).lerp(smooth, 0.6);
                points.set(i, TentacleAnchorFinder.pushOutOfSolid(world, pos));
            }
            points.set(0, start);
            points.set(points.size() - 1, end);
        }
    }

    private static void enforceLength(List<Vec3d> points, float maxLength, Vec3d end) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += points.get(i).distanceTo(points.get(i - 1));
        }

        if (total <= maxLength || total < 1.0e-5) {
            return;
        }

        double scale = maxLength / total;
        Vec3d root = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            Vec3d p = points.get(i);
            points.set(i, root.add(p.subtract(root).multiply(scale)));
        }
        points.set(points.size() - 1, end);
    }
}
