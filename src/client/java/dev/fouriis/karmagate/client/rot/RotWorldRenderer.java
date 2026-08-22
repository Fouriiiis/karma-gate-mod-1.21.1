package dev.fouriis.karmagate.client.rot;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.entity.rot.RotBlockEntity;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.*;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

/** Three-dimensional port of DaddyCorruption and CorruptionBulb. */
public final class RotWorldRenderer {
    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise2.png");
    private static final Identifier CORRUPTION_TEXTURE =
            Identifier.of("karma-gate-mod", "textures/effect/corruption.png");
    private static final int VIEW_DISTANCE_CHUNKS = 8;
    private static final int FULL_BRIGHT = 0x00F000F0;
    // 1 block = 20 Rain World pixels. A quarter-pixel separation is enough to
    // prevent depth-buffer fighting without making the painted layer float.
    private static final float DECAL_OFFSET = 0.0125f;
    // Shader packs lose more effective depth precision on nearly horizontal
    // decals. Bias only their depth by half a Rain World pixel in view space;
    // the vertex shader preserves the original screen-space position.
    private static final float IRIS_DECAL_DEPTH_BIAS = 0.025f;
    private static final SphereMesh[] BULB_MESHES = createBulbMeshes();
    private static final SphereMesh NODULE_MESH = createIcosphere(0, 0);
    /** Runtime toggle for LittleLeg gravity. Disabled for the current 3D presentation. */
    public static boolean ROT_GRAVITY_ENABLED = false;
    private static CorruptionSystem system;
    private static long anchorSignature = Long.MIN_VALUE;

