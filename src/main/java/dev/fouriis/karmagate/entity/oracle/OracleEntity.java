package dev.fouriis.karmagate.entity.oracle;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

public abstract class OracleEntity extends PathAwareEntity {
    public static final double CHAMBER_TRACK_WIDTH = 29.0;
    public static final double CHAMBER_TRACK_STRAIGHT_LENGTH = 25.0;
    public static final double CHAMBER_TRACK_CORNER_OFFSET = (CHAMBER_TRACK_WIDTH - CHAMBER_TRACK_STRAIGHT_LENGTH) * 0.5;
    public static final double CHAMBER_TRACK_HALF_WIDTH = CHAMBER_TRACK_WIDTH * 0.5;
    public static final double CHAMBER_TRACK_STRAIGHT_HALF_WIDTH = CHAMBER_TRACK_STRAIGHT_LENGTH * 0.5;
    private static final double CHAMBER_TRACK_ROOT_WIDTH = 3.0;
    private static final double VISIBILITY_ARM_MARGIN = 18.0;
    private static final double VISIBILITY_DEPTH_MARGIN = 8.0;

    private static final TrackedData<Float> TARGET_X = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> TARGET_Y = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> TARGET_Z = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> BASE_X = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> BASE_Y = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> BASE_Z = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> LOOK_X = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> LOOK_Y = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> LOOK_Z = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> DIR_X = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> DIR_Y = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> DIR_Z = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CHAMBER_X = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CHAMBER_Y = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CHAMBER_Z = DataTracker.registerData(OracleEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private final OracleId oracleId;
    private final OracleArm arm;
    private OracleBehavior behavior;
    private Vec3d homePos = Vec3d.ZERO;
    private BlockPos chamberBlockPos = BlockPos.ORIGIN;
    private boolean chamberBlockInitialized;
    private Vec3d chamberBasePos = Vec3d.ZERO;
    private boolean chamberBaseFrameInitialized;

    protected OracleEntity(EntityType<? extends PathAwareEntity> type, World world, OracleId oracleId) {
        super(type, world);
        this.oracleId = oracleId;
        this.arm = new OracleArm(this);
        this.setNoGravity(true);
        this.noClip = true;
        this.experiencePoints = 0;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.6)
                .add(EntityAttributes.GENERIC_ARMOR, 2.0);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(8, new LookAroundGoal(this));
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(TARGET_X, 0f);
        builder.add(TARGET_Y, 0f);
        builder.add(TARGET_Z, 0f);
        builder.add(BASE_X, 0f);
        builder.add(BASE_Y, 0f);
        builder.add(BASE_Z, 0f);
        builder.add(LOOK_X, 0f);
        builder.add(LOOK_Y, 0f);
        builder.add(LOOK_Z, 0f);
        builder.add(DIR_X, 0f);
        builder.add(DIR_Y, 1f);
        builder.add(DIR_Z, 0f);
        builder.add(CHAMBER_X, 0f);
        builder.add(CHAMBER_Y, 0f);
        builder.add(CHAMBER_Z, 0f);
    }

    @Override
    public void tick() {
        this.noClip = true;
        this.setNoGravity(true);
        this.fallDistance = 0f;

        if (!this.getWorld().isClient) {
            initializeChamberBlockIfNeeded();
            syncChamberCenter();
        }
        if (behavior == null && !this.getWorld().isClient) {
            behavior = createBehavior();
            syncBehaviorTargets();
        }

        super.tick();

        if (!this.getWorld().isClient) {
            behavior.tick();
            syncBehaviorTargets();
            applyOracleMovement();
        }

        arm.tick();
    }

    protected abstract OracleBehavior createBehavior();

    private void syncBehaviorTargets() {
        setTrackedVec(TARGET_X, TARGET_Y, TARGET_Z, behavior.oracleGetToPos());
        setTrackedVec(BASE_X, BASE_Y, BASE_Z, slideBaseOnChamberTrack(behavior.baseGetToPos()));
        setTrackedVec(LOOK_X, LOOK_Y, LOOK_Z, behavior.lookPoint());
        setTrackedVec(DIR_X, DIR_Y, DIR_Z, behavior.getToDir());
    }

    private Vec3d slideBaseOnChamberTrack(Vec3d desired) {
        Vec3d projected = projectToChamberTrack(desired);
        if (!chamberBaseFrameInitialized) {
            chamberBasePos = projected;
            chamberBaseFrameInitialized = true;
            return chamberBasePos;
        }
        Vec3d toProjected = projected.subtract(chamberBasePos);
        double distance = toProjected.length();
        if (distance > 0.001) {
            double maxStep = 0.35;
            chamberBasePos = chamberBasePos.add(toProjected.multiply(Math.min(maxStep, distance) / distance));
        }
        chamberBasePos = projectToChamberTrack(chamberBasePos);
        return chamberBasePos;
    }

    private void initializeChamberBlockIfNeeded() {
        if (chamberBlockInitialized) {
            return;
        }
        chamberBlockPos = getBlockPos();
        chamberBlockInitialized = true;
        updateHomeFromChamberBlock();
    }

    private void syncChamberCenter() {
        setTrackedVec(CHAMBER_X, CHAMBER_Y, CHAMBER_Z, getChamberCenter());
    }

    private void updateHomeFromChamberBlock() {
        homePos = Vec3d.ofCenter(chamberBlockPos);
    }

    private void applyOracleMovement() {
        Vec3d desiredBottom = behavior.oracleGetToPos().subtract(0.0, getHeight() * 0.58, 0.0);
        Vec3d toDesired = desiredBottom.subtract(getPos());
        Vec3d velocity = getVelocity().multiply(0.76).add(toDesired.multiply(isStationaryOracle() ? 0.16 : 0.075));
        double maxSpeed = isStationaryOracle() ? 0.08 : 0.22;
        if (velocity.length() > maxSpeed) {
            velocity = velocity.normalize().multiply(maxSpeed);
        }

        setVelocity(velocity);
        move(MovementType.SELF, velocity);
        velocityDirty = true;

        Vec3d look = getSyncedLookTarget().subtract(getOracleCenter());
        if (look.lengthSquared() > 1.0E-6) {
            float yaw = (float) (MathHelper.atan2(look.z, look.x) * 180.0 / Math.PI) - 90.0f;
            float pitch = (float) (-(MathHelper.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)) * 180.0 / Math.PI));
            setYaw(yaw);
            setPitch(pitch);
            bodyYaw = yaw;
            headYaw = yaw;
        }
    }

    private boolean isStationaryOracle() {
        return oracleId == OracleId.LOOKS_TO_THE_MOON;
    }

    private void setTrackedVec(TrackedData<Float> x, TrackedData<Float> y, TrackedData<Float> z, Vec3d value) {
        this.dataTracker.set(x, (float) value.x);
        this.dataTracker.set(y, (float) value.y);
        this.dataTracker.set(z, (float) value.z);
    }

    public OracleId getOracleId() {
        return oracleId;
    }

    public OracleArm getArm() {
        return arm;
    }

    public Vec3d getHomePos() {
        return homePos;
    }

    public Vec3d getChamberCenter() {
        Vec3d value = getTrackedVec(CHAMBER_X, CHAMBER_Y, CHAMBER_Z);
        if (!isUnsetTrackedVec(value)) {
            return value;
        }
        if (chamberBlockInitialized) {
            return Vec3d.ofCenter(chamberBlockPos);
        }
        return homePos.lengthSquared() > 1.0E-6 ? homePos : getOracleCenter();
    }

    public Vec3d projectToChamberTrack(Vec3d desired) {
        return closestRailProjection(desired).point();
    }

    public Vec3d chamberTrackInwardDir(Vec3d trackPoint) {
        Vec3d center = getChamberCenter();
        RailProjection projection = closestRailProjection(trackPoint);
        Vec3d tangent = rootWidthRailTangent(projection);
        Vec3d toCenter = new Vec3d(center.x - projection.point().x, center.y - projection.point().y, 0.0);
        Vec3d inward;
        if (tangent.lengthSquared() > 1.0E-6) {
            tangent = tangent.normalize();
            inward = toCenter.subtract(tangent.multiply(toCenter.dotProduct(tangent)));
        } else {
            inward = toCenter;
        }
        if (inward.lengthSquared() < 1.0E-6) {
            return new Vec3d(0.0, -1.0, 0.0);
        }
        return inward.normalize();
    }

    public Vec3d chamberTrackTangentDir(Vec3d trackPoint) {
        RailProjection projection = closestRailProjection(trackPoint);
        Vec3d tangent = rootWidthRailTangent(projection);
        if (tangent.lengthSquared() < 1.0E-6) {
            return new Vec3d(1.0, 0.0, 0.0);
        }
        return tangent;
    }

    private RailProjection closestRailProjection(Vec3d desired) {
        RailProjection best = null;
        List<RailSegment> segments = chamberRailSegments(getChamberCenter());
        double pathDistance = 0.0;
        for (int i = 0; i < segments.size(); i++) {
            RailSegment segment = segments.get(i);
            double segmentLength = segment.start().distanceTo(segment.end());
            double t = segmentT(desired, segment.start(), segment.end());
            Vec3d candidate = pointOnSegment(segment.start(), segment.end(), t);
            double distance = candidate.squaredDistanceTo(desired);
            if (best == null || distance < best.distanceSquared()) {
                best = new RailProjection(segment, candidate, distance, i, t, pathDistance + segmentLength * t);
            }
            pathDistance += segmentLength;
        }
        return best;
    }

    private Vec3d rootWidthRailTangent(RailProjection projection) {
        Vec3d center = getChamberCenter();
        double halfWidth = CHAMBER_TRACK_ROOT_WIDTH * 0.5;
        Vec3d before = railPointAtDistance(center, projection.pathDistance() - halfWidth);
        Vec3d after = railPointAtDistance(center, projection.pathDistance() + halfWidth);
        Vec3d tangent = after.subtract(before);
        tangent = new Vec3d(tangent.x, tangent.y, 0.0);
        if (tangent.lengthSquared() < 1.0E-6) {
            tangent = segmentTangent(projection.segment());
        }
        if (tangent.lengthSquared() < 1.0E-6) {
            return new Vec3d(1.0, 0.0, 0.0);
        }
        return tangent.normalize();
    }

    private static Vec3d segmentTangent(RailSegment segment) {
        Vec3d tangent = segment.end().subtract(segment.start());
        tangent = new Vec3d(tangent.x, tangent.y, 0.0);
        if (tangent.lengthSquared() < 1.0E-6) {
            return Vec3d.ZERO;
        }
        return tangent.normalize();
    }

    private static double segmentT(Vec3d point, Vec3d start, Vec3d end) {
        Vec3d delta = end.subtract(start);
        double lengthSquared = delta.lengthSquared();
        if (lengthSquared < 1.0E-6) {
            return 0.0;
        }
        double t = point.subtract(start).dotProduct(delta) / lengthSquared;
        return MathHelper.clamp(t, 0.0, 1.0);
    }

    private static Vec3d pointOnSegment(Vec3d start, Vec3d end, double t) {
        Vec3d delta = end.subtract(start);
        return start.add(delta.multiply(t));
    }

    private static Vec3d railPointAtDistance(Vec3d center, double distance) {
        List<RailSegment> segments = chamberRailSegments(center);
        double totalLength = railPathLength(segments);
        if (totalLength <= 1.0E-6) {
            return center;
        }
        double wrappedDistance = distance % totalLength;
        if (wrappedDistance < 0.0) {
            wrappedDistance += totalLength;
        }
        for (RailSegment segment : segments) {
            double segmentLength = segment.start().distanceTo(segment.end());
            if (wrappedDistance <= segmentLength) {
                return pointOnSegment(segment.start(), segment.end(), segmentLength <= 1.0E-6 ? 0.0 : wrappedDistance / segmentLength);
            }
            wrappedDistance -= segmentLength;
        }
        return segments.get(segments.size() - 1).end();
    }

    private static double railPathLength(List<RailSegment> segments) {
        double length = 0.0;
        for (RailSegment segment : segments) {
            length += segment.start().distanceTo(segment.end());
        }
        return length;
    }

    public static List<RailSegment> chamberRailSegments(Vec3d center) {
        Vec3d[] points = chamberRailPoints(center);
        List<RailSegment> segments = new java.util.ArrayList<>(points.length);
        for (int i = 0; i < points.length; i++) {
            segments.add(new RailSegment(points[i], points[(i + 1) % points.length]));
        }
        return segments;
    }

    public static List<Vec3d> chamberRailJunctions(Vec3d center) {
        return List.of(chamberRailPoints(center));
    }

    private static Vec3d[] chamberRailPoints(Vec3d center) {
        double half = CHAMBER_TRACK_HALF_WIDTH;
        double straight = CHAMBER_TRACK_STRAIGHT_HALF_WIDTH;
        return new Vec3d[] {
                center.add(-straight, half, 0.0),
                center.add(straight, half, 0.0),
                center.add(half, straight, 0.0),
                center.add(half, -straight, 0.0),
                center.add(straight, -half, 0.0),
                center.add(-straight, -half, 0.0),
                center.add(-half, -straight, 0.0),
                center.add(-half, straight, 0.0)
        };
    }

    public record RailSegment(Vec3d start, Vec3d end) {
    }

    private record RailProjection(RailSegment segment, Vec3d point, double distanceSquared, int segmentIndex, double segmentT, double pathDistance) {
    }

    public Vec3d getOracleCenter() {
        return getPos().add(0.0, getHeight() * 0.58, 0.0);
    }

    public Vec3d getOracleLowerBodyAnchor() {
        Vec3d getToDir = getSyncedGetToDir();
        boolean moon = getOracleId() == OracleId.LOOKS_TO_THE_MOON;
        Vec3d bodyAxis = moon
                ? new Vec3d(-0.2, -0.95, 0.0).normalize()
                : getToDir.multiply(-0.18).add(0.0, -1.0, 0.0).normalize();
        return getOracleCenter().add(0.0, 0.42, 0.0).add(bodyAxis.multiply(moon ? 0.74 : 0.88));
    }

    public Vec3d getSyncedOracleTarget() {
        Vec3d value = getTrackedVec(TARGET_X, TARGET_Y, TARGET_Z);
        return isUnsetTrackedVec(value) ? getOracleCenter() : value;
    }

    public Vec3d getSyncedBaseTarget() {
        Vec3d value = getTrackedVec(BASE_X, BASE_Y, BASE_Z);
        return isUnsetTrackedVec(value) ? getOracleCenter().add(0.0, 3.5, 0.0) : value;
    }

    public Vec3d getSyncedLookTarget() {
        Vec3d value = getTrackedVec(LOOK_X, LOOK_Y, LOOK_Z);
        return isUnsetTrackedVec(value) ? getOracleCenter().add(0.0, 0.0, 4.0) : value;
    }

    public Vec3d getSyncedGetToDir() {
        Vec3d dir = getTrackedVec(DIR_X, DIR_Y, DIR_Z);
        if (dir.lengthSquared() < 1.0E-6) {
            return new Vec3d(0.0, 1.0, 0.0);
        }
        return dir.normalize();
    }

    private Vec3d getTrackedVec(TrackedData<Float> x, TrackedData<Float> y, TrackedData<Float> z) {
        return new Vec3d(dataTracker.get(x), dataTracker.get(y), dataTracker.get(z));
    }

    private boolean isUnsetTrackedVec(Vec3d value) {
        return age < 5 && value.lengthSquared() < 1.0E-6;
    }

    public PlayerEntity findNearestPlayer(double radius) {
        PlayerEntity nearest = null;
        double best = radius * radius;
        for (PlayerEntity player : getWorld().getPlayers()) {
            if (player.isSpectator() || player.isCreative() || !player.isAlive()) {
                continue;
            }
            double distance = player.squaredDistanceTo(this);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isInsideWall() {
        return false;
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, net.minecraft.entity.damage.DamageSource damageSource) {
        return false;
    }

    @Override
    public void travel(Vec3d movementInput) {
    }

    @Override
    public Box getVisibilityBoundingBox() {
        Vec3d center = getChamberCenter();
        double planarRadius = CHAMBER_TRACK_HALF_WIDTH + VISIBILITY_ARM_MARGIN;
        Box oracleVisualBox = new Box(
                center.x - planarRadius,
                center.y - planarRadius,
                center.z - VISIBILITY_DEPTH_MARGIN,
                center.x + planarRadius,
                center.y + planarRadius,
                center.z + VISIBILITY_DEPTH_MARGIN
        );
        Box hitbox = getBoundingBox();
        return new Box(
                Math.min(oracleVisualBox.minX, hitbox.minX),
                Math.min(oracleVisualBox.minY, hitbox.minY),
                Math.min(oracleVisualBox.minZ, hitbox.minZ),
                Math.max(oracleVisualBox.maxX, hitbox.maxX),
                Math.max(oracleVisualBox.maxY, hitbox.maxY),
                Math.max(oracleVisualBox.maxZ, hitbox.maxZ)
        );
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putDouble("OracleHomeX", homePos.x);
        nbt.putDouble("OracleHomeY", homePos.y);
        nbt.putDouble("OracleHomeZ", homePos.z);
        nbt.putInt("OracleChamberBlockX", chamberBlockPos.getX());
        nbt.putInt("OracleChamberBlockY", chamberBlockPos.getY());
        nbt.putInt("OracleChamberBlockZ", chamberBlockPos.getZ());
        nbt.putBoolean("OracleChamberBlockInitialized", chamberBlockInitialized);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("OracleChamberBlockX")) {
            chamberBlockPos = new BlockPos(
                    nbt.getInt("OracleChamberBlockX"),
                    nbt.getInt("OracleChamberBlockY"),
                    nbt.getInt("OracleChamberBlockZ")
            );
            chamberBlockInitialized = !nbt.contains("OracleChamberBlockInitialized") || nbt.getBoolean("OracleChamberBlockInitialized");
            updateHomeFromChamberBlock();
        } else if (nbt.contains("OracleHomeX")) {
            homePos = new Vec3d(nbt.getDouble("OracleHomeX"), nbt.getDouble("OracleHomeY"), nbt.getDouble("OracleHomeZ"));
            chamberBlockPos = BlockPos.ofFloored(homePos);
            chamberBlockInitialized = true;
            updateHomeFromChamberBlock();
        }
    }
}
