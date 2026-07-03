package dev.fouriis.karmagate.entity.overseer;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.world.World;

public class YellowOverseerEntity extends OverseerEntity {
    public YellowOverseerEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        setColorVariant(ColorVariant.YELLOW);
        setLimbCount(stableLimbCount(3, 5));
    }
}
