package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for the invisible RedCentipedeEntity controller.
 * Uses the head geometry as a placeholder (never actually rendered).
 */
public class RedCentipedeModel extends GeoModel<RedCentipedeEntity> {

    @Override
    public Identifier getModelResource(RedCentipedeEntity entity) {
        return Identifier.of("karma-gate-mod", "geo/red_centipede_head.geo.json");
    }

    @Override
    public Identifier getTextureResource(RedCentipedeEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/red_centipede.png");
    }

    @Override
    public Identifier getAnimationResource(RedCentipedeEntity entity) {
        return Identifier.of("karma-gate-mod", "animations/red_centipede.animation.json");
    }
}
