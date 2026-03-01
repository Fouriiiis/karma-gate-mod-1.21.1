package dev.fouriis.karmagate.entity.centipede;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model for the centipede body segment.
 * Uses the red_centipede_body.geo.json model and red_centipede.png texture.
 */
public class CentipedeBodyModel extends GeoModel<CentipedeBodyEntity> {

    private static final Identifier MODEL = Identifier.of(KarmaGateMod.MOD_ID, "geo/red_centipede_body.geo.json");
    private static final Identifier TEXTURE = Identifier.of(KarmaGateMod.MOD_ID, "textures/entity/red_centipede.png");
    private static final Identifier ANIMATION = Identifier.of(KarmaGateMod.MOD_ID, "animations/red_centipede.animation.json");

    @Override
    public Identifier getModelResource(CentipedeBodyEntity animatable) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(CentipedeBodyEntity animatable) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(CentipedeBodyEntity animatable) {
        return ANIMATION;
    }
}
