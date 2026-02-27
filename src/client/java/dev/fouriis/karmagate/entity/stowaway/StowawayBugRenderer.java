package dev.fouriis.karmagate.entity.stowaway;

import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renderer for Stowaway Bug entities.
 * Renders feeler and grabbing tentacles as smooth tubular meshes.
 * Uses the C# TriangleMesh approach: ribbon strips with perpendicular offset.
 */
public class StowawayBugRenderer extends EntityRenderer<StowawayBugEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger(StowawayBugRenderer.class);
    private static final Identifier WHITE_TEX = Identifier.of("minecraft", "textures/misc/white.png");
    
    // Number of sides for circular cross-section (more = smoother tube)
    private static final int TUBE_SIDES = 6;
    
    public StowawayBugRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.5f;
        LOGGER.info("StowawayBugRenderer initialized");
    }
    
    @Override
    public void render(StowawayBugEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        
        Vec3d camWorld = this.dispatcher.camera.getPos();
        Vec3d camLocal = camWorld.subtract(entity.getPos());
        
        // Render feeler tentacles
        renderFeelers(entity, matrices, vertexConsumers, light, tickDelta);
        
        // Render grabbing tentacles (heads)  
        renderGrabbers(entity, matrices, vertexConsumers, light, tickDelta);
        
        // Render main body
        renderBody(entity, matrices, vertexConsumers, light);
        
        matrices.pop();
    }
    
    private void renderFeelers(StowawayBugEntity entity, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta) {
        FeelerTentacle[] feelers = entity.getFeelerTentacles();
        if (feelers == null || !isValidPosition(entity)) return;
        
        // Use triangles render layer for smooth tube rendering
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntitySolid(WHITE_TEX));
        
        for (FeelerTentacle feeler : feelers) {
            renderTube(entity, feeler.getSegments(), matrices, vc, 
                0.02f,  // base radius
                80, 60, 50,  // dark brownish-gray
                light, tickDelta);
        }
    }
    
    private void renderGrabbers(StowawayBugEntity entity, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta) {
        GrabbingTentacle[] grabbers = entity.getGrabbingTentacles();
        if (grabbers == null || !isValidPosition(entity)) return;
        
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntitySolid(WHITE_TEX));
        
        for (GrabbingTentacle grabber : grabbers) {
            // Only render if extended (retractFac < 1)
            if (grabber.getRetractFactor() < 0.99f) {
                renderTube(entity, grabber.getSegments(), matrices, vc,
                    0.035f,  // thicker than feelers
                    120, 100, 80,  // lighter brown
                    light, tickDelta);
            }
        }
    }
    
    /**
     * Render tentacle as a smooth 3D tube (cylindrical mesh along segments).
     * Similar to C# TriangleMesh approach but with full 3D tube geometry.
     */
    private void renderTube(StowawayBugEntity entity, TentacleSegment[] segments, MatrixStack matrices, VertexConsumer vc, 
                            float baseRadius, int r, int g, int b, int light, float tickDelta) {
        if (segments == null || segments.length < 2) {
            return;
        }
        
        Matrix4f mat = matrices.peek().getPositionMatrix();
        Vec3d entityPos = entity.getPos();
        int alpha = 255;
        
        // Pre-calculate interpolated positions and directions
        Vec3d[] positions = new Vec3d[segments.length];
        Vec3d[] directions = new Vec3d[segments.length];
        float[] radii = new float[segments.length];
        
        for (int i = 0; i < segments.length; i++) {
            // Interpolate between last and current position for smooth animation
            Vec3d lastPos = segments[i].lastPos;
            Vec3d currPos = segments[i].pos;
            Vec3d worldPos = lastPos.add(currPos.subtract(lastPos).multiply(tickDelta));
            positions[i] = worldPos.subtract(entityPos);  // Convert to entity-local
            
            // Calculate radius with taper
            float t = (float) i / (segments.length - 1);
            radii[i] = baseRadius * calculateTaper(t);
        }
        
        // Calculate directions (tangent along the tube)
        for (int i = 0; i < segments.length; i++) {
            Vec3d dir;
            if (i == 0) {
                dir = positions[1].subtract(positions[0]);
            } else if (i == segments.length - 1) {
                dir = positions[i].subtract(positions[i - 1]);
            } else {
                // Average direction from neighbors for smooth curve
                dir = positions[i + 1].subtract(positions[i - 1]);
            }
            if (dir.lengthSquared() < 1e-10) {
                dir = new Vec3d(0, -1, 0);
            }
            directions[i] = dir.normalize();
        }
        
        // Generate rings of vertices for each segment
        Vector3f[][] rings = new Vector3f[segments.length][TUBE_SIDES];
        
        for (int i = 0; i < segments.length; i++) {
            Vec3d pos = positions[i];
            Vec3d dir = directions[i];
            float radius = radii[i];
            
            // Find perpendicular vectors for the circle
            Vec3d perp1, perp2;
            if (Math.abs(dir.y) < 0.99) {
                perp1 = dir.crossProduct(new Vec3d(0, 1, 0)).normalize();
            } else {
                perp1 = dir.crossProduct(new Vec3d(1, 0, 0)).normalize();
            }
            perp2 = dir.crossProduct(perp1).normalize();
            
            // Generate ring vertices
            for (int j = 0; j < TUBE_SIDES; j++) {
                float angle = (float) (j * 2.0 * Math.PI / TUBE_SIDES);
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);
                
                Vec3d offset = perp1.multiply(cos * radius).add(perp2.multiply(sin * radius));
                Vec3d vertPos = pos.add(offset);
                rings[i][j] = new Vector3f((float) vertPos.x, (float) vertPos.y, (float) vertPos.z);
            }
        }
        
        // Draw tube as quads between adjacent rings
        for (int i = 0; i < segments.length - 1; i++) {
            // Color gradient - slightly darker toward tip
            float t = (float) i / (segments.length - 1);
            int segR = (int) (r * (1.0f - t * 0.3f));
            int segG = (int) (g * (1.0f - t * 0.3f));
            int segB = (int) (b * (1.0f - t * 0.3f));
            
            for (int j = 0; j < TUBE_SIDES; j++) {
                int nextJ = (j + 1) % TUBE_SIDES;
                
                Vector3f v0 = rings[i][j];
                Vector3f v1 = rings[i][nextJ];
                Vector3f v2 = rings[i + 1][nextJ];
                Vector3f v3 = rings[i + 1][j];
                
                // Calculate normal for this quad face
                Vector3f normal = calculateNormal(v0, v1, v2);
                
                // Emit quad (4 vertices in correct winding order)
                emitVertex(vc, mat, v0, normal, segR, segG, segB, alpha, light);
                emitVertex(vc, mat, v1, normal, segR, segG, segB, alpha, light);
                emitVertex(vc, mat, v2, normal, segR, segG, segB, alpha, light);
                emitVertex(vc, mat, v3, normal, segR, segG, segB, alpha, light);
            }
        }
        
        // Cap the end (tip)
        if (segments.length >= 2) {
            int lastIdx = segments.length - 1;
            Vec3d tipPos = positions[lastIdx];
            Vec3d tipDir = directions[lastIdx];
            Vector3f tipCenter = new Vector3f((float) tipPos.x, (float) tipPos.y, (float) tipPos.z);
            Vector3f tipNormal = new Vector3f((float) tipDir.x, (float) tipDir.y, (float) tipDir.z);
            
            // Color for tip
            int tipR = (int) (r * 0.7f);
            int tipG = (int) (g * 0.7f);
            int tipB = (int) (b * 0.7f);
            
            // Draw tip cap as triangle fan (using quads approximation)
            for (int j = 0; j < TUBE_SIDES; j++) {
                int nextJ = (j + 1) % TUBE_SIDES;
                Vector3f v0 = rings[lastIdx][j];
                Vector3f v1 = rings[lastIdx][nextJ];
                
                // Emit degenerate quad (triangle fan point)
                emitVertex(vc, mat, tipCenter, tipNormal, tipR, tipG, tipB, alpha, light);
                emitVertex(vc, mat, v0, tipNormal, tipR, tipG, tipB, alpha, light);
                emitVertex(vc, mat, v1, tipNormal, tipR, tipG, tipB, alpha, light);
                emitVertex(vc, mat, tipCenter, tipNormal, tipR, tipG, tipB, alpha, light);
            }
        }
    }
    
    /**
     * Taper function for tentacle thickness.
     * Matches C# RadOfSegment: thick in middle, thin at ends.
     */
    private float calculateTaper(float t) {
        // Start thin, get thick, end thin
        float startTaper = smoothstep(0f, 0.15f, t);
        float endTaper = 1f - smoothstep(0.7f, 1f, t);
        return Math.max(0.1f, startTaper * endTaper);
    }
    
    private float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / Math.max(1e-6f, (e1 - e0)), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
    
    private float clamp(float x, float lo, float hi) {
        return Math.max(lo, Math.min(hi, x));
    }
    
    private Vector3f calculateNormal(Vector3f v0, Vector3f v1, Vector3f v2) {
        Vector3f edge1 = new Vector3f(v1).sub(v0);
        Vector3f edge2 = new Vector3f(v2).sub(v0);
        Vector3f normal = new Vector3f();
        edge1.cross(edge2, normal);
        if (normal.lengthSquared() > 1e-10f) {
            normal.normalize();
        } else {
            normal.set(0, 1, 0);
        }
        return normal;
    }
    
    private void emitVertex(VertexConsumer vc, Matrix4f mat, Vector3f pos, Vector3f normal, 
                            int r, int g, int b, int a, int light) {
        vc.vertex(mat, pos.x, pos.y, pos.z)
            .color(r, g, b, a)
            .texture(0, 0)
            .overlay(OverlayTexture.DEFAULT_UV)
            .light(light)
            .normal(normal.x, normal.y, normal.z);
    }
    
    /**
     * Check if entity position is valid (not at world origin or clearly unsynced)
     */
    private boolean isValidPosition(StowawayBugEntity entity) {
        Vec3d pos = entity.getPos();
        return Math.abs(pos.x) > 0.1 || Math.abs(pos.y) > 0.1 || Math.abs(pos.z) > 0.1;
    }
    
    private void renderBody(StowawayBugEntity entity, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        // Render main body - placeholder for now
        // Could render a sphere or model here
        matrices.push();
        
        // Simple body representation as a small sphere (approximated with quads)
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntitySolid(WHITE_TEX));
        Matrix4f mat = matrices.peek().getPositionMatrix();
        
        float bodyRadius = 0.15f;
        int segments = 8;
        int rings = 6;
        
        // Body color - darker than tentacles
        int r = 60, g = 45, b = 35, a = 255;
        
        for (int i = 0; i < rings; i++) {
            float phi0 = (float) (Math.PI * i / rings);
            float phi1 = (float) (Math.PI * (i + 1) / rings);
            float y0 = (float) Math.cos(phi0) * bodyRadius;
            float y1 = (float) Math.cos(phi1) * bodyRadius;
            float r0 = (float) Math.sin(phi0) * bodyRadius;
            float r1 = (float) Math.sin(phi1) * bodyRadius;
            
            for (int j = 0; j < segments; j++) {
                float theta0 = (float) (2 * Math.PI * j / segments);
                float theta1 = (float) (2 * Math.PI * (j + 1) / segments);
                
                Vector3f v0 = new Vector3f(r0 * (float) Math.cos(theta0), y0, r0 * (float) Math.sin(theta0));
                Vector3f v1 = new Vector3f(r0 * (float) Math.cos(theta1), y0, r0 * (float) Math.sin(theta1));
                Vector3f v2 = new Vector3f(r1 * (float) Math.cos(theta1), y1, r1 * (float) Math.sin(theta1));
                Vector3f v3 = new Vector3f(r1 * (float) Math.cos(theta0), y1, r1 * (float) Math.sin(theta0));
                
                Vector3f normal = calculateNormal(v0, v1, v2);
                
                emitVertex(vc, mat, v0, normal, r, g, b, a, light);
                emitVertex(vc, mat, v1, normal, r, g, b, a, light);
                emitVertex(vc, mat, v2, normal, r, g, b, a, light);
                emitVertex(vc, mat, v3, normal, r, g, b, a, light);
            }
        }
        
        matrices.pop();
    }
    
    @Override
    public Identifier getTexture(StowawayBugEntity entity) {
        return WHITE_TEX;
    }
}
