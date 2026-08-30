package dev.fouriis.karmagate.entity.monsterkelp;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.fluid.FluidState;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Three-dimensional stem plus the fifty simulated Dangler leaves from TentaclePlantGraphics. */
public final class MonsterKelpRenderer extends EntityRenderer<MonsterKelpEntity> {
    private static final int LEAF_COUNT = 50;
    private static final double SOURCE_UNIT = 1.0 / 20.0;
    private static final double COLLISION_EPSILON = 1.0E-5;
    private static final double LEAF_SURFACE_FRICTION = 0.72;
    private static FAtlasElement white;

    private final Map<UUID, KelpVisualState> visualStates = new HashMap<>();

    public MonsterKelpRenderer(EntityRendererFactory.Context context) {
        super(context);
        shadowRadius = 0.0f;
    }

    @Override
    public void render(MonsterKelpEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int light) {
        resolveWhite();
        if (white == null || white.textureIdentifier == null) return;

        List<Vec3d> worldPath = entity.getClientStemPositions(tickDelta);
        if (worldPath.size() < 2) return;
        Vec3d renderOrigin = entity.getLerpedPos(tickDelta);
        List<Vec3d> path = new ArrayList<>(worldPath.size());
        for (Vec3d position : worldPath) path.add(position.subtract(renderOrigin));
        path = resamplePath(path, 49);

        KelpVisualState visual = visualStates.computeIfAbsent(entity.getUuid(), uuid ->
                new KelpVisualState(uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits()));
        visual.advance(entity, path);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        renderStem(path, matrix, consumers, light);
        Vec3d cameraLocal = MinecraftClient.getInstance().gameRenderer.getCamera().getPos().subtract(renderOrigin);
        renderLeaves(entity, path, visual, tickDelta, cameraLocal, matrix, consumers, light);
    }

    /** A transported square section makes the formerly 2D rope genuinely volumetric. */
    private static void renderStem(List<Vec3d> path, Matrix4f matrix,
                                   VertexConsumerProvider consumers, int light) {
        VertexConsumer vertices = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(white.textureIdentifier));
        int count = path.size();
        Vec3d[] side = new Vec3d[count];
        Vec3d[] depth = new Vec3d[count];
        double[] radius = new double[count];
        Vec3d previousSide = new Vec3d(1.0, 0.0, 0.0);

        for (int i = 0; i < count; i++) {
            float f = i / (float) Math.max(1, count - 1);
            Vec3d tangent = pathTangent(path, i);
            Vec3d transported = previousSide.subtract(tangent.multiply(previousSide.dotProduct(tangent)));
            if (transported.lengthSquared() < 1.0E-6) transported = perpendicular(tangent);
            side[i] = transported.normalize();
            depth[i] = safeNormalize(tangent.crossProduct(side[i]), new Vec3d(0.0, 0.0, 1.0));
            previousSide = side[i];
            // TentaclePlant.Rad followed by RopeGraphic's 1.7x + 2 pixel width.
            radius[i] = (plantRadiusPixels(f) * 1.7 + 2.0) * 0.5 * SOURCE_UNIT;
        }

