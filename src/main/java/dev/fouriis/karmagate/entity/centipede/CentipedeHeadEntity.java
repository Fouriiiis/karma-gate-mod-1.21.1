package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A centipede head segment. The centipede has two heads (front and rear).
 * Each head can grab a target entity. When both heads grab the same target,
 * the centipede charges up and delivers an instakill shock.
 *
 * In Rain World, the head chunks (index 0 and N-1) are the grab points.
 * This entity represents one such head.
 */
public class CentipedeHeadEntity extends CentipedeSegmentEntity {

    // Tracked: entity ID of the creature this head is grabbing (-1 = none)
    private static final TrackedData<Integer> GRABBED_ENTITY_ID = DataTracker.registerData(
            CentipedeHeadEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Tracked: whether this is the front head (true) or rear head (false)
    private static final TrackedData<Boolean> IS_FRONT_HEAD = DataTracker.registerData(
            CentipedeHeadEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // Server-side grab state
    private LivingEntity grabbedEntity = null;
    private int grabCooldown = 0;

    public CentipedeHeadEntity(EntityType<? extends MobEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(GRABBED_ENTITY_ID, -1);
        builder.add(IS_FRONT_HEAD, true);
    }

    // --- Front/rear ---

    public void setFrontHead(boolean front) {
        this.dataTracker.set(IS_FRONT_HEAD, front);
    }

    public boolean isFrontHead() {
        return this.dataTracker.get(IS_FRONT_HEAD);
    }

    // --- Grab management ---

    public void setGrabbedEntityId(int id) {
        this.dataTracker.set(GRABBED_ENTITY_ID, id);
    }

    public int getGrabbedEntityId() {
        return this.dataTracker.get(GRABBED_ENTITY_ID);
    }

    public LivingEntity getGrabbedEntity() {
        return grabbedEntity;
    }

    /**
     * Attempt to grab a target entity. Called by the parent controller
     * when this head collides with valid prey.
     */
    public boolean tryGrab(LivingEntity target) {
        if (grabbedEntity != null || grabCooldown > 0) return false;
        if (target == null || target.isRemoved() || target.isDead()) return false;

        grabbedEntity = target;
        setGrabbedEntityId(target.getId());
        return true;
    }

    /**
     * Release the currently grabbed entity.
     */
    public void releaseGrab() {
        grabbedEntity = null;
        setGrabbedEntityId(-1);
        grabCooldown = 30; // 1.5 seconds cooldown before grabbing again
    }

    public boolean isGrabbing() {
        return grabbedEntity != null && !grabbedEntity.isRemoved() && !grabbedEntity.isDead();
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.getWorld().isClient) {
            if (grabCooldown > 0) grabCooldown--;

            // Validate grab
            if (grabbedEntity != null) {
                if (grabbedEntity.isRemoved() || grabbedEntity.isDead()) {
                    releaseGrab();
                } else {
                    double dist = this.getPos().distanceTo(grabbedEntity.getPos());
                    // Release distance matches ~body length so the grab holds
                    // while the other head wraps around
                    if (dist > 12.0) {
                        // Too far — release
                        releaseGrab();
                    } else {
                        // C# CentipedeAI.UpdateGrasp: pull grabbed entity toward head,
                        // dampen its velocity to resist its own movement

                        // Dampen grabbed entity's velocity (resist its movement)
                        Vec3d targetVel = grabbedEntity.getVelocity();
                        grabbedEntity.setVelocity(targetVel.multiply(0.3));

                        if (dist > 0.3) {
                            // Strong pull toward head position
                            Vec3d pullDir = this.getPos().subtract(grabbedEntity.getPos()).normalize();
                            double pullStrength = 0.4;
                            // Scale pull up when farther away
                            if (dist > 2.0) pullStrength = 0.55;
                            if (dist > 4.0) pullStrength = 0.7;
                            grabbedEntity.setVelocity(grabbedEntity.getVelocity().add(pullDir.multiply(pullStrength)));
                        } else {
                            // Very close: lock position to head
                            grabbedEntity.setPosition(this.getPos());
                        }

                        // Head pulls toward grabbed entity (anchors head at prey)
                        if (dist > 0.5) {
                            Vec3d headPull = grabbedEntity.getPos().subtract(this.getPos()).normalize();
                            this.segmentVelocity = this.segmentVelocity.add(headPull.multiply(0.08));
                        }

                        // Velocity limit on grabbed entity
                        grabbedEntity.velocityModified = true;
                    }
                }
            }
        }
    }

    // --- NBT ---

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("IsFrontHead", isFrontHead());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("IsFrontHead")) setFrontHead(nbt.getBoolean("IsFrontHead"));
    }
}
