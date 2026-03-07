package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for the invisible CentipedeEntity controller.
 * Uses the head geometry as a placeholder (never actually rendered).
 */
public class CentipedeEntityModel extends GeoModel<CentipedeEntity> {

    @Override
    public Identifier getModelResource(CentipedeEntity entity) {
        return Identifier.of("karma-gate-mod", "geo/centipede_head.geo.json");
    }

    @Override
    public Identifier getTextureResource(CentipedeEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/centipede.png");
    }

    @Override
    public Identifier getAnimationResource(CentipedeEntity entity) {
        return Identifier.of("karma-gate-mod", "animations/centipede.animation.json");
    }
}
