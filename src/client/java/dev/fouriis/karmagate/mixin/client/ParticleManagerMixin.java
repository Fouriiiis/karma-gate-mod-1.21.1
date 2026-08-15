package dev.fouriis.karmagate.mixin.client;

import dev.fouriis.karmagate.particle.SteamSmokeSystem;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    @Inject(method = "renderParticles", at = @At("TAIL"))
    private void karmaGate$renderSteamSystem(
            LightmapTextureManager lightmapTextureManager,
            Camera camera,
            float tickDelta,
            CallbackInfo ci
    ) {
        SteamSmokeSystem.queueRender(tickDelta);
    }
}
