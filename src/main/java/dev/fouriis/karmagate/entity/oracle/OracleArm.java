package dev.fouriis.karmagate.entity.oracle;

import net.minecraft.util.math.Vec3d;

public class OracleArm {
    private static final double FIRST_SEGMENT_LENGTH_BLOCKS = 14.0;
    private static final double BLOCKS_PER_ORACLE_ARM_PIXEL = FIRST_SEGMENT_LENGTH_BLOCKS / 300.0;
    private static final double[] SEGMENT_LENGTHS = { armPx(300.0), armPx(150.0), armPx(90.0), armPx(30.0) };

    private final OracleEntity oracle;
    private final Joint[] joints = new Joint[4];
    private boolean initialized;

    public OracleArm(OracleEntity oracle) {
        this.oracle = oracle;
        for (int i = 0; i < joints.length; i++) {
            joints[i] = new Joint(i, SEGMENT_LENGTHS[i]);
        }
    }

    public void tick() {
        Vec3d base = oracle.getSyncedBaseTarget();
        Vec3d body = oracle.getOracleCenter();

        if (!initialized) {
            for (int i = 0; i < joints.length; i++) {
                double t = i / (double) joints.length;
                joints[i].pos = base.lerp(body, t);
                joints[i].lastPos = joints[i].pos;
                joints[i].vel = Vec3d.ZERO;
            }
            initialized = true;
        }

        for (Joint joint : joints) {
            joint.lastPos = joint.pos;
        }

        joints[0].pos = base;
        joints[0].vel = Vec3d.ZERO;

        for (int i = 1; i < joints.length; i++) {
            Joint joint = joints[i];
            Vec3d sway = orbitalBias(i).multiply(0.010 + i * 0.003);
            if (i == 1) {
                Vec3d preferred = preferredRootBendTarget(base);
                sway = sway.add(preferred.subtract(joint.pos).multiply(0.012));
            } else if (i == joints.length - 1) {
                Vec3d preferred = preferredFinalJointTarget(joint.pos);
                sway = sway.add(preferred.subtract(joint.pos).multiply(0.035));
            }
            joint.vel = joint.vel.multiply(0.82).add(sway);
            joint.pos = joint.pos.add(joint.vel);
        }

        for (int pass = 0; pass < 16; pass++) {
            joints[0].pos = base;
            for (int i = 1; i < joints.length - 1; i++) {
                satisfyCSharpLink(i);
            }
            satisfyCSharpLink(0);
            satisfyBody(joints[joints.length - 1], body, SEGMENT_LENGTHS[SEGMENT_LENGTHS.length - 1], 1.0);
        }

        for (int i = 1; i < joints.length; i++) {
            joints[i].vel = joints[i].pos.subtract(joints[i].lastPos);
        }
    }

    private Vec3d orbitalBias(int index) {
        float age = oracle.age + index * 37.0f;
        Vec3d dir = oracle.getSyncedGetToDir();
        Vec3d side = dir.crossProduct(new Vec3d(0.0, 1.0, 0.0));
        if (side.lengthSquared() < 1.0E-6) {
            side = new Vec3d(1.0, 0.0, 0.0);
        } else {
            side = side.normalize();
        }
        return side.multiply(Math.sin(age * 0.055) * (index % 2 == 0 ? 1.0 : -1.0))
                .add(new Vec3d(0.0, Math.cos(age * 0.043), 0.0));
    }

    private void satisfyCSharpLink(int index) {
        Joint a = joints[index];
        Joint b = joints[index + 1];
        double maxDistance = SEGMENT_LENGTHS[index];
        double aWeight = 0.5;
        if (index == 0) {
            aWeight = 0.0;
        } else if (index == joints.length - 2) {
            aWeight = 1.0;
        }
        double bWeight = 1.0 - aWeight;

        double minRatio = 0.5;
        if (index > 0) {
            Vec3d previousDir = safeNormalize(a.pos.subtract(joints[index - 1].pos), new Vec3d(0.0, 1.0, 0.0));
            Vec3d nextDir = safeNormalize(b.pos.subtract(a.pos), previousDir);
            double dot = Math.max(-1.0, Math.min(1.0, previousDir.dotProduct(nextDir)));
            minRatio = lerp((dot + 1.0) * 0.5, 1.0, 0.2);
        }
        satisfyRange(a, b, maxDistance, minRatio, aWeight, bWeight);
    }

