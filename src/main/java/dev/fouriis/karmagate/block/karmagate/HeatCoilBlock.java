package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public class HeatCoilBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    /** Quantized C# heater heat consumed by the RWMC shaderpack (0 = no light). */
    public static final IntProperty LIGHT_LEVEL = IntProperty.of("light_level", 0, 31);
    // Expanded hitbox: extend 0.5 blocks (8 px) outward on X/Z, so cube spans -0.5 .. 1.5 in both axes.
    // createCuboidShape uses 1/16th block units. -8 -> -0.5 blocks, 24 -> 1.5 blocks.
    // Height kept full (0..16). Symmetric, so no rotation per facing needed.
    //private static final VoxelShape EXPANDED_SHAPE = Block.createCuboidShape(-8, 0, -8, 24, 16, 24);

    public HeatCoilBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(LIGHT_LEVEL, 0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(FACING, LIGHT_LEVEL);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // Face the player
        return getDefaultState()
                .with(FACING, ctx.getHorizontalPlayerFacing().getOpposite())
                .with(LIGHT_LEVEL, 0);
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
        return new HeatCoilBlockEntity(pos, state);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        // The visible heater is still rendered by its BER. The block model is
        // only a tiny shaderpack light-source plane hidden inside that mesh.
        return BlockRenderType.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World w, BlockState s, BlockEntityType<T> t) {
        return t == ModBlockEntities.HEAT_COIL_BLOCK_ENTITY
                ? (world, pos, state, be) -> ((HeatCoilBlockEntity) be).tick(world, pos, state)
                : null;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof HeatCoilBlockEntity coil) {
            coil.addHeat(0.10f); // +10%
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    public static final MapCodec<HeatCoilBlock> CODEC = createCodec(HeatCoilBlock::new);
    @Override public MapCodec<HeatCoilBlock> getCodec() { return CODEC; }
}
