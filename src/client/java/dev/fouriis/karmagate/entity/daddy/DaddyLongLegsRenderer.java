package dev.fouriis.karmagate.entity.daddy;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class DaddyLongLegsRenderer extends EntityRenderer<DaddyLongLegsEntity> {
    private static final Identifier WHITE_TEX = Identifier.of("minecraft", "textures/misc/white.png");
    private static final int SPHERE_SEGMENTS = 12;
    private static final int SPHERE_RINGS = 8;
    private static final int TUBE_SIDES = 6;
    private static final int MAX_CACHE = 256;

    private static class CoreLobe {
        final Vec3d offset;
        final float radius;
        final boolean eye;
        final float eyeRotation;

        CoreLobe(Vec3d offset, float radius, boolean eye, float eyeRotation) {
            this.offset = offset;
            this.radius = radius;
            this.eye = eye;
            this.eyeRotation = eyeRotation;
        }
    }

    private final Map<Integer, List<CoreLobe>> coreCache = new HashMap<>();

    public DaddyLongLegsRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 1.1f;
    }

    @Override
    public Identifier getTexture(DaddyLongLegsEntity entity) {
        return WHITE_TEX;
    }

    @Override
    public void render(DaddyLongLegsEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
        if (coreCache.size() > MAX_CACHE) {
            coreCache.clear();
        }

        VertexConsumer darkVc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE_TEX));
        VertexConsumer glowVc = vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(WHITE_TEX));

        Vec3d center = new Vec3d(0, entity.getHeight() * 0.52, 0);
        renderBodyCluster(entity, matrices, darkVc, glowVc, center, light, tickDelta);
        renderTentacles(entity, matrices, darkVc, glowVc, light, tickDelta);

        super.render(entity, yaw, tickDelta, matrices, vcp, light);
    }

    private void renderBodyCluster(DaddyLongLegsEntity entity, MatrixStack matrices, VertexConsumer darkVc, VertexConsumer glowVc, Vec3d center, int light, float tickDelta) {
        List<CoreLobe> lobes = getOrCreateCoreLobes(entity);
        float t = entity.age + tickDelta;

        for (int i = 0; i < lobes.size(); i++) {
            CoreLobe lobe = lobes.get(i);
            float wobble = (float) Math.sin(t * 0.08 + i * 1.71) * 0.018f;
            Vec3d lobeCenter = center.add(lobe.offset).add(0, wobble, 0);

            float shade = 0.88f + ((i & 1) == 0 ? 0.08f : 0f);
            float r = 0.03f * shade;
            float g = 0.035f * shade;
            float b = 0.05f * shade;
            renderSphere(matrices, darkVc, lobeCenter, lobe.radius, r, g, b, 1.0f, light);

            if (lobe.eye) {
                int fullBright = LightmapTextureManager.pack(15, 15);
                renderEyeCross(matrices, glowVc, lobeCenter, lobe.radius * 0.95f, lobe.eyeRotation + t * 0.8f, fullBright);
            }
        }

        int fullBright = LightmapTextureManager.pack(15, 15);
        float pulse = 0.7f + (float) Math.sin((entity.age + tickDelta) * 0.2f) * 0.3f;
        for (int i = 0; i < entity.getTentacleCount(); i++) {
            Vec3d socketWorld = entity.getTentacleSocketPosition(i, tickDelta);
            Vec3d socket = socketWorld.subtract(entity.getPos());
            float s = 0.06f + ((i & 1) == 0 ? 0.015f : 0f);
            drawQuad(matrices, glowVc, socket, new Vec3d(s, 0, 0), new Vec3d(0, s, 0), (int) (20 * pulse), (int) (130 * pulse), 255, 235, fullBright);
            drawQuad(matrices, glowVc, socket, new Vec3d(0, 0, s), new Vec3d(0, s, 0), (int) (20 * pulse), (int) (130 * pulse), 255, 235, fullBright);
        }
    }

    private void renderTentacles(DaddyLongLegsEntity entity, MatrixStack matrices, VertexConsumer darkVc, VertexConsumer glowVc, int light, float tickDelta) {
        List<DaddyLongLegsEntity.RenderTentacleData> tentacleData = entity.getRenderTentacles();
        if (tentacleData.isEmpty()) {
            return;
        }

        int fullBright = LightmapTextureManager.pack(15, 15);
        for (int i = 0; i < tentacleData.size(); i++) {
            DaddyLongLegsEntity.RenderTentacleData data = tentacleData.get(i);
            List<Vec3d> worldPath = new ArrayList<>(data.points());
            if (worldPath.size() < 2) {
                Vec3d socket = entity.getTentacleSocketPosition(i, tickDelta);
                worldPath.add(socket);
                worldPath.add(data.tipPos());
            }

            List<Vec3d> smoothed = catmullRom(worldPath, 3);
            float baseRadius = data.support() ? 0.052f : 0.046f;
            renderTube(entity, matrices, darkVc, smoothed, baseRadius, light);

            for (int n = 2; n < smoothed.size() - 1; n += 5) {
                Vec3d local = smoothed.get(n).subtract(entity.getPos());
                float s = 0.045f;
                drawQuad(matrices, glowVc, local, new Vec3d(s, 0, 0), new Vec3d(0, s, 0), 15, 120, 255, 210, fullBright);
            }

            Vec3d tipLocal = smoothed.get(smoothed.size() - 1).subtract(entity.getPos());
            float tipSize = data.anchored() ? 0.075f : 0.06f;
            drawQuad(matrices, glowVc, tipLocal, new Vec3d(tipSize, 0, 0), new Vec3d(0, tipSize, 0), 35, 170, 255, 235, fullBright);
            drawQuad(matrices, glowVc, tipLocal, new Vec3d(0, 0, tipSize), new Vec3d(0, tipSize, 0), 35, 170, 255, 235, fullBright);
        }
    }

    private List<Vec3d> catmullRom(List<Vec3d> src, int subdivisions) {
        if (src.size() < 3) {
            return src;
        }

        List<Vec3d> out = new ArrayList<>();
        out.add(src.get(0));
        for (int i = 0; i < src.size() - 1; i++) {
            Vec3d p0 = i > 0 ? src.get(i - 1) : src.get(i);
            Vec3d p1 = src.get(i);
            Vec3d p2 = src.get(i + 1);
            Vec3d p3 = i + 2 < src.size() ? src.get(i + 2) : src.get(i + 1);

            for (int s = 1; s <= subdivisions; s++) {
                double t = s / (double) subdivisions;
                out.add(catmullRomPoint(p0, p1, p2, p3, t));
            }
        }
        return out;
    }

    private Vec3d catmullRomPoint(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;

        double x = 0.5 * ((2.0 * p1.x)
                + (-p0.x + p2.x) * t
                + (2.0 * p0.x - 5.0 * p1.x + 4.0 * p2.x - p3.x) * t2
                + (-p0.x + 3.0 * p1.x - 3.0 * p2.x + p3.x) * t3);
        double y = 0.5 * ((2.0 * p1.y)
                + (-p0.y + p2.y) * t
                + (2.0 * p0.y - 5.0 * p1.y + 4.0 * p2.y - p3.y) * t2
                + (-p0.y + 3.0 * p1.y - 3.0 * p2.y + p3.y) * t3);
        double z = 0.5 * ((2.0 * p1.z)
                + (-p0.z + p2.z) * t
                + (2.0 * p0.z - 5.0 * p1.z + 4.0 * p2.z - p3.z) * t2
                + (-p0.z + 3.0 * p1.z - 3.0 * p2.z + p3.z) * t3);
        return new Vec3d(x, y, z);
    }

    private List<CoreLobe> getOrCreateCoreLobes(DaddyLongLegsEntity entity) {
        List<CoreLobe> lobes = coreCache.get(entity.getId());
        if (lobes != null) {
            return lobes;
        }

        float bodyR = entity.getVariantConfig().bodyRadius();
        Random random = new Random(entity.getUuid().getMostSignificantBits() ^ entity.getUuid().getLeastSignificantBits());
        int count = 7 + random.nextInt(4);
        lobes = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            double ox = (random.nextDouble() - 0.5) * bodyR * 0.95;
            double oy = (random.nextDouble() - 0.5) * bodyR * 0.72;
            double oz = (random.nextDouble() - 0.5) * bodyR * 0.95;
            float radius = bodyR * (0.22f + random.nextFloat() * 0.16f);
            boolean eye = random.nextFloat() < 0.33f;
            float eyeRot = random.nextFloat() * 360f;
            lobes.add(new CoreLobe(new Vec3d(ox, oy, oz), radius, eye, eyeRot));
        }

        coreCache.put(entity.getId(), lobes);
        return lobes;
    }

    private void renderSphere(MatrixStack matrices, VertexConsumer consumer,
                              Vec3d center, float radius,
                              float r, float g, float b, float a, int light) {
        Matrix4f posMatrix = matrices.peek().getPositionMatrix();

        for (int ring = 0; ring < SPHERE_RINGS; ring++) {
            float theta1 = (float) (ring * Math.PI / SPHERE_RINGS);
            float theta2 = (float) ((ring + 1) * Math.PI / SPHERE_RINGS);

            float y1 = (float) Math.cos(theta1) * radius;
            float y2 = (float) Math.cos(theta2) * radius;
            float rr1 = (float) Math.sin(theta1) * radius;
            float rr2 = (float) Math.sin(theta2) * radius;

            for (int seg = 0; seg < SPHERE_SEGMENTS; seg++) {
                float phi1 = (float) (seg * 2 * Math.PI / SPHERE_SEGMENTS);
                float phi2 = (float) ((seg + 1) * 2 * Math.PI / SPHERE_SEGMENTS);

                float x11 = (float) center.x + (float) Math.cos(phi1) * rr1;
                float z11 = (float) center.z + (float) Math.sin(phi1) * rr1;
                float x12 = (float) center.x + (float) Math.cos(phi2) * rr1;
                float z12 = (float) center.z + (float) Math.sin(phi2) * rr1;

                float x21 = (float) center.x + (float) Math.cos(phi1) * rr2;
                float z21 = (float) center.z + (float) Math.sin(phi1) * rr2;
                float x22 = (float) center.x + (float) Math.cos(phi2) * rr2;
                float z22 = (float) center.z + (float) Math.sin(phi2) * rr2;

                float yy1 = (float) center.y + y1;
                float yy2 = (float) center.y + y2;

                Vector3f n11 = new Vector3f(x11 - (float) center.x, yy1 - (float) center.y, z11 - (float) center.z).normalize();
                Vector3f n12 = new Vector3f(x12 - (float) center.x, yy1 - (float) center.y, z12 - (float) center.z).normalize();
                Vector3f n21 = new Vector3f(x21 - (float) center.x, yy2 - (float) center.y, z21 - (float) center.z).normalize();
                Vector3f n22 = new Vector3f(x22 - (float) center.x, yy2 - (float) center.y, z22 - (float) center.z).normalize();

                vertex(consumer, posMatrix, x11, yy1, z11, r, g, b, a, 0f, 0f, light, n11.x, n11.y, n11.z);
                vertex(consumer, posMatrix, x21, yy2, z21, r, g, b, a, 0f, 1f, light, n21.x, n21.y, n21.z);
                vertex(consumer, posMatrix, x22, yy2, z22, r, g, b, a, 1f, 1f, light, n22.x, n22.y, n22.z);
                vertex(consumer, posMatrix, x12, yy1, z12, r, g, b, a, 1f, 0f, light, n12.x, n12.y, n12.z);
            }
        }
    }

    private void renderEyeCross(MatrixStack matrices, VertexConsumer vc, Vec3d center, float radius, float rotationDeg, int light) {
        float glowR = 0.08f;
        float glowG = 0.28f;
        float glowB = 0.92f;
        float s = radius * 0.22f;
        float rot = (float) Math.toRadians(rotationDeg);
        Vec3d right = new Vec3d(Math.cos(rot) * s, 0, Math.sin(rot) * s);
        Vec3d up = new Vec3d(0, s, 0);
        drawQuad(matrices, vc, center.add(0, 0, radius * 0.02), right, up, (int) (glowR * 255), (int) (glowG * 255), (int) (glowB * 255), 225, light);
        Vec3d right2 = new Vec3d(Math.cos(rot + Math.PI * 0.5) * s, 0, Math.sin(rot + Math.PI * 0.5) * s);
        drawQuad(matrices, vc, center.add(0, 0, radius * 0.02), right2, up, (int) (glowR * 255), (int) (glowG * 255), (int) (glowB * 255), 225, light);
    }

    private void renderTube(DaddyLongLegsEntity entity, MatrixStack matrices, VertexConsumer vc, List<Vec3d> worldPath, float baseRadius, int light) {
        List<Vec3d> localPath = new ArrayList<>(worldPath.size());
        Vec3d base = entity.getPos();
        for (Vec3d p : worldPath) {
            localPath.add(p.subtract(base));
        }

        Vector3f[][] rings = new Vector3f[localPath.size()][TUBE_SIDES];
        for (int i = 0; i < localPath.size(); i++) {
            Vec3d pos = localPath.get(i);
            Vec3d dir;
            if (i == 0) {
                dir = localPath.get(1).subtract(localPath.get(0));
            } else if (i == localPath.size() - 1) {
                dir = localPath.get(i).subtract(localPath.get(i - 1));
            } else {
                dir = localPath.get(i + 1).subtract(localPath.get(i - 1));
            }
            if (dir.lengthSquared() < 1.0e-6) {
                dir = new Vec3d(0, 1, 0);
            } else {
                dir = dir.normalize();
            }

            Vec3d p1 = Math.abs(dir.y) < 0.95 ? dir.crossProduct(new Vec3d(0, 1, 0)).normalize() : dir.crossProduct(new Vec3d(1, 0, 0)).normalize();
            Vec3d p2 = dir.crossProduct(p1).normalize();

            float taper = 1f - (i / (float) Math.max(1, localPath.size() - 1)) * 0.72f;
            float radius = Math.max(0.014f, baseRadius * taper);
            for (int s = 0; s < TUBE_SIDES; s++) {
                float ang = (float) (Math.PI * 2.0 * s / TUBE_SIDES);
                float c = (float) Math.cos(ang);
                float sn = (float) Math.sin(ang);
                Vec3d v = pos.add(p1.multiply(c * radius)).add(p2.multiply(sn * radius));
                rings[i][s] = new Vector3f((float) v.x, (float) v.y, (float) v.z);
            }
        }

        Matrix4f mat = matrices.peek().getPositionMatrix();
        for (int i = 0; i < rings.length - 1; i++) {
            float shade = 0.7f + (1f - i / (float) Math.max(1, rings.length - 1)) * 0.3f;
            int r = (int) (16 * shade);
            int g = (int) (18 * shade);
            int b = (int) (24 * shade);
            for (int s = 0; s < TUBE_SIDES; s++) {
                int n = (s + 1) % TUBE_SIDES;
                Vector3f a = rings[i][s];
                Vector3f b0 = rings[i][n];
                Vector3f c = rings[i + 1][n];
                Vector3f d = rings[i + 1][s];
                emitQuad(vc, mat, a, b0, c, d, r, g, b, 240, light);
            }
        }
    }

    private void drawQuad(MatrixStack matrices, VertexConsumer vc, Vec3d center, Vec3d right, Vec3d up, int r, int g, int b, int a, int light) {
        Vec3d bl = center.subtract(right).subtract(up);
        Vec3d br = center.add(right).subtract(up);
        Vec3d tr = center.add(right).add(up);
        Vec3d tl = center.subtract(right).add(up);

        Matrix4f mat = matrices.peek().getPositionMatrix();
        vc.vertex(mat, (float) bl.x, (float) bl.y, (float) bl.z).color(r, g, b, a).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 1, 0);
        vc.vertex(mat, (float) br.x, (float) br.y, (float) br.z).color(r, g, b, a).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 1, 0);
        vc.vertex(mat, (float) tr.x, (float) tr.y, (float) tr.z).color(r, g, b, a).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 1, 0);
        vc.vertex(mat, (float) tl.x, (float) tl.y, (float) tl.z).color(r, g, b, a).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 1, 0);
    }

    private void vertex(VertexConsumer vc, Matrix4f posMat,
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

    private void emitQuad(VertexConsumer vc, Matrix4f mat, Vector3f a, Vector3f b, Vector3f c, Vector3f d, int r, int g, int bl, int alpha, int light) {
        vc.vertex(mat, a.x(), a.y(), a.z()).color(r, g, bl, alpha).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 1, 0);
        vc.vertex(mat, b.x(), b.y(), b.z()).color(r, g, bl, alpha).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 1, 0);
        vc.vertex(mat, c.x(), c.y(), c.z()).color(r, g, bl, alpha).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 1, 0);
        vc.vertex(mat, d.x(), d.y(), d.z()).color(r, g, bl, alpha).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 1, 0);
    }
}
