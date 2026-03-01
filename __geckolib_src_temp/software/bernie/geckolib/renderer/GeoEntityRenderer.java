package software.bernie.geckolib.renderer;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import software.bernie.geckolib.GeckoLibServices;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.texture.AnimatableTexture;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayersContainer;
import software.bernie.geckolib.util.ClientUtil;
import software.bernie.geckolib.util.Color;
import software.bernie.geckolib.util.RenderUtil;

import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

/**
 * Base {@link GeoRenderer} class for rendering {@link Entity Entities} specifically
 * <p>
 * All entities added to be rendered by GeckoLib should use an instance of this class
 * <p>
 * This also includes {@link net.minecraft.entity.projectile.ProjectileEntity Projectiles}
 */
public class GeoEntityRenderer<T extends Entity & GeoAnimatable> extends EntityRenderer<T> implements GeoRenderer<T> {
	protected final GeoRenderLayersContainer<T> renderLayers = new GeoRenderLayersContainer<>(this);
	protected final GeoModel<T> model;

	protected T animatable;
	protected float scaleWidth = 1;
	protected float scaleHeight = 1;

	protected Matrix4f entityRenderTranslations = new Matrix4f();
	protected Matrix4f modelRenderTranslations = new Matrix4f();

	public GeoEntityRenderer(EntityRendererFactory.Context renderManager, GeoModel<T> model) {
		super(renderManager);

		this.model = model;
	}

	/**
	 * Gets the model instance for this renderer
	 */
	@Override
	public GeoModel<T> getGeoModel() {
		return this.model;
	}

	/**
	 * Gets the {@link GeoAnimatable} instance currently being rendered
	 */
	@Override
	public T getAnimatable() {
		return this.animatable;
	}

	/**
	 * Gets the id that represents the current animatable's instance for animation purposes
	 * <p>
	 * This is mostly useful for things like items, which have a single registered instance for all objects
	 */
	@Override
	public long getInstanceId(T animatable) {
		return animatable.getId();
	}

	/**
	 * Shadowing override of {@link EntityRenderer#getTexture}
	 * <p>
	 * This redirects the call to {@link GeoRenderer#getTextureLocation}
	 */
	@Override
	public Identifier getTexture(T animatable) {
		return GeoRenderer.super.getTextureLocation(animatable);
	}

	/**
	 * Returns the list of registered {@link GeoRenderLayer GeoRenderLayers} for this renderer
	 */
	@Override
	public List<GeoRenderLayer<T>> getRenderLayers() {
		return this.renderLayers.getRenderLayers();
	}

	/**
	 * Adds a {@link GeoRenderLayer} to this renderer, to be called after the main model is rendered each frame
	 */
	public GeoEntityRenderer<T> addRenderLayer(GeoRenderLayer<T> renderLayer) {
		this.renderLayers.addLayer(renderLayer);

		return this;
	}

	/**
	 * Sets a scale override for this renderer, telling GeckoLib to pre-scale the model
	 */
	public GeoEntityRenderer<T> withScale(float scale) {
		return withScale(scale, scale);
	}

	/**
	 * Sets a scale override for this renderer, telling GeckoLib to pre-scale the model
	 */
	public GeoEntityRenderer<T> withScale(float scaleWidth, float scaleHeight) {
		this.scaleWidth = scaleWidth;
		this.scaleHeight = scaleHeight;

		return this;
	}

	/**
	 * Gets a tint-applying color to render the given animatable with
	 * <p>
	 * Returns {@link Color#WHITE} by default, modified for invisibility in spectator
	 */
	@Override
	public Color getRenderColor(T animatable, float partialTick, int packedLight) {
		Color color = GeoRenderer.super.getRenderColor(animatable, partialTick, packedLight);

		if (animatable.isInvisible() && !animatable.isInvisibleTo(ClientUtil.getClientPlayer()))
			color = Color.ofARGB(MathHelper.ceil(color.getAlpha() * 38 / 255f), color.getRed(), color.getGreen(), color.getBlue());

		return color;
	}

