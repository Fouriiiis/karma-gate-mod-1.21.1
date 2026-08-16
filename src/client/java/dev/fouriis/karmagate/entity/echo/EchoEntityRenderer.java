package dev.fouriis.karmagate.entity.echo;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Renders the supplied animated model and queues its room-scale ghost effects. */
public final class EchoEntityRenderer extends GeoEntityRenderer<EchoEntity> {
    public EchoEntityRenderer(EntityRendererFactory.Context context) {
        super(context, new EchoModel());
        shadowRadius = 0.0f;
    }

    @Override
    public void render(EchoEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
        EchoGhostEffectSystem.queue(entity, tickDelta);
    }

    @Override
    public Identifier getTextureLocation(EchoEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/echo.png");
    }

    @Override
    public boolean shouldRender(EchoEntity entity, Frustum frustum,
                                double x, double y, double z) {
        // The effect is roughly 35 blocks across, substantially larger than
        // the entity hitbox and the animated model itself.
        return frustum.isVisible(entity.getBoundingBox().expand(18.0));
    }
}
