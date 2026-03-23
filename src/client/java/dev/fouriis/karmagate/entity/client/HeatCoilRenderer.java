package dev.fouriis.karmagate.entity.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasManager;


public class HeatCoilRenderer extends GeoBlockRenderer<HeatCoilBlockEntity> {
    private static final Identifier HEAT_NOISE_TEX =
            Identifier.of("karmagate", "textures/effect/heat_distortion_noise.png");
    private static final Identifier GRAB_TEXTURE = Identifier.of("librainworldmc", "grabtex");
    private static final float[] FULL_SPRITE_RECT = new float[]{0.0f, 0.0f, 1.0f, 1.0f};
    private final FAtlasManager atlasManager;

    public HeatCoilRenderer(BlockEntityRendererFactory.Context ctx) {
        super(new HeatCoilModel());
        atlasManager = LibrainworldmcClient.getAtlasManager();
        addRenderLayer(new GlowLayer(this, resolveHeatNoiseTexture(atlasManager)));
    }

    private static Identifier resolveHeatNoiseTexture(FAtlasManager atlasManager) {
        if (atlasManager != null) {
            String[] candidates = {"NoiseTex", "noise", "HeatDistortionNoise", "HeatDistortion"};
            for (String candidate : candidates) {
                FAtlasElement element = atlasManager.getElementWithName(candidate);
                if (element != null && element.textureIdentifier != null) {
                    return element.textureIdentifier;
                }
            }
        }
        return HEAT_NOISE_TEX;
    }

    private static final class GlowLayer extends GeoRenderLayer<HeatCoilBlockEntity> {
        private final Identifier heatNoiseTexture;

        GlowLayer(HeatCoilRenderer parent, Identifier heatNoiseTexture) {
            super(parent);
            this.heatNoiseTexture = heatNoiseTexture != null ? heatNoiseTexture : HEAT_NOISE_TEX;
        }

        @Override
        public void render(
                MatrixStack matrices,
                HeatCoilBlockEntity animatable,
                BakedGeoModel bakedModel,
                RenderLayer baseLayer,
                VertexConsumerProvider bufferSource,
                VertexConsumer buffer,
                float partialTick,
                int packedLight,
                int packedOverlay
        ) {
            float heat = animatable.getVisualHeat();

            float h = Math.max(0f, Math.min(1f, heat));
            float threshold = 0.05f;
            if (h <= threshold) return;

            float t = (h - threshold) / (1f - threshold);

            float p = t * 3f;
            int i0 = (int) Math.floor(p);
            int i1 = Math.min(i0 + 1, 3);
            float frac = p - i0;

            float aFast = t * t;
            float aTail = (float) Math.sqrt(t);
            float baseAlpha = Math.min(1f, 0.35f * aFast + 0.65f * aTail);

            java.util.function.IntFunction<Identifier> tex = idx -> switch (idx) {
                case 0 -> HeatCoilModel.EMISSIVE_1;
                case 1 -> HeatCoilModel.EMISSIVE_2;
                case 2 -> HeatCoilModel.EMISSIVE_3;
                default -> HeatCoilModel.EMISSIVE_4;
            };

            int fullBright = 0xF000F0;

            float w0 = (i0 == i1) ? 1f : (1f - frac);
            float w1 = (i0 == i1) ? 0f : frac;

            if (w0 > 0f) {
                int a0 = (int) (Math.min(1f, baseAlpha * w0) * 255f);
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
                int a1 = (int) (Math.min(1f, baseAlpha * w1) * 255f);
                int argb1 = ColorHelper.Argb.getArgb(a1, 255, 255, 255);
                RenderLayer layer1 = RenderLayer.getEyes(tex.apply(i1));
                VertexConsumer buf1 = bufferSource.getBuffer(layer1);
                getRenderer().reRender(
                        bakedModel, matrices, bufferSource, animatable,
                        layer1, buf1, partialTick,
                        fullBright, packedOverlay, argb1
                );
            }

            renderHeatDistortionBillboard(animatable, partialTick, t, baseAlpha, fullBright);
        }

