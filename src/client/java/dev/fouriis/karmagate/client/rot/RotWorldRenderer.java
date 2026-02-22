package dev.fouriis.karmagate.client.rot;

import dev.fouriis.karmagate.block.ModBlocks;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.ChunkPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * World renderer for Rot / Daddy Corruption visuals.
 * Renders clusters of black spherical "bulbs" with blue X-pattern eyes
 * on all adjacent solid surface blocks to RotBlock positions.
 * 
 * Inspired by Rain World's DaddyCorruption system.
 */
public final class RotWorldRenderer {
    private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");
    
    private static final int VIEW_DISTANCE_CHUNKS = 8;
    
    // Bulb visual parameters (scaled from Rain World's ~20px per tile to 1 block)
    private static final float MIN_BULB_RADIUS = 0.08f;  // 4px scaled
    private static final float MAX_BULB_RADIUS = 0.5f;   // 20px scaled
    
    // Colors
    private static final float BULB_R = 0.02f;
    private static final float BULB_G = 0.02f;
    private static final float BULB_B = 0.02f;
    
    // Eye color (blue X pattern)
    private static final float EYE_R = 0.1f;
    private static final float EYE_G = 0.3f;
    private static final float EYE_B = 0.9f;
    
    // Bulb generation parameters
    private static final int MIN_BULBS_PER_SURFACE = 2;
    private static final int MAX_BULBS_PER_SURFACE = 6;
    private static final float EYE_PROBABILITY = 0.4f;
    
    // Sphere rendering detail
    private static final int SPHERE_SEGMENTS = 12;
    private static final int SPHERE_RINGS = 8;
    
    // Tentacle parameters
    private static final int TENTACLE_SEGMENTS = 8;
    private static final float TENTACLE_BASE_WIDTH = 0.06f;
    private static final float TENTACLE_TIP_WIDTH = 0.02f;
    private static final float MIN_TENTACLE_LENGTH = 0.3f;
    private static final float MAX_TENTACLE_LENGTH = 1.5f;
    private static final float TENTACLE_PROBABILITY = 0.6f;
    private static final int MAX_TENTACLES_PER_BULB = 3;
    
    // Darkness/goo overlay parameters
    private static final float GOO_RADIUS = 2.5f;
    private static final float GOO_ALPHA = 0.4f;
    private static final int GOO_LAYERS = 3;
    
    // Cache of bulb data keyed by surface position
    private static final Map<Long, List<Bulb>> BULB_CACHE = new HashMap<>();
    
    /**
     * Represents a single corruption bulb with position, size, and eye data.
     */
    private static class Bulb {
        final Vec3d pos;
        final float radius;
        final boolean hasEye;
        final float rotation;     // Eye rotation in degrees
        final float eyeRadius;    // Size of eye relative to bulb
        final List<Tentacle> tentacles;
        
        // Animation state
        float offsetX, offsetY, offsetZ;
        float velX, velY, velZ;
        
        Bulb(Vec3d pos, float radius, boolean hasEye, float rotation, float eyeRadius, List<Tentacle> tentacles) {
            this.pos = pos;
            this.radius = radius;
            this.hasEye = hasEye;
            this.rotation = rotation;
            this.eyeRadius = eyeRadius;
            this.tentacles = tentacles;
        }
    }
    
    /**
     * Represents a static tentacle extending from a bulb.
     */
    private static class Tentacle {
        final Vec3d[] segments;       // World positions of each segment
        final float[] widths;         // Width at each segment
        final List<TentacleBump> bumps;  // Bumps along the tentacle
        
        Tentacle(Vec3d[] segments, float[] widths, List<TentacleBump> bumps) {
            this.segments = segments;
            this.widths = widths;
            this.bumps = bumps;
        }
    }
    
    /**
     * A bump (small sphere) on a tentacle, optionally with an eye.
     */
    private static class TentacleBump {
        final float t;         // Position along tentacle (0-1)
        final float offset;    // Lateral offset (-1 to 1)
        final float size;      // Relative size
        final boolean hasEye;
        final float eyeSize;
        final float rotation;
        
        TentacleBump(float t, float offset, float size, boolean hasEye, float eyeSize, float rotation) {
            this.t = t;
            this.offset = offset;
            this.size = size;
            this.hasEye = hasEye;
            this.eyeSize = eyeSize;
            this.rotation = rotation;
        }
    }
    
    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;
        
