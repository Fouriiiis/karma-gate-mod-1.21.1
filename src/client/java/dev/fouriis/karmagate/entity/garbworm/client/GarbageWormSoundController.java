package dev.fouriis.karmagate.entity.garbworm.client;

import dev.fouriis.karmagate.entity.garbworm.GarbageWormEntity;
import net.brickcraftdream.librainworldmc.client.api.RwSoundApi;
import net.brickcraftdream.librainworldmc.client.api.RwSoundsApi;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class GarbageWormSoundController {

    private static final String SND_SWALLOW_LOOP = "wormRustle";
    private static final String SND_UPSET_LOOP = "wormHum4";
    private static final String SND_CURIOUS_LOOP = "wormHum3";

    private static final RwSoundApi RW_SOUNDS = RwSoundsApi.get();

    private static final Map<UUID, GarbageWormLoopSoundInstance> ACTIVE = new HashMap<>();

    private GarbageWormSoundController() {
    }

    private static SoundEvent resolveRwSound(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return RW_SOUNDS.getEvent(id);
        } catch (Exception e) {
            return null;
        }
    }

    private static SoundEvent selectLoopEvent(GarbageWormEntity worm) {
        if (worm.getAttackCtr() > 40 && worm.getAttackCtr() < 190) {
            return resolveRwSound(SND_SWALLOW_LOOP);
        }
        return resolveRwSound(worm.isShowAngry() ? SND_UPSET_LOOP : SND_CURIOUS_LOOP);
    }

    public static void tickFor(GarbageWormEntity worm) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSoundManager() == null || worm == null) {
            return;
        }

        UUID id = worm.getUuid();

        if (!worm.isAlive() || worm.isRemoved() || worm.getExtended() <= 0f) {
            stopFor(worm);
            return;
        }

        SoundEvent desiredEvent = selectLoopEvent(worm);
        if (desiredEvent == null) {
            stopFor(worm);
            return;
        }

        SoundManager soundManager = client.getSoundManager();
        GarbageWormLoopSoundInstance existing = ACTIVE.get(id);

        if (existing == null || existing.isDone()) {
            GarbageWormLoopSoundInstance created = new GarbageWormLoopSoundInstance(worm, desiredEvent, 1.0f, 1.0f);
            ACTIVE.put(id, created);
            soundManager.play(created);
            return;
        }

        if (existing.getCurrentEvent() != desiredEvent) {
            existing.stopNow();

            GarbageWormLoopSoundInstance replacement = new GarbageWormLoopSoundInstance(worm, desiredEvent, 1.0f, 1.0f);
            ACTIVE.put(id, replacement);
            soundManager.play(replacement);
        }
    }

    public static void stopFor(GarbageWormEntity worm) {
        if (worm == null) {
            return;
        }
        GarbageWormLoopSoundInstance instance = ACTIVE.remove(worm.getUuid());
        if (instance != null) {
            instance.stopNow();
        }
    }

    public static void cleanup() {
        Iterator<Map.Entry<UUID, GarbageWormLoopSoundInstance>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, GarbageWormLoopSoundInstance> entry = it.next();
            GarbageWormLoopSoundInstance instance = entry.getValue();
            if (instance == null || instance.isDone()) {
                it.remove();
            }
        }
    }
}
