package dev.fouriis.karmagate.mixin.client;

import dev.fouriis.karmagate.item.ModItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents block breaking when the player is holding the graffiti spray can.
 * attackBlock  – the initial punch that begins breaking
 * updateBlockBreakingProgress – the per-tick progress sent to the server
 * continueDestroyBlock        – the local animation tick
 */
@Mixin(ClientPlayerInteractionManager.class)
public class GraffitiBlockBreakMixin {

    @Unique
    private static boolean isHoldingGraffiti() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;
        return client.player.getMainHandStack().isOf(ModItems.GRAFFITI_PLACER) ||
               client.player.getOffHandStack().isOf(ModItems.GRAFFITI_PLACER);
    }

    /** Cancels the initial block-break attempt. */
    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void cancelAttackBlock(BlockPos pos, Direction direction,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (isHoldingGraffiti()) cir.setReturnValue(false);
    }

    /** Cancels the per-tick progress update sent to the server. */
    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void cancelUpdateBreakingProgress(BlockPos pos, Direction direction,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (isHoldingGraffiti()) cir.setReturnValue(false);
    }
}
