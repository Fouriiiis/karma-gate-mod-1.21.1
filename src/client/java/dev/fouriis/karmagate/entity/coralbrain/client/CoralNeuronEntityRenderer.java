package dev.fouriis.karmagate.entity.coralbrain.client;

import dev.fouriis.karmagate.entity.coralbrain.CoralNeuronEntity;
import dev.fouriis.karmagate.entity.coralbrain.Mycelium;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.minecraft.client.render.*;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Stem: twisted X-strip quads (your existing).
 * Mycelia: smooth hair-ribbons via MyceliumRenderUtil.
 */
public class CoralNeuronEntityRenderer extends EntityRenderer<CoralNeuronEntity> {

    private static final Identifier WHITE_TEX = Identifier.of("minecraft", "textures/misc/white.png");
    private static FAtlasElement bumpSprite;

    public CoralNeuronEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0f;
    }

    @Override
    public void render(CoralNeuronEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {

        // C# renders one quad between each simulated body chunk. Subdividing
        // the curve made the visible sections one third of their reference
        // length and produced an overly busy, serrated stem.
        Vec3d[] pts = entity.getInterpolatedPointsLocal(tickDelta);
        if (pts == null || pts.length < 2) return;

        int firstIdx = -1;
        for (int j = 0; j < pts.length - 1; j++) {
            if (pts[j] == null || pts[j + 1] == null) continue;
            if (pts[j + 1].subtract(pts[j]).length() > 1e-6) { firstIdx = j; break; }
        }
        if (firstIdx == -1) return;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        Vec3d camWorld = this.dispatcher.camera.getPos();
        Vec3d camLocal = camWorld.subtract(entity.getPos());

        // ---------------------------
        // 1) Stem (unchanged)
        // ---------------------------
        VertexConsumer vcStem = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE_TEX));

        final float TWIST_PER_BLOCK = (float) (Math.PI);
        final float TWIST_NOISE_AMP = 0.0f;

        Vec3d tPrev = pts[firstIdx + 1].subtract(pts[firstIdx]);
        double tPrevLen = tPrev.length();
        if (tPrevLen < 1e-6) return;
        tPrev = tPrev.multiply(1.0 / tPrevLen);

        Vec3d up = new Vec3d(0, 1, 0);
        if (Math.abs(tPrev.dotProduct(up)) > 0.98) up = new Vec3d(1, 0, 0);

        Vec3d nPrev = tPrev.crossProduct(up);
        double nLen = nPrev.length();
        if (nLen < 1e-6) return;
        nPrev = nPrev.multiply(1.0 / nLen);

        Vec3d bPrev = tPrev.crossProduct(nPrev);
        double bLen = bPrev.length();
        if (bLen < 1e-6) return;
        bPrev = bPrev.multiply(1.0 / bLen);

        double totalLen = 0.0;
        for (int i = 0; i < pts.length - 1; i++) {
            if (pts[i] == null || pts[i + 1] == null) continue;
            totalLen += pts[i + 1].subtract(pts[i]).length();
        }
        totalLen = Math.max(totalLen, 1e-6);

        double s = 0.0;

        for (int i = 0; i < pts.length - 1; i++) {
            Vec3d A = pts[i];
            Vec3d B = pts[i + 1];
            if (A == null || B == null) continue;

            Vec3d seg = B.subtract(A);
            double segLen = seg.length();
            if (segLen < 1e-6) continue;

            Vec3d t = seg.multiply(1.0 / segLen);

            Frame transported = parallelTransportFrame(tPrev, t, nPrev, bPrev);
            Vec3d nBase = transported.n;
            Vec3d bBase2 = transported.b;

            double s0 = s;
            double s1 = s + segLen;

            float twist0 = (float) (s0 * TWIST_PER_BLOCK) + twistNoise(entity, s0) * TWIST_NOISE_AMP;
            float twist1 = (float) (s1 * TWIST_PER_BLOCK) + twistNoise(entity, s1) * TWIST_NOISE_AMP;

            Vec3d s1_0 = rotateAroundAxis(nBase, t, twist0);
            Vec3d s1_1 = rotateAroundAxis(nBase, t, twist1);

            Vec3d s2_0 = rotateAroundAxis(bBase2, t, twist0);
            Vec3d s2_1 = rotateAroundAxis(bBase2, t, twist1);

            float f0 = (float) (s0 / totalLen);
            float f1 = (float) (s1 / totalLen);
            float w0 = stemHalfWidth(f0);
            float w1 = stemHalfWidth(f1);
            int[] c0 = meshColor(f0);
            int[] c1 = meshColor(f1);

            emitTwistedQuadDoubleSided(vcStem, mat, A, B, s1_0, s1_1, w0, w1, c0, c1);
            emitTwistedQuadDoubleSided(vcStem, mat, A, B, s2_0, s2_1, w0, w1, c0, c1);

            s += segLen;
            tPrev = t;
            nPrev = nBase;
            bPrev = bBase2;
        }

        renderNeuronBumps(entity, pts, tickDelta, mat, vertexConsumers, camLocal);

        VertexConsumer vcMyc = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE_TEX));

        // Mycelia: red base -> pale -> bright blue tip
        // Mycelia: RW look — red base, pale body, BLUE ONLY ON EXTREME TIP
        List<Mycelium> strands = entity.getMycelia();
        if (strands != null && !strands.isEmpty()) {
            for (Mycelium m : strands) {
                if (m == null) continue;
                Vec3d[] mPts = m.samplePoints(tickDelta);

                for (int i = 0; i < mPts.length; i++) {
                    mPts[i] = mPts[i].subtract(entity.getPos());
                }
                float rootAlong = entity.myceliumRootAlong(m.index);
                int[] rootColor = meshColor(rootAlong);
                MyceliumRenderUtil.renderRainWorldMycelium(
                        vcMyc, mat, mPts, camLocal,
                        rootColor[0], rootColor[1], rootColor[2], 255,
                        LightmapTextureManager.MAX_LIGHT_COORDINATE
                );
            }
        }

        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    public Identifier getTexture(CoralNeuronEntity entity) {
        return WHITE_TEX;
    }

    @Override
    public boolean shouldRender(CoralNeuronEntity entity, Frustum frustum, double x, double y, double z) {
        // Disable frustum culling for CoralNeuron entities so long stems and mycelia
        // are rendered even if partially or fully outside the camera frustum.
        return true;
    }

    // ---------------------------
    // Stem helpers (unchanged)
    // ---------------------------

    private static float stemHalfWidth(float along) {
        float edge = Math.min(clampf(along / 0.08f, 0f, 1f),
                clampf((1f - along) / 0.08f, 0f, 1f));
        edge = edge * edge * (3f - 2f * edge);
        return MathHelper.lerp(edge, 1f / 20f, 2.2f / 20f);
    }

    private static void renderNeuronBumps(CoralNeuronEntity entity, Vec3d[] points,
                                          float tickDelta, Matrix4f matrix,
                                          VertexConsumerProvider consumers, Vec3d cameraLocal) {
        FAtlasElement sprite = getBumpSprite();
        if (sprite == null || sprite.textureIdentifier == null || points.length < 2) return;
        VertexConsumer vertices = consumers.getBuffer(
                RenderLayer.getEntityTranslucentEmissive(sprite.textureIdentifier));
        int bumpCount = Math.max(1, (int) (entity.getSegmentPointCount() * 1.2f));
        double simulationFrame = entity.getWorld().getTime() * 2.0 + tickDelta * 2.0;
        long baseSeed = entity.getId() * 0x9E3779B97F4A7C15L + points.length * 0xD1B54A32D192ED03L;
        float halfWidth = Math.max(0.025f, sprite.sourcePixelSize.x / 40f);
        float halfHeight = Math.max(0.025f, sprite.sourcePixelSize.y / 40f);

        for (int i = 0; i < bumpCount; i++) {
            float along = (float) hash01(baseSeed ^ (i * 0x94D049BB133111EBL));
            float lateral = (float) (hash01(baseSeed ^ 0xBF58476D1CE4E5B9L ^ i * 0x632BE59BD9B4E019L) * 2.0 - 1.0);
            float location = along * (points.length - 1);
            int segment = Math.min(points.length - 2, Math.max(0, (int) Math.floor(location)));
            float segmentT = location - segment;
            Vec3d tangent = points[segment + 1].subtract(points[segment]);
            if (tangent.lengthSquared() < 1.0e-10) continue;
            tangent = tangent.normalize();
            Vec3d stemPoint = points[segment].lerp(points[segment + 1], segmentT);

            Vec3d reference = Math.abs(tangent.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
            Vec3d normal = tangent.crossProduct(reference).normalize();
            Vec3d binormal = tangent.crossProduct(normal).normalize();
            double angle = hash01(baseSeed ^ 0xA24BAED4963EE407L ^ i * 0x9FB21C651E98DF25L) * Math.PI * 2.0;
            Vec3d radial = normal.multiply(Math.cos(angle)).add(binormal.multiply(Math.sin(angle)));
            Vec3d center = stemPoint.add(radial.multiply(lateral * 8.0 / 20.0));

            Vec3d view = cameraLocal.subtract(center);
            if (view.lengthSquared() < 1.0e-10) view = new Vec3d(0, 0, 1);
            else view = view.normalize();
            Vec3d right = view.crossProduct(tangent);
            if (right.lengthSquared() < 1.0e-10) right = normal;
            else right = right.normalize();
            Vec3d up = right.crossProduct(view).normalize();

            int[] stemColor = meshColor(along);
            int[] baseColor = new int[] {
                    Math.round(MathHelper.lerp(0.25f, stemColor[0], 0)),
                    Math.round(MathHelper.lerp(0.25f, stemColor[1], 0)),
                    Math.round(MathHelper.lerp(0.25f, stemColor[2], 51))
            };
            emitAtlasBillboard(vertices, matrix, sprite, center, right, up,
                    halfWidth, halfHeight, baseColor, 255);

            Vec3d highlight = center.add(view.multiply(0.002)).add(right.multiply(-3.0 / 20.0))
                    .add(up.multiply(3.0 / 20.0));
            emitAtlasBillboard(vertices, matrix, sprite, highlight, right, up,
                    halfWidth * 0.5f, halfHeight * 0.5f, new int[] {255, 255, 255}, 77);

            int period = 50 + (int) (hash01(baseSeed ^ 0xDB4F0B9175AE2165L ^ i) * 80.0);
            double phase = (simulationFrame + hash01(baseSeed ^ i * 31L) * period) % period;
            float ping = phase < 10.0 ? 1f - (float) phase * 0.1f : 0f;
            int[] pingColor = hslToRgb(2f / 3f, 1f, 0.1f + 0.9f * ping);
            emitAtlasBillboard(vertices, matrix, sprite, center.add(view.multiply(0.004)), right, up,
                    halfWidth * 0.5f, halfHeight * 0.5f, pingColor, 255);
        }
    }

    private static FAtlasElement getBumpSprite() {
        if (bumpSprite != null && bumpSprite.textureIdentifier != null) return bumpSprite;
        for (String name : new String[] {"deerEyeB", "DeerEyeB", "JetFishEyeB"}) {
            FAtlasElement candidate = LibrainworldmcClient.getAtlasManager().getElementWithName(name);
            if (candidate != null && candidate.textureIdentifier != null) {
                bumpSprite = candidate;
                break;
            }
        }
        return bumpSprite;
    }

    private static void emitAtlasBillboard(VertexConsumer vertices, Matrix4f matrix,
                                           FAtlasElement sprite, Vec3d center,
                                           Vec3d right, Vec3d up,
                                           float halfWidth, float halfHeight,
                                           int[] color, int alpha) {
        Vec3d r = right.multiply(halfWidth);
        Vec3d u = up.multiply(halfHeight);
        putAtlasVertex(vertices, matrix, center.subtract(r).subtract(u), sprite.uvBottomLeft, color, alpha);
        putAtlasVertex(vertices, matrix, center.add(r).subtract(u), sprite.uvBottomRight, color, alpha);
        putAtlasVertex(vertices, matrix, center.add(r).add(u), sprite.uvTopRight, color, alpha);
        putAtlasVertex(vertices, matrix, center.subtract(r).add(u), sprite.uvTopLeft, color, alpha);
    }

    private static void putAtlasVertex(VertexConsumer vertices, Matrix4f matrix, Vec3d position,
                                       FAtlasElement.Vec2 uv, int[] color, int alpha) {
        vertices.vertex(matrix, (float) position.x, (float) position.y, (float) position.z)
                .color(color[0], color[1], color[2], alpha)
                .texture(uv.x, uv.y)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
                .normal(0f, 1f, 0f);
    }

    private static double hash01(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return ((value >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    /** Direct port of CoralNeuron.MeshColor; 20 Rain World pixels equal one block. */
    private static int[] meshColor(float along) {
        float f = Math.abs(clampf(along, 0f, 1f) - 0.5f) * 2f;
        float curved = 0.5f + 0.5f * f * f * f;
        float hue = MathHelper.lerp(curved, 1.025f, 0.9638889f);
        hue = hue - (float) Math.floor(hue);
        float saturation = lerpMap(f, 0.8f, 1f, 1f, 0.5f);
        float luminance = lerpMap(f, 0.7f, 1f, 0.5f, 0.15f);
        return hslToRgb(hue, saturation, luminance);
    }

    private static float lerpMap(float value, float inMin, float inMax, float outMin, float outMax) {
        float t = clampf((value - inMin) / Math.max(1.0e-6f, inMax - inMin), 0f, 1f);
        return MathHelper.lerp(t, outMin, outMax);
    }

    private static int[] hslToRgb(float hue, float saturation, float luminance) {
        float c = (1f - Math.abs(2f * luminance - 1f)) * saturation;
        float h = hue * 6f;
        float x = c * (1f - Math.abs(h % 2f - 1f));
        float r1 = 0f, g1 = 0f, b1 = 0f;
        if (h < 1f) { r1 = c; g1 = x; }
        else if (h < 2f) { r1 = x; g1 = c; }
        else if (h < 3f) { g1 = c; b1 = x; }
        else if (h < 4f) { g1 = x; b1 = c; }
        else if (h < 5f) { r1 = x; b1 = c; }
        else { r1 = c; b1 = x; }
        float m = luminance - c * 0.5f;
        return new int[] {
                Math.round((r1 + m) * 255f),
                Math.round((g1 + m) * 255f),
                Math.round((b1 + m) * 255f)
        };
    }

    private static float clampf(float x, float lo, float hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static float twistNoise(CoralNeuronEntity e, double s) {
        float seed = e.getId() * 0.173f;
        return (float) Math.sin(s * 0.7 + seed);
    }

    private static void emitTwistedQuadDoubleSided(VertexConsumer vc, Matrix4f mat,
                                                  Vec3d p0, Vec3d p1,
                                                  Vec3d s0, Vec3d s1,
                                                  float w0, float w1,
                                                  int[] c0, int[] c1) {

        if (w0 <= 1e-6f && w1 <= 1e-6f) return;

        float p0x = (float) p0.x, p0y = (float) p0.y, p0z = (float) p0.z;
        float p1x = (float) p1.x, p1y = (float) p1.y, p1z = (float) p1.z;

        float s0x = (float) s0.x, s0y = (float) s0.y, s0z = (float) s0.z;
        float s1x = (float) s1.x, s1y = (float) s1.y, s1z = (float) s1.z;

        float a0x = p0x - s0x * w0;
        float a0y = p0y - s0y * w0;
        float a0z = p0z - s0z * w0;

        float a1x = p0x + s0x * w0;
        float a1y = p0y + s0y * w0;
        float a1z = p0z + s0z * w0;

        float b1x = p1x + s1x * w1;
        float b1y = p1y + s1y * w1;
        float b1z = p1z + s1z * w1;

        float b0x = p1x - s1x * w1;
        float b0y = p1y - s1y * w1;
        float b0z = p1z - s1z * w1;

        v(vc, mat, a0x, a0y, a0z, c0, 0f, 0f);
        v(vc, mat, a1x, a1y, a1z, c0, 1f, 0f);
        v(vc, mat, b1x, b1y, b1z, c1, 1f, 1f);
        v(vc, mat, b0x, b0y, b0z, c1, 0f, 1f);

        v(vc, mat, b0x, b0y, b0z, c1, 0f, 1f);
        v(vc, mat, b1x, b1y, b1z, c1, 1f, 1f);
        v(vc, mat, a1x, a1y, a1z, c0, 1f, 0f);
        v(vc, mat, a0x, a0y, a0z, c0, 0f, 0f);
    }

    private static void v(VertexConsumer vc, Matrix4f mat,
                          float x, float y, float z,
                          int[] color, float u, float v) {
        vc.vertex(mat, x, y, z)
                .color(color[0], color[1], color[2], 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
                .normal(0f, 1f, 0f);
    }

    // ---------------------------
    // Frame math
    // ---------------------------

    private record Frame(Vec3d n, Vec3d b) {}

    private static Frame parallelTransportFrame(Vec3d tPrev, Vec3d t, Vec3d nPrev, Vec3d bPrev) {
        double dot = clamp(tPrev.dotProduct(t), -1.0, 1.0);
        if (dot > 0.9999) return new Frame(nPrev, bPrev);

        Vec3d axis = tPrev.crossProduct(t);
        double axisLen = axis.length();

        if (axisLen < 1e-9) {
            Vec3d up = new Vec3d(0, 1, 0);
            if (Math.abs(t.dotProduct(up)) > 0.98) up = new Vec3d(1, 0, 0);

            Vec3d n = t.crossProduct(up);
            double nLen = n.length();
            if (nLen < 1e-9) n = new Vec3d(1, 0, 0);
            else n = n.multiply(1.0 / nLen);

            Vec3d b = t.crossProduct(n);
            double bLen = b.length();
            if (bLen < 1e-9) b = new Vec3d(0, 0, 1);
            else b = b.multiply(1.0 / bLen);

            return new Frame(n, b);
        }

        axis = axis.multiply(1.0 / axisLen);
        double angle = Math.acos(dot);

        Vec3d n = rotateAroundAxis(nPrev, axis, angle);
        Vec3d b = rotateAroundAxis(bPrev, axis, angle);

        n = n.subtract(t.multiply(n.dotProduct(t)));
        double nLen2 = n.length();
        if (nLen2 > 1e-9) n = n.multiply(1.0 / nLen2);

        b = t.crossProduct(n);
        double bLen2 = b.length();
        if (bLen2 > 1e-9) b = b.multiply(1.0 / bLen2);

        return new Frame(n, b);
    }

    private static Vec3d rotateAroundAxis(Vec3d v, Vec3d axisUnit, double angle) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);

        Vec3d term1 = v.multiply(c);
        Vec3d term2 = axisUnit.crossProduct(v).multiply(s);
        Vec3d term3 = axisUnit.multiply(axisUnit.dotProduct(v) * (1.0 - c));
        return term1.add(term2).add(term3);
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
