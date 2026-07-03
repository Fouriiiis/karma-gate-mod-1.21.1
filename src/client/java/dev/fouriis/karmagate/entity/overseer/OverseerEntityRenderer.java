package dev.fouriis.karmagate.entity.overseer;

import dev.fouriis.karmagate.entity.client.MyceliumRenderUtil;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

public class OverseerEntityRenderer extends EntityRenderer<OverseerEntity> {
    private static final Identifier WHITE_TEXTURE = Identifier.of("minecraft", "textures/block/white_concrete.png");
    private static final double MAX_RENDERED_TETHER_LENGTH = 2.0;
    private static final String[] EYE_SPRITE_CANDIDATES = {
            "Circle20",
            "mouseEyeA1",
            "mouseEyeB1",
            "JetFishEyeB",
            "tinyStar"
    };

    private static FAtlasElement eyeSprite;
    private static boolean triedLoadEyeSprite;

    public OverseerEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.15f;
    }

    @Override
    public void render(OverseerEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        matrices.translate(0.0, entity.getHeight() * 0.52f, 0.0);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        int fullBright = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        OverseerEntity.ColorVariant variant = entity.getColorVariant();
        int bodyColor = variant.bodyColor();
        int glowColor = variant.glowColor();

        Vec3d forward = computeForward(entity);
        Vec3d[] frame = makeFrame(forward);
        Vec3d right = frame[0];
        Vec3d up = frame[1];
        Vec3d bodyCenterWorld = entity.getPos().add(0.0, entity.getHeight() * 0.52, 0.0);
        Vec3d rootLocal = limitedRootLocal(entity, bodyCenterWorld);
        Vec3d camLocal = this.dispatcher.camera.getPos().subtract(bodyCenterWorld);
        float extended = entity.getExtended();

        VertexConsumer bodyVc = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE_TEXTURE));
        VertexConsumer hairVc = vertexConsumers.getBuffer(RenderLayer.getEntitySolid(WHITE_TEXTURE));
        renderBody(entity, bodyVc, matrix, rootLocal, forward, right, up, bodyColor, fullBright, extended);
        renderTendrils(entity, hairVc, matrix, forward, right, up, camLocal, glowColor, fullBright, extended);
        renderEye(vertexConsumers, matrix, forward, right, up, glowColor, fullBright);

        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    private static Vec3d computeForward(OverseerEntity entity) {
        Vec3d look = entity.getLookTarget();
        Vec3d eyePos = entity.getPos().add(0.0, entity.getHeight() * 0.52, 0.0);
        Vec3d forward = look.subtract(eyePos);
        if (forward.lengthSquared() < 1.0E-6) {
            float yawRad = entity.getYaw() * MathHelper.RADIANS_PER_DEGREE;
            forward = new Vec3d(-MathHelper.sin(yawRad), 0.0, MathHelper.cos(yawRad));
        }
        return forward.normalize();
    }

    private static Vec3d[] makeFrame(Vec3d forward) {
        Vec3d worldUp = new Vec3d(0.0, 1.0, 0.0);
        Vec3d right = worldUp.crossProduct(forward);
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vec3d(1.0, 0.0, 0.0).crossProduct(forward);
        }
        right = right.normalize();
        Vec3d up = forward.crossProduct(right);
        if (up.lengthSquared() < 1.0E-6) {
            up = worldUp;
        } else {
            up = up.normalize();
        }
        return new Vec3d[] { right, up };
    }

    private static void renderBody(OverseerEntity entity, VertexConsumer vc, Matrix4f matrix,
                                   Vec3d rootLocal, Vec3d forward, Vec3d right, Vec3d up,
                                   int color, int light, float extended) {
        int segments = 10;
        Vec3d[] points = new Vec3d[segments];
        float[] radii = new float[segments];
        float age = entity.age;
        float open = MathHelper.clamp(extended, 0f, 1f);
        float openScale = 0.22f + 0.78f * open;

        Vec3d rootToBodyDir = safeNormalize(rootLocal.multiply(-1.0), forward);
        Vec3d head = forward.multiply(0.22 * openScale);
        Vec3d root = rootLocal.multiply(open);
        Vec3d headControl = head.subtract(forward.multiply(0.36 * openScale));
        Vec3d rootControl = root.add(rootToBodyDir.multiply(Math.min(0.72, root.length() * 0.45) * openScale));

        for (int i = 0; i < segments; i++) {
            float t = i / (float) (segments - 1);
            float wave = MathHelper.sin(age * 0.18f + t * 7.5f) * 0.026f * open * (1.0f - t);
            float lift = MathHelper.sin(age * 0.13f + t * 5.2f) * 0.020f * open * (1.0f - t);
            points[i] = cubicBezier(head, headControl, rootControl, root, t)
                    .add(right.multiply(wave))
                    .add(up.multiply(lift));

            float rootTaper = 1.0f - smoothstep(0.64f, 1.0f, t);
            float bulb = 0.55f + 0.45f * (1.0f - smoothstep(0.0f, 0.34f, t));
            radii[i] = (0.012f + 0.18f * bulb * rootTaper) * (0.35f + 0.65f * open);
        }

        renderTube(vc, matrix, points, radii, 4, color, 178, light);
        renderTube(vc, matrix, points, scaleRadii(radii, 1.34f), 4, color, 38, light);
    }

    private static float[] scaleRadii(float[] radii, float scale) {
        float[] scaled = new float[radii.length];
        for (int i = 0; i < radii.length; i++) {
            scaled[i] = radii[i] * scale;
        }
        return scaled;
    }

    private static void renderTendrils(OverseerEntity entity, VertexConsumer vc, Matrix4f matrix,
                                       Vec3d forward, Vec3d right, Vec3d up, Vec3d camLocal,
                                       int color, int light, float extended) {
        float age = entity.age;
        int count = entity.getLimbCount();
        float open = MathHelper.clamp(extended, 0f, 1f);
        for (int i = 0; i < count; i++) {
            float angle = (float) (Math.PI * 2.0 * i / count) + MathHelper.sin(age * 0.04f + i) * 0.18f;
            Vec3d radial = right.multiply(MathHelper.cos(angle)).add(up.multiply(MathHelper.sin(angle))).normalize();
            float length = (0.34f + 0.06f * MathHelper.sin(age * 0.07f + i * 1.7f)) * open;

            Vec3d root = forward.multiply(0.11).add(radial.multiply(0.16));
            Vec3d mid = forward.multiply(0.16 + length * 0.45)
                    .add(radial.multiply(0.19 + length * 0.28))
                    .add(up.multiply(MathHelper.sin(age * 0.1f + i * 0.9f) * 0.035f));
            Vec3d tip = forward.multiply(0.18 + length)
                    .add(radial.multiply(0.22 + length * 0.52))
                    .add(up.multiply(MathHelper.sin(age * 0.12f + i * 1.3f) * 0.06f));

            renderHair(vc, matrix, new Vec3d[] { root, mid, tip, tip.add(radial.multiply(0.03)) }, camLocal,
                    0.015f, color, light, (int) (190 * open), entity.getId() * 997L + i * 313L);
        }
    }

    private static void renderHair(VertexConsumer vc, Matrix4f matrix, Vec3d[] points, Vec3d camLocal,
                                   float width, int color, int light, int alpha, long seed) {
        int baseR = (color >> 16) & 0xFF;
        int baseG = (color >> 8) & 0xFF;
        int baseB = color & 0xFF;

        MyceliumRenderUtil.renderHairRibbonGradient(
                vc, matrix, points, camLocal, width,
                baseR, Math.max(70, baseG - 70), 35,
                238, 232, 174,
                70, 150, 255,
                0.45f, 0.10f, 5.0f,
                MathHelper.clamp(alpha, 0, 255),
                light,
                true,
                4,
                seed
        );
    }

    private static Vec3d limitedRootLocal(OverseerEntity entity, Vec3d bodyCenterWorld) {
        Vec3d rootLocal = entity.getRootPos().subtract(bodyCenterWorld);
        if (rootLocal.length() > MAX_RENDERED_TETHER_LENGTH) {
            rootLocal = rootLocal.normalize().multiply(MAX_RENDERED_TETHER_LENGTH);
        }
        return rootLocal;
    }

    private static Vec3d safeNormalize(Vec3d vector, Vec3d fallback) {
        if (vector.lengthSquared() < 1.0E-6) {
            return fallback.lengthSquared() < 1.0E-6 ? new Vec3d(0.0, 0.0, 1.0) : fallback.normalize();
        }
        return vector.normalize();
    }

    private static Vec3d cubicBezier(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, float t) {
        double inv = 1.0 - t;
        return p0.multiply(inv * inv * inv)
                .add(p1.multiply(3.0 * inv * inv * t))
                .add(p2.multiply(3.0 * inv * t * t))
                .add(p3.multiply(t * t * t));
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = MathHelper.clamp((value - edge0) / Math.max(1.0E-6f, edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static void renderEye(VertexConsumerProvider vertexConsumers, Matrix4f matrix,
                                  Vec3d forward, Vec3d right, Vec3d up, int color, int light) {
        FAtlasElement sprite = getEyeSprite();
        Vec3d center = forward.multiply(0.21);
        float eyeHalf = 0.185f;
        if (sprite != null && sprite.textureIdentifier != null) {
            VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(sprite.textureIdentifier));
            emitQuad(vc, matrix, center, right, up, forward, eyeHalf, eyeHalf, 255, 255, 255, 235, light);
            return;
        }

        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE_TEXTURE));
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        emitQuad(vc, matrix, center, right, up, forward, eyeHalf, eyeHalf, r, g, b, 230, light);
        emitQuad(vc, matrix, center.add(forward.multiply(0.006)), right, up, forward, eyeHalf * 0.36f, eyeHalf * 0.36f,
                20, 20, 16, 245, light);
    }

    private static FAtlasElement getEyeSprite() {
        if (triedLoadEyeSprite) {
            return eyeSprite;
        }
        triedLoadEyeSprite = true;
        try {
            var atlas = LibrainworldmcClient.getAtlasManager();
            for (String candidate : EYE_SPRITE_CANDIDATES) {
                FAtlasElement element = atlas.getElementWithName(candidate);
                if (element != null && element.textureIdentifier != null) {
                    eyeSprite = element;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            eyeSprite = null;
        }
        return eyeSprite;
    }

    private static void renderTube(VertexConsumer vc, Matrix4f matrix, Vec3d[] points, float[] radii,
                                   int sides, int color, int alpha, int light) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        Vec3d[][] rings = new Vec3d[points.length][sides];
        Vec3d[][] normals = new Vec3d[points.length][sides];
        for (int i = 0; i < points.length; i++) {
            Vec3d tangent;
            if (i == 0) {
                tangent = points[1].subtract(points[0]);
            } else if (i == points.length - 1) {
                tangent = points[i].subtract(points[i - 1]);
            } else {
                tangent = points[i + 1].subtract(points[i - 1]);
            }
            if (tangent.lengthSquared() < 1.0E-6) {
                tangent = new Vec3d(0.0, 0.0, 1.0);
            }
            tangent = tangent.normalize();
            Vec3d[] frame = makeFrame(tangent);
            Vec3d right = frame[0];
            Vec3d up = frame[1];
            for (int s = 0; s < sides; s++) {
                float angle = (float) (Math.PI * 2.0 * s / sides);
                Vec3d normal = right.multiply(MathHelper.cos(angle)).add(up.multiply(MathHelper.sin(angle))).normalize();
                normals[i][s] = normal;
                rings[i][s] = points[i].add(normal.multiply(radii[i]));
            }
        }

        for (int i = 0; i < points.length - 1; i++) {
            for (int s = 0; s < sides; s++) {
                int next = (s + 1) % sides;
                Vec3d normal = normals[i][s].add(normals[i][next]).add(normals[i + 1][s]).add(normals[i + 1][next]);
                if (normal.lengthSquared() < 1.0E-6) {
                    normal = normals[i][s];
                } else {
                    normal = normal.normalize();
                }
                putVertex(vc, matrix, rings[i][s], 0f, 1f, r, g, b, alpha, light, normal);
                putVertex(vc, matrix, rings[i][next], 1f, 1f, r, g, b, alpha, light, normal);
                putVertex(vc, matrix, rings[i + 1][next], 1f, 0f, r, g, b, alpha, light, normal);
                putVertex(vc, matrix, rings[i + 1][s], 0f, 0f, r, g, b, alpha, light, normal);
            }
        }
    }

    private static void emitQuad(VertexConsumer vc, Matrix4f matrix, Vec3d center,
                                 Vec3d rightUnit, Vec3d upUnit, Vec3d normalUnit,
                                 float halfWidth, float halfHeight,
                                 int r, int g, int b, int a, int light) {
        Vec3d right = rightUnit.multiply(halfWidth);
        Vec3d up = upUnit.multiply(halfHeight);
        putVertex(vc, matrix, center.subtract(right).subtract(up), 0f, 1f, r, g, b, a, light, normalUnit);
        putVertex(vc, matrix, center.add(right).subtract(up), 1f, 1f, r, g, b, a, light, normalUnit);
        putVertex(vc, matrix, center.add(right).add(up), 1f, 0f, r, g, b, a, light, normalUnit);
        putVertex(vc, matrix, center.subtract(right).add(up), 0f, 0f, r, g, b, a, light, normalUnit);
    }

    private static void putVertex(VertexConsumer vc, Matrix4f matrix, Vec3d point, float u, float v,
                                  int r, int g, int b, int a, int light, Vec3d normal) {
        vc.vertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .color(r, g, b, a)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    @Override
    public Identifier getTexture(OverseerEntity entity) {
        return WHITE_TEXTURE;
    }
}