        int[] body = {5, 7, 8};
        for (int i = 0; i < count - 1; i++) {
            Vec3d[] a = square(path.get(i), side[i], depth[i], radius[i]);
            Vec3d[] b = square(path.get(i + 1), side[i + 1], depth[i + 1], radius[i + 1]);
            emitFace(vertices, matrix, a[0], a[1], b[1], b[0], depth[i].negate(), body, 1.0f, 255, light);
            emitFace(vertices, matrix, a[1], a[2], b[2], b[1], side[i], body, 0.80f, 255, light);
            emitFace(vertices, matrix, a[2], a[3], b[3], b[2], depth[i], body, 0.62f, 255, light);
            emitFace(vertices, matrix, a[3], a[0], b[0], b[3], side[i].negate(), body, 0.72f, 255, light);
            if (i == count - 2) {
                emitFace(vertices, matrix, b[0], b[1], b[2], b[3], pathTangent(path, count - 1),
                        body, 0.9f, 255, light);
            }
        }
    }

    private static void renderLeaves(MonsterKelpEntity entity, List<Vec3d> path,
                                     KelpVisualState visual, float tickDelta, Vec3d cameraLocal,
                                     Matrix4f matrix, VertexConsumerProvider consumers, int light) {
        // Dangler.InitSprite creates one opaque TriangleMesh.MakeLongMesh. Its
        // alpha is shader seed data, not blend opacity, so use a cutout layer.
        VertexConsumer vertices = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(white.textureIdentifier));
        for (int leafIndex = 0; leafIndex < LEAF_COUNT; leafIndex++) {
            DanglerSpec spec = visual.specs[leafIndex];
            Vec3d anchor = visual.connection(path, spec);
            int[] growthColor = leafColor(entity.isOceanKelp(), spec.f, spec.colorNoise);
            int[] bodyColor = darkLeafColor(growthColor, spec.f);

            // MakeLongMesh is a single connected strip. Build shared joint
            // vertices so adjacent Dangler segments cannot expose sky cracks.
            Vec3d[] centers = new Vec3d[spec.segmentCount + 1];
            double[] widths = new double[spec.segmentCount + 1];
            Vec3d[] widthAxes = new Vec3d[spec.segmentCount + 1];
            centers[0] = anchor;
            widths[0] = visual.stretchedWidths[leafIndex][0];
            for (int segment = 0; segment < spec.segmentCount; segment++) {
                centers[segment + 1] = visual.lastPositions[leafIndex][segment]
                        .lerp(visual.positions[leafIndex][segment], tickDelta);
                widths[segment + 1] = visual.stretchedWidths[leafIndex][segment];
            }
            for (int joint = 0; joint < centers.length; joint++) {
                Vec3d before = centers[Math.max(0, joint - 1)];
                Vec3d after = centers[Math.min(centers.length - 1, joint + 1)];
                Vec3d tangent = safeNormalize(after.subtract(before), new Vec3d(0.0, -1.0, 0.0));
                Vec3d view = safeNormalize(cameraLocal.subtract(centers[joint]), new Vec3d(0.0, 0.0, 1.0));
                widthAxes[joint] = safeNormalize(tangent.crossProduct(view), perpendicular(tangent));
            }

            for (int segment = 0; segment < spec.segmentCount; segment++) {
                Vec3d low = centers[segment];
                Vec3d high = centers[segment + 1];
                Vec3d lowWidth = widthAxes[segment].multiply(widths[segment]);
                Vec3d highWidth = widthAxes[segment + 1].multiply(widths[segment + 1]);
                Vec3d a = low.subtract(lowWidth);
                Vec3d b = low.add(lowWidth);
                Vec3d c = high.add(highWidth);
                Vec3d d = high.subtract(highWidth);
                Vec3d axis = safeNormalize(high.subtract(low), new Vec3d(0.0, -1.0, 0.0));
                Vec3d view = safeNormalize(cameraLocal.subtract(low.lerp(high, 0.5)), new Vec3d(0.0, 0.0, 1.0));
                Vec3d normal = safeNormalize(axis.crossProduct(widthAxes[segment]), view);
                emitFace(vertices, matrix, a, b, c, d, normal, bodyColor, 1.0f, 255, light);

                // The TentaclePlant shader keeps the leaf interior dark and
                // exposes palette colour along both ragged outside edges.
                double lowEdge = edgeBand(spec, segment);
                double highEdge = edgeBand(spec, segment + 1);
                Vec3d lift = normal.multiply(0.0015);

                Vec3d lowLeftOuter = low.subtract(lowWidth).add(lift);
                Vec3d lowLeftInner = low.subtract(lowWidth.multiply(1.0 - lowEdge)).add(lift);
                Vec3d highLeftInner = high.subtract(highWidth.multiply(1.0 - highEdge)).add(lift);
                Vec3d highLeftOuter = high.subtract(highWidth).add(lift);
                emitFace(vertices, matrix,
                        lowLeftOuter, lowLeftInner, highLeftInner, highLeftOuter,
                        normal, growthColor, edgeShade(spec, segment, false), 255, light);

                Vec3d lowRightInner = low.add(lowWidth.multiply(1.0 - lowEdge)).add(lift);
                Vec3d lowRightOuter = low.add(lowWidth).add(lift);
                Vec3d highRightOuter = high.add(highWidth).add(lift);
                Vec3d highRightInner = high.add(highWidth.multiply(1.0 - highEdge)).add(lift);
                emitFace(vertices, matrix,
                        lowRightInner, lowRightOuter, highRightOuter, highRightInner,
                        normal, growthColor, edgeShade(spec, segment, true), 255, light);
            }
        }
    }

    private static final class KelpVisualState {
        private final DanglerSpec[] specs = new DanglerSpec[LEAF_COUNT];
        private final Vec3d[][] positions = new Vec3d[LEAF_COUNT][];
        private final Vec3d[][] lastPositions = new Vec3d[LEAF_COUNT][];
        private final Vec3d[][] velocities = new Vec3d[LEAF_COUNT][];
        private final double[][] stretchedWidths = new double[LEAF_COUNT][];
        private final Random random;
        private int lastAge = -1;

        private KelpVisualState(long seed) {
            random = new Random(seed);
            for (int i = 0; i < LEAF_COUNT; i++) {
                int segments = i < 15 ? 4 + random.nextInt(8) : nestedSegmentCount(random);
                float f = (float) Math.pow(random.nextFloat(), 0.6);
                float side = MathHelper.lerp(random.nextFloat(), -1.0f, 1.0f);
                float angle = random.nextFloat() * MathHelper.TAU;
                float baseWidth = MathHelper.lerp(0.5f, (float) plantRadiusPixels(f), 4.0f)
                        * MathHelper.lerp(random.nextFloat(), 0.5f, 1.5f);
                float lengthScale = MathHelper.lerp(random.nextFloat(), 0.5f, 1.5f);
                specs[i] = new DanglerSpec(f, side, angle, segments, baseWidth,
                        lengthScale, random.nextFloat());
                positions[i] = new Vec3d[segments];
                lastPositions[i] = new Vec3d[segments];
                velocities[i] = new Vec3d[segments];
                stretchedWidths[i] = new double[segments];
            }
        }

        private void advance(MonsterKelpEntity entity, List<Vec3d> path) {
            if (lastAge < 0) {
                reset(path);
                lastAge = entity.age;
                return;
            }
            if (lastAge == entity.age) return;
            int ticks = MathHelper.clamp(entity.age - lastAge, 1, 5);
            lastAge = entity.age;
            for (int i = 0; i < LEAF_COUNT; i++) {
                for (int j = 0; j < specs[i].segmentCount; j++) {
                    lastPositions[i][j] = positions[i][j];
                }
            }
            for (int step = 0; step < ticks * 2; step++) updateStep(entity, path);
        }

        private void reset(List<Vec3d> path) {
            for (int i = 0; i < LEAF_COUNT; i++) {
                DanglerSpec spec = specs[i];
                Vec3d point = connection(path, spec);
                for (int j = 0; j < spec.segmentCount; j++) {
                    point = point.add(0.0, -connectionLength(spec, j), 0.0);
                    positions[i][j] = lastPositions[i][j] = point;
                    velocities[i][j] = Vec3d.ZERO;
                    stretchedWidths[i][j] = widthBlocks(spec, j);
                }
            }
        }

        /** Direct 3D counterpart of Dangler.Update and TentaclePlantGraphics.Update. */
        private void updateStep(MonsterKelpEntity entity, List<Vec3d> path) {
            float attack = entity.getAttackProgress();
            for (int i = 0; i < LEAF_COUNT; i++) {
                DanglerSpec spec = specs[i];
                Vec3d anchor = connection(path, spec);
                Vec3d tubeDirection = tangentAlong(path, spec.f);

                for (int j = 0; j < spec.segmentCount; j++) {
                    Vec3d oldPosition = positions[i][j];
                    double segmentRadius = Math.max(0.025, widthBlocks(spec, j));
                    LeafMotion motion = moveLeafWithCollision(entity, oldPosition,
                            velocities[i][j], segmentRadius);
                    positions[i][j] = motion.position;
                    velocities[i][j] = motion.velocity;
                    boolean submerged = isLeafSubmerged(entity, positions[i][j]);
                    velocities[i][j] = velocities[i][j]
                            .multiply(submerged ? 0.8 : 0.98)
                            .add(0.0, submerged ? 0.01 : -0.045, 0.0);

                    Vec3d connection = j == 0 ? anchor : positions[i][j - 1];
                    double desiredLength = connectionLength(spec, j);
                    Vec3d delta = positions[i][j].subtract(connection);
                    double length = delta.length();
                    if (length > 1.0E-6) {
                        double rawWidth = widthBlocks(spec, j);
                        double stretch = MathHelper.clamp(MathHelper.lerp(0.5,
                                Math.sqrt(desiredLength / length), 1.0), 0.2, 1.8);
                        stretchedWidths[i][j] = rawWidth * stretch;
                        Vec3d correction = delta.multiply((length - desiredLength) / length * 0.85);
                        if (j == 0) {
                            positions[i][j] = positions[i][j].subtract(correction);
                            velocities[i][j] = velocities[i][j].subtract(correction);
                        } else {
                            double currentShare = 0.7;
                            positions[i][j] = positions[i][j].subtract(correction.multiply(currentShare));
                            velocities[i][j] = velocities[i][j].subtract(correction.multiply(currentShare));
                            positions[i][j - 1] = positions[i][j - 1].add(correction.multiply(1.0 - currentShare));
                            velocities[i][j - 1] = velocities[i][j - 1].add(correction.multiply(1.0 - currentShare));
                        }
                    }
                    // Avoid catastrophic client-side explosions after a long render pause.
                    if (!finite(positions[i][j]) || positions[i][j].distanceTo(anchor) > 12.0) {
                        positions[i][j] = oldPosition;
                        velocities[i][j] = Vec3d.ZERO;
                    }
                }

                // BodyPart.PushOutOfTerrain runs after every DanglerSegment
                // constraint. Two passes also keep the long rendered links
                // from cutting across a block between their endpoints.
                for (int pass = 0; pass < 2; pass++) {
                    Vec3d connection = anchor;
                    for (int j = 0; j < spec.segmentCount; j++) {
                        double radius = Math.max(0.025, stretchedWidths[i][j]);
                        Vec3d resolved = resolveLeafPenetration(entity, positions[i][j], radius);
                        Vec3d correction = resolved.subtract(positions[i][j]);
                        positions[i][j] = resolved;
                        if (correction.lengthSquared() > COLLISION_EPSILON * COLLISION_EPSILON) {
                            velocities[i][j] = removeInwardVelocity(
                                    velocities[i][j], correction.normalize());
                        }
                        Vec3d clipped = clipLeafLinkToTerrain(entity, connection,
                                positions[i][j], radius);
                        if (clipped.squaredDistanceTo(positions[i][j])
                                > COLLISION_EPSILON * COLLISION_EPSILON) {
                            Vec3d linkCorrection = clipped.subtract(positions[i][j]);
                            positions[i][j] = clipped;
                            velocities[i][j] = removeInwardVelocity(
                                    velocities[i][j], linkCorrection.normalize());
                        }
                        connection = positions[i][j];
                    }
                }

                Vec3d radialForce = radial(tubeDirection, spec.angle).multiply(1.0 / Math.max(1.0, spec.baseWidthPixels));
                velocities[i][0] = velocities[i][0].add(
                        tubeDirection.multiply(MathHelper.lerp(spec.f, -1.0, 1.0) * SOURCE_UNIT)
                                .add(radialForce.multiply(SOURCE_UNIT)));
                if (attack > 0.5f && attack < 1.0f) {
                    int affected = Math.min(4, spec.segmentCount);
                    for (int j = 0; j < affected; j++) {
                        velocities[i][j] = velocities[i][j].add(randomUnit(random)
                                .multiply(inverseLerp(0.5f, 1.0f, attack) * 0.75 * spec.f));
                    }
                }
                if (entity.getExtended() < 0.25f) {
                    double collapse = inverseLerp(0.25f, 0.0f, entity.getExtended());
                    for (int j = 0; j < spec.segmentCount; j++) {
                        positions[i][j] = positions[i][j].lerp(anchor, collapse);
                        velocities[i][j] = velocities[i][j].multiply(1.0 - collapse);
                    }
                }
            }
        }

        private Vec3d connection(List<Vec3d> path, DanglerSpec spec) {
            Vec3d center = positionAlong(path, spec.f);
            Vec3d tangent = tangentAlong(path, spec.f);
            Vec3d lateral = radial(tangent, spec.angle);
            return center.add(lateral.multiply(spec.side * plantRadiusPixels(spec.f) * SOURCE_UNIT));
        }

        private double widthBlocks(DanglerSpec spec, int segment) {
            float f = segment / (float) Math.max(1, spec.segmentCount - 1);
            double first = MathHelper.lerp((float) Math.pow(f, 0.7), 1.0f, 0.5f);
            double second = 0.5 + Math.sin(Math.pow(f, 2.5) * Math.PI) * 0.5;
            return MathHelper.lerp(f, (float) first, (float) second)
                    * spec.baseWidthPixels * SOURCE_UNIT * 0.5;
        }

        private double connectionLength(DanglerSpec spec, int segment) {
            float f = segment / (float) Math.max(1, spec.segmentCount - 1);
            return MathHelper.lerp(f, 30.0, 5.0) * spec.lengthScale * SOURCE_UNIT;
        }
    }

    /** Swept BodyPart collision against Minecraft's real voxel shapes. */
    private static LeafMotion moveLeafWithCollision(MonsterKelpEntity entity, Vec3d localStart,
                                                    Vec3d requested, double radius) {
        if (requested.lengthSquared() < 1.0E-14) return new LeafMotion(localStart, requested);
        Vec3d origin = entity.getPos();
        Vec3d worldStart = resolveLeafPenetration(entity, localStart, radius).add(origin);
        Box moving = new Box(worldStart, worldStart).expand(radius);
        List<VoxelShape> collisions = blockCollisionShapes(entity,
                stretch(moving, requested).expand(COLLISION_EPSILON));
        if (collisions.isEmpty()) return new LeafMotion(worldStart.add(requested).subtract(origin), requested);

        double x = requested.x;
        double y = requested.y;
        double z = requested.z;
        if (y != 0.0) {
            y = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, moving, collisions, y);
            moving = moving.offset(0.0, y, 0.0);
        }
        if (Math.abs(x) < Math.abs(z)) {
            if (z != 0.0) {
                z = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moving, collisions, z);
                moving = moving.offset(0.0, 0.0, z);
            }
            if (x != 0.0) x = VoxelShapes.calculateMaxOffset(Direction.Axis.X, moving, collisions, x);
        } else {
            if (x != 0.0) {
                x = VoxelShapes.calculateMaxOffset(Direction.Axis.X, moving, collisions, x);
                moving = moving.offset(x, 0.0, 0.0);
            }
            if (z != 0.0) z = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moving, collisions, z);
        }

        Vec3d moved = new Vec3d(x, y, z);
        Vec3d velocity = new Vec3d(
                Math.abs(x - requested.x) > COLLISION_EPSILON ? 0.0 : requested.x * LEAF_SURFACE_FRICTION,
                Math.abs(y - requested.y) > COLLISION_EPSILON ? 0.0 : requested.y * LEAF_SURFACE_FRICTION,
                Math.abs(z - requested.z) > COLLISION_EPSILON ? 0.0 : requested.z * LEAF_SURFACE_FRICTION);
        return new LeafMotion(worldStart.add(moved).subtract(origin), velocity);
    }

    /** Rain World's PointSubmerged check, evaluated independently per leaf segment. */
    private static boolean isLeafSubmerged(MonsterKelpEntity entity, Vec3d localPoint) {
        Vec3d worldPoint = entity.getPos().add(localPoint);
        BlockPos blockPos = BlockPos.ofFloored(worldPoint);
        FluidState fluid = entity.getWorld().getFluidState(blockPos);
        if (!fluid.isIn(FluidTags.WATER)) return false;
        double surface = blockPos.getY() + fluid.getHeight(entity.getWorld(), blockPos);
        return worldPoint.y < surface + COLLISION_EPSILON;
    }

    private static Vec3d resolveLeafPenetration(MonsterKelpEntity entity, Vec3d localPoint, double radius) {
        Vec3d origin = entity.getPos();
        Vec3d result = localPoint.add(origin);
        for (int pass = 0; pass < 4; pass++) {
            List<Box> obstacles = blockCollisionBoxes(entity,
                    new Box(result, result).expand(radius + COLLISION_EPSILON));
            Vec3d resolved = pushPointOutOfBoxes(result, obstacles, radius);
            if (resolved.squaredDistanceTo(result) <= COLLISION_EPSILON * COLLISION_EPSILON) break;
            result = resolved;
        }
        return result.subtract(origin);
    }

    /** Prevents the rendered ribbon between two collision points crossing a block. */
    private static Vec3d clipLeafLinkToTerrain(MonsterKelpEntity entity, Vec3d localStart,
                                               Vec3d localEnd, double radius) {
        if (localStart.squaredDistanceTo(localEnd) < radius * radius * 1.44) return localEnd;
        Vec3d origin = entity.getPos();
        Vec3d worldStart = localStart.add(origin);
        Vec3d worldEnd = localEnd.add(origin);
        Vec3d line = worldEnd.subtract(worldStart);
        if (line.lengthSquared() < 1.0E-10) return localEnd;
        Vec3d rayStart = worldStart.add(line.normalize().multiply(COLLISION_EPSILON * 4.0));
        HitResult result = entity.getWorld().raycast(new RaycastContext(rayStart, worldEnd,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity));
        if (!(result instanceof BlockHitResult blockHit) || result.getType() != HitResult.Type.BLOCK) {
            return localEnd;
        }
        Vec3d normal = Vec3d.of(blockHit.getSide().getVector());
        return blockHit.getPos().add(normal.multiply(radius + COLLISION_EPSILON * 2.0)).subtract(origin);
    }

    private static Vec3d removeInwardVelocity(Vec3d velocity, Vec3d outwardNormal) {
        double inward = velocity.dotProduct(outwardNormal);
        if (inward < 0.0) velocity = velocity.subtract(outwardNormal.multiply(inward));
        return velocity.multiply(LEAF_SURFACE_FRICTION);
    }

    private static List<VoxelShape> blockCollisionShapes(MonsterKelpEntity entity, Box bounds) {
        List<VoxelShape> result = new ArrayList<>();
        for (VoxelShape shape : entity.getWorld().getBlockCollisions(entity, bounds)) result.add(shape);
        return result;
    }

    private static List<Box> blockCollisionBoxes(MonsterKelpEntity entity, Box bounds) {
        List<Box> result = new ArrayList<>();
        for (VoxelShape shape : entity.getWorld().getBlockCollisions(entity, bounds)) {
            result.addAll(shape.getBoundingBoxes());
        }
        return result;
    }

    private static Vec3d pushPointOutOfBoxes(Vec3d point, List<Box> boxes, double radius) {
        Vec3d result = point;
        for (Box box : boxes) {
            Box expanded = box.expand(radius);
            if (!contains(expanded, result)) continue;
            double minX = result.x - expanded.minX;
            double maxX = expanded.maxX - result.x;
            double minY = result.y - expanded.minY;
            double maxY = expanded.maxY - result.y;
            double minZ = result.z - expanded.minZ;
            double maxZ = expanded.maxZ - result.z;
            double nearest = Math.min(Math.min(minX, maxX), Math.min(minY, maxY));
            nearest = Math.min(nearest, Math.min(minZ, maxZ));
            if (nearest == minX) result = new Vec3d(expanded.minX - COLLISION_EPSILON, result.y, result.z);
            else if (nearest == maxX) result = new Vec3d(expanded.maxX + COLLISION_EPSILON, result.y, result.z);
            else if (nearest == minY) result = new Vec3d(result.x, expanded.minY - COLLISION_EPSILON, result.z);
            else if (nearest == maxY) result = new Vec3d(result.x, expanded.maxY + COLLISION_EPSILON, result.z);
            else if (nearest == minZ) result = new Vec3d(result.x, result.y, expanded.minZ - COLLISION_EPSILON);
            else result = new Vec3d(result.x, result.y, expanded.maxZ + COLLISION_EPSILON);
        }
        return result;
    }

    private static boolean contains(Box box, Vec3d point) {
        return point.x >= box.minX && point.x <= box.maxX
                && point.y >= box.minY && point.y <= box.maxY
                && point.z >= box.minZ && point.z <= box.maxZ;
    }

    private static Box stretch(Box box, Vec3d movement) {
        return new Box(
                movement.x < 0.0 ? box.minX + movement.x : box.minX,
                movement.y < 0.0 ? box.minY + movement.y : box.minY,
                movement.z < 0.0 ? box.minZ + movement.z : box.minZ,
                movement.x > 0.0 ? box.maxX + movement.x : box.maxX,
                movement.y > 0.0 ? box.maxY + movement.y : box.maxY,
                movement.z > 0.0 ? box.maxZ + movement.z : box.maxZ);
    }

    private record LeafMotion(Vec3d position, Vec3d velocity) {
    }

    private record DanglerSpec(float f, float side, float angle, int segmentCount,
                               float baseWidthPixels, float lengthScale, float colorNoise) {
    }

    private static int nestedSegmentCount(Random random) {
        int inner = 4 + random.nextInt(8);
        int middle = 4 + random.nextInt(Math.max(1, inner - 3));
        int outer = 4 + random.nextInt(Math.max(1, middle - 3));
        return 2 + random.nextInt(Math.max(1, outer - 1));
    }

    private static int[] leafColor(boolean ocean, float stemPosition, float noise) {
        float brightness = MathHelper.clamp(0.42f + stemPosition * 0.46f + noise * 0.12f, 0.0f, 1.0f);
        if (ocean) {
            return new int[] {
                    MathHelper.clamp((int) (18 + brightness * 32), 0, 255),
                    MathHelper.clamp((int) (54 + brightness * 135), 0, 255),
                    MathHelper.clamp((int) (25 + brightness * 45), 0, 255)
            };
        }
        return new int[] {
                MathHelper.clamp((int) (90 + brightness * 165), 0, 255),
                MathHelper.clamp((int) (4 + brightness * 24), 0, 255),
                MathHelper.clamp((int) (12 + brightness * 30), 0, 255)
        };
    }

    private static int[] darkLeafColor(int[] growthColor, float stemPosition) {
        float tint = 0.055f + stemPosition * 0.035f;
        return new int[] {
                MathHelper.clamp((int) (2 + growthColor[0] * tint), 0, 255),
                MathHelper.clamp((int) (3 + growthColor[1] * tint), 0, 255),
                MathHelper.clamp((int) (4 + growthColor[2] * tint), 0, 255)
        };
    }

    private static double edgeBand(DanglerSpec spec, int joint) {
        double wave = Math.sin(joint * 2.73 + spec.colorNoise * 19.0 + spec.f * 7.0);
        return MathHelper.clamp(0.25 + wave * 0.075, 0.15, 0.34);
    }

    private static float edgeShade(DanglerSpec spec, int segment, boolean rightEdge) {
        double phase = segment * 1.91 + spec.colorNoise * 31.0 + (rightEdge ? 2.17 : 0.0);
        return MathHelper.clamp((float) (0.84 + Math.sin(phase) * 0.16), 0.62f, 1.0f);
    }

    private static double plantRadiusPixels(float f) {
        double shaped = Math.max(1.0 - f,
                Math.sin(Math.PI * inverseLerp(0.7f, 1.0f, f)));
        return MathHelper.lerp(shaped, 1.0, 8.0);
    }

    private static void emitFace(VertexConsumer vertices, Matrix4f matrix,
                                 Vec3d a, Vec3d b, Vec3d c, Vec3d d, Vec3d normal,
                                 int[] color, float shade, int alpha, int light) {
        int r = MathHelper.clamp((int) (color[0] * shade), 0, 255);
        int g = MathHelper.clamp((int) (color[1] * shade), 0, 255);
        int bl = MathHelper.clamp((int) (color[2] * shade), 0, 255);
        putVertex(vertices, matrix, a, white.uvBottomLeft, r, g, bl, alpha, light, normal);
        putVertex(vertices, matrix, b, white.uvBottomRight, r, g, bl, alpha, light, normal);
        putVertex(vertices, matrix, c, white.uvTopRight, r, g, bl, alpha, light, normal);
        putVertex(vertices, matrix, d, white.uvTopLeft, r, g, bl, alpha, light, normal);
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

    private static List<Vec3d> resamplePath(List<Vec3d> path, int samples) {
        List<Vec3d> result = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            result.add(positionAlong(path, i / (double) Math.max(1, samples - 1)));
        }
        return result;
    }

    private static Vec3d positionAlong(List<Vec3d> path, double f) {
        double x = MathHelper.clamp(f, 0.0, 1.0) * (path.size() - 1);
        int index = Math.min(path.size() - 2, (int) x);
        return path.get(index).lerp(path.get(index + 1), x - index);
    }

    private static Vec3d tangentAlong(List<Vec3d> path, double f) {
        double epsilon = 1.0 / Math.max(8.0, path.size() * 3.0);
        return safeNormalize(positionAlong(path, Math.min(1.0, f + epsilon))
                .subtract(positionAlong(path, Math.max(0.0, f - epsilon))), new Vec3d(0.0, 1.0, 0.0));
    }

    private static Vec3d pathTangent(List<Vec3d> path, int index) {
        if (index == 0) return safeNormalize(path.get(1).subtract(path.getFirst()), new Vec3d(0.0, 1.0, 0.0));
        if (index == path.size() - 1) {
            return safeNormalize(path.getLast().subtract(path.get(index - 1)), new Vec3d(0.0, 1.0, 0.0));
        }
        return safeNormalize(path.get(index + 1).subtract(path.get(index - 1)), new Vec3d(0.0, 1.0, 0.0));
    }

    private static Vec3d radial(Vec3d tangent, double angle) {
        Vec3d u = perpendicular(tangent);
        Vec3d v = safeNormalize(tangent.crossProduct(u), new Vec3d(0.0, 0.0, 1.0));
        return u.multiply(Math.cos(angle)).add(v.multiply(Math.sin(angle)));
    }

    private static Vec3d perpendicular(Vec3d vector) {
        Vec3d axis = Math.abs(vector.y) < 0.95 ? new Vec3d(0.0, 1.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);
        return safeNormalize(vector.crossProduct(axis), new Vec3d(1.0, 0.0, 0.0));
    }

    private static Vec3d safeNormalize(Vec3d vector, Vec3d fallback) {
        return vector.lengthSquared() < 1.0E-8 ? fallback : vector.normalize();
    }

    private static Vec3d randomUnit(Random random) {
        double y = random.nextDouble() * 2.0 - 1.0;
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        return new Vec3d(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
    }

    private static boolean finite(Vec3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static float inverseLerp(float from, float to, float value) {
        if (from == to) return 0.0f;
        return MathHelper.clamp((value - from) / (to - from), 0.0f, 1.0f);
    }

    private static void resolveWhite() {
        if (white != null) return;
        try {
            white = LibrainworldmcClient.getAtlasManager().getElementWithName("Futile_White");
        } catch (IllegalStateException ignored) {
            // The libMod atlas can briefly lag behind the initial resource reload.
        }
    }

    @Override
    public boolean shouldRender(MonsterKelpEntity entity, Frustum frustum, double x, double y, double z) {
        return frustum.isVisible(entity.getBoundingBox().expand(20.0));
    }

    @Override
    public Identifier getTexture(MonsterKelpEntity entity) {
        return white != null && white.textureIdentifier != null
                ? white.textureIdentifier : Identifier.ofVanilla("textures/misc/white.png");
    }
}
