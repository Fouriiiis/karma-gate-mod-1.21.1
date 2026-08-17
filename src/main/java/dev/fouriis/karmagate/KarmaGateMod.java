package dev.fouriis.karmagate;

import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.command.CoralNeuronCommands;
import dev.fouriis.karmagate.command.ProjectionZoneCommands;
import dev.fouriis.karmagate.command.StarMatrixCommands;
import dev.fouriis.karmagate.entity.GraffitiEntity;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.centipede.CentipedeBodyEntity;
import dev.fouriis.karmagate.entity.centipede.CentipedeHeadEntity;
import dev.fouriis.karmagate.entity.centipede.CentipedeSegmentEntity;
import dev.fouriis.karmagate.entity.centipede.CentipedeEntity;
import dev.fouriis.karmagate.entity.centipede.CentiwingEntity;
import dev.fouriis.karmagate.entity.centipede.RedCentipedeEntity;
import dev.fouriis.karmagate.entity.centipede.SmallCentipedeEntity;
import dev.fouriis.karmagate.entity.centipede.SmallCentiwingEntity;
import dev.fouriis.karmagate.entity.daddy.DaddyLongLegsEntity;
import dev.fouriis.karmagate.entity.echo.EchoEntity;
import dev.fouriis.karmagate.entity.garbworm.GarbageWormEntity;
import dev.fouriis.karmagate.entity.oracle.FivePebblesEntity;
import dev.fouriis.karmagate.entity.oracle.LooksToTheMoonEntity;
import dev.fouriis.karmagate.entity.oracle.OracleEntity;
import dev.fouriis.karmagate.entity.overseer.YellowOverseerEntity;
import dev.fouriis.karmagate.entity.poleplant.PolePlantEntity;
import dev.fouriis.karmagate.entity.poleplant.PolePlantSegmentEntity;
import dev.fouriis.karmagate.entity.spider.SpiderEntity;
import dev.fouriis.karmagate.entity.stowaway.StowawayBugEntity;
import dev.fouriis.karmagate.item.ModItems;
import dev.fouriis.karmagate.item.tool.CoralNeuronDefinition;
import dev.fouriis.karmagate.item.tool.ProjectionZoneDefinition;
import dev.fouriis.karmagate.hose.FuelHoseSessionManager;
import dev.fouriis.karmagate.network.ModNetworking;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.sound.ModSounds;
import net.brickcraftdream.librainworldmc.tool.api.SelectionToolRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.block.DoorBlock;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

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

    public static final EntityType<GraffitiEntity> GRAFFITI_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "graffiti"),
            FabricEntityTypeBuilder.<GraffitiEntity>create(SpawnGroup.MISC, GraffitiEntity::new)
                    .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(20)
                    .build()
    );

    public static final EntityType<StowawayBugEntity> STOWAWAY_BUG_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "stowaway_bug"),
            FabricEntityTypeBuilder.<StowawayBugEntity>create(SpawnGroup.MONSTER, (type, world) -> new StowawayBugEntity(type, world))
                    .dimensions(EntityDimensions.fixed(1.0f, 2.0f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(3)
                    .build()
    );

    // --- Centipede entity types ---
    public static final EntityType<CentipedeHeadEntity> CENTIPEDE_HEAD_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "centipede_head"),
            FabricEntityTypeBuilder.<CentipedeHeadEntity>create(SpawnGroup.MONSTER, (type, world) -> new CentipedeHeadEntity(type, world))
                    .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(3)
                    .build()
    );

    public static final EntityType<CentipedeBodyEntity> CENTIPEDE_BODY_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "centipede_body"),
            FabricEntityTypeBuilder.<CentipedeBodyEntity>create(SpawnGroup.MONSTER, (type, world) -> new CentipedeBodyEntity(type, world))
                    .dimensions(EntityDimensions.fixed(0.8f, 0.45f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(3)
                    .build()
    );

    public static final EntityType<RedCentipedeEntity> RED_CENTIPEDE_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "red_centipede"),
            FabricEntityTypeBuilder.<RedCentipedeEntity>create(SpawnGroup.MONSTER, (type, world) -> new RedCentipedeEntity(type, world))
                    .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(3)
                    .build()
    );

    public static final EntityType<CentipedeEntity> CENTIPEDE_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "centipede"),
            FabricEntityTypeBuilder.<CentipedeEntity>create(SpawnGroup.MONSTER, (type, world) -> new CentipedeEntity(type, world))
                    .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(3)
                    .build()
    );

    public static final EntityType<CentiwingEntity> CENTIWING_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "centiwing"),
            FabricEntityTypeBuilder.<CentiwingEntity>create(SpawnGroup.MONSTER, (type, world) -> new CentiwingEntity(type, world))
                    .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(3)
                    .build()
    );

    public static final EntityType<SmallCentipedeEntity> SMALL_CENTIPEDE_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "small_centipede"),
            FabricEntityTypeBuilder.<SmallCentipedeEntity>create(SpawnGroup.MONSTER, (type, world) -> new SmallCentipedeEntity(type, world))
                    .dimensions(EntityDimensions.fixed(0.3f, 0.3f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(3)
                    .build()
    );

    public static final EntityType<SmallCentiwingEntity> SMALL_CENTIWING_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "small_centiwing"),
            FabricEntityTypeBuilder.<SmallCentiwingEntity>create(SpawnGroup.MONSTER, (type, world) -> new SmallCentiwingEntity(type, world))
                    .dimensions(EntityDimensions.fixed(0.3f, 0.3f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(3)
                    .build()
    );

    // --- Spider entity type ---
    public static final EntityType<SpiderEntity> SPIDER_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "coalmine_spider"),
            FabricEntityTypeBuilder.<SpiderEntity>create(SpawnGroup.MONSTER, (type, world) -> new SpiderEntity(type, world))
                    .dimensions(EntityDimensions.fixed(0.3f, 0.15f))
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(3)
                    .build()
    );

    // --- Garbage Worm entity type ---
    public static final EntityType<GarbageWormEntity> GARBAGE_WORM_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "garbage_worm"),
            FabricEntityTypeBuilder.<GarbageWormEntity>create(SpawnGroup.CREATURE, (type, world) -> new GarbageWormEntity(type, world))
                    .dimensions(EntityDimensions.fixed(0.35f, 0.35f))
                    .trackRangeBlocks(96)
                    .trackedUpdateRate(3)
                    .build()
    );

    public static final EntityType<DaddyLongLegsEntity> DADDY_LONG_LEGS_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "daddy_long_legs"),
            FabricEntityTypeBuilder.<DaddyLongLegsEntity>create(SpawnGroup.MONSTER, (type, world) -> new DaddyLongLegsEntity(type, world))
                    .dimensions(EntityDimensions.fixed(1.45f, 1.55f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(1)
                    .build()
    );

    public static final EntityType<YellowOverseerEntity> YELLOW_OVERSEER_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "yellow_overseer"),
            FabricEntityTypeBuilder.<YellowOverseerEntity>create(SpawnGroup.CREATURE, YellowOverseerEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 0.65f))
                    .trackRangeBlocks(96)
                    .trackedUpdateRate(2)
                    .build()
    );

    public static final EntityType<FivePebblesEntity> FIVE_PEBBLES_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "five_pebbles"),
            FabricEntityTypeBuilder.<FivePebblesEntity>create(SpawnGroup.CREATURE, FivePebblesEntity::new)
                    .dimensions(EntityDimensions.fixed(0.65f, 1.55f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(1)
                    .build()
    );

    public static final EntityType<LooksToTheMoonEntity> LOOKS_TO_THE_MOON_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "looks_to_the_moon"),
            FabricEntityTypeBuilder.<LooksToTheMoonEntity>create(SpawnGroup.CREATURE, LooksToTheMoonEntity::new)
                    .dimensions(EntityDimensions.fixed(0.65f, 1.55f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(1)
                    .build()
    );

    public static final EntityType<EchoEntity> ECHO_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "echo"),
            FabricEntityTypeBuilder.<EchoEntity>create(SpawnGroup.CREATURE, EchoEntity::new)
                    .dimensions(EntityDimensions.fixed(6.0f, 18.0f))
                    .trackRangeBlocks(256)
                    .trackedUpdateRate(1)
                    .build()
    );

    public static final EntityType<PolePlantEntity> POLE_PLANT_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "pole_plant"),
            FabricEntityTypeBuilder.<PolePlantEntity>create(SpawnGroup.MONSTER, PolePlantEntity::new)
                    .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
                    .trackRangeBlocks(160)
                    .trackedUpdateRate(1)
                    .build()
    );

    public static final EntityType<PolePlantSegmentEntity> POLE_PLANT_SEGMENT_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "pole_plant_segment"),
            FabricEntityTypeBuilder.<PolePlantSegmentEntity>create(SpawnGroup.MISC, PolePlantSegmentEntity::new)
                    .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
                    .trackRangeBlocks(160)
                    .trackedUpdateRate(1)
                    .build()
    );


    @Override
    public void onInitialize() {
                

        // Register mod content
        ModBlocks.registerModBlocks();
        ModBlockEntities.registerBlockEntities();
        ModItems.registerModItems();
        ModParticles.register();
        ModSounds.registerModSounds();
        SelectionToolRegistry.register(CoralNeuronDefinition.INSTANCE);
        SelectionToolRegistry.register(ProjectionZoneDefinition.INSTANCE);

        // Register entity attributes
        FabricDefaultAttributeRegistry.register(STOWAWAY_BUG_ENTITY_TYPE, StowawayBugEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(CENTIPEDE_HEAD_ENTITY_TYPE, CentipedeSegmentEntity.createSegmentAttributes());
        FabricDefaultAttributeRegistry.register(CENTIPEDE_BODY_ENTITY_TYPE, CentipedeSegmentEntity.createSegmentAttributes());
        FabricDefaultAttributeRegistry.register(RED_CENTIPEDE_ENTITY_TYPE, RedCentipedeEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(CENTIPEDE_ENTITY_TYPE, CentipedeEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(CENTIWING_ENTITY_TYPE, CentiwingEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(SMALL_CENTIPEDE_ENTITY_TYPE, SmallCentipedeEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(SMALL_CENTIWING_ENTITY_TYPE, SmallCentiwingEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(SPIDER_ENTITY_TYPE, SpiderEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(GARBAGE_WORM_ENTITY_TYPE, GarbageWormEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(DADDY_LONG_LEGS_ENTITY_TYPE, DaddyLongLegsEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(YELLOW_OVERSEER_ENTITY_TYPE, YellowOverseerEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(FIVE_PEBBLES_ENTITY_TYPE, OracleEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(LOOKS_TO_THE_MOON_ENTITY_TYPE, OracleEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(ECHO_ENTITY_TYPE, EchoEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(POLE_PLANT_ENTITY_TYPE, PolePlantEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(POLE_PLANT_SEGMENT_ENTITY_TYPE, PolePlantSegmentEntity.createAttributes());

        // Register networking
        ModNetworking.register();
        
        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ProjectionZoneCommands.register(dispatcher);
            StarMatrixCommands.register(dispatcher);
            CoralNeuronCommands.register(dispatcher);
                        
        });


        // Wormgrass server-side grab / bury tick
        ServerTickEvents.END_WORLD_TICK.register(world ->
                dev.fouriis.karmagate.block.WormGrassManager.tick(world));


        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
                if (world.isClient) {
                        return ActionResult.PASS;
                }
                if (!player.getStackInHand(hand).isOf(ModItems.FUEL_HOSE_TOOL)) {
                        return ActionResult.PASS;
                }
                if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                        return ActionResult.PASS;
                }
                FuelHoseSessionManager.setFirstEndpoint(serverPlayer, pos, world.getRegistryKey());
                serverPlayer.sendMessage(Text.literal("Fuel hose start set to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), true);
                return ActionResult.SUCCESS;
        });

        // Server-side door interaction cancellation
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient && hand == Hand.MAIN_HAND) {
                net.minecraft.block.BlockState state = world.getBlockState(hitResult.getBlockPos());
                if (state.getBlock() instanceof DoorBlock) {
                    // Cancel door interaction on server side
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        

        LOGGER.info("Hello Fabric world!");
    }
}
