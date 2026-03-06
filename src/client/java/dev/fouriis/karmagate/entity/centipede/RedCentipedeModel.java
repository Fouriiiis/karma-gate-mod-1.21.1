package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for the invisible RedCentipedeEntity controller.
 * Uses the head geometry as a placeholder (never actually rendered).
 * Previously referenced red_centipede assets; now points at centipede geometry/texture.
 */
public class RedCentipedeModel extends GeoModel<RedCentipedeEntity> {

    @Override
    public Identifier getModelResource(RedCentipedeEntity entity) {
        return Identifier.of("karma-gate-mod", "geo/centipede_head.geo.json");
    }

    @Override
    public Identifier getTextureResource(RedCentipedeEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/centipede.png");
    }

    @Override
    public Identifier getAnimationResource(RedCentipedeEntity entity) {
        return Identifier.of("karma-gate-mod", "animations/centipede.animation.json");
    }
}
