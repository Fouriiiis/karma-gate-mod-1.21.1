package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.item.GraffitiPickerOpener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
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

        // Register right-click config handler
        GraffitiConfigHandler.register();
        
        // Register the graffiti picker opener
        GraffitiPickerOpener.setOpener(data -> {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().setScreen(
                    new GraffitiPickerScreen(data.x(), data.y(), data.z(), data.facing())
                );
            });
        });

        // Advance video textures every render frame (not 20 Hz game tick) so
        // playback is as smooth as the monitor refresh rate.
        WorldRenderEvents.END.register(context -> VideoTextureManager.tick());

        // Destroy and re-create video textures when the resource pack is reloaded,
        // so updated .mp4 files are picked up without restarting the game.
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
            .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                @Override
                public Identifier getFabricId() {
                    return Identifier.of("karma-gate-mod", "graffiti_video_reload");
                }

                @Override
                public void reload(ResourceManager manager) {
                    VideoTextureManager.closeAll();
                }
            });
    }

    public static RenderPhase.ShaderProgram phase() {
        return new RenderPhase.ShaderProgram(() -> PROGRAM);
    }
}
