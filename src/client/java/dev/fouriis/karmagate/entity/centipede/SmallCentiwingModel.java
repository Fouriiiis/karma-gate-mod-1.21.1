package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for the invisible SmallCentiwingEntity controller.
 * Uses the head geometry as a placeholder (never actually rendered).
 */
public class SmallCentiwingModel extends GeoModel<SmallCentiwingEntity> {

    @Override
    public Identifier getModelResource(SmallCentiwingEntity entity) {
        return Identifier.of("karma-gate-mod", "geo/centipede_head.geo.json");
    }

    @Override
    public Identifier getTextureResource(SmallCentiwingEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/centipede.png");
    }

    @Override
    public Identifier getAnimationResource(SmallCentiwingEntity entity) {
        return Identifier.of("karma-gate-mod", "animations/centipede.animation.json");
    }
}
