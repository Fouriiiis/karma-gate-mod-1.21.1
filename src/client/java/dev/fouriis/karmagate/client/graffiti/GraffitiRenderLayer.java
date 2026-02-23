package dev.fouriis.karmagate.client.graffiti;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GraffitiRenderLayer {

    private static final Map<Identifier, RenderLayer> CACHE = new ConcurrentHashMap<>();

    public static RenderLayer get(Identifier texture) {
        return CACHE.computeIfAbsent(texture, GraffitiRenderLayer::create);
    }

    private static RenderLayer create(Identifier texture) {
        RenderPhase.Texture tex = new RenderPhase.Texture(texture, false, false);

        RenderLayer.MultiPhaseParameters params = RenderLayer.MultiPhaseParameters.builder()
            .program(GraffitiShaders.phase()) // <-- THIS is what makes discard work
            .texture(tex)
            .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
            .cull(RenderPhase.ENABLE_CULLING)
            .lightmap(RenderPhase.ENABLE_LIGHTMAP)
            .overlay(RenderPhase.ENABLE_OVERLAY_COLOR)
            .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
            .writeMaskState(RenderPhase.COLOR_MASK)
            .build(true);

        return RenderLayer.of(
            "graffiti_decal",
            VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
            VertexFormat.DrawMode.QUADS,
            256,
            true,
            true,
            params
        );
    }
}