package dev.fouriis.karmagate.rain;

import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-side Rain World style rain-cycle controller.
 *
 * Non-lethal version:
 * - cycle expires
 * - post-cycle GlobalRain begins
 * - once rain reaches max intensity, wait 10 seconds
 * - then reset back to cycle start
 *
 * No vanilla weather is used here.
 *
 * Important:
 * To avoid the visible "once per second" stepping, this class manually sends
 * WorldTimeUpdateS2CPacket every tick during the day->night transition.
 */
public final class RainCycle {
    private static final Map<MinecraftServer, RainCycle> INSTANCES = new WeakHashMap<>();

    public static final int MC_TPS = 20;
    public static final long LOCKED_DAY_TIME = 7500L;
    public static final long MIDNIGHT_TIME = 18000L;

    /**
     * 10 second wait after rain reaches max intensity.
     */
    public static final int RESET_DELAY_AFTER_MAX_INTENSITY_TICKS = MC_TPS * 10;

    public static int rwTicksToMc(int rwTicks) {
        return Math.max(1, Math.round(rwTicks * 0.5f));
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
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

    /**
     * Quintic smootherstep for a very smooth ease-in/ease-out.
     */
    private static float smootherStep(float t) {
        t = clamp01(t);
        return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f);
    }

    public static RainCycle get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, ignored -> new RainCycle());
    }

    private int timer;
    private int cycleLength;
    private int baseCycleLength;

    private boolean storyMode = true;
    private boolean speedUpToRain;
    private boolean deathRainHasHit;

    private int startUpTicks;
    private int pause;

    private int sunDownStartTime;
    private int dayNightCounter;

    private int preTimer;
    private int maxPreTimer;

    private float preCycleRainPulseWaveA;
    private float preCycleRainPulseWaveB;
    private float preCycleRainPulseWaveC;

    private boolean enablePrecycles;
    private boolean forcePrecycle;
    private float precycleChance;
    private int minPrecycleTicks = rwTicksToMc(4800);
    private int maxPrecycleTicks = rwTicksToMc(12000);

    /**
     * Duration of the day->night transition.
     */
    private int sunDownDurationTicks = rwTicksToMc(2400);

    /**
     * After post-cycle rain reaches max intensity, wait before resetting.
     */
    private boolean waitingForCycleReset;
    private int resetDelayTicksRemaining;

    private RainCycle() {
        setCycleMinutes(10.0f);
        this.startUpTicks = rwTicksToMc(2400);
        this.precycleChance = 0.0f;
        recomputeSunDownStartTime();
    }

    public void tick(MinecraftServer server) {
        GlobalRain globalRain = GlobalRain.get(server);

        if (allowRainCounterToTick()) {
            if (pause > 0) {
                pause--;
            } else if (preTimer <= 0) {
                if (speedUpToRain && !deathRainHasHit) {
                    if (timer < cycleLength - rwTicksToMc(800)) {
                        timer += 3;
                    } else if (timer < cycleLength) {
                        timer += 1;
                    }
                }

                timer++;

                if (preTimer <= 0) {
                    globalRain.setPreCycleRainPulseScaleDecay();
                }
            }

            if (pause <= 0) {
                if (preTimer > 0) {
                    preTimer--;
                }

                preCycleRainPulseWaveA += 0.012f;
                preCycleRainPulseWaveB += 0.020f;
                preCycleRainPulseWaveC += 0.006f;

                globalRain.setPreCycleRainPulseIntensity(getPreCycleRainIntensity());

                if (globalRain.getDrainWorldFastDrainCounter() > 0) {
                    globalRain.setDrainWorldFastDrainCounter(globalRain.getDrainWorldFastDrainCounter() - 1);
                }

                if (globalRain.getDrainWorldFlood() > 0.0f) {
                    float drain = globalRain.getDrainWorldDrainSpeed()
                            * (1.0f
                            + inverseLerp(0.0f, cycleLength / 2.0f, timer)
                            + inverseLerp(cycleLength / 2.0f, cycleLength, timer)
                            + 6.0f * inverseLerp((cycleLength / 8.0f) * 7.0f, cycleLength, timer))
                            * (0.66f + (float) Math.sin(timer / 15.0f) / 3.0f);

                    float flood = globalRain.getDrainWorldFlood();
                    flood -= drain;
                    flood += (float) Math.pow(globalRain.getIntensity() * 2.0f, 4.0f) * 1.85f;
                    flood -= 0.55f * inverseLerp(0.0f, rwTicksToMc(200), globalRain.getDrainWorldFastDrainCounter());

                    if (flood < 0.0f) {
                        flood = 0.0f;
                    }

                    globalRain.setDrainWorldFlood(flood);
                }
            }
        }

        if (!deathRainHasHit && timer >= cycleLength) {
            globalRain.initDeathRain();
            deathRainHasHit = true;
        }

        globalRain.tick(server, this);

        if (deathRainHasHit && !waitingForCycleReset && globalRain.getIntensity() >= 0.999f) {
            waitingForCycleReset = true;
            resetDelayTicksRemaining = RESET_DELAY_AFTER_MAX_INTENSITY_TICKS;
        }

        if (waitingForCycleReset) {
            resetDelayTicksRemaining--;
            if (resetDelayTicksRemaining <= 0) {
                restartCycle(server);
                return;
            }
        }

        updateWorldTime(server);
    }

    private boolean allowRainCounterToTick() {
        return true;
    }

    private void updateWorldTime(MinecraftServer server) {
        long appliedTime;

        if (deathRainHasHit) {
            appliedTime = MIDNIGHT_TIME;
        } else if (timer < sunDownStartTime) {
            appliedTime = LOCKED_DAY_TIME;
        } else {
            dayNightCounter++;

            float progress = clamp01(
                    (float) (timer - sunDownStartTime) / (float) Math.max(1, sunDownDurationTicks)
            );

            // Absolute smooth curve, not speed accumulation.
            float eased = smootherStep(progress);
            appliedTime = Math.round(lerp(LOCKED_DAY_TIME, MIDNIGHT_TIME, eased));
        }

        for (ServerWorld world : server.getWorlds()) {
            world.setTimeOfDay(appliedTime);
            sendImmediateTimeSync(world, appliedTime);
        }
    }

    /**
     * Push world time to clients every tick so the sky transition does not wait
     * for vanilla's coarser time sync cadence.
     */
    private void sendImmediateTimeSync(ServerWorld world, long timeOfDay) {
        long gameTime = world.getTime();

        for (ServerPlayerEntity player : world.getPlayers()) {
            player.networkHandler.sendPacket(
                    new WorldTimeUpdateS2CPacket(gameTime, timeOfDay, false)
            );
        }
    }

    private void recomputeSunDownStartTime() {
        this.sunDownStartTime = Math.max(0, cycleLength - sunDownDurationTicks);
    }

    public void restartCycle(MinecraftServer server) {
        this.timer = 0;
        this.pause = 0;
        this.dayNightCounter = 0;
        this.speedUpToRain = false;
        this.deathRainHasHit = false;

        this.waitingForCycleReset = false;
        this.resetDelayTicksRemaining = 0;

        rollPrecycle();
        recomputeSunDownStartTime();

        GlobalRain.get(server).resetRain();

        for (ServerWorld world : server.getWorlds()) {
            world.setTimeOfDay(LOCKED_DAY_TIME);
            sendImmediateTimeSync(world, LOCKED_DAY_TIME);
        }
    }

    private void rollPrecycle() {
        if (forcePrecycle) {
            this.maxPreTimer = ThreadLocalRandom.current().nextInt(minPrecycleTicks, maxPrecycleTicks + 1);
            this.preTimer = maxPreTimer;
            this.preCycleRainPulseWaveA = 0.0f;
            this.preCycleRainPulseWaveB = 0.0f;
            this.preCycleRainPulseWaveC = (float) Math.PI / 2.0f;
            return;
        }

        if (!enablePrecycles || precycleChance <= 0.0f) {
            this.maxPreTimer = 0;
            this.preTimer = 0;
            return;
        }

        if (ThreadLocalRandom.current().nextFloat() < precycleChance) {
            this.maxPreTimer = ThreadLocalRandom.current().nextInt(minPrecycleTicks, maxPrecycleTicks + 1);
            this.preTimer = maxPreTimer;
            this.preCycleRainPulseWaveA = 0.0f;
            this.preCycleRainPulseWaveB = 0.0f;
            this.preCycleRainPulseWaveC = (float) Math.PI / 2.0f;
        } else {
            this.maxPreTimer = 0;
            this.preTimer = 0;
        }
    }

    public void arenaEndSessionRain() {
        speedUpToRain = true;
        timer = Math.max(timer, cycleLength - rwTicksToMc(2000));
    }

    public float getTimeUntilRain() {
        return cycleLength - timer;
    }

    public float getAmountLeft() {
        if (cycleLength <= 0) {
            return 0.0f;
        }
        return (float) (cycleLength - timer) / (float) cycleLength;
    }

    public float getRainApproaching() {
        if (!storyMode) {
            return inverseLerp(0.0f, rwTicksToMc(400), getTimeUntilRain());
        }
        if (preTimer > 0 && maxPreTimer > 0) {
            return 1.0f - Math.min((preTimer * 4.0f) / (float) maxPreTimer, 1.0f);
        }
        return inverseLerp(0.0f, rwTicksToMc(2400), getTimeUntilRain());
    }

    public boolean isRainGameOver() {
        return timer >= cycleLength;
    }

    public float getCycleStartUp() {
        if (startUpTicks <= 0) {
            return 1.0f;
        }
        return inverseLerp(0.0f, startUpTicks, timer);
    }

    public float getCycleProgression() {
        return inverseLerp(0.0f, cycleLength, timer);
    }

    public float getProximityToMiddleOfCycle() {
        if (cycleLength <= 0) {
            return 0.0f;
        }
        return Math.abs(timer - (cycleLength / 2.0f)) / (cycleLength / 2.0f);
    }

    public float getLightChangeBecauseOfRain() {
        return inverseLerp(0.4f, 1.0f, getRainApproaching());
    }

    public float getShaderLight(MinecraftServer server) {
        if (isRainGameOver()) {
            return GlobalRain.get(server).getShaderLight();
        }

        if (storyMode) {
            return -1.0f
                    + lerp(getCycleStartUp(), 1.0f - getProximityToMiddleOfCycle(), 0.2f)
                    * 2.0f
                    * inverseLerp(0.4f, 1.0f, getLightChangeBecauseOfRain());
        }

        return lerp(-1.0f, 1.0f, inverseLerp(rwTicksToMc(880), rwTicksToMc(200), getTimeUntilRain()));
    }

    public float getRainDarkPalette() {
        if (storyMode) {
            return inverseLerp(1.0f, 0.0f, getLightChangeBecauseOfRain());
        }
        return inverseLerp(rwTicksToMc(1000), rwTicksToMc(400), getTimeUntilRain());
    }

    public float getScreenShake(MinecraftServer server) {
        if (preTimer > 0) {
            return Math.max(0.15f, Math.min(1.0f, getPreCycleRainIntensity())) / 3.0f;
        }
        if (isRainGameOver()) {
            return GlobalRain.get(server).getScreenShake();
        }
        return (float) Math.pow(1.0f - inverseLerp(0.0f, 0.2f, getRainApproaching()), 2.0f);
    }

    public float getMicroScreenShake(MinecraftServer server) {
        if (preTimer > 0) {
            return getPreCycleRainIntensity() / 5.0f;
        }
        if (isRainGameOver()) {
            return GlobalRain.get(server).getMicroScreenShake();
        }
        return (float) Math.pow(1.0f - inverseLerp(0.0f, 0.6f, getRainApproaching()), 1.5f);
    }

    public float getPreCycleRainIntensity() {
        if (preTimer == 0 || maxPreTimer == 0) {
            return 0.0f;
        }

        float fade = 1.0f - (float) Math.pow(inverseLerp(maxPreTimer, 0.0f, preTimer), 24.0f);

        return ((float) Math.sin(preCycleRainPulseWaveA)
                + (float) Math.sin(preCycleRainPulseWaveB) / 2.0f
                + (float) Math.cos(preCycleRainPulseWaveC) * (((float) preTimer / (float) maxPreTimer) * 2.0f))
                * fade;
    }

    public int getTimer() {
        return timer;
    }

    public int getCycleLength() {
        return cycleLength;
    }

    public int getBaseCycleLength() {
        return baseCycleLength;
    }

    public int getSunDownStartTime() {
        return sunDownStartTime;
    }

    public int getDayNightCounter() {
        return dayNightCounter;
    }

    public int getPreTimer() {
        return preTimer;
    }

    public int getMaxPreTimer() {
        return maxPreTimer;
    }

    public boolean isDeathRainHasHit() {
        return deathRainHasHit;
    }

    public boolean isWaitingForCycleReset() {
        return waitingForCycleReset;
    }

    public int getResetDelayTicksRemaining() {
        return resetDelayTicksRemaining;
    }

    public void setCycleMinutes(float minutes) {
        this.baseCycleLength = Math.max(1, Math.round(minutes * MC_TPS * 60.0f));
        this.cycleLength = baseCycleLength;
        recomputeSunDownStartTime();
    }

    public void setStoryMode(boolean storyMode) {
        this.storyMode = storyMode;
    }

    public void setPause(int pause) {
        this.pause = Math.max(0, pause);
    }

    public void setEnablePrecycles(boolean enablePrecycles) {
        this.enablePrecycles = enablePrecycles;
    }

    public void setForcePrecycle(boolean forcePrecycle) {
        this.forcePrecycle = forcePrecycle;
    }

    public void setPrecycleChance(float precycleChance) {
        this.precycleChance = clamp01(precycleChance);
    }

    public void setSunDownDurationTicks(int sunDownDurationTicks) {
        this.sunDownDurationTicks = Math.max(1, sunDownDurationTicks);
        recomputeSunDownStartTime();
    }
}