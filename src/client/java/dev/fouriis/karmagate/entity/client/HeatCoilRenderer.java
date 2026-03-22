package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ColorHelper;

public class HeatCoilRenderer extends GeoBlockRenderer<HeatCoilBlockEntity> {
    public HeatCoilRenderer(BlockEntityRendererFactory.Context ctx) {
        super(new HeatCoilModel());
        addRenderLayer(new GlowLayer(this));
    }

    private static final class GlowLayer extends GeoRenderLayer<HeatCoilBlockEntity> {
        GlowLayer(HeatCoilRenderer parent) { super(parent); }

        @Override
        public void render(MatrixStack matrices, HeatCoilBlockEntity animatable, BakedGeoModel bakedModel, RenderLayer baseLayer, VertexConsumerProvider bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
            float heat = animatable.getVisualHeat();

            // Clamp heat and apply a small dead zone so zero/near-zero shows only the base texture
            float h = Math.max(0f, Math.min(1f, heat));
            float threshold = 0.05f; // no emissive under 5% heat
            if (h <= threshold) return;

            // Remap remaining range [threshold,1] -> [0,1] so blending starts from the coolest emissive
            float t = (h - threshold) / (1f - threshold);

            // Map to [0, 3] across 4 textures (1..4)
            float p = t * 3f; // 0..3
            int i0 = (int)Math.floor(p);
            int i1 = Math.min(i0 + 1, 3);
            float frac = p - i0; // blend toward i1

            // Base alpha: emulate cooling metal — quicker drop from hot, lingering tail near cool
            // Blend between a fast-drop quadratic and a lingering square-root tail
            float aFast = t * t;                // drops quicker at high heat
            float aTail = (float)Math.sqrt(t);  // lingers near low heat
            float baseAlpha = Math.min(1f, 0.35f * aFast + 0.65f * aTail);

            // Helper to select the right emissive texture
            java.util.function.IntFunction<net.minecraft.util.Identifier> tex = idx -> switch (idx) {
                case 0 -> HeatCoilModel.EMISSIVE_1;
                case 1 -> HeatCoilModel.EMISSIVE_2;
                case 2 -> HeatCoilModel.EMISSIVE_3;
                default -> HeatCoilModel.EMISSIVE_4;
            };

            // Fullbright for glow
            int fullBright = 0xF000F0;

            // Draw the two nearest textures with weighted alpha for a smooth transition
            float w0 = (i0 == i1) ? 1f : (1f - frac);
            float w1 = (i0 == i1) ? 0f : frac;

            if (w0 > 0f) {
                int a0 = (int)(Math.min(1f, baseAlpha * w0) * 255f);
                int argb0 = ColorHelper.Argb.getArgb(a0, 255, 255, 255);
                RenderLayer layer0 = RenderLayer.getEyes(tex.apply(i0));
                VertexConsumer buf0 = bufferSource.getBuffer(layer0);
                getRenderer().reRender(
                        bakedModel, matrices, bufferSource, animatable,
                        layer0, buf0, partialTick,
                        fullBright, packedOverlay, argb0
                );
            }

            if (w1 > 0f) {
                int a1 = (int)(Math.min(1f, baseAlpha * w1) * 255f);
                int argb1 = ColorHelper.Argb.getArgb(a1, 255, 255, 255);
                RenderLayer layer1 = RenderLayer.getEyes(tex.apply(i1));
                VertexConsumer buf1 = bufferSource.getBuffer(layer1);
                getRenderer().reRender(
                        bakedModel, matrices, bufferSource, animatable,
                        layer1, buf1, partialTick,
                        fullBright, packedOverlay, argb1
                );
            }
        }
    }
}