package dev.fouriis.karmagate.sound;


import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import dev.fouriis.karmagate.KarmaGateMod;

public class ModSounds {
    public static final Identifier STEAM_LOOP = Identifier.of("karma-gate-mod:steamloop");
    public static SoundEvent STEAM_LOOP_EVENT = SoundEvent.of(STEAM_LOOP);
    public static final Identifier STEAM_LOOP_2 = Identifier.of("karma-gate-mod:steamloop2");
    public static SoundEvent STEAM_LOOP_2_EVENT = SoundEvent.of(STEAM_LOOP_2);

    // Grouped/normalized sound event IDs (match sounds.json)
    public static final Identifier CAABINET_DOOR = Identifier.of("karma-gate-mod:caabinetdoor");
    public static final Identifier CAABINET_LOOP = Identifier.of("karma-gate-mod:caabinetloop");
    public static final Identifier CHAIN_DOOR_LOOP = Identifier.of("karma-gate-mod:chaindoorloop");
    public static final Identifier DEEP_GLUG_LOOP = Identifier.of("karma-gate-mod:deepglugloop");
    public static final Identifier DRAIN_GLUG_A = Identifier.of("karma-gate-mod:draingluga");
    public static final Identifier HUGE_IMPACT = Identifier.of("karma-gate-mod:hugeimpact");
    public static final Identifier HYDRA_IMPACT = Identifier.of("karma-gate-mod:hydraimpact");
    public static final Identifier HYDRAULICS_A = Identifier.of("karma-gate-mod:hydraulicsa");
    public static final Identifier LARGE_LATCH = Identifier.of("karma-gate-mod:largelatch");
    public static final Identifier MACHINE_DOORS = Identifier.of("karma-gate-mod:machinedoors");
    public static final Identifier MED_IMPACT_2 = Identifier.of("karma-gate-mod:medimpact2");
    public static final Identifier METAL_PLING = Identifier.of("karma-gate-mod:metalpling");
    public static final Identifier METAL_SCRAPE_A = Identifier.of("karma-gate-mod:metalscrapea");
    public static final Identifier OMINOUS_MACHINE_A = Identifier.of("karma-gate-mod:ominousmachinea");
    public static final Identifier STEAM_BLAST_A = Identifier.of("karma-gate-mod:steamblasta");
    public static final Identifier WATER_DRAIN_LOOP = Identifier.of("karma-gate-mod:waterdrainloop");
    public static final Identifier THE_HORIZON_OF_EVENTS_WITHOUT_BURNIN_IN_QUESTION_FROM_A_PIERCE_HER = Identifier.of("karma-gate-mod:the-horizon-of-events-without-burnin-in-question-from-a-pierce-her");

    public static SoundEvent CAABINET_DOOR_EVENT = SoundEvent.of(CAABINET_DOOR);
    public static SoundEvent CAABINET_LOOP_EVENT = SoundEvent.of(CAABINET_LOOP);
    public static SoundEvent CHAIN_DOOR_LOOP_EVENT = SoundEvent.of(CHAIN_DOOR_LOOP);
    public static SoundEvent DEEP_GLUG_LOOP_EVENT = SoundEvent.of(DEEP_GLUG_LOOP);
    public static SoundEvent DRAIN_GLUG_A_EVENT = SoundEvent.of(DRAIN_GLUG_A);
    public static SoundEvent HUGE_IMPACT_EVENT = SoundEvent.of(HUGE_IMPACT);
    public static SoundEvent HYDRA_IMPACT_EVENT = SoundEvent.of(HYDRA_IMPACT);
    public static SoundEvent HYDRAULICS_A_EVENT = SoundEvent.of(HYDRAULICS_A);
    public static SoundEvent LARGE_LATCH_EVENT = SoundEvent.of(LARGE_LATCH);
    public static SoundEvent MACHINE_DOORS_EVENT = SoundEvent.of(MACHINE_DOORS);
    public static SoundEvent MED_IMPACT_2_EVENT = SoundEvent.of(MED_IMPACT_2);
    public static SoundEvent METAL_PLING_EVENT = SoundEvent.of(METAL_PLING);
    public static SoundEvent METAL_SCRAPE_A_EVENT = SoundEvent.of(METAL_SCRAPE_A);
    public static SoundEvent OMINOUS_MACHINE_A_EVENT = SoundEvent.of(OMINOUS_MACHINE_A);
    public static SoundEvent STEAM_BLAST_A_EVENT = SoundEvent.of(STEAM_BLAST_A);
    public static SoundEvent WATER_DRAIN_LOOP_EVENT = SoundEvent.of(WATER_DRAIN_LOOP);
    public static SoundEvent THE_HORIZON_OF_EVENTS_WITHOUT_BURNIN_IN_QUESTION_FROM_A_PIERCE_HER_EVENT = SoundEvent.of(THE_HORIZON_OF_EVENTS_WITHOUT_BURNIN_IN_QUESTION_FROM_A_PIERCE_HER);

