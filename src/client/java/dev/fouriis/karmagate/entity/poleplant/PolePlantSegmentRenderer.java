package dev.fouriis.karmagate.entity.poleplant;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.util.Identifier;

/** Collision children are intentionally invisible; the parent draws the stem. */
public final class PolePlantSegmentRenderer extends EntityRenderer<PolePlantSegmentEntity> {
    public PolePlantSegmentRenderer(EntityRendererFactory.Context context) {
        super(context);
        shadowRadius = 0.0f;
    }

    @Override
    public Identifier getTexture(PolePlantSegmentEntity entity) {
        return Identifier.ofVanilla("textures/misc/white.png");
    }
}
