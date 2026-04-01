package dev.fouriis.karmagate.mixin;


import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public class DisableMovementCheckMixin {

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void onPlayerMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;

    // Mirror vanilla thread handoff to avoid off-thread world/chunk mutations.
    NetworkThreadUtils.forceMainThread(packet, handler, handler.player.getServerWorld());

        handler.player.updatePositionAndAngles(
                packet.getX(handler.player.getX()),
                packet.getY(handler.player.getY()),
                packet.getZ(handler.player.getZ()),
                packet.getYaw(handler.player.getYaw()),
                packet.getPitch(handler.player.getPitch())
        );

        handler.player.getServerWorld().getChunkManager().updatePosition(handler.player);

        ci.cancel();
    }
}