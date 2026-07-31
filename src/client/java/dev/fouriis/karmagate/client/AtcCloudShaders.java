package dev.fouriis.karmagate.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

public final class AtcCloudShaders implements ClientModInitializer {
    public static ShaderProgram PROGRAM;
    public static ShaderProgram STRUCTURE_PROGRAM;
    public static final Identifier ID = Identifier.of("karma-gate-mod", "karma_atc_cloud_billboard");
    public static final Identifier STRUCTURE_ID = Identifier.of("karma-gate-mod", "karma_atc_structure_billboard");

    @Override
    public void onInitializeClient() {
        CoreShaderRegistrationCallback.EVENT.register(ctx -> {
            ctx.register(ID, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, program -> PROGRAM = program);
            ctx.register(AtcCloseCloudShaders.ID, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                    program -> AtcCloseCloudShaders.PROGRAM = program);
            ctx.register(STRUCTURE_ID, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, program -> STRUCTURE_PROGRAM = program);
        });
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.of("karma-gate-mod", "close_cloud_volume_cache");
                    }

                    @Override
                    public void reload(ResourceManager manager) {
                        AtcCloseCloudVolumeCache.reload(manager);
                    }
                });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AtcCloseCloudVolumeCache.closeActive());
    }

    public static RenderPhase.ShaderProgram phase() {
        return new RenderPhase.ShaderProgram(() -> PROGRAM);
    }

    public static RenderPhase.ShaderProgram structurePhase() {
        return new RenderPhase.ShaderProgram(() -> STRUCTURE_PROGRAM);
    }
}
