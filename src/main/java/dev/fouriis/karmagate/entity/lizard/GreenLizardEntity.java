package dev.fouriis.karmagate.entity.lizard;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.World;

public class GreenLizardEntity extends AbstractLizardEntity {
    public GreenLizardEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world, LizardBreedProfile.green());
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return AbstractLizardEntity.createBaseAttributes(LizardBreedProfile.green());
    }
}
