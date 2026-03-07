package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer for the invisible SmallCentiwingEntity controller.
 * This entity is never visually rendered — all rendering is done by segment renderers.
 */
public class SmallCentiwingRenderer extends GeoEntityRenderer<SmallCentiwingEntity> {

    public SmallCentiwingRenderer(EntityRendererFactory.Context context) {
        super(context, new SmallCentiwingModel());
        this.shadowRadius = 0f;
    }

    @Override
    public void render(SmallCentiwingEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        // Controller entity is invisible — do not render anything
    }

    @Override
    public Identifier getTextureLocation(SmallCentiwingEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/centipede.png");
    }
}
