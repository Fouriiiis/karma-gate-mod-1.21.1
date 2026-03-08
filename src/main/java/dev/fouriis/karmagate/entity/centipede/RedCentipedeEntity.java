package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * Large fixed-size red centipede variant.
 */
public class RedCentipedeEntity extends CentipedeEntity {
    private static final float SIZE = 1.0f;
    private static final int TOTAL_SEGMENTS = 18;
    private static final float MAX_RADIUS = 8.0f;
    // RedCentipedeEntity.java
private static final int SHELL_COLOR = (200 << 16) | (40 << 8) | 24;
private static final int SECONDARY_SHELL_COLOR = (120 << 16) | (18 << 8) | 10;

    public RedCentipedeEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        configureFixedVariant(SIZE, TOTAL_SEGMENTS, MAX_RADIUS);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 80.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.35)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 6.0)
                .add(EntityAttributes.GENERIC_ARMOR, 8.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.8);
    }

    @Override
    protected void computeSizeFromSeed() {
        // Fixed-size variant.
    }

    public static float computeRedSegmentRadius(int segIndex) {
        float segRatio = (float) segIndex / (float) (TOTAL_SEGMENTS - 1);
        float sinVal = (float) Math.max(0, Math.sin(Math.PI * segRatio));
        float powVal = (float) Math.pow(sinVal, 0.3f);
        return MathHelper.lerp(powVal, 3.5f, 6.5f) + 1.5f;
    }

    @Override
    public float computeSegmentRadius(int segIndex) {
        return computeRedSegmentRadius(segIndex);
    }

    @Override
    public float getMaxRadius() {
        return MAX_RADIUS;
    }

    @Override
    public boolean isFireImmune() {
        return false;
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
