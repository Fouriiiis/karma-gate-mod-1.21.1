package dev.fouriis.karmagate.mixin.client;

import dev.fouriis.karmagate.client.AtcCloudVolumeRenderer;
import dev.fouriis.karmagate.client.AtcCowboyEasterEggRenderer;
import dev.fouriis.karmagate.client.DistantStructuresRenderer;
import dev.fouriis.karmagate.client.cubefold.CubeFoldEffect;
import dev.fouriis.karmagate.client.gridproject.GridProjectRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class GameRendererMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void karmaGate$renderBillboardsAfterShader(
        RenderTickCounter tickCounter, boolean tick,
        Camera camera, GameRenderer gameRenderer,
        LightmapTextureManager lightmapTextureManager,
        Matrix4f projectionMatrix, Matrix4f matrix4f2,
        CallbackInfo ci
    ) {
        if (CubeFoldEffect.shouldSuppressExtraWorldRender()) {
            return;
        }

        
        float tickDelta = tickCounter.getTickDelta(true);
        AtcCloudVolumeRenderer.renderDistantCloudLayer(tickDelta, camera);
        DistantStructuresRenderer.renderLate(tickDelta, camera);
        DistantStructuresRenderer.renderLightningLate(tickDelta, camera);
        AtcCowboyEasterEggRenderer.render(tickDelta, camera);
        AtcCloudVolumeRenderer.renderVolumeClouds(tickDelta, camera);
        GridProjectRenderer.renderLate(tickDelta, camera);
    }
}