    private RotWorldRenderer() { }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;
        List<Anchor> anchors = collectAnchors(client.world, context.camera().getPos());
        long signature = signature(anchors);
        if (anchors.isEmpty()) {
            system = null;
            anchorSignature = signature;
            return;
        }
        // Chunk streaming used to set a global dirty bit and reconstruct the whole
        // simulation every few seconds. The anchor signature already captures every
        // addition, removal and radius change that affects this renderer, so retain
        // live physics whenever that signature is unchanged.
        if (system == null || signature != anchorSignature) {
            system = CorruptionSystem.build(client.world, anchors);
            anchorSignature = signature;
        }
        system.update(client.world, client.player.getPos(), client.player.getVelocity());
        CorruptionSystem captured = system;
        float tickDelta = context.tickCounter().getTickDelta(true);
        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(
                camera -> captured.render(camera, tickDelta), false), 720);
    }

    public static void markDirty() {
        // Kept as the RotRenderCache notification hook. Anchor changes are detected
        // by signature without resetting an otherwise identical live simulation.
    }
    public static void clearCache() { system = null; anchorSignature = Long.MIN_VALUE; }

    private static List<Anchor> collectAnchors(ClientWorld world, Vec3d camera) {
        int chunkX = MathHelper.floor(camera.x) >> 4;
        int chunkZ = MathHelper.floor(camera.z) >> 4;
        ArrayList<Anchor> anchors = new ArrayList<>();
        for (int dx = -VIEW_DISTANCE_CHUNKS; dx <= VIEW_DISTANCE_CHUNKS; dx++) {
            for (int dz = -VIEW_DISTANCE_CHUNKS; dz <= VIEW_DISTANCE_CHUNKS; dz++) {
                for (long packed : RotRenderCache.getPositionsForChunk(chunkX + dx, chunkZ + dz)) {
                    BlockPos pos = BlockPos.fromLong(packed);
                    if (world.getBlockState(pos).getBlock() != ModBlocks.ROT_BLOCK) continue;
                    float radius = world.getBlockEntity(pos) instanceof RotBlockEntity rot
                            ? rot.getRadius() : RotBlockEntity.DEFAULT_RADIUS;
                    anchors.add(new Anchor(pos.toImmutable(), Vec3d.ofCenter(pos), radius));
                }
            }
        }
        anchors.sort(Comparator.comparingLong(a -> a.pos.asLong()));
        return anchors;
    }

    private static long signature(List<Anchor> anchors) {
        long value = 0xCBF29CE484222325L;
        for (Anchor anchor : anchors) {
            value = (value ^ anchor.pos.asLong()) * 0x100000001B3L;
            value = (value ^ Float.floatToIntBits(anchor.radius)) * 0x100000001B3L;
        }
        return value;
    }

    private record Anchor(BlockPos pos, Vec3d center, float radius) { }

    private static final class CorruptionSystem {
        private final List<SurfaceQuad> surfaceQuads;
        private final List<CorruptionBulb> bulbs;
        private long lastTick = Long.MIN_VALUE;

        private CorruptionSystem(List<SurfaceQuad> surfaceQuads, List<CorruptionBulb> bulbs) {
            this.surfaceQuads = surfaceQuads;
            this.bulbs = bulbs;
        }

        private static CorruptionSystem build(ClientWorld world, List<Anchor> anchors) {
            ArrayList<SurfaceQuad> decals = new ArrayList<>();
            LinkedHashMap<SurfaceKey, SurfaceCell> cells = new LinkedHashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            for (long packed : collectCandidateBlocks(anchors)) {
                BlockPos pos = BlockPos.fromLong(packed);
                BlockState state = world.getBlockState(pos);
                if (state.isAir() || state.getBlock() == ModBlocks.ROT_BLOCK
                        || state.getRenderType() != BlockRenderType.MODEL) continue;
                BakedModel model = client.getBlockRenderManager().getModel(state);
                net.minecraft.util.math.random.Random modelRandom =
                        net.minecraft.util.math.random.Random.create(pos.asLong());
                for (Direction direction : Direction.values()) {
                    for (BakedQuad quad : model.getQuads(state, direction, modelRandom))
                        extractQuad(world, anchors, decals, cells, pos, quad, direction);
                }
                for (BakedQuad quad : model.getQuads(state, null, modelRandom))
                    extractQuad(world, anchors, decals, cells, pos, quad, quad.getFace());
            }

            ArrayList<CorruptionBulb> bulbs = new ArrayList<>();
            for (Map.Entry<SurfaceKey, SurfaceCell> entry : cells.entrySet()) {
                SurfaceCell cell = entry.getValue();
                float level = corruptionLevel(cell.center(), anchors);
                Random random = new Random(entry.getKey().seed() ^ anchors.getFirst().pos.asLong());
                // Rain World's population is distributed along a 2D boundary. Repeating its
                // 2-4 bulbs on every face of a 3D volume cubes the workload and turns the
                // individual shapes into a solid mass. Preserve the apparent projected
                // density instead: most occupied faces receive one bulb and deep corruption
                // occasionally receives a second.
                int count = random.nextFloat() < lerp(.32, .72, level) ? 1 : 0;
                if (count > 0 && random.nextFloat() < lerp(.04, .24, level)) count++;
                for (int i = 0; i < count; i++) {
                    SurfacePatch support = cell.patches.get(random.nextInt(cell.patches.size()));
                    Vec3d point = support.sample(random.nextDouble(), random.nextDouble());
                    Vec3d normal = support.quad.normal;
                    float radius = (float) lerp(0.2, 0.5 + 0.5 * random.nextDouble(), level);
                    Vec3d stuck = point.add(normal.multiply(Math.max(0.012, radius * 0.12)));
                    boolean eye = random.nextFloat() < level;
                    if (eye) for (CorruptionBulb existing : bulbs) {
                        if (existing.hasEye && existing.stuck.squaredDistanceTo(stuck)
                                < square(radius + existing.radius)) { eye = false; break; }
                    }
                    CorruptionBulb bulb = new CorruptionBulb(stuck, normal, radius, eye, random);
                    // LittleLegs are also surface-area corrected for 3D. Their construction
                    // and motion remain the C# implementation scaled at 20 px = 1 block.
                    if (random.nextFloat() < 0.075f && random.nextFloat() < level) {
                        float length = (float) lerp(1.0, 7.5, Math.sqrt(random.nextDouble()) * level);
                        bulb.tube = new CorruptionTube(stuck, normal, length, random);
                    }
                    bulbs.add(bulb);
                }
            }
            return new CorruptionSystem(decals, bulbs);
        }

        private static Set<Long> collectCandidateBlocks(List<Anchor> anchors) {
            HashSet<Long> result = new HashSet<>();
            for (Anchor anchor : anchors) {
                int reach = MathHelper.ceil(anchor.radius) + 1;
                double maximum = square(anchor.radius + 0.9);
                for (int x = -reach; x <= reach; x++) for (int y = -reach; y <= reach; y++)
                    for (int z = -reach; z <= reach; z++) {
                        BlockPos candidate = anchor.pos.add(x, y, z);
                        if (Vec3d.ofCenter(candidate).squaredDistanceTo(anchor.center) <= maximum)
                            result.add(candidate.asLong());
                    }
            }
            return result;
        }

        private static void extractQuad(ClientWorld world, List<Anchor> anchors,
                                        List<SurfaceQuad> decals, Map<SurfaceKey, SurfaceCell> cells,
                                        BlockPos blockPos, BakedQuad baked, Direction face) {
            int[] data = baked.getVertexData();
            int stride = data.length / 4;
            if (stride < 3) return;
            Vec3d[] points = new Vec3d[4];
            Vec3d center = Vec3d.ZERO;
            for (int i = 0; i < 4; i++) {
                int offset = i * stride;
                points[i] = new Vec3d(blockPos.getX() + Float.intBitsToFloat(data[offset]),
                        blockPos.getY() + Float.intBitsToFloat(data[offset + 1]),
                        blockPos.getZ() + Float.intBitsToFloat(data[offset + 2]));
                center = center.add(points[i]);
            }
            center = center.multiply(0.25);
            if (corruptionLevel(center, anchors) <= 0.0f
                    || isBoundaryQuadOccluded(world, blockPos, face, center)) return;
            Vec3d nominalNormal = Vec3d.of(face.getVector());
            Vec3d normal = points[1].subtract(points[0]).crossProduct(points[3].subtract(points[0]));
            if (normal.lengthSquared() < 1.0e-10) normal = nominalNormal;
            else {
                normal = normal.normalize();
                if (normal.dotProduct(nominalNormal) < 0) normal = normal.negate();
            }
            float[] levels = new float[4], u = new float[4], v = new float[4];
            float[] noiseU = new float[4], noiseV = new float[4];
            long nearestSeed = nearestAnchor(center, anchors).pos.asLong();
            float seedU = ((nearestSeed >>> 8) & 1023L) / 1023.0f;
            float seedV = ((nearestSeed >>> 20) & 1023L) / 1023.0f;
            for (int i = 0; i < 4; i++) {
                levels[i] = corruptionLevel(points[i], anchors);
                points[i] = points[i].add(normal.multiply(DECAL_OFFSET));
                Vec3d p = points[i];
                switch (face.getAxis()) {
                    case X -> {
                        u[i] = (float) p.z * .22f + seedU;
                        v[i] = (float) p.y * .22f + seedV;
                        noiseU[i] = (float) p.z * .47f + seedU;
                        noiseV[i] = (float) p.y * .47f + seedV;
                    }
                    case Y -> {
                        u[i] = (float) p.x * .22f + seedU;
                        v[i] = (float) p.z * .22f + seedV;
                        noiseU[i] = (float) p.x * .47f + seedU;
                        noiseV[i] = (float) p.z * .47f + seedV;
                    }
                    case Z -> {
                        u[i] = (float) p.x * .22f + seedU;
                        v[i] = (float) p.y * .22f + seedV;
                        noiseU[i] = (float) p.x * .47f + seedU;
                        noiseV[i] = (float) p.y * .47f + seedV;
                    }
                    default -> throw new IllegalStateException("Unexpected face axis");
                }
            }
            SurfaceQuad quad = new SurfaceQuad(points, normal, levels, u, v, noiseU, noiseV);
            decals.add(quad);
            addSurfacePatches(cells, quad, face, anchors);
        }

        /** Divides a baked quad into Rain World tile-sized spawning regions.
         * Atlas-backed block models can extend many blocks beyond their source
         * BlockPos; grouping by that source position produced only one random
         * bulb for the entire gravity disruptor. */
        private static void addSurfacePatches(Map<SurfaceKey, SurfaceCell> cells,
                                              SurfaceQuad quad, Direction face,
                                              List<Anchor> anchors) {
            double edgeU = Math.max(quad.points[0].distanceTo(quad.points[1]),
                    quad.points[3].distanceTo(quad.points[2]));
            double edgeV = Math.max(quad.points[0].distanceTo(quad.points[3]),
                    quad.points[1].distanceTo(quad.points[2]));
            int stepsU = MathHelper.clamp(MathHelper.ceil(edgeU), 1, 16);
            int stepsV = MathHelper.clamp(MathHelper.ceil(edgeV), 1, 16);
            for (int patchU = 0; patchU < stepsU; patchU++) {
                double u0 = patchU / (double) stepsU;
                double u1 = (patchU + 1) / (double) stepsU;
                for (int patchV = 0; patchV < stepsV; patchV++) {
                    double v0 = patchV / (double) stepsV;
                    double v1 = (patchV + 1) / (double) stepsV;
                    SurfacePatch patch = new SurfacePatch(quad, u0, u1, v0, v1);
                    Vec3d patchCenter = patch.sample(.5, .5);
                    // Evaluate the patch itself. Large quads can have all four outer
                    // vertices beyond the Rot radius while their middle crosses it.
                    if (corruptionLevel(patchCenter, anchors) <= 0) continue;
                    Vec3d supportedCenter = patchCenter.subtract(
                            quad.normal.multiply(DECAL_OFFSET + .001));
                    BlockPos tile = BlockPos.ofFloored(supportedCenter);
                    // Detailed atlas models contain many parallel decorative fragments.
                    // Merge those within one world tile/face so model size produces more
                    // coverage, while internal mesh complexity does not produce more physics.
                    SurfaceKey key = new SurfaceKey(tile.asLong(), face);
                    cells.computeIfAbsent(key, ignored -> new SurfaceCell()).add(patch, patchCenter);
                }
            }
        }

        private static boolean isBoundaryQuadOccluded(ClientWorld world, BlockPos pos,
                                                       Direction face, Vec3d center) {
            double local = switch (face.getAxis()) {
                case X -> center.x - pos.getX(); case Y -> center.y - pos.getY();
                case Z -> center.z - pos.getZ();
            };
            boolean boundary = face.getDirection() == Direction.AxisDirection.POSITIVE
                    ? local > .999 : local < .001;
            if (!boundary) return false;
            BlockPos front = pos.offset(face);
            BlockState state = world.getBlockState(front);
            if (!state.isOpaque()) return false;
            VoxelShape shape = state.getOutlineShape(world, front);
            for (Box box : shape.getBoundingBoxes()) if (box.minX <= .001 && box.minY <= .001
                    && box.minZ <= .001 && box.maxX >= .999 && box.maxY >= .999 && box.maxZ >= .999)
                return true;
            return false;
        }

        private static Anchor nearestAnchor(Vec3d point, List<Anchor> anchors) {
            Anchor nearest = anchors.getFirst(); double best = point.squaredDistanceTo(nearest.center);
            for (int i = 1; i < anchors.size(); i++) {
                double distance = point.squaredDistanceTo(anchors.get(i).center);
                if (distance < best) { best = distance; nearest = anchors.get(i); }
            }
            return nearest;
        }

        private static float corruptionLevel(Vec3d point, List<Anchor> anchors) {
            float result = 0;
            for (Anchor anchor : anchors) {
                double distance = point.distanceTo(anchor.center);
                if (distance >= anchor.radius) continue;
                float radial = inverseLerp(anchor.radius, 0, distance);
                float depth = inverseLerp(0, 10, anchor.radius - distance);
                result = Math.max(result, MathHelper.lerp(.5f, radial, depth));
            }
            return result;
        }

        private void update(ClientWorld world, Vec3d playerPosition, Vec3d playerVelocity) {
            long now = world.getTime();
            if (lastTick == now) return;
            for (CorruptionBulb bulb : bulbs) bulb.captureRenderState();
            int elapsed = lastTick == Long.MIN_VALUE ? 1 : (int) MathHelper.clamp(now - lastTick, 1L, 20L);
            lastTick = now;
            boolean movement = playerVelocity.lengthSquared() > .0016;
            for (int tick = 0; tick < elapsed; tick++) {
                if (movement && (now + tick) % 8 == 0) for (CorruptionBulb bulb : bulbs) {
                    double distance = bulb.position.distanceTo(playerPosition);
                    if (distance < 13) bulb.heardNoise(playerPosition, (float) (1 - distance / 13));
                }
                for (int step = 0; step < 2; step++) for (CorruptionBulb bulb : bulbs) bulb.update(world);
            }
        }

        private void render(Camera camera, float delta) {
            renderSurfaceDecals(camera);
            renderSolidBodies(camera, delta);
            renderTubes(camera, delta);
            // Cross eyes are deliberately submitted last, after their opaque parent
            // bulbs and every tube/nodule pass.
            renderBulbEyes(camera, delta);
        }

        private void renderSurfaceDecals(Camera camera) {
            ShaderProgram program = RotShaders.PROGRAM;
            if (program == null || surfaceQuads.isEmpty()) return;
            Vec3d cam = camera.getPos();
            BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
            for (SurfaceQuad quad : surfaceQuads) for (int i = 0; i < 4; i++) {
                Vec3d p = quad.points[i];
                buffer.vertex((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
                        .color(1, 1, 1, quad.levels[i]).texture(quad.u[i], quad.v[i]).light(FULL_BRIGHT)
                        // Normal is not used for lighting in this shader; carry a second
                        // UV set for independently seeded BlackGoo noise.
                        .normal(quad.noiseU[i], quad.noiseV[i], 0);
            }
            drawRotBuffer(buffer, camera, program);
        }

        private void renderSolidBodies(Camera camera, float delta) {
            if (bulbs.isEmpty()) return;
            Vec3d cam = camera.getPos();
            Matrix4f view = new Matrix4f(RenderUtils.getCameraMatrix(camera));
            BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES,
                    VertexFormats.POSITION_COLOR);
            for (CorruptionBulb bulb : bulbs) {
                appendSphere(buffer, view, cam, bulb.renderPosition(delta), bulb.radius,
                        BULB_MESHES[bulb.meshVariant]);
                if (bulb.tube != null) for (TubeBump bump : bulb.tube.bumps)
                    appendSphere(buffer, view, cam, bulb.tube.positionAt(bump.along, delta),
                            (float) lerp(.05, .15, bump.size), NODULE_MESH);
            }
            var built = buffer.endNullable();
            if (built == null) return;
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            BufferRenderer.drawWithGlobalProgram(built);
            RenderSystem.enableCull();
        }

        private void renderTubes(Camera camera, float delta) {
            Vec3d cam = camera.getPos();
            Matrix4f view = new Matrix4f(RenderUtils.getCameraMatrix(camera));
            Vector3f f3 = new Vector3f(0, 0, -1).rotate(camera.getRotation());
            Vector3f r3 = new Vector3f(1, 0, 0).rotate(camera.getRotation());
            Vector3f u3 = new Vector3f(0, 1, 0).rotate(camera.getRotation());
            Vec3d forward = new Vec3d(f3.x, f3.y, f3.z), right = new Vec3d(r3.x, r3.y, r3.z),
                    up = new Vec3d(u3.x, u3.y, u3.z);
            BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR);
            for (CorruptionBulb bulb : bulbs) {
                if (bulb.tube != null) for (int i = 1; i < bulb.tube.segments.length; i++) {
                    Vec3d a = bulb.tube.interpolated(i - 1, delta), b = bulb.tube.interpolated(i, delta);
                    Vec3d side = b.subtract(a).crossProduct(forward);
                    if (side.lengthSquared() < 1e-8) side = right;
                    side = side.normalize().multiply(.1);
                    float mix = (float) Math.pow(i / (float) (bulb.tube.segments.length - 1), 1.5) * .4f;
                    float green = MathHelper.lerp(mix, .008f, 0), blue = MathHelper.lerp(mix, .012f, 1);
                    colorVertex(buffer, view, cam, a.add(side), 0, green, blue);
                    colorVertex(buffer, view, cam, b.add(side), 0, green, blue);
                    colorVertex(buffer, view, cam, b.subtract(side), 0, green, blue);
                    colorVertex(buffer, view, cam, a.subtract(side), 0, green, blue);
                }
                if (bulb.tube != null) for (TubeBump bump : bulb.tube.bumps) {
                    if (bump.eyeSize <= 0) continue;
                    float bodyRadius = (float) lerp(.05, .15, bump.size);
                    Vec3d p = bulb.tube.positionAt(bump.along, delta)
                            .subtract(forward.multiply(bodyRadius + .004));
                    float eyeSize = bodyRadius * bump.eyeSize;
                    appendEyeArm(buffer, view, cam, p, right, up, eyeSize, eyeSize, 0);
                }
            }
            var built = buffer.endNullable();
            if (built == null) return;
            setupRenderState(); RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            BufferRenderer.drawWithGlobalProgram(built); restoreRenderState();
        }

        private void renderBulbEyes(Camera camera, float delta) {
            Vec3d cam = camera.getPos();
            Matrix4f view = new Matrix4f(RenderUtils.getCameraMatrix(camera));
            BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR);
            for (CorruptionBulb bulb : bulbs) {
                if (!bulb.hasEye) continue;
                Vec3d center = bulb.renderPosition(delta);
                // C#: DirVec(eyeStalkPos, interpolated bulb position). This moves
                // with the bulb itself but never changes when the camera rotates.
                Vec3d outward = safeNormalize(center.subtract(bulb.eyeStalk), bulb.direction);
                Vec3d reference = Math.abs(outward.y) < .9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
                Vec3d tangentRight = safeNormalize(outward.crossProduct(reference), new Vec3d(1, 0, 0));
                Vec3d tangentUp = safeNormalize(tangentRight.crossProduct(outward), new Vec3d(0, 0, 1));
                float cos = (float) Math.cos(bulb.rotation), sin = (float) Math.sin(bulb.rotation);
                Vec3d eyeRight = tangentRight.multiply(cos).add(tangentUp.multiply(sin));
                Vec3d eyeUp = tangentUp.multiply(cos).subtract(tangentRight.multiply(sin));
                float eyeRadius = MathHelper.lerp(bulb.eyeRadius, bulb.radius * .5f, bulb.radius);
                float white = MathHelper.clamp(bulb.renderLightNoise, 0, 1);
                appendWrappedEyeArm(buffer, view, cam, center, outward, eyeRight, eyeUp,
                        bulb.radius, eyeRadius * .9f,
                        Math.max(.025f, eyeRadius * .09f), white);
                appendWrappedEyeArm(buffer, view, cam, center, outward, eyeUp, eyeRight,
                        bulb.radius, eyeRadius * .9f,
                        Math.max(.025f, eyeRadius * .09f), white);
            }
            var built = buffer.endNullable();
            if (built == null) return;
            setupRenderState();
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            BufferRenderer.drawWithGlobalProgram(built);
            restoreRenderState();
        }

        private static void appendSphere(BufferBuilder buffer, Matrix4f view, Vec3d camera,
                                         Vec3d center, float radius, SphereMesh mesh) {
            for (SphereVertex vertex : mesh.vertices) {
                Vec3d point = center.add(vertex.position.multiply(radius));
                float green = .006f + vertex.light * .010f;
                float blue = .009f + vertex.light * .014f;
                colorVertex(buffer, view, camera, point, 0, green, blue);
            }
        }

        private static void appendEyeArm(BufferBuilder b, Matrix4f view, Vec3d cam, Vec3d center,
                                         Vec3d along, Vec3d across, float length, float width, float white) {
            Vec3d l = along.normalize().multiply(length), w = across.normalize().multiply(width);
            colorVertex(b, view, cam, center.subtract(l), white, white, 1);
            colorVertex(b, view, cam, center.subtract(w), white, white, 1);
            colorVertex(b, view, cam, center.add(l), white, white, 1);
            colorVertex(b, view, cam, center.add(w), white, white, 1);
        }

        /** Projects each strip vertex onto the visible hemisphere instead of drawing
         * the cross as a tangent-plane decal. This is the 3D equivalent of C#'s
         * BulgeVertex slit mesh and keeps large eyes conforming to their bulbs. */
        private static void appendWrappedEyeArm(BufferBuilder b, Matrix4f view, Vec3d cam,
                                                Vec3d center, Vec3d outward,
                                                Vec3d along, Vec3d across, float radius,
                                                float length, float width, float white) {
            final int segments = 8;
            float wrappedLength = Math.min(length, radius * .92f);
            for (int i = 0; i < segments; i++) {
                float t0 = i / (float) segments;
                float t1 = (i + 1) / (float) segments;
                float x0 = MathHelper.lerp(t0, -wrappedLength, wrappedLength);
                float x1 = MathHelper.lerp(t1, -wrappedLength, wrappedLength);
                float w0 = width * (.22f + .78f * (float) Math.sin(Math.PI * t0));
                float w1 = width * (.22f + .78f * (float) Math.sin(Math.PI * t1));
                wrappedEyeVertex(b, view, cam, center, outward, along, across,
                        radius, x0, -w0, white);
                wrappedEyeVertex(b, view, cam, center, outward, along, across,
                        radius, x1, -w1, white);
                wrappedEyeVertex(b, view, cam, center, outward, along, across,
                        radius, x1, w1, white);
                wrappedEyeVertex(b, view, cam, center, outward, along, across,
                        radius, x0, w0, white);
            }
        }

        private static void wrappedEyeVertex(BufferBuilder b, Matrix4f view, Vec3d cam,
                                             Vec3d center, Vec3d outward,
                                             Vec3d along, Vec3d across, float radius,
                                             float x, float y, float white) {
            Vec3d tangent = along.normalize().multiply(x).add(across.normalize().multiply(y));
            double maximum = radius * .965;
            if (tangent.lengthSquared() > maximum * maximum)
                tangent = tangent.normalize().multiply(maximum);
            double depth = Math.sqrt(Math.max(0, radius * radius - tangent.lengthSquared()));
            Vec3d radial = tangent.add(outward.normalize().multiply(depth)).normalize();
            // The body roughness hash is defined only at the shared icosphere
            // vertices. Sampling it continuously here made adjacent eye vertices
            // jump between unrelated radii and folded the strip into spikes.
            // A smooth shell just outside the maximum body radius is stable and
            // guarantees the final eye pass cannot clip into its parent.
            double eyeShellRadius = radius * 1.102 + .003;
            Vec3d point = center.add(radial.multiply(eyeShellRadius));
            colorVertex(b, view, cam, point, white, white, 1);
        }

        private static void colorVertex(VertexConsumer b, Matrix4f view, Vec3d cam, Vec3d p,
                                        float red, float green, float blue) {
            b.vertex(view, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
                    .color(red, green, blue, 1);
        }

        private static void drawRotBuffer(BufferBuilder buffer, Camera camera, ShaderProgram program) {
            var built = buffer.endNullable(); if (built == null) return;
            setupRenderState();
            RenderSystem.setShader(() -> program); RenderSystem.setShaderTexture(0, NOISE_TEXTURE);
            program.addSampler("Sampler0", MinecraftClient.getInstance().getTextureManager().getTexture(NOISE_TEXTURE));
            RenderSystem.setShaderTexture(1, CORRUPTION_TEXTURE);
            program.addSampler("Sampler1", MinecraftClient.getInstance().getTextureManager()
                    .getTexture(CORRUPTION_TEXTURE));
            // Shader packs render the world with libMod's captured bobbed view
            // matrix. Using the raw camera rotation here makes an otherwise
            // coplanar decal disagree with Iris's depth buffer, most visibly as
            // screen-space horizontal bands over floors.
            setUniform(program, "uViewMat", new Matrix4f(RenderUtils.getCameraMatrix(camera)));
            setUniform(program, "uMode", 0);
            setUniform(program, "uViewDepthBias",
                    isIrisShaderPackActive() ? IRIS_DECAL_DEPTH_BIAS : 0.0f);
            BufferRenderer.drawWithGlobalProgram(built);
            restoreRenderState();
        }

        private static void setupRenderState() {
            RenderSystem.enableDepthTest(); RenderSystem.depthMask(false); RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc(); RenderSystem.disableCull();
        }
        private static void restoreRenderState() {
            RenderSystem.depthMask(true); RenderSystem.enableCull(); RenderSystem.disableBlend();
        }
    }

    private record SurfaceKey(long position, Direction face) {
        private long seed() { return position * 31 + face.ordinal(); }
    }
    private static final class SurfaceCell {
        private final ArrayList<SurfacePatch> patches = new ArrayList<>();
        private Vec3d centerSum = Vec3d.ZERO;
        private void add(SurfacePatch patch, Vec3d center) {
            patches.add(patch);
            centerSum = centerSum.add(center);
        }
        private Vec3d center() { return centerSum.multiply(1.0 / patches.size()); }
    }
    private record SurfacePatch(SurfaceQuad quad, double u0, double u1, double v0, double v1) {
        private Vec3d sample(double u, double v) {
            return quad.sample(lerp(u0, u1, u), lerp(v0, v1, v));
        }
    }
    private static final class SurfaceQuad {
        private final Vec3d[] points; private final Vec3d normal;
        private final float[] levels, u, v, noiseU, noiseV;
        private SurfaceQuad(Vec3d[] p, Vec3d n, float[] levels, float[] u, float[] v,
                            float[] noiseU, float[] noiseV) {
            points = p; normal = n; this.levels = levels; this.u = u; this.v = v;
            this.noiseU = noiseU; this.noiseV = noiseV;
        }
        private Vec3d sample(double u, double v) {
            return points[0].lerp(points[1], u).lerp(points[3].lerp(points[2], u), v);
        }
    }

    private static final class CorruptionBulb {
        private final Random random; private final Vec3d stuck, direction, eyeStalk;
        private final float radius, rotation, eyeRadius; private final boolean hasEye;
        private final int meshVariant;
        private Vec3d position, previousRenderPosition, velocity = Vec3d.ZERO;
        private Vec3d lookDirection, lastLookDirection, nextLookDirection, legReachPosition;
        private int reactionDelay; private boolean heardSound;
        private float light, renderLightNoise, focus, focusGoal; private CorruptionTube tube;
        private CorruptionBulb(Vec3d stuck, Vec3d direction, float radius, boolean eye, Random source) {
            random = new Random(source.nextLong()); this.stuck = stuck; this.direction = direction;
            this.radius = radius; hasEye = eye; position = previousRenderPosition = stuck;
            eyeStalk = stuck.subtract(direction.multiply(lerp(.5, 2.5, random.nextDouble())));
            rotation = random.nextFloat() * MathHelper.TAU; eyeRadius = random.nextFloat();
            meshVariant = random.nextInt(BULB_MESHES.length);
            lookDirection = lastLookDirection = nextLookDirection = randomUnit(random);
        }
        private void captureRenderState() { previousRenderPosition = position; if (tube != null) tube.capture(); }
        private void update(ClientWorld world) {
            position = position.add(velocity);
            velocity = velocity.multiply(.9).add(lookDirection.multiply(.005))
                    .subtract(position.subtract(stuck).multiply(.1));
            Vec3d offset = position.subtract(stuck); double limit = radius * .5;
            if (offset.lengthSquared() > limit * limit) {
                Vec3d correction = offset.normalize().multiply(offset.length() - limit);
                velocity = velocity.subtract(correction); position = position.subtract(correction);
            }
            if (tube != null) {
                velocity = velocity.add(safeNormalize(tube.segments[tube.segments.length / 2].position
                        .subtract(position), Vec3d.ZERO).multiply(.05));
                tube.update(world, legReachPosition);
            }
            float changed = light * inverseLerp(0, 1, lastLookDirection.distanceTo(lookDirection));
            light = Math.max(0, light - .05f); renderLightNoise = random.nextFloat() * light;
            if (random.nextFloat() < changed) focusGoal = Math.max(focusGoal, random.nextFloat());
            else if (random.nextFloat() < 1f / 70f) focusGoal = 0;
            focus = moveTowards(focus, focusGoal, .05f);
            if (random.nextFloat() < .01f) legReachPosition = null;
            lastLookDirection = lookDirection;
            if (reactionDelay < 1) {
                lookDirection = nextLookDirection;
                if (heardSound) { light = Math.max(light, random.nextFloat());
                    legReachPosition = position.add(lookDirection.multiply(lerp(5, 10, random.nextDouble()))); }
                heardSound = false; reactionDelay = 10 + random.nextInt(10);
            } else reactionDelay--;
            if (random.nextFloat() < .00125f) nextLookDirection = randomUnit(random);
        }
        private void heardNoise(Vec3d p, float intensity) {
            nextLookDirection = safeNormalize(p.subtract(stuck), nextLookDirection); heardSound = true;
            light = Math.max(light, intensity); if (legReachPosition == null) legReachPosition = p;
        }
        private Vec3d renderPosition(float delta) { return previousRenderPosition.lerp(position, delta); }
    }

    private static final class CorruptionTube {
        private static final double CONNECTION_RADIUS = .5, PUSH_APART = .15 / 20;
        private final Random random; private final Vec3d mountedDirection;
        private final TubeSegment[] segments; private final TubeBump[] bumps; private int moveCounter;
        private CorruptionTube(Vec3d start, Vec3d direction, float length, Random source) {
            random = new Random(source.nextLong()); mountedDirection = direction;
            int count = Math.max(2, Math.min(200, (int) (length / CONNECTION_RADIUS)));
            segments = new TubeSegment[count];
            for (int i = 0; i < count; i++) {
                Vec3d p = start.lerp(start.add(direction.multiply(length)), i / (double) (count - 1))
                        .add(randomUnit(random).multiply(random.nextDouble() / 20));
                segments[i] = new TubeSegment(p, randomUnit(random).multiply(random.nextDouble() / 20));
            }
            bumps = new TubeBump[count / 2 + 5 + random.nextInt(3)];
            for (int i = 0; i < bumps.length; i++) {
                float amount = (float) Math.sqrt(random.nextDouble()); if (i == 0) amount = 1;
                float along = (float) lerp(inverseLerp(0, count, count - 20), 1, amount);
                float size = (float) lerp(random.nextDouble(), amount, random.nextDouble());
                float eye = random.nextFloat() * 1.6f < lerp(0, .6, amount)
                        ? (float) lerp(.2, .8, random.nextDouble()) : 0;
                bumps[i] = new TubeBump((float) lerp(-.15, .15, random.nextDouble()) * amount,
                        along, size, eye);
            }
        }
        private void capture() { for (TubeSegment s : segments) s.previousRenderPosition = s.position; }
        private void update(ClientWorld world, Vec3d reach) {
            for (int i = 2; i < segments.length; i++) {
                Vec3d apart = safeNormalize(segments[i].position.subtract(segments[i - 2].position),
                        new Vec3d(0, 1, 0));
                segments[i - 2].velocity = segments[i - 2].velocity.subtract(apart.multiply(PUSH_APART));
                segments[i].velocity = segments[i].velocity.add(apart.multiply(PUSH_APART));
            }
            for (int i = 0; i < segments.length; i++) {
                TubeSegment s = segments[i];
                if (ROT_GRAVITY_ENABLED)
                    s.velocity = s.velocity.add(0, -.045 * inverseLerp(2, 5, i), 0);
                s.position = s.position.add(s.velocity); s.velocity = s.velocity.multiply(.999);
                if (i > 2) resolveTerrain(world, s, .1);
            }
            connectWall(); for (int i = segments.length - 1; i > 0; i--) connect(i, i - 1);
            connectWall(); for (int i = 1; i < segments.length; i++) connect(i, i - 1); connectWall();
            for (int i = 0; i < segments.length; i++) {
                float along = i / (float) (segments.length - 1);
                segments[i].velocity = segments[i].velocity.add(mountedDirection.multiply(
                        .05 * inverseLerp(5, 1, i)));
                if (reach != null) segments[i].velocity = segments[i].velocity.add(safeNormalize(
                        reach.subtract(segments[i].position), Vec3d.ZERO).multiply(.01 * random.nextDouble()));
                else if (moveCounter < 0) segments[i].velocity = segments[i].velocity.add(
                        randomUnit(random).multiply(.1 * random.nextDouble() * along));
            }
            moveCounter--; if (moveCounter < 0 && random.nextFloat() < .025f) moveCounter = 80 + random.nextInt(220);
        }
        private static void resolveTerrain(ClientWorld world, TubeSegment s, double radius) {
            BlockPos pos = BlockPos.ofFloored(s.position); VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
            double x = s.position.x - pos.getX(), y = s.position.y - pos.getY(), z = s.position.z - pos.getZ();
            for (Box box : shape.getBoundingBoxes()) {
                if (x <= box.minX || x >= box.maxX || y <= box.minY || y >= box.maxY || z <= box.minZ || z >= box.maxZ) continue;
                double[] d = {x-box.minX,box.maxX-x,y-box.minY,box.maxY-y,z-box.minZ,box.maxZ-z}; int side=0;
                for (int i=1;i<6;i++) if(d[i]<d[side]) side=i; Vec3d old=s.position;
                s.position = switch(side) {
                    case 0 -> new Vec3d(pos.getX()+box.minX-radius,old.y,old.z);
                    case 1 -> new Vec3d(pos.getX()+box.maxX+radius,old.y,old.z);
                    case 2 -> new Vec3d(old.x,pos.getY()+box.minY-radius,old.z);
                    case 3 -> new Vec3d(old.x,pos.getY()+box.maxY+radius,old.z);
                    case 4 -> new Vec3d(old.x,old.y,pos.getZ()+box.minZ-radius);
                    default -> new Vec3d(old.x,old.y,pos.getZ()+box.maxZ+radius); };
                Vec3d delta=s.position.subtract(old); s.velocity=new Vec3d(delta.x!=0?s.velocity.x*-.15:s.velocity.x,
                        delta.y!=0?s.velocity.y*-.15:s.velocity.y,delta.z!=0?s.velocity.z*-.15:s.velocity.z); return;
            }
        }
        private void connectWall() { segments[0].position=segments[0].stuck; segments[0].velocity=Vec3d.ZERO; }
        private void connect(int a,int b) {
            Vec3d dir=safeNormalize(segments[a].position.subtract(segments[b].position),new Vec3d(0,1,0));
            double distance=segments[a].position.distanceTo(segments[b].position);
            Vec3d correction=dir.multiply((CONNECTION_RADIUS-distance)*.5*inverseLerp(0,CONNECTION_RADIUS,distance));
            segments[a].position=segments[a].position.add(correction); segments[a].velocity=segments[a].velocity.add(correction);
            segments[b].position=segments[b].position.subtract(correction); segments[b].velocity=segments[b].velocity.subtract(correction);
        }
        private Vec3d interpolated(int i,float d) { return segments[i].previousRenderPosition.lerp(segments[i].position,d); }
        private Vec3d positionAt(float amount,float d) {
            float scaled=MathHelper.clamp(amount,0,1)*(segments.length-1); int a=MathHelper.clamp(MathHelper.floor(scaled),0,segments.length-1);
            int b=Math.min(a+1,segments.length-1); return interpolated(a,d).lerp(interpolated(b,d),scaled-a);
        }
    }
    private static final class TubeSegment {
        private Vec3d position, previousRenderPosition, velocity; private final Vec3d stuck;
        private TubeSegment(Vec3d p,Vec3d v) { position=previousRenderPosition=stuck=p; velocity=v; }
    }
    private record TubeBump(float offset,float along,float size,float eyeSize) { }

    private record SphereVertex(Vec3d position, float light) { }
    private record SphereMesh(SphereVertex[] vertices) { }
    private record SphereTriangle(Vec3d a, Vec3d b, Vec3d c) { }

    private static SphereMesh[] createBulbMeshes() {
        SphereMesh[] meshes = new SphereMesh[8];
        for (int i = 0; i < meshes.length; i++) meshes[i] = createIcosphere(1, i + 1);
        return meshes;
    }

    /**
     * Precomputes a small set of closed, irregular sphere meshes. The old UV
     * spheres evaluated trigonometry and emitted 640 vertices for every bulb
     * and nodule every frame. Main bulbs now emit 240 precomputed vertices and
     * tiny nodules just 60, while deterministic radial displacement retains the
     * uneven JaggedCircle silhouette without transparent holes.
     */
    private static SphereMesh createIcosphere(int subdivisions, int variant) {
        double golden = (1.0 + Math.sqrt(5.0)) * .5;
        Vec3d[] points = {
                new Vec3d(-1, golden, 0), new Vec3d(1, golden, 0),
                new Vec3d(-1, -golden, 0), new Vec3d(1, -golden, 0),
                new Vec3d(0, -1, golden), new Vec3d(0, 1, golden),
                new Vec3d(0, -1, -golden), new Vec3d(0, 1, -golden),
                new Vec3d(golden, 0, -1), new Vec3d(golden, 0, 1),
                new Vec3d(-golden, 0, -1), new Vec3d(-golden, 0, 1)
        };
        for (int i = 0; i < points.length; i++) points[i] = points[i].normalize();
        int[][] indices = {
                {0,11,5},{0,5,1},{0,1,7},{0,7,10},{0,10,11},
                {1,5,9},{5,11,4},{11,10,2},{10,7,6},{7,1,8},
                {3,9,4},{3,4,2},{3,2,6},{3,6,8},{3,8,9},
                {4,9,5},{2,4,11},{6,2,10},{8,6,7},{9,8,1}
        };
        ArrayList<SphereTriangle> triangles = new ArrayList<>();
        for (int[] face : indices)
            triangles.add(new SphereTriangle(points[face[0]], points[face[1]], points[face[2]]));
        for (int pass = 0; pass < subdivisions; pass++) {
            ArrayList<SphereTriangle> divided = new ArrayList<>(triangles.size() * 4);
            for (SphereTriangle triangle : triangles) {
                Vec3d ab = triangle.a.add(triangle.b).normalize();
                Vec3d bc = triangle.b.add(triangle.c).normalize();
                Vec3d ca = triangle.c.add(triangle.a).normalize();
                divided.add(new SphereTriangle(triangle.a, ab, ca));
                divided.add(new SphereTriangle(triangle.b, bc, ab));
                divided.add(new SphereTriangle(triangle.c, ca, bc));
                divided.add(new SphereTriangle(ab, bc, ca));
            }
            triangles = divided;
        }

        Vec3d lightDirection = new Vec3d(-.35, .8, -.45).normalize();
        SphereVertex[] vertices = new SphereVertex[triangles.size() * 3];
        int cursor = 0;
        for (SphereTriangle triangle : triangles) {
            Vec3d[] corners = {triangle.a, triangle.b, triangle.c};
            for (Vec3d normal : corners) {
                double roughness = sphereRoughness(normal, variant);
                float light = (float) Math.max(0, normal.dotProduct(lightDirection));
                vertices[cursor++] = new SphereVertex(normal.multiply(roughness), light);
            }
        }
        return new SphereMesh(vertices);
    }

    private static double sphereRoughness(Vec3d normal, int variant) {
        if (variant == 0) return 1.0;
        double hash = Math.sin(normal.x * 31.17 + normal.y * 57.91
                + normal.z * 91.73 + variant * 17.11) * 43758.5453;
        hash -= Math.floor(hash);
        return lerp(.91, 1.10, hash);
    }

    private static Vec3d randomUnit(Random random) {
        double y=lerp(-1,1,random.nextDouble()), r=Math.sqrt(Math.max(0,1-y*y)), a=random.nextDouble()*Math.PI*2;
        return new Vec3d(Math.cos(a)*r,y,Math.sin(a)*r);
    }
    private static Vec3d safeNormalize(Vec3d v,Vec3d fallback) { return v.lengthSquared()<1e-8?fallback:v.normalize(); }
    private static float moveTowards(float a,float b,float n) { return a<b?Math.min(a+n,b):Math.max(a-n,b); }
    private static float inverseLerp(double a,double b,double v) { return a==b?0:(float)MathHelper.clamp((v-a)/(b-a),0,1); }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    private static double square(double v) { return v*v; }
    private static boolean isIrisShaderPackActive() {
        try {
            Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = irisApi.getMethod("getInstance").invoke(null);
            Object active = irisApi.getMethod("isShaderPackInUse").invoke(api);
            return active instanceof Boolean enabled && enabled;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private static void setUniform(ShaderProgram p,String n,float v) { GlUniform u=p.getUniform(n); if(u!=null)u.set(v); }
    private static void setUniform(ShaderProgram p,String n,Matrix4f v) { GlUniform u=p.getUniform(n); if(u!=null)u.set(v); }
}
