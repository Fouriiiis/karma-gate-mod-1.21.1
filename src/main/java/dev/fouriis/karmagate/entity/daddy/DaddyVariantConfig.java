package dev.fouriis.karmagate.entity.daddy;

public interface DaddyVariantConfig {
    float bodyRadius();

    int tentacleCount();

    float tentacleLength();

    int tentacleSegments();

    float movementForce();

    float pullStrength();

    float anchorSearchRadius();

    float anchorSearchForwardBias();

    int targetIntervalTicks();

    int horizontalTargetRadius();

    int verticalTargetRadius();

    int targetSearchAttempts();

    int stuckProgressWindowTicks();

    int stuckRecoveryTicks();

    boolean hearingEnabled();

    int supportTentacleCount();
}
