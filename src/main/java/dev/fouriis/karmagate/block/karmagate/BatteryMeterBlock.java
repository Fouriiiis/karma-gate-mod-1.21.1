package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;
import dev.fouriis.karmagate.entity.karmagate.BatteryMeterBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Placement anchor for RegionGateGraphics' electric-gate battery meter.
 *
 * <p>The source meter is a single procedural pixel sprite rather than a piece
 * of gate machinery. Consequently the block itself is invisible; its centre
 * and facing define the centre and plane of the 21-block-wide rendered bar.</p>
 */
public final class BatteryMeterBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final MapCodec<BatteryMeterBlock> CODEC = createCodec(BatteryMeterBlock::new);

    public BatteryMeterBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<BatteryMeterBlock> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new BatteryMeterBlockEntity(pos, state);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        // The oversized casing is drawn by the block-entity renderer so its
        // 22-block span is not culled with the anchor block's chunk section.
        return BlockRenderType.INVISIBLE;
    }
}
