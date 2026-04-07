package dev.fouriis.karmagate.client.weather;

public final class GlobalRainClientState {

    private static volatile float bulletRainDensity;
    private static volatile boolean hasSync;

    private GlobalRainClientState() {
    }

    public static void applySync(float syncedBulletRainDensity) {
        bulletRainDensity = clamp01(syncedBulletRainDensity);
        hasSync = true;
    }

    public static float bulletRainDensity() {
        return bulletRainDensity;
    }

    public static boolean hasSync() {
        return hasSync;
    }

    public static void clear() {
        bulletRainDensity = 0.0f;
        hasSync = false;
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }
}