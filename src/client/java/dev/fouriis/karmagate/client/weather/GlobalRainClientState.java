package dev.fouriis.karmagate.client.weather;

public final class GlobalRainClientState {

    private static volatile float intensity;
    private static volatile float rainDirection;
    private static volatile float bulletRainDensity;
    private static volatile float rumbleSound;
    private static volatile boolean hasSync;

    private GlobalRainClientState() {
    }

    public static void applySync(float syncedIntensity,
                                 float syncedRainDirection,
                                 float syncedBulletRainDensity,
                                 float syncedRumbleSound) {
        intensity = clamp01(syncedIntensity);
        rainDirection = clampSignedUnit(syncedRainDirection);
        bulletRainDensity = clamp01(syncedBulletRainDensity);
        rumbleSound = clamp01(syncedRumbleSound);
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

    public static boolean hasSync() {
        return hasSync;
    }

    public static void clear() {
        intensity = 0.0f;
        rainDirection = 0.0f;
        bulletRainDensity = 0.0f;
        rumbleSound = 0.0f;
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
}