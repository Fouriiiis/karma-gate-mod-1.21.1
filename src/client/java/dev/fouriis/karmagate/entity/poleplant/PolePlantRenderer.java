package dev.fouriis.karmagate.entity.poleplant;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** 3D version of the isolated PoleMimicGraphics renderer. */
public final class PolePlantRenderer extends EntityRenderer<PolePlantEntity> {
    private static final double SOURCE_UNIT = 1.0 / 20.0;
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    private static final FAtlasElement.Vec2 ELEMENT_UV_BOTTOM_LEFT = new FAtlasElement.Vec2(0.0f, 1.0f);
    private static final FAtlasElement.Vec2 ELEMENT_UV_BOTTOM_RIGHT = new FAtlasElement.Vec2(1.0f, 1.0f);
    private static final FAtlasElement.Vec2 ELEMENT_UV_TOP_RIGHT = new FAtlasElement.Vec2(1.0f, 0.0f);
    private static final FAtlasElement.Vec2 ELEMENT_UV_TOP_LEFT = new FAtlasElement.Vec2(0.0f, 0.0f);

    private static FAtlasElement white;
    private static FAtlasElement scaleA3;
    private static FAtlasElement scaleA0;
    private static FAtlasElement scaleB3;
    private static FAtlasElement scaleB0;

    private final Map<UUID, LeafVisualState> visualStates = new HashMap<>();

    public PolePlantRenderer(EntityRendererFactory.Context context) {
        super(context);
        shadowRadius = 0.0f;
    }

    @Override
    public void render(PolePlantEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int light) {
        resolveAtlasElements();
        if (white == null || scaleA3 == null || scaleA0 == null
                || scaleB3 == null || scaleB0 == null) return;

        List<Vec3d> worldPath = entity.getClientStemPositions(tickDelta);
        if (worldPath.size() < 2) return;
        Vec3d renderOrigin = entity.getLerpedPos(tickDelta);
        List<Vec3d> path = new ArrayList<>(worldPath.size());
        for (Vec3d position : worldPath) path.add(position.subtract(renderOrigin));
        int leafPairs = Math.max(2, (int) (entity.getPlantHeight() / (8.0 * SOURCE_UNIT)));
        path = resamplePath(path, Math.max(2, leafPairs * 2));

        LeafVisualState visual = visualStates.computeIfAbsent(entity.getUuid(),
                uuid -> new LeafVisualState(leafPairs, uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits()));
        if (visual.pairs != leafPairs) {
            visual = new LeafVisualState(leafPairs,
                    entity.getUuid().getMostSignificantBits() ^ entity.getUuid().getLeastSignificantBits());
            visualStates.put(entity.getUuid(), visual);
        }
        visual.advance(entity, path);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float visualLook = MathHelper.lerp(tickDelta, visual.lastLook, visual.look);
        int[] bodyColor = mixColor(7, 8, 9, 64, 79, 56, visualLook);
        renderStem(path, visual, tickDelta, bodyColor, matrix, consumers, light);
        renderLeaves(entity, path, visual, tickDelta, bodyColor, matrix, consumers, light);
    }

    private static void renderStem(List<Vec3d> path, LeafVisualState visual, float tickDelta,
                                   int[] color, Matrix4f matrix,
                                   VertexConsumerProvider consumers, int light) {
        VertexConsumer vertices = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(white.textureIdentifier));
        int count = path.size();
        Vec3d[] side = new Vec3d[count];
        Vec3d[] depth = new Vec3d[count];
        double[] width = new double[count];

        Vec3d previousSide = new Vec3d(1.0, 0.0, 0.0);
        for (int i = 0; i < count; i++) {
            float f = i / (float) Math.max(1, count - 1);
            Vec3d tangent = pathTangent(path, i);
            Vec3d transported = previousSide.subtract(tangent.multiply(previousSide.dotProduct(tangent)));
            if (transported.lengthSquared() < 0.00001) transported = makePerpendicular(tangent);
            side[i] = transported.normalize();
            depth[i] = safeNormalize(tangent.crossProduct(side[i]), new Vec3d(0.0, 0.0, 1.0));
            previousSide = side[i];

            double openWidth = (MathHelper.lerp(f, 4.0f, 1.5f)
                    + MathHelper.lerp(f, 1.4f, 0.75f)) * 0.5 * SOURCE_UNIT;
            float stemLook = visual.stemLook(f, tickDelta);
            width[i] = MathHelper.lerp((float) Math.pow(stemLook, 0.75), (float) openWidth, 0.1f);
        }

        for (int i = 0; i < count - 1; i++) {
            Vec3d[] a = square(path.get(i), side[i], depth[i], width[i]);
            Vec3d[] b = square(path.get(i + 1), side[i + 1], depth[i + 1], width[i + 1]);
            emitStemFace(vertices, matrix, a[0], a[1], b[1], b[0], depth[i].negate(), color, 1.0f, light);
            emitStemFace(vertices, matrix, a[1], a[2], b[2], b[1], side[i], color, 0.84f, light);
            emitStemFace(vertices, matrix, a[2], a[3], b[3], b[2], depth[i], color, 0.68f, light);
            emitStemFace(vertices, matrix, a[3], a[0], b[0], b[3], side[i].negate(), color, 0.76f, light);
            if (i == count - 2) {
                emitStemFace(vertices, matrix, b[0], b[1], b[2], b[3],
                        pathTangent(path, count - 1), color, 1.0f, light);
            }
        }
    }

    private static void renderLeaves(PolePlantEntity entity, List<Vec3d> path,
                                     LeafVisualState visual, float tickDelta,
                                     int[] faceColor, Matrix4f matrix,
                                     VertexConsumerProvider consumers, int light) {
        int pairs = Math.max(2, (int) (entity.getPlantHeight() / (8.0 * SOURCE_UNIT)));
        float scale = leafScale(pairs);

        for (int pair = 0; pair < pairs; pair++) {
            float f = pair / (float) Math.max(1, pairs - 1);
            Vec3d base = positionAlong(path, f);
            Vec3d tangent = tangentAlong(path, f);

            for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
                double angle = pair * GOLDEN_ANGLE + (sideIndex == 0 ? 0.0 : Math.PI);
                //double angle = sideIndex == 0 ? 0.0 : Math.PI;
                Vec3d radial = radial(tangent, angle);
                LeafState leaf = visual.leaves[pair][sideIndex];
                Vec3d top = leaf.lastPosition.lerp(leaf.position, tickDelta);
                Vec3d bottom = base.add(radial.multiply(SOURCE_UNIT));
                Vec3d longAxis = safeNormalize(top.subtract(bottom), tangent);
                Vec3d baseNormal = safeNormalize(tangent.crossProduct(radial), makePerpendicular(tangent));
                Vec3d widthAxis = safeNormalize(baseNormal.crossProduct(longAxis), radial);

                FAtlasElement baseSprite = f < 0.75f ? scaleA3 : scaleA0;
                float leafWidth = MathHelper.lerp((float) Math.sqrt(f), 1.0f, 0.5f) * scale;
                float leafMimic = MathHelper.lerp(tickDelta, leaf.lastMimic, leaf.mimic);
                float unfold = (float) Math.sqrt(inverseLerp(1.0, 0.6, leafMimic));
                float flip = -MathHelper.lerp(tickDelta, leaf.lastFlip, leaf.flip);

                if (f >= 0.75f) {
                    flip = Math.abs(flip);
                }

                float signedWidth =
                        -flip * leafWidth * unfold * (float) SOURCE_UNIT;

                renderLeafSprite(
                        consumers,
                        matrix,
                        baseSprite,
                        bottom,
                        top,
                        widthAxis,
                        signedWidth,
                        faceColor[0],
                        faceColor[1],
                        faceColor[2],
                        255,
                        false,
                        light
                );

                if (pair < pairs * 0.6f) {
                    FAtlasElement detailSprite = f < 0.75f ? scaleB3 : scaleB0;
                    int decorated = MathHelper.clamp((int) (pairs * 0.6f), 1, 80);
                    float detailMix = (float) Math.pow(inverseLerp(decorated / 2.0, decorated, pair), 0.6);
                    int[] detailColor = mixColor(255, 0, 0,
                            faceColor[0], faceColor[1], faceColor[2], detailMix);
                    float alpha = (float) Math.pow(1.0f - visual.stemLook(f, tickDelta), 0.2f)
                            * (flip > 0.0f ? 1.0f / 3.0f : 1.0f);
                    renderLeafSprite(consumers, matrix, detailSprite, bottom, top, widthAxis,
                            signedWidth,
                            detailColor[0], detailColor[1], detailColor[2],
                            MathHelper.clamp((int) (alpha * 255.0f), 0, 255), true, light);
                }
            }
        }
    }

    /** Client-local port of PoleMimicGraphics.leaves and leavesMimic. */
    private static final class LeafVisualState {
        private final int pairs;
        private final LeafState[][] leaves;
        private final Random random;
        private int lastAge = -1;
        private float look;
        private float lastLook;
        private float flipPoint = 1.0f;
        private boolean leavesFlip;

        private LeafVisualState(int pairs, long seed) {
            this.pairs = pairs;
            this.random = new Random(seed);
            this.leaves = new LeafState[pairs][2];
            for (int pair = 0; pair < pairs; pair++) {
                leaves[pair][0] = new LeafState();
                leaves[pair][1] = new LeafState();
            }
        }

        private void advance(PolePlantEntity entity, List<Vec3d> path) {
            if (lastAge < 0) {
                initialize(entity, path);
                lastAge = entity.age;
                return;
            }
            if (lastAge == entity.age) return;

            int elapsedTicks = MathHelper.clamp(entity.age - lastAge, 1, 10);
            lastAge = entity.age;
            lastLook = look;
            for (LeafState[] pair : leaves) {
                for (LeafState leaf : pair) {
                    leaf.lastPosition = leaf.position;
                    leaf.lastMimic = leaf.mimic;
                    leaf.lastFlip = leaf.flip;
                }
            }
            for (int i = 0; i < elapsedTicks * 2; i++) updateStep(entity, path);
        }

        private void initialize(PolePlantEntity entity, List<Vec3d> path) {
            boolean emerging = entity.age < 40 && entity.getMimic() < 0.5f;
            look = lastLook = emerging ? 0.0f : 1.0f;
            float scale = leafScale(pairs);
            for (int pair = 0; pair < pairs; pair++) {
                float f = pair / (float) Math.max(1, pairs - 1);
                double length = leafLength(pair, pairs, scale);
                Vec3d initial = emerging ? path.getFirst() : positionAlong(path,
                        Math.min(1.0, f + length / Math.max(1.0, entity.getPlantHeight())));
                for (int side = 0; side < 2; side++) {
                    LeafState leaf = leaves[pair][side];
                    leaf.position = leaf.lastPosition = initial;
                    leaf.velocity = Vec3d.ZERO;
                    leaf.mimic = leaf.lastMimic = leaf.targetMimic = emerging ? 0.0f : 1.0f;
                    leaf.flip = leaf.lastFlip = emerging ? -1.0f : 1.0f;
                }
            }
        }

        private void updateStep(PolePlantEntity entity, List<Vec3d> path) {
            boolean allOpen = true;
            float scale = leafScale(pairs);
            for (int pair = 0; pair < pairs; pair++) {
                float f = pair / (float) Math.max(1, pairs - 1);
                Vec3d base = positionAlong(path, f);
                Vec3d tangent = tangentAlong(path, f);
                double length = leafLength(pair, pairs, scale);
                Vec3d closed = positionAlong(path,
                        Math.min(1.0, f + length / Math.max(1.0, entity.getPlantHeight())));

                for (int side = 0; side < 2; side++) {
                    LeafState leaf = leaves[pair][side];
                    leaf.mimic = MathHelper.lerp(0.1f, leaf.mimic, leaf.targetMimic);
                    if (random.nextFloat() < 0.1f) {
                        leaf.targetMimic = lerpAndTick(leaf.targetMimic, look,
                                random.nextFloat() * 0.1f, random.nextFloat() / 40.0f);
                    }
                    if (allOpen && leaf.targetMimic > 0.5f) allOpen = false;

                    double angle = pair * GOLDEN_ANGLE + (side == 0 ? 0.0 : Math.PI);
                    //double angle = side == 0 ? 0.0 : Math.PI;
                    Vec3d radial = radial(tangent, angle);
                    Vec3d openDirection = safeNormalize(tangent.multiply(forward(f))
                            .add(radial.multiply(perpendicular(f))), radial);
                    Vec3d desired = base.add(openDirection.multiply(length)).lerp(closed, leaf.mimic);

                    leaf.position = leaf.position.add(leaf.velocity);
                    leaf.velocity = leaf.velocity.multiply(0.75 * (1.0 - Math.pow(leaf.targetMimic, 3.0)))
                            .add(0.0, -0.3 * SOURCE_UNIT, 0.0);
                    leaf.position = leaf.position.lerp(closed, Math.pow(leaf.targetMimic, 3.0));
                    if (desired.distanceTo(leaf.position) > length * 0.5) {
                        desired = base.add(direction(base, desired).multiply(length * 0.5));
                    }

                    // PoleMimicGraphics pins every leaf associated with a
                    // stickChunk to the caught creature's body surface.
                    Entity snagTarget = entity.getClientSnagTarget(f);
                    if (snagTarget != null && !snagTarget.isRemoved()) {
                        leaf.position = snagSurfacePoint(entity, snagTarget, leaf.position);
                        leaf.velocity = Vec3d.ZERO;
                    }
                    leaf.velocity = leaf.velocity.add(desired.subtract(leaf.position).multiply(0.25));

                    if (random.nextFloat() < 1.0f / 3.0f) {
                        float targetFlip = ((f < flipPoint) == leavesFlip) ? 1.0f : -1.0f;
                        leaf.flip = lerpAndTick(leaf.flip, targetFlip,
                                0.1f * (1.0f - leaf.targetMimic), 0.25f);
                    }
                }
            }

            if ((flipPoint > 0.0f || leavesFlip) && look < 0.6f && allOpen) {
                flipPoint += 1.0f / (3.5f * MathHelper.lerp(0.6f, pairs, 10.0f));
            } else if ((flipPoint > 0.0f || !leavesFlip) && look > 0.4f) {
                flipPoint += 1.0f / (10.0f * MathHelper.lerp(0.3f, pairs, 10.0f));
            }
            if (flipPoint >= 1.0f) {
                flipPoint = 0.0f;
                leavesFlip = !leavesFlip;
            }
            look = lerpAndTick(look, entity.getMimic(), 0.03f, 1.0f / 30.0f);
        }

        private float stemLook(float stemPosition, float tickDelta) {
            int pair = MathHelper.clamp((int) (stemPosition * (pairs - 1)), 0, pairs - 1);
            float left = MathHelper.lerp(tickDelta, leaves[pair][0].lastMimic, leaves[pair][0].mimic);
            float right = MathHelper.lerp(tickDelta, leaves[pair][1].lastMimic, leaves[pair][1].mimic);
            return 1.0f - (1.0f - left) * (1.0f - right);
        }
    }

    private static final class LeafState {
        private Vec3d position = Vec3d.ZERO;
        private Vec3d lastPosition = Vec3d.ZERO;
        private Vec3d velocity = Vec3d.ZERO;
        private float mimic;
        private float lastMimic;
        private float targetMimic;
        private float flip = 1.0f;
        private float lastFlip = 1.0f;
    }

    /** Flat Futile sprite with the source renderer's anchorX=.5, anchorY=0 layout. */
    private static void renderLeafSprite(VertexConsumerProvider consumers, Matrix4f matrix,
                                         FAtlasElement sprite, Vec3d bottom, Vec3d top,
                                         Vec3d widthAxis, float signedPixelScale,
                                         int r, int g, int b, int alpha,
                                         boolean translucent, int light) {
        if (sprite == null || sprite.textureIdentifier == null
                || alpha <= 0 || Math.abs(signedPixelScale) < 0.00001f) return;
        Vec3d longAxis = safeNormalize(top.subtract(bottom), new Vec3d(0.0, 1.0, 0.0));
        Vec3d xAxis = safeNormalize(widthAxis, new Vec3d(1.0, 0.0, 0.0));
        Vec3d normal = safeNormalize(longAxis.crossProduct(
                xAxis.multiply(Math.signum(signedPixelScale))), new Vec3d(0.0, 0.0, 1.0));

        float sourceWidth = positive(sprite.sourcePixelSize.x, sprite.sourceRect.width);
        float sourceHeight = positive(sprite.sourcePixelSize.y, sprite.sourceRect.height);
        float trimmedWidth = positive(sprite.sourceRect.width, sourceWidth);
        float trimmedHeight = positive(sprite.sourceRect.height, sourceHeight);
        double left = -sourceWidth * 0.5 + sprite.sourceRect.x;
        double right = left + trimmedWidth;
        double low = sourceHeight - sprite.sourceRect.y - trimmedHeight;
        double high = low + trimmedHeight;
        double yScale = top.distanceTo(bottom) / Math.max(1.0, sourceHeight);

        Vec3d bottomLeft = bottom.add(xAxis.multiply(left * signedPixelScale))
                .add(longAxis.multiply(low * yScale));
        Vec3d bottomRight = bottom.add(xAxis.multiply(right * signedPixelScale))
                .add(longAxis.multiply(low * yScale));
        Vec3d topRight = bottom.add(xAxis.multiply(right * signedPixelScale))
                .add(longAxis.multiply(high * yScale));
        Vec3d topLeft = bottom.add(xAxis.multiply(left * signedPixelScale))
                .add(longAxis.multiply(high * yScale));

        VertexConsumer vertices = consumers.getBuffer(translucent
                ? RenderLayer.getEntityTranslucent(sprite.textureIdentifier)
                : RenderLayer.getEntityCutoutNoCull(sprite.textureIdentifier));
        // libMod registers every atlas element as its own extracted texture.
        // The element's atlas-space UVs therefore point at the wrong region;
        // extracted element textures always use the complete 0..1 UV range.
        putVertex(vertices, matrix, bottomLeft, ELEMENT_UV_BOTTOM_LEFT, r, g, b, alpha, light, normal);
        putVertex(vertices, matrix, bottomRight, ELEMENT_UV_BOTTOM_RIGHT, r, g, b, alpha, light, normal);
        putVertex(vertices, matrix, topRight, ELEMENT_UV_TOP_RIGHT, r, g, b, alpha, light, normal);
        putVertex(vertices, matrix, topLeft, ELEMENT_UV_TOP_LEFT, r, g, b, alpha, light, normal);
    }

    private static void emitStemFace(VertexConsumer vertices, Matrix4f matrix,
                                     Vec3d a, Vec3d b, Vec3d c, Vec3d d, Vec3d normal,
                                     int[] color, float shade, int light) {
        int r = MathHelper.clamp((int) (color[0] * shade), 0, 255);
        int g = MathHelper.clamp((int) (color[1] * shade), 0, 255);
        int bl = MathHelper.clamp((int) (color[2] * shade), 0, 255);
        putVertex(vertices, matrix, a, white.uvBottomLeft, r, g, bl, 255, light, normal);
        putVertex(vertices, matrix, b, white.uvBottomRight, r, g, bl, 255, light, normal);
        putVertex(vertices, matrix, c, white.uvTopRight, r, g, bl, 255, light, normal);
        putVertex(vertices, matrix, d, white.uvTopLeft, r, g, bl, 255, light, normal);
    }

    private static void putVertex(VertexConsumer vertices, Matrix4f matrix, Vec3d position,
                                  FAtlasElement.Vec2 uv, int r, int g, int b, int alpha,
                                  int light, Vec3d normal) {
        vertices.vertex(matrix, (float) position.x, (float) position.y, (float) position.z)
                .color(r, g, b, alpha)
                .texture(uv.x, uv.y)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static Vec3d[] square(Vec3d center, Vec3d side, Vec3d depth, double radius) {
        Vec3d s = side.multiply(radius);
        Vec3d d = depth.multiply(radius);
        return new Vec3d[] {
                center.subtract(s).subtract(d), center.add(s).subtract(d),
                center.add(s).add(d), center.subtract(s).add(d)
        };
    }

    private static List<Vec3d> resamplePath(List<Vec3d> input, int samples) {
        List<Vec3d> result = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            result.add(positionAlong(input, i / (double) Math.max(1, samples - 1)));
        }
        return result;
    }

    private static Vec3d pathTangent(List<Vec3d> path, int index) {
        if (index <= 0) return safeNormalize(path.get(1).subtract(path.getFirst()), new Vec3d(0, 1, 0));
        if (index >= path.size() - 1) return safeNormalize(path.getLast().subtract(path.get(index - 1)), new Vec3d(0, 1, 0));
        return safeNormalize(path.get(index + 1).subtract(path.get(index - 1)), new Vec3d(0, 1, 0));
    }

    private static Vec3d positionAlong(List<Vec3d> path, double f) {
        double x = MathHelper.clamp(f, 0.0, 1.0) * (path.size() - 1);
        int index = Math.min(path.size() - 2, (int) x);
        return path.get(index).lerp(path.get(index + 1), x - index);
    }

    private static Vec3d tangentAlong(List<Vec3d> path, double f) {
        double epsilon = 1.0 / Math.max(8.0, path.size() * 3.0);
        return safeNormalize(positionAlong(path, Math.min(1.0, f + epsilon))
                .subtract(positionAlong(path, Math.max(0.0, f - epsilon))), new Vec3d(0, 1, 0));
    }

    private static Vec3d radial(Vec3d tangent, double angle) {
        Vec3d reference = Math.abs(tangent.dotProduct(new Vec3d(0, 1, 0))) > 0.95
                ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = safeNormalize(tangent.crossProduct(reference), new Vec3d(1, 0, 0));
        Vec3d v = safeNormalize(tangent.crossProduct(u), new Vec3d(0, 0, 1));
        return u.multiply(Math.cos(angle)).add(v.multiply(Math.sin(angle)));
    }

    private static Vec3d makePerpendicular(Vec3d direction) {
        Vec3d axis = Math.abs(direction.y) < 0.95 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        return safeNormalize(direction.crossProduct(axis), new Vec3d(1, 0, 0));
    }

    private static Vec3d snagSurfacePoint(PolePlantEntity plant, Entity target, Vec3d leafPosition) {
        Box worldBox = target.getBoundingBox();
        Box box = worldBox.offset(-plant.getX(), -plant.getY(), -plant.getZ());
        Vec3d center = box.getCenter();
        Vec3d radial = leafPosition.subtract(center);
        if (radial.lengthSquared() < 1.0E-8) radial = new Vec3d(1.0, 0.0, 0.0);
        radial = radial.normalize();

        // Rain World's BodyChunk is circular. An ellipsoid fitted to the
        // Minecraft hitbox provides the equivalent surface in three dimensions.
        double halfX = Math.max(0.05, box.getLengthX() * 0.5);
        double halfY = Math.max(0.05, box.getLengthY() * 0.5);
        double halfZ = Math.max(0.05, box.getLengthZ() * 0.5);
        double denominator = radial.x * radial.x / (halfX * halfX)
                + radial.y * radial.y / (halfY * halfY)
                + radial.z * radial.z / (halfZ * halfZ);
        double radius = denominator > 1.0E-8 ? 1.0 / Math.sqrt(denominator) : halfX;
        return center.add(radial.multiply(radius));
    }

    private static double leafLength(int pair, int pairs, float scale) {
        float f = pair / (float) Math.max(1, pairs - 1);
        double sourceLength;
        if (f < 0.75f) {
            float n = (float) inverseLerp(0.0, 0.6, f);
            sourceLength = 4.0 + MathHelper.lerp(MathHelper.lerp(n, 0.8f, 0.3f),
                    1.0f - Math.pow(n, 1.2),
                    Math.sin(inverseLerp(0.0, 0.75, Math.pow(n, 0.6)) * Math.PI)) * 42.0 * scale;
        } else {
            sourceLength = 4.0 + Math.pow(inverseLerp(0.75, 1.0, f), 1.5) * 20.0 * scale;
        }
        return sourceLength * SOURCE_UNIT;
    }

    private static float leafScale(int pairs) {
        float blended = MathHelper.lerp(0.1f, pairs, 65.0f);
        return MathHelper.lerp((float) inverseLerp(5.0, 185.0, blended), 0.5f, 2.0f);
    }

    private static float perpendicular(float f) {
        float first = 0.15f + 0.85f * (float) Math.sin(
                inverseLerp(0.0, 0.75, Math.pow(f, 0.2)) * Math.PI);
        return MathHelper.lerp(f, first, (float) inverseLerp(0.6, 1.0, f));
    }

    private static float forward(float f) {
        return MathHelper.lerp((float) Math.pow(f, 0.3), -0.2f,
                1.0f - (float) Math.sin(Math.pow(f, 1.8) * Math.PI));
    }

    private static int[] mixColor(int ar, int ag, int ab, int br, int bg, int bb, float t) {
        return new int[] {
                MathHelper.clamp((int) MathHelper.lerp(t, ar, br), 0, 255),
                MathHelper.clamp((int) MathHelper.lerp(t, ag, bg), 0, 255),
                MathHelper.clamp((int) MathHelper.lerp(t, ab, bb), 0, 255)
        };
    }

    private static Vec3d safeNormalize(Vec3d value, Vec3d fallback) {
        return value.lengthSquared() < 0.0000001 ? fallback : value.normalize();
    }

    private static Vec3d direction(Vec3d from, Vec3d to) {
        return safeNormalize(to.subtract(from), Vec3d.ZERO);
    }

    private static float lerpAndTick(float value, float target, float lerp, float tick) {
        value = MathHelper.lerp(lerp, value, target);
        return value < target ? Math.min(target, value + tick) : Math.max(target, value - tick);
    }

    private static double inverseLerp(double from, double to, double value) {
        if (from == to) return 0.0;
        return MathHelper.clamp((value - from) / (to - from), 0.0, 1.0);
    }

    private static float positive(float preferred, float fallback) {
        return preferred > 0.0f ? preferred : Math.max(1.0f, fallback);
    }

    private static void resolveAtlasElements() {
        if (white != null && scaleA3 != null && scaleA0 != null) return;
        try {
            var atlas = LibrainworldmcClient.getAtlasManager();
            white = atlas.getElementWithName("Futile_White");
            scaleA3 = atlas.getElementWithName("LizardScaleA3");
            scaleA0 = atlas.getElementWithName("LizardScaleA0");
            scaleB3 = atlas.getElementWithName("LizardScaleB3");
            scaleB0 = atlas.getElementWithName("LizardScaleB0");
        } catch (IllegalStateException ignored) {
            // Atlas initialization can briefly lag behind a resource reload.
        }
    }

    @Override
    public boolean shouldRender(PolePlantEntity entity, Frustum frustum, double x, double y, double z) {
        return frustum.isVisible(entity.getBoundingBox().expand(3.0, entity.getPlantHeight() + 3.0, 3.0));
    }

    @Override
    public Identifier getTexture(PolePlantEntity entity) {
        return white != null && white.textureIdentifier != null
                ? white.textureIdentifier : Identifier.ofVanilla("textures/misc/white.png");
    }
}
