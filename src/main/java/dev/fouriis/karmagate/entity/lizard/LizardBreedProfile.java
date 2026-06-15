package dev.fouriis.karmagate.entity.lizard;

/**
 * Compact runtime tuning for lizard variants. Future breeds can reuse the same
 * controller by swapping profile values.
 */
public record LizardBreedProfile(
        String id,
        int bodySegments,
        int tailSegments,
        float bodySpacing,
        float bodyLift,
        float bodyRadiusStart,
        float bodyRadiusEnd,
        float headRadius,
        float headReach,
        float headLift,
        float tailSpacing,
        float tailRadiusStart,
        float tailRadiusEnd,
        float tailSway,
        float frontShoulderOffset,
        float hindHipOffset,
        float stanceWidth,
        float stanceHeight,
        float upperLimbLength,
        float lowerLimbLength,
        float limbRadius,
        float stepLength,
        float liftFeet,
        float feetDown,
        float noGripSpeed,
        float limbSpeed,
        float limbQuickness,
        int limbGripDelay,
        boolean smoothenLegMovement,
        float legPairDisplacement,
        float walkBob,
        float movementSpeed,
        float turnRateDegrees,
        float bodyStiffness,
        float neckStiffness,
        int bodyColorRgb,
        int limbColorRgb,
        float maxHealth
) {
    public static LizardBreedProfile green() {
        return new LizardBreedProfile(
                "green",
                4,
                7,
                0.72f,
                0.46f,
                0.40f,
                0.24f,
                0.34f,
                0.62f,
                0.06f,
                0.34f,
                0.22f,
                0.10f,
                0.13f,
                0.16f,
                0.20f,
                0.55f,
                0.62f,
                0.78f,
                0.74f,
                0.13f,
                0.90f,
                0.50f,
                1.0f,
                0.05f,
                0.72f,
                0.30f,
                1,
                false,
                1.0f,
                4.0f,
                0.090f,
                5.0f,
                0.48f,
                1.0f,
                0x48A43C,
                0x2C5220,
                28.0f
        );
    }

    public float bodyRadius(int index) {
        if (bodySegments <= 1) {
            return bodyRadiusStart;
        }
        float t = index / (float) (bodySegments - 1);
        return lerp(bodyRadiusStart, bodyRadiusEnd, t);
    }

    public float tailRadius(int index) {
        if (tailSegments <= 1) {
            return tailRadiusStart;
        }
        float t = index / (float) (tailSegments - 1);
        return lerp(tailRadiusStart, tailRadiusEnd, t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
