package dev.fouriis.karmagate.item;

import dev.fouriis.karmagate.KarmaGateMod;
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

    public static final SpawnEggItem STOWAWAY_BUG_SPAWN_EGG = (SpawnEggItem) registerItem(
        "stowaway_bug_spawn_egg",
        new SpawnEggItem(
            KarmaGateMod.STOWAWAY_BUG_ENTITY_TYPE,
            0x7A5A3A,
            0x2A1F18,
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
        });

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of("minecraft", "spawn_eggs"))).register(entries -> {
            entries.add(STOWAWAY_BUG_SPAWN_EGG);
        });
    }
}
