package dev.fouriis.karmagate;

import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.command.CoralNeuronCommands;
import dev.fouriis.karmagate.command.ProjectionZoneCommands;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.network.ModNetworking;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.sound.ModSounds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KarmaGateMod implements ModInitializer {
    public static final String MOD_ID = "karma-gate-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

        public static final EntityType<CoralNeuronEntity> VINE_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "vine"),
            FabricEntityTypeBuilder.<CoralNeuronEntity>create(SpawnGroup.MISC, (type, world) -> new CoralNeuronEntity(type, world))
                    // Tiny bounding box; we render our own geometry
                    .dimensions(EntityDimensions.fixed(0.25f, 0.25f))
                    .trackRangeBlocks(196)
                    .trackedUpdateRate(1)
                    .build()
    );


    @Override
    public void onInitialize() {
        // Register mod content
        ModBlocks.registerModBlocks();
        ModBlockEntities.registerBlockEntities();
        ModParticles.register();
        ModSounds.registerModSounds();
        
        // Register networking
        ModNetworking.register();
        
        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ProjectionZoneCommands.register(dispatcher);
            CoralNeuronCommands.register(dispatcher);
        });

        

        LOGGER.info("Hello Fabric world!");
    }
}
