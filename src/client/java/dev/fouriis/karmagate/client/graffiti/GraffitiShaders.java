package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.item.GraffitiPickerOpener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

public final class GraffitiShaders implements ClientModInitializer {
    public static ShaderProgram PROGRAM;
    public static final Identifier ID = Identifier.of("karma-gate-mod", "karma_graffiti");

    @Override
    public void onInitializeClient() {
        CoreShaderRegistrationCallback.EVENT.register(ctx -> {
            ctx.register(ID, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, program -> PROGRAM = program);
        });
        
        // Register corner interaction handler for graffiti editing
        GraffitiCornerHandler.register();
        
        // Register the graffiti picker opener
        GraffitiPickerOpener.setOpener(data -> {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().setScreen(
                    new GraffitiPickerScreen(data.x(), data.y(), data.z(), data.facing())
                );
            });
        });
    }

    public static RenderPhase.ShaderProgram phase() {
        return new RenderPhase.ShaderProgram(() -> PROGRAM);
    }
}
