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

    public static final Item ROOM_TOOL = registerItem("room_tool",
        new RoomToolItem(new Item.Settings().maxCount(1)));

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

    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of(KarmaGateMod.MOD_ID, name), item);
    }
    
    public static void registerModItems() {
        KarmaGateMod.LOGGER.info("Registering ModItems for " + KarmaGateMod.MOD_ID);
        
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of("minecraft", "tools"))).register(entries -> {
            entries.add(GRAFFITI_PLACER);
            entries.add(ROOM_TOOL);
            entries.add(FUEL_HOSE_TOOL);
        });

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of("minecraft", "spawn_eggs"))).register(entries -> {
            entries.add(STOWAWAY_BUG_SPAWN_EGG);
            entries.add(SPIDER_SPAWN_EGG);
            entries.add(DADDY_LONG_LEGS_SPAWN_EGG);
        });
    }
}
