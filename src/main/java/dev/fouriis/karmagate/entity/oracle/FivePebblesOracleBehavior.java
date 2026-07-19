package dev.fouriis.karmagate.entity.oracle;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class FivePebblesOracleBehavior extends OracleBehavior {
    private static final double ROOM_X_RADIUS = 7.5;
    private static final double ROOM_Y_RADIUS = 4.0;
    private static final double ROOM_Z_RADIUS = 7.5;

    private Vec3d lastPos;
    private Vec3d nextPos;
    private Vec3d lastHandle = Vec3d.ZERO;
    private Vec3d nextHandle = Vec3d.ZERO;
    private float pathProgression = 1f;
    private float investigateAngle;
    private Vec3d baseAimDir = new Vec3d(1.0, 0.0, 0.0);
    private Vec3d baseTargetDir = new Vec3d(1.0, 0.0, 0.0);
    private int baseRetargetCounter;
    private int idleCounter;
    private boolean meditate;

    public FivePebblesOracleBehavior(OracleEntity oracle) {
        super(oracle);
        this.lastPos = oracle.getOracleCenter();
        this.nextPos = this.lastPos;
        this.investigateAngle = oracle.getRandom().nextFloat() * 360f;
        this.baseAimDir = randomPlanarUnit();
        this.baseTargetDir = this.baseAimDir;
        this.baseRetargetCounter = 240 + oracle.getRandom().nextInt(360);
        this.idleCounter = 80 + oracle.getRandom().nextInt(180);
        this.meditate = oracle.getRandom().nextBoolean();
        this.oracleGetToPos = lastPos;
        this.baseGetToPos = baseTargetProbe();
    }

    @Override
    public void tick() {
        super.tick();

        idleCounter--;
        if (meditate) {
            Vec3d meditative = oracle.getHomePos().add(0.0, 1.4, 0.0);
            if (nextPos.squaredDistanceTo(meditative) > 0.25) {
                setNewDestination(meditative);
            }
            investigateAngle = MathHelper.lerp(0.025f, investigateAngle, 0f);
            lookPoint = oracle.getOracleCenter().add(0.0, -1.0, 0.0);
            if (idleCounter <= 0 && oracle.getRandom().nextFloat() < 0.35f) {
                meditate = false;
                idleCounter = 120 + oracle.getRandom().nextInt(360);
            }
        } else {
            PlayerEntity player = oracle.findNearestPlayer(18.0);
            if (player != null) {
                lookPoint = player.getEyePos();
            } else {
                lookPoint = oracle.getOracleCenter().add(getToDir.multiply(4.0));
            }

            if (idleCounter <= 0 || pathProgression >= 0.98f && oracle.getRandom().nextFloat() < 0.0125f) {
                setNewDestination(randomPointInBox(ROOM_X_RADIUS * 0.72, ROOM_Y_RADIUS * 0.45, ROOM_Z_RADIUS * 0.72));
                investigateAngle = oracle.getRandom().nextFloat() * 360f;
                idleCounter = 140 + oracle.getRandom().nextInt(360);
                if (oracle.getRandom().nextFloat() < 0.16f) {
                    meditate = true;
                }
            }
        }

        pathProgression = Math.min(1f, pathProgression + 0.012f);
        oracleGetToPos = clampAroundHome(bezier(lastPos, lastPos.add(lastHandle), nextPos.add(nextHandle), nextPos, ease(pathProgression)),
                ROOM_X_RADIUS, ROOM_Y_RADIUS, ROOM_Z_RADIUS);

        Vec3d targetDir = nextPos.subtract(lastPos);
        if (targetDir.lengthSquared() > 1.0E-5) {
            getToDir = targetDir.normalize();
        } else {
            double angle = Math.toRadians(investigateAngle);
            getToDir = new Vec3d(Math.cos(angle), 0.35, Math.sin(angle)).normalize();
        }

        updateBaseIdeal();
        baseGetToPos = baseTargetProbe();
    }

    private void setNewDestination(Vec3d destination) {
        destination = clampAroundHome(destination, ROOM_X_RADIUS, ROOM_Y_RADIUS, ROOM_Z_RADIUS);
        lastPos = oracleGetToPos;
        nextPos = destination;
        double distance = Math.max(0.25, lastPos.distanceTo(nextPos));
        lastHandle = randomUnit().multiply(distance * MathHelper.lerp(oracle.getRandom().nextFloat(), 0.3, 0.65));
        nextHandle = getToDir().negate().multiply(distance * MathHelper.lerp(oracle.getRandom().nextFloat(), 0.3, 0.65));
        pathProgression = 0f;
        consistentBasePosCounter = 0;
        if (oracle.getRandom().nextFloat() < 0.45f) {
            chooseNewBaseTarget();
        }
    }

    private void updateBaseIdeal() {
        baseRetargetCounter--;
        if (baseRetargetCounter <= 0 || pathProgression >= 0.96f && oracle.getRandom().nextFloat() < 0.003f) {
            chooseNewBaseTarget();
        }

        double blend = meditate ? 0.006 : 0.012;
        baseAimDir = baseAimDir.multiply(1.0 - blend).add(baseTargetDir.multiply(blend));
        if (baseAimDir.lengthSquared() < 1.0E-6) {
            baseAimDir = randomPlanarUnit();
        } else {
            baseAimDir = new Vec3d(baseAimDir.x, baseAimDir.y, 0.0).normalize();
        }
        if (baseAimDir.dotProduct(baseTargetDir) > 0.998 && oracle.getRandom().nextFloat() < 0.0015f) {
            chooseNewBaseTarget();
        }
    }

    private void chooseNewBaseTarget() {
        Vec3d chamber = oracle.getChamberCenter();
        Vec3d bodyOffset = oracleGetToPos.subtract(chamber);
        Vec3d planarBodyOffset = new Vec3d(bodyOffset.x, bodyOffset.y, 0.0);
        if (!meditate && planarBodyOffset.lengthSquared() > 1.0E-4 && oracle.getRandom().nextFloat() < 0.55f) {
            Vec3d wander = randomPlanarUnit().multiply(0.75);
            baseTargetDir = planarBodyOffset.normalize().add(wander);
            if (baseTargetDir.lengthSquared() < 1.0E-6) {
                baseTargetDir = randomPlanarUnit();
            } else {
                baseTargetDir = baseTargetDir.normalize();
            }
        } else {
            baseTargetDir = randomPlanarUnit();
        }
        baseRetargetCounter = (meditate ? 300 : 220) + oracle.getRandom().nextInt(meditate ? 400 : 360);
        consistentBasePosCounter = 0;
    }

    private Vec3d baseTargetProbe() {
        Vec3d chamber = oracle.getChamberCenter();
        double probeDistance = OracleEntity.CHAMBER_TRACK_HALF_WIDTH + 4.0;
        return chamber.add(baseAimDir.multiply(probeDistance));
    }

    private Vec3d randomUnit() {
        Vec3d v = new Vec3d(
                oracle.getRandom().nextDouble() * 2.0 - 1.0,
                oracle.getRandom().nextDouble() * 2.0 - 1.0,
                oracle.getRandom().nextDouble() * 2.0 - 1.0
        );
        if (v.lengthSquared() < 1.0E-6) {
            return new Vec3d(0.0, 1.0, 0.0);
        }
        return v.normalize();
    }

    private Vec3d randomPlanarUnit() {
        double angle = oracle.getRandom().nextDouble() * Math.PI * 2.0;
        return new Vec3d(Math.cos(angle), Math.sin(angle), 0.0);
    }

    private static Vec3d bezier(Vec3d a, Vec3d b, Vec3d c, Vec3d d, float t) {
        double inv = 1.0 - t;
        return a.multiply(inv * inv * inv)
                .add(b.multiply(3.0 * inv * inv * t))
                .add(c.multiply(3.0 * inv * t * t))
                .add(d.multiply(t * t * t));
    }

    private static float ease(float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
