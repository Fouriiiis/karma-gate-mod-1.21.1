package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.world.World;

/**
 * Small centiwing coloring on the small ground-centipede body plan.
 */
public class SmallCentiwingEntity extends CentipedeEntity {
    private static final float SIZE = 0f;
    private static final int TOTAL_SEGMENTS = 5;
    private static final float MAX_RADIUS = 8.0f;
    private static final int SHELL_COLOR = (106 << 16) | (191 << 8) | 64;
    private static final int SECONDARY_SHELL_COLOR = (64 << 16) | (115 << 8) | 38;

    public SmallCentiwingEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        configureFixedVariant(SIZE, TOTAL_SEGMENTS, MAX_RADIUS);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 8.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
                .add(EntityAttributes.GENERIC_ARMOR, 0.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.0);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new net.minecraft.entity.ai.goal.SwimGoal(this));
        this.goalSelector.add(1, new CentipedeShockGoal<>(this));
        this.goalSelector.add(2, new CentipedeHuntGoal<>(this));
        this.goalSelector.add(3, new CentipedeWanderGoal<>(this));
        this.goalSelector.add(4, new net.minecraft.entity.ai.goal.LookAroundGoal(this));
        this.targetSelector.add(1, new ActiveTargetGoal<>(this, ChickenEntity.class, 10, true, false,
                entity -> !entity.isRemoved() && entity.isAlive()));
    }

    @Override
    protected void computeSizeFromSeed() {
        // Fixed-size variant.
    }

    @Override
    public float computeSegmentRadius(int segIndex) {
        float segRatio = (float) segIndex / (float) (TOTAL_SEGMENTS - 1);
        float sinVal = (float) Math.max(0, Math.sin(Math.PI * segRatio));
        float powVal = (float) Math.pow(sinVal, 0.7f);
        return net.minecraft.util.math.MathHelper.lerp(powVal, 2f, 4f);
    }

    @Override
    public float getMaxRadius() {
        return MAX_RADIUS;
    }

    @Override
    public float getHeadScaleFactor() {
        return 0.5f;
    }

    @Override
    public boolean isFireImmune() {
        return false;
    }

    @Override
    protected boolean isValidPrey(LivingEntity entity) {
        return entity instanceof ChickenEntity chicken
                && !chicken.isRemoved()
                && chicken.isAlive();
    }

    @Override
    protected void spawnSegments() {
        super.spawnSegments();
        if (segments != null) {
            for (CentipedeSegmentEntity seg : segments) {
                if (seg != null) {
                    seg.setHasShell(false);
                }
            }
        }
    }

    @Override
    public int getShellColorRGB() {
        return SHELL_COLOR;
    }

    @Override
    public int getSecondaryShellColorRGB() {
        return SECONDARY_SHELL_COLOR;
    }
}
