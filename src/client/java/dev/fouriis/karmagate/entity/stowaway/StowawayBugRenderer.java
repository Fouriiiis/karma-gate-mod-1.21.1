package dev.fouriis.karmagate.entity.stowaway;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer for Stowaway Bug entities.
 * Renders the GeckoLib geo model for the body, plus feeler and grabbing
 * tentacles as smooth tubular meshes on top.
 * Uses the C# TriangleMesh approach: ribbon strips with perpendicular offset.
 */
public class StowawayBugRenderer extends GeoEntityRenderer<StowawayBugEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger(StowawayBugRenderer.class);
    private static final Identifier WHITE_TEX = Identifier.of("minecraft", "textures/misc/white.png");

    // Number of sides for circular cross-section (more = smoother tube)
    private static final int TUBE_SIDES = 6;

    // TrapHook sprite (lazily resolved from the atlas)
    private static FAtlasElement trapHookSprite = null;

    /** Half-size of the TrapHook sprite in blocks. */
    private static final float TRAP_HOOK_HALF_SIZE = 0.15f;

    public StowawayBugRenderer(EntityRendererFactory.Context context) {
        super(context, new StowawayBugModel());
        this.shadowRadius = 0.5f;
        LOGGER.info("StowawayBugRenderer initialized");
    }

    @Override
    public void render(StowawayBugEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        // Render the GeckoLib geo model (body)
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);

        matrices.push();

        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, entity.prevBodyYaw, entity.bodyYaw);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-bodyYaw));

        // Render feeler tentacles
        renderFeelers(entity, matrices, vertexConsumers, light, tickDelta);

        // Render grabbing tentacles (heads)
        renderGrabbers(entity, matrices, vertexConsumers, light, tickDelta);

        matrices.pop();
    }

    @Override
    public Identifier getTextureLocation(StowawayBugEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/stowaway.png");
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
                light, tickDelta, false);
        }
    }

    private void renderGrabbers(StowawayBugEntity entity, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float tickDelta) {
        GrabbingTentacle[] grabbers = entity.getGrabbingTentacles();
        if (grabbers == null || !isValidPosition(entity)) return;

        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntitySolid(WHITE_TEX));

        // Grabber tip color (same as what renderTube uses for the tip cap)
        final int baseR = 120, baseG = 100, baseB = 80;
        final int tipR = (int)(baseR * 0.7f), tipG = (int)(baseG * 0.7f), tipB = (int)(baseB * 0.7f);

        for (GrabbingTentacle grabber : grabbers) {
            // Only render if extended (retractFac < 1)
            if (grabber.getRetractFactor() < 0.99f) {
                renderTube(entity, grabber.getSegments(), matrices, vc,
                    0.035f,  // thicker than feelers
                    baseR, baseG, baseB,  // lighter brown
                    light, tickDelta, true);  // true = grabber tip widening

                // Render TrapHook sprite at the tip of this grabber, tinted in tip color
                renderGrabberTip(entity, grabber.getSegments(), matrices, vertexConsumers,
                        light, tickDelta, tipR, tipG, tipB);
            }
        }
    }

    /**
     * Renders a TrapHook sprite at the tip of a grabber arm.
     * The sprite is billboarded toward the camera (always facing the player) but is
     * also rotated in the camera plane to align with the direction of the end segment.
     */
    private void renderGrabberTip(StowawayBugEntity entity, TentacleSegment[] segments,
                                  MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                  int light, float tickDelta, int colorR, int colorG, int colorB) {
        if (segments == null || segments.length < 2) return;

        // Lazily resolve the TrapHook sprite
        if (trapHookSprite == null) {
            trapHookSprite = LibrainworldmcClient.getAtlasManager().getElementWithName("TrapHook");
            if (trapHookSprite == null) return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.gameRenderer == null) return;

        // Camera rotation gives us the billboard axes (right and up in screen space)
        Quaternionf camRot = new Quaternionf(client.gameRenderer.getCamera().getRotation());
        Vector3f billboardRight = camRot.transform(new Vector3f(1f, 0f, 0f));
        Vector3f billboardUp    = camRot.transform(new Vector3f(0f, 1f, 0f));
        // The normal facing toward the camera
        Vector3f billboardNormal = camRot.transform(new Vector3f(0f, 0f, 1f));

        Vec3d entityPos = entity.getPos();

        // Interpolate tip position (last segment)
        int lastIdx = segments.length - 1;
        TentacleSegment tipSeg = segments[lastIdx];
        Vec3d tipWorld = tipSeg.lastPos.add(tipSeg.pos.subtract(tipSeg.lastPos).multiply(tickDelta));
        Vec3d tipLocal = tipWorld.subtract(entityPos);

        // Interpolate second-to-last segment for tangent
        TentacleSegment prevSeg = segments[lastIdx - 1];
        Vec3d prevWorld = prevSeg.lastPos.add(prevSeg.pos.subtract(prevSeg.lastPos).multiply(tickDelta));
        Vec3d prevLocal = prevWorld.subtract(entityPos);

        // Tangent points FROM prev segment TOWARD tip (the direction the hook is flying)
        Vec3d tangent = tipLocal.subtract(prevLocal);
        double tangentLen = tangent.length();
        if (tangentLen > 1e-8) {
            tangent = tangent.multiply(1.0 / tangentLen);
        } else {
            tangent = new Vec3d(0, -1, 0);
        }

        // Project tangent onto the camera plane to find the in-plane rotation angle.
        // tRight / tUp give the tangent's screen-space direction components.
        float tRight = (float)(tangent.x * billboardRight.x + tangent.y * billboardRight.y + tangent.z * billboardRight.z);
        float tUp    = (float)(tangent.x * billboardUp.x    + tangent.y * billboardUp.y    + tangent.z * billboardUp.z);

        // atan2(tRight, tUp): angle measured clockwise from screen-up toward screen-right.
        // The sprite's "up" axis should align with the tangent direction (pointing outward along
        // the arm), so we use the tangent directly as the rotated up-axis.
        float angle = (float) Math.atan2(tRight, tUp);

        // Rotate the billboard right/up axes by this angle in the camera plane
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);

        // rotated_up    = cos(a)*up    + sin(a)*right   (aligns with tangent direction)
        // rotated_right = cos(a)*right - sin(a)*up      (perpendicular to tangent in screen space)
        float rotUpX = cosA * billboardUp.x + sinA * billboardRight.x;
        float rotUpY = cosA * billboardUp.y + sinA * billboardRight.y;
        float rotUpZ = cosA * billboardUp.z + sinA * billboardRight.z;

        float rotRightX =  cosA * billboardRight.x - sinA * billboardUp.x;
        float rotRightY =  cosA * billboardRight.y - sinA * billboardUp.y;
        float rotRightZ =  cosA * billboardRight.z - sinA * billboardUp.z;

        float cx = (float) tipLocal.x;
        float cy = (float) tipLocal.y;
        float cz = (float) tipLocal.z;
        float half = TRAP_HOOK_HALF_SIZE;

        float blX = cx - rotRightX * half - rotUpX * half;
        float blY = cy - rotRightY * half - rotUpY * half;
        float blZ = cz - rotRightZ * half - rotUpZ * half;

        float brX = cx + rotRightX * half - rotUpX * half;
        float brY = cy + rotRightY * half - rotUpY * half;
        float brZ = cz + rotRightZ * half - rotUpZ * half;

        float trX = cx + rotRightX * half + rotUpX * half;
        float trY = cy + rotRightY * half + rotUpY * half;
        float trZ = cz + rotRightZ * half + rotUpZ * half;

        float tlX = cx - rotRightX * half + rotUpX * half;
        float tlY = cy - rotRightY * half + rotUpY * half;
        float tlZ = cz - rotRightZ * half + rotUpZ * half;

        float nx = billboardNormal.x;
        float ny = billboardNormal.y;
        float nz = billboardNormal.z;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(trapHookSprite.textureIdentifier));

        vc.vertex(mat, blX, blY, blZ).color(colorR, colorG, colorB, 255).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        vc.vertex(mat, brX, brY, brZ).color(colorR, colorG, colorB, 255).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        vc.vertex(mat, trX, trY, trZ).color(colorR, colorG, colorB, 255).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
        vc.vertex(mat, tlX, tlY, tlZ).color(colorR, colorG, colorB, 255).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, ny, nz);
    }

    /**
     * Render tentacle as a smooth 3D tube (cylindrical mesh along segments).
     * Similar to C# TriangleMesh approach but with full 3D tube geometry.
     */
    private void renderTube(StowawayBugEntity entity, TentacleSegment[] segments, MatrixStack matrices, VertexConsumer vc,
                            float baseRadius, int r, int g, int b, int light, float tickDelta, boolean grabberTip) {
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
            Vec3d localPos = worldPos.subtract(entityPos);  // Convert to entity-local

            positions[i] = localPos;

            // Calculate radius with taper
            float t = (float) i / (segments.length - 1);
            radii[i] = baseRadius * calculateTaper(t, grabberTip, segments.length);
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
     * If grabberTip is true, the last segment is fully 120% wide, with the
     * second-to-last segment widening smoothly from normal to 120%. No dropoff at tip.
     */
    private float calculateTaper(float t, boolean grabberTip, int segmentCount) {
        // Start thin, get thick, taper toward tip
        float startTaper = smoothstep(0f, 0.15f, t);
        // End taper: only apply if not in the grabber tip override zone
        float endTaper = 1f - smoothstep(0.7f, 1f, t);
        float base = Math.max(0.35f, startTaper * endTaper);

        if (grabberTip && segmentCount >= 4) {
            // Widen starts at n-3, reaches full 120% by n-2, then stays flat.
            float tWidenStart = (float)(segmentCount - 3) / (segmentCount - 1);
            float tWidenEnd   = (float)(segmentCount - 2) / (segmentCount - 1);

            if (t >= tWidenStart) {
                // Base taper value at the point the widen begins
                float baseAtWiden = Math.max(0.35f,
                        smoothstep(0f, 0.15f, tWidenStart) * (1f - smoothstep(0.7f, 1f, tWidenStart)));

                // Progress: 0 at tWidenStart, 1 at tWidenEnd, clamped at 1 beyond
                float widen = clamp((t - tWidenStart) / (tWidenEnd - tWidenStart), 0f, 1f);
                widen = smoothstep(0f, 1f, widen);

                base = MathHelper.lerp(widen, baseAtWiden, 1.2f);
            }
        }

        return base;
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

    @Override
    public Identifier getTexture(StowawayBugEntity entity) {
        return Identifier.of("karma-gate-mod", "textures/entity/stowaway.png");
    }
}
