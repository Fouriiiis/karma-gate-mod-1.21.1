package dev.fouriis.karmagate.entity;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.block.karmagate.KarmaGatePartBlock;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.WaterStreamBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.WaterfallBlockEntity;
import dev.fouriis.karmagate.entity.shelterdoor.ShelterDoorBlockEntity;
import dev.fouriis.karmagate.entity.hologram.HologramProjectorBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.GateLightBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.SteamEmitterBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.BatteryMeterBlockEntity;
import dev.fouriis.karmagate.entity.gravity.GravityDisruptorBlockEntity;
import dev.fouriis.karmagate.entity.rot.RotBlockEntity;
import dev.fouriis.karmagate.entity.coralbrain.WallMyceliaBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlockEntities {


    public static final BlockEntityType<KarmaGateBlockEntity> KARMA_GATE_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "karma_gate_block_entity"),
            FabricBlockEntityTypeBuilder.create(KarmaGateBlockEntity::new, ModBlocks.KARMA_GATE).build());

    public static final BlockEntityType<ShelterDoorBlockEntity> SHELTER_DOOR_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "shelter_door_block_entity"),
            FabricBlockEntityTypeBuilder.create(ShelterDoorBlockEntity::new, ModBlocks.SHELTER_DOOR).build());

    public static final BlockEntityType<GateLightBlockEntity> GATE_LIGHT_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "gate_light_block_entity"),
            FabricBlockEntityTypeBuilder.create(GateLightBlockEntity::new, ModBlocks.GATE_LIGHT).build());

    public static final BlockEntityType<HeatCoilBlockEntity> HEAT_COIL_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "heat_coil_block_entity"),
            FabricBlockEntityTypeBuilder.create(HeatCoilBlockEntity::new, ModBlocks.HEAT_COIL).build());

    public static final BlockEntityType<WaterStreamBlockEntity> WATER_STREAM_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "water_stream_block_entity"),
            FabricBlockEntityTypeBuilder.create(WaterStreamBlockEntity::new, ModBlocks.WATER_STREAM).build());

    public static final BlockEntityType<WaterfallBlockEntity> WATERFALL_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "waterfall_block_entity"),
            FabricBlockEntityTypeBuilder.create(WaterfallBlockEntity::new, ModBlocks.WATERFALL).build());

    public static final BlockEntityType<KarmaGatePartBlock.PartBE> KARMA_GATE_PART_BE =
        Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(KarmaGateMod.MOD_ID, "karma_gate_part_be"),
            FabricBlockEntityTypeBuilder.create(KarmaGatePartBlock.PartBE::new, ModBlocks.KARMA_GATE_PART).build()
        );
    public static final BlockEntityType<SteamEmitterBlockEntity> STEAM_EMITTER_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "steam_emitter_block_entity"),
            FabricBlockEntityTypeBuilder.create(SteamEmitterBlockEntity::new, ModBlocks.STEAM_EMITTER).build());

    public static final BlockEntityType<BatteryMeterBlockEntity> BATTERY_METER_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "battery_meter_block_entity"),
            FabricBlockEntityTypeBuilder.create(BatteryMeterBlockEntity::new, ModBlocks.BATTERY_METER).build());

    public static final BlockEntityType<GravityDisruptorBlockEntity> GRAVITY_DISRUPTOR_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "gravity_disruptor_block_entity"),
            FabricBlockEntityTypeBuilder.create(GravityDisruptorBlockEntity::new, ModBlocks.GRAVITY_DISRUPTOR).build());

    public static final BlockEntityType<HologramProjectorBlockEntity> HOLOGRAM_PROJECTOR =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "hologram_projector"),
            FabricBlockEntityTypeBuilder.create(HologramProjectorBlockEntity::new, ModBlocks.HOLOGRAM_PROJECTOR).build());

    public static final BlockEntityType<RotBlockEntity> ROT_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "rot_block_entity"),
            FabricBlockEntityTypeBuilder.create(RotBlockEntity::new, ModBlocks.ROT_BLOCK).build());

    public static final BlockEntityType<WallMyceliaBlockEntity> WALL_MYCELIA_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "wall_mycelia_block_entity"),
            FabricBlockEntityTypeBuilder.create(WallMyceliaBlockEntity::new, ModBlocks.WALL_MYCELIA).build());

    public static void registerBlockEntities() {
        KarmaGateMod.LOGGER.info("Registering Block Entities for " + KarmaGateMod.MOD_ID);
    }
}
