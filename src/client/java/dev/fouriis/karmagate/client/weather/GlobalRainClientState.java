package dev.fouriis.karmagate.client.weather;

public final class GlobalRainClientState {

    private static volatile float intensity;
    private static volatile float rainDirection;
    private static volatile float bulletRainDensity;
    private static volatile float rumbleSound;
    private static volatile float screenShake;
    private static volatile float microScreenShake;
    private static volatile boolean hasSync;

    private GlobalRainClientState() {
    }

    public static void applySync(float syncedIntensity,
                                 float syncedRainDirection,
                                 float syncedBulletRainDensity,
                                 float syncedRumbleSound,
                                 float syncedScreenShake,
                                 float syncedMicroScreenShake) {
        intensity = clamp01(syncedIntensity);
        rainDirection = clampSignedUnit(syncedRainDirection);
        bulletRainDensity = clamp01(syncedBulletRainDensity);
        rumbleSound = clamp01(syncedRumbleSound);
        screenShake = clampNonNegative(syncedScreenShake, 8.0f);
        microScreenShake = clampNonNegative(syncedMicroScreenShake, 8.0f);
        hasSync = true;
    }

    public static float intensity() {
        return intensity;
    }

    public static float rainDirection() {
        return rainDirection;
    }

    public static float bulletRainDensity() {
        return bulletRainDensity;
    }

    public static float rumbleSound() {
        return rumbleSound;
    }

    public static float screenShake() {
        return screenShake;
    }

    public static float microScreenShake() {
        return microScreenShake;
    }

    public static boolean hasSync() {
        return hasSync;
    }

    public static void clear() {
        intensity = 0.0f;
        rainDirection = 0.0f;
        bulletRainDensity = 0.0f;
        rumbleSound = 0.0f;
        screenShake = 0.0f;
        microScreenShake = 0.0f;
        hasSync = false;
    }

    private static float clampSignedUnit(float v) {
        if (v < -1.0f) return -1.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }

    private static float clampNonNegative(float v, float max) {
        if (v < 0.0f) return 0.0f;
        if (v > max) return max;
        return v;
    }
}
