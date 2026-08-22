package dev.fouriis.karmagate.block;

import com.mojang.serialization.MapCodec;
import dev.fouriis.karmagate.entity.rot.RotBlockEntity;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.block.ShapeContext;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;

/** Invisible placed-object anchor for a configurable DaddyCorruption zone. */
public final class RotBlock extends BlockWithEntity {
    public static final MapCodec<RotBlock> CODEC = createCodec(RotBlock::new);

    public RotBlock(Settings settings) {
        super(settings);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new RotBlockEntity(pos, state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView view, BlockPos pos, ShapeContext ctx) {
        return VoxelShapes.empty();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView view, BlockPos pos, ShapeContext ctx) {
        return VoxelShapes.empty();
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         net.minecraft.entity.LivingEntity placer, net.minecraft.item.ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (world.isClient) {
            callClientCache("onBlockAdded", world, pos);
        }
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        // Restrict to holding the rot block item to break
        if (player != null) {
            boolean holding = player.getMainHandStack().getItem() == this.asItem()
                    || player.getOffHandStack().getItem() == this.asItem();
            if (!holding) {
                return state;
            }
        }

        BlockState s = super.onBreak(world, pos, state, player);

        if (world.isClient) {
            callClientCache("onBlockRemoved", world, pos);
        }
        return s;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (world.isClient && state.getBlock() != newState.getBlock()) {
            if (newState.getBlock() == ModBlocks.ROT_BLOCK) {
                callClientCache("onBlockAdded", world, pos);
            } else if (state.getBlock() == ModBlocks.ROT_BLOCK) {
                callClientCache("onBlockRemoved", world, pos);
            }
        }
    }

    @Override
    public net.minecraft.util.ActionResult onUse(BlockState state, World world, BlockPos pos,
                                                 PlayerEntity player, net.minecraft.util.hit.BlockHitResult hit) {
        boolean holding = player.getMainHandStack().getItem() == this.asItem()
                || player.getOffHandStack().getItem() == this.asItem();
        if (!holding) return net.minecraft.util.ActionResult.PASS;
        return net.minecraft.util.ActionResult.SUCCESS;
    }

    // Reflection helper to call client cache without hard dependency
    private static void callClientCache(String methodName, World world, BlockPos pos) {
        try {
            Class<?> cacheCls = Class.forName("dev.fouriis.karmagate.client.rot.RotRenderCache");
            Class<?> clientWorldCls = Class.forName("net.minecraft.client.world.ClientWorld");
            java.lang.reflect.Method m = cacheCls.getMethod(methodName, clientWorldCls, BlockPos.class);
            m.invoke(null, clientWorldCls.cast(world), pos);
        } catch (ClassNotFoundException ignored) {
            // Client classes not present on server
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
