package dev.fouriis.karmagate.entity.monsterkelp;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.util.Identifier;

/** Collision children are intentionally invisible; the controller renders one coherent plant. */
public final class MonsterKelpSegmentRenderer extends EntityRenderer<MonsterKelpSegmentEntity> {
    public MonsterKelpSegmentRenderer(EntityRendererFactory.Context context) {
        super(context);
        shadowRadius = 0.0f;
    }

    @Override
    public Identifier getTexture(MonsterKelpSegmentEntity entity) {
        return Identifier.ofVanilla("textures/misc/white.png");
    }
}
