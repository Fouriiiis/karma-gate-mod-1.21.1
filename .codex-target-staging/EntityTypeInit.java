package pencil.mechanics.init;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.resource.featuretoggle.ToggleableFeature;
import net.minecraft.util.TypeFilter;
import org.jetbrains.annotations.Nullable;
import pencil.mechanics.entity.*;


public class EntityTypeInit<T extends Entity> implements ToggleableFeature, TypeFilter<Entity, T> {


    private static <T extends Entity> EntityType<T> register(String id, EntityType.Builder<T> type) {
        return Registry.register(Registries.ENTITY_TYPE, id, type.build(id));
    }

    public static void load() {
        FabricDefaultAttributeRegistry.register(ECHO, EchoEntity.createAttributes());
    }

    public static final EntityType<GreenLizardEntity> GREEN_LIZARD = register(
            "green_lizard",
            EntityType.Builder.<GreenLizardEntity>create(GreenLizardEntity::new, SpawnGroup.MONSTER).setDimensions(0.9F, 0.9F).maxTrackingRange(4)
    );

    public static final EntityType<PinkLizardEntity> PINK_LIZARD = register(
            "pink_lizard",
            EntityType.Builder.<PinkLizardEntity>create(PinkLizardEntity::new, SpawnGroup.MONSTER).setDimensions(0.9F, 0.9F).maxTrackingRange(4)
    );

    public static final EntityType<NoodleflyEntity> NOODLEFLY_ENTITY = register(
            "noodlefly_entity",
            EntityType.Builder.<NoodleflyEntity>create(NoodleflyEntity::new, SpawnGroup.MONSTER).setDimensions(0.9F, 0.9F).maxTrackingRange(4)
    );

    public static final EntityType<JetfishEntity> JETFISH = register(
            "jetfish",
            EntityType.Builder.<JetfishEntity>create(JetfishEntity::new, SpawnGroup.MONSTER).setDimensions(0.9F, 0.9F).maxTrackingRange(4)
    );

    public static final EntityType<PoleplantEntity> POLEPLANT = register(
            "poleplant",
            EntityType.Builder.<PoleplantEntity>create(PoleplantEntity::new, SpawnGroup.MONSTER).setDimensions(0.9F, 0.9F).maxTrackingRange(4)
    );

    public static final EntityType<DropwigEntity> DROPWIG = register(
            "dropwig",
            EntityType.Builder.<DropwigEntity>create(DropwigEntity::new, SpawnGroup.MONSTER).setDimensions(0.9F, 0.9F).maxTrackingRange(4)
    );

    public static final EntityType<FivePebblesEntity> FIVEPEBBLES = register(
            "fivepebbles",
            EntityType.Builder.<FivePebblesEntity>create(FivePebblesEntity::new, SpawnGroup.MONSTER).setDimensions(0.9F, 0.9F).maxTrackingRange(4)
    );

    public static final EntityType<JellyfishEntity> JELLYFISH = register(
            "jellyfish",
            EntityType.Builder.<JellyfishEntity>create(JellyfishEntity::new, SpawnGroup.MONSTER).setDimensions(0.9F, 0.9F).maxTrackingRange(4)
    );

    public static final EntityType<EchoEntity> ECHO = register(
            "echo",
            EntityType.Builder.<EchoEntity>create(EchoEntity::new, SpawnGroup.CREATURE)
                    .setDimensions(1.0F, 2.0F)
                    .maxTrackingRange(10)
                    .trackingTickInterval(20)
    );

    public static final EntityType<SpearEntity> SPEAR = register(
            "spear",
            EntityType.Builder.<SpearEntity>create(SpearEntity::new, SpawnGroup.MISC).setDimensions(0.5F, 0.5F).maxTrackingRange(4).trackingTickInterval(20)
    );

    @Override
    public FeatureSet getRequiredFeatures() {
        return null;
    }

    @Nullable
    @Override
    public T downcast(Entity obj) {
        return null;
    }

    @Override
    public Class<? extends Entity> getBaseClass() {
        return null;
    }

}
