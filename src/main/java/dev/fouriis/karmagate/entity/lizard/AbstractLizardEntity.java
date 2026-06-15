package dev.fouriis.karmagate.entity.lizard;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractLizardEntity extends HostileEntity {
    private static final TrackedData<NbtCompound> POSE_SYNC = DataTracker.registerData(
            AbstractLizardEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND
    );

    protected final LizardBreedProfile breed;
    protected Vec3d headPos = Vec3d.ZERO;
    protected Vec3d[] bodyNodes;
    protected Vec3d[] bodyVelocities;
    protected Vec3d[] tailNodes;
    protected LizardPoseSnapshot.LegPose[] legPoses;
    protected float walkCycle;
    protected boolean skeletonInitialized;

    private final Map<String, LizardPartEntity> partMap = new HashMap<>();
    private final boolean[] legPlanted = new boolean[4];
    private final boolean[] legReaching = new boolean[4];
    private final int[] legGripCounter = new int[4];
    private final float[] legFlip = new float[4];
    private final boolean[] legExtraLongStep = new boolean[4];
    private final Vec3d[] legHuntPos = new Vec3d[4];

    private Vec3d locomotionRoot = Vec3d.ZERO;
    private Vec3d travelHeading = Vec3d.ZERO;
    private Vec3d rootVelocity = Vec3d.ZERO;
    private LizardPoseSnapshot clientPose = LizardPoseSnapshot.EMPTY;
    private LizardPoseSnapshot previousClientPose = LizardPoseSnapshot.EMPTY;
    private float frontBob;
    private float hindBob;

    protected AbstractLizardEntity(EntityType<? extends HostileEntity> type, World world, LizardBreedProfile breed) {
        super(type, world);
        this.breed = breed;
        this.noClip = true;
        this.setNoGravity(true);
        this.bodyNodes = new Vec3d[breed.bodySegments()];
        this.bodyVelocities = new Vec3d[breed.bodySegments()];
        this.tailNodes = new Vec3d[breed.tailSegments()];
        this.legPoses = new LizardPoseSnapshot.LegPose[4];
        Arrays.fill(this.bodyNodes, Vec3d.ZERO);
        Arrays.fill(this.bodyVelocities, Vec3d.ZERO);
        Arrays.fill(this.tailNodes, Vec3d.ZERO);
        Arrays.fill(this.legPoses, LizardPoseSnapshot.ZERO_LEG);
        Arrays.fill(this.legPlanted, true);
        Arrays.fill(this.legReaching, true);
        Arrays.fill(this.legFlip, 1.0f);
        Arrays.fill(this.legHuntPos, Vec3d.ZERO);
        this.experiencePoints = 6;
    }

    public static DefaultAttributeContainer.Builder createBaseAttributes(LizardBreedProfile breed) {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, breed.maxHealth())
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 18.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.6);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(POSE_SYNC, new NbtCompound());
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(8, new LookAroundGoal(this));
    }

    public LizardBreedProfile getBreed() {
        return breed;
    }

    public LizardPoseSnapshot getRenderPose(float tickDelta) {
        if (!this.getWorld().isClient) {
            return buildSnapshot();
        }
        return LizardPoseSnapshot.interpolate(previousClientPose, clientPose, tickDelta);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient) {
            previousClientPose = clientPose;
            clientPose = LizardPoseSnapshot.fromNbt(this.dataTracker.get(POSE_SYNC));
            return;
        }

        this.noClip = true;
        this.setNoGravity(true);
        serverTick((ServerWorld) this.getWorld());
    }

    @Override
    public void travel(Vec3d movementInput) {
    }

    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canMoveVoluntarily() {
        return false;
    }

    @Override
    public boolean isInsideWall() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 256.0 * 256.0;
    }

    @Override
    public Box getVisibilityBoundingBox() {
        return this.getBoundingBox().expand(8.0, 4.0, 8.0);
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public void onRemoved() {
        super.onRemoved();
        if (!this.getWorld().isClient) {
            for (LizardPartEntity part : partMap.values()) {
                if (part != null && part.isAlive()) {
                    part.discard();
                }
            }
            partMap.clear();
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putDouble("HeadingX", travelHeading.x);
        nbt.putDouble("HeadingY", travelHeading.y);
        nbt.putDouble("HeadingZ", travelHeading.z);
        nbt.putDouble("LocomotionRootX", locomotionRoot.x);
        nbt.putDouble("LocomotionRootY", locomotionRoot.y);
        nbt.putDouble("LocomotionRootZ", locomotionRoot.z);
        nbt.putDouble("RootVelX", rootVelocity.x);
        nbt.putDouble("RootVelY", rootVelocity.y);
        nbt.putDouble("RootVelZ", rootVelocity.z);
        nbt.putFloat("WalkCycle", walkCycle);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        travelHeading = new Vec3d(
                nbt.getDouble("HeadingX"),
                nbt.getDouble("HeadingY"),
                nbt.getDouble("HeadingZ")
        );
        locomotionRoot = new Vec3d(
                nbt.contains("LocomotionRootX") ? nbt.getDouble("LocomotionRootX") : this.getX(),
                nbt.contains("LocomotionRootY") ? nbt.getDouble("LocomotionRootY") : this.getY(),
                nbt.contains("LocomotionRootZ") ? nbt.getDouble("LocomotionRootZ") : this.getZ()
        );
        rootVelocity = new Vec3d(
                nbt.getDouble("RootVelX"),
                nbt.getDouble("RootVelY"),
                nbt.getDouble("RootVelZ")
        );
        walkCycle = nbt.getFloat("WalkCycle");
        skeletonInitialized = false;
    }

    private void serverTick(ServerWorld world) {
        if (!skeletonInitialized) {
            initializeSkeleton();
        }

        recoverOrSpawnParts(world);
        updateForwardLocomotion(world);
        updateSkeleton(world);
        updateHitboxParts();
        this.dataTracker.set(POSE_SYNC, buildSnapshot().toNbt());
    }

    private void initializeSkeleton() {
        Vec3d forward = initialForward();
        travelHeading = forward;

        double groundY = sampleGroundY(this.getPos().add(0.0, 1.5, 0.0), this.getY(), breed.stanceHeight() + 4.0);
        locomotionRoot = new Vec3d(this.getX(), groundY + 0.02, this.getZ());

        bodyNodes = computeBodyFrame(locomotionRoot, forward, 0.0, 0.0);
        headPos = bodyNodes[0].add(forward.multiply(breed.headReach())).add(0.0, breed.headLift(), 0.0);
        for (int i = 0; i < tailNodes.length; i++) {
            tailNodes[i] = bodyNodes[bodyNodes.length - 1].subtract(forward.multiply((i + 1) * breed.tailSpacing()));
        }
        for (int i = 0; i < legPoses.length; i++) {
            Vec3d attach = computeLegAttach(bodyNodes, forward, i);
            Vec3d foot = resolveAndClampNode(
                    findGroundPoint(locomotionRoot.add(relativeGripFootTarget(forward, sideVector(forward), i)), breed.stanceHeight() + 1.5),
                    breed.limbRadius() * 0.55f
            );
            Vec3d knee = solveKnee(attach, foot, breed.upperLimbLength(), breed.lowerLimbLength(), computeLegPole(forward, i));
            legPoses[i] = new LizardPoseSnapshot.LegPose(attach, knee, foot);
            legPlanted[i] = true;
            legReaching[i] = true;
            legGripCounter[i] = breed.limbGripDelay();
            legFlip[i] = legSide(i) < 0 ? 1.0f : -1.0f;
            legExtraLongStep[i] = false;
            legHuntPos[i] = foot;
        }
        syncControllerToBodyAnchor(bodyAnchor());
        skeletonInitialized = true;
    }

    private Vec3d initialForward() {
        float yaw = this.getYaw();
        if (Math.abs(yaw) < 0.001f) {
            yaw = this.random.nextFloat() * 360.0f;
            this.setYaw(yaw);
            this.setBodyYaw(yaw);
            this.setHeadYaw(yaw);
        }
        return yawToForward(yaw);
    }

    private void recoverOrSpawnParts(ServerWorld world) {
        Box searchBox = new Box(
                this.getX() - 8.0, this.getY() - 4.0, this.getZ() - 8.0,
                this.getX() + 8.0, this.getY() + 4.0, this.getZ() + 8.0
        );
        List<LizardPartEntity> nearby = world.getEntitiesByClass(
                LizardPartEntity.class,
                searchBox,
                part -> part.getParentId() == this.getId()
        );
        for (LizardPartEntity part : nearby) {
            partMap.put(part.partKey(), part);
        }

        ensurePart(world, LizardPartEntity.Kind.HEAD, 0, breed.headRadius(), headPos);
        for (int i = 0; i < bodyNodes.length; i++) {
            ensurePart(world, LizardPartEntity.Kind.BODY, i, breed.bodyRadius(i), bodyNodes[i]);
        }
        for (int i = 0; i < legPoses.length; i++) {
            ensurePart(world, LizardPartEntity.Kind.LEG_UPPER, i, breed.limbRadius(), midpoint(legPoses[i].attach(), legPoses[i].knee()));
            ensurePart(world, LizardPartEntity.Kind.LEG_LOWER, i, breed.limbRadius() * 0.92f, midpoint(legPoses[i].knee(), legPoses[i].foot()));
        }
        for (int i = 0; i < tailNodes.length; i++) {
            ensurePart(world, LizardPartEntity.Kind.TAIL, i, breed.tailRadius(i), tailNodes[i]);
        }
    }

    private void ensurePart(ServerWorld world, LizardPartEntity.Kind kind, int index, float radius, Vec3d pos) {
        String key = partKey(kind, index);
        LizardPartEntity existing = partMap.get(key);
        if (existing != null && existing.isAlive()) {
            return;
        }

        LizardPartEntity created = KarmaGateMod.LIZARD_PART_ENTITY_TYPE.create(world);
        if (created == null) {
            return;
        }
        created.configure(this, kind, index, radius, pos);
        world.spawnEntity(created);
        partMap.put(key, created);
    }

    private void updateForwardLocomotion(ServerWorld world) {
        Vec3d forward = travelHeading.lengthSquared() > 1.0e-5 ? travelHeading.normalize() : initialForward();

        if (isBlockedAhead(world, forward, locomotionRoot)) {
            float turn = (this.random.nextBoolean() ? 1f : -1f) * (20f + this.random.nextFloat() * breed.turnRateDegrees());
            float yaw = this.getYaw() + turn;
            this.setYaw(yaw);
            this.setBodyYaw(yaw);
            this.setHeadYaw(yaw);
            forward = yawToForward(yaw);
        }

        travelHeading = forward;
        int supportCount = grippingCount(0, legPoses.length);
        if (supportCount > 0) {
            rootVelocity = new Vec3d(rootVelocity.x * 0.55, Math.min(0.0, rootVelocity.y) * 0.35, rootVelocity.z * 0.55);
            walkCycle += computeFrameSpeed(supportCount) * 36.0f;
        } else {
            rootVelocity = new Vec3d(0.0, Math.max(-0.35, rootVelocity.y - 0.08), 0.0);
            walkCycle += computeFrameSpeed(supportCount) * 18.0f;
        }
    }

    private void updateSkeleton(ServerWorld world) {
        Vec3d forward = travelHeading.lengthSquared() > 1.0e-5 ? travelHeading.normalize() : initialForward();
        Vec3d side = sideVector(forward);

        int frontLegsGripping = grippingCount(0, 2);
        int hindLegsGripping = grippingCount(2, 4);
        float bobSmoothing = (4.0f + 7.0f / Math.max(0.01f, breed.walkBob())) * 0.5f;
        frontBob = (frontBob * bobSmoothing + (frontLegsGripping - 1)) / (bobSmoothing + 1.0f);
        hindBob = (hindBob * bobSmoothing + (hindLegsGripping - 1)) / (bobSmoothing + 1.0f);

        Vec3d currentRoot = locomotionRoot;
        Vec3d[] previewBody = computeBodyFrame(currentRoot, forward, frontBob * breed.walkBob() * 0.02, hindBob * breed.walkBob() * 0.02);
        Vec3d[] updatedFeet = new Vec3d[legPoses.length];

        for (int i = 0; i < legPoses.length; i++) {
            Vec3d attach = computeLegAttach(previewBody, forward, i);
            updatedFeet[i] = updateFootTarget(currentRoot, attach, forward, side, i);
        }

        double frameSpeed = computeFrameSpeed(grippingCount(0, legPoses.length));
        Vec3d rootTarget = solveRootFromLegs(currentRoot, forward, side, updatedFeet, frameSpeed).add(rootVelocity);
        Vec3d attemptedRoot = lerp(currentRoot, rootTarget, 0.60);
        Vec3d resolvedRoot = resolveRootPosition(attemptedRoot);
        if (resolvedRoot.y > attemptedRoot.y + 1.0e-3 && rootVelocity.y < 0.0) {
            rootVelocity = new Vec3d(rootVelocity.x, 0.0, rootVelocity.z);
        } else {
            rootVelocity = new Vec3d(rootVelocity.x, MathHelper.clamp(resolvedRoot.y - currentRoot.y, -0.3, 0.24), rootVelocity.z);
        }

        locomotionRoot = resolvedRoot;
        this.setVelocity(Vec3d.ZERO);

        Vec3d[] desiredBody = computeBodyFrame(resolvedRoot, forward, frontBob * breed.walkBob() * 0.02, hindBob * breed.walkBob() * 0.02);
        Vec3d[] previousBody = Arrays.copyOf(bodyNodes, bodyNodes.length);
        applyBodyDrive(desiredBody, forward, frameSpeed);
        for (int i = 0; i < bodyNodes.length; i++) {
            bodyNodes[i] = bodyNodes[i].add(bodyVelocities[i]);
            if (i > 0) {
                bodyNodes[i] = constrainDistance(bodyNodes[i], bodyNodes[i - 1], breed.bodySpacing());
            }
            bodyNodes[i] = resolveAndClampNode(bodyNodes[i], breed.bodyRadius(i));
        }
        for (int i = bodyNodes.length - 2; i >= 0; i--) {
            bodyNodes[i] = constrainDistance(bodyNodes[i], bodyNodes[i + 1], breed.bodySpacing());
            bodyNodes[i] = resolveAndClampNode(bodyNodes[i], breed.bodyRadius(i));
        }
        for (int i = 0; i < bodyNodes.length; i++) {
            bodyVelocities[i] = clampMagnitude(bodyNodes[i].subtract(previousBody[i]).multiply(0.42), 0.10);
        }

        Vec3d desiredHead = bodyNodes[0]
                .add(forward.multiply(breed.headReach()))
                .add(0.0, breed.headLift() + frontBob * breed.walkBob() * 0.01, 0.0);
        headPos = lerp(headPos, desiredHead, 0.24 + breed.neckStiffness() * 0.26);
        headPos = resolveAndClampNode(constrainDistance(headPos, bodyNodes[0], breed.headReach()), breed.headRadius());

        Vec3d tailAnchor = bodyNodes[bodyNodes.length - 1];
        Vec3d previous = tailAnchor;
        for (int i = 0; i < tailNodes.length; i++) {
            double sway = Math.sin((walkCycle * 0.055) - i * 0.55) * breed.tailSway() * Math.max(0.15, 1.0 - (i / (double) tailNodes.length));
            Vec3d desired = previous
                    .subtract(forward.multiply(breed.tailSpacing()))
                    .add(side.multiply(sway));
            tailNodes[i] = lerp(tailNodes[i], desired, Math.max(0.18, 0.34 - i * 0.018));
            tailNodes[i] = resolveAndClampNode(constrainDistance(tailNodes[i], previous, breed.tailSpacing()), breed.tailRadius(i));
            previous = tailNodes[i];
        }

        for (int i = 0; i < legPoses.length; i++) {
            Vec3d attach = computeLegAttach(bodyNodes, forward, i);
            Vec3d foot = resolveAndClampNode(clampFootToReach(attach, updatedFeet[i]), breed.limbRadius() * 0.55f);
            Vec3d knee = solveKnee(attach, foot, breed.upperLimbLength(), breed.lowerLimbLength(), computeLegPole(forward, i));
            legPoses[i] = new LizardPoseSnapshot.LegPose(
                    attach,
                    resolveAndClampNode(knee, breed.limbRadius() * 0.5f),
                    foot
            );
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
        this.setYaw(yaw);
        this.setBodyYaw(yaw);
        this.setHeadYaw(yaw);
        syncControllerToBodyAnchor(bodyAnchor());
    }

    private Vec3d updateFootTarget(Vec3d root, Vec3d attach, Vec3d forward, Vec3d side, int legIndex) {
        LizardPoseSnapshot.LegPose previous = legPoses[legIndex];
        Vec3d foot = previous.foot();
        double jointDist = limbJointDistance();
        Vec3d dragAim = limbAimDirection(attach, root, forward, legIndex);
        Vec3d outward = side.multiply(legSide(legIndex));
        Vec3d footOffset = foot.subtract(attach);
        double forwardDist = signedStepDistance(foot, attach, dragAim);
        double sideDist = footOffset.dotProduct(side);
        float desiredFlip = sideDist < 0.0 ? 1.0f : -1.0f;
        legFlip[legIndex] = MathHelper.lerp(0.3f, legFlip[legIndex], desiredFlip);
        double huntSpeed = 0.10 + breed.limbSpeed() * 0.20;
        double snapThreshold = breed.limbRadius() + 0.08;

        if (!legReaching[legIndex]) {
            legPlanted[legIndex] = false;
            legGripCounter[legIndex] = 0;
            legHuntPos[legIndex] = lerp(foot, attach, breed.liftFeet())
                    .add(dragAim.multiply(jointDist + 0.05))
                    .add(outward.multiply(breed.stanceWidth() * 0.75 + breed.legPairDisplacement() * 0.10));
            foot = lerp(foot, legHuntPos[legIndex], huntSpeed + breed.limbQuickness() * 0.18);
            if (legExtraLongStep[legIndex]) {
                int pairIndex = pairedLegIndex(legIndex);
                if (pairIndex < 0 || legGripCounter[pairIndex] > breed.limbGripDelay()) {
                    legExtraLongStep[legIndex] = false;
                }
            }
            if (!legExtraLongStep[legIndex] && forwardDist < -jointDist * positiveStepLength()) {
                legReaching[legIndex] = true;
            }
        } else {
            if (!isOverlappingHuntPos(foot, legHuntPos[legIndex], snapThreshold)) {
                Vec3d searchDirection = dragAim
                        .add(outward.multiply(0.42 + breed.legPairDisplacement() * 0.18 * Math.max(0.25f, Math.abs(legFlip[legIndex]))))
                        .add(0.0, -0.30 * breed.feetDown(), 0.0);
                legHuntPos[legIndex] = findGripTarget(attach, searchDirection, jointDist - 0.03, jointDist * 2.0);
                foot = lerp(foot, legHuntPos[legIndex], huntSpeed + breed.limbQuickness() * 0.14);
                legPlanted[legIndex] = false;
                legGripCounter[legIndex] = 0;
            } else {
                foot = lerp(foot, legHuntPos[legIndex], 0.35);
                legPlanted[legIndex] = true;
                legGripCounter[legIndex] = Math.min(20, legGripCounter[legIndex] + 1);
                boolean pairAllowsRelease = !breed.smoothenLegMovement() || pairGripCounter(legIndex) >= breed.limbGripDelay();
                if (forwardDist > -jointDist * 0.5 * (breed.stepLength() + 0.1)
                        && attach.distanceTo(foot) > jointDist - 0.03
                        && attach.distanceTo(legHuntPos[legIndex]) > jointDist
                        && pairAllowsRelease) {
                    int pairIndex = pairedLegIndex(legIndex);
                    legExtraLongStep[legIndex] = pairIndex >= 0
                            && breed.smoothenLegMovement()
                            && legGripCounter[pairIndex] < 1;
                    legReaching[legIndex] = false;
                    legPlanted[legIndex] = false;
                    legGripCounter[legIndex] = 0;
                }
            }
        }

        return clampFootToReach(attach, foot);
    }

    private Vec3d solveRootFromLegs(Vec3d currentRoot, Vec3d forward, Vec3d side, Vec3d[] feet, double frameSpeed) {
        Vec3d accumulated = Vec3d.ZERO;
        int plantedCount = 0;
        for (int i = 0; i < feet.length; i++) {
            if (!legPlanted[i] || legGripCounter[i] < breed.limbGripDelay()) {
                continue;
            }
            Vec3d relativeFoot = relativeGripFootTarget(forward, side, i);
            accumulated = accumulated.add(feet[i].subtract(relativeFoot));
            plantedCount++;
        }

        double support = plantedCount / (double) feet.length;
        Vec3d drivenRoot = currentRoot.add(forward.multiply(frameSpeed * MathHelper.lerp((float) support, 1.00f, 1.45f)));
        if (plantedCount == 0) {
            return drivenRoot;
        }

        Vec3d averaged = accumulated.multiply(1.0 / plantedCount);
        Vec3d correction = averaged.subtract(currentRoot);
        double forwardCorrection = MathHelper.clamp(correction.dotProduct(forward), -0.02, 0.08);
        double sideCorrection = MathHelper.clamp(correction.dotProduct(side), -0.03, 0.03);
        double verticalCorrection = MathHelper.clamp(correction.y, -0.04, 0.04);

        return drivenRoot
                .add(forward.multiply(forwardCorrection * 0.30))
                .add(side.multiply(sideCorrection * 0.20))
                .add(0.0, verticalCorrection * 0.22, 0.0);
    }

    private void applyBodyDrive(Vec3d[] desiredBody, Vec3d forward, double frameSpeed) {
        for (int i = 0; i < bodyVelocities.length; i++) {
            double stiffness = switch (i) {
                case 0 -> 0.16 + breed.bodyStiffness() * 0.04;
                case 1 -> 0.12 + breed.bodyStiffness() * 0.04;
                default -> 0.10 + breed.bodyStiffness() * 0.03;
            };
            double forwardPush = switch (i) {
                case 0 -> frameSpeed * 0.14;
                case 1 -> frameSpeed * 0.09;
                default -> frameSpeed * 0.06;
            };
            bodyVelocities[i] = clampMagnitude(
                    bodyVelocities[i].multiply(0.48).add(desiredBody[i].subtract(bodyNodes[i]).multiply(stiffness)).add(forward.multiply(forwardPush)),
                    0.12
            );
        }
    }

    private Vec3d[] computeBodyFrame(Vec3d root, Vec3d forward, double frontBob, double hindBob) {
        Vec3d[] frame = new Vec3d[bodyNodes.length];
        Vec3d liftedRoot = root.add(0.0, breed.bodyLift(), 0.0);
        for (int i = 0; i < frame.length; i++) {
            double along = i * breed.bodySpacing();
            double bob = switch (i) {
                case 0 -> frontBob;
                case 1 -> (frontBob + hindBob) * 0.5;
                default -> hindBob;
            };
            frame[i] = liftedRoot.subtract(forward.multiply(along)).add(0.0, bob, 0.0);
        }
        return frame;
    }

    private Vec3d computeLegAttach(Vec3d[] bodyFrame, Vec3d forward, int legIndex) {
        Vec3d side = sideVector(forward);
        boolean front = legIndex < 2;
        double along = front ? breed.frontShoulderOffset() : (breed.bodySpacing() + breed.hindHipOffset());
        int bodyIndex = front ? 0 : Math.min(bodyFrame.length - 1, 2);
        Vec3d body = bodyFrame[bodyIndex];
        return body
                .subtract(forward.multiply(along * 0.25))
                .add(side.multiply(legSide(legIndex) * breed.stanceWidth() * 0.52))
                .add(0.0, -0.08, 0.0);
    }

    private Vec3d relativeGripFootTarget(Vec3d forward, Vec3d side, int legIndex) {
        double sideSign = legSide(legIndex);
        Vec3d[] relativeBody = computeBodyFrame(Vec3d.ZERO, forward, 0.0, 0.0);
        Vec3d attach = computeLegAttach(relativeBody, forward, legIndex);
        return attach
                .add(side.multiply(sideSign * (breed.stanceWidth() * 1.06) + sideSign * breed.legPairDisplacement() * 0.16))
                .add(forward.multiply(limbJointDistance() + 0.10))
                .add(0.0, -breed.stanceHeight() - 0.05 * breed.feetDown(), 0.0);
    }

    private Vec3d clampFootToReach(Vec3d attach, Vec3d foot) {
        double maxReach = breed.upperLimbLength() + breed.lowerLimbLength() - 0.01;
        if (attach.distanceTo(foot) <= maxReach) {
            return foot;
        }
        Vec3d dir = foot.subtract(attach);
        if (dir.lengthSquared() < 1.0e-6) {
            return foot;
        }
        return attach.add(dir.normalize().multiply(maxReach));
    }

    private Vec3d computeLegPole(Vec3d forward, int legIndex) {
        Vec3d side = sideVector(forward);
        boolean front = legIndex < 2;
        return side.multiply(legSide(legIndex) * 0.9)
                .add(0.0, 0.8, 0.0)
                .add(forward.multiply(front ? 0.25 : -0.16))
                .normalize();
    }

    private void updateHitboxParts() {
        updatePart(LizardPartEntity.Kind.HEAD, 0, headPos, breed.headRadius());
        for (int i = 0; i < bodyNodes.length; i++) {
            updatePart(LizardPartEntity.Kind.BODY, i, bodyNodes[i], breed.bodyRadius(i));
        }
        for (int i = 0; i < tailNodes.length; i++) {
            updatePart(LizardPartEntity.Kind.TAIL, i, tailNodes[i], breed.tailRadius(i));
        }
        for (int i = 0; i < legPoses.length; i++) {
            updatePart(LizardPartEntity.Kind.LEG_UPPER, i, midpoint(legPoses[i].attach(), legPoses[i].knee()), breed.limbRadius());
            updatePart(LizardPartEntity.Kind.LEG_LOWER, i, midpoint(legPoses[i].knee(), legPoses[i].foot()), breed.limbRadius() * 0.92f);
        }
    }

    private void updatePart(LizardPartEntity.Kind kind, int index, Vec3d pos, float radius) {
        LizardPartEntity part = partMap.get(partKey(kind, index));
        if (part == null || !part.isAlive()) {
            return;
        }
        part.configure(this, kind, index, radius, pos);
    }

    private LizardPoseSnapshot buildSnapshot() {
        Vec3d[] bodyCopy = Arrays.copyOf(bodyNodes, bodyNodes.length);
        Vec3d[] tailCopy = Arrays.copyOf(tailNodes, tailNodes.length);
        LizardPoseSnapshot.LegPose[] legCopy = Arrays.copyOf(legPoses, legPoses.length);
        return new LizardPoseSnapshot(headPos, bodyCopy, tailCopy, legCopy, walkCycle);
    }

    private String partKey(LizardPartEntity.Kind kind, int index) {
        return kind.name() + ":" + index;
    }

    private boolean isBlockedAhead(ServerWorld world, Vec3d forward, Vec3d root) {
        Vec3d probe = root.add(forward.multiply(0.85));
        BlockPos low = BlockPos.ofFloored(probe.x, root.y + 0.25, probe.z);
        BlockPos mid = BlockPos.ofFloored(probe.x, root.y + breed.bodyLift() + 0.45, probe.z);
        return isSolid(world, low) || isSolid(world, mid);
    }

    private boolean isSolid(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isSolidBlock(world, pos);
    }

    private double sampleGroundY(Vec3d near, double fallback, double maxDrop) {
        BlockPos.Mutable mutable = BlockPos.ofFloored(near).mutableCopy();
        int maxY = MathHelper.floor(near.y + 2.0);
        int minY = MathHelper.floor(near.y - Math.max(0.5, maxDrop));
        for (int y = maxY; y >= minY; y--) {
            mutable.set(near.x, y, near.z);
            if (this.getWorld().getBlockState(mutable).isSolidBlock(this.getWorld(), mutable)) {
                return y + 1.0;
            }
        }
        return fallback;
    }

    private Vec3d findGroundPoint(Vec3d near, double maxDrop) {
        double groundY = sampleGroundY(near.add(0.0, 1.2, 0.0), near.y - 0.4, maxDrop);
        return new Vec3d(near.x, groundY + 0.02, near.z);
    }

    private Vec3d resolveRootPosition(Vec3d root) {
        double groundY = sampleGroundY(root.add(0.0, 1.5, 0.0), root.y, breed.stanceHeight() + breed.bodyLift() + 4.0);
        Vec3d center = root.add(0.0, breed.bodyLift() * 0.9, 0.0);
        Vec3d resolvedCenter = resolveAndClampNode(center, breed.bodyRadius(Math.min(1, bodyNodes.length - 1)));
        return new Vec3d(resolvedCenter.x, Math.max(groundY + 0.02, resolvedCenter.y - breed.bodyLift() * 0.9), resolvedCenter.z);
    }

    private Vec3d resolveNodeCollision(Vec3d pos, double radius) {
        if (this.getWorld() == null || radius <= 0.0) {
            return pos;
        }
        Vec3d resolved = pos;
        for (int iter = 0; iter < 3; iter++) {
            Box sphereBox = new Box(
                    resolved.x - radius, resolved.y - radius, resolved.z - radius,
                    resolved.x + radius, resolved.y + radius, resolved.z + radius
            );
            boolean pushed = false;
            int minX = MathHelper.floor(sphereBox.minX);
            int maxX = MathHelper.floor(sphereBox.maxX);
            int minY = MathHelper.floor(sphereBox.minY);
            int maxY = MathHelper.floor(sphereBox.maxY);
            int minZ = MathHelper.floor(sphereBox.minZ);
            int maxZ = MathHelper.floor(sphereBox.maxZ);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos blockPos = new BlockPos(x, y, z);
                        VoxelShape shape = this.getWorld().getBlockState(blockPos).getCollisionShape(this.getWorld(), blockPos);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        for (Box localBox : shape.getBoundingBoxes()) {
                            Box box = localBox.offset(blockPos);
                            resolved = pushSphereOutOfBox(resolved, radius, box);
                        }
                        pushed = true;
                    }
                }
            }
            if (!pushed) {
                break;
            }
        }
        return resolved;
    }

    private Vec3d resolveAndClampNode(Vec3d pos, double radius) {
        Vec3d resolved = resolveNodeCollision(pos, radius);
        Vec3d clamped = clampNodeAboveGround(resolved, radius);
        if (!clamped.equals(resolved)) {
            return resolveNodeCollision(clamped, radius);
        }
        return resolved;
    }

    private Vec3d clampNodeAboveGround(Vec3d pos, double radius) {
        double searchDrop = Math.max(breed.stanceHeight() + breed.bodyLift() + 1.5, radius + 1.0);
        double groundY = sampleGroundY(pos.add(0.0, Math.max(0.6, radius + 0.4), 0.0), pos.y - radius - 0.2, searchDrop);
        double minY = groundY + radius + 1.0e-3;
        if (pos.y < minY) {
            return new Vec3d(pos.x, minY, pos.z);
        }
        return pos;
    }

    private Vec3d pushSphereOutOfBox(Vec3d center, double radius, Box box) {
        double closestX = MathHelper.clamp(center.x, box.minX, box.maxX);
        double closestY = MathHelper.clamp(center.y, box.minY, box.maxY);
        double closestZ = MathHelper.clamp(center.z, box.minZ, box.maxZ);
        double dx = center.x - closestX;
        double dy = center.y - closestY;
        double dz = center.z - closestZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq >= radius * radius) {
            return center;
        }

        if (distSq > 1.0e-7) {
            double dist = Math.sqrt(distSq);
            double push = radius - dist + 1.0e-3;
            return center.add(dx / dist * push, dy / dist * push, dz / dist * push);
        }

        double pushLeft = Math.abs(center.x - box.minX);
        double pushRight = Math.abs(box.maxX - center.x);
        double pushUp = Math.abs(box.maxY - center.y);
        double pushNorth = Math.abs(center.z - box.minZ);
        double pushSouth = Math.abs(box.maxZ - center.z);

        double minPush = pushUp;
        Vec3d direction = new Vec3d(0.0, 1.0, 0.0);
        if (pushLeft < minPush) {
            minPush = pushLeft;
            direction = new Vec3d(-1.0, 0.0, 0.0);
        }
        if (pushRight < minPush) {
            minPush = pushRight;
            direction = new Vec3d(1.0, 0.0, 0.0);
        }
        if (pushNorth < minPush) {
            minPush = pushNorth;
            direction = new Vec3d(0.0, 0.0, -1.0);
        }
        if (pushSouth < minPush) {
            direction = new Vec3d(0.0, 0.0, 1.0);
        }

        return center.add(direction.multiply(radius + 1.0e-3));
    }

    private static Vec3d solveKnee(Vec3d attach, Vec3d foot, double upperLength, double lowerLength, Vec3d poleHint) {
        Vec3d delta = foot.subtract(attach);
        double distance = MathHelper.clamp(delta.length(), 1.0e-4, upperLength + lowerLength - 1.0e-4);
        Vec3d dir = delta.normalize();
        Vec3d planeNormal = dir.crossProduct(poleHint);
        if (planeNormal.lengthSquared() < 1.0e-6) {
            planeNormal = dir.crossProduct(new Vec3d(0.0, 1.0, 0.0));
            if (planeNormal.lengthSquared() < 1.0e-6) {
                planeNormal = dir.crossProduct(new Vec3d(1.0, 0.0, 0.0));
            }
        }
        planeNormal = planeNormal.normalize();
        Vec3d bendDir = planeNormal.crossProduct(dir).normalize();

        double along = (upperLength * upperLength - lowerLength * lowerLength + distance * distance) / (2.0 * distance);
        double heightSq = Math.max(0.0, upperLength * upperLength - along * along);
        double height = Math.sqrt(heightSq);
        return attach.add(dir.multiply(along)).add(bendDir.multiply(height));
    }

    private static Vec3d midpoint(Vec3d a, Vec3d b) {
        return new Vec3d((a.x + b.x) * 0.5, (a.y + b.y) * 0.5, (a.z + b.z) * 0.5);
    }

    private static Vec3d sideVector(Vec3d forward) {
        Vec3d side = forward.crossProduct(new Vec3d(0.0, 1.0, 0.0));
        if (side.lengthSquared() < 1.0e-6) {
            side = new Vec3d(1.0, 0.0, 0.0);
        }
        return side.normalize();
    }

    private static Vec3d yawToForward(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(radians), 0.0, Math.cos(radians)).normalize();
    }

    private static Vec3d lerp(Vec3d from, Vec3d to, double t) {
        return new Vec3d(
                MathHelper.lerp(t, from.x, to.x),
                MathHelper.lerp(t, from.y, to.y),
                MathHelper.lerp(t, from.z, to.z)
        );
    }

    private static Vec3d constrainDistance(Vec3d point, Vec3d anchor, double distance) {
        Vec3d delta = point.subtract(anchor);
        if (delta.lengthSquared() < 1.0e-7) {
            return anchor.add(distance, 0.0, 0.0);
        }
        return anchor.add(delta.normalize().multiply(distance));
    }

    private Vec3d limbAimDirection(Vec3d attach, Vec3d root, Vec3d forward, int legIndex) {
        Vec3d chunkForward = legIndex < 2
                ? safeDir(bodyNodes[Math.min(1, bodyNodes.length - 1)], bodyNodes[0])
                : safeDir(bodyNodes[bodyNodes.length - 1], bodyNodes[Math.max(0, bodyNodes.length - 2)]);
        Vec3d aimFor = safeDir(attach, root.add(forward.multiply(2.2)));
        return lerp(chunkForward, aimFor, 0.4).normalize();
    }

    private double signedStepDistance(Vec3d point, Vec3d anchor, Vec3d aim) {
        Vec3d flatAim = new Vec3d(aim.x, 0.0, aim.z);
        if (flatAim.lengthSquared() < 1.0e-6) {
            flatAim = new Vec3d(1.0, 0.0, 0.0);
        }
        Vec3d lineDir = sideVector(flatAim.normalize());
        return signedDistanceToLine2d(point, anchor.add(lineDir), anchor);
    }

    private Vec3d findGripTarget(Vec3d attach, Vec3d searchDirection, double maxAttachDistance, double searchDistance) {
        Vec3d dir = searchDirection.lengthSquared() < 1.0e-6 ? new Vec3d(0.0, -1.0, 0.0) : searchDirection.normalize();
        Vec3d horizontalDir = new Vec3d(dir.x, 0.0, dir.z);
        if (horizontalDir.lengthSquared() < 1.0e-6) {
            horizontalDir = new Vec3d(0.0, 0.0, 1.0);
        }
        horizontalDir = horizontalDir.normalize();
        Vec3d lateral = sideVector(horizontalDir);
        Vec3d goal = attach.add(dir.multiply(searchDistance));

        Vec3d best = resolveAndClampNode(findGroundPoint(goal, breed.stanceHeight() + 1.5), breed.limbRadius() * 0.55f);
        double bestScore = gripCandidateScore(best, goal, attach, maxAttachDistance);

        Vec3d[] offsets = new Vec3d[] {
                Vec3d.ZERO,
                horizontalDir.multiply(0.18),
                horizontalDir.multiply(-0.18),
                lateral.multiply(0.18),
                lateral.multiply(-0.18),
                horizontalDir.multiply(0.12).add(lateral.multiply(0.12)),
                horizontalDir.multiply(0.12).add(lateral.multiply(-0.12))
        };

        for (Vec3d offset : offsets) {
            Vec3d candidateGoal = goal.add(offset);
            Vec3d candidate = resolveAndClampNode(findGroundPoint(candidateGoal, breed.stanceHeight() + 1.5), breed.limbRadius() * 0.55f);
            double score = gripCandidateScore(candidate, candidateGoal, attach, maxAttachDistance);
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }

        return best;
    }

    private double gripCandidateScore(Vec3d candidate, Vec3d goal, Vec3d attach, double maxAttachDistance) {
        double overreachPenalty = Math.max(0.0, candidate.distanceTo(attach) - maxAttachDistance) * 4.0;
        return candidate.squaredDistanceTo(goal) + overreachPenalty;
    }

    private static boolean isOverlappingHuntPos(Vec3d foot, Vec3d huntPos, double threshold) {
        return foot.squaredDistanceTo(huntPos) <= threshold * threshold;
    }

    private static Vec3d clampMagnitude(Vec3d vec, double maxLength) {
        double lengthSq = vec.lengthSquared();
        if (lengthSq <= maxLength * maxLength) {
            return vec;
        }
        if (lengthSq < 1.0e-9) {
            return Vec3d.ZERO;
        }
        return vec.normalize().multiply(maxLength);
    }

    private double computeFrameSpeed(int supportCount) {
        float grip = supportCount / (float) legPoses.length;
        float gripFactor = grip * (1.0f - breed.noGripSpeed()) + breed.noGripSpeed();
        gripFactor = Math.max(0.35f, gripFactor);
        return breed.movementSpeed() * gripFactor;
    }

    private int grippingCount(int fromInclusive, int toExclusive) {
        int count = 0;
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (legGripCounter[i] >= breed.limbGripDelay()) {
                count++;
            }
        }
        return count;
    }

    private int pairGripCounter(int legIndex) {
        int pairIndex = pairedLegIndex(legIndex);
        if (pairIndex < 0) {
            return 0;
        }
        return legGripCounter[pairIndex];
    }

    private int pairedLegIndex(int legIndex) {
        int pairIndex = (legIndex % 2 == 0) ? legIndex + 1 : legIndex - 1;
        return (pairIndex >= 0 && pairIndex < legGripCounter.length) ? pairIndex : -1;
    }

    private double limbJointDistance() {
        return (breed.upperLimbLength() + breed.lowerLimbLength()) * 0.94;
    }

    private double positiveStepLength() {
        return MathHelper.lerp(breed.stepLength(), -0.5f, 0.5f);
    }

    private static int legSide(int legIndex) {
        return (legIndex & 1) == 0 ? -1 : 1;
    }

    private static Vec3d safeDir(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        if (delta.lengthSquared() < 1.0e-6) {
            return new Vec3d(0.0, 0.0, 1.0);
        }
        return delta.normalize();
    }

    private static double signedDistanceToLine2d(Vec3d point, Vec3d l2, Vec3d l1) {
        double ax = l2.x - l1.x;
        double az = l2.z - l1.z;
        double denom = Math.sqrt(ax * ax + az * az);
        if (denom < 1.0e-6) {
            return 0.0;
        }
        return (az * point.x - ax * point.z + l2.x * l1.z - l2.z * l1.x) / denom;
    }

    private Vec3d bodyAnchor() {
        if (bodyNodes.length == 0) {
            return locomotionRoot;
        }
        return bodyNodes[Math.min(bodyNodes.length - 1, 1)];
    }

    private void syncControllerToBodyAnchor(Vec3d anchor) {
        this.refreshPositionAndAngles(anchor.x, anchor.y, anchor.z, this.getYaw(), this.getPitch());
        this.setBoundingBox(Box.of(anchor, 0.01, 0.01, 0.01));
    }
}