    private static void satisfyRange(Joint a, Joint b, double maxDistance, double minRatio, double aWeight, double bWeight) {
        Vec3d delta = b.pos.subtract(a.pos);
        double distance = delta.length();
        if (distance < 1.0E-6) {
            return;
        }
        double minDistance = maxDistance * minRatio;
        double target = distance;
        if (distance > maxDistance) {
            target = maxDistance;
        } else if (distance < minDistance) {
            target = minDistance;
        }
        if (Math.abs(target - distance) < 1.0E-6) {
            return;
        }
        Vec3d correction = delta.normalize().multiply(distance - target);
        a.pos = a.pos.add(correction.multiply(aWeight));
        b.pos = b.pos.subtract(correction.multiply(bWeight));
    }

    private Vec3d preferredRootBendTarget(Vec3d base) {
        Vec3d rootDir = oracle.chamberTrackInwardDir(base);
        if (rootDir.lengthSquared() < 1.0E-6) {
            rootDir = oracle.getOracleCenter().subtract(base);
        }
        if (rootDir.lengthSquared() < 1.0E-6) {
            rootDir = new Vec3d(0.0, -1.0, 0.0);
        }
        Vec3d tangent = oracle.chamberTrackTangentDir(base);
        if (tangent.lengthSquared() < 1.0E-6) {
            tangent = rootDir.crossProduct(new Vec3d(0.0, 0.0, 1.0));
        }
        tangent = tangent.normalize();
        return base.add(rootDir.normalize().multiply(SEGMENT_LENGTHS[0] * 0.72))
                .add(tangent.multiply(SEGMENT_LENGTHS[0] * 0.24));
    }

    private Vec3d preferredFinalJointTarget(Vec3d currentPos) {
        double length = SEGMENT_LENGTHS[SEGMENT_LENGTHS.length - 1];
        Vec3d lowerBody = oracle.getOracleLowerBodyAnchor();
        Vec3d getToDir = oracle.getSyncedGetToDir();
        Vec3d target;
        if (oracle.getOracleId() == OracleId.FIVE_PEBBLES) {
            target = lowerBody.subtract(getToDir.multiply(length * 0.5));
        } else {
            Vec3d side = getToDir.crossProduct(new Vec3d(0.0, 0.0, 1.0));
            if (side.lengthSquared() < 1.0E-6) {
                side = getToDir.crossProduct(new Vec3d(1.0, 0.0, 0.0));
            }
            target = lowerBody.subtract(safeNormalize(side, new Vec3d(1.0, 0.0, 0.0)).multiply(length * 0.5));
        }
        target = target.add(safeNormalize(currentPos.subtract(lowerBody), getToDir.negate()).multiply(length * 0.5));
        return currentPos.add(clampMagnitude(target.subtract(currentPos), 2.5).multiply(0.48));
    }

    private static double armPx(double pixels) {
        return pixels * BLOCKS_PER_ORACLE_ARM_PIXEL;
    }

    private static Vec3d safeNormalize(Vec3d value, Vec3d fallback) {
        if (value.lengthSquared() < 1.0E-6) {
            return fallback.lengthSquared() < 1.0E-6 ? new Vec3d(0.0, 1.0, 0.0) : fallback.normalize();
        }
        return value.normalize();
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static Vec3d clampMagnitude(Vec3d value, double maxLength) {
        double length = value.length();
        if (length <= maxLength || length < 1.0E-6) {
            return value;
        }
        return value.multiply(maxLength / length);
    }

    private static void satisfyBody(Joint joint, Vec3d body, double desired, double jointWeight) {
        Vec3d delta = body.subtract(joint.pos);
        double distance = delta.length();
        if (distance < 1.0E-6) {
            return;
        }
        Vec3d correction = delta.normalize().multiply(distance - desired);
        joint.pos = joint.pos.add(correction.multiply(jointWeight));
    }

    public Joint[] joints() {
        return joints;
    }

    public record JointView(Vec3d lastPos, Vec3d pos, double length, int index) {
    }

    public static class Joint {
        private final int index;
        private final double length;
        private Vec3d pos = Vec3d.ZERO;
        private Vec3d lastPos = Vec3d.ZERO;
        private Vec3d vel = Vec3d.ZERO;

        private Joint(int index, double length) {
            this.index = index;
            this.length = length;
        }

        public JointView view() {
            return new JointView(lastPos, pos, length, index);
        }
    }
}
