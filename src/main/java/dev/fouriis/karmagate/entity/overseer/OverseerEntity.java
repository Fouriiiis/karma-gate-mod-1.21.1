package dev.fouriis.karmagate.entity.overseer;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class OverseerEntity extends PathAwareEntity {
    public enum ColorVariant {
        YELLOW(0, 0xFFE84A, 0xFFF5A6);

        private final int id;
        private final int bodyColor;
        private final int glowColor;

        ColorVariant(int id, int bodyColor, int glowColor) {
            this.id = id;
            this.bodyColor = bodyColor;
            this.glowColor = glowColor;
        }

        public int id() {
            return id;
        }

        public int bodyColor() {
            return bodyColor;
        }

        public int glowColor() {
            return glowColor;
        }

        public static ColorVariant byId(int id) {
            for (ColorVariant variant : values()) {
                if (variant.id == id) {
                    return variant;
                }
            }
            return YELLOW;
        }
    }

    private static final double ROOM_RADIUS = 20.0;
    private static final double DESIRED_FOLLOW_DISTANCE = 4.0;
    private static final double MAX_SPEED = 0.26;
    private static final double TETHER_REST_LENGTH = 1.55;
    private static final double MAX_TETHER_LENGTH = 2.0;
    private static final int REANCHOR_COOLDOWN_TICKS = 90;

    private static final TrackedData<Integer> COLOR_VARIANT = DataTracker.registerData(
            OverseerEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> ROOT_X = DataTracker.registerData(
            OverseerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ROOT_Y = DataTracker.registerData(
            OverseerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ROOT_Z = DataTracker.registerData(
            OverseerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> LOOK_X = DataTracker.registerData(
            OverseerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> LOOK_Y = DataTracker.registerData(
            OverseerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> LOOK_Z = DataTracker.registerData(
            OverseerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> EXTENDED = DataTracker.registerData(
            OverseerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Integer> LIMB_COUNT = DataTracker.registerData(
            OverseerEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private PlayerEntity followTarget;
    private int retargetCooldown;
    private int reanchorCooldown;
    private BlockPos anchorBlock;
    private Direction anchorFace = Direction.UP;
    private BlockPos pendingAnchorBlock;
    private Direction pendingAnchorFace = Direction.UP;
    private Mode mode = Mode.WATCHING;

    private enum Mode {
        WATCHING,
        RETRACTING,
        EMERGING
    }

    public OverseerEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        this.experiencePoints = 0;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 12.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, ROOM_RADIUS)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.1);
    }

    @Override
    protected void initGoals() {
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(COLOR_VARIANT, ColorVariant.YELLOW.id());
        builder.add(ROOT_X, 0f);
        builder.add(ROOT_Y, 0f);
        builder.add(ROOT_Z, 0f);
        builder.add(LOOK_X, 0f);
        builder.add(LOOK_Y, 0f);
        builder.add(LOOK_Z, 0f);
        builder.add(EXTENDED, 1f);
        builder.add(LIMB_COUNT, 4);
    }

    @Override
    public void tick() {
        this.noClip = true;
        this.setNoGravity(true);
        super.tick();

        if (!this.getWorld().isClient) {
            tickServerMovement();
        }
    }

    private void tickServerMovement() {
        if (--retargetCooldown <= 0 || !isValidFollowTarget(followTarget)) {
            followTarget = findNearestPlayerInRoom();
            retargetCooldown = 20;
        }

        ensureAnchor();

        Vec3d lookAt;
        Vec3d rootPos = rootPos();
        if (followTarget != null) {
            lookAt = followTarget.getEyePos();
            maybeStartReanchor();
        } else {
            lookAt = rootPos.add(0.0, 0.2, 1.0);
        }

        setLookTarget(lookAt);
        tickMode();

        double extended = getExtended();
        Vec3d hoverPos = computeHoverPos(rootPos, lookAt);
        Vec3d retractedPos = rootPos.add(faceVector(anchorFace).multiply(0.12));
        Vec3d desiredPos = retractedPos.lerp(hoverPos, extended).subtract(bodyCenterOffset());

        Vec3d toDesired = desiredPos.subtract(this.getPos());
        Vec3d velocity = this.getVelocity()
                .multiply(0.72)
                .add(toDesired.multiply(mode == Mode.WATCHING ? 0.09 : 0.18));
        if (velocity.length() > MAX_SPEED) {
            velocity = velocity.normalize().multiply(MAX_SPEED);
        }

        this.setVelocity(velocity);
        this.move(MovementType.SELF, velocity);
        updateFacing(lookAt);
        this.velocityDirty = true;
    }

    private void ensureAnchor() {
        if (anchorBlock != null && isValidAnchor(anchorBlock, anchorFace)) {
            setRootPos(rootPos(anchorBlock, anchorFace));
            return;
        }

        AnchorCandidate candidate = findBestAnchor(this.getPos());
        if (candidate != null) {
            setAnchor(candidate.blockPos, candidate.face);
            this.setPosition(rootPos().add(faceVector(anchorFace).multiply(TETHER_REST_LENGTH * 0.7)).subtract(bodyCenterOffset()));
            return;
        }

        BlockPos fallback = this.getBlockPos().down();
        anchorBlock = fallback;
        anchorFace = Direction.UP;
        setRootPos(rootPos(fallback, Direction.UP));
    }

    private void maybeStartReanchor() {
        if (mode != Mode.WATCHING || followTarget == null || reanchorCooldown-- > 0) {
            return;
        }

        Vec3d desired = desiredPlayerHover(followTarget);
        if (this.getPos().squaredDistanceTo(desired) < 18.0 && rootPos().squaredDistanceTo(desired) < 42.0) {
            return;
        }

        AnchorCandidate candidate = findBestAnchor(desired);
        if (candidate == null || (candidate.blockPos.equals(anchorBlock) && candidate.face == anchorFace)) {
            reanchorCooldown = 30;
            return;
        }

        pendingAnchorBlock = candidate.blockPos;
        pendingAnchorFace = candidate.face;
        mode = Mode.RETRACTING;
        reanchorCooldown = REANCHOR_COOLDOWN_TICKS;
    }

    private void tickMode() {
        if (mode == Mode.RETRACTING) {
            setExtended(Math.max(0f, getExtended() - 0.16f));
            if (getExtended() <= 0.02f) {
                if (pendingAnchorBlock != null) {
                    setAnchor(pendingAnchorBlock, pendingAnchorFace);
                    this.setPosition(rootPos().add(faceVector(anchorFace).multiply(0.12)).subtract(bodyCenterOffset()));
                    pendingAnchorBlock = null;
                }
                mode = Mode.EMERGING;
            }
        } else if (mode == Mode.EMERGING) {
            setExtended(Math.min(1f, getExtended() + 0.12f));
            if (getExtended() >= 0.98f) {
                mode = Mode.WATCHING;
            }
        } else {
            setExtended(Math.min(1f, getExtended() + 0.04f));
        }
    }

    private Vec3d computeHoverPos(Vec3d rootPos, Vec3d lookAt) {
        Vec3d outward = faceVector(anchorFace);
        Vec3d lookPull = lookAt.subtract(rootPos);
        if (lookPull.lengthSquared() > 1.0E-6) {
            lookPull = lookPull.normalize().multiply(0.65);
        }
        Vec3d bob = new Vec3d(0.0, MathHelper.sin(this.age * 0.08f) * 0.22, 0.0);
        Vec3d desired = rootPos.add(outward.multiply(TETHER_REST_LENGTH)).add(lookPull).add(bob);
        Vec3d fromRoot = desired.subtract(rootPos);
        if (fromRoot.length() > MAX_TETHER_LENGTH) {
            desired = rootPos.add(fromRoot.normalize().multiply(MAX_TETHER_LENGTH));
        }
        return desired;
    }

    private void setAnchor(BlockPos blockPos, Direction face) {
        this.anchorBlock = blockPos.toImmutable();
        this.anchorFace = face;
        setRootPos(rootPos(blockPos, face));
    }

    private Vec3d rootPos() {
        return new Vec3d(
                this.dataTracker.get(ROOT_X),
                this.dataTracker.get(ROOT_Y),
                this.dataTracker.get(ROOT_Z)
        );
    }

    private void setRootPos(Vec3d pos) {
        this.dataTracker.set(ROOT_X, (float) pos.x);
        this.dataTracker.set(ROOT_Y, (float) pos.y);
        this.dataTracker.set(ROOT_Z, (float) pos.z);
    }

    public Vec3d getRootPos() {
        return rootPos();
    }

    public float getExtended() {
        return this.dataTracker.get(EXTENDED);
    }

    private void setExtended(float extended) {
        this.dataTracker.set(EXTENDED, MathHelper.clamp(extended, 0f, 1f));
    }

    private boolean isValidFollowTarget(PlayerEntity player) {
        return player != null
                && !player.isRemoved()
                && !player.isSpectator()
                && !player.isCreative()
                && player.squaredDistanceTo(this) <= ROOM_RADIUS * ROOM_RADIUS;
    }

    private PlayerEntity findNearestPlayerInRoom() {
        PlayerEntity nearest = null;
        double nearestDistance = ROOM_RADIUS * ROOM_RADIUS;
        for (PlayerEntity player : this.getWorld().getPlayers()) {
            if (player.isSpectator() || player.isCreative()) {
                continue;
            }
            double distance = player.squaredDistanceTo(this);
            if (distance <= nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private AnchorCandidate findBestAnchor(Vec3d desiredHover) {
        BlockPos center = BlockPos.ofFloored(desiredHover);
        AnchorCandidate best = null;
        double bestScore = Double.MAX_VALUE;
        int radius = 8;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos blockPos = center.add(dx, dy, dz);
                    if (!this.getWorld().getBlockState(blockPos).isSolidBlock(this.getWorld(), blockPos)) {
                        continue;
                    }
                    for (Direction face : Direction.values()) {
                        if (!isValidAnchor(blockPos, face)) {
                            continue;
                        }
                        Vec3d root = rootPos(blockPos, face);
                        Vec3d hover = root.add(faceVector(face).multiply(TETHER_REST_LENGTH));
                        double score = hover.squaredDistanceTo(desiredHover);
                        if (followTarget != null) {
                            score += Math.max(0.0, hover.distanceTo(followTarget.getPos()) - ROOM_RADIUS) * 100.0;
                        }
                        if (score < bestScore) {
                            bestScore = score;
                            best = new AnchorCandidate(blockPos.toImmutable(), face);
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean isValidAnchor(BlockPos blockPos, Direction face) {
        World world = this.getWorld();
        BlockPos openPos = blockPos.offset(face);
        return world.getBlockState(blockPos).isSolidBlock(world, blockPos)
                && !world.getBlockState(openPos).isSolidBlock(world, openPos);
    }

    private Vec3d desiredPlayerHover(PlayerEntity player) {
        Vec3d playerFacing = player.getRotationVec(1.0f).normalize();
        Vec3d side = new Vec3d(-playerFacing.z, 0.0, playerFacing.x);
        if (side.lengthSquared() < 1.0E-6) {
            side = new Vec3d(1.0, 0.0, 0.0);
        }
        double sideSign = (this.getUuid().getLeastSignificantBits() & 1L) == 0L ? 1.0 : -1.0;
        return player.getPos()
                .subtract(playerFacing.multiply(DESIRED_FOLLOW_DISTANCE))
                .add(side.normalize().multiply(sideSign * 1.4))
                .add(0.0, 2.35, 0.0);
    }

    private static Vec3d rootPos(BlockPos blockPos, Direction face) {
        return Vec3d.ofCenter(blockPos).add(faceVector(face).multiply(0.54));
    }

    private static Vec3d faceVector(Direction face) {
        return new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
    }

    private Vec3d bodyCenterOffset() {
        return new Vec3d(0.0, this.getHeight() * 0.52, 0.0);
    }

    private void updateFacing(Vec3d lookAt) {
        Vec3d direction = lookAt.subtract(this.getPos());
        if (direction.lengthSquared() < 1.0E-6) {
            return;
        }
        float yaw = (float) (MathHelper.atan2(direction.z, direction.x) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z)) * 180.0 / Math.PI));
        this.setYaw(yaw);
        this.setPitch(pitch);
        this.bodyYaw = yaw;
        this.headYaw = yaw;
    }

    public ColorVariant getColorVariant() {
        return ColorVariant.byId(this.dataTracker.get(COLOR_VARIANT));
    }

    public void setColorVariant(ColorVariant variant) {
        this.dataTracker.set(COLOR_VARIANT, variant.id());
    }

    public int getLimbCount() {
        return MathHelper.clamp(this.dataTracker.get(LIMB_COUNT), 2, 5);
    }

    protected void setLimbCount(int limbCount) {
        this.dataTracker.set(LIMB_COUNT, MathHelper.clamp(limbCount, 2, 5));
    }

    protected int stableLimbCount(int minInclusive, int maxInclusive) {
        int min = Math.min(minInclusive, maxInclusive);
        int max = Math.max(minInclusive, maxInclusive);
        int span = max - min + 1;
        long seed = this.getUuid().getLeastSignificantBits() ^ (this.getUuid().getMostSignificantBits() * 31L);
        return min + Math.floorMod((int) seed, span);
    }

    public Vec3d getLookTarget() {
        return new Vec3d(
                this.dataTracker.get(LOOK_X),
                this.dataTracker.get(LOOK_Y),
                this.dataTracker.get(LOOK_Z)
        );
    }

    private void setLookTarget(Vec3d lookTarget) {
        this.dataTracker.set(LOOK_X, (float) lookTarget.x);
        this.dataTracker.set(LOOK_Y, (float) lookTarget.y);
        this.dataTracker.set(LOOK_Z, (float) lookTarget.z);
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
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("ColorVariant", this.dataTracker.get(COLOR_VARIANT));
        nbt.putInt("LimbCount", this.dataTracker.get(LIMB_COUNT));
        if (anchorBlock != null) {
            nbt.putInt("AnchorX", anchorBlock.getX());
            nbt.putInt("AnchorY", anchorBlock.getY());
            nbt.putInt("AnchorZ", anchorBlock.getZ());
            nbt.putString("AnchorFace", anchorFace.asString());
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("ColorVariant")) {
            this.dataTracker.set(COLOR_VARIANT, nbt.getInt("ColorVariant"));
        }
        if (nbt.contains("LimbCount")) {
            setLimbCount(nbt.getInt("LimbCount"));
        }
        if (nbt.contains("AnchorX") && nbt.contains("AnchorY") && nbt.contains("AnchorZ")) {
            Direction face = Direction.byName(nbt.getString("AnchorFace"));
            setAnchor(new BlockPos(nbt.getInt("AnchorX"), nbt.getInt("AnchorY"), nbt.getInt("AnchorZ")),
                    face == null ? Direction.UP : face);
        }
    }

    private record AnchorCandidate(BlockPos blockPos, Direction face) {
    }
}