    public static void registerModSounds() {
        Registry.register(Registries.SOUND_EVENT, STEAM_LOOP, STEAM_LOOP_EVENT);
        Registry.register(Registries.SOUND_EVENT, STEAM_LOOP_2, STEAM_LOOP_2_EVENT);
        Registry.register(Registries.SOUND_EVENT, CAABINET_DOOR, CAABINET_DOOR_EVENT);
        Registry.register(Registries.SOUND_EVENT, CAABINET_LOOP, CAABINET_LOOP_EVENT);
        Registry.register(Registries.SOUND_EVENT, CHAIN_DOOR_LOOP, CHAIN_DOOR_LOOP_EVENT);
        Registry.register(Registries.SOUND_EVENT, DEEP_GLUG_LOOP, DEEP_GLUG_LOOP_EVENT);
        Registry.register(Registries.SOUND_EVENT, DRAIN_GLUG_A, DRAIN_GLUG_A_EVENT);
        Registry.register(Registries.SOUND_EVENT, HUGE_IMPACT, HUGE_IMPACT_EVENT);
        Registry.register(Registries.SOUND_EVENT, HYDRA_IMPACT, HYDRA_IMPACT_EVENT);
        Registry.register(Registries.SOUND_EVENT, HYDRAULICS_A, HYDRAULICS_A_EVENT);
        Registry.register(Registries.SOUND_EVENT, LARGE_LATCH, LARGE_LATCH_EVENT);
        Registry.register(Registries.SOUND_EVENT, MACHINE_DOORS, MACHINE_DOORS_EVENT);
        Registry.register(Registries.SOUND_EVENT, MED_IMPACT_2, MED_IMPACT_2_EVENT);
        Registry.register(Registries.SOUND_EVENT, METAL_PLING, METAL_PLING_EVENT);
        Registry.register(Registries.SOUND_EVENT, METAL_SCRAPE_A, METAL_SCRAPE_A_EVENT);
        Registry.register(Registries.SOUND_EVENT, OMINOUS_MACHINE_A, OMINOUS_MACHINE_A_EVENT);
        Registry.register(Registries.SOUND_EVENT, STEAM_BLAST_A, STEAM_BLAST_A_EVENT);
        Registry.register(Registries.SOUND_EVENT, WATER_DRAIN_LOOP, WATER_DRAIN_LOOP_EVENT);
        Registry.register(Registries.SOUND_EVENT, THE_HORIZON_OF_EVENTS_WITHOUT_BURNIN_IN_QUESTION_FROM_A_PIERCE_HER, THE_HORIZON_OF_EVENTS_WITHOUT_BURNIN_IN_QUESTION_FROM_A_PIERCE_HER_EVENT);
        System.out.println("Registered sound events for karma-gate-mod");
    }

    // ---------------- Inline client audio shim ----------------
    public interface AudioImpl {
        void onSteamBurst(BlockPos pos, float intensity01, SoundEvent loopEvent);
        default void onTimelineEvent(BlockPos pos, String token) {}
        default void onSoundKeyframe(BlockPos pos, Identifier soundId, float volume, float pitch) {}
    }

    private static AudioImpl AUDIO = (p, i, e) -> {};

    public static void setAudio(AudioImpl impl) {
        if (impl != null) AUDIO = impl;
    }

    public static void onSteamBurst(BlockPos pos, float intensity01, SoundEvent loopEvent) {
        AUDIO.onSteamBurst(pos, intensity01, loopEvent);
    }

    public static void onTimelineEvent(BlockPos pos, String token) {
        KarmaGateMod.LOGGER.info("[AudioShim] timeline token '{}' at {}", token, pos);
        AUDIO.onTimelineEvent(pos, token);
    }

    public static void onSoundKeyframe(BlockPos pos, Identifier soundId, float volume, float pitch) {
        KarmaGateMod.LOGGER.info("[AudioShim] sound keyframe '{}' v={} p={} at {}", soundId, volume, pitch, pos);
        AUDIO.onSoundKeyframe(pos, soundId, volume, pitch);
    }
}
