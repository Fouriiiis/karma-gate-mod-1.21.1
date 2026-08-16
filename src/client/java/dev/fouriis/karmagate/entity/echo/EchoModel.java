package dev.fouriis.karmagate.entity.echo;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public final class EchoModel extends GeoModel<EchoEntity> {
    private static final Identifier MODEL =
            Identifier.of(KarmaGateMod.MOD_ID, "geo/echo.geo.json");
    private static final Identifier TEXTURE =
            Identifier.of(KarmaGateMod.MOD_ID, "textures/entity/echo.png");
    private static final Identifier ANIMATION =
            Identifier.of(KarmaGateMod.MOD_ID, "animations/echo.animation.json");

    @Override
    public Identifier getModelResource(EchoEntity animatable) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(EchoEntity animatable) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(EchoEntity animatable) {
        return ANIMATION;
    }
}
