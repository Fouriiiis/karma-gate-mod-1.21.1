package dev.fouriis.karmagate.mixin.client;

import dev.fouriis.karmagate.client.weather.RainCameraShakeController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void moveBy(float x, float y, float z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void karmaGate$applyRainCameraShake(BlockView area,
                                                Entity focusedEntity,
                                                boolean thirdPerson,
                                                boolean inverseView,
                                                float tickDelta,
                                                CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }

        Camera self = (Camera) (Object) this;
        RainCameraShakeController.Sample sample = RainCameraShakeController.INSTANCE.sample(tickDelta);
        if (sample == RainCameraShakeController.Sample.ZERO) {
            return;
        }

        moveBy(sample.localX(), sample.localY(), sample.localZ());
        setRotation(self.getYaw() + sample.yaw(), self.getPitch() + sample.pitch());
    }
}