        ClientWorld world = client.world;
        Vec3d cam = context.camera().getPos();
        float tickDelta = context.tickCounter().getTickDelta(true);
        
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider vertexConsumers = context.consumers();
        if (vertexConsumers == null) return;
        
        // Get positions to render
        int playerChunkX = (int) Math.floor(cam.x) >> 4;
        int playerChunkZ = (int) Math.floor(cam.z) >> 4;
        
        List<BlockPos> rotPositions = new ArrayList<>();
        
        for (int dx = -VIEW_DISTANCE_CHUNKS; dx <= VIEW_DISTANCE_CHUNKS; dx++) {
            for (int dz = -VIEW_DISTANCE_CHUNKS; dz <= VIEW_DISTANCE_CHUNKS; dz++) {
                int cx = playerChunkX + dx;
                int cz = playerChunkZ + dz;
                
                List<Long> positions = RotRenderCache.getPositionsForChunk(cx, cz);
                for (long packed : positions) {
                    BlockPos pos = BlockPos.fromLong(packed);
                    rotPositions.add(pos);
                }
            }
        }
        
        if (rotPositions.isEmpty()) return;
        
        // Collect all surfaces that should have corruption
        List<SurfaceInfo> surfaces = new ArrayList<>();
        for (BlockPos rotPos : rotPositions) {
            collectAdjacentSurfaces(world, rotPos, surfaces);
        }
        
