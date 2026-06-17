package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.karmagate.WaterfallBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * Invisible but interactable block that renders a client-side waterfall sheet.
 * Right click increases flow; shift-right click decreases flow.
 */
public class WaterfallBlock extends BlockWithEntity {
    public static final MapCodec<WaterfallBlock> CODEC = createCodec(WaterfallBlock::new);
    @Override public MapCodec<WaterfallBlock> getCodec() { return CODEC; }

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    public static final float FLOW_STEP = 0.1f;

    public WaterfallBlock(Settings settings) {
        super(settings.nonOpaque().noCollision());
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // Keep a selectable outline even though it's invisible.
        return VoxelShapes.fullCube();
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction facing = ctx.getHorizontalPlayerFacing();
        return getDefaultState().with(FACING, facing);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof WaterfallBlockEntity wf)) return ActionResult.PASS;

        float cur = wf.getFlow();
        float next;
        if (player.isSneaking()) {
            next = cur - FLOW_STEP;
        } else {
            next = cur + FLOW_STEP;
        }
        wf.setFlow(next);
        return ActionResult.SUCCESS;
    }

    /* -------- Block Entity wiring -------- */

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new WaterfallBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        // No ticking required server-side; client handles smooth propagation during rendering.
        return type == ModBlockEntities.WATERFALL_BLOCK_ENTITY ? WaterfallBlockEntity::clientTick : null;
    }
}
