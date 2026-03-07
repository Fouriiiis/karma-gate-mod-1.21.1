package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for the invisible CentiwingEntity controller.
 * Uses the head geometry as a placeholder (never actually rendered).
 */
public class CentiwingEntityModel extends GeoModel<CentiwingEntity> {

    @Override
    public Identifier getModelResource(CentiwingEntity entity) {
        return Identifier.of("karma-gate-mod", "geo/centipede_head.geo.json");
    }

    @Override
    public Identifier getTextureResource(CentiwingEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/centipede.png");
    }

    @Override
    public Identifier getAnimationResource(CentiwingEntity entity) {
        return Identifier.of("karma-gate-mod", "animations/centipede.animation.json");
    }
}
