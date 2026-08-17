package dev.fouriis.karmagate.entity.poleplant;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * One authoritative half-block collision sample along a pole plant's stem.
 * It has no independent health or movement; attacks are forwarded to the
 * owning plant.
 */
public final class PolePlantSegmentEntity extends MobEntity {
    private static final TrackedData<Integer> PARENT_ID = DataTracker.registerData(
            PolePlantSegmentEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> SEGMENT_INDEX = DataTracker.registerData(
            PolePlantSegmentEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private int orphanTicks;

    public PolePlantSegmentEntity(EntityType<? extends PolePlantSegmentEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
        setAiDisabled(true);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 1.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 0.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(PARENT_ID, -1);
        builder.add(SEGMENT_INDEX, 0);
    }

    public void setParent(PolePlantEntity parent, int index) {
        dataTracker.set(PARENT_ID, parent.getId());
        dataTracker.set(SEGMENT_INDEX, index);
    }

    public int getParentId() {
        return dataTracker.get(PARENT_ID);
    }

    public int getSegmentIndex() {
        return dataTracker.get(SEGMENT_INDEX);
    }

    public PolePlantEntity getParentPlant() {
        Entity entity = getWorld().getEntityById(getParentId());
        return entity instanceof PolePlantEntity plant ? plant : null;
    }

    @Override
    public void tick() {
        noClip = true;
        setNoGravity(true);
        setVelocity(Vec3d.ZERO);
        super.tick();

        PolePlantEntity parent = getParentPlant();
        if (parent == null || parent.isRemoved()) {
            if (!getWorld().isClient && ++orphanTicks > 20) discard();
            return;
        }
        orphanTicks = 0;
        if (getWorld().isClient) parent.registerClientHitbox(this);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        PolePlantEntity parent = getParentPlant();
        if (parent != null && !parent.isRemoved()) {
            return parent.damage(source, amount);
        }
        return false;
    }

    @Override
    public void travel(Vec3d movementInput) {
        // The parent writes the authoritative center every server tick.
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushAway(Entity entity) {
    }

    @Override
    public boolean isInsideWall() {
        return false;
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("PolePlantParentId", getParentId());
        nbt.putInt("PolePlantSegmentIndex", getSegmentIndex());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        dataTracker.set(PARENT_ID, nbt.getInt("PolePlantParentId"));
        dataTracker.set(SEGMENT_INDEX, nbt.getInt("PolePlantSegmentIndex"));
    }
}
