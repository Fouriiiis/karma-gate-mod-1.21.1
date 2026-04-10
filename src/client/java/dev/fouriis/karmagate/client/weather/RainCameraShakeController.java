package dev.fouriis.karmagate.client.weather;

import net.minecraft.util.math.MathHelper;

/**
 * Samples two-layer camera shake from synced GlobalRain channels.
 *
 * Tuned for:
 * - much less slow rocking
 * - much faster shake
 * - much stronger shake
 */
public final class RainCameraShakeController {
    public static final RainCameraShakeController INSTANCE = new RainCameraShakeController();

    public record Sample(float localX, float localY, float localZ, float yaw, float pitch) {
        public static final Sample ZERO = new Sample(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    private long ageTicks;
    private float microShake;
    private float screenShake;

    private RainCameraShakeController() {
    }

    public void tick() {
        ageTicks++;

        float targetMicro = 0.0f;
        float targetScreen = 0.0f;

        if (GlobalRainClientState.hasSync()) {
            targetMicro = GlobalRainClientState.microScreenShake();
            targetScreen = GlobalRainClientState.screenShake();
        }

        // Faster attack so spikes hit immediately.
        // Slight release smoothing so plateau phases still linger.
        microShake = smoothToward(microShake, targetMicro, targetMicro > microShake ? 0.62f : 0.24f);
        screenShake = smoothToward(screenShake, targetScreen, targetScreen > screenShake ? 0.48f : 0.20f);

        if (microShake < 0.0001f) {
            microShake = 0.0f;
        }
        if (screenShake < 0.0001f) {
            screenShake = 0.0f;
        }
    }

    public Sample sample(float tickDelta) {
        if (microShake <= 0.0f && screenShake <= 0.0f) {
            return Sample.ZERO;
        }

        float t = (ageTicks + tickDelta) / 20.0f;

        // Stronger nonlinear gain.
        // This makes higher rain phases hit much harder than before.
        float microVis = microShake * (1.8f + 0.40f * microShake);
        float screenVis = screenShake * (1.9f + 0.45f * screenShake);

        // --- FAST MICRO JITTER (frequencies roughly doubled) ---
        float microX =
                (MathHelper.sin(t * 78.0f) * 0.0068f
              +  MathHelper.sin(t * 122.0f + 1.7f) * 0.0046f
              +  MathHelper.cos(t * 94.0f + 0.2f) * 0.0038f) * microVis;

        float microY =
                (MathHelper.cos(t * 86.0f + 0.5f) * 0.0072f
              +  MathHelper.sin(t * 134.0f + 2.4f) * 0.0048f
              +  MathHelper.sin(t * 102.0f + 0.9f) * 0.0036f) * microVis;

        float microZ =
                (MathHelper.sin(t * 90.0f + 1.2f) * 0.0022f
              +  MathHelper.cos(t * 118.0f + 2.1f) * 0.0016f) * microVis;

        // --- REDUCED ROCKING ---
        // Keep some slower body sway, but much less than before.
        float macroX = MathHelper.sin(t * 10.2f) * 0.0075f * screenVis;
        float macroY = MathHelper.cos(t * 8.6f) * 0.0065f * screenVis;
        float macroZ = MathHelper.sin(t * 9.4f + 1.2f) * 0.0020f * screenVis;

        // --- FAST ANGULAR CHATTER (also doubled) ---
        float microYaw =
                (MathHelper.sin(t * 58.0f) * 0.14f
              +  MathHelper.cos(t * 74.0f + 0.3f) * 0.08f) * microVis;

        float microPitch =
                (MathHelper.cos(t * 62.0f + 0.3f) * 0.12f
              +  MathHelper.sin(t * 70.0f + 1.1f) * 0.07f) * microVis;

        // Keep screen shake angular movement, but reduce the slow “boat rocking”
        // feeling by lowering amplitude and increasing speed.
        float macroYaw = MathHelper.sin(t * 6.2f) * 0.24f * screenVis;
        float macroPitch = MathHelper.cos(t * 7.4f) * 0.18f * screenVis;

        return new Sample(
                microX + macroX,
                microY + macroY,
                microZ + macroZ,
                microYaw + macroYaw,
                microPitch + macroPitch
        );
    }

    public void reset() {
        ageTicks = 0L;
        microShake = 0.0f;
        screenShake = 0.0f;
    }

    private static float smoothToward(float current, float target, float factor) {
        return MathHelper.lerp(MathHelper.clamp(factor, 0.0f, 1.0f), current, target);
    }
}