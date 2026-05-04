package dev.fouriis.karmagate.rain;

import dev.fouriis.karmagate.network.ModNetworking;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-side global rain state, modeled after Rain World's GlobalRain.
 *
 * Responsibilities:
 * - passive room/profile-based rain
 * - heavy/bullet rain flux pulsing
 * - wind direction
 * - post-cycle death-rain state machine
 * - flood-related values
 *
 * This class only computes and syncs rain state. It does NOT use vanilla weather.
 */
public final class GlobalRain {
    private static final Map<MinecraftServer, GlobalRain> INSTANCES = new WeakHashMap<>();

    public static GlobalRain get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, ignored -> new GlobalRain());
    }

    public enum DeathRainMode {
        NONE,
        CALM_BEFORE_STORM,
        GRADE_A_BUILD_UP,
        GRADE_A_PLATEAU,
        GRADE_B_BUILD_UP,
        GRADE_B_PLATEAU,
        FINAL_BUILD_UP,
        MAYHEM,
        ALTERNATE_BUILD_UP,
        PULSES
    }

    public static final class RainProfile {
        public static final RainProfile NONE = new RainProfile(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

        public final float lightRain;
        public final float heavyRain;
        public final float heavyRainFlux;
        public final float bulletRain;
        public final float bulletRainFlux;

        public RainProfile(float lightRain,
                           float heavyRain,
                           float heavyRainFlux,
                           float bulletRain,
                           float bulletRainFlux) {
            this.lightRain = lightRain;
            this.heavyRain = heavyRain;
            this.heavyRainFlux = heavyRainFlux;
            this.bulletRain = bulletRain;
            this.bulletRainFlux = bulletRainFlux;
        }
    }

    private final class DeathRain {
        private DeathRainMode mode = DeathRainMode.NONE;
        private float timeInThisMode;
        private float progression;
        private float calmBeforeStormSunlight;

        private DeathRain() {
            nextMode();
        }

        private void update(RainCycle cycle) {
            progression += 1.0f / Math.max(1.0f, timeInThisMode);
            boolean advance = false;

            if (progression > 1.0f) {
                progression = 1.0f;
                advance = true;
            }

            if (mode == DeathRainMode.CALM_BEFORE_STORM) {
                rumbleSound = Math.max(rumbleSound - 0.05f, 0.0f);
            } else {
                // Original was Lerp(..., 0.2) at 40 TPS. Equivalent alpha at 20 TPS is ~0.36.
                rumbleSound = lerp(
                        rumbleSound,
                        1.0f - inverseLerp(0.0f, 0.6f, cycle.getRainApproaching()),
                        0.36f
                );
            }

            if (mode == DeathRainMode.CALM_BEFORE_STORM) {
                intensity = (float) Math.pow(inverseLerp(0.15f, 0.0f, progression), 1.5f) * 0.24f;
                shaderLight = -1.0f
                        + 0.3f
                        * (float) Math.sin(inverseLerp(0.03f, 0.8f, progression) * Math.PI)
                        * calmBeforeStormSunlight;
                bulletRainDensity = (float) Math.pow(inverseLerp(0.3f, 1.0f, progression), 8.0f);
            } else if (mode == DeathRainMode.GRADE_A_BUILD_UP) {
                intensity = progression * 0.6f;
                microScreenShake = progression * 1.5f;
                bulletRainDensity = 1.0f - progression;
            } else if (mode == DeathRainMode.GRADE_B_BUILD_UP) {
                intensity = lerp(0.6f, 0.71f, progression);
                microScreenShake = lerp(1.5f, 2.1f, progression);
                screenShake = progression * 1.2f;
            } else if (mode == DeathRainMode.FINAL_BUILD_UP) {
                intensity = lerp(0.71f, 1.0f, progression);
                microScreenShake = lerp(2.1f, 4.0f, (float) Math.pow(progression, 1.2f));
                screenShake = lerp(1.2f, 3.0f, progression);
            } else if (mode == DeathRainMode.ALTERNATE_BUILD_UP) {
                intensity = lerp(0.24f, 0.6f, progression);
                microScreenShake = 1.0f + progression * 0.5f;
            } else if (mode == DeathRainMode.PULSES) {
                float pulseValue;

                if (progression <= 0.9f) {
                    float pulsePeriod = (1.0f - progression) * 50.0f;
                    float pulseShape = 0.4f + (float) Math.sin(progression / (pulsePeriod / 3.0f));

                    pulseValue = progression
                            + (float) Math.sin((progression * timeInThisMode) / pulsePeriod)
                            / (timeInThisMode / Math.max(0.0001f, progression * timeInThisMode));

                    if (progression > 0.6f && Math.abs(pulseValue - pulseShape) < 0.1f) {
                        pulseValue *= pulseShape;
                    }

                    bulletRainDensity = lerp(0.1f, 1.0f, pulseShape - 0.4f);
                    pulseValue = clamp(pulseValue, progression * 0.6f, 1.0f);
                } else {
                    bulletRainDensity = 0.0f;
                    pulseValue = 1.0f;
                }

                float t = 0.25f * inverseLerp(0.0f, 0.1f, progression);
                intensity = lerp(intensity, lerp(0.0f, 0.75f, pulseValue), t);
                microScreenShake = (1.0f + progression * 0.65f) * (pulseValue + 0.25f);
                screenShake = lerp(screenShake, microScreenShake, 0.51f);
            }

            if (advance) {
                nextMode();
            }
        }

        private void nextMode() {
            if (mode == DeathRainMode.MAYHEM) {
                return;
            }

            if (mode == DeathRainMode.NONE && ThreadLocalRandom.current().nextFloat() < 0.7f) {
                if (ThreadLocalRandom.current().nextFloat() < 0.7f) {
                    mode = DeathRainMode.ALTERNATE_BUILD_UP;
                } else {
                    mode = DeathRainMode.PULSES;
                }
            } else if (mode == DeathRainMode.ALTERNATE_BUILD_UP) {
                mode = DeathRainMode.GRADE_A_PLATEAU;
            } else if (mode == DeathRainMode.PULSES) {
                mode = DeathRainMode.FINAL_BUILD_UP;
            } else {
                mode = nextSequentialMode(mode);
            }

            progression = 0.0f;
            configureCurrentMode();
        }

        private DeathRainMode nextSequentialMode(DeathRainMode current) {
            return switch (current) {
                case NONE -> DeathRainMode.CALM_BEFORE_STORM;
                case CALM_BEFORE_STORM -> DeathRainMode.GRADE_A_BUILD_UP;
                case GRADE_A_BUILD_UP -> DeathRainMode.GRADE_A_PLATEAU;
                case GRADE_A_PLATEAU -> DeathRainMode.GRADE_B_BUILD_UP;
                case GRADE_B_BUILD_UP -> DeathRainMode.GRADE_B_PLATEAU;
                case GRADE_B_PLATEAU -> DeathRainMode.FINAL_BUILD_UP;
                case FINAL_BUILD_UP -> DeathRainMode.MAYHEM;
                default -> DeathRainMode.MAYHEM;
            };
        }

        private void configureCurrentMode() {
            switch (mode) {
                case CALM_BEFORE_STORM -> {
                    timeInThisMode = randomRange(RainCycle.rwTicksToMc(400), RainCycle.rwTicksToMc(800));
                    calmBeforeStormSunlight = ThreadLocalRandom.current().nextFloat() < 0.5f
                            ? 0.0f
                            : ThreadLocalRandom.current().nextFloat();
                }
                case GRADE_A_BUILD_UP -> {
                    timeInThisMode = RainCycle.rwTicksToMc(6);
                    shaderLight = -1.0f;
                }
                case GRADE_A_PLATEAU -> timeInThisMode = randomRange(RainCycle.rwTicksToMc(400), RainCycle.rwTicksToMc(600));
                case GRADE_B_BUILD_UP -> {
                    if (ThreadLocalRandom.current().nextFloat() < 0.5f) {
                        timeInThisMode = RainCycle.rwTicksToMc(100);
                    } else {
                        timeInThisMode = randomRange(RainCycle.rwTicksToMc(50), RainCycle.rwTicksToMc(300));
                    }
                }
                case GRADE_B_PLATEAU -> {
                    if (ThreadLocalRandom.current().nextFloat() < 0.5f) {
                        timeInThisMode = RainCycle.rwTicksToMc(100);
                    } else {
                        timeInThisMode = randomRange(RainCycle.rwTicksToMc(50), RainCycle.rwTicksToMc(300));
                    }
                }
                case FINAL_BUILD_UP -> {
                    if (ThreadLocalRandom.current().nextFloat() < 0.5f) {
                        timeInThisMode = randomRange(RainCycle.rwTicksToMc(300), RainCycle.rwTicksToMc(500));
                    } else {
                        timeInThisMode = randomRange(RainCycle.rwTicksToMc(100), RainCycle.rwTicksToMc(800));
                    }
                }
                case ALTERNATE_BUILD_UP -> timeInThisMode = randomRange(RainCycle.rwTicksToMc(400), RainCycle.rwTicksToMc(1200));
                case PULSES -> timeInThisMode = randomRange(RainCycle.rwTicksToMc(1000), RainCycle.rwTicksToMc(2600));
                case NONE, MAYHEM -> timeInThisMode = Float.MAX_VALUE;
            }
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

    private DeathRain deathRain;

    private float flood;
    private float floodSpeed;
    private int bulletTimer;
    private int heavyTimer;
    private float floodLerpSpeed = 0.2f;
    private int waterFluxTicker;

    private float preCycleRainPulseIntensity;
    private float preCycleRainPulseScale;
    private boolean forceSlowFlood;

    private float drainWorldDrainSpeed = 0.45f;
    private float drainWorldFlood;
    private int drainWorldFastDrainCounter;

    /**
     * Global rain profile analogous to Rain World's room settings.
     * Something else in your mod can update this based on biome, structure, dimension, etc.
     */
    private RainProfile currentProfile = RainProfile.NONE;

    private GlobalRain() {
    }

    public void tick(MinecraftServer server) {
        tick(server, RainCycle.get(server));
    }

    public void tick(MinecraftServer server, RainCycle cycle) {
        try {
            updateRainDirection();
            waterFluxTicker++;
            floodLerpSpeed = 0.2f;

            if (deathRain != null) {
                deathRain.update(cycle);

                boolean fastFlood = modeOrdinal(deathRain.mode) > modeOrdinal(DeathRainMode.GRADE_A_BUILD_UP);

                if (!forceSlowFlood && fastFlood) {
                    floodSpeed = Math.min(0.8f, floodSpeed + 0.005f);
                } else if (modeOrdinal(deathRain.mode) >= modeOrdinal(DeathRainMode.GRADE_A_BUILD_UP)) {
                    floodSpeed = Math.min(1.8f, floodSpeed + (1.0f / 75.0f));
                }

                flood += floodSpeed;
                return;
            }

            // Baseline cycle rain pressure before post-cycle rain starts.
            intensity = inverseLerp(RainCycle.rwTicksToMc(600), RainCycle.rwTicksToMc(200), cycle.getTimeUntilRain()) * 0.24f;
            shaderLight = -1.0f;
            screenShake = 0.0f;
            microScreenShake = 0.0f;
            rumbleSound = 0.0f;
            bulletRainDensity = 0.0f;

            // Passive/profile rain.
            float lightRain = currentProfile.lightRain;
            float heavyRain = currentProfile.heavyRain;
            float heavyRainFlux = currentProfile.heavyRainFlux;
            float bulletRain = currentProfile.bulletRain;
            float bulletRainFlux = currentProfile.bulletRainFlux;

            if (preCycleRainPulseScale != 0.0f) {
                float oldLightRain = lightRain;
                float oldHeavyFlux = heavyRainFlux;
                float oldBulletRain = bulletRain;

                lightRain = (1.0f + clamp(preCycleRainPulseIntensity, 0.0f, 1.0f)) * preCycleRainPulseScale;
                heavyRainFlux = clamp(lightRain - 0.9f, 0.0f, 0.8f);
                bulletRain = clamp(lightRain - 0.9f, 0.0f, 1.0f);

                if (heavyRainFlux > 0.4f) {
                    bulletRain = 0.0f;
                }

                if (lightRain < oldLightRain) {
                    lightRain = oldLightRain;
                }
                if (heavyRainFlux < oldHeavyFlux) {
                    heavyRainFlux = oldHeavyFlux;
                }
                if (lightRain < oldBulletRain) {
                    lightRain = oldBulletRain;
                }
            }

            float effectiveHeavyRain = heavyRain;
            if (heavyRainFlux > 0.0f) {
                float plateauTicks = RainCycle.rwTicksToMc(1200) * heavyRainFlux;
                float rampTicks = RainCycle.rwTicksToMc(60);
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
            } else if (lightRain > 0.0f) {
                intensity = lightRain * 0.24f;
            }

            float effectiveBulletRain = bulletRain;
            if (bulletRainFlux > 0.0f) {
                float plateauTicks = RainCycle.rwTicksToMc(1200) * bulletRainFlux;
                float rampTicks = RainCycle.rwTicksToMc(60);
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

            if (drainWorldFlood > 0.0f) {
                flood = 1.0f + drainWorldFlood;
                floodLerpSpeed = 1.0f;
            }
        } finally {
            ModNetworking.syncGlobalRainToAll(
                    server,
                    intensity,
                    rainDirection,
                    bulletRainDensity,
                    rumbleSound,
                    screenShake,
                    microScreenShake
            );
        }
    }

    public void initDeathRain() {
        if (deathRain == null) {
            deathRain = new DeathRain();
        }
    }

    public void resetRain() {
        deathRain = null;

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
        forceSlowFlood = false;

        drainWorldFlood = 0.0f;
        drainWorldFastDrainCounter = 0;

        lastRainDirection = 0.0f;
        rainDirection = 0.0f;
        rainDirectionGetTo = 0.0f;
    }

    private void updateRainDirection() {
        // Locked to perfectly vertical for now (no horizontal drift).
        lastRainDirection = 0.0f;
        rainDirectionGetTo = 0.0f;
        rainDirection = 0.0f;
    }

    private static int modeOrdinal(DeathRainMode mode) {
        return mode.ordinal();
    }

    private static float randomRange(float min, float max) {
        return min + ThreadLocalRandom.current().nextFloat() * (max - min);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }

    private static float inverseLerp(float a, float b, float value) {
        if (a == b) {
            return 0.0f;
        }
        return clamp01((value - a) / (b - a));
    }

    private static float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }

    public void setRainProfile(RainProfile profile) {
        this.currentProfile = profile == null ? RainProfile.NONE : profile;
    }

    public RainProfile getRainProfile() {
        return currentProfile;
    }

    public void setPreCycleRainPulseIntensity(float value) {
        this.preCycleRainPulseIntensity = value;
    }

    public void setPreCycleRainPulseScale(float value) {
        this.preCycleRainPulseScale = value;
    }

    public void setPreCycleRainPulseScaleDecay() {
        if (Math.abs(preCycleRainPulseScale) > 0.003f) {
            // 0.999 per RW tick ~= 0.998001 per MC tick
            preCycleRainPulseScale *= 0.998001f;
        } else {
            preCycleRainPulseScale = 0.0f;
        }
    }

    public void setForceSlowFlood(boolean forceSlowFlood) {
        this.forceSlowFlood = forceSlowFlood;
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

    public float getFloodLerpSpeed() {
        return floodLerpSpeed;
    }

    public int getWaterFluxTicker() {
        return waterFluxTicker;
    }

    public float getLastRainDirection() {
        return lastRainDirection;
    }

    public float getRainDirection() {
        return rainDirection;
    }

    public float getDrainWorldDrainSpeed() {
        return drainWorldDrainSpeed;
    }

    public void setDrainWorldDrainSpeed(float drainWorldDrainSpeed) {
        this.drainWorldDrainSpeed = drainWorldDrainSpeed;
    }

    public float getDrainWorldFlood() {
        return drainWorldFlood;
    }

    public void setDrainWorldFlood(float drainWorldFlood) {
        this.drainWorldFlood = drainWorldFlood;
    }

    public int getDrainWorldFastDrainCounter() {
        return drainWorldFastDrainCounter;
    }

    public void setDrainWorldFastDrainCounter(int drainWorldFastDrainCounter) {
        this.drainWorldFastDrainCounter = Math.max(0, drainWorldFastDrainCounter);
    }
}