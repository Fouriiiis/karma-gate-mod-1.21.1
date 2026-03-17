package dev.fouriis.karmagate.entity.daddy;

public final class StandardDaddyVariantConfig implements DaddyVariantConfig {
    public static final StandardDaddyVariantConfig INSTANCE = new StandardDaddyVariantConfig();

    private StandardDaddyVariantConfig() {
    }

    @Override
    public float bodyRadius() {
        return 0.75f;
    }

    @Override
    public int tentacleCount() {
        return 10;
    }

    @Override
    public float tentacleLength() {
        return 8.5f;
    }

    @Override
    public int tentacleSegments() {
        return 14;
    }

    @Override
    public float movementForce() {
        return 0.018f;
    }

    @Override
    public float pullStrength() {
        return 0.07f;
    }

    @Override
    public float anchorSearchRadius() {
        return 8.0f;
    }

    @Override
    public float anchorSearchForwardBias() {
        return 2.5f;
    }

    @Override
    public int targetIntervalTicks() {
        return 20 * 20;
    }

    @Override
    public int horizontalTargetRadius() {
        return 20;
    }

    @Override
    public int verticalTargetRadius() {
        return 10;
    }

    @Override
    public int targetSearchAttempts() {
        return 40;
    }

    @Override
    public int stuckProgressWindowTicks() {
        return 80;
    }

    @Override
    public int stuckRecoveryTicks() {
        return 120;
    }

    @Override
    public boolean hearingEnabled() {
        return true;
    }

    @Override
    public int supportTentacleCount() {
        return 4;
    }
}
