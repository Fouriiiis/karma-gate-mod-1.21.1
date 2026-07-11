package dev.fouriis.karmagate.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

public final class AtcCloudShaders implements ClientModInitializer {
    public static ShaderProgram PROGRAM;
    public static ShaderProgram DISTANT_PROGRAM;
    public static ShaderProgram STRUCTURE_PROGRAM;
    public static final Identifier ID = Identifier.of("karma-gate-mod", "karma_atc_cloud_volume");
    public static final Identifier DISTANT_ID = Identifier.of("karma-gate-mod", "karma_atc_cloud_distant");
    public static final Identifier STRUCTURE_ID = Identifier.of("karma-gate-mod", "karma_atc_structure_billboard");

    @Override
    public void onInitializeClient() {
        CoreShaderRegistrationCallback.EVENT.register(ctx -> {
            ctx.register(ID, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, program -> PROGRAM = program);
            ctx.register(DISTANT_ID, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, program -> DISTANT_PROGRAM = program);
            ctx.register(STRUCTURE_ID, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, program -> STRUCTURE_PROGRAM = program);
        });
    }

    public static RenderPhase.ShaderProgram phase() {
        return new RenderPhase.ShaderProgram(() -> PROGRAM);
    }

    public static RenderPhase.ShaderProgram distantPhase() {
        return new RenderPhase.ShaderProgram(() -> DISTANT_PROGRAM);
    }

    public static RenderPhase.ShaderProgram structurePhase() {
        return new RenderPhase.ShaderProgram(() -> STRUCTURE_PROGRAM);
    }
}
