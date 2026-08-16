package pencil.mechanics;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.render.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendereregistry.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.network.ClientSidePacketRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;
import pencil.mechanics.entity.JellyfishEntity;
import pencil.mechanics.entity.PoleplantEntity;
import pencil.mechanics.gui.screen.BlockEditorScreen;
import pencil.mechanics.gui.screen.KarmaScreen;
import pencil.mechanics.gui.screen.PlayerModelScreen;
import pencil.mechanics.init.BlockInit;
import pencil.mechanics.init.EntityTypeInit;
import pencil.mechanics.init.ItemInit;
import pencil.mechanics.player.Keybinds;
import pencil.mechanics.player.foodHandler;
import pencil.mechanics.render.block.PipeBlockEntityRenderer;
import pencil.mechanics.render.entity.*;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.animatable.GeoReplacedEntity;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoReplacedEntityRenderer;

import java.util.Objects;
import java.util.Optional;

public class RainworldMechanicsClient implements ClientModInitializer {

	public static final DefaultedEntityGeoModel GREEN_LIZARD = new DefaultedEntityGeoModel<>(new Identifier(RainworldMechanics.MOD_ID, "green_lizard"));
	public static final DefaultedEntityGeoModel PINK_LIZARD = new DefaultedEntityGeoModel<>(new Identifier(RainworldMechanics.MOD_ID, "pink_lizard"));
	public static final DefaultedEntityGeoModel NOODLE_FLY = new DefaultedEntityGeoModel<>(new Identifier(RainworldMechanics.MOD_ID, "noodle_fly"));
	public static final DefaultedEntityGeoModel JETFISH = new DefaultedEntityGeoModel<>(new Identifier(RainworldMechanics.MOD_ID, "jetfish"));
	public static final DefaultedEntityGeoModel POLEPLANT = new DefaultedEntityGeoModel<>(new Identifier(RainworldMechanics.MOD_ID, "poleplant"));
	public static final DefaultedEntityGeoModel DROPWIG = new DefaultedEntityGeoModel<>(new Identifier(RainworldMechanics.MOD_ID, "dropwig"));
	public static final DefaultedEntityGeoModel FIVEPEBBLES = new DefaultedEntityGeoModel<>(new Identifier(RainworldMechanics.MOD_ID, "fivepebbles"));
	public static final DefaultedEntityGeoModel JELLYFISH = new DefaultedEntityGeoModel<>(new Identifier(RainworldMechanics.MOD_ID, "jellyfish"));
	public static final DefaultedEntityGeoModel ECHO = new DefaultedEntityGeoModel<>(new Identifier(RainworldMechanics.MOD_ID, "echo"));

	// True only if mod is on server
	public static boolean modEnabled = false;

	// Player client and player entity references
	public static MinecraftClient clientPlayer = null;
	public static ClientPlayerEntity playerEntity;

	// Base move speed
	public static float moveSpeed = 0.12f;

	public static BlockState editorBlock = null;
	public static BlockPos editorPos = null;

	// Food variables
	public static int foodLevel = 0;
	public static int foodRequired = 8;
	public static int lastFoodLevel = 0;
	public static int maxFoodLevel = 14;
	public static boolean eating = false;
	public static boolean starving = false;

	// Is the rain cycling now
	public static boolean cycling = false;
	// Time left in Cycle
	public static int cycleTime = 25;
	// Has slugcat survived this cycle so far
	public static boolean survived = true;

	// Karma variables
	public static int karmaLevel = 0;
	public static int lastKarmaLevel = 0;
	public static int maxKarmaLevel = 9;
	public static boolean karmaShielded = false;
	public static boolean karmaScreen = false;

	// Player Model Variables
	public static String[] modelList = { "UwsBAQQjInA6MDllZmM2NzA3MGM5NDQ4MDhkYzQ1ZWJjOWU1Y2Q4MjkAAAnW", "UwsBAQQjInA6Mzk2NGJkOWFhYmNhNDYzNDhmN2IzMTc2Yjg5Nzg5MWIAAAmk", "UwsBAQQjInA6OWI2ZDdkZmIwN2E4NDRmMjhmM2U4OTFiM2FlMDJiMjYAAAn3" };
	public static float selectedModel = 0;
	public static String modelID = modelList[0];
	public static String lastModelID = modelID;

