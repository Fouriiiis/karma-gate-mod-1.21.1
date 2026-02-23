package dev.fouriis.karmagate.client.graffiti;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

public final class GraffitiRenderLayer {

    private GraffitiRenderLayer() {}

    public static RenderLayer get(Identifier texture) {
        // Even if Iris overrides core shaders, CPU clipping keeps UVs in-range,
        // so vanilla entity translucent works reliably with shader packs.
        //
        // If your custom shader is active, this still works too.
        if (isIrisShaderPackActive()) {
            return RenderLayer.getEntityTranslucentCull(texture);
        }

        // Non-Iris path: keep your custom shader layer (optional).
        // If PROGRAM isn't ready yet, fall back.
        RenderLayer custom = CustomGraffitiLayer.get(texture);
        return custom != null ? custom : RenderLayer.getEntityTranslucentCull(texture);
    }

    /**
     * Detect Iris + active shader pack via reflection to avoid hard dependency.
     */
    private static boolean isIrisShaderPackActive() {
        try {
            Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = irisApi.getMethod("getInstance").invoke(null);

            // Most Iris versions expose isShaderPackInUse()
            try {
                Object r = irisApi.getMethod("isShaderPackInUse").invoke(api);
                return (r instanceof Boolean b) && b;
            } catch (NoSuchMethodException ignored) {
                // Some versions expose isShaderPackInUse as isShaderPackInUse() or similar; if not found, assume true
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Separate helper so we don't allocate the custom layer when not needed.
     */
    private static final class CustomGraffitiLayer {
        private static RenderLayer get(Identifier texture) {
            try {
                // If your custom program isn't loaded yet, skip.
                if (GraffitiShaders.PROGRAM == null) return null;
                return RenderLayer.of(
                    "graffiti_decal",
                    net.minecraft.client.render.VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                    net.minecraft.client.render.VertexFormat.DrawMode.QUADS,
                    256,
                    true,
                    true,
                    RenderLayer.MultiPhaseParameters.builder()
                        .program(GraffitiShaders.phase())
                        .texture(new net.minecraft.client.render.RenderPhase.Texture(texture, false, false))
                        .transparency(net.minecraft.client.render.RenderPhase.TRANSLUCENT_TRANSPARENCY)
                        .cull(net.minecraft.client.render.RenderPhase.ENABLE_CULLING)
                        .lightmap(net.minecraft.client.render.RenderPhase.ENABLE_LIGHTMAP)
                        .overlay(net.minecraft.client.render.RenderPhase.ENABLE_OVERLAY_COLOR)
                        .depthTest(net.minecraft.client.render.RenderPhase.LEQUAL_DEPTH_TEST)
                        .writeMaskState(net.minecraft.client.render.RenderPhase.COLOR_MASK)
                        .build(true)
                );
            } catch (Throwable t) {
                return null;
            }
        }
    }
}