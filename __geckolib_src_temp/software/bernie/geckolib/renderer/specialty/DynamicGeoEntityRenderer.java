package software.bernie.geckolib.renderer.specialty;

import it.unimi.dsi.fastutil.ints.IntIntPair;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.*;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.RenderUtil;

import java.util.Map;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;

/**
 * Extended special-entity renderer for more advanced or dynamic models
 * <p>
 * Because of the extra performance cost of this renderer, it is advised to avoid using it unnecessarily,
 * and consider whether the benefits are worth the cost for your needs.
 */
public abstract class DynamicGeoEntityRenderer<T extends Entity & GeoAnimatable> extends GeoEntityRenderer<T> {
	protected static Map<Identifier, IntIntPair> TEXTURE_DIMENSIONS_CACHE = new Object2ObjectOpenHashMap<>();

	protected Identifier textureOverride = null;

	public DynamicGeoEntityRenderer(EntityRendererFactory.Context renderManager, GeoModel<T> model) {
		super(renderManager, model);
	}

	/**
	 * For each bone rendered, this method is called
	 * <p>
	 * If a ResourceLocation is returned, the renderer will render the bone using that texture instead of the default
	 * This can be useful for custom rendering  on a per-bone basis
	 * <p>
	 * There is a somewhat significant performance cost involved in this however, so only use as needed
	 *
	 * @return The specified ResourceLocation, or null if no override
	 */
	@Nullable
	protected Identifier getTextureOverrideForBone(GeoBone bone, T animatable, float partialTick) {
		return null;
	}

	/**
	 * For each bone rendered, this method is called
	 * <p>
	 * If a RenderType is returned, the renderer will render the bone using that RenderType instead of the default
	 * This can be useful for custom rendering operations on a per-bone basis
	 * <p>
	 * There is a somewhat significant performance cost involved in this however, so only use as needed
	 *
	 * @return The specified RenderType, or null if no override
	 */
	@Nullable
	protected RenderLayer getRenderTypeOverrideForBone(GeoBone bone, T animatable, Identifier texturePath, VertexConsumerProvider bufferSource, float partialTick) {
		return null;
	}

	/**
	 * Override this to handle a given {@link GeoBone GeoBone's} rendering in a particular way
	 *
	 * @return Whether the renderer should skip rendering the {@link GeoCube cubes} of the given GeoBone or not
	 */
	protected boolean boneRenderOverride(MatrixStack poseStack, GeoBone bone, VertexConsumerProvider bufferSource, VertexConsumer buffer,
										 float partialTick, int packedLight, int packedOverlay, int colour) {
		return false;
	}

	/**
	 * Renders the provided {@link GeoBone} and its associated child bones
	 */
	@Override
	public void renderRecursively(MatrixStack poseStack, T animatable, GeoBone bone, RenderLayer renderType, VertexConsumerProvider bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		poseStack.push();
		RenderUtil.translateMatrixToBone(poseStack, bone);
		RenderUtil.translateToPivotPoint(poseStack, bone);
		RenderUtil.rotateMatrixAroundBone(poseStack, bone);
		RenderUtil.scaleMatrixForBone(poseStack, bone);

		if (bone.isTrackingMatrices()) {
			Matrix4f poseState = new Matrix4f(poseStack.peek().getPositionMatrix());
			Matrix4f localMatrix = RenderUtil.invertAndMultiplyMatrices(poseState, this.entityRenderTranslations);

			bone.setModelSpaceMatrix(RenderUtil.invertAndMultiplyMatrices(poseState, this.modelRenderTranslations));
			localMatrix.translate(new Vector3f(getPositionOffset(this.animatable, 1).toVector3f()));
			bone.setLocalSpaceMatrix(localMatrix);

			Matrix4f worldState = new Matrix4f(localMatrix);

			worldState.translate(new Vector3f(this.animatable.getPos().toVector3f()));
			bone.setWorldSpaceMatrix(worldState);
		}

		RenderUtil.translateAwayFromPivotPoint(poseStack, bone);

		this.textureOverride = getTextureOverrideForBone(bone, this.animatable, partialTick);
		Identifier texture = this.textureOverride == null ? getTexture(this.animatable) : this.textureOverride;
		RenderLayer renderTypeOverride = getRenderTypeOverrideForBone(bone, this.animatable, texture, bufferSource, partialTick);

		if (texture != null && renderTypeOverride == null)
			renderTypeOverride = getRenderType(this.animatable, texture, bufferSource, partialTick);

		if (renderTypeOverride != null)
			buffer = bufferSource.getBuffer(renderTypeOverride);

		if (!boneRenderOverride(poseStack, bone, bufferSource, buffer, partialTick, packedLight, packedOverlay, colour))
			super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);

		if (renderTypeOverride != null)
			buffer = bufferSource.getBuffer(renderType);

		if (!isReRender)
			applyRenderLayersForBone(poseStack, animatable, bone, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay);

		buffer = checkAndRefreshBuffer(isReRender, buffer, bufferSource, renderType);

		super.renderChildBones(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);

		poseStack.pop();
	}

	/**
	 * Called after rendering the model to buffer. Post-render modifications should be performed here
	 * <p>
	 * {@link MatrixStack} transformations will be unused and lost once this method ends
	 */
	@Override
	public void postRender(MatrixStack poseStack, T animatable, BakedGeoModel model, VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		this.textureOverride = null;

		super.postRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
	}

	/**
	 * Applies the {@link GeoQuad Quad's} {@link GeoVertex vertices} to the given {@link VertexConsumer buffer} for rendering
	 * <p>
	 * Custom override to handle custom non-baked textures for DynamicGeoEntityRenderer
	 */
	@Override
	public void createVerticesOfQuad(GeoQuad quad, Matrix4f poseState, Vector3f normal, VertexConsumer buffer,
									 int packedLight, int packedOverlay, int colour) {
		if (this.textureOverride == null) {
			super.createVerticesOfQuad(quad, poseState, normal, buffer, packedLight, packedOverlay,
					colour);

			return;
		}

		IntIntPair boneTextureSize = computeTextureSize(this.textureOverride);
		IntIntPair entityTextureSize = computeTextureSize(getTexture(this.animatable));

		if (boneTextureSize == null || entityTextureSize == null) {
			super.createVerticesOfQuad(quad, poseState, normal, buffer, packedLight, packedOverlay,
					colour);

			return;
		}

		for (GeoVertex vertex : quad.vertices()) {
			Vector4f vector4f = poseState.transform(new Vector4f(vertex.position().x(), vertex.position().y(), vertex.position().z(), 1.0f));
			float texU = (vertex.texU() * entityTextureSize.firstInt()) / boneTextureSize.firstInt();
			float texV = (vertex.texV() * entityTextureSize.secondInt()) / boneTextureSize.secondInt();

			buffer.vertex(vector4f.x(), vector4f.y(), vector4f.z(), colour, texU, texV,
					packedOverlay, packedLight, normal.x(), normal.y(), normal.z());
		}
	}

	/**
	 * Retrieve or compute the height and width of a given texture from its {@link Identifier}
	 * <p>
	 * This is used for dynamically mapping vertices on a given quad
	 * <p>
	 * This is inefficient however, and should only be used where required
	 */
	protected IntIntPair computeTextureSize(Identifier texture) {
		return TEXTURE_DIMENSIONS_CACHE.computeIfAbsent(texture, RenderUtil::getTextureDimensions);
	}
}