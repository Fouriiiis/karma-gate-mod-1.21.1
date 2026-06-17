package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class WaterStreamBlockEntity extends WaterfallBlockEntity {
    public WaterStreamBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WATER_STREAM_BLOCK_ENTITY, pos, state);
        setInitialFlow(0.0f);
    }

    public static void tick(World world, BlockPos pos, BlockState state, WaterStreamBlockEntity be) {
        WaterfallBlockEntity.clientTick(world, pos, state, be);
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putFloat("targetFlow", getFlow());
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        if (!nbt.contains("flow") && nbt.contains("targetFlow")) {
            setInitialFlow(nbt.getFloat("targetFlow"));
        }
    }

    public void setTargetFlow(float f) {
        setFlow(f);
    }
}
