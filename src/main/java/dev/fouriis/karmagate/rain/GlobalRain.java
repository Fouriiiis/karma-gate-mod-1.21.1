package dev.fouriis.karmagate.rain;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.network.ModNetworking;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class GlobalRain {
    private static final Map<MinecraftServer, GlobalRain> INSTANCES = new WeakHashMap<>();

    /**
     * Rain World runs at 40 TPS. Minecraft runs at 20 TPS.
     * Convert time-based behavior so it matches in real seconds.
     */
    private static final float SOURCE_TPS = 40.0f;
    private static final float TARGET_TPS = 20.0f;
    private static final float TICK_RATIO = SOURCE_TPS / TARGET_TPS; // 2.0

    public enum DeathRainMode {
        NONE,
        CALM_BEFORE_STORM,
        GRADE_A_BUILDUP,
        GRADE_A_PLATEU,
        GRADE_B_BUILDUP,
        GRADE_B_PLATEU,
        FINAL_BUILDUP,
        MAYHEM,
        ALTERNATE_BUILDUP,
        PULSES
    }

    private final class DeathRain {
        private DeathRainMode deathRainMode = DeathRainMode.NONE;
        private float timeInThisMode;
        private float progression;
        private float calmBeforeStormSunlight;

        private DeathRain() {
            nextDeathRainMode();
        }

        private void forceMode(DeathRainMode mode) {
            deathRainMode = mode;
            progression = 0.0f;
            configureCurrentMode();
        }

        private void update(float rainApproaching) {
            progression += 1.0f / Math.max(timeInThisMode, 1.0f) * (arenaStyleDeathRain ? 3.2f : 1.0f);

            boolean advance = false;
            if (progression > 1.0f) {
                progression = 1.0f;
                advance = true;
            }

            if (deathRainMode == DeathRainMode.CALM_BEFORE_STORM) {
                rumbleSound = Math.max(rumbleSound - scalePerTick(0.025f), 0.0f);
            } else {
                rumbleSound = lerp(
                        rumbleSound,
                        1.0f - inverseLerp(0.0f, 0.6f, rainApproaching),
                        scaleAlpha(0.2f)
                );
            }

            switch (deathRainMode) {
                case CALM_BEFORE_STORM -> {
                    intensity = (float) Math.pow(inverseLerp(0.15f, 0.0f, progression), 1.5f) * 0.24f;
                    shaderLight = -1.0f
                            + 0.3f * (float) Math.sin(inverseLerp(0.03f, 0.8f, progression) * Math.PI) * calmBeforeStormSunlight;
                    bulletRainDensity = (float) Math.pow(inverseLerp(0.3f, 1.0f, progression), 8.0f);
                }
                case GRADE_A_BUILDUP -> {
                    intensity = progression * 0.6f;
                    microScreenShake = progression * 1.5f;
                    bulletRainDensity = 1.0f - progression;
                }
                case GRADE_A_PLATEU -> {
                    // Hold previous values like the original.
                }
                case GRADE_B_BUILDUP -> {
                    intensity = lerp(0.6f, 0.71f, progression);
                    microScreenShake = lerp(1.5f, 2.1f, progression);
                    screenShake = progression * 1.2f;
                }
                case GRADE_B_PLATEU -> {
                    // Hold previous values like the original.
                }
                case FINAL_BUILDUP -> {
                    intensity = lerp(0.71f, 1.0f, progression);
                    microScreenShake = lerp(2.1f, 4.0f, (float) Math.pow(progression, 1.2f));
                    screenShake = lerp(1.2f, 3.0f, progression);
                }
                case ALTERNATE_BUILDUP -> {
                    intensity = lerp(0.24f, 0.6f, progression);
                    microScreenShake = 1.0f + progression * 0.5f;
                }
                case PULSES -> {
                    float pulseValue;
                    if (progression <= 0.9f) {
                        float num = (1.0f - progression) * 50.0f;
                        float num2 = 0.4f + (float) Math.sin(progression / (num / 3.0f));
                        pulseValue = progression + (float) Math.sin((progression * timeInThisMode) / num) * progression;

                        if (progression > 0.6f && Math.abs(pulseValue - num2) < 0.1f) {
                            pulseValue *= num2;
                        }

                        bulletRainDensity = lerp(0.1f, 1.0f, num2 - 0.4f);
                        pulseValue = clamp(pulseValue, progression * 0.6f, 1.0f);
                    } else {
                        bulletRainDensity = 0.0f;
                        pulseValue = 1.0f;
                    }

                    float t = 0.25f * inverseLerp(0.0f, 0.1f, progression);
                    intensity = lerp(intensity, lerp(0.0f, 0.75f, pulseValue), scaleAlpha(t));
                    microScreenShake = (1.0f + progression * 0.65f) * (pulseValue + 0.25f);
                    screenShake = lerp(screenShake, microScreenShake, scaleAlpha(0.3f));
                }
                case NONE, MAYHEM -> {
                    // NONE is transitional; MAYHEM is terminal.
                }
            }

            if (advance) {
                nextDeathRainMode();
            }
        }

        private void nextDeathRainMode() {
            if (deathRainMode == DeathRainMode.MAYHEM) {
                return;
            }

            if (deathRainMode == DeathRainMode.NONE) {
                if (chance40(0.7f) || arenaStyleDeathRain) {
                    deathRainMode = DeathRainMode.ALTERNATE_BUILDUP;
                } else {
                    deathRainMode = DeathRainMode.CALM_BEFORE_STORM;
                }
            } else if (deathRainMode == DeathRainMode.ALTERNATE_BUILDUP) {
                deathRainMode = DeathRainMode.GRADE_A_PLATEU;
            } else if (deathRainMode == DeathRainMode.PULSES) {
                deathRainMode = DeathRainMode.FINAL_BUILDUP;
            } else {
                deathRainMode = nextSequentialMode(deathRainMode);
            }

            progression = 0.0f;
            configureCurrentMode();
        }

        private void configureCurrentMode() {
            switch (deathRainMode) {
                case CALM_BEFORE_STORM -> {
                    timeInThisMode = scaledDuration(randomRange(400.0f, 800.0f));
                    calmBeforeStormSunlight = chance40(0.5f) ? 0.0f : ThreadLocalRandom.current().nextFloat();
                }
                case GRADE_A_BUILDUP -> {
                    timeInThisMode = scaledDuration(6.0f);
                    shaderLight = -1.0f;
                }
                case GRADE_A_PLATEU -> timeInThisMode = scaledDuration(randomRange(400.0f, 600.0f));
                case GRADE_B_BUILDUP -> {
                    if (chance40(0.5f)) {
                        timeInThisMode = scaledDuration(100.0f);
                    } else {
                        timeInThisMode = scaledDuration(randomRange(50.0f, 300.0f));
                    }
                }
                case GRADE_B_PLATEU -> {
                    if (chance40(0.5f)) {
                        timeInThisMode = scaledDuration(100.0f);
                    } else {
                        timeInThisMode = scaledDuration(randomRange(50.0f, 300.0f));
                    }
                }
                case FINAL_BUILDUP -> {
                    if (chance40(0.5f)) {
                        timeInThisMode = scaledDuration(randomRange(300.0f, 500.0f));
                    } else {
                        timeInThisMode = scaledDuration(randomRange(100.0f, 800.0f));
                    }
                }
                case ALTERNATE_BUILDUP -> timeInThisMode = scaledDuration(randomRange(400.0f, 1200.0f));
                case PULSES -> timeInThisMode = scaledDuration(randomRange(1000.0f, 2600.0f));
                case MAYHEM, NONE -> timeInThisMode = Float.MAX_VALUE;
            }
        }

        private DeathRainMode nextSequentialMode(DeathRainMode mode) {
            return switch (mode) {
                case CALM_BEFORE_STORM -> DeathRainMode.GRADE_A_BUILDUP;
                case GRADE_A_BUILDUP -> DeathRainMode.GRADE_A_PLATEU;
                case GRADE_A_PLATEU -> DeathRainMode.GRADE_B_BUILDUP;
                case GRADE_B_BUILDUP -> DeathRainMode.GRADE_B_PLATEU;
                case GRADE_B_PLATEU -> DeathRainMode.FINAL_BUILDUP;
                case FINAL_BUILDUP -> DeathRainMode.MAYHEM;
                default -> DeathRainMode.MAYHEM;
            };
        }
    }

    private float lastRainDirection;
    private float rainDirection;
    private float rainDirectionGetTo;

    private float intensity;
    private float shaderLight = -1.0f;
    private float screenShake;
    private float microScreenShake;
    private float rumbleSound;
    private float bulletRainDensity;

    private float flood;
    private float floodSpeed;

    private int bulletTimer;
    private int heavyTimer;
    private float floodLerpSpeed = 0.2f;
    private int waterFluxTicker;

    private float preCycleRainPulseIntensity;
    private float preCycleRainPulseScale;
    private boolean forceSlowFlood;

    private boolean forcedRain;
    private boolean arenaStyleDeathRain;

    private int debugLogTicker;

    /**
     * Stored in original Rain World 40-TPS ticks.
     * This can go negative like the source game.
     */
    private int timeUntilRainTicks40 = 2400;
    private boolean cyclePaused;

    private DeathRain deathRain;

    /**
     * Optional room/profile inputs, analogous to Rain World's room effects.
     */
    private boolean hasCustomRainProfile;
    private float customLightRain;
    private float customHeavyRain;
    private float customHeavyRainFlux;
    private float customBulletRain;
    private float customBulletRainFlux;

    /**
     * Optional override for RainApproaching-like behavior.
     */
    private boolean hasCustomRainApproaching;
    private float customRainApproaching;

    private GlobalRain() {
    }

    public static GlobalRain get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, ignored -> new GlobalRain());
    }

    public void tick(MinecraftServer server) {
        try {
            updateRainDirection();
            waterFluxTicker++;
            floodLerpSpeed = 0.2f;
            debugLogTicker++;

            if (!cyclePaused) {
                timeUntilRainTicks40 -= Math.round(TICK_RATIO);
            }

            float rainApproaching = resolveRainApproaching();

            if (deathRain != null) {
                deathRain.update(rainApproaching);

                boolean fastFlood = deathRain.deathRainMode.ordinal() > DeathRainMode.GRADE_A_BUILDUP.ordinal() && !forceSlowFlood;
                if (fastFlood) {
                    floodSpeed = Math.min(0.8f, floodSpeed + scalePerTick(0.0025f));
                }

                flood += floodSpeed;
                logEvery10Ticks(true);
                return;
            }

            intensity = inverseLerp(600.0f, 200.0f, timeUntilRainTicks40) * 0.24f;
            bulletRainDensity = 0.0f;

            float lightRain = resolveLightRain();
            float heavyRain = resolveHeavyRain();
            float heavyRainFlux = resolveHeavyRainFlux();
            float bulletRain = resolveBulletRain();
            float bulletRainFlux = resolveBulletRainFlux();

            if (preCycleRainPulseScale != 0.0f) {
                float prevLight = lightRain;
                float prevHeavyFlux = heavyRainFlux;
                float prevBullet = bulletRain;

                lightRain = (1.0f + clamp(preCycleRainPulseIntensity, 0.0f, 1.0f)) * preCycleRainPulseScale;
                heavyRainFlux = clamp(lightRain - 0.9f, 0.0f, 0.8f);
                bulletRain = clamp(lightRain - 0.9f, 0.0f, 1.0f);

                if (heavyRainFlux > 0.4f) {
                    bulletRain = 0.0f;
                }

                if (lightRain < prevLight) lightRain = prevLight;
                if (heavyRainFlux < prevHeavyFlux) heavyRainFlux = prevHeavyFlux;
                if (lightRain < prevBullet) lightRain = prevBullet;
            }

            float effectiveHeavyRain = heavyRain;
            if (heavyRainFlux > 0.0f) {
                float plateauTicks = scaledDuration(1200.0f * heavyRainFlux);
                float rampTicks = scaledDuration(60.0f);
                int period = Math.max(1, Math.round(rampTicks * 2.0f + plateauTicks * 2.0f));

                heavyTimer = (heavyTimer + 1) % period;

                if (heavyTimer < rampTicks) {
                    effectiveHeavyRain *= heavyTimer / rampTicks;
                } else if (heavyTimer >= rampTicks + plateauTicks && heavyTimer < rampTicks * 2.0f + plateauTicks) {
                    effectiveHeavyRain *= 1.0f - ((heavyTimer - (plateauTicks + rampTicks)) / rampTicks);
                } else if (heavyTimer >= rampTicks * 2.0f + plateauTicks) {
                    effectiveHeavyRain = 0.0f;
                }
            }

            if (effectiveHeavyRain > 0.0f) {
                intensity = (1.0f + effectiveHeavyRain * 4.0f) * 0.24f;
                rumbleSound = effectiveHeavyRain * 0.2f;
                screenShake = effectiveHeavyRain;
            } else if (lightRain > 0.0f || forcedRain) {
                intensity = Math.max(intensity, lightRain * 0.24f);
            } else {
                shaderLight = -1.0f;
                screenShake = 0.0f;
                microScreenShake = 0.0f;
                rumbleSound = 0.0f;
            }

            float effectiveBulletRain = bulletRain;
            if (bulletRainFlux > 0.0f) {
                float plateauTicks = scaledDuration(1200.0f * bulletRainFlux);
                float rampTicks = scaledDuration(60.0f);
                int period = Math.max(1, Math.round(rampTicks * 2.0f + plateauTicks * 2.0f));

                bulletTimer = (bulletTimer + 1) % period;

                if (bulletTimer < rampTicks) {
                    effectiveBulletRain *= bulletTimer / rampTicks;
                } else if (bulletTimer >= rampTicks + plateauTicks && bulletTimer < rampTicks * 2.0f + plateauTicks) {
                    effectiveBulletRain *= 1.0f - ((bulletTimer - (plateauTicks + rampTicks)) / rampTicks);
                } else if (bulletTimer >= rampTicks * 2.0f + plateauTicks) {
                    effectiveBulletRain = 0.0f;
                }
            }

            if (effectiveBulletRain > 0.0f) {
                bulletRainDensity = effectiveBulletRain;
            }

            logEvery10Ticks(lightRain > 0.0f || heavyRain > 0.0f || forcedRain);
        } finally {
            ModNetworking.syncGlobalRainToAll(server, bulletRainDensity);
        }
    }

    /**
     * Original-style random start.
     * This often begins in ALTERNATE_BUILDUP, which usually keeps bulletRainDensity at 0.
     */
    public void startDeathRain() {
        startDeathRain(false);
    }

    public void startDeathRain(boolean arenaStyle) {
        this.forcedRain = true;
        this.arenaStyleDeathRain = arenaStyle;

        if (this.deathRain == null) {
            this.deathRain = new DeathRain();
        }

        this.intensity = Math.max(this.intensity, 0.24f);
    }

    /**
     * Command-friendly overload.
     * Use GRADE_A_BUILDUP if you want immediate nonzero bulletRainDensity.
     */
    public void startDeathRain(DeathRainMode initialMode, boolean arenaStyle) {
        this.forcedRain = true;
        this.arenaStyleDeathRain = arenaStyle;

        if (this.deathRain == null) {
            this.deathRain = new DeathRain();
        }

        if (initialMode != null && initialMode != DeathRainMode.NONE && initialMode != DeathRainMode.MAYHEM) {
            this.deathRain.forceMode(initialMode);
        }

        this.intensity = Math.max(this.intensity, 0.24f);
    }

    public void triggerRain() {
        startDeathRain(false);
    }

    public void forcePassiveRainOnly() {
        this.forcedRain = true;
        this.intensity = Math.max(this.intensity, 0.24f);
    }

    public void stopForcedRain() {
        this.forcedRain = false;
        this.arenaStyleDeathRain = false;
    }

    public void resetRain() {
        deathRain = null;
        forcedRain = false;
        arenaStyleDeathRain = false;
        forceSlowFlood = false;

        intensity = 0.0f;
        shaderLight = -1.0f;
        screenShake = 0.0f;
        microScreenShake = 0.0f;
        rumbleSound = 0.0f;
        bulletRainDensity = 0.0f;

        flood = 0.0f;
        floodSpeed = 0.0f;

        bulletTimer = 0;
        heavyTimer = 0;
        waterFluxTicker = 0;

        preCycleRainPulseIntensity = 0.0f;
        preCycleRainPulseScale = 0.0f;
        debugLogTicker = 0;
    }

    public void setForceSlowFlood(boolean forceSlowFlood) {
        this.forceSlowFlood = forceSlowFlood;
    }

    public void setCyclePaused(boolean cyclePaused) {
        this.cyclePaused = cyclePaused;
    }

    public void setTimeUntilRainTicks40(int ticks40) {
        this.timeUntilRainTicks40 = ticks40;
    }

    public void setTimeUntilRainTicks20(int ticks20) {
        this.timeUntilRainTicks40 = Math.round(ticks20 * TICK_RATIO);
    }

    public int getTimeUntilRainTicks40() {
        return timeUntilRainTicks40;
    }

    public int getTimeUntilRainTicks20() {
        return Math.round(timeUntilRainTicks40 / TICK_RATIO);
    }

    public void setPreCycleRainPulse(float intensity, float scale) {
        this.preCycleRainPulseIntensity = intensity;
        this.preCycleRainPulseScale = scale;
    }

    public void clearPreCycleRainPulse() {
        this.preCycleRainPulseIntensity = 0.0f;
        this.preCycleRainPulseScale = 0.0f;
    }

    public void setCustomRainProfile(float lightRain, float heavyRain, float heavyRainFlux, float bulletRain, float bulletRainFlux) {
        this.hasCustomRainProfile = true;
        this.customLightRain = clamp(lightRain, 0.0f, 1.0f);
        this.customHeavyRain = clamp(heavyRain, 0.0f, 1.0f);
        this.customHeavyRainFlux = Math.max(0.0f, heavyRainFlux);
        this.customBulletRain = clamp(bulletRain, 0.0f, 1.0f);
        this.customBulletRainFlux = Math.max(0.0f, bulletRainFlux);
    }

    public void clearCustomRainProfile() {
        this.hasCustomRainProfile = false;
    }

    public void setCustomRainApproaching(float rainApproaching) {
        this.hasCustomRainApproaching = true;
        this.customRainApproaching = clamp(rainApproaching, 0.0f, 1.0f);
    }

    public void clearCustomRainApproaching() {
        this.hasCustomRainApproaching = false;
    }

    public float getLastRainDirection() {
        return lastRainDirection;
    }

    public float getRainDirection() {
        return rainDirection;
    }

    public float getIntensity() {
        return intensity;
    }

    public float getShaderLight() {
        return shaderLight;
    }

    public float getScreenShake() {
        return screenShake;
    }

    public float getMicroScreenShake() {
        return microScreenShake;
    }

    public float getRumbleSound() {
        return rumbleSound;
    }

    public float getBulletRainDensity() {
        return bulletRainDensity;
    }

    public float getFlood() {
        return flood;
    }

    public float getFloodSpeed() {
        return floodSpeed;
    }

    public boolean isForcedRain() {
        return forcedRain;
    }

    public boolean isDeathRainActive() {
        return deathRain != null;
    }

    public DeathRainMode getDeathRainMode() {
        return deathRain == null ? DeathRainMode.NONE : deathRain.deathRainMode;
    }

    public float getOutsidePushAround() {
        return (float) Math.pow(inverseLerp(0.35f, 0.7f, intensity), 0.8f);
    }

    public float getInsidePushAround() {
        return (float) Math.pow(inverseLerp(0.63f, 0.98f, intensity), 3.5f);
    }

    public boolean hasAnyPushAround() {
        return getOutsidePushAround() > 0.0f || getInsidePushAround() > 0.0f;
    }

    private void updateRainDirection() {
        if (chance40(0.025f)) {
            rainDirectionGetTo = lerp(-1.0f, 1.0f, ThreadLocalRandom.current().nextFloat());
        }

        lastRainDirection = rainDirection;
        rainDirection = lerp(rainDirection, rainDirectionGetTo, scaleAlpha(0.01f));

        float step = scalePerTick(0.0125f);
        if (rainDirection < rainDirectionGetTo) {
            rainDirection = Math.min(rainDirection + step, rainDirectionGetTo);
        } else if (rainDirection > rainDirectionGetTo) {
            rainDirection = Math.max(rainDirection - step, rainDirectionGetTo);
        }
    }

    private float resolveLightRain() {
        return hasCustomRainProfile ? customLightRain : 0.0f;
    }

    private float resolveHeavyRain() {
        return hasCustomRainProfile ? customHeavyRain : 0.0f;
    }

    private float resolveHeavyRainFlux() {
        return hasCustomRainProfile ? customHeavyRainFlux : 0.0f;
    }

    private float resolveBulletRain() {
        return hasCustomRainProfile ? customBulletRain : 0.0f;
    }

    private float resolveBulletRainFlux() {
        return hasCustomRainProfile ? customBulletRainFlux : 0.0f;
    }

    private float resolveRainApproaching() {
        if (hasCustomRainApproaching) {
            return customRainApproaching;
        }

        return inverseLerp(1200.0f, 0.0f, timeUntilRainTicks40);
    }

    private void logEvery10Ticks(boolean activeRain) {
        if (debugLogTicker < 10) {
            return;
        }
        debugLogTicker = 0;

        KarmaGateMod.LOGGER.info(
                "[GlobalRain] activeRain={} forcedRain={} deathRain={} mode={} " +
                        "timeUntilRain40={} timeUntilRain20={} rainApproaching={} " +
                        "intensity={} shaderLight={} screenShake={} microScreenShake={} rumbleSound={} " +
                        "bulletRainDensity={} flood={} floodSpeed={} rainDir={} lastRainDir={}",
                activeRain,
                forcedRain,
                deathRain != null,
                getDeathRainMode(),
                timeUntilRainTicks40,
                getTimeUntilRainTicks20(),
                resolveRainApproaching(),
                intensity,
                shaderLight,
                screenShake,
                microScreenShake,
                rumbleSound,
                bulletRainDensity,
                flood,
                floodSpeed,
                rainDirection,
                lastRainDirection
        );
    }

    private static boolean chance40(float chanceAt40Tps) {
        float chanceAt20Tps = 1.0f - (float) Math.pow(1.0f - chanceAt40Tps, TICK_RATIO);
        return ThreadLocalRandom.current().nextFloat() < chanceAt20Tps;
    }

    private static float scalePerTick(float perTickAt40Tps) {
        return perTickAt40Tps * TICK_RATIO;
    }

    private static float scaledDuration(float ticksAt40Tps) {
        return Math.max(1.0f, ticksAt40Tps / TICK_RATIO);
    }

    private static float scaleAlpha(float alphaAt40Tps) {
        alphaAt40Tps = clamp(alphaAt40Tps, 0.0f, 1.0f);
        return 1.0f - (float) Math.pow(1.0f - alphaAt40Tps, TICK_RATIO);
    }

    private static float randomRange(float min, float max) {
        return min + ThreadLocalRandom.current().nextFloat() * (max - min);
    }

    private static float inverseLerp(float a, float b, float value) {
        if (a == b) {
            return 0.0f;
        }
        return clamp((value - a) / (b - a), 0.0f, 1.0f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }
}