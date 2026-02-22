package dev.fouriis.karmagate.block;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.karmagate.KarmaGateBlock;
import dev.fouriis.karmagate.block.karmagate.KarmaGatePartBlock;
import dev.fouriis.karmagate.block.karmagate.SteamEmitterBlock;
import dev.fouriis.karmagate.block.karmagate.WaterStreamBlock;
import dev.fouriis.karmagate.block.karmagate.WaterfallBlock;
import dev.fouriis.karmagate.block.karmagate.GateLightBlock;
import dev.fouriis.karmagate.block.karmagate.HeatCoilBlock;
import dev.fouriis.karmagate.block.shelterdoor.ShelterDoorBlock;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import dev.fouriis.karmagate.item.KarmaGateItem;
import dev.fouriis.karmagate.item.HeatCoilItem;
import dev.fouriis.karmagate.item.GateLightItem;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.block.MapColor;

public class ModBlocks {
    

    public static final Block KARMA_GATE = registerBlock("karma_gate",
        new KarmaGateBlock(Block.Settings.create()
            .strength(4.0f)
            .sounds(BlockSoundGroup.STONE)
            .nonOpaque()));

    public static final Block GATE_LIGHT = registerBlock("gate_light",
        new GateLightBlock(Block.Settings.create()
            .strength(1.0f)
            .sounds(BlockSoundGroup.GLASS)
            .nonOpaque()));
    
    public static final Block HEAT_COIL = registerBlock("heat_coil",
        new HeatCoilBlock(Block.Settings.create()
            .strength(3.0f)
            .sounds(BlockSoundGroup.METAL)
            .nonOpaque()));

    public static final Block WATER_STREAM = registerBlock("water_stream",
        new WaterStreamBlock(Block.Settings.create()
            .strength(3.0f)
            .sounds(BlockSoundGroup.METAL)
            .nonOpaque()));

    public static final Block WATERFALL = registerBlock("waterfall",
        new WaterfallBlock(Block.Settings.create()
            .strength(0.5f)
            .sounds(BlockSoundGroup.WET_GRASS)
            .nonOpaque()));
    public static final Block STEAM_EMITTER = registerBlock("steam_emitter",
        new SteamEmitterBlock(Block.Settings.create()
            .strength(3.0f)
            .sounds(BlockSoundGroup.METAL)
            .nonOpaque()));

    public static final Block SHELTER_DOOR = registerBlock("shelter_door",
        new ShelterDoorBlock(Block.Settings.create()
            .strength(4.0f)
            .sounds(BlockSoundGroup.METAL)
            .nonOpaque()));

    public static final Block KARMA_GATE_PART = Registry.register(
            Registries.BLOCK,
            Identifier.of(KarmaGateMod.MOD_ID, "karma_gate_part"),
            new KarmaGatePartBlock(Block.Settings.create().strength(-1.0F, 3600000.0F).dropsNothing().nonOpaque().suffocates((state, world, pos) -> false))
    );

    public static final Block HOLOGRAM_PROJECTOR = registerBlock("hologram_projector",
        new dev.fouriis.karmagate.block.hologram.HologramProjectorBlock(Block.Settings.create()
            .strength(3.0f)
            .sounds(BlockSoundGroup.METAL)
            .nonOpaque()));

    // Worm grass: invisible gameplay block (visuals handled by client renderer)
    public static final Block WORM_GRASS = registerBlock("worm_grass",
        new WormGrassBlock(Block.Settings.create()
            .mapColor(MapColor.CLEAR)
            .noCollision()
            .nonOpaque()
            .strength(0.0f)
            .sounds(BlockSoundGroup.GRASS)
            .dropsNothing()));

    // Rot (Daddy Corruption): invisible block that renders flesh clusters on adjacent surfaces
    public static final Block ROT_BLOCK = registerBlock("rot_block",
        new RotBlock(Block.Settings.create()
            .mapColor(MapColor.CLEAR)
            .noCollision()
            .nonOpaque()
            .strength(0.0f)
            .sounds(BlockSoundGroup.SLIME)
            .dropsNothing()));
    

    private static Block registerBlock(String name, Block block) {
        registerBlockItem(name, block);
        return Registry.register(Registries.BLOCK, Identifier.of(KarmaGateMod.MOD_ID, name), block);
    }

    private static Item registerBlockItem(String name, Block block) {
        Item item;
        if (block instanceof KarmaGateBlock) {
            item = new KarmaGateItem(block, new Item.Settings());
        } else if (name.equals("heat_coil")) {
            item = new HeatCoilItem(block, new Item.Settings());
        } else if (name.equals("gate_light")) {
            item = new GateLightItem(block, new Item.Settings());
        } else {
            item = new BlockItem(block, new Item.Settings());
        }
        return Registry.register(Registries.ITEM, Identifier.of(KarmaGateMod.MOD_ID, name), item);
    }
    



    public static void registerModBlocks() {
        KarmaGateMod.LOGGER.info("Registering ModBlocks for " + KarmaGateMod.MOD_ID);

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of("minecraft", "building_blocks"))).register(entries -> {
            entries.add(KARMA_GATE);
            entries.add(GATE_LIGHT);
            entries.add(HEAT_COIL);
            entries.add(WATER_STREAM);
            entries.add(WATERFALL);
            entries.add(STEAM_EMITTER);
            entries.add(HOLOGRAM_PROJECTOR);
            entries.add(SHELTER_DOOR);
            entries.add(WORM_GRASS);
            entries.add(ROT_BLOCK);
        });
    }
}