	/**
	 * Gets the {@link RenderLayer} to render the given animatable with
	 * <p>
	 * Uses the {@link RenderLayer#getEntityCutoutNoCull} {@code RenderType} by default
	 * <p>
	 * Override this to change the way a model will render (such as translucent models, etc).
	 *
	 * @return Return the RenderType to use, or null to prevent the model rendering. Returning null will not prevent animation functions taking place
	 */
	@Nullable
	@Override
	public RenderLayer getRenderType(T animatable, Identifier texture, @Nullable VertexConsumerProvider bufferSource, float partialTick) {
		final boolean invisible = animatable.isInvisible();

		if (invisible && !animatable.isInvisibleTo(ClientUtil.getClientPlayer()))
			return RenderLayer.getItemEntityTranslucentCull(texture);

		if (!invisible)
			return GeoRenderer.super.getRenderType(animatable, texture, bufferSource, partialTick);

		return MinecraftClient.getInstance().hasOutline(animatable) ? RenderLayer.getOutline(texture) : null;
	}

	/**
	 * Called before rendering the model to buffer. Allows for render modifications and preparatory work such as scaling and translating
	 * <p>
	 * {@link MatrixStack} translations made here are kept until the end of the render process
	 */
	@Override
	public void preRender(MatrixStack poseStack, T animatable, BakedGeoModel model, @Nullable VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		this.entityRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick, packedLight, packedOverlay);
	}

	@Override
	@ApiStatus.Internal
	public void render(T entity, float entityYaw, float partialTick, MatrixStack poseStack, VertexConsumerProvider bufferSource, int packedLight) {
		this.animatable = entity;

		defaultRender(poseStack, entity, bufferSource, null, null, entityYaw, partialTick, packedLight);

		this.animatable = null;
	}

	/**
	 * The actual render method that subtype renderers should override to handle their specific rendering tasks
	 * <p>
	 * {@link GeoRenderer#preRender} has already been called by this stage, and {@link GeoRenderer#postRender} will be called directly after
	 */
	@Override
	public void actuallyRender(MatrixStack poseStack, T animatable, BakedGeoModel model, @Nullable RenderLayer renderType,
							   VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick,
							   int packedLight, int packedOverlay, int colour) {
		poseStack.push();

		LivingEntity livingEntity = animatable instanceof LivingEntity entity ? entity : null;
		boolean shouldSit = animatable.hasVehicle() && (animatable.getVehicle() != null);
		float lerpBodyRot = livingEntity == null ? 0 : MathHelper.lerpAngleDegrees(partialTick, livingEntity.prevBodyYaw, livingEntity.bodyYaw);
		float lerpHeadRot = livingEntity == null ? 0 : MathHelper.lerpAngleDegrees(partialTick, livingEntity.prevHeadYaw, livingEntity.headYaw);
		float netHeadYaw = lerpHeadRot - lerpBodyRot;

		if (shouldSit && animatable.getVehicle() instanceof LivingEntity livingentity) {
			lerpBodyRot = MathHelper.lerpAngleDegrees(partialTick, livingentity.prevBodyYaw, livingentity.bodyYaw);
			netHeadYaw = lerpHeadRot - lerpBodyRot;
			float clampedHeadYaw = MathHelper.clamp(MathHelper.wrapDegrees(netHeadYaw), -85, 85);
			lerpBodyRot = lerpHeadRot - clampedHeadYaw;

			if (clampedHeadYaw * clampedHeadYaw > 2500f)
				lerpBodyRot += clampedHeadYaw * 0.2f;

			netHeadYaw = lerpHeadRot - lerpBodyRot;
		}

		if (animatable.getPose() == EntityPose.SLEEPING && livingEntity != null) {
			Direction bedDirection = livingEntity.getSleepingDirection();

			if (bedDirection != null) {
				float eyePosOffset = livingEntity.getEyeHeight(EntityPose.STANDING) - 0.1F;

				poseStack.translate(-bedDirection.getOffsetX() * eyePosOffset, 0, -bedDirection.getOffsetZ() * eyePosOffset);
			}
		}

		float nativeScale = livingEntity != null ? livingEntity.getScale() : 1;
		float ageInTicks = animatable.age + partialTick;
		float limbSwingAmount = 0;
		float limbSwing = 0;

		poseStack.scale(nativeScale, nativeScale, nativeScale);
		applyRotations(animatable, poseStack, ageInTicks, lerpBodyRot, partialTick, nativeScale);

		if (!shouldSit && animatable.isAlive() && livingEntity != null) {
			limbSwingAmount = livingEntity.limbAnimator.getSpeed(partialTick);
			limbSwing = livingEntity.limbAnimator.getPos(partialTick);

			if (livingEntity.isBaby())
				limbSwing *= 3f;

			if (limbSwingAmount > 1f)
				limbSwingAmount = 1f;
		}

		if (!isReRender) {
			float headPitch = MathHelper.lerp(partialTick, animatable.prevPitch, animatable.getPitch());
			float motionThreshold = getMotionAnimThreshold(animatable);
			Vec3d velocity = animatable.getVelocity();
			float avgVelocity = (float)((Math.abs(velocity.x) + Math.abs(velocity.z)) / 2f);
			AnimationState<T> animationState = new AnimationState<T>(animatable, limbSwing, limbSwingAmount, partialTick, avgVelocity >= motionThreshold && limbSwingAmount != 0);
			long instanceId = getInstanceId(animatable);
			GeoModel<T> currentModel = getGeoModel();

			animationState.setData(DataTickets.TICK, animatable.getTick(animatable));
			animationState.setData(DataTickets.ENTITY, animatable);
			animationState.setData(DataTickets.ENTITY_MODEL_DATA, new EntityModelData(shouldSit, livingEntity != null && livingEntity.isBaby(), -netHeadYaw, -headPitch));
			currentModel.addAdditionalStateData(animatable, instanceId, animationState::setData);
			currentModel.handleAnimations(animatable, instanceId, animationState, partialTick);
		}

		poseStack.translate(0, 0.01f, 0);

		this.modelRenderTranslations = new Matrix4f(poseStack.peek().getPositionMatrix());

		if (buffer != null)
			GeoRenderer.super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, isReRender, partialTick,
					packedLight, packedOverlay, colour);

		poseStack.pop();
	}

	/**
	 * Render the various {@link GeoRenderLayer RenderLayers} that have been registered to this renderer
	 */
	@Override
	public void applyRenderLayers(MatrixStack poseStack, T animatable, BakedGeoModel model, @Nullable RenderLayer renderType,
								  VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, float partialTick,
								  int packedLight, int packedOverlay) {
		if (!animatable.isSpectator())
			GeoRenderer.super.applyRenderLayers(poseStack, animatable, model, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay);
	}

	/**
	 * Call after all other rendering work has taken place, including reverting the {@link MatrixStack}'s state
	 * <p>
	 * This method is <u>not</u> called in {@link GeoRenderer#reRender re-render}
	 */
	@Override
	public void renderFinal(MatrixStack poseStack, T animatable, BakedGeoModel model, VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay, int colour) {
		super.render(animatable, 0, partialTick, poseStack, bufferSource, packedLight);

		if (animatable instanceof MobEntity mob) {
			Entity leashHolder = mob.getLeashHolder();

			if (leashHolder != null)
				renderLeash(mob, partialTick, poseStack, bufferSource, leashHolder);
		}
	}

	/**
	 * Called after all render operations are completed and the render pass is considered functionally complete.
	 * <p>
	 * Use this method to clean up any leftover persistent objects stored during rendering or any other post-render maintenance tasks as required
	 */
	@Override
	public void doPostRenderCleanup() {
		this.animatable = null;
	}

	/**
	 * Renders the provided {@link GeoBone} and its associated child bones
	 */
	@Override
	public void renderRecursively(MatrixStack poseStack, T animatable, GeoBone bone, RenderLayer renderType, VertexConsumerProvider bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight,
								  int packedOverlay, int colour) {
		poseStack.push();
		RenderUtil.translateMatrixToBone(poseStack, bone);
		RenderUtil.translateToPivotPoint(poseStack, bone);
		RenderUtil.rotateMatrixAroundBone(poseStack, bone);
		RenderUtil.scaleMatrixForBone(poseStack, bone);

		if (bone.isTrackingMatrices()) {
			Matrix4f poseState = new Matrix4f(poseStack.peek().getPositionMatrix());
			Matrix4f localMatrix = RenderUtil.invertAndMultiplyMatrices(poseState, this.entityRenderTranslations);

			bone.setModelSpaceMatrix(RenderUtil.invertAndMultiplyMatrices(poseState, this.modelRenderTranslations));
			bone.setLocalSpaceMatrix(RenderUtil.translateMatrix(localMatrix, getPositionOffset(this.animatable, 1).toVector3f()));
			bone.setWorldSpaceMatrix(RenderUtil.translateMatrix(new Matrix4f(localMatrix), this.animatable.getPos().toVector3f()));
		}

		RenderUtil.translateAwayFromPivotPoint(poseStack, bone);

		buffer = checkAndRefreshBuffer(isReRender, buffer, bufferSource, renderType);

		renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);

		if (!isReRender)
			applyRenderLayersForBone(poseStack, animatable, bone, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay);

		renderChildBones(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);

		poseStack.pop();
	}

	/**
	 * Applies rotation transformations to the renderer prior to render time to account for various entity states
	 * @deprecated Use {@link #applyRotations(Entity, MatrixStack, float, float, float, float)}
	 */
	@Deprecated(forRemoval = true)
	protected void applyRotations(T animatable, MatrixStack poseStack, float ageInTicks, float rotationYaw,
			float partialTick) {
		applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, 1);
	}

	/**
	 * Applies rotation transformations to the renderer prior to render time to account for various entity states
	 */
	protected void applyRotations(T animatable, MatrixStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
		if (isShaking(animatable))
			rotationYaw += (float)(Math.cos(animatable.age * 3.25d) * Math.PI * 0.4d);

		if (!animatable.isInPose(EntityPose.SLEEPING))
			poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - rotationYaw));

		if (animatable instanceof LivingEntity livingEntity) {
			if (livingEntity.deathTime > 0) {
				float deathRotation = (livingEntity.deathTime + partialTick - 1f) / 20f * 1.6f;

				poseStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(Math.min(MathHelper.sqrt(deathRotation), 1) * getDeathMaxRotation(animatable)));
			}
			else if (livingEntity.isUsingRiptide()) {
				poseStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f - livingEntity.getPitch()));
				poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((livingEntity.age + partialTick) * -75f));
			}
			else if (animatable.isInPose(EntityPose.SLEEPING)) {
				Direction bedOrientation = livingEntity.getSleepingDirection();

				poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(bedOrientation != null ? RenderUtil.getDirectionAngle(bedOrientation) : rotationYaw));
				poseStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(getDeathMaxRotation(animatable)));
				poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(270f));
			}
			else if (LivingEntityRenderer.shouldFlipUpsideDown(livingEntity)) {
				poseStack.translate(0, (animatable.getHeight() + 0.1f) / nativeScale, 0);
				poseStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180f));
			}
		}
	}

	/**
	 * Gets the max rotation value for dying entities
	 * <p>
	 * You might want to modify this for different aesthetics, such as a {@link net.minecraft.entity.mob.SpiderEntity} flipping upside down on death
	 * <p>
	 * Functionally equivalent to {@link net.minecraft.client.render.entity.LivingEntityRenderer#getLyingAngle}
	 */
	protected float getDeathMaxRotation(T animatable) {
		return 90f;
	}

	/**
	 * Get the maximum distance (in blocks) that an entity's nameplate should be visible.
	 * <p>This is only a short-circuit predicate, and other conditions after this check must be also passed in order for the name to render</p>
	 */
	public double getNameRenderCutoffDistance(T animatable) {
		return animatable.isSneaky() ? 32d : 64d;
	}

	/**
	 * Whether the entity's nametag should be rendered or not
	 * <p>
	 * Pretty much exclusively used in {@link EntityRenderer#renderLabelIfPresent}
	 */
	@Override
	public boolean hasLabel(T animatable) {
		if (!(animatable instanceof LivingEntity))
			return super.hasLabel(animatable);

		double nameRenderCutoff = getNameRenderCutoffDistance(animatable);

		if (this.dispatcher.getSquaredDistanceToCamera(animatable) >= nameRenderCutoff * nameRenderCutoff)
			return false;

		if (animatable instanceof MobEntity && (!animatable.shouldRenderName() && (!animatable.hasCustomName() || animatable != this.dispatcher.targetedEntity)))
			return false;

		final MinecraftClient minecraft = MinecraftClient.getInstance();
		boolean visibleToClient = !animatable.isInvisibleTo(minecraft.player);
		AbstractTeam entityTeam = animatable.getScoreboardTeam();

		if (entityTeam == null)
			return MinecraftClient.isHudEnabled() && animatable != minecraft.getCameraEntity() && visibleToClient && !animatable.hasPassengers();

		AbstractTeam playerTeam = minecraft.player.getScoreboardTeam();

		return switch (entityTeam.getNameTagVisibilityRule()) {
			case ALWAYS -> visibleToClient;
			case NEVER -> false;
			case HIDE_FOR_OTHER_TEAMS -> playerTeam == null ? visibleToClient : entityTeam.isEqual(playerTeam) && (entityTeam.shouldShowFriendlyInvisibles() || visibleToClient);
			case HIDE_FOR_OWN_TEAM -> playerTeam == null ? visibleToClient : !entityTeam.isEqual(playerTeam) && visibleToClient;
		};
	}

	/**
	 * Gets a packed overlay coordinate pair for rendering
	 * <p>
	 * Mostly just used for the red tint when an entity is hurt,
	 * but can be used for other things like the {@link net.minecraft.entity.mob.CreeperEntity}
	 * white tint when exploding.
	 */
	@Override
	public int getPackedOverlay(T animatable, float u, float partialTick) {
		if (!(animatable instanceof LivingEntity entity))
			return OverlayTexture.DEFAULT_UV;

		return OverlayTexture.packUv(OverlayTexture.getU(u),
				OverlayTexture.getV(entity.hurtTime > 0 || entity.deathTime > 0));
	}

	/**
	 * Whether the entity is currently shaking. This is usually used for freezing, but also for things like piglin conversion or striders suffocating
	 * <p>
	 * This is used for a shaking effect while rendering
	 * @see net.minecraft.client.render.entity.LivingEntityRenderer#isShaking(LivingEntity)
	 */
	public boolean isShaking(T animatable) {
		return animatable.isFrozen();
	}

	/**
	 * Static rendering code for rendering a leash segment
	 * <p>
	 * It's a like-for-like from {@link net.minecraft.client.render.entity.MobEntityRenderer#renderLeash} that had to be duplicated here for flexible usage
	 */
	public <E extends Entity, M extends MobEntity> void renderLeash(M mob, float partialTick, MatrixStack poseStack,
			VertexConsumerProvider bufferSource, E leashHolder) {
		double lerpBodyAngle = (MathHelper.lerp(partialTick, mob.prevBodyYaw, mob.bodyYaw) * MathHelper.RADIANS_PER_DEGREE) + MathHelper.HALF_PI;
		Vec3d leashOffset = mob.getLeashOffset(partialTick);
		double xAngleOffset = Math.cos(lerpBodyAngle) * leashOffset.z + Math.sin(lerpBodyAngle) * leashOffset.x;
		double zAngleOffset = Math.sin(lerpBodyAngle) * leashOffset.z - Math.cos(lerpBodyAngle) * leashOffset.x;
		double lerpOriginX = MathHelper.lerp(partialTick, mob.prevX, mob.getX()) + xAngleOffset;
		double lerpOriginY = MathHelper.lerp(partialTick, mob.prevY, mob.getY()) + leashOffset.y;
		double lerpOriginZ = MathHelper.lerp(partialTick, mob.prevZ, mob.getZ()) + zAngleOffset;
		Vec3d ropeGripPosition = leashHolder.getLeashPos(partialTick);
		float xDif = (float)(ropeGripPosition.x - lerpOriginX);
		float yDif = (float)(ropeGripPosition.y - lerpOriginY);
		float zDif = (float)(ropeGripPosition.z - lerpOriginZ);
		float offsetMod = MathHelper.inverseSqrt(xDif * xDif + zDif * zDif) * 0.025f / 2f;
		float xOffset = zDif * offsetMod;
		float zOffset = xDif * offsetMod;
		VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderLayer.getLeash());
		BlockPos entityEyePos = BlockPos.ofFloored(mob.getCameraPosVec(partialTick));
		BlockPos holderEyePos = BlockPos.ofFloored(leashHolder.getCameraPosVec(partialTick));
		int entityBlockLight = getBlockLight((T)mob, entityEyePos);
		int holderBlockLight = leashHolder.isOnFire() ? 15 : leashHolder.getWorld().getLightLevel(LightType.BLOCK, holderEyePos);
		int entitySkyLight = mob.getWorld().getLightLevel(LightType.SKY, entityEyePos);
		int holderSkyLight = mob.getWorld().getLightLevel(LightType.SKY, holderEyePos);

		poseStack.push();
		poseStack.translate(xAngleOffset, leashOffset.y, zAngleOffset);

		Matrix4f posMatrix = new Matrix4f(poseStack.peek().getPositionMatrix());

		for (int segment = 0; segment <= 24; ++segment) {
			GeoEntityRenderer.renderLeashPiece(vertexConsumer, posMatrix, xDif, yDif, zDif, entityBlockLight, holderBlockLight,
					entitySkyLight, holderSkyLight, 0.025f, 0.025f, xOffset, zOffset, segment, false);
		}

		for (int segment = 24; segment >= 0; --segment) {
			GeoEntityRenderer.renderLeashPiece(vertexConsumer, posMatrix, xDif, yDif, zDif, entityBlockLight, holderBlockLight,
					entitySkyLight, holderSkyLight, 0.025f, 0.0f, xOffset, zOffset, segment, true);
		}

		poseStack.pop();
	}

	/**
	 * Static rendering code for rendering a leash segment
	 * <p>
	 * It's a like-for-like from {@link EntityRenderer#renderLeashSegment} that had to be duplicated here for flexible usage
	 */
	private static void renderLeashPiece(VertexConsumer buffer, Matrix4f positionMatrix, float xDif, float yDif,
										 float zDif, int entityBlockLight, int holderBlockLight, int entitySkyLight,
										 int holderSkyLight, float width, float yOffset, float xOffset, float zOffset, int segment, boolean isLeashKnot) {
		float piecePosPercent = segment / 24f;
		int lerpBlockLight = (int)MathHelper.lerp(piecePosPercent, entityBlockLight, holderBlockLight);
		int lerpSkyLight = (int)MathHelper.lerp(piecePosPercent, entitySkyLight, holderSkyLight);
		int packedLight = LightmapTextureManager.pack(lerpBlockLight, lerpSkyLight);
		float knotColourMod = segment % 2 == (isLeashKnot ? 1 : 0) ? 0.7f : 1f;
		float red = 0.5f * knotColourMod;
		float green = 0.4f * knotColourMod;
		float blue = 0.3f * knotColourMod;
		float x = xDif * piecePosPercent;
		float y = yDif > 0.0f ? yDif * piecePosPercent * piecePosPercent : yDif - yDif * (1.0f - piecePosPercent) * (1.0f - piecePosPercent);
		float z = zDif * piecePosPercent;

		buffer.vertex(positionMatrix, x - xOffset, y + yOffset, z + zOffset).color(red, green, blue, 1).light(packedLight);
		buffer.vertex(positionMatrix, x + xOffset, y + width - yOffset, z - zOffset).color(red, green, blue, 1).light(packedLight);
	}

	/**
	 * Update the current frame of a {@link AnimatableTexture potentially animated} texture used by this GeoRenderer
	 * <p>
	 * This should only be called immediately prior to rendering
	 *
	 * @see AnimatableTexture#setAndUpdate
	 */
	@Override
	public void updateAnimatedTextureFrame(T animatable) {
		AnimatableTexture.setAndUpdate(getTexture(animatable));
	}

	/**
	 * Create and fire the relevant {@code CompileLayers} event hook for this renderer
	 */
	@Override
	public void fireCompileRenderLayersEvent() {
		GeckoLibServices.Client.EVENTS.fireCompileEntityRenderLayers(this);
	}

	/**
	 * Create and fire the relevant {@code Pre-Render} event hook for this renderer
	 *
	 * @return Whether the renderer should proceed based on the cancellation state of the event
	 */
	@Override
	public boolean firePreRenderEvent(MatrixStack poseStack, BakedGeoModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		return GeckoLibServices.Client.EVENTS.fireEntityPreRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}

	/**
	 * Create and fire the relevant {@code Post-Render} event hook for this renderer
	 */
	@Override
	public void firePostRenderEvent(MatrixStack poseStack, BakedGeoModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
		GeckoLibServices.Client.EVENTS.fireEntityPostRender(this, poseStack, model, bufferSource, partialTick, packedLight);
	}
}
