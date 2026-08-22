package dev.fouriis.karmagate.client.rot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/** Shader used for both model-conforming goo and Rain World jagged bulbs. */
public final class RotShaders implements ClientModInitializer {
    public static final Identifier ID = Identifier.of("karma-gate-mod", "karma_rot_corruption");
    public static ShaderProgram PROGRAM;

    @Override
    public void onInitializeClient() {
        CoreShaderRegistrationCallback.EVENT.register(context ->
                context.register(ID, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL,
                        program -> PROGRAM = program));
    }
}
