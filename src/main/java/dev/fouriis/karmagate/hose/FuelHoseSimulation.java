package dev.fouriis.karmagate.hose;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public final class FuelHoseSimulation {
    private FuelHoseSimulation() {
    }

    public static List<Vec3d> simulate(BlockPos startPos, BlockPos endPos, int segmentCount, int simulationTicks, float gravity) {
        int safeSegments = Math.max(1, Math.min(segmentCount, 128));
        int safeTicks = Math.max(0, Math.min(simulationTicks, 4000));

        Vec3d start = Vec3d.ofCenter(startPos);
        Vec3d end = Vec3d.ofCenter(endPos);

        Vec3d[] points = new Vec3d[safeSegments + 1];
        Vec3d[] velocities = new Vec3d[safeSegments + 1];

        for (int i = 0; i <= safeSegments; i++) {
            double t = safeSegments == 0 ? 0.0 : (double) i / (double) safeSegments;
            points[i] = lerp(start, end, t);
            velocities[i] = Vec3d.ZERO;
        }

        double restLength = Math.max(0.05, start.distanceTo(end) / safeSegments);

        for (int tick = 0; tick < safeTicks; tick++) {
            points[0] = start;
            points[safeSegments] = end;

            for (int i = 1; i < safeSegments; i++) {
                Vec3d midpoint = points[i - 1].add(points[i + 1]).multiply(0.5);
                Vec3d pull = midpoint.subtract(points[i]).multiply(0.35);
                velocities[i] = velocities[i].multiply(0.88).add(pull).add(0.0, -gravity, 0.0);
            }

            for (int i = 1; i < safeSegments; i++) {
                points[i] = points[i].add(velocities[i]);
            }

            relaxConstraints(points, start, end, restLength, 4);
        }

        relaxConstraints(points, start, end, restLength, 12);

        List<Vec3d> out = new ArrayList<>(points.length);
        for (Vec3d point : points) {
            out.add(point);
        }
        return out;
    }

    private static void relaxConstraints(Vec3d[] points, Vec3d start, Vec3d end, double restLength, int iterations) {
        int last = points.length - 1;
        for (int pass = 0; pass < iterations; pass++) {
            points[0] = start;
            points[last] = end;
            for (int i = 0; i < last; i++) {
                Vec3d a = points[i];
                Vec3d b = points[i + 1];
                Vec3d delta = b.subtract(a);
                double dist = delta.length();
                if (dist < 1.0e-8) {
                    continue;
                }

                Vec3d dir = delta.multiply(1.0 / dist);
                double error = dist - restLength;

                if (i == 0) {
                    points[i + 1] = b.subtract(dir.multiply(error));
                } else if (i + 1 == last) {
                    points[i] = a.add(dir.multiply(error));
                } else {
                    Vec3d correction = dir.multiply(error * 0.5);
                    points[i] = a.add(correction);
                    points[i + 1] = b.subtract(correction);
                }
            }
        }
    }

    private static Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t
        );
    }
}