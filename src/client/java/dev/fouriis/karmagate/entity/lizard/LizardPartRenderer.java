package dev.fouriis.karmagate.entity.lizard;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

public class LizardPartRenderer extends EntityRenderer<LizardPartEntity> {
    private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");

    public LizardPartRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.0f;
    }

    @Override
    public void render(LizardPartEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
    }

    @Override
    public Identifier getTexture(LizardPartEntity entity) {
        return WHITE;
    }
}
