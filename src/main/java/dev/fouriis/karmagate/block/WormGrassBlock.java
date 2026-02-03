package dev.fouriis.karmagate.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.block.ShapeContext;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Invisible gameplay anchor. All visuals are done by a custom world renderer.
 */
public final class WormGrassBlock extends Block {
    public WormGrassBlock(Settings settings) {
        super(settings);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
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
        // Notify client cache so render heights are recalculated promptly on placement (use reflection
        // to avoid a hard dependency on client-only classes from common code).
        if (world.isClient) {
            callClientCache("onBlockAdded", world, pos);
        }
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        // Restrict players to breaking only when holding the wormgrass block item itself.
        if (player != null) {
            boolean holding = player.getMainHandStack().getItem() == this.asItem()
                    || player.getOffHandStack().getItem() == this.asItem();
            if (!holding) {
                // Deny the break attempt.
                return state;
            }
        }

        BlockState s = super.onBreak(world, pos, state, player);

        // Notify client cache on client side so render heights recalc on break (use reflection)
        if (world.isClient) {
            callClientCache("onBlockRemoved", world, pos);
        }
        return s;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        // When replaced/removed/added by non-player action or networked change, update client cache as well
        if (world.isClient && state.getBlock() != newState.getBlock()) {
            if (newState.getBlock() == dev.fouriis.karmagate.block.ModBlocks.WORM_GRASS) {
                // New block placed here
                callClientCache("onBlockAdded", world, pos);
            } else if (state.getBlock() == dev.fouriis.karmagate.block.ModBlocks.WORM_GRASS) {
                // Block removed from here
                callClientCache("onBlockRemoved", world, pos);
            }
        }
    }

    @Override
    public net.minecraft.util.ActionResult onUse(BlockState state, World world, BlockPos pos,
                                                 PlayerEntity player, net.minecraft.util.hit.BlockHitResult hit) {
        // Allow "interaction" only when holding the wormgrass block itself.
        boolean holding = player.getMainHandStack().getItem() == this.asItem()
                || player.getOffHandStack().getItem() == this.asItem();
        if (!holding) return net.minecraft.util.ActionResult.PASS;
        return net.minecraft.util.ActionResult.SUCCESS;
    }

    // ------------------ Reflection helpers ------------------
    private static void callClientCache(String methodName, World world, BlockPos pos) {
        try {
            Class<?> cacheCls = Class.forName("dev.fouriis.karmagate.client.wormgrass.WormGrassRenderCache");
            Class<?> clientWorldCls = Class.forName("net.minecraft.client.world.ClientWorld");
            java.lang.reflect.Method m = cacheCls.getMethod(methodName, clientWorldCls, BlockPos.class);
            m.invoke(null, clientWorldCls.cast(world), pos);
        } catch (ClassNotFoundException ignored) {
            // Client classes not present in dedicated server / compile-time environment.
        } catch (Exception ex) {
            // Guard: don't crash the game if reflection fails.
            ex.printStackTrace();
        }
    }
}
