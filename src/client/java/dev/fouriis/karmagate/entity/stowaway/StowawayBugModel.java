package dev.fouriis.karmagate.entity.stowaway;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class StowawayBugModel extends GeoModel<StowawayBugEntity> {
    private static final Identifier MODEL     = Identifier.of(KarmaGateMod.MOD_ID, "geo/stowaway.geo.json");
    private static final Identifier TEXTURE   = Identifier.of(KarmaGateMod.MOD_ID, "textures/entity/stowaway.png");
    private static final Identifier ANIMATION = Identifier.of(KarmaGateMod.MOD_ID, "animations/stowaway.animation.json");

    @Override
    public Identifier getModelResource(StowawayBugEntity animatable) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(StowawayBugEntity animatable) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(StowawayBugEntity animatable) {
        return ANIMATION;
    }
}

