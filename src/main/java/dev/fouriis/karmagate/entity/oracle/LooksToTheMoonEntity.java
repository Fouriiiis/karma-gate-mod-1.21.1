package dev.fouriis.karmagate.entity.oracle;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.world.World;

public class LooksToTheMoonEntity extends OracleEntity {
    public LooksToTheMoonEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world, OracleId.LOOKS_TO_THE_MOON);
    }

    @Override
    protected OracleBehavior createBehavior() {
        return new LooksToTheMoonOracleBehavior(this);
    }
}