        // Render corruption on each surface
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));
        
        // Render bulbs and tentacles
        for (SurfaceInfo surface : surfaces) {
            int light = WorldRenderer.getLightmapCoordinates(world, surface.solidBlock.offset(surface.exposedFace));
            List<Bulb> bulbs = getOrCreateBulbs(surface);
            for (Bulb bulb : bulbs) {
                // Render tentacles first (behind bulb)
                for (Tentacle tentacle : bulb.tentacles) {
                    renderTentacle(matrices, vc, tentacle, cam, light);
                }
                // Render the bulb
                renderBulb(matrices, vc, bulb, tickDelta, cam, light);
            }
        }
        
        matrices.pop();
    }
    
    /**
     * Info about a surface that should have corruption rendered on it.
     */
    private static class SurfaceInfo {
        final BlockPos solidBlock;
        final Direction exposedFace;
        final long cacheKey;
        
        SurfaceInfo(BlockPos solidBlock, Direction exposedFace) {
            this.solidBlock = solidBlock;
            this.exposedFace = exposedFace;
            // Unique key combining position and face
            this.cacheKey = BlockPos.asLong(solidBlock.getX(), solidBlock.getY(), solidBlock.getZ()) * 7 + exposedFace.ordinal();
        }
    }
    
    /**
     * Find all solid surface blocks adjacent to a rot block.
     */
    private static void collectAdjacentSurfaces(ClientWorld world, BlockPos rotPos, List<SurfaceInfo> surfaces) {
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = rotPos.offset(dir);
            if (world.getBlockState(adjacent).isSolidBlock(world, adjacent)) {
                // This solid block has a face exposed toward the rot block
                Direction exposedFace = dir.getOpposite();
                SurfaceInfo info = new SurfaceInfo(adjacent, exposedFace);
                
                // Avoid duplicates
                boolean exists = false;
                for (SurfaceInfo s : surfaces) {
                    if (s.cacheKey == info.cacheKey) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    surfaces.add(info);
                }
            }
        }
    }
    
    /**
     * Get or create a cached set of bulbs for a surface.
     */
    private static List<Bulb> getOrCreateBulbs(SurfaceInfo surface) {
        List<Bulb> bulbs = BULB_CACHE.get(surface.cacheKey);
        if (bulbs != null) return bulbs;
        
        bulbs = new ArrayList<>();
        
        // Deterministic random based on position
        long seed = surface.solidBlock.asLong() * 31 + surface.exposedFace.ordinal();
        Random rand = new Random(seed);
        
        int numBulbs = MIN_BULBS_PER_SURFACE + rand.nextInt(MAX_BULBS_PER_SURFACE - MIN_BULBS_PER_SURFACE + 1);
        
        // Surface center and tangent vectors
        Vec3d center = Vec3d.ofCenter(surface.solidBlock);
        Vec3d normal = Vec3d.of(surface.exposedFace.getVector());
        
        // Offset center to the surface face
        center = center.add(normal.multiply(0.5));
        
        // Get tangent vectors for the face
        Vec3d tangent1, tangent2;
        if (surface.exposedFace.getAxis() == Direction.Axis.Y) {
            tangent1 = new Vec3d(1, 0, 0);
            tangent2 = new Vec3d(0, 0, 1);
        } else if (surface.exposedFace.getAxis() == Direction.Axis.X) {
            tangent1 = new Vec3d(0, 1, 0);
            tangent2 = new Vec3d(0, 0, 1);
        } else {
            tangent1 = new Vec3d(1, 0, 0);
            tangent2 = new Vec3d(0, 1, 0);
        }
        
        for (int i = 0; i < numBulbs; i++) {
            // Random position on face with some spread
            float u = (rand.nextFloat() - 0.5f) * 0.8f;
            float v = (rand.nextFloat() - 0.5f) * 0.8f;
            
            Vec3d bulbPos = center
                .add(tangent1.multiply(u))
                .add(tangent2.multiply(v))
                .add(normal.multiply(rand.nextFloat() * 0.1f)); // Slight protrusion
            
            float radius = MIN_BULB_RADIUS + rand.nextFloat() * (MAX_BULB_RADIUS - MIN_BULB_RADIUS);
            
            // Larger bulbs more likely to have eyes
            boolean hasEye = rand.nextFloat() < EYE_PROBABILITY * (radius / MAX_BULB_RADIUS);
            float rotation = rand.nextFloat() * 360f;
            float eyeRadius = 0.3f + rand.nextFloat() * 0.5f;
            
            // Generate tentacles for this bulb
            List<Tentacle> tentacles = new ArrayList<>();
            if (rand.nextFloat() < TENTACLE_PROBABILITY) {
                int numTentacles = 1 + rand.nextInt(MAX_TENTACLES_PER_BULB);
                for (int t = 0; t < numTentacles; t++) {
                    tentacles.add(generateTentacle(rand, bulbPos, normal, tangent1, tangent2, radius));
                }
            }
            
            bulbs.add(new Bulb(bulbPos, radius, hasEye, rotation, eyeRadius, tentacles));
        }
        
        BULB_CACHE.put(surface.cacheKey, bulbs);
        return bulbs;
    }
    
    /**
     * Generate a static tentacle extending from a bulb.
     */
    private static Tentacle generateTentacle(Random rand, Vec3d bulbPos, Vec3d normal, 
                                              Vec3d tangent1, Vec3d tangent2, float bulbRadius) {
        float length = MIN_TENTACLE_LENGTH + rand.nextFloat() * (MAX_TENTACLE_LENGTH - MIN_TENTACLE_LENGTH);
        
        // Random direction biased outward from surface
        float dirU = (rand.nextFloat() - 0.5f) * 2f;
        float dirV = (rand.nextFloat() - 0.5f) * 2f;
        float dirN = 0.5f + rand.nextFloat() * 0.5f;  // Always extends outward somewhat
        
        Vec3d baseDir = normal.multiply(dirN)
            .add(tangent1.multiply(dirU))
            .add(tangent2.multiply(dirV))
            .normalize();
        
        // Build segments with slight curvature
        Vec3d[] segments = new Vec3d[TENTACLE_SEGMENTS];
        float[] widths = new float[TENTACLE_SEGMENTS];
        
        Vec3d currentDir = baseDir;
        Vec3d currentPos = bulbPos.add(normal.multiply(bulbRadius * 0.8)); // Start at bulb surface
        
        for (int s = 0; s < TENTACLE_SEGMENTS; s++) {
            float t = (float) s / (TENTACLE_SEGMENTS - 1);
            segments[s] = currentPos;
            widths[s] = MathHelper.lerp(t, TENTACLE_BASE_WIDTH, TENTACLE_TIP_WIDTH);
            
            // Add some curve/droop
            float segLen = length / TENTACLE_SEGMENTS;
            Vec3d gravity = new Vec3d(0, -0.15 * t, 0);  // Slight droop
            Vec3d perturbation = new Vec3d(
                (rand.nextFloat() - 0.5f) * 0.3f,
                (rand.nextFloat() - 0.5f) * 0.3f,
                (rand.nextFloat() - 0.5f) * 0.3f
            );
            currentDir = currentDir.add(gravity).add(perturbation).normalize();
            currentPos = currentPos.add(currentDir.multiply(segLen));
        }
        
        // Generate bumps along tentacle
        List<TentacleBump> bumps = new ArrayList<>();
        int numBumps = 2 + rand.nextInt(4);
        for (int b = 0; b < numBumps; b++) {
            float bumpT = 0.1f + rand.nextFloat() * 0.8f;  // Avoid very ends
            float offset = (rand.nextFloat() - 0.5f) * 2f;
            float size = 0.3f + rand.nextFloat() * 0.7f;
            boolean bumpHasEye = rand.nextFloat() < 0.2f * size;
            float bumpEyeSize = 0.3f + rand.nextFloat() * 0.4f;
            float bumpRot = rand.nextFloat() * 360f;
            bumps.add(new TentacleBump(bumpT, offset, size, bumpHasEye, bumpEyeSize, bumpRot));
        }
        
        return new Tentacle(segments, widths, bumps);
    }
    
    /**
     * Render a single corruption bulb as a sphere with optional X-pattern eye.
     */
    private static void renderBulb(MatrixStack matrices, VertexConsumer consumer, Bulb bulb, float tickDelta, Vec3d cam, int light) {
        matrices.push();
        
        // Subtle idle animation
        double time = System.currentTimeMillis() / 1000.0;
        float wobble = (float) Math.sin(time * 0.5 + bulb.pos.x * 3.7 + bulb.pos.z * 2.3) * 0.02f;
        
        matrices.translate(bulb.pos.x + wobble, bulb.pos.y + wobble * 0.5f, bulb.pos.z);
        
        // Draw the black sphere body
        renderSphere(matrices, consumer, bulb.radius, BULB_R, BULB_G, BULB_B, 1.0f, light);
        
        // Draw eye X pattern if present
        if (bulb.hasEye) {
            renderEye(matrices, consumer, bulb, cam, light);
        }
        
        matrices.pop();
    }
    
    /**
     * Render a simple sphere using quads (4 vertices per face for entity rendering).
     */
    private static void renderSphere(MatrixStack matrices, VertexConsumer consumer, 
                                      float radius, float r, float g, float b, float a, int light) {
        Matrix4f posMatrix = matrices.peek().getPositionMatrix();
        
        for (int ring = 0; ring < SPHERE_RINGS; ring++) {
            float theta1 = (float) (ring * Math.PI / SPHERE_RINGS);
            float theta2 = (float) ((ring + 1) * Math.PI / SPHERE_RINGS);
            
            float y1 = (float) Math.cos(theta1) * radius;
            float y2 = (float) Math.cos(theta2) * radius;
            float r1 = (float) Math.sin(theta1) * radius;
            float r2 = (float) Math.sin(theta2) * radius;
            
            for (int seg = 0; seg < SPHERE_SEGMENTS; seg++) {
                float phi1 = (float) (seg * 2 * Math.PI / SPHERE_SEGMENTS);
                float phi2 = (float) ((seg + 1) * 2 * Math.PI / SPHERE_SEGMENTS);
                
                float x11 = (float) Math.cos(phi1) * r1;
                float z11 = (float) Math.sin(phi1) * r1;
                float x12 = (float) Math.cos(phi2) * r1;
                float z12 = (float) Math.sin(phi2) * r1;
                
                float x21 = (float) Math.cos(phi1) * r2;
                float z21 = (float) Math.sin(phi1) * r2;
                float x22 = (float) Math.cos(phi2) * r2;
                float z22 = (float) Math.sin(phi2) * r2;
                
                // Compute normals
                Vector3f n11 = new Vector3f(x11, y1, z11).normalize();
                Vector3f n12 = new Vector3f(x12, y1, z12).normalize();
                Vector3f n21 = new Vector3f(x21, y2, z21).normalize();
                Vector3f n22 = new Vector3f(x22, y2, z22).normalize();
                
                // Emit as a quad (4 vertices in proper winding order for entity rendering)
                vertex(consumer, posMatrix, x11, y1, z11, r, g, b, a, 0f, 0f, light, n11.x, n11.y, n11.z);
                vertex(consumer, posMatrix, x21, y2, z21, r, g, b, a, 0f, 1f, light, n21.x, n21.y, n21.z);
                vertex(consumer, posMatrix, x22, y2, z22, r, g, b, a, 1f, 1f, light, n22.x, n22.y, n22.z);
                vertex(consumer, posMatrix, x12, y1, z12, r, g, b, a, 1f, 0f, light, n12.x, n12.y, n12.z);
            }
        }
    }
    
    // Number of segments for curved eye slits
    private static final int EYE_SEGMENTS = 8;
    
    /**
     * Render the X-pattern eye curved on the sphere surface.
     * Creates two perpendicular slits that form an X, following the sphere curvature.
     */
    private static void renderEye(MatrixStack matrices, VertexConsumer consumer, Bulb bulb, Vec3d cam, int light) {
        // Direction from bulb center toward camera
        Vec3d toCamera = cam.subtract(bulb.pos).normalize();
        
        // Angular size of the eye on the sphere (in radians)
        float eyeAngularSize = bulb.eyeRadius * 0.8f; // radians
        float slitAngularThickness = eyeAngularSize * 0.12f;
        
        matrices.push();
        
        Matrix4f posMatrix = matrices.peek().getPositionMatrix();
        
        // Build rotation basis: eye center faces camera
        // Up vector for the eye coordinate system
        Vector3f forward = new Vector3f((float)toCamera.x, (float)toCamera.y, (float)toCamera.z);
        Vector3f worldUp = new Vector3f(0, 1, 0);
        
        // If forward is nearly parallel to worldUp, use a different reference
        if (Math.abs(forward.dot(worldUp)) > 0.99f) {
            worldUp = new Vector3f(1, 0, 0);
        }
        
        // Create orthonormal basis for the eye plane
        Vector3f right = new Vector3f(forward).cross(worldUp).normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();
        
        // Apply bulb's rotation around the forward axis
        float rotRad = (float) Math.toRadians(bulb.rotation);
        float cosRot = (float) Math.cos(rotRad);
        float sinRot = (float) Math.sin(rotRad);
        Vector3f rotatedRight = new Vector3f(
            right.x * cosRot + up.x * sinRot,
            right.y * cosRot + up.y * sinRot,
            right.z * cosRot + up.z * sinRot
        );
        Vector3f rotatedUp = new Vector3f(
            -right.x * sinRot + up.x * cosRot,
            -right.y * sinRot + up.y * cosRot,
            -right.z * sinRot + up.z * cosRot
        );
        
        // Draw two curved slits forming an X (at 45 and -45 degrees)
        renderCurvedSlit(posMatrix, consumer, bulb.radius, forward, rotatedRight, rotatedUp,
                         eyeAngularSize, slitAngularThickness, 45f, EYE_R, EYE_G, EYE_B, light);
        renderCurvedSlit(posMatrix, consumer, bulb.radius, forward, rotatedRight, rotatedUp,
                         eyeAngularSize, slitAngularThickness, -45f, EYE_R, EYE_G, EYE_B, light);
        
        matrices.pop();
    }
    
    /**
     * Render a single curved slit of the X pattern on the sphere surface.
     * The slit follows the sphere curvature using multiple quad segments.
     */
    private static void renderCurvedSlit(Matrix4f posMatrix, VertexConsumer consumer,
                                          float sphereRadius, Vector3f center, Vector3f right, Vector3f up,
                                          float angularLength, float angularThickness, float rotationDeg,
                                          float r, float g, float b, int light) {
        // Rotate the slit direction
        float rotRad = (float) Math.toRadians(rotationDeg);
        float cosRot = (float) Math.cos(rotRad);
        float sinRot = (float) Math.sin(rotRad);
        
        // Slit direction (along the slit) and perpendicular (slit width)
        Vector3f slitDir = new Vector3f(
            right.x * cosRot + up.x * sinRot,
            right.y * cosRot + up.y * sinRot,
            right.z * cosRot + up.z * sinRot
        );
        Vector3f slitPerp = new Vector3f(
            -right.x * sinRot + up.x * cosRot,
            -right.y * sinRot + up.y * cosRot,
            -right.z * sinRot + up.z * cosRot
        );
        
        float halfLen = angularLength * 0.5f;
        float halfThick = angularThickness * 0.5f;
        
        // Render as multiple segments along the slit length
        for (int i = 0; i < EYE_SEGMENTS; i++) {
            float t0 = (float) i / EYE_SEGMENTS;
            float t1 = (float) (i + 1) / EYE_SEGMENTS;
            
            // Angular positions along the slit (-halfLen to +halfLen)
            float angle0 = -halfLen + t0 * angularLength;
            float angle1 = -halfLen + t1 * angularLength;
            
            // Get 4 corners of this segment on the sphere surface
            Vector3f p00 = projectToSphere(center, slitDir, slitPerp, angle0, -halfThick, sphereRadius);
            Vector3f p01 = projectToSphere(center, slitDir, slitPerp, angle0, halfThick, sphereRadius);
            Vector3f p10 = projectToSphere(center, slitDir, slitPerp, angle1, -halfThick, sphereRadius);
            Vector3f p11 = projectToSphere(center, slitDir, slitPerp, angle1, halfThick, sphereRadius);
            
            // Normals point outward from sphere center
            Vector3f n00 = new Vector3f(p00).normalize();
            Vector3f n01 = new Vector3f(p01).normalize();
            Vector3f n10 = new Vector3f(p10).normalize();
            Vector3f n11 = new Vector3f(p11).normalize();
            
            // Emit quad (proper winding for outward-facing)
            float u0 = t0;
            float u1 = t1;
            vertex(consumer, posMatrix, p00.x, p00.y, p00.z, r, g, b, 1f, u0, 0f, light, n00.x, n00.y, n00.z);
            vertex(consumer, posMatrix, p10.x, p10.y, p10.z, r, g, b, 1f, u1, 0f, light, n10.x, n10.y, n10.z);
            vertex(consumer, posMatrix, p11.x, p11.y, p11.z, r, g, b, 1f, u1, 1f, light, n11.x, n11.y, n11.z);
            vertex(consumer, posMatrix, p01.x, p01.y, p01.z, r, g, b, 1f, u0, 1f, light, n01.x, n01.y, n01.z);
        }
    }
    
    /**
     * Project a point onto the sphere surface given angular offsets from center.
     * @param center The direction from sphere origin to eye center (unit vector)
     * @param alongDir Direction along the slit (unit vector, tangent to sphere)
     * @param perpDir Direction perpendicular to slit (unit vector, tangent to sphere)
     * @param alongAngle Angular offset along the slit direction (radians)
     * @param perpAngle Angular offset perpendicular to slit (radians)
     * @param radius Sphere radius
     * @return Point on sphere surface
     */
    private static Vector3f projectToSphere(Vector3f center, Vector3f alongDir, Vector3f perpDir,
                                             float alongAngle, float perpAngle, float radius) {
        // Start from center direction, rotate by angular offsets
        // This creates a great-circle arc on the sphere
        
        // First rotate around perpDir axis by alongAngle (moves along slit)
        Vector3f dir = rotateAroundAxis(center, perpDir, alongAngle);
        // Then rotate around the new alongDir by perpAngle (moves perpendicular)
        dir = rotateAroundAxis(dir, alongDir, perpAngle);
        
        // Scale to sphere surface (slightly outside to avoid z-fighting)
        return new Vector3f(dir.x * radius * 1.002f, dir.y * radius * 1.002f, dir.z * radius * 1.002f);
    }
    
    /**
     * Rotate a vector around an axis using Rodrigues' rotation formula.
     */
    private static Vector3f rotateAroundAxis(Vector3f v, Vector3f axis, float angle) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        
        // v_rot = v*cos(θ) + (axis × v)*sin(θ) + axis*(axis·v)*(1-cos(θ))
        float dot = v.dot(axis);
        Vector3f cross = new Vector3f(axis).cross(v);
        
        return new Vector3f(
            v.x * cos + cross.x * sin + axis.x * dot * (1 - cos),
            v.y * cos + cross.y * sin + axis.y * dot * (1 - cos),
            v.z * cos + cross.z * sin + axis.z * dot * (1 - cos)
        );
    }
    
    /**
     * Helper to emit a vertex with all required attributes for entity rendering.
     */
    private static void vertex(VertexConsumer vc, Matrix4f posMat,
                               float x, float y, float z,
                               float r, float g, float b, float a,
                               float u, float v,
                               int light,
                               float nx, float ny, float nz) {
        vc.vertex(posMat, x, y, z)
          .color(r, g, b, a)
          .texture(u, v)
          .overlay(OverlayTexture.DEFAULT_UV)
          .light(light)
          .normal(nx, ny, nz);
    }
    
    /**
     * Render darkness/goo overlay as concentric semi-transparent spheres.
     * NOTE: Disabled - entity cutout render layer doesn't support alpha blending.
     * Would need a separate translucent render pass to work properly.
     */
    private static void renderDarknessGoo(MatrixStack matrices, VertexConsumer consumer, Vec3d center, int light) {
        // Disabled for now - the cutout render layer renders these as solid black squares
        // TODO: Implement with a translucent render layer in a separate pass
    }
    
    /**
     * Render a camera-facing goo quad (billboard).
     */
    private static void renderGooBillboard(MatrixStack matrices, VertexConsumer consumer, 
                                            float radius, float r, float g, float b, float a, int light) {
        Matrix4f posMatrix = matrices.peek().getPositionMatrix();
        
        // Simple billboard quad facing all directions (rendered as 3 intersecting quads)
        // XY plane
        vertex(consumer, posMatrix, -radius, -radius, 0, r, g, b, a, 0, 0, light, 0, 0, 1);
        vertex(consumer, posMatrix, -radius, radius, 0, r, g, b, a, 0, 1, light, 0, 0, 1);
        vertex(consumer, posMatrix, radius, radius, 0, r, g, b, a, 1, 1, light, 0, 0, 1);
        vertex(consumer, posMatrix, radius, -radius, 0, r, g, b, a, 1, 0, light, 0, 0, 1);
        
        // XZ plane
        vertex(consumer, posMatrix, -radius, 0, -radius, r, g, b, a, 0, 0, light, 0, 1, 0);
        vertex(consumer, posMatrix, -radius, 0, radius, r, g, b, a, 0, 1, light, 0, 1, 0);
        vertex(consumer, posMatrix, radius, 0, radius, r, g, b, a, 1, 1, light, 0, 1, 0);
        vertex(consumer, posMatrix, radius, 0, -radius, r, g, b, a, 1, 0, light, 0, 1, 0);
        
        // YZ plane
        vertex(consumer, posMatrix, 0, -radius, -radius, r, g, b, a, 0, 0, light, 1, 0, 0);
        vertex(consumer, posMatrix, 0, -radius, radius, r, g, b, a, 0, 1, light, 1, 0, 0);
        vertex(consumer, posMatrix, 0, radius, radius, r, g, b, a, 1, 1, light, 1, 0, 0);
        vertex(consumer, posMatrix, 0, radius, -radius, r, g, b, a, 1, 0, light, 1, 0, 0);
    }
    
    /**
     * Render a tentacle as a tube with bumps.
     */
    private static void renderTentacle(MatrixStack matrices, VertexConsumer consumer, 
                                        Tentacle tentacle, Vec3d cam, int light) {
        matrices.push();
        
        Matrix4f posMatrix = matrices.peek().getPositionMatrix();
        
        // Render tube segments
        for (int i = 0; i < tentacle.segments.length - 1; i++) {
            Vec3d p0 = tentacle.segments[i];
            Vec3d p1 = tentacle.segments[i + 1];
            float w0 = tentacle.widths[i];
            float w1 = tentacle.widths[i + 1];
            
            renderTubeSegment(posMatrix, consumer, p0, p1, w0, w1, light);
        }
        
        // Render bumps along the tentacle
        for (TentacleBump bump : tentacle.bumps) {
            Vec3d bumpPos = getTentaclePosition(tentacle, bump.t, bump.offset);
            float bumpRadius = TENTACLE_BASE_WIDTH * 1.5f * bump.size;
            
            matrices.push();
            matrices.translate(bumpPos.x, bumpPos.y, bumpPos.z);
            renderSphere(matrices, consumer, bumpRadius, BULB_R, BULB_G, BULB_B, 1.0f, light);
            
            // Render eye on bump if present
            if (bump.hasEye) {
                // Create a temporary bulb for eye rendering
                Bulb fakeBulb = new Bulb(bumpPos, bumpRadius, true, bump.rotation, bump.eyeSize, List.of());
                matrices.pop();
                matrices.push();
                matrices.translate(bumpPos.x, bumpPos.y, bumpPos.z);
                renderEyeOnSphere(matrices, consumer, fakeBulb, cam, light);
            }
            
            matrices.pop();
        }
        
        matrices.pop();
    }
    
    /**
     * Get the world position at a point along the tentacle.
     */
    private static Vec3d getTentaclePosition(Tentacle tentacle, float t, float lateralOffset) {
        float scaledT = t * (tentacle.segments.length - 1);
        int idx = (int) scaledT;
        float frac = scaledT - idx;
        
        if (idx >= tentacle.segments.length - 1) {
            idx = tentacle.segments.length - 2;
            frac = 1f;
        }
        
        Vec3d p0 = tentacle.segments[idx];
        Vec3d p1 = tentacle.segments[idx + 1];
        
        // Interpolate position
        Vec3d pos = p0.add(p1.subtract(p0).multiply(frac));
        
        // Add lateral offset perpendicular to tentacle direction
        Vec3d dir = p1.subtract(p0).normalize();
        Vec3d perp = dir.crossProduct(new Vec3d(0, 1, 0));
        if (perp.lengthSquared() < 0.001) {
            perp = dir.crossProduct(new Vec3d(1, 0, 0));
        }
        perp = perp.normalize();
        
        float width = MathHelper.lerp(frac, tentacle.widths[idx], tentacle.widths[idx + 1]);
        pos = pos.add(perp.multiply(lateralOffset * width * 2));
        
        return pos;
    }
    
    /**
     * Render a single tube segment between two points.
     */
    private static void renderTubeSegment(Matrix4f posMatrix, VertexConsumer consumer,
                                           Vec3d p0, Vec3d p1, float w0, float w1, int light) {
        Vec3d dir = p1.subtract(p0).normalize();
        
        // Get perpendicular vectors for tube surface
        Vec3d up = new Vec3d(0, 1, 0);
        if (Math.abs(dir.dotProduct(up)) > 0.99) {
            up = new Vec3d(1, 0, 0);
        }
        Vec3d perp1 = dir.crossProduct(up).normalize();
        Vec3d perp2 = dir.crossProduct(perp1).normalize();
        
        // Render tube as quads around circumference
        int tubeSegments = 6;
        for (int i = 0; i < tubeSegments; i++) {
            float angle0 = (float) (i * 2 * Math.PI / tubeSegments);
            float angle1 = (float) ((i + 1) * 2 * Math.PI / tubeSegments);
            
            float cos0 = (float) Math.cos(angle0);
            float sin0 = (float) Math.sin(angle0);
            float cos1 = (float) Math.cos(angle1);
            float sin1 = (float) Math.sin(angle1);
            
            // Four corners of this quad
            Vec3d v00 = p0.add(perp1.multiply(cos0 * w0)).add(perp2.multiply(sin0 * w0));
            Vec3d v01 = p0.add(perp1.multiply(cos1 * w0)).add(perp2.multiply(sin1 * w0));
            Vec3d v10 = p1.add(perp1.multiply(cos0 * w1)).add(perp2.multiply(sin0 * w1));
            Vec3d v11 = p1.add(perp1.multiply(cos1 * w1)).add(perp2.multiply(sin1 * w1));
            
            // Normals pointing outward
            Vec3d n0 = perp1.multiply(cos0).add(perp2.multiply(sin0));
            Vec3d n1 = perp1.multiply(cos1).add(perp2.multiply(sin1));
            
            // Emit quad
            vertex(consumer, posMatrix, (float)v00.x, (float)v00.y, (float)v00.z, BULB_R, BULB_G, BULB_B, 1f, 0, 0, light, (float)n0.x, (float)n0.y, (float)n0.z);
            vertex(consumer, posMatrix, (float)v10.x, (float)v10.y, (float)v10.z, BULB_R, BULB_G, BULB_B, 1f, 0, 1, light, (float)n0.x, (float)n0.y, (float)n0.z);
            vertex(consumer, posMatrix, (float)v11.x, (float)v11.y, (float)v11.z, BULB_R, BULB_G, BULB_B, 1f, 1, 1, light, (float)n1.x, (float)n1.y, (float)n1.z);
            vertex(consumer, posMatrix, (float)v01.x, (float)v01.y, (float)v01.z, BULB_R, BULB_G, BULB_B, 1f, 1, 0, light, (float)n1.x, (float)n1.y, (float)n1.z);
        }
    }
    
    /**
     * Render just the eye part (for tentacle bumps).
     */
    private static void renderEyeOnSphere(MatrixStack matrices, VertexConsumer consumer, 
                                           Bulb bulb, Vec3d cam, int light) {
        renderEye(matrices, consumer, bulb, cam, light);
    }
    
    /**
     * Clear the bulb cache (called on world unload/change).
     */
    public static void clearCache() {
        BULB_CACHE.clear();
    }
    
    private RotWorldRenderer() {}
}
