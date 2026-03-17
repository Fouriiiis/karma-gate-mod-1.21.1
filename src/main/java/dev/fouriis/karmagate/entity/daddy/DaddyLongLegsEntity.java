package dev.fouriis.karmagate.entity.daddy;

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
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class DaddyLongLegsEntity extends HostileEntity {
    public record RenderTentacleData(
            DaddyTentacleController.Task task,
            boolean support,
            boolean anchored,
            float extensionProgress,
            Vec3d anchorPos,
            Vec3d temporaryGoalPos,
            Vec3d tipPos,
            List<Vec3d> points
    ) {
    }

    private static final TrackedData<NbtCompound> TENTACLE_SYNC = DataTracker.registerData(DaddyLongLegsEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);
    private static final TrackedData<Integer> AI_STATE = DataTracker.registerData(DaddyLongLegsEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> TARGET_X = DataTracker.registerData(DaddyLongLegsEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> TARGET_Y = DataTracker.registerData(DaddyLongLegsEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> TARGET_Z = DataTracker.registerData(DaddyLongLegsEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private final DaddyVariantConfig variantConfig;
    private final DaddyLongLegsAIController brain;
    private final DaddyTentacleController[] tentacles;

    public DaddyLongLegsEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        this.variantConfig = StandardDaddyVariantConfig.INSTANCE;
        this.brain = new DaddyLongLegsAIController(variantConfig);
        this.tentacles = new DaddyTentacleController[variantConfig.tentacleCount()];
        for (int i = 0; i < tentacles.length; i++) {
            boolean support = i < variantConfig.supportTentacleCount();
            tentacles[i] = new DaddyTentacleController(i, support, variantConfig);
        }
        this.experiencePoints = 20;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 80.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.75)
                .add(EntityAttributes.GENERIC_ARMOR, 6.0)
                .add(EntityAttributes.GENERIC_STEP_HEIGHT, 1.1);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(1, new SwimGoal(this));
        this.goalSelector.add(8, new LookAroundGoal(this));
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(TENTACLE_SYNC, new NbtCompound());
        builder.add(AI_STATE, DaddyLongLegsAIController.State.IDLE.ordinal());
        builder.add(TARGET_X, 0f);
        builder.add(TARGET_Y, 0f);
        builder.add(TARGET_Z, 0f);
    }

    @Override
    public void tick() {
        this.setNoGravity(true);
        this.fallDistance = 0f;
        super.tick();
        if (this.getWorld().isClient) {
            return;
        }
        serverTick((ServerWorld) this.getWorld());
    }

    public DaddyVariantConfig getVariantConfig() {
        return variantConfig;
    }

    public Vec3d getWanderTarget() {
        return new Vec3d(this.dataTracker.get(TARGET_X), this.dataTracker.get(TARGET_Y), this.dataTracker.get(TARGET_Z));
    }

    public DaddyLongLegsAIController.State getBrainState() {
        int ordinal = MathHelper.clamp(this.dataTracker.get(AI_STATE), 0, DaddyLongLegsAIController.State.values().length - 1);
        return DaddyLongLegsAIController.State.values()[ordinal];
    }

    public int getTentacleCount() {
        return variantConfig.tentacleCount();
    }

    public Vec3d getTentacleSocketPosition(int index, float tickDelta) {
        float t = this.age + tickDelta;
        double angle = (Math.PI * 2.0 * index / Math.max(1, variantConfig.tentacleCount())) + (t * 0.025);
        double r = variantConfig.bodyRadius() * 0.9;
        double wobble = Math.sin(t * 0.08 + index * 0.9) * 0.06;

        return new Vec3d(
                this.getX() + Math.cos(angle) * r,
                this.getY() + this.getHeight() * 0.5 + wobble,
                this.getZ() + Math.sin(angle) * r
        );
    }

    public List<RenderTentacleData> getRenderTentacles() {
        NbtCompound root = this.dataTracker.get(TENTACLE_SYNC);
        NbtList list = root.getList("t", NbtElement.COMPOUND_TYPE);
        List<RenderTentacleData> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            NbtCompound tag = list.getCompound(i);
            int taskOrdinal = MathHelper.clamp(tag.getInt("task"), 0, DaddyTentacleController.Task.values().length - 1);
            DaddyTentacleController.Task task = DaddyTentacleController.Task.values()[taskOrdinal];
            boolean support = tag.getBoolean("support");
            boolean anchored = tag.getBoolean("anchored");
            float progress = tag.getFloat("progress");
            Vec3d anchor = new Vec3d(tag.getDouble("ax"), tag.getDouble("ay"), tag.getDouble("az"));
            Vec3d temporaryGoal = new Vec3d(tag.getDouble("gx"), tag.getDouble("gy"), tag.getDouble("gz"));
            Vec3d tipPos = new Vec3d(tag.getDouble("tx"), tag.getDouble("ty"), tag.getDouble("tz"));

            NbtList segList = tag.getList("segments", NbtElement.COMPOUND_TYPE);
            List<Vec3d> points = new ArrayList<>(segList.size());
            for (int s = 0; s < segList.size(); s++) {
                NbtCompound seg = segList.getCompound(s);
                points.add(new Vec3d(seg.getDouble("x"), seg.getDouble("y"), seg.getDouble("z")));
            }

            out.add(new RenderTentacleData(task, support, anchored, progress, anchor, temporaryGoal, tipPos, points));
        }
        return out;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("DaddyAiState", this.dataTracker.get(AI_STATE));
        nbt.putFloat("TargetX", this.dataTracker.get(TARGET_X));
        nbt.putFloat("TargetY", this.dataTracker.get(TARGET_Y));
        nbt.putFloat("TargetZ", this.dataTracker.get(TARGET_Z));
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.dataTracker.set(AI_STATE, nbt.getInt("DaddyAiState"));
        this.dataTracker.set(TARGET_X, nbt.getFloat("TargetX"));
        this.dataTracker.set(TARGET_Y, nbt.getFloat("TargetY"));
        this.dataTracker.set(TARGET_Z, nbt.getFloat("TargetZ"));
    }

    private void serverTick(ServerWorld world) {
        boolean forceReanchor = this.age % 5 == 0 && this.horizontalCollision;
        brain.tick(world, this, false);

        Vec3d target = brain.getWanderTarget();
        if (target == null) {
            target = this.getPos();
        }

        this.dataTracker.set(AI_STATE, brain.getState().ordinal());
        this.dataTracker.set(TARGET_X, (float) target.x);
        this.dataTracker.set(TARGET_Y, (float) target.y);
        this.dataTracker.set(TARGET_Z, (float) target.z);

        Vec3d supportPull = Vec3d.ZERO;
        Vec3d advancePull = Vec3d.ZERO;
        int anchoredSupport = 0;
        int anchoredAdvance = 0;
        float biasBoost = brain.getSearchBiasBoost();

        for (int i = 0; i < tentacles.length; i++) {
            Vec3d socket = getTentacleSocketPosition(i, 0f);
            Vec3d pull = tentacles[i].tick(world, this, this.getPos(), socket, target, variantConfig, world.random, biasBoost, forceReanchor);
            if (tentacles[i].isSupport()) {
                supportPull = supportPull.add(pull);
                if (tentacles[i].isAnchored()) {
                    anchoredSupport++;
                }
            } else {
                advancePull = advancePull.add(pull);
                if (tentacles[i].isAnchored()) {
                    anchoredAdvance++;
                }
            }
        }

        int anchoredTotal = anchoredSupport + anchoredAdvance;
        float anchoredRatio = anchoredTotal / (float) Math.max(1, tentacles.length);
        float supportRatio = anchoredSupport / (float) Math.max(1, variantConfig.supportTentacleCount());
        float advanceRatio = anchoredAdvance / (float) Math.max(1, tentacles.length - variantConfig.supportTentacleCount());

        Vec3d toTarget = target.subtract(this.getPos());
        Vec3d targetDir = toTarget.lengthSquared() > 1.0e-5 ? toTarget.normalize() : Vec3d.ZERO;
        Vec3d horizontalDir = new Vec3d(targetDir.x, 0.0, targetDir.z);
        if (horizontalDir.lengthSquared() > 1.0e-5) {
            horizontalDir = horizontalDir.normalize();
        } else {
            horizontalDir = Vec3d.ZERO;
        }

        Vec3d locomotionPull = supportPull.multiply(0.95).add(advancePull.multiply(1.05));
        if (advanceRatio < 0.20f) {
            locomotionPull = locomotionPull.multiply(0.70);
        }

        Vec3d planarAssist = horizontalDir.multiply(variantConfig.movementForce() * (0.02 + 0.10 * advanceRatio));
        double targetYDelta = target.y - this.getY();
        double climbAssistY = MathHelper.clamp(targetYDelta * 0.015, -0.04, 0.05) * Math.max(0.25, anchoredRatio);
        Vec3d climbAssist = new Vec3d(0.0, climbAssistY, 0.0);

        double gravity = MathHelper.lerp(MathHelper.clamp(supportRatio, 0f, 1f), -0.040, -0.004);
        if (targetYDelta > 1.5 && anchoredRatio > 0.35f) {
            gravity += 0.010;
        }

        Vec3d velocity = this.getVelocity();
        velocity = velocity.add(locomotionPull).add(planarAssist).add(climbAssist).add(0.0, gravity, 0.0);

        double horizontalDamping = MathHelper.lerp(MathHelper.clamp(anchoredRatio, 0f, 1f), 0.89, 0.96);
        double verticalDamping = MathHelper.lerp(MathHelper.clamp(supportRatio, 0f, 1f), 0.90, 0.97);
        velocity = new Vec3d(velocity.x * horizontalDamping, velocity.y * verticalDamping, velocity.z * horizontalDamping);

        double maxHorizontalSpeed = 0.18 + 0.14 * anchoredRatio;
        Vec3d horizontalVel = new Vec3d(velocity.x, 0.0, velocity.z);
        if (horizontalVel.lengthSquared() > maxHorizontalSpeed * maxHorizontalSpeed) {
            horizontalVel = horizontalVel.normalize().multiply(maxHorizontalSpeed);
        }

        double maxVerticalSpeed = 0.16 + 0.16 * supportRatio;
        double clampedY = MathHelper.clamp(velocity.y, -maxVerticalSpeed, maxVerticalSpeed);
        velocity = new Vec3d(horizontalVel.x, clampedY, horizontalVel.z);

        this.setVelocity(velocity);
        this.velocityModified = true;

        Vec3d look = horizontalVel;
        if (look.lengthSquared() > 1.0e-4) {
            float yaw = (float) (MathHelper.atan2(look.z, look.x) * 57.2957763671875) - 90f;
            this.setYaw(yaw);
            this.setBodyYaw(yaw);
            this.setHeadYaw(yaw);
        }

        syncTentacles();
    }

    private void syncTentacles() {
        NbtCompound root = new NbtCompound();
        NbtList list = new NbtList();
        for (DaddyTentacleController tentacle : tentacles) {
            DaddyTentacleController.Snapshot s = tentacle.toSnapshot();
            NbtCompound tag = new NbtCompound();
            tag.putInt("task", s.task().ordinal());
            tag.putBoolean("support", s.support());
            tag.putBoolean("anchored", s.anchored());
            tag.putFloat("progress", s.extensionProgress());
            tag.putDouble("ax", s.anchorPos().x);
            tag.putDouble("ay", s.anchorPos().y);
            tag.putDouble("az", s.anchorPos().z);
            tag.putDouble("gx", s.temporaryGoalPos().x);
            tag.putDouble("gy", s.temporaryGoalPos().y);
            tag.putDouble("gz", s.temporaryGoalPos().z);
            tag.putDouble("tx", s.currentTipPos().x);
            tag.putDouble("ty", s.currentTipPos().y);
            tag.putDouble("tz", s.currentTipPos().z);

            NbtList segments = new NbtList();
            for (TentacleSegmentState seg : tentacle.getSegmentStates()) {
                NbtCompound st = new NbtCompound();
                Vec3d p = seg.getPos();
                st.putDouble("x", p.x);
                st.putDouble("y", p.y);
                st.putDouble("z", p.z);
                segments.add(st);
            }
            tag.put("segments", segments);
            list.add(tag);
        }
        root.put("t", list);
        this.dataTracker.set(TENTACLE_SYNC, root);
    }
}
