package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;
import dev.fouriis.karmagate.entity.karmagate.GateLightBlockEntity;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public class GateLightBlock extends BlockWithEntity {
    public static final MapCodec<GateLightBlock> CODEC = createCodec(GateLightBlock::new);
    @Override public MapCodec<GateLightBlock> getCodec() { return CODEC; }

    // Use all 4 horizontal facings
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    // Emits light when true
    public static final BooleanProperty LIT = Properties.LIT;
    // When true, the light is considered broken and must never turn on
    public static final BooleanProperty BROKEN = BooleanProperty.of("broken");

    public GateLightBlock(Settings settings) {
        // RWMC supplies the actual coloured lighting. Vanilla luminance would
        // add a white light on top of it, so this block deliberately emits none.
        super(settings);
        setDefaultState(getStateManager().getDefaultState()
            .with(FACING, Direction.NORTH)
            .with(LIT, false)
            .with(BROKEN, false));
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new GateLightBlockEntity(pos, state);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        // The lit model is a single source plane consumed by the shaderpack.
        return BlockRenderType.MODEL;
    }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(FACING, LIT, BROKEN);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // Face *towards* the player; start unlit
        return getDefaultState()
            .with(FACING, ctx.getHorizontalPlayerFacing().getOpposite())
            .with(LIT, false);
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
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;

        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof GateLightBlockEntity light) {
            light.toggle();
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }
}
