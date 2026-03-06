package dev.fouriis.karmagate.entity.centipede;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model for the centipede head segment.
 * Uses the centipede_head.geo.json model and centipede.png texture.
 * No animations — orientation is driven by chain physics.
 */
public class CentipedeHeadModel extends GeoModel<CentipedeHeadEntity> {

    private static final Identifier MODEL = Identifier.of(KarmaGateMod.MOD_ID, "geo/centipede_head.geo.json");
    private static final Identifier TEXTURE = Identifier.of(KarmaGateMod.MOD_ID, "textures/entity/centipede.png");
    // No animation file needed — we use an empty placeholder
    private static final Identifier ANIMATION = Identifier.of(KarmaGateMod.MOD_ID, "animations/centipede.animation.json");

    @Override
    public Identifier getModelResource(CentipedeHeadEntity animatable) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(CentipedeHeadEntity animatable) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(CentipedeHeadEntity animatable) {
        return ANIMATION;
    }
}
