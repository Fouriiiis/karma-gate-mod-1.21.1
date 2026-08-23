package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.block.karmagate.GateLightBlock;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Position marker used by {@link GateLightGroup} when it binds a gate's lights.
 * The on/off value lives entirely in the block state so terrain rebuilding and
 * RWMC's coloured-light scan see the change immediately.
 */
public class GateLightBlockEntity extends BlockEntity {
    public GateLightBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GATE_LIGHT_BLOCK_ENTITY, pos, state);
    }

    public void toggle() {
        setLit(!isLit());
    }

    public void setLit(boolean value) {
        if (world == null || world.isClient) return;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof GateLightBlock)) return;

        if (state.get(GateLightBlock.BROKEN)) {
            value = false;
        }

        if (state.get(GateLightBlock.LIT) != value) {
            world.setBlockState(pos, state.with(GateLightBlock.LIT, value), 3);
        }
    }

    public boolean isLit() {
        BlockState state = world == null ? getCachedState() : world.getBlockState(pos);
        return state.getBlock() instanceof GateLightBlock
                && state.get(GateLightBlock.LIT)
                && !state.get(GateLightBlock.BROKEN);
    }

    public void setGateLit(boolean value) {
        setLit(value);
    }
}