        private void renderHeatDistortionBillboard(
                HeatCoilBlockEntity animatable,
                float partialTick,
                float heatT,
                float baseAlpha,
                int packedLight
        ) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null || mc.gameRenderer == null || mc.getCameraEntity() == null) return;

            Vec3d blockCenter = Vec3d.ofCenter(animatable.getPos()).add(0.0, 0.15, 0.0);

            Vec3d camPos = mc.gameRenderer.getCamera().getPos();
            double rx = blockCenter.x - camPos.x;
            double ry = blockCenter.y - camPos.y;
            double rz = blockCenter.z - camPos.z;

            float halfWidth = 0.55f + 0.10f * heatT;
            float halfHeight = 0.85f + 0.20f * heatT;
            float alpha = Math.min(0.40f, 0.10f + baseAlpha * 0.28f);

            long time = animatable.getWorld() != null ? animatable.getWorld().getTime() : 0L;
                float spinRadians = (time + partialTick) * 0.75f * ((float)Math.PI / 180.0f);

                RenderUtils.recordLateWorldDraw(camera -> drawHeatDistortionNow(
                    camera,
                    blockCenter,
                    halfWidth,
                    halfHeight,
                    alpha,
                    spinRadians,
                    packedLight
                ));
            }

            private void drawHeatDistortionNow(
                Camera camera,
                Vec3d center,
                float halfWidth,
                float halfHeight,
                float alpha,
                float spinRadians,
                int packedLight
            ) {
                Quaternionf cameraRotation = new Quaternionf(camera.getRotation());
                Vector3f right = cameraRotation.transform(new Vector3f(1f, 0f, 0f));
                Vector3f up = cameraRotation.transform(new Vector3f(0f, 1f, 0f));

                float cos = (float)Math.cos(spinRadians);
                float sin = (float)Math.sin(spinRadians);
                float rightX = right.x * cos + up.x * sin;
                float rightY = right.y * cos + up.y * sin;
                float rightZ = right.z * cos + up.z * sin;
                float upX = up.x * cos - right.x * sin;
                float upY = up.y * cos - right.y * sin;
                float upZ = up.z * cos - right.z * sin;

                Vec3d cam = camera.getPos();
                float cx = (float)(center.x - cam.x);
                float cy = (float)(center.y - cam.y);
                float cz = (float)(center.z - cam.z);

                float blX = cx - rightX * halfWidth - upX * halfHeight;
                float blY = cy - rightY * halfWidth - upY * halfHeight;
                float blZ = cz - rightZ * halfWidth - upZ * halfHeight;
                float brX = cx + rightX * halfWidth - upX * halfHeight;
                float brY = cy + rightY * halfWidth - upY * halfHeight;
                float brZ = cz + rightZ * halfWidth - upZ * halfHeight;
                float trX = cx + rightX * halfWidth + upX * halfHeight;
                float trY = cy + rightY * halfWidth + upY * halfHeight;
                float trZ = cz + rightZ * halfWidth + upZ * halfHeight;
                float tlX = cx - rightX * halfWidth + upX * halfHeight;
                float tlY = cy - rightY * halfWidth + upY * halfHeight;
                float tlZ = cz - rightZ * halfWidth + upZ * halfHeight;

                Matrix4f mat = new Matrix4f().rotation(camera.getRotation()).transpose();
                CoreShaderRenderer.bindShader$HeatDistortion(FULL_SPRITE_RECT, heatNoiseTexture, GRAB_TEXTURE);

                BufferBuilder bb = Tessellator.getInstance().begin(
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_LIGHT
                );

                bb.vertex(mat, blX, blY, blZ).color(1f, 1f, 1f, alpha).texture(0f, 1f).light(packedLight);
                bb.vertex(mat, brX, brY, brZ).color(1f, 1f, 1f, alpha).texture(1f, 1f).light(packedLight);
                bb.vertex(mat, trX, trY, trZ).color(1f, 1f, 1f, alpha).texture(1f, 0f).light(packedLight);
                bb.vertex(mat, tlX, tlY, tlZ).color(1f, 1f, 1f, alpha).texture(0f, 0f).light(packedLight);

                BufferRenderer.drawWithGlobalProgram(bb.end());
                RenderSystem.disableBlend();
        }
    }
}