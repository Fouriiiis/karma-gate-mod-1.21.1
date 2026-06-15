package dev.fouriis.karmagate.entity.lizard;

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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class LizardPartEntity extends MobEntity {
    public enum Kind {
        HEAD,
        BODY,
        LEG_UPPER,
        LEG_LOWER,
        TAIL
    }

    private static final TrackedData<Integer> PARENT_ID = DataTracker.registerData(
            LizardPartEntity.class, TrackedDataHandlerRegistry.INTEGER
    );
    private static final TrackedData<Integer> PART_KIND = DataTracker.registerData(
            LizardPartEntity.class, TrackedDataHandlerRegistry.INTEGER
    );
    private static final TrackedData<Integer> PART_INDEX = DataTracker.registerData(
            LizardPartEntity.class, TrackedDataHandlerRegistry.INTEGER
    );
    private static final TrackedData<Float> PART_RADIUS = DataTracker.registerData(
            LizardPartEntity.class, TrackedDataHandlerRegistry.FLOAT
    );

    public LizardPartEntity(EntityType<? extends MobEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        this.setAiDisabled(true);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 4.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 0.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(PARENT_ID, -1);
        builder.add(PART_KIND, Kind.BODY.ordinal());
        builder.add(PART_INDEX, 0);
        builder.add(PART_RADIUS, 0.2f);
    }

    public void configure(AbstractLizardEntity parent, Kind kind, int index, float radius, Vec3d pos) {
        this.dataTracker.set(PARENT_ID, parent.getId());
        this.dataTracker.set(PART_KIND, kind.ordinal());
        this.dataTracker.set(PART_INDEX, index);
        this.dataTracker.set(PART_RADIUS, radius);
        this.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        updateCustomBounds();
    }

    public String partKey() {
        return kind().name() + ":" + this.dataTracker.get(PART_INDEX);
    }

    public Kind kind() {
        int ordinal = this.dataTracker.get(PART_KIND);
        if (ordinal < 0 || ordinal >= Kind.values().length) {
            return Kind.BODY;
        }
        return Kind.values()[ordinal];
    }

    public int getParentId() {
        return this.dataTracker.get(PARENT_ID);
    }

    public float getPartRadius() {
        return this.dataTracker.get(PART_RADIUS);
    }

    public AbstractLizardEntity getParentLizard() {
        Entity entity = this.getWorld().getEntityById(getParentId());
        if (entity instanceof AbstractLizardEntity lizard) {
            return lizard;
        }
        return null;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        AbstractLizardEntity parent = getParentLizard();
        if (parent != null && parent.isAlive()) {
            return parent.damage(source, amount);
        }
        return super.damage(source, amount);
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public void tick() {
        this.noClip = true;
        this.setNoGravity(true);
        this.setVelocity(Vec3d.ZERO);
        super.tick();
        updateCustomBounds();

        if (!this.getWorld().isClient) {
            AbstractLizardEntity parent = getParentLizard();
            if (parent == null || !parent.isAlive()) {
                this.discard();
            }
        }
    }

    @Override
    public void travel(Vec3d movementInput) {
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return !isLegPart();
    }

    @Override
    public boolean collidesWith(Entity other) {
        return !isLegPart();
    }

    @Override
    public void pushAwayFrom(Entity entity) {
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
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("ParentId", getParentId());
        nbt.putInt("Kind", kind().ordinal());
        nbt.putInt("PartIndex", this.dataTracker.get(PART_INDEX));
        nbt.putFloat("PartRadius", getPartRadius());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.dataTracker.set(PARENT_ID, nbt.getInt("ParentId"));
        this.dataTracker.set(PART_KIND, nbt.getInt("Kind"));
        this.dataTracker.set(PART_INDEX, nbt.getInt("PartIndex"));
        this.dataTracker.set(PART_RADIUS, nbt.getFloat("PartRadius"));
        updateCustomBounds();
    }

    private void updateCustomBounds() {
        double diameter = Math.max(0.12, getPartRadius() * 2.0);
        this.setBoundingBox(Box.of(this.getPos(), diameter, diameter, diameter));
    }

    private boolean isLegPart() {
        Kind kind = kind();
        return kind == Kind.LEG_UPPER || kind == Kind.LEG_LOWER;
    }
}
