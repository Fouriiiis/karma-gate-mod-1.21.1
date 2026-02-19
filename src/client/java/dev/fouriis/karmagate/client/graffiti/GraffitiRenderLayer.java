package dev.fouriis.karmagate.client.graffiti;

import net.minecraft.client.render.*;
import net.minecraft.util.Identifier;

public final class GraffitiRenderLayer {
    public static RenderLayer get(Identifier texture) {
        // Use entity translucent for proper color rendering without harsh lighting
        return RenderLayer.getEntityTranslucentCull(texture);
    }
}