	// Crawling Variables
	public static boolean jumpHeld = false;
	public static boolean crawling = false;
	public static boolean crouchPressed = false;
	public static boolean crawlFrame = false;
	public static boolean climbJumping = false;
	public static boolean climbing = false;
	public static boolean miscSet = false;

	// Stored item variables
	public static ItemStack lastStoredItem = ItemStack.EMPTY;
	public static ItemStack storedItem = ItemStack.EMPTY;
	public static float storeTime = 40;
	public static float storeTimeMax = 40;
	public static float lastTime = storeTime;

	public static boolean stunned = false;
	public static float stunTime = 0;

	// Keybinding Variables
	private static KeyBinding crawlKey = null;
	private static final KeyBinding grabKey = Keybinds.grabKey;
	private static final KeyBinding interactKey = Keybinds.interactKey;

	// Keys that were used to test some features such as the karma screen and a player model swapping gui
	private static final KeyBinding testingKey1 = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.slugcatmovement.test1", // The translation key of the keybindings name
			InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
			GLFW.GLFW_KEY_Y, // The keycode of the key
			"category.slugcatmovement.rainworld" // The translation key of the keybinding's category.
	));
	private static final KeyBinding testingKey2 = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.slugcatmovement.test2", // The translation key of the keybinding's name
			InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
			GLFW.GLFW_KEY_U, // The keycode of the key
			"category.slugcatmovement.rainworld" // The translation key of the keybinding's category.
	));

	public static void sendFallVelocityPacket(ClientPlayerEntity player) {
		if (player != null) {
			double velocityY = player.getVelocity().y;
			VelocityFallPacket packet = new VelocityFallPacket(velocityY);
			ClientPlayNetworking.send(VelocityFallPacket.ID, packet.toBytes());
		}
	}

	public static final EntityModelLayer MODEL_SPEAR_LAYER = new EntityModelLayer(new Identifier("rw-mechanics", "spear"), "main");

	@Override
	public void onInitializeClient() {

		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.GREEN_LIZARD, (context) -> {return new GreenLizardEntityRenderer(context, GREEN_LIZARD); });
		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.PINK_LIZARD, (context) -> {return new PinkLizardEntityRenderer(context, PINK_LIZARD); });

		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.NOODLEFLY_ENTITY, (context) -> {return new NoodleflyEntityRenderer(context, NOODLE_FLY); });
		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.DROPWIG , (context) -> {return new DropwigEntityRenderer(context, DROPWIG); });
		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.JETFISH , (context) -> {return new JetfishEntityRenderer(context, JETFISH); });
		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.POLEPLANT , (context) -> {return new PoleplantEntityRenderer(context, POLEPLANT); });
		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.JELLYFISH , (context) -> {return new JellyfishEntityRenderer(context, JELLYFISH); });
		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.ECHO, (context) -> new EchoEntityRenderer(context, ECHO));
		EchoGhostRenderer.initialize();

		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.FIVEPEBBLES , (context) -> {return new FivePebblesEntityRenderer(context, FIVEPEBBLES); });

		EntityRendererRegistry.INSTANCE.register(EntityTypeInit.SPEAR, SpearEntityRenderer::new);
		EntityModelLayerRegistry.registerModelLayer(MODEL_SPEAR_LAYER, SpearEntityModel::getTexturedModelData);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			// Checks the server for the mod
			if (client.player != null) {
				if (client.world != null) {
					editorBlock = client.world.getBlockState(new BlockPos(0, 0, 0));
				}

				// Asks if mod is on the server
				PacketByteBuf buf = PacketByteBufs.create(); // Create Packet
				ClientSidePacketRegistry.INSTANCE.sendToServer(RainworldMechanics.SLUGCAT_MISC_PACKET_ID, buf); // Send to server

				// If the server says yes enable mod
				ClientSidePacketRegistry.INSTANCE.register(RainworldMechanics.SLUGCAT_MISC_PACKET_ID, (packetContext, attachedData) -> {
					boolean serversideResponse = attachedData.readBoolean();
					packetContext.getTaskQueue().execute(() -> {
						modEnabled = true;
					});
				});
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			modEnabled = false;
		});

		// Runs at the start of each client tick
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			// Sets a reference to the player
			clientPlayer = client;

			// Sets the crawl keybinding to the current crouch keybinding
			crawlKey = client.options.sneakKey;

			// Checks if the player is in game and in survival/adventure mode
			if (client.player != null && !client.player.isCreative() && !client.player.isSpectator()) {

				if (foodLevel<maxFoodLevel) {
					client.player.getHungerManager().setFoodLevel(19);
				} else {
					client.player.getHungerManager().setFoodLevel(20);
				}

				// Opens karma menu for testing purposes
				if (testingKey1.wasPressed() && !cycling) {
					cycling = true;
				}

				// Karma Screen functions
				if (cycling) {
					if (!karmaScreen) {
						onHibernated();
						client.setScreen(new KarmaScreen(Text.empty()));
						karmaScreen = true;
					}
				}

				// Checks if the item in the players hand is food and they are eating it
				if (client.player != null && client.player.isUsingItem() && client.player.getActiveItem().isFood() && foodLevel < maxFoodLevel) {
					eating = true;
				} else {
					eating = false;
				}

				// Eats item
				if (client.player != null && client.player.isUsingItem() && client.player.getItemUseTimeLeft() <= 1f && eating  && foodLevel < maxFoodLevel){
					foodHandler.tick(client);
				}

				// Opens player model debug menu for testing purposes
				if (testingKey2.wasPressed()) {
					client.setScreen(new PlayerModelScreen(Text.empty()));
				}

				// Checks if the crawl key was pressed and toggles crawl mode
				if (crawlKey.isPressed() && !crawling && !crouchPressed && !climbing) {
						crawling = true;
						crouchPressed = true;
				}
				if (crawling && client.options.sprintKey.isPressed()) { // Checks if already in a crawl and if true stops crawling
					crawling = false;
					crouchPressed = true;
				}

				if (client.world.getBlockState(client.player.getBlockPos()).getBlock() == BlockInit.PIPE_ENTRANCE) {
					crawling = true;
				}

				// Resets the above crawl toggle function on release of the crawl key
				if (!crawlKey.isPressed() && crouchPressed || !client.options.sprintKey.isPressed() && crouchPressed){
					crouchPressed = false;
				}

				ClientSidePacketRegistry.INSTANCE.register(RainworldMechanics.STUN_PLAYER_PACKET_ID, (packetContext, attachedData) -> {
					float stunServerTime = attachedData.readFloat();
					packetContext.getTaskQueue().execute(() -> {
						stunned = true;
						stunTime = stunServerTime;
					});
				});

				if (stunned) {
					crawling = true;
					client.player.setVelocity(0,0,0);
					if (stunTime > 0) {
						stunTime--;
					} else {
						stunned = false;
						stunTime = 0;
					}
				}

				// Sets refrence to the player entity
				playerEntity = MinecraftClient.getInstance().player;

				// activates the movement keybinds
				Keybinds.main();

				pencil.mechanics.player.movement.PoleClimbing.main(client, grabKey);

				// Checks if the player is standing and not crawling
				if (!crawling) { // Then activates the movement code
					pencil.mechanics.player.movement.WallMovement.main();
					client.player.setNoGravity(false);
				} else { // Runs if crawling or submerged in water

					// Checks if crawling
					if (crawling && !climbing) { // Then activates the crawling code
						pencil.mechanics.player.movement.Crawling.main(client);
						ClientSidePacketRegistry.INSTANCE.sendToServer(RainworldMechanics.CRAWL_PACKET_ID, PacketByteBufs.empty());
					}
				}

				// Currently does nothing due to the server side of this function being disabled
				// If the current player model id and selected model id do not match, tell server to change the model
				if (!Objects.equals(modelID, lastModelID)) {
					// Create packet
					PacketByteBuf bufModel = PacketByteBufs.create();

					// Write model id to packet
					bufModel.writeString(modelID);

					// Send Packet to server
					ClientSidePacketRegistry.INSTANCE.sendToServer(RainworldMechanics.PLAYER_MODEL_PACKET_ID, bufModel);

					// Update the active model id
					lastModelID = modelID;
				}

				// Runs once on startup or when entering survival/adventure mode to set misc values
				if (!miscSet) {

					// Sets the initial player model
					PacketByteBuf bufModel = PacketByteBufs.create(); // Create Packet
					bufModel.writeString(modelID); // Write Model ID
					ClientSidePacketRegistry.INSTANCE.sendToServer(RainworldMechanics.PLAYER_MODEL_PACKET_ID, bufModel); // Send to server

					// Sets a boolean to true so this function only runs once
					miscSet = true;
				}
			} else if (client.player != null){ // Runs when in creative/spectator
				Keybinds.disable(); // Disables movement keybindings
				client.options.inventoryKey.setBoundKey(client.options.inventoryKey.getDefaultKey()); // Enables inventory

				if (client.options.useKey.isPressed() && client.world != null) {
					HitResult hit = client.crosshairTarget;

					switch(Objects.requireNonNull(hit).getType()) {
						case MISS:
							//nothing near enough
							break;
						case BLOCK:
							BlockHitResult blockHit = (BlockHitResult) hit;
							BlockPos blockPos = blockHit.getBlockPos();
							BlockState blockState = client.world.getBlockState(blockPos);
							Block block = blockState.getBlock();
							if (block == BlockInit.PIPE_ENTRANCE && client.player.getMainHandStack().getItem() == ItemInit.PIPE_WAND) {
								editorBlock = block.getDefaultState();
								editorPos = blockPos;
								client.setScreen(new BlockEditorScreen(Text.empty()));
							}
							break;
					}
				}
			}
		});

		// Pipe block registries
		BlockEntityRendererRegistry.register(RainworldMechanics.PIPE_BLOCK_ENTITY, PipeBlockEntityRenderer::new);
		BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getTranslucent(), BlockInit.PIPE_BLOCK);
		BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getTranslucent(), BlockInit.PIPE_ENTRANCE);

		// Color provider for pipes
		ColorProviderRegistry.BLOCK.register((state, view, pos, tintIndex) -> view != null && view.getBlockEntityRenderData(pos) instanceof Integer integer ? integer : 0x1f1f1f, BlockInit.PIPE_BLOCK);

		// Registers blocks as transparent
		BlockRenderLayerMap.INSTANCE.putBlock(BlockInit.POLE_X, RenderLayer.getCutout());
		BlockRenderLayerMap.INSTANCE.putBlock(BlockInit.POLE_Y, RenderLayer.getCutout());
		BlockRenderLayerMap.INSTANCE.putBlock(BlockInit.POLE_Z, RenderLayer.getCutout());
		BlockRenderLayerMap.INSTANCE.putBlock(BlockInit.PIPE_BLOCK, RenderLayer.getCutout());
		BlockRenderLayerMap.INSTANCE.putBlock(BlockInit.PIPE_ENTRANCE, RenderLayer.getCutout());
	}

	// Basic hibernation code could probably be improved upon
	public static void onHibernated() {
		if (foodLevel >= foodRequired && survived && !starving) { // normal successful cycle
			foodLevel = foodLevel-foodRequired;
			if (karmaLevel < maxKarmaLevel) {
				++karmaLevel;
			}
			lastStoredItem = storedItem;
			lastFoodLevel = foodLevel;
		} else if (survived && foodLevel < foodRequired && !starving) { // cylce survived but not enough food
			foodLevel = 0;
			lastFoodLevel = foodLevel;
			lastStoredItem = storedItem;
			starving = true;
		} else if (survived && foodLevel >= maxFoodLevel && starving) { // cycle survived during starvation, starvation revoked
			foodLevel = 0;
			lastFoodLevel = foodLevel;
			lastStoredItem = storedItem;
			starving = false;
		} else if (survived && foodLevel <= maxFoodLevel && starving) { // cycle failed, starvation
			if (karmaLevel > 0) {
				--karmaLevel;
			}
			survived = false;
		}
		if (!survived) { // if dead take karma
			foodLevel = lastFoodLevel;
			if (karmaLevel > 0 && !karmaShielded) {
				--karmaLevel;
			} else {
				karmaShielded = false;
			}
		}
	}

	public static void playerDeath() { // function that could be used instead of actually killing the player
		survived = false;
	}
}
