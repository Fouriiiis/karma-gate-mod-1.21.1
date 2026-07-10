package dev.fouriis.karmagate.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

public final class AtcCloudShaders implements ClientModInitializer {
    public static ShaderProgram PROGRAM;
    public static final Identifier ID = Identifier.of("karma-gate-mod", "karma_atc_cloud_volume");

    @Override
    public void onInitializeClient() {
        CoreShaderRegistrationCallback.EVENT.register(ctx -> {
            ctx.register(ID, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, program -> PROGRAM = program);
        });
    }

    public static RenderPhase.ShaderProgram phase() {
        return new RenderPhase.ShaderProgram(() -> PROGRAM);
    }
}
