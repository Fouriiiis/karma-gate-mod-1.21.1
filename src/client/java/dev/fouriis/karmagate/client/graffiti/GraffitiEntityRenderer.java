package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.GraffitiEntity;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders graffiti as a decal projected onto block surfaces.
 *
 * IMPORTANT:
 * - UVs are NOT clamped.
 * - The graffiti shader MUST discard when uv is outside [0..1] to prevent tiling.
 */
public class GraffitiEntityRenderer extends EntityRenderer<GraffitiEntity> {

    private static final int MAX_DEPTH = 3;
    private static final float DECAL_OFFSET = 0.002f;

    public GraffitiEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    private Identifier getEntityTexture(GraffitiEntity entity) {
        String texturePath = entity.getTexturePath();
        return Identifier.of(KarmaGateMod.MOD_ID, "textures/graffiti/" + texturePath);
    }

    @Override
    public Identifier getTexture(GraffitiEntity entity) {
        return getEntityTexture(entity);
    }

    @Override
    public void render(GraffitiEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {

        Direction facing = entity.getFacing();
        World world = entity.getWorld();
        if (world == null) return;

        double entityX = entity.getX();
        double entityY = entity.getY();
        double entityZ = entity.getZ();

        Identifier texture = getEntityTexture(entity);
        VertexConsumer vc = vertexConsumers.getBuffer(GraffitiRenderLayer.get(texture));
        Matrix4f mat = matrices.peek().getPositionMatrix();

        float[][] entityCorners = entity.getCorners();

        float minH = Float.MAX_VALUE, maxH = Float.MIN_VALUE;
        float minV = Float.MAX_VALUE, maxV = Float.MIN_VALUE;
        for (float[] corner : entityCorners) {
            minH = Math.min(minH, corner[0]);
            maxH = Math.max(maxH, corner[0]);
            minV = Math.min(minV, corner[1]);
            maxV = Math.max(maxV, corner[1]);
        }

        Direction rightDir = facing.rotateYClockwise();

        int minBlockH = MathHelper.floor(minH) - 1;
        int maxBlockH = MathHelper.ceil(maxH) + 1;
        int minBlockV = MathHelper.floor(minV) - 1;
        int maxBlockV = MathHelper.ceil(maxV) + 1;

        List<DecalFace> faces = new ArrayList<>();

        for (int h = minBlockH; h <= maxBlockH; h++) {
            for (int v = minBlockV; v <= maxBlockV; v++) {
                for (int d = 0; d < MAX_DEPTH; d++) {
                    double blockWorldX = entityX + rightDir.getOffsetX() * h + facing.getOffsetX() * d;
                    double blockWorldY = entityY + v;
                    double blockWorldZ = entityZ + rightDir.getOffsetZ() * h + facing.getOffsetZ() * d;

                    BlockPos blockPos = new BlockPos(
                        MathHelper.floor(blockWorldX),
                        MathHelper.floor(blockWorldY),
                        MathHelper.floor(blockWorldZ)
                    );

                    BlockState state = world.getBlockState(blockPos);
                    if (state.isAir()) continue;

                    BakedModel model = MinecraftClient.getInstance().getBlockRenderManager().getModel(state);
                    if (model == null) continue;

                    // Stable seeded random so model variants don't flicker.
                    Random seeded = Random.create(blockPos.asLong());

                    extractFacesFromModel(
                        faces, model, state, blockPos, facing,
                        entityX, entityY, entityZ, entityCorners, world, seeded
                    );
                }
            }
        }

        for (DecalFace face : faces) {
            renderFace(mat, vc, face, world);
        }
    }

    private void extractFacesFromModel(List<DecalFace> faces, BakedModel model, BlockState state,
                                       BlockPos blockPos, Direction entityFacing,
                                       double entityX, double entityY, double entityZ,
                                       float[][] entityCorners, World world, Random random) {

        Direction[] directionsToCheck = {
            entityFacing.getOpposite(),
            entityFacing.rotateYClockwise(),
            entityFacing.rotateYCounterclockwise(),
            Direction.UP,
            Direction.DOWN
        };

        for (Direction dir : directionsToCheck) {
            List<BakedQuad> quads = model.getQuads(state, dir, random);
            for (BakedQuad quad : quads) {
                DecalFace face = extractFaceFromQuad(
                    quad, blockPos, dir, entityFacing,
                    entityX, entityY, entityZ, entityCorners, world
                );
                if (face != null) faces.add(face);
            }
        }

        List<BakedQuad> unculledQuads = model.getQuads(state, null, random);
        for (BakedQuad quad : unculledQuads) {
            Direction quadDir = quad.getFace();
            if (isRelevantDirection(quadDir, entityFacing)) {
                DecalFace face = extractFaceFromQuad(
                    quad, blockPos, quadDir, entityFacing,
                    entityX, entityY, entityZ, entityCorners, world
                );
                if (face != null) faces.add(face);
            }
        }
    }

    private boolean isRelevantDirection(Direction quadDir, Direction entityFacing) {
        return quadDir == entityFacing.getOpposite()
            || quadDir == entityFacing.rotateYClockwise()
            || quadDir == entityFacing.rotateYCounterclockwise()
            || quadDir == Direction.UP
            || quadDir == Direction.DOWN;
    }

    private DecalFace extractFaceFromQuad(BakedQuad quad, BlockPos blockPos, Direction quadDir,
                                          Direction entityFacing, double entityX, double entityY, double entityZ,
                                          float[][] entityCorners, World world) {

        int[] vertexData = quad.getVertexData();
        double[][] corners = new double[4][3];

        int vertexSize = 8; // x,y,z,color,u,v,light,normal (we only read position)
        double bx = blockPos.getX();
        double by = blockPos.getY();
        double bz = blockPos.getZ();

        for (int i = 0; i < 4; i++) {
            int offset = i * vertexSize;
            float x = Float.intBitsToFloat(vertexData[offset]);
            float y = Float.intBitsToFloat(vertexData[offset + 1]);
            float z = Float.intBitsToFloat(vertexData[offset + 2]);

            corners[i][0] = bx + x - entityX;
            corners[i][1] = by + y - entityY;
            corners[i][2] = bz + z - entityZ;
        }

        float nx = quadDir.getOffsetX();
        float ny = quadDir.getOffsetY();
        float nz = quadDir.getOffsetZ();

        for (int i = 0; i < 4; i++) {
            corners[i][0] += nx * DECAL_OFFSET;
            corners[i][1] += ny * DECAL_OFFSET;
            corners[i][2] += nz * DECAL_OFFSET;
        }

        if (wouldQuadBeOccluded(world, blockPos, quadDir)) return null;

        float[][] uvs = computeQuadUVs(corners, entityCorners, entityFacing);

        // Cheap reject: if the UV bounding box doesn't overlap [0..1] at all, skip this quad.
        if (!overlapsUnitSquare(uvs)) return null;

        BlockPos lightPos = blockPos.offset(quadDir);
        return new DecalFace(corners, uvs, lightPos);
    }

    private boolean wouldQuadBeOccluded(World world, BlockPos blockPos, Direction facing) {
        BlockPos frontPos = blockPos.offset(facing);
        BlockState frontState = world.getBlockState(frontPos);

        if (frontState.isAir()) return false;
        if (!frontState.isOpaque()) return false;

        VoxelShape frontShape = frontState.getOutlineShape(world, frontPos);
        if (frontShape.isEmpty()) return false;

        for (Box frontBox : frontShape.getBoundingBoxes()) {
            if (frontBox.minX <= 0.001 && frontBox.minY <= 0.001 && frontBox.minZ <= 0.001 &&
                frontBox.maxX >= 0.999 && frontBox.maxY >= 0.999 && frontBox.maxZ >= 0.999) {
                return true;
            }
        }
        return false;
    }

    private boolean overlapsUnitSquare(float[][] uvs) {
        float minU = uvs[0][0], maxU = uvs[0][0];
        float minV = uvs[0][1], maxV = uvs[0][1];
        for (int i = 1; i < 4; i++) {
            minU = Math.min(minU, uvs[i][0]);
            maxU = Math.max(maxU, uvs[i][0]);
            minV = Math.min(minV, uvs[i][1]);
            maxV = Math.max(maxV, uvs[i][1]);
        }
        return minU <= 1.0f && maxU >= 0.0f && minV <= 1.0f && maxV >= 0.0f;
    }

    /**
     * UV mapping uses the SAME basis as the editor/border:
     * h = dot(relative, rightDir), v = relative.y
     */
    private float[][] computeQuadUVs(double[][] corners, float[][] entityCorners, Direction facing) {
        float[][] uvs = new float[4][2];

        float h0 = entityCorners[0][0], v0 = entityCorners[0][1];
        float h1 = entityCorners[1][0], v1 = entityCorners[1][1];
        float h2 = entityCorners[2][0], v2 = entityCorners[2][1];
        float h3 = entityCorners[3][0], v3 = entityCorners[3][1];

        Direction rightDir = facing.rotateYClockwise();
        double rx = rightDir.getOffsetX();
        double rz = rightDir.getOffsetZ();

        for (int i = 0; i < 4; i++) {
            float localH = (float) (corners[i][0] * rx + corners[i][2] * rz);
            float localV = (float) corners[i][1];

            float[] uv = inverseBilinear(localH, localV, h0, v0, h1, v1, h2, v2, h3, v3);

            uvs[i][0] = uv[0];
            uvs[i][1] = 1.0f - uv[1];
        }

        return uvs;
    }

    private float[] inverseBilinear(float px, float py,
                                    float x0, float y0,
                                    float x1, float y1,
                                    float x2, float y2,
                                    float x3, float y3) {

        float ax = x0 - x1 - x3 + x2;
        float ay = y0 - y1 - y3 + y2;
        float bx = x1 - x0;
        float by = y1 - y0;
        float cx = x3 - x0;
        float cy = y3 - y0;
        float dx = x0 - px;
        float dy = y0 - py;

        float A = cross(ax, ay, cx, cy);
        float B = cross(ax, ay, dx, dy) + cross(bx, by, cx, cy);
        float C = cross(bx, by, dx, dy);

        float v;
        if (Math.abs(A) < 0.0001f) {
            v = (Math.abs(B) < 0.0001f) ? 0.5f : (-C / B);
        } else {
            float discriminant = B * B - 4 * A * C;
            if (discriminant < 0) {
                v = -B / (2 * A);
            } else {
                float sqrtD = (float) Math.sqrt(discriminant);
                float v1 = (-B + sqrtD) / (2 * A);
                float v2 = (-B - sqrtD) / (2 * A);
                v = (Math.abs(v1 - 0.5f) < Math.abs(v2 - 0.5f)) ? v1 : v2;
            }
        }

        float denomX = bx + ax * v;
        float denomY = by + ay * v;

        float u;
        if (Math.abs(denomX) > Math.abs(denomY)) {
            u = (-dx - cx * v) / denomX;
        } else if (Math.abs(denomY) > 0.0001f) {
            u = (-dy - cy * v) / denomY;
        } else {
            u = 0.5f;
        }

        return new float[]{u, v};
    }

    private float cross(float ax, float ay, float bx, float by) {
        return ax * by - ay * bx;
    }

    private void renderFace(Matrix4f mat, VertexConsumer vc, DecalFace face, World world) {
        int blockLight = world.getLightLevel(LightType.BLOCK, face.lightPos);
        int skyLight = world.getLightLevel(LightType.SKY, face.lightPos);
        int packedLight = LightmapTextureManager.pack(blockLight, skyLight);

        for (int i = 0; i < 4; i++) {
            vc.vertex(mat, (float) face.corners[i][0], (float) face.corners[i][1], (float) face.corners[i][2])
                .color(255, 255, 255, 255)
                .texture(face.uvs[i][0], face.uvs[i][1])
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(packedLight);
            // NOTE: no normals here; shader format is POSITION_COLOR_TEXTURE_LIGHT
        }
    }

    private static class DecalFace {
        final double[][] corners;
        final float[][] uvs;
        final BlockPos lightPos;

        DecalFace(double[][] corners, float[][] uvs, BlockPos lightPos) {
            this.corners = corners;
            this.uvs = uvs;
            this.lightPos = lightPos;
        }
    }
}