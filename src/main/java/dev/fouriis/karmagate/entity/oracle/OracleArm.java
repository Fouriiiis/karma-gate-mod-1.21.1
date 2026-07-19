package dev.fouriis.karmagate.entity.oracle;

import net.minecraft.util.math.Vec3d;

public class OracleArm {
    private static final int RAIN_WORLD_STEPS_PER_MINECRAFT_TICK = 2;
    private static final double FIRST_SEGMENT_LENGTH_BLOCKS = 14.0;
    private static final double BLOCKS_PER_ORACLE_ARM_PIXEL = FIRST_SEGMENT_LENGTH_BLOCKS / 300.0;
    private static final double[] SEGMENT_LENGTHS = { armPx(300.0), armPx(150.0), armPx(90.0), armPx(30.0) };
    private static final double JOINT_COLLISION_RADIUS = 0.28;
    private static final double SEGMENT_COLLISION_SAMPLE_SPACING = 0.35;

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
        Vec3d base = flattenToArmPlane(oracle.getSyncedBaseTarget());
        Vec3d body = flattenToArmPlane(oracle.getOracleCenter());

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
            joint.pos = flattenToArmPlane(joint.pos);
            joint.vel = flattenVectorToArmPlane(joint.vel);
            joint.lastPos = joint.pos;
        }

        for (int step = 0; step < RAIN_WORLD_STEPS_PER_MINECRAFT_TICK; step++) {
            simulateStep(base, body, step);
        }
    }

    private void simulateStep(Vec3d base, Vec3d body, int step) {
        Vec3d[] stepStart = new Vec3d[joints.length];
        for (int i = 0; i < joints.length; i++) {
            stepStart[i] = joints[i].pos;
        }

        joints[0].pos = base;
        joints[0].vel = Vec3d.ZERO;

        for (int i = 1; i < joints.length; i++) {
            Joint joint = joints[i];
            Vec3d sway = orbitalBias(i, step).multiply(0.010 + i * 0.003);
            if (i == 1) {
                Vec3d preferred = preferredRootBendTarget(base);
                sway = sway.add(preferred.subtract(joint.pos).multiply(0.012));
            } else if (i == joints.length - 1) {
                Vec3d preferred = preferredFinalJointTarget(joint.pos);
                sway = sway.add(preferred.subtract(joint.pos).multiply(0.035));
            }
            joint.vel = joint.vel.multiply(0.82).add(sway);
            joint.pos = flattenToArmPlane(joint.pos.add(joint.vel));
            collideJoint(joint);
        }

        for (int pass = 0; pass < 16; pass++) {
            joints[0].pos = base;
            for (int i = 1; i < joints.length - 1; i++) {
                satisfyCSharpLink(i);
            }
            satisfyCSharpLink(0);
            satisfyBody(joints[joints.length - 1], body, SEGMENT_LENGTHS[SEGMENT_LENGTHS.length - 1], 1.0);
            flattenFreeJoints();
            collideFreeJoints();
            collideSegments(body);
            flattenFreeJoints();
        }

        for (int i = 1; i < joints.length; i++) {
            joints[i].vel = joints[i].pos.subtract(stepStart[i]);
        }
    }

    private void collideFreeJoints() {
        for (int i = 1; i < joints.length; i++) {
            collideJoint(joints[i]);
        }
    }

    private void collideJoint(Joint joint) {
        Vec3d before = joint.pos;
        Vec3d collided = OraclePhysicsUtil.collidePoint(oracle.getWorld(), joint.pos, JOINT_COLLISION_RADIUS, oracle.getOracleCollisionCache());
        joint.pos = flattenToArmPlane(collided);
        Vec3d correction = flattenVectorToArmPlane(joint.pos.subtract(before));
        if (correction.lengthSquared() > 1.0E-8) {
            joint.vel = flattenVectorToArmPlane(joint.vel.add(correction).multiply(0.35));
        }
    }

    private void collideSegments(Vec3d body) {
        for (int i = 0; i < joints.length - 1; i++) {
            double aWeight = i == 0 ? 0.0 : 0.5;
            double bWeight = i == 0 ? 1.0 : 0.5;
            collideJointSegment(joints[i], joints[i + 1], aWeight, bWeight);
        }
        Vec3d correction = OraclePhysicsUtil.segmentCollisionCorrection(oracle.getWorld(),
                joints[joints.length - 1].pos, body, JOINT_COLLISION_RADIUS, SEGMENT_COLLISION_SAMPLE_SPACING,
                oracle.getOracleCollisionCache());
        correction = flattenVectorToArmPlane(correction);
        if (correction.lengthSquared() > 1.0E-8) {
            joints[joints.length - 1].pos = joints[joints.length - 1].pos.add(correction);
            joints[joints.length - 1].vel = flattenVectorToArmPlane(joints[joints.length - 1].vel.add(correction).multiply(0.45));
        }
    }

    private void collideJointSegment(Joint a, Joint b, double aWeight, double bWeight) {
        Vec3d correction = OraclePhysicsUtil.segmentCollisionCorrection(oracle.getWorld(), a.pos, b.pos,
                JOINT_COLLISION_RADIUS, SEGMENT_COLLISION_SAMPLE_SPACING, oracle.getOracleCollisionCache());
        correction = flattenVectorToArmPlane(correction);
        if (correction.lengthSquared() < 1.0E-8) {
            return;
        }
        Vec3d aCorrection = correction.multiply(aWeight);
        Vec3d bCorrection = correction.multiply(bWeight);
        a.pos = a.pos.add(aCorrection);
        b.pos = b.pos.add(bCorrection);
        if (aWeight > 0.0) {
            a.vel = flattenVectorToArmPlane(a.vel.add(aCorrection).multiply(0.45));
        }
        if (bWeight > 0.0) {
            b.vel = flattenVectorToArmPlane(b.vel.add(bCorrection).multiply(0.45));
        }
    }

    private Vec3d orbitalBias(int index, int step) {
        float age = oracle.age * RAIN_WORLD_STEPS_PER_MINECRAFT_TICK + step + index * 37.0f;
        Vec3d dir = flattenVectorToArmPlane(oracle.getSyncedGetToDir());
        Vec3d side = new Vec3d(-dir.y, dir.x, 0.0);
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
        Vec3d rootDir = flattenVectorToArmPlane(oracle.chamberTrackInwardDir(base));
        if (rootDir.lengthSquared() < 1.0E-6) {
            rootDir = flattenVectorToArmPlane(oracle.getOracleCenter().subtract(base));
        }
        if (rootDir.lengthSquared() < 1.0E-6) {
            rootDir = new Vec3d(0.0, -1.0, 0.0);
        }
        Vec3d tangent = flattenVectorToArmPlane(oracle.chamberTrackTangentDir(base));
        if (tangent.lengthSquared() < 1.0E-6) {
            tangent = new Vec3d(-rootDir.y, rootDir.x, 0.0);
        }
        tangent = tangent.normalize();
        return flattenToArmPlane(base.add(rootDir.normalize().multiply(SEGMENT_LENGTHS[0] * 0.72))
                .add(tangent.multiply(SEGMENT_LENGTHS[0] * 0.24)));
    }

    private Vec3d preferredFinalJointTarget(Vec3d currentPos) {
        double length = SEGMENT_LENGTHS[SEGMENT_LENGTHS.length - 1];
        Vec3d lowerBody = flattenToArmPlane(oracle.getOracleLowerBodyAnchor());
        Vec3d getToDir = flattenVectorToArmPlane(oracle.getSyncedGetToDir());
        if (getToDir.lengthSquared() < 1.0E-6) {
            getToDir = new Vec3d(0.0, 1.0, 0.0);
        }
        Vec3d target;
        if (oracle.getOracleId() == OracleId.FIVE_PEBBLES) {
            target = lowerBody.subtract(getToDir.multiply(length * 0.5));
        } else {
            Vec3d side = new Vec3d(-getToDir.y, getToDir.x, 0.0);
            if (side.lengthSquared() < 1.0E-6) {
                side = new Vec3d(1.0, 0.0, 0.0);
            }
            target = lowerBody.subtract(safeNormalize(side, new Vec3d(1.0, 0.0, 0.0)).multiply(length * 0.5));
        }
        currentPos = flattenToArmPlane(currentPos);
        target = target.add(safeNormalize(flattenVectorToArmPlane(currentPos.subtract(lowerBody)), getToDir.negate()).multiply(length * 0.5));
        return flattenToArmPlane(currentPos.add(clampMagnitude(target.subtract(currentPos), 2.5).multiply(0.48)));
    }

    private void flattenFreeJoints() {
        for (int i = 1; i < joints.length; i++) {
            joints[i].pos = flattenToArmPlane(joints[i].pos);
            joints[i].vel = flattenVectorToArmPlane(joints[i].vel);
        }
    }

    private Vec3d flattenToArmPlane(Vec3d value) {
        return new Vec3d(value.x, value.y, oracle.getChamberCenter().z);
    }

    private static Vec3d flattenVectorToArmPlane(Vec3d value) {
        return new Vec3d(value.x, value.y, 0.0);
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
