package pencil.mechanics.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/** A Rain World echo: its model animates, but its world position never drifts. */
public final class EchoEntity extends PathAwareEntity implements GeoEntity {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.model.idle");
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    private boolean anchorSet;
    private double anchorX;
    private double anchorY;
    private double anchorZ;

    public EchoEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0);
    }

    @Override
    protected void initGoals() {
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
        setVelocity(Vec3d.ZERO);
        setPosition(anchorX, anchorY, anchorZ);
        velocityDirty = true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushAway(net.minecraft.entity.Entity entity) {
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("EchoAnchorSet", anchorSet);
        if (anchorSet) {
            nbt.putDouble("EchoAnchorX", anchorX);
            nbt.putDouble("EchoAnchorY", anchorY);
            nbt.putDouble("EchoAnchorZ", anchorZ);
        }
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        anchorSet = nbt.getBoolean("EchoAnchorSet");
        if (anchorSet) {
            anchorX = nbt.getDouble("EchoAnchorX");
            anchorY = nbt.getDouble("EchoAnchorY");
            anchorZ = nbt.getDouble("EchoAnchorZ");
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "echo_idle", 0, state -> state.setAndContinue(IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animationCache;
    }
}
