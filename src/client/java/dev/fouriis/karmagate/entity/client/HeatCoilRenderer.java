package dev.fouriis.karmagate.entity.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasManager;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

public class HeatCoilRenderer extends GeoBlockRenderer<HeatCoilBlockEntity> {
    private static final Identifier HEAT_NOISE_TEX =
            Identifier.of("karmagate", "textures/effect/heat_distortion_noise.png");
    private static final Identifier GRAB_TEXTURE = Identifier.of("librainworldmc", "grabtex");
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

            renderHeatDistortionBillboard(animatable, partialTick, t, fullBright);
        }

        private void renderHeatDistortionBillboard(
                HeatCoilBlockEntity animatable,
                float partialTick,
                float heatT,
                int packedLight
        ) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null || mc.gameRenderer == null || mc.getCameraEntity() == null) return;

            // Rain World reference:
            // num3 = InverseLerp(0.15f, 0.8f, heat)
            // y += 40f * num3
            // scaleX = Lerp(10f, 15f, num3)
            // scaleY = Lerp(15f, 30f, num3)
            // alpha = SCurve(heat, 1.5f)
            float num3 = inverseLerpClamped(0.15f, 0.8f, heatT);

            Vec3d baseCenter = Vec3d.ofCenter(animatable.getPos()).add(0.0, 0.15 + 0.45 * num3, 0.0);
            Vec3d camPos = mc.gameRenderer.getCamera().getPos();
            Vec3d toCamera = camPos.subtract(baseCenter);
            Vec3d towardCamera = toCamera.lengthSquared() > 1.0e-6 ? toCamera.normalize() : Vec3d.ZERO;
            Vec3d blockCenter = baseCenter.add(towardCamera.multiply(1.0));

            // Tuned to preserve the Rain World proportions:
            // width grows 10 -> 15  (1.5x)
            // height grows 15 -> 30 (2.0x)
            float halfWidth = lerp(0.50f, 0.75f, num3);
            float halfHeight = lerp(0.75f, 1.50f, num3);

            float alpha = sCurve(heatT, 1.5f);

            long time = animatable.getWorld() != null ? animatable.getWorld().getTime() : 0L;
            float spinRadians = (time + partialTick) * 0.02f;

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

            float cos = (float) Math.cos(spinRadians);
            float sin = (float) Math.sin(spinRadians);
            float rightX = right.x * cos + up.x * sin;
            float rightY = right.y * cos + up.y * sin;
            float rightZ = right.z * cos + up.z * sin;
            float upX = up.x * cos - right.x * sin;
            float upY = up.y * cos - right.y * sin;
            float upZ = up.z * cos - right.z * sin;

            Vec3d cam = camera.getPos();
            float cx = (float) (center.x - cam.x);
            float cy = (float) (center.y - cam.y);
            float cz = (float) (center.z - cam.z);

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

            float rectExpand = lerp(0.08f, 0.28f, alpha);
            float[] rippleRect = new float[]{
                    -rectExpand,
                    -rectExpand,
                    1.0f + rectExpand,
                    1.0f + rectExpand
            };

            CoreShaderRenderer.bindShader$HeatDistortion(rippleRect, heatNoiseTexture, GRAB_TEXTURE);

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

        private float inverseLerpClamped(float a, float b, float value) {
            if (a == b) return 0f;
            return Math.max(0f, Math.min(1f, (value - a) / (b - a)));
        }

        private float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        // Approximation of Rain World's Custom.SCurve(x, 1.5f)
        private float sCurve(float x, float exp) {
            float v = Math.max(0f, Math.min(1f, x));
            if (v <= 0f) return 0f;
            if (v >= 1f) return 1f;

            float a = (float) Math.pow(v, exp);
            float b = (float) Math.pow(1f - v, exp);
            return a / (a + b);
        }
    }
}