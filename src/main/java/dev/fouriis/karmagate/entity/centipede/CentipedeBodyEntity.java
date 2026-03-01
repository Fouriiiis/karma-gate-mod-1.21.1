package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;

/**
 * A centipede body segment. These form the chain between the two heads.
 * Body segments have shells that absorb damage and can break off when hit hard.
 *
 * In Rain World's C#, each body chunk has a CentiState.shells[i] that tracks
 * whether the shell is intact. When hit with enough force, the shell falls off
 * and subsequent hits deal full damage to that segment.
 */
public class CentipedeBodyEntity extends CentipedeSegmentEntity {

    public CentipedeBodyEntity(EntityType<? extends MobEntity> type, World world) {
        super(type, world);
    }
}
