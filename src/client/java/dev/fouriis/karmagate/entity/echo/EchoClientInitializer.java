package dev.fouriis.karmagate.entity.echo;

import dev.fouriis.karmagate.KarmaGateMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/**
 * Registers all client-only Echo behavior from a dedicated entrypoint.
 *
 * <p>Keeping this separate from the mod's large legacy client initializer
 * ensures an Echo renderer is installed before any summoned Echo is rendered.
 */
public final class EchoClientInitializer implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(KarmaGateMod.ECHO_ENTITY_TYPE, EchoEntityRenderer::new);
        ClientTickEvents.END_CLIENT_TICK.register(EchoGhostEffectSystem::tick);
        ClientTickEvents.END_CLIENT_TICK.register(EchoDialogueSystem::tick);
        HudRenderCallback.EVENT.register(EchoDialogueSystem::render);
    }
}
