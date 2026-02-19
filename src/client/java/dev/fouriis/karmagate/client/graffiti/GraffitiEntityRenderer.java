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
        
        // Entity world position (center of graffiti)
        double entityX = entity.getX();
        double entityY = entity.getY();
        double entityZ = entity.getZ();
        
        Identifier texture = getEntityTexture(entity);
        VertexConsumer vc = vertexConsumers.getBuffer(GraffitiRenderLayer.get(texture));
        Matrix4f mat = matrices.peek().getPositionMatrix();
        
        // Get corner positions from entity
        float[][] entityCorners = entity.getCorners();
        
        // Calculate bounding box from corners
        float minH = Float.MAX_VALUE, maxH = Float.MIN_VALUE;
        float minV = Float.MAX_VALUE, maxV = Float.MIN_VALUE;
        for (float[] corner : entityCorners) {
            minH = Math.min(minH, corner[0]);
            maxH = Math.max(maxH, corner[0]);
            minV = Math.min(minV, corner[1]);
            maxV = Math.max(maxV, corner[1]);
        }
        
        // Direction vectors for the graffiti plane
        Direction rightDir = facing.rotateYClockwise();
        
        // Calculate the bounding box of blocks we need to check
        int minBlockH = MathHelper.floor(minH) - 1;
        int maxBlockH = MathHelper.ceil(maxH) + 1;
        int minBlockV = MathHelper.floor(minV) - 1;
        int maxBlockV = MathHelper.ceil(maxV) + 1;
        
        // Collect all faces to render
        List<DecalFace> faces = new ArrayList<>();
        
        // Random instance for BakedModel quad fetching
        Random random = Random.create();
        
        // Scan all blocks in the projection volume
        for (int h = minBlockH; h <= maxBlockH; h++) {
            for (int v = minBlockV; v <= maxBlockV; v++) {
                for (int d = 0; d < MAX_DEPTH; d++) {
                    // Calculate block position in world space
                    // Go IN the facing direction (into the wall) for each depth level
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
                    
                    // Get the block's baked model for actual visual geometry
                    BakedModel model = MinecraftClient.getInstance().getBlockRenderManager().getModel(state);
                    if (model == null) continue;
                    
                    // Extract faces from the BakedModel quads for all relevant directions
                    extractFacesFromModel(faces, model, state, blockPos, facing,
                                         entityX, entityY, entityZ, entityCorners, world, random);
                }
            }
        }
        
        // Render all collected faces
        for (DecalFace face : faces) {
            renderFace(mat, vc, face, world);
        }
    }
    
    /**
     * Extract renderable faces from a BakedModel's quads.
     */
    private void extractFacesFromModel(List<DecalFace> faces, BakedModel model, BlockState state,
                                       BlockPos blockPos, Direction entityFacing,
                                       double entityX, double entityY, double entityZ,
                                       float[][] entityCorners, World world, Random random) {
        
        // Check all directions that could contribute visible faces
        Direction[] directionsToCheck = {
            entityFacing.getOpposite(), // Main face toward viewer
            entityFacing.rotateYClockwise(), // Right side
            entityFacing.rotateYCounterclockwise(), // Left side
            Direction.UP, // Top
            Direction.DOWN // Bottom
        };
        
        for (Direction dir : directionsToCheck) {
            // Get quads for this direction (culled faces)
            List<BakedQuad> quads = model.getQuads(state, dir, random);
            for (BakedQuad quad : quads) {
                DecalFace face = extractFaceFromQuad(quad, blockPos, dir, entityFacing,
                                                     entityX, entityY, entityZ, entityCorners, world);
                if (face != null) {
                    faces.add(face);
                }
            }
        }
        
        // Also check unculled quads (null direction) for complex models
        List<BakedQuad> unculledQuads = model.getQuads(state, null, random);
        for (BakedQuad quad : unculledQuads) {
            Direction quadDir = quad.getFace();
            // Only process quads that face relevant directions
            if (isRelevantDirection(quadDir, entityFacing)) {
                DecalFace face = extractFaceFromQuad(quad, blockPos, quadDir, entityFacing,
                                                     entityX, entityY, entityZ, entityCorners, world);
                if (face != null) {
                    faces.add(face);
                }
            }
        }
    }
    
    private boolean isRelevantDirection(Direction quadDir, Direction entityFacing) {
        return quadDir == entityFacing.getOpposite() ||
               quadDir == entityFacing.rotateYClockwise() ||
               quadDir == entityFacing.rotateYCounterclockwise() ||
               quadDir == Direction.UP ||
               quadDir == Direction.DOWN;
    }
    
    /**
     * Extract a DecalFace from a BakedQuad's vertex data.
     */
    private DecalFace extractFaceFromQuad(BakedQuad quad, BlockPos blockPos, Direction quadDir,
                                          Direction entityFacing, double entityX, double entityY, double entityZ,
                                          float[][] entityCorners, World world) {
        
        int[] vertexData = quad.getVertexData();
        double[][] corners = new double[4][3];
        
        // Each vertex has 8 ints in the vertex data
        // Format: x, y, z (as float bits), color, u, v, light, normal
        int vertexSize = 8;
        
        double bx = blockPos.getX();
        double by = blockPos.getY();
        double bz = blockPos.getZ();
        
        for (int i = 0; i < 4; i++) {
            int offset = i * vertexSize;
            
            // Extract position from vertex data (stored as float bits)
            float x = Float.intBitsToFloat(vertexData[offset]);
            float y = Float.intBitsToFloat(vertexData[offset + 1]);
            float z = Float.intBitsToFloat(vertexData[offset + 2]);
            
            // Convert to entity-relative coordinates
            corners[i][0] = bx + x - entityX;
            corners[i][1] = by + y - entityY;
            corners[i][2] = bz + z - entityZ;
        }
        
        // Apply decal offset to push the decal slightly toward the viewer
        float nx = quadDir.getOffsetX();
        float ny = quadDir.getOffsetY();
        float nz = quadDir.getOffsetZ();
        
        for (int i = 0; i < 4; i++) {
            corners[i][0] += nx * DECAL_OFFSET;
            corners[i][1] += ny * DECAL_OFFSET;
            corners[i][2] += nz * DECAL_OFFSET;
        }
        
        // Check if the face is occluded by an adjacent block
        if (wouldQuadBeOccluded(world, blockPos, quadDir)) {
            return null;
        }
        
        // Calculate UVs based on world position relative to graffiti quadrilateral
        float[][] uvs = computeQuadUVs(corners, entityCorners, entityFacing);
        
        if (!isAnyVisible(uvs)) return null;
        
        clampUVs(uvs);
        
        // Sample light from the air block in front of the face
        BlockPos lightPos = blockPos.offset(quadDir);
        
        return new DecalFace(corners, uvs, lightPos, nx, ny, nz);
    }
    
    /**
     * Check if a quad would be occluded by an adjacent full block.
     */
    private boolean wouldQuadBeOccluded(World world, BlockPos blockPos, Direction facing) {
        BlockPos frontPos = blockPos.offset(facing);
        BlockState frontState = world.getBlockState(frontPos);
        
        if (frontState.isAir()) return false;
        if (!frontState.isOpaque()) return false;
        
        // Check if front block is a full cube using its outline shape
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
    
    /**
     * Compute UVs by mapping positions to the quadrilateral defined by entityCorners.
     * Uses inverse bilinear interpolation for proper quadrilateral texture distortion.
     * 
     * entityCorners layout:
     * [3]=top-left -------- [2]=top-right
     *      |                     |
     *      |                     |
     * [0]=bottom-left ---- [1]=bottom-right
     * 
     * UV mapping: [0] -> (0,1), [1] -> (1,1), [2] -> (1,0), [3] -> (0,0)
     */
    private float[][] computeQuadUVs(double[][] corners, float[][] entityCorners, Direction facing) {
        float[][] uvs = new float[4][2];
        
        // Extract quad corners in local 2D space (h, v)
        float h0 = entityCorners[0][0], v0 = entityCorners[0][1]; // bottom-left
        float h1 = entityCorners[1][0], v1 = entityCorners[1][1]; // bottom-right
        float h2 = entityCorners[2][0], v2 = entityCorners[2][1]; // top-right
        float h3 = entityCorners[3][0], v3 = entityCorners[3][1]; // top-left
        
        for (int i = 0; i < 4; i++) {
            // Convert 3D corner to local 2D space (h, v)
            double localX = corners[i][0];
            double localY = corners[i][1];
            double localZ = corners[i][2];
            
            // Calculate horizontal position based on facing direction
            double localH;
            if (facing == Direction.NORTH || facing == Direction.SOUTH) {
                localH = localX;
                if (facing == Direction.SOUTH) localH = -localH;
            } else {
                localH = localZ;
                if (facing == Direction.EAST) localH = -localH;
            }
            double localV = localY;
            
            // Inverse bilinear interpolation to find (u, v) in quad space
            float[] uv = inverseBilinear((float)localH, (float)localV, h0, v0, h1, v1, h2, v2, h3, v3);
            
            // Map to texture UV: u stays as-is, v is flipped (texture v=0 is top)
            uvs[i][0] = uv[0];
            uvs[i][1] = 1.0f - uv[1];
        }
        
        return uvs;
    }
    
    /**
     * Inverse bilinear interpolation: find (u, v) such that point P is inside quad Q0-Q1-Q2-Q3.
     * 
     * Bilinear formula: P = (1-u)(1-v)*Q0 + u*(1-v)*Q1 + u*v*Q2 + (1-u)*v*Q3
     * 
     * Returns [u, v] where 0 <= u, v <= 1 if point is inside quad.
     */
    private float[] inverseBilinear(float px, float py, 
                                    float x0, float y0,  // Q0 = bottom-left
                                    float x1, float y1,  // Q1 = bottom-right
                                    float x2, float y2,  // Q2 = top-right
                                    float x3, float y3)  // Q3 = top-left
    {
        // Vectors for the bilinear equation
        float ax = x0 - x1 - x3 + x2;
        float ay = y0 - y1 - y3 + y2;
        float bx = x1 - x0;
        float by = y1 - y0;
        float cx = x3 - x0;
        float cy = y3 - y0;
        float dx = x0 - px;
        float dy = y0 - py;
        
        // Solve quadratic for v: Av^2 + Bv + C = 0
        float A = cross(ax, ay, cx, cy);
        float B = cross(ax, ay, dx, dy) + cross(bx, by, cx, cy);
        float C = cross(bx, by, dx, dy);
        
        float v;
        if (Math.abs(A) < 0.0001f) {
            // Linear case
            if (Math.abs(B) < 0.0001f) {
                v = 0.5f; // Degenerate
            } else {
                v = -C / B;
            }
        } else {
            float discriminant = B * B - 4 * A * C;
            if (discriminant < 0) {
                // No real solution, use approximation
                v = -B / (2 * A);
            } else {
                float sqrtD = (float) Math.sqrt(discriminant);
                float v1 = (-B + sqrtD) / (2 * A);
                float v2 = (-B - sqrtD) / (2 * A);
                
                // Choose the v that's closer to [0,1] range
                if (Math.abs(v1 - 0.5f) < Math.abs(v2 - 0.5f)) {
                    v = v1;
                } else {
                    v = v2;
                }
            }
        }
        
        // Solve for u given v
        float denomX = bx + ax * v;
        float denomY = by + ay * v;
        float u;
        
        if (Math.abs(denomX) > Math.abs(denomY)) {
            u = (-dx - cx * v) / denomX;
        } else if (Math.abs(denomY) > 0.0001f) {
            u = (-dy - cy * v) / denomY;
        } else {
            u = 0.5f; // Degenerate
        }
        
        return new float[]{u, v};
    }
    
    private float cross(float ax, float ay, float bx, float by) {
        return ax * by - ay * bx;
    }
    
    private boolean isAnyVisible(float[][] uvs) {
        // Check if any corner is in bounds
        for (float[] uv : uvs) {
            if (uv[0] >= 0 && uv[0] <= 1 && uv[1] >= 0 && uv[1] <= 1) {
                return true;
            }
        }
        // Check if quad spans across the valid region
        float minU = Math.min(Math.min(uvs[0][0], uvs[1][0]), Math.min(uvs[2][0], uvs[3][0]));
        float maxU = Math.max(Math.max(uvs[0][0], uvs[1][0]), Math.max(uvs[2][0], uvs[3][0]));
        float minV = Math.min(Math.min(uvs[0][1], uvs[1][1]), Math.min(uvs[2][1], uvs[3][1]));
        float maxV = Math.max(Math.max(uvs[0][1], uvs[1][1]), Math.max(uvs[2][1], uvs[3][1]));
        return minU <= 1 && maxU >= 0 && minV <= 1 && maxV >= 0;
    }
    
    private void clampUVs(float[][] uvs) {
        for (float[] uv : uvs) {
            uv[0] = MathHelper.clamp(uv[0], 0, 1);
            uv[1] = MathHelper.clamp(uv[1], 0, 1);
        }
    }
    
    private void renderFace(Matrix4f mat, VertexConsumer vc, DecalFace face, World world) {
        int blockLight = world.getLightLevel(LightType.BLOCK, face.lightPos);
        int skyLight = world.getLightLevel(LightType.SKY, face.lightPos);
        int packedLight = LightmapTextureManager.pack(blockLight, skyLight);
        
        // Normal pointing towards the viewer (opposite of facing direction into wall)
        float nx = face.normalX;
        float ny = face.normalY;
        float nz = face.normalZ;
        
        for (int i = 0; i < 4; i++) {
            vc.vertex(mat, (float)face.corners[i][0], (float)face.corners[i][1], (float)face.corners[i][2])
              .color(255, 255, 255, 255)
              .texture(face.uvs[i][0], face.uvs[i][1])
              .overlay(OverlayTexture.DEFAULT_UV)
              .light(packedLight)
              .normal(nx, ny, nz);
        }
    }
    
    private static class DecalFace {
        final double[][] corners;
        final float[][] uvs;
        final BlockPos lightPos;
        final float normalX, normalY, normalZ;
        
        DecalFace(double[][] corners, float[][] uvs, BlockPos lightPos, float nx, float ny, float nz) {
            this.corners = corners;
            this.uvs = uvs;
            this.lightPos = lightPos;
            this.normalX = nx;
            this.normalY = ny;
            this.normalZ = nz;
        }
    }
}
