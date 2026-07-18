package dev.fouriis.karmagate.entity.oracle;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.world.World;

public class FivePebblesEntity extends OracleEntity {
    public FivePebblesEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world, OracleId.FIVE_PEBBLES);
    }

    @Override
    protected OracleBehavior createBehavior() {
        return new FivePebblesOracleBehavior(this);
    }
}
