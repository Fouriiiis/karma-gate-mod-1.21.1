package dev.fouriis.karmagate.entity.echo;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A Rain World echo. Its GeckoLib bones animate, but its world-space anchor is
 * immutable after the entity's first tick.
 */
public final class EchoEntity extends MobEntity implements GeoAnimatable {
    /** Center of the supplied model's visible bounds relative to its spawn anchor. */
    public static final double VISUAL_CENTER_Y = 6.0;

    private static final RawAnimation IDLE =
            RawAnimation.begin().thenLoop("animation.model.idle");

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    private boolean anchorSet;
    private double anchorX;
    private double anchorY;
    private double anchorZ;

    public EchoEntity(EntityType<? extends EchoEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
        noClip = true;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void initGoals() {
        // Echoes are environmental presences, not navigating mobs.
    }

    @Override
    public void tick() {
        if (!anchorSet) {
            anchorX = getX();
            anchorY = getY();
            anchorZ = getZ();
            anchorSet = true;
        }

        super.tick();
        noClip = true;
        setNoGravity(true);
        setVelocity(Vec3d.ZERO);
        setPosition(anchorX, anchorY, anchorZ);
        velocityDirty = true;
    }

    public Vec3d getVisualCenter() {
        return new Vec3d(getX(), getY() + VISUAL_CENTER_Y, getZ());
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void pushAwayFrom(Entity entity) {
        // Fixed environmental entity: never displace another entity.
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("EchoAnchorSet", anchorSet);
        if (anchorSet) {
            nbt.putDouble("EchoAnchorX", anchorX);
            nbt.putDouble("EchoAnchorY", anchorY);
            nbt.putDouble("EchoAnchorZ", anchorZ);
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        anchorSet = nbt.getBoolean("EchoAnchorSet");
        if (anchorSet) {
            anchorX = nbt.getDouble("EchoAnchorX");
            anchorY = nbt.getDouble("EchoAnchorY");
            anchorZ = nbt.getDouble("EchoAnchorZ");
            setPosition(anchorX, anchorY, anchorZ);
        }
    }

    @Override
    public double getTick(Object object) {
        return age;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "echo_idle", 0, state -> {
            state.getController().setAnimation(IDLE);
            return PlayState.CONTINUE;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animationCache;
    }
}
