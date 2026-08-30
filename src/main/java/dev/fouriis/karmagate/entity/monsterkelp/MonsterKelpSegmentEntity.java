package dev.fouriis.karmagate.entity.monsterkelp;

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

/** Invisible authoritative half-block hitbox along a Monster Kelp stem. */
public final class MonsterKelpSegmentEntity extends MobEntity {
    private static final TrackedData<Integer> PARENT_ID = DataTracker.registerData(
            MonsterKelpSegmentEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> SEGMENT_INDEX = DataTracker.registerData(
            MonsterKelpSegmentEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private int orphanTicks;

    public MonsterKelpSegmentEntity(EntityType<? extends MonsterKelpSegmentEntity> type, World world) {
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

    public void setParent(MonsterKelpEntity parent, int index) {
        dataTracker.set(PARENT_ID, parent.getId());
        dataTracker.set(SEGMENT_INDEX, index);
    }

    private MonsterKelpEntity getParentPlant() {
        Entity entity = getWorld().getEntityById(dataTracker.get(PARENT_ID));
        return entity instanceof MonsterKelpEntity kelp ? kelp : null;
    }

    @Override
    public void tick() {
        noClip = true;
        setNoGravity(true);
        setVelocity(Vec3d.ZERO);
        super.tick();
        MonsterKelpEntity parent = getParentPlant();
        if (parent == null || parent.isRemoved()) {
            if (!getWorld().isClient && ++orphanTicks > 20) discard();
        } else {
            orphanTicks = 0;
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        MonsterKelpEntity parent = getParentPlant();
        return parent != null && !parent.isRemoved() && parent.damage(source, amount);
    }

    @Override
    public void travel(Vec3d movementInput) {
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
        nbt.putInt("MonsterKelpParentId", dataTracker.get(PARENT_ID));
        nbt.putInt("MonsterKelpSegmentIndex", dataTracker.get(SEGMENT_INDEX));
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        dataTracker.set(PARENT_ID, nbt.getInt("MonsterKelpParentId"));
        dataTracker.set(SEGMENT_INDEX, nbt.getInt("MonsterKelpSegmentIndex"));
    }
}
