package dev.fouriis.karmagate;

import dev.fouriis.karmagate.client.AtcSkyFabricAdapter;
import dev.fouriis.karmagate.client.gridproject.CoralNeuronCircleManager;
import dev.fouriis.karmagate.client.gridproject.StarMatrixPatternManager;
import dev.fouriis.karmagate.client.network.ClientNetworking;
import dev.fouriis.karmagate.client.rot.RotRenderCache;
import dev.fouriis.karmagate.client.rot.RotWorldRenderer;
import dev.fouriis.karmagate.client.swarmer.NeuronSwarmerManager;
import dev.fouriis.karmagate.client.swarmer.NeuronSwarmerRenderer;
import dev.fouriis.karmagate.client.wormgrass.WormGrassRenderCache;
import dev.fouriis.karmagate.client.wormgrass.WormGrassWorldRenderer;
import dev.fouriis.karmagate.client.cubefold.CubeFoldEffect;
import dev.fouriis.karmagate.client.graffiti.GraffitiEntityRenderer;
import dev.fouriis.karmagate.client.hose.FuelHoseClientState;
import dev.fouriis.karmagate.client.hose.FuelHoseWorldRenderer;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.centipede.CentiwingEntityRenderer;
import dev.fouriis.karmagate.entity.centipede.CentipedeBodyRenderer;
import dev.fouriis.karmagate.entity.centipede.CentipedeEntityRenderer;
import dev.fouriis.karmagate.entity.centipede.CentipedeHeadRenderer;
import dev.fouriis.karmagate.entity.centipede.RedCentipedeRenderer;
import dev.fouriis.karmagate.entity.centipede.SmallCentipedeRenderer;
import dev.fouriis.karmagate.entity.centipede.SmallCentiwingRenderer;
import dev.fouriis.karmagate.entity.client.CoralNeuronEntityRenderer;
import dev.fouriis.karmagate.entity.client.GateLightBlockRenderer;
import dev.fouriis.karmagate.entity.client.GateLightItemModel;
import dev.fouriis.karmagate.entity.client.HeatCoilItemModel;
import dev.fouriis.karmagate.entity.client.HeatCoilRenderer;
import dev.fouriis.karmagate.entity.client.KarmaGateBlockRenderer;
import dev.fouriis.karmagate.entity.client.ShelterDoorRenderer;
import dev.fouriis.karmagate.entity.client.WaterfallBlockRenderer;
import dev.fouriis.karmagate.entity.daddy.DaddyLongLegsRenderer;
import dev.fouriis.karmagate.entity.garbworm.GarbageWormRenderer;
import dev.fouriis.karmagate.entity.karmagate.WaterStreamBlockEntity;
import dev.fouriis.karmagate.entity.lizard.GreenLizardRenderer;
import dev.fouriis.karmagate.entity.lizard.LizardPartRenderer;
import dev.fouriis.karmagate.entity.spider.SpiderEntityRenderer;
import dev.fouriis.karmagate.entity.stowaway.StowawayBugRenderer;
import dev.fouriis.karmagate.hologram.HologramProjectorRenderer;
import dev.fouriis.karmagate.item.KarmaGateItemGeoRenderer;
import dev.fouriis.karmagate.item.tool.CoralNeuronClientDefinition;
import dev.fouriis.karmagate.item.tool.ProjectionZoneClientDefinition;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.particle.SteamParticle;
import dev.fouriis.karmagate.particle.WaterStreamParticle;
import dev.fouriis.karmagate.sound.GateAudioSpecs;
import dev.fouriis.karmagate.sound.ModSounds;
import dev.fouriis.karmagate.sound.MultiSound;
import dev.fouriis.karmagate.sound.MultiSound.Spec;
import dev.fouriis.karmagate.sound.SteamAudioController;
import net.brickcraftdream.librainworldmc.tool.api.SelectionToolRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendereregistry.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public class KarmaGateModClient implements ClientModInitializer {
	private static KeyBinding ROOM_MAP_KEY;

	@Override
	public void onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.
		SelectionToolRegistry.upgradeDefinition(CoralNeuronClientDefinition.INSTANCE);
		SelectionToolRegistry.upgradeDefinition(ProjectionZoneClientDefinition.INSTANCE);

		// Register client networking
		ClientNetworking.register();
		FuelHoseWorldRenderer.register();

		// Register distant structure billboards
		//dev.fouriis.karmagate.client.DistantStructuresRenderer.init();

		// Register block entity renderer
		BlockEntityRendererFactories.register(ModBlockEntities.KARMA_GATE_BLOCK_ENTITY, KarmaGateBlockRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.SHELTER_DOOR_BLOCK_ENTITY, ShelterDoorRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.GATE_LIGHT_BLOCK_ENTITY, GateLightBlockRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.HEAT_COIL_BLOCK_ENTITY, HeatCoilRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.HOLOGRAM_PROJECTOR, HologramProjectorRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.WATERFALL_BLOCK_ENTITY, WaterfallBlockRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.WATER_STREAM_BLOCK_ENTITY, WaterfallBlockRenderer::new);

		ParticleFactoryRegistry.getInstance().register(ModParticles.WATER_STREAM, sprites -> new WaterStreamParticle.Factory(sprites));
		ParticleFactoryRegistry.getInstance().register(ModParticles.STEAM, sprites -> new SteamParticle.Factory(sprites));

		// Register neuron swarmer renderer
		NeuronSwarmerRenderer.register();
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.VINE_ENTITY_TYPE, CoralNeuronEntityRenderer::new);

		// Register graffiti entity renderer
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.GRAFFITI_ENTITY_TYPE, GraffitiEntityRenderer::new);

		// Register stowaway bug entity renderer
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.STOWAWAY_BUG_ENTITY_TYPE, StowawayBugRenderer::new);

		// Register centipede entity renderers
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.CENTIPEDE_HEAD_ENTITY_TYPE, CentipedeHeadRenderer::new);
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.CENTIPEDE_BODY_ENTITY_TYPE, CentipedeBodyRenderer::new);
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.RED_CENTIPEDE_ENTITY_TYPE, RedCentipedeRenderer::new);
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.CENTIPEDE_ENTITY_TYPE, CentipedeEntityRenderer::new);
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.CENTIWING_ENTITY_TYPE, CentiwingEntityRenderer::new);
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.SMALL_CENTIPEDE_ENTITY_TYPE, SmallCentipedeRenderer::new);
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.SMALL_CENTIWING_ENTITY_TYPE, SmallCentiwingRenderer::new);

		// Register spider entity renderer
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.SPIDER_ENTITY_TYPE, SpiderEntityRenderer::new);

		// Register garbage worm entity renderer
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.GARBAGE_WORM_ENTITY_TYPE, GarbageWormRenderer::new);

		// Register daddy long legs renderer
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.DADDY_LONG_LEGS_ENTITY_TYPE, DaddyLongLegsRenderer::new);

		// Register lizard debug renderers
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.GREEN_LIZARD_ENTITY_TYPE, GreenLizardRenderer::new);
		EntityRendererRegistry.INSTANCE.register(KarmaGateMod.LIZARD_PART_ENTITY_TYPE, LizardPartRenderer::new);

		// --- Cube fold effect ---
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (hand != Hand.MAIN_HAND) {
				return ActionResult.PASS;
			}

			BlockState state = world.getBlockState(hitResult.getBlockPos());

			if (state.getBlock() instanceof DoorBlock) {
				if (world.isClient) {
					CubeFoldEffect.trigger(MinecraftClient.getInstance());
				}
				// Returning SUCCESS on the server cancels the vanilla door interaction
				return ActionResult.SUCCESS;
			}

			return ActionResult.PASS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(CubeFoldEffect::tick);

		WorldRenderEvents.LAST.register(CubeFoldEffect::render);
		WorldRenderEvents.END.register(CubeFoldEffect::onEndFrame);
		HudRenderCallback.EVENT.register((context, tickCounter) -> CubeFoldEffect.renderCaptureOverlay(context));

		WorldRenderEvents.LAST.register(context -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.world == null) {
				return;
			}

			

			
		});

		// Register Karma Gate item renderer with custom transforms
		var gateItemRenderer = new KarmaGateItemGeoRenderer();
		BuiltinItemRendererRegistry.INSTANCE.register(
			dev.fouriis.karmagate.block.ModBlocks.KARMA_GATE.asItem(),
			(stack, mode, matrices, vertexConsumers, light, overlay) -> gateItemRenderer.render(stack, mode, matrices, vertexConsumers, light, overlay)
		);

		DimensionRenderingRegistry.registerSkyRenderer(World.OVERWORLD, AtcSkyFabricAdapter::render);

		// Heat Coil item renderer (simple small centered)
		var heatCoilItemRenderer = new GeoItemRenderer<>(new HeatCoilItemModel());
		BuiltinItemRendererRegistry.INSTANCE.register(
			dev.fouriis.karmagate.block.ModBlocks.HEAT_COIL.asItem(),
			(stack, mode, matrices, vertexConsumers, light, overlay) -> {
				matrices.push();
				// Increased size by 100% (0.18 -> 0.36). Drop slightly to keep centered visually.
				matrices.translate(0.5f, 0.40f, 0.5f);
				matrices.scale(0.36f, 0.36f, 0.36f);
				matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(35f));
				matrices.translate(-0.5f, -0.5f, -0.5f);
				heatCoilItemRenderer.render(stack, mode, matrices, vertexConsumers, light, overlay);
				matrices.pop();
			}
		);

		// Gate Light item renderer
		var gateLightItemRenderer = new GeoItemRenderer<>(new GateLightItemModel());
		BuiltinItemRendererRegistry.INSTANCE.register(
			dev.fouriis.karmagate.block.ModBlocks.GATE_LIGHT.asItem(),
			(stack, mode, matrices, vertexConsumers, light, overlay) -> {
				matrices.push();
				// Increased size by 100% (0.22 -> 0.44). Lower slightly to keep within slot.
				matrices.translate(0.5f, 0.43f, 0.5f);
				matrices.scale(0.44f, 0.44f, 0.44f);
				matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(45f));
				matrices.translate(-0.5f, -0.5f, -0.5f);
				gateLightItemRenderer.render(stack, mode, matrices, vertexConsumers, light, overlay);
				matrices.pop();
			}
		);

		// Install client implementation for audio shim
		final Map<BlockPos, MultiSound.Handle> clampLoops = new HashMap<>();
		final Map<BlockPos, MultiSound.Handle> screwLoops = new HashMap<>();

		ModSounds.setAudio(new ModSounds.AudioImpl() {
			@Override
			public void onSteamBurst(net.minecraft.util.math.BlockPos pos, float intensity01, net.minecraft.sound.SoundEvent loopEvent) {
				SteamAudioController.get().onSteamBurst(pos, intensity01, loopEvent);
			}

			@Override
			public void onTimelineEvent(BlockPos pos, String token) {
				KarmaGateMod.LOGGER.info("[AudioClient] token '{}' at {}", token, pos);
				Spec spec = switch (token) {
					case "Gate_Poles_And_Rails_In" -> GateAudioSpecs.POLES_AND_RAILS_IN;
					case "Gate_Pillows_Move_In" -> GateAudioSpecs.PILLOWS_MOVE_IN;
					case "Gate_Pillows_In_Place" -> GateAudioSpecs.PILLOWS_IN_PLACE;
					case "Gate_Panser_On" -> GateAudioSpecs.PANSER_ON;
					case "Gate_Rails_Collide" -> GateAudioSpecs.RAILS_COLLIDE;
					case "Gate_Secure_Rail_Down" -> GateAudioSpecs.SECURE_RAIL_DOWN;
					case "Gate_Secure_Rail_Slam" -> GateAudioSpecs.CLAMP_COLLISION;
					case "Gate_Bolt" -> GateAudioSpecs.BOLT;
					// Opening tokens
					case "Gate_Secure_Rail_Up" -> GateAudioSpecs.SECURE_RAIL_UP;
					case "Gate_Panser_Off" -> GateAudioSpecs.PANSER_OFF;
					case "Gate_Pillows_Move_Out" -> GateAudioSpecs.PILLOWS_MOVE_OUT;
					case "Gate_Poles_Out" -> GateAudioSpecs.POLES_OUT;
					default -> null;
				};
				if (spec != null) {
					KarmaGateMod.LOGGER.info("[AudioClient] mapped '{}' -> spec with {} clip(s)", token, spec.clips.size());
					MultiSound.playAt(pos, spec);
				} else {
					// Loop token handling
					switch (token) {
						case "ClampLoopStart" -> {
							var key = pos.toImmutable();
							var h = clampLoops.get(key);
							if (h == null || !h.isPlaying()) {
								var nh = MultiSound.playAt(key, GateAudioSpecs.CLAMPS_MOVING_LOOP);
								clampLoops.put(key, nh);
								KarmaGateMod.LOGGER.info("[AudioClient] Clamp loop started @{}", key);
							}
						}
						case "ClampLoopStop" -> {
							var key = pos.toImmutable();
							var h = clampLoops.remove(key);
							if (h != null) h.stop();
							KarmaGateMod.LOGGER.info("[AudioClient] Clamp loop stopped @{}", key);
						}
						case "ScrewLoopStart" -> {
							var key = pos.toImmutable();
							var h = screwLoops.get(key);
							if (h == null || !h.isPlaying()) {
								Spec loopSpec = chooseScrewLoopSpec(key);
								var nh = MultiSound.playAt(key, loopSpec);
								screwLoops.put(key, nh);
								KarmaGateMod.LOGGER.info("[AudioClient] Screw loop started @{}", key);
							}
						}
						case "ScrewLoopStop" -> {
							var key = pos.toImmutable();
							var h = screwLoops.remove(key);
							if (h != null) h.stop();
							KarmaGateMod.LOGGER.info("[AudioClient] Screw loop stopped @{}", key);
						}
						default -> KarmaGateMod.LOGGER.warn("[AudioClient] unmapped token '{}'", token);
					}
				}
			}

			@Override
			public void onSoundKeyframe(net.minecraft.util.math.BlockPos pos, Identifier soundId, float volume, float pitch) {
				var event = Registries.SOUND_EVENT.get(soundId);
				if (event == null) {
					KarmaGateMod.LOGGER.warn("[AudioClient] Unknown sound id from keyframe: {}", soundId);
					return;
				}
				KarmaGateMod.LOGGER.info("[AudioClient] keyframe -> play {} v={} p={} at {}", soundId, volume, pitch, pos);
				var spec = new Spec().add(new MultiSound.Clip(event, volume, pitch));
				MultiSound.playAt(pos, spec);
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SteamAudioController.get().clientTick();
			// Update neuron swarmers
			NeuronSwarmerManager.getInstance().tick();
			// Update coral neuron endpoint circles
			CoralNeuronCircleManager.getInstance().tick();
			StarMatrixPatternManager.getInstance().tick();
		});
		

		// Clear cached loop references on disconnect or new join to avoid stale sound state after rejoin
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SteamAudioController.get().clear();
			NeuronSwarmerManager.getInstance().clear();
			CoralNeuronCircleManager.getInstance().clear();
			StarMatrixPatternManager.getInstance().clear();
			clampLoops.values().forEach(MultiSound.Handle::stop);
			screwLoops.values().forEach(MultiSound.Handle::stop);
			clampLoops.clear();
			screwLoops.clear();
			RotRenderCache.clearAll();
			RotWorldRenderer.clearCache();
			CubeFoldEffect.clearForWorldTransition();
			FuelHoseClientState.clear();
		});

		// --- Wormgrass client hooks ---
		// Maintain a cache of wormgrass positions per chunk.
		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> WormGrassRenderCache.onChunkLoad(world, chunk));
		ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> WormGrassRenderCache.onChunkUnload(world, chunk));

		// Render after translucent world layers.
		WorldRenderEvents.AFTER_ENTITIES.register(WormGrassWorldRenderer::render);

		// --- Rot (Daddy Corruption) client hooks ---
		// Maintain a cache of rot block positions per chunk.
		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> RotRenderCache.onChunkLoad(world, chunk));
		ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> RotRenderCache.onChunkUnload(world, chunk));

		// Render corruption spheres with eye patterns.
		WorldRenderEvents.AFTER_ENTITIES.register(RotWorldRenderer::render);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			SteamAudioController.get().clear();
			NeuronSwarmerManager.getInstance().clear();
			CoralNeuronCircleManager.getInstance().clear();
			StarMatrixPatternManager.getInstance().clear();
			clampLoops.values().forEach(MultiSound.Handle::stop);
			screwLoops.values().forEach(MultiSound.Handle::stop);
			clampLoops.clear();
			screwLoops.clear();
			RotRenderCache.clearAll();
			RotWorldRenderer.clearCache();
			CubeFoldEffect.clearForWorldTransition();
		});
	}

	private static Spec chooseScrewLoopSpec(BlockPos pos) {
		var world = MinecraftClient.getInstance().world;
		if (world == null) return GateAudioSpecs.ELEC_SCREW;
		int r = 8;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					var be = world.getBlockEntity(pos.add(dx, dy, dz));
					if (be instanceof WaterStreamBlockEntity) {
						return GateAudioSpecs.WATER_SCREW;
					}
				}
			}
		}
		return GateAudioSpecs.ELEC_SCREW;
	}
}
