package dev.fouriis.karmagate.sound;

import dev.fouriis.karmagate.client.weather.GlobalRainClientState;
import dev.fouriis.karmagate.rain.GlobalRain;
import net.brickcraftdream.librainworldmc.client.api.RwSoundApi;
import net.brickcraftdream.librainworldmc.client.api.RwSoundsApi;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;

public final class GlobalRainAudioController {

    private static final RwSoundApi RW_SOUNDS = RwSoundsApi.get();

    private static final String NORMAL_RAIN_LOOP = "shortLightRainLoop";
    private static final String HEAVY_RAIN_LOOP = "rainHeavy1";
    private static final String DEATH_RAIN_LOOP = "shortDeathRainLoop";
    private static final String DEATH_RAIN_HEARD_FROM_UNDERGROUND_LOOP = "rainRumble1";

    private static final String[] HEAVY_RUMBLE_IDS = new String[]{
            "rainrumble1"
    };

    private static final String[] LIGHT_RUMBLE_IDS = new String[]{
            "rainrumble1"
    };

    private static final Map<String, SoundEvent> EVENT_CACHE = new HashMap<>();

    private static RainLoopSoundInstance normalRainLoop;
    private static RainLoopSoundInstance heavyRainLoop;
    private static RainLoopSoundInstance deathRainLoop;

    private static Identifier normalRainEventId;
    private static Identifier heavyRainEventId;
    private static Identifier deathRainEventId;

    private static RumbleLoopSoundInstance activeRumbleLoop;
    private static Identifier activeRumbleEventId;
    private static SoundEvent cachedHeavyRumbleEvent;
    private static SoundEvent cachedLightRumbleEvent;
    private static long lastRumbleResolveAttemptTick = Long.MIN_VALUE;

    private GlobalRainAudioController() {
    }

    public static void clientTick(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null || client.getSoundManager() == null) {
            clear();
            return;
        }

