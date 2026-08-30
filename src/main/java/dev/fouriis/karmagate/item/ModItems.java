package dev.fouriis.karmagate.item;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.spider.SpiderSpawnEggItem;
import dev.fouriis.karmagate.hose.FuelHoseToolItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public class ModItems {
    
    public static final Item GRAFFITI_PLACER = registerItem("graffiti_placer",
        new GraffitiItem(new Item.Settings().maxCount(64)));

    public static final Item FUEL_HOSE_TOOL = registerItem("fuel_hose_tool",
        new FuelHoseToolItem(new Item.Settings().maxCount(1)));

    public static final SpawnEggItem STOWAWAY_BUG_SPAWN_EGG = (SpawnEggItem) registerItem(
        "stowaway_bug_spawn_egg",
        new SpawnEggItem(
            KarmaGateMod.STOWAWAY_BUG_ENTITY_TYPE,
            0x7A5A3A,
            0x2A1F18,
            new Item.Settings().maxCount(64)
        )
    );

    // Spider spawn egg — spawns a whole flock of spiders
    public static final SpiderSpawnEggItem SPIDER_SPAWN_EGG = (SpiderSpawnEggItem) registerItem(
        "spider_spawn_egg",
        new SpiderSpawnEggItem(
            KarmaGateMod.SPIDER_ENTITY_TYPE,
            0x1A1510,  // dark brownish-black (body color)
            0x0F0D0A,  // very dark (leg color)
            new Item.Settings().maxCount(64)
        )
    );

    public static final SpawnEggItem DADDY_LONG_LEGS_SPAWN_EGG = (SpawnEggItem) registerItem(
        "daddy_long_legs_spawn_egg",
        new SpawnEggItem(
            KarmaGateMod.DADDY_LONG_LEGS_ENTITY_TYPE,
            0x0D0F16,
            0x1E83FF,
            new Item.Settings().maxCount(64)
        )
    );

    public static final SpawnEggItem YELLOW_OVERSEER_SPAWN_EGG = (SpawnEggItem) registerItem(
        "yellow_overseer_spawn_egg",
        new SpawnEggItem(
            KarmaGateMod.YELLOW_OVERSEER_ENTITY_TYPE,
            0xFFE84A,
            0x3A3210,
            new Item.Settings().maxCount(64)
        )
    );

    public static final SpawnEggItem FIVE_PEBBLES_SPAWN_EGG = (SpawnEggItem) registerItem(
        "five_pebbles_spawn_egg",
        new SpawnEggItem(
            KarmaGateMod.FIVE_PEBBLES_ENTITY_TYPE,
            0xFF66CB,
            0x17121F,
            new Item.Settings().maxCount(64)
        )
    );

    public static final SpawnEggItem LOOKS_TO_THE_MOON_SPAWN_EGG = (SpawnEggItem) registerItem(
        "looks_to_the_moon_spawn_egg",
        new SpawnEggItem(
            KarmaGateMod.LOOKS_TO_THE_MOON_ENTITY_TYPE,
            0x1B4557,
            0x7D9A8D,
            new Item.Settings().maxCount(64)
        )
    );

    public static final SpawnEggItem POLE_PLANT_SPAWN_EGG = (SpawnEggItem) registerItem(
        "pole_plant_spawn_egg",
        new SpawnEggItem(
            KarmaGateMod.POLE_PLANT_ENTITY_TYPE,
            0x070809,
            0x404F38,
            new Item.Settings().maxCount(64)
        )
    );

    public static final SpawnEggItem MONSTER_KELP_SPAWN_EGG = (SpawnEggItem) registerItem(
        "monster_kelp_spawn_egg",
        new SpawnEggItem(
            KarmaGateMod.MONSTER_KELP_ENTITY_TYPE,
            0x080A0B,
            0xD71932,
            new Item.Settings().maxCount(64)
        )
    );

    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of(KarmaGateMod.MOD_ID, name), item);
    }
    
    public static void registerModItems() {
        KarmaGateMod.LOGGER.info("Registering ModItems for " + KarmaGateMod.MOD_ID);
        
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of("minecraft", "tools"))).register(entries -> {
            entries.add(GRAFFITI_PLACER);
            entries.add(FUEL_HOSE_TOOL);
        });

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of("minecraft", "spawn_eggs"))).register(entries -> {
            entries.add(STOWAWAY_BUG_SPAWN_EGG);
            entries.add(SPIDER_SPAWN_EGG);
            entries.add(DADDY_LONG_LEGS_SPAWN_EGG);
            entries.add(YELLOW_OVERSEER_SPAWN_EGG);
            entries.add(FIVE_PEBBLES_SPAWN_EGG);
            entries.add(LOOKS_TO_THE_MOON_SPAWN_EGG);
            entries.add(POLE_PLANT_SPAWN_EGG);
            entries.add(MONSTER_KELP_SPAWN_EGG);
        });
    }
}