        SoundManager soundManager = client.getSoundManager();
        tickAmbient(client, soundManager);
        tickRumble(client, soundManager);
    }

    public static void clear() {
        stopLoop(normalRainLoop);
        stopLoop(heavyRainLoop);
        stopLoop(deathRainLoop);

        normalRainLoop = null;
        heavyRainLoop = null;
        deathRainLoop = null;

        normalRainEventId = null;
        heavyRainEventId = null;
        deathRainEventId = null;

        stopActiveRumbleLoop();
    }

    private static void tickAmbient(MinecraftClient client, SoundManager soundManager) {
        float intensity = MathHelper.clamp(resolveIntensity(client), 0.0f, 1.0f);

        boolean isOutside = true;
        ensurePersistentLoops(soundManager, isOutside);

        if (intensity <= 0.001f) {
            setAllTargetsSilent();
            return;
        }

        float normalTarget = 0.0f;
        float heavyTarget = 0.0f;
        float deathTarget = 0.0f;

        if (intensity < 0.24f) {
            normalTarget = remapClamped(intensity, 0.0f, 0.24f, 0.0f, 1.0f);
        } else if (intensity < 0.71f) {
            normalTarget = remapClamped(intensity, 0.24f, 0.71f, 1.0f, 0.0f);
            heavyTarget = remapClamped(intensity, 0.24f, 0.71f, 0.0f, 1.0f);
        } else {
            heavyTarget = remapClamped(intensity, 0.71f, 1.0f, 1.0f, 0.0f);
            deathTarget = remapClamped(intensity, 0.71f, 1.0f, 0.0f, 1.0f);
        }

        if (normalRainLoop != null) {
            normalRainLoop.setTarget(normalTarget, computePitch(intensity, LoopKind.NORMAL));
        }
        if (heavyRainLoop != null) {
            heavyRainLoop.setTarget(heavyTarget, computePitch(intensity, LoopKind.HEAVY));
        }
        if (deathRainLoop != null) {
            deathRainLoop.setTarget(deathTarget, computePitch(intensity, LoopKind.DEATH));
        }
    }

    private static void tickRumble(MinecraftClient client, SoundManager soundManager) {
        float rumble = MathHelper.clamp(resolveRumbleLevel(client), 0.0f, 1.0f);
        if (rumble <= 0.001f) {
            stopActiveRumbleLoop();
            return;
        }

        SoundEvent desiredEvent = selectRumbleEvent(client, rumble);
        if (desiredEvent == null) {
            return;
        }

        ensureRumbleLoop(soundManager, desiredEvent, rumble);

        if (activeRumbleLoop != null) {
            activeRumbleLoop.setTargetRumble(rumble);
        }
    }

    private static float resolveIntensity(MinecraftClient client) {
        if (GlobalRainClientState.hasSync()) {
            return GlobalRainClientState.intensity();
        }

        if (client.getServer() != null) {
            return GlobalRain.get(client.getServer()).getIntensity();
        }

        return 0.0f;
    }

    private static float resolveRumbleLevel(MinecraftClient client) {
        if (GlobalRainClientState.hasSync()) {
            return GlobalRainClientState.rumbleSound();
        }

        if (client.getServer() != null) {
            return GlobalRain.get(client.getServer()).getRumbleSound();
        }

        return 0.0f;
    }

    private static void ensurePersistentLoops(SoundManager soundManager, boolean isOutside) {
        normalRainLoop = ensurePersistentLoop(
                soundManager,
                normalRainLoop,
                normalRainEventId,
                resolveEvent(NORMAL_RAIN_LOOP),
                LoopKind.NORMAL
        );
        normalRainEventId = normalRainLoop != null ? normalRainLoop.getEventId() : null;

        heavyRainLoop = ensurePersistentLoop(
                soundManager,
                heavyRainLoop,
                heavyRainEventId,
                resolveEvent(HEAVY_RAIN_LOOP),
                LoopKind.HEAVY
        );
        heavyRainEventId = heavyRainLoop != null ? heavyRainLoop.getEventId() : null;

        String deathId = isOutside
                ? DEATH_RAIN_LOOP
                : DEATH_RAIN_HEARD_FROM_UNDERGROUND_LOOP;

        deathRainLoop = ensurePersistentLoop(
                soundManager,
                deathRainLoop,
                deathRainEventId,
                resolveEvent(deathId),
                LoopKind.DEATH
        );
        deathRainEventId = deathRainLoop != null ? deathRainLoop.getEventId() : null;
    }

    private static RainLoopSoundInstance ensurePersistentLoop(
            SoundManager soundManager,
            RainLoopSoundInstance active,
            Identifier activeEventId,
            SoundEvent desiredEvent,
            LoopKind kind
    ) {
        if (desiredEvent == null) {
            if (active != null) {
                active.stopNow();
            }
            return null;
        }

        Identifier desiredId = desiredEvent.getId();
        if (active == null || active.isDone() || activeEventId == null || !activeEventId.equals(desiredId)) {
            if (active != null) {
                active.stopNow();
            }

            RainLoopSoundInstance created = new RainLoopSoundInstance(desiredEvent, kind);
            soundManager.play(created);
            return created;
        }

        return active;
    }

    private static SoundEvent selectRumbleEvent(MinecraftClient client, float rumble) {
        long worldTime = client.world == null ? 0L : client.world.getTime();

        if (cachedHeavyRumbleEvent == null || cachedLightRumbleEvent == null) {
            if (lastRumbleResolveAttemptTick != worldTime) {
                cachedHeavyRumbleEvent = resolveRwSound(HEAVY_RUMBLE_IDS);
                cachedLightRumbleEvent = resolveRwSound(LIGHT_RUMBLE_IDS);
                lastRumbleResolveAttemptTick = worldTime;
            }
        }

        if (rumble >= 0.25f && cachedHeavyRumbleEvent != null) {
            return cachedHeavyRumbleEvent;
        }

        if (cachedLightRumbleEvent != null) {
            return cachedLightRumbleEvent;
        }

        return cachedHeavyRumbleEvent;
    }

    private static SoundEvent resolveEvent(String id) {
        SoundEvent cached = EVENT_CACHE.get(id);
        if (cached != null) {
            return cached;
        }

        SoundEvent resolved = tryResolveEvent(id);
        if (resolved != null) {
            EVENT_CACHE.put(id, resolved);
        }
        return resolved;
    }

    private static SoundEvent resolveRwSound(String[] ids) {
        for (String id : ids) {
            SoundEvent event = resolveEvent(id);
            if (event != null) {
                return event;
            }
        }
        return null;
    }

    private static SoundEvent tryResolveEvent(String id) {
        try {
            SoundEvent event = RW_SOUNDS.getEvent(id);
            if (event != null) {
                return event;
            }
        } catch (Exception ignored) {
        }

        try {
            Identifier directId = Identifier.of("librainworldmc", id);
            if (Registries.SOUND_EVENT.containsId(directId)) {
                return Registries.SOUND_EVENT.get(directId);
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static void ensureRumbleLoop(SoundManager soundManager, SoundEvent desiredEvent, float initialRumble) {
        Identifier desiredId = desiredEvent.getId();

        if (activeRumbleLoop == null || activeRumbleLoop.isDone() || activeRumbleEventId == null || !activeRumbleEventId.equals(desiredId)) {
            stopActiveRumbleLoop();
            activeRumbleLoop = new RumbleLoopSoundInstance(desiredEvent, initialRumble);
            activeRumbleEventId = desiredId;
            soundManager.play(activeRumbleLoop);
            return;
        }

        activeRumbleLoop.setTargetRumble(initialRumble);
    }

    private static void setAllTargetsSilent() {
        if (normalRainLoop != null) {
            normalRainLoop.setTarget(0.0f, normalRainLoop.getCurrentTargetPitch());
        }
        if (heavyRainLoop != null) {
            heavyRainLoop.setTarget(0.0f, heavyRainLoop.getCurrentTargetPitch());
        }
        if (deathRainLoop != null) {
            deathRainLoop.setTarget(0.0f, deathRainLoop.getCurrentTargetPitch());
        }
    }

    private static void stopLoop(RainLoopSoundInstance loop) {
        if (loop != null) {
            loop.stopNow();
        }
    }

    private static void stopActiveRumbleLoop() {
        if (activeRumbleLoop != null) {
            activeRumbleLoop.stopNow();
            activeRumbleLoop = null;
            activeRumbleEventId = null;
        }
    }

    private static float computePitch(float intensity, LoopKind kind) {
        float pitch = switch (kind) {
            case NORMAL -> 1.00f;
            case HEAVY -> 0.96f;
            case DEATH -> 0.92f;
        };

        pitch += MathHelper.clamp(intensity, 0.0f, 1.0f) * 0.04f;
        return MathHelper.clamp(pitch, 0.5f, 1.5f);
    }

    private static float remapClamped(float value, float inMin, float inMax, float outMin, float outMax) {
        if (inMin == inMax) {
            return outMin;
        }
        float t = MathHelper.clamp((value - inMin) / (inMax - inMin), 0.0f, 1.0f);
        return MathHelper.lerp(t, outMin, outMax);
    }

    private enum LoopKind {
        NORMAL,
        HEAVY,
        DEATH
    }

    private static final class RainLoopSoundInstance extends MovingSoundInstance {

        private static final int FADE_IN_TICKS = 35;
        private static final float VOLUME_RESPONSE = 0.08f;
        private static final float PITCH_RESPONSE = 0.05f;
        private static final float SILENT_STOP_THRESHOLD = 0.0005f;
        private static final int SILENT_STOP_TICKS = 240;
        private static final float ENGINE_KEEPALIVE_VOLUME = 0.001f;

        private final LoopKind kind;

        private float targetVolume;
        private float targetPitch;
        private int ageTicks;
        private int quietTicks;
        private boolean stopped;

        private RainLoopSoundInstance(SoundEvent event, LoopKind kind) {
            super(event, SoundCategory.AMBIENT, SoundInstance.createRandom());
            this.kind = kind;
            this.repeat = true;
            this.repeatDelay = 0;
            this.relative = true;
            this.attenuationType = AttenuationType.NONE;

            this.targetVolume = 0.0f;
            this.targetPitch = switch (kind) {
                case NORMAL -> 1.00f;
                case HEAVY -> 0.96f;
                case DEATH -> 0.92f;
            };

            this.volume = ENGINE_KEEPALIVE_VOLUME;
            this.pitch = this.targetPitch;
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
        }

        private Identifier getEventId() {
            return this.getSound().getIdentifier();
        }

        private float getCurrentTargetPitch() {
            return targetPitch;
        }

        private void setTarget(float volume, float pitch) {
            this.targetVolume = MathHelper.clamp(volume, 0.0f, 1.0f);
            this.targetPitch = MathHelper.clamp(pitch, 0.5f, 1.5f);
        }

        @Override
        public void tick() {
            if (stopped) {
                setDone();
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null || client.world == null) {
                setDone();
                return;
            }

            ageTicks++;

            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;

            float fadeIn = MathHelper.clamp(ageTicks / (float) FADE_IN_TICKS, 0.0f, 1.0f);
            float effectiveTarget = targetVolume * fadeIn;

            this.volume += (effectiveTarget - this.volume) * VOLUME_RESPONSE;
            this.pitch += (targetPitch - this.pitch) * PITCH_RESPONSE;

            if (targetVolume <= SILENT_STOP_THRESHOLD) {
                this.volume = Math.max(this.volume, ENGINE_KEEPALIVE_VOLUME);
            }

            this.volume = MathHelper.clamp(this.volume, 0.0f, 1.0f);
            this.pitch = MathHelper.clamp(this.pitch, 0.5f, 1.5f);

            if (targetVolume <= SILENT_STOP_THRESHOLD && this.volume <= ENGINE_KEEPALIVE_VOLUME + 0.0001f) {
                quietTicks++;
                if (quietTicks > SILENT_STOP_TICKS) {
                    setDone();
                }
            } else {
                quietTicks = 0;
            }
        }

        private void stopNow() {
            this.stopped = true;
            this.repeat = false;
            setDone();
        }
    }

    private static final class RumbleLoopSoundInstance extends MovingSoundInstance {

        private static final int FADE_IN_TICKS = 30;

        private float targetRumble;
        private int quietTicks;
        private int ageTicks;
        private boolean stopped;

        private RumbleLoopSoundInstance(SoundEvent event, float initialRumble) {
            super(event, SoundCategory.AMBIENT, SoundInstance.createRandom());
            this.repeat = true;
            this.repeatDelay = 0;
            this.relative = true;
            this.attenuationType = AttenuationType.NONE;

            this.targetRumble = MathHelper.clamp(initialRumble, 0.0f, 1.0f);

            this.volume = 0.001f;
            this.pitch = 0.85f + this.targetRumble * 0.35f;

            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;

            this.quietTicks = 0;
            this.ageTicks = 0;
        }

        private void setTargetRumble(float rumble) {
            this.targetRumble = MathHelper.clamp(rumble, 0.0f, 1.0f);
        }

        @Override
        public void tick() {
            if (stopped) {
                setDone();
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null || client.world == null) {
                setDone();
                return;
            }

            ageTicks++;

            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;

            float mappedTarget = MathHelper.clamp(targetRumble * 1.8f, 0.0f, 1.0f);
            this.volume += (mappedTarget - this.volume) * 0.10f;

            float fadeIn = MathHelper.clamp(ageTicks / (float) FADE_IN_TICKS, 0.0f, 1.0f);
            float allowedVolume = mappedTarget * fadeIn;
            if (this.volume > allowedVolume) {
                this.volume = allowedVolume;
            }

            this.volume = MathHelper.clamp(this.volume, 0.0f, 1.0f);

            float targetPitch = 0.85f + targetRumble * 0.35f;
            this.pitch += (targetPitch - this.pitch) * 0.05f;

            if (mappedTarget < 0.01f && this.volume < 0.01f) {
                quietTicks++;
                if (quietTicks > 20) {
                    setDone();
                }
            } else {
                quietTicks = 0;
            }
        }

        private void stopNow() {
            this.stopped = true;
            this.repeat = false;
            setDone();
        }
    }
}
