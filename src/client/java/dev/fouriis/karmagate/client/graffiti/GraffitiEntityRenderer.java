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
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Renders graffiti as a decal projected onto block surfaces.
 *
 * Key detail:
 * - We CLIP each block-face quad in UV space to the [0..1] range on the CPU.
 *   This prevents tiling/bleeding even when Iris shader packs override custom shaders.
 */
public class GraffitiEntityRenderer extends EntityRenderer<GraffitiEntity> {

    private static final int MAX_DEPTH = 3;
    private static final float DECAL_OFFSET = 0.002f;

    private static final String APRIL_FOOLS_TEXTURE_KEY = "graffiti_april_fools";
    private static final Map<Path, Identifier> aprilFoolsTextureIds = new HashMap<>();
    private static List<Path> aprilFoolsPngFiles = List.of();
    private static LocalDate aprilFoolsTextureDate;
    private static boolean aprilFoolsScanAttempted;

    public GraffitiEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    private Identifier getEntityTexture(GraffitiEntity entity) {
        Identifier aprilFoolsTexture = getAprilFoolsTexture(entity);
        if (aprilFoolsTexture != null) {
            return aprilFoolsTexture;
        }

        String texturePath = entity.getTexturePath();
        if (texturePath.endsWith(".mp4")) {
            // Always returns a valid GL identifier — broken videos get a 1×1
            // black placeholder, never the raw .mp4 path which Minecraft would
            // try to parse as a PNG and throw "Bad PNG Signature".
            return VideoTextureManager.getOrCreate(texturePath);
        }
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

        double entityX = entity.getX();
        double entityY = entity.getY();
        double entityZ = entity.getZ();

        Identifier texture = getEntityTexture(entity);
        VertexConsumer vc = vertexConsumers.getBuffer(GraffitiRenderLayer.get(texture));
        Matrix4f mat = matrices.peek().getPositionMatrix();
        boolean useCustomShader = GraffitiRenderLayer.useCustomShader();

        float[][] entityCorners = entity.getCorners();
        float[] cornerOpacity = new float[] {
            entity.getCornerOpacity(0),
            entity.getCornerOpacity(1),
            entity.getCornerOpacity(2),
            entity.getCornerOpacity(3)
        };
        float[] cornerMelt = new float[] {
            entity.getCornerMelt(0),
            entity.getCornerMelt(1),
            entity.getCornerMelt(2),
            entity.getCornerMelt(3)
        };

        // Bounds in local (H,V)
        float minH = Float.MAX_VALUE, maxH = Float.MIN_VALUE;
        float minV = Float.MAX_VALUE, maxV = Float.MIN_VALUE;
        for (float[] corner : entityCorners) {
            minH = Math.min(minH, corner[0]);
            maxH = Math.max(maxH, corner[0]);
            minV = Math.min(minV, corner[1]);
            maxV = Math.max(maxV, corner[1]);
        }

        Direction rightDir = facing.rotateYClockwise();

        int minBlockH = MathHelper.floor(minH) - 1;
        int maxBlockH = MathHelper.ceil(maxH) + 1;
        int minBlockV = MathHelper.floor(minV) - 1;
        int maxBlockV = MathHelper.ceil(maxV) + 1;

        List<DecalQuad> quadsToDraw = new ArrayList<>();

        for (int h = minBlockH; h <= maxBlockH; h++) {
            for (int v = minBlockV; v <= maxBlockV; v++) {
                for (int d = 0; d < MAX_DEPTH; d++) {
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

                    BakedModel model = MinecraftClient.getInstance().getBlockRenderManager().getModel(state);
                    if (model == null) continue;

                    // Stable random per block to avoid flicker on models with random variants
                    Random seeded = Random.create(blockPos.asLong());

                    extractFromModel(
                        quadsToDraw, model, state, blockPos, facing,
                        entityX, entityY, entityZ, entityCorners, cornerOpacity, cornerMelt, world, seeded
                    );
                }
            }
        }

        for (DecalQuad dq : quadsToDraw) {
            renderQuad(mat, vc, dq, world, useCustomShader);
        }
    }

    private void extractFromModel(List<DecalQuad> out, BakedModel model, BlockState state,
                                  BlockPos blockPos, Direction entityFacing,
                                  double entityX, double entityY, double entityZ,
                                  float[][] entityCorners, float[] cornerOpacity, float[] cornerMelt,
                                  World world, Random random) {

        Direction[] dirs = {
            entityFacing.getOpposite(),
            entityFacing.rotateYClockwise(),
            entityFacing.rotateYCounterclockwise(),
            Direction.UP,
            Direction.DOWN
        };

        for (Direction dir : dirs) {
            List<BakedQuad> quads = model.getQuads(state, dir, random);
            for (BakedQuad quad : quads) {
                extractFromQuad(out, quad, blockPos, dir, entityFacing,
                    entityX, entityY, entityZ, entityCorners, cornerOpacity, cornerMelt, world);
            }
        }

        // Unculled quads for complex models
        List<BakedQuad> unculled = model.getQuads(state, null, random);
        for (BakedQuad quad : unculled) {
            Direction qd = quad.getFace();
            if (isRelevant(qd, entityFacing)) {
                extractFromQuad(out, quad, blockPos, qd, entityFacing,
                    entityX, entityY, entityZ, entityCorners, cornerOpacity, cornerMelt, world);
            }
        }
    }

    private boolean isRelevant(Direction quadDir, Direction entityFacing) {
        return quadDir == entityFacing.getOpposite()
            || quadDir == entityFacing.rotateYClockwise()
            || quadDir == entityFacing.rotateYCounterclockwise()
            || quadDir == Direction.UP
            || quadDir == Direction.DOWN;
    }

    private void extractFromQuad(List<DecalQuad> out, BakedQuad quad, BlockPos blockPos, Direction quadDir,
                                 Direction entityFacing, double entityX, double entityY, double entityZ,
                                 float[][] entityCorners, float[] cornerOpacity, float[] cornerMelt,
                                 World world) {

        if (wouldQuadBeOccluded(world, blockPos, quadDir)) return;

        int[] vd = quad.getVertexData();
        int vertexSize = 8;

        double bx = blockPos.getX();
        double by = blockPos.getY();
        double bz = blockPos.getZ();

        // Build initial polygon with 4 vertices (pos + uv)
        List<Vtx> poly = new ArrayList<>(4);

        float nx = quadDir.getOffsetX();
        float ny = quadDir.getOffsetY();
        float nz = quadDir.getOffsetZ();

        for (int i = 0; i < 4; i++) {
            int off = i * vertexSize;
            float x = Float.intBitsToFloat(vd[off]);
            float y = Float.intBitsToFloat(vd[off + 1]);
            float z = Float.intBitsToFloat(vd[off + 2]);

            double rx = bx + x - entityX + nx * DECAL_OFFSET;
            double ry = by + y - entityY + ny * DECAL_OFFSET;
            double rz = bz + z - entityZ + nz * DECAL_OFFSET;

            float[] uv = computeUV(rx, ry, rz, entityCorners, entityFacing);
            float u = uv[0];
            float vTex = uv[1];
            float v = 1.0f - vTex;
            float opacity = bilerp(cornerOpacity, u, v);
            float melt = bilerp(cornerMelt, u, v);
            poly.add(new Vtx(rx, ry, rz, u, vTex, opacity, melt));
        }

        // Quick reject: no overlap with [0..1] UV area
        if (!overlapsUnitSquare(poly)) return;

        // Clip polygon in UV space to [0..1]x[0..1]
        poly = clipPoly(poly, ClipEdge.U_MIN);
        if (poly.size() < 3) return;
        poly = clipPoly(poly, ClipEdge.U_MAX);
        if (poly.size() < 3) return;
        poly = clipPoly(poly, ClipEdge.V_MIN);
        if (poly.size() < 3) return;
        poly = clipPoly(poly, ClipEdge.V_MAX);
        if (poly.size() < 3) return;

        // Triangulate (fan) then emit as "degenerate quads" (a,b,c,c) so we can still draw QUADS
        BlockPos lightPos = blockPos.offset(quadDir);

        Vtx v0 = poly.get(0);
        for (int i = 1; i + 1 < poly.size(); i++) {
            Vtx v1 = poly.get(i);
            Vtx v2 = poly.get(i + 1);

            // triangle as degenerate quad: v0, v1, v2, v2
            out.add(new DecalQuad(
                new Vtx[]{v0, v1, v2, v2},
                lightPos,
                nx, ny, nz
            ));
        }
    }

    private boolean wouldQuadBeOccluded(World world, BlockPos blockPos, Direction facing) {
        BlockPos frontPos = blockPos.offset(facing);
        BlockState frontState = world.getBlockState(frontPos);

        if (frontState.isAir()) return false;
        if (!frontState.isOpaque()) return false;

        VoxelShape shape = frontState.getOutlineShape(world, frontPos);
        if (shape.isEmpty()) return false;

        for (Box b : shape.getBoundingBoxes()) {
            if (b.minX <= 0.001 && b.minY <= 0.001 && b.minZ <= 0.001 &&
                b.maxX >= 0.999 && b.maxY >= 0.999 && b.maxZ >= 0.999) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes UV for a world-relative point (rx,ry,rz) using the same basis as the corner editor.
     * Returns (u, vTex) where vTex is already flipped for texture coordinates.
     */
    private float[] computeUV(double rx, double ry, double rz, float[][] entityCorners, Direction facing) {
        // Entity corner quad in local (H,V)
        float h0 = entityCorners[0][0], v0 = entityCorners[0][1];
        float h1 = entityCorners[1][0], v1 = entityCorners[1][1];
        float h2 = entityCorners[2][0], v2 = entityCorners[2][1];
        float h3 = entityCorners[3][0], v3 = entityCorners[3][1];

        // Convert (rx,ry,rz) to local (H,V) matching GraffitiCornerHandler:
        // h = dot(relative, rightDir), v = relative.y
        Direction rightDir = facing.rotateYClockwise();
        double localH = rx * rightDir.getOffsetX() + rz * rightDir.getOffsetZ();
        double localV = ry;

        float[] uv = inverseBilinear((float) localH, (float) localV, h0, v0, h1, v1, h2, v2, h3, v3);

        float u = uv[0];
        float vTex = 1.0f - uv[1];
        return new float[]{u, vTex};
    }

    private boolean overlapsUnitSquare(List<Vtx> poly) {
        float minU = poly.get(0).u, maxU = poly.get(0).u;
        float minV = poly.get(0).v, maxV = poly.get(0).v;
        for (int i = 1; i < poly.size(); i++) {
            Vtx p = poly.get(i);
            minU = Math.min(minU, p.u);
            maxU = Math.max(maxU, p.u);
            minV = Math.min(minV, p.v);
            maxV = Math.max(maxV, p.v);
        }
        return minU <= 1.0f && maxU >= 0.0f && minV <= 1.0f && maxV >= 0.0f;
    }

    private enum ClipEdge { U_MIN, U_MAX, V_MIN, V_MAX }

    private List<Vtx> clipPoly(List<Vtx> in, ClipEdge edge) {
        List<Vtx> out = new ArrayList<>();
        if (in.isEmpty()) return out;

        Vtx S = in.get(in.size() - 1);
        for (Vtx E : in) {
            boolean Ein = inside(E, edge);
            boolean Sin = inside(S, edge);

            if (Ein) {
                if (!Sin) {
                    out.add(intersect(S, E, edge));
                }
                out.add(E);
            } else if (Sin) {
                out.add(intersect(S, E, edge));
            }

            S = E;
        }
        return out;
    }

    private boolean inside(Vtx p, ClipEdge edge) {
        return switch (edge) {
            case U_MIN -> p.u >= 0.0f;
            case U_MAX -> p.u <= 1.0f;
            case V_MIN -> p.v >= 0.0f;
            case V_MAX -> p.v <= 1.0f;
        };
    }

    private Vtx intersect(Vtx a, Vtx b, ClipEdge edge) {
        float t;
        switch (edge) {
            case U_MIN -> t = (0.0f - a.u) / (b.u - a.u);
            case U_MAX -> t = (1.0f - a.u) / (b.u - a.u);
            case V_MIN -> t = (0.0f - a.v) / (b.v - a.v);
            case V_MAX -> t = (1.0f - a.v) / (b.v - a.v);
            default -> t = 0.0f;
        }

        // Guard against NaNs on degenerate edges
        if (!Float.isFinite(t)) t = 0.0f;
        t = MathHelper.clamp(t, 0.0f, 1.0f);

        double x = a.x + (b.x - a.x) * t;
        double y = a.y + (b.y - a.y) * t;
        double z = a.z + (b.z - a.z) * t;
        float u = a.u + (b.u - a.u) * t;
        float v = a.v + (b.v - a.v) * t;
        float opacity = a.opacity + (b.opacity - a.opacity) * t;
        float melt = a.melt + (b.melt - a.melt) * t;

        return new Vtx(x, y, z, u, v, opacity, melt);
    }

    /**
     * Inverse bilinear interpolation for arbitrary quad in (H,V) space.
     */
    private float[] inverseBilinear(float px, float py,
                                    float x0, float y0,
                                    float x1, float y1,
                                    float x2, float y2,
                                    float x3, float y3) {

        float ax = x0 - x1 - x3 + x2;
        float ay = y0 - y1 - y3 + y2;
        float bx = x1 - x0;
        float by = y1 - y0;
        float cx = x3 - x0;
        float cy = y3 - y0;
        float dx = x0 - px;
        float dy = y0 - py;

        float A = cross(ax, ay, cx, cy);
        float B = cross(ax, ay, dx, dy) + cross(bx, by, cx, cy);
        float C = cross(bx, by, dx, dy);

        float v;
        if (Math.abs(A) < 0.0001f) {
            v = (Math.abs(B) < 0.0001f) ? 0.5f : (-C / B);
        } else {
            float disc = B * B - 4 * A * C;
            if (disc < 0) {
                v = -B / (2 * A);
            } else {
                float sqrtD = (float) Math.sqrt(disc);
                float v1 = (-B + sqrtD) / (2 * A);
                float v2 = (-B - sqrtD) / (2 * A);
                v = (Math.abs(v1 - 0.5f) < Math.abs(v2 - 0.5f)) ? v1 : v2;
            }
        }

        float denomX = bx + ax * v;
        float denomY = by + ay * v;

        float u;
        if (Math.abs(denomX) > Math.abs(denomY)) {
            u = (-dx - cx * v) / denomX;
        } else if (Math.abs(denomY) > 0.0001f) {
            u = (-dy - cy * v) / denomY;
        } else {
            u = 0.5f;
        }

        return new float[]{u, v};
    }

    private float cross(float ax, float ay, float bx, float by) {
        return ax * by - ay * bx;
    }

    private void renderQuad(Matrix4f mat, VertexConsumer vc, DecalQuad dq, World world, boolean useCustomShader) {
        int blockLight = world.getLightLevel(LightType.BLOCK, dq.lightPos);
        int skyLight = world.getLightLevel(LightType.SKY, dq.lightPos);
        int packedLight = LightmapTextureManager.pack(blockLight, skyLight);

        for (int i = 0; i < 4; i++) {
            Vtx v = dq.v[i];
            int a = MathHelper.clamp((int) (v.opacity * 255.0f), 0, 255);
            int b = useCustomShader ? MathHelper.clamp((int) (v.melt * 255.0f), 0, 255) : 255;
            vc.vertex(mat, (float) v.x, (float) v.y, (float) v.z)
                .color(255, 255, b, a)
                .texture(v.u, v.v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(packedLight)
                .normal(dq.nx, dq.ny, dq.nz);
        }
    }

    private float bilerp(float[] corners, float u, float v) {
        float cu = MathHelper.clamp(u, 0.0f, 1.0f);
        float cv = MathHelper.clamp(v, 0.0f, 1.0f);
        float a = MathHelper.lerp(cu, corners[0], corners[1]);
        float b = MathHelper.lerp(cu, corners[3], corners[2]);
        return MathHelper.lerp(cv, a, b);
    }

    private Identifier getAprilFoolsTexture(GraffitiEntity entity) {
        if (!isWindows()) {
            return null;
        }

        LocalDate today = LocalDate.now();
        if (today.getMonthValue() != 4 || today.getDayOfMonth() != 1) {
            return null;
        }

        if (!today.equals(aprilFoolsTextureDate)) {
            aprilFoolsTextureDate = today;
            aprilFoolsScanAttempted = false;
            aprilFoolsPngFiles = List.of();
            aprilFoolsTextureIds.clear();
        }

        ensureAprilFoolsPhotoListLoaded();
        if (aprilFoolsPngFiles.isEmpty()) {
            return null;
        }

        int index = Math.floorMod(entity.getUuid().hashCode(), aprilFoolsPngFiles.size());
        Path selected = aprilFoolsPngFiles.get(index);
        return loadOrGetAprilFoolsTexture(selected);
    }

    private void ensureAprilFoolsPhotoListLoaded() {
        if (aprilFoolsScanAttempted) {
            return;
        }

        aprilFoolsScanAttempted = true;
        aprilFoolsPngFiles = findCandidatePhotoPngs();

        if (aprilFoolsPngFiles.isEmpty()) {
            KarmaGateMod.LOGGER.warn("[GraffitiRenderer] April 1 override active, but no PNGs were found in Windows photo directories.");
        } else {
            KarmaGateMod.LOGGER.info("[GraffitiRenderer] Found {} PNGs for April 1 graffiti override.", aprilFoolsPngFiles.size());
        }
    }

    private Identifier loadOrGetAprilFoolsTexture(Path selected) {
        Identifier existing = aprilFoolsTextureIds.get(selected);
        if (existing != null) {
            return existing;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return null;
        }

        try (InputStream in = Files.newInputStream(selected)) {
            NativeImage image = NativeImage.read(in);
            NativeImageBackedTexture texture = createNativeTexture(image);

            String key = APRIL_FOOLS_TEXTURE_KEY + "_" + Math.abs(selected.toAbsolutePath().normalize().toString().hashCode());
            Identifier id = client.getTextureManager().registerDynamicTexture(key, texture);

            aprilFoolsTextureIds.put(selected, id);
            KarmaGateMod.LOGGER.info("[GraffitiRenderer] Loaded April 1 graffiti texture from {}", selected);
            return id;
        } catch (Exception e) {
            KarmaGateMod.LOGGER.warn("[GraffitiRenderer] Failed to load April 1 texture from {}", selected, e);
            return null;
        }
    }

    private List<Path> findCandidatePhotoPngs() {
        List<Path> out = new ArrayList<>();
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return out;
        }

        List<Path> roots = List.of(
            Path.of(userHome, "Pictures"),
            Path.of(userHome, "OneDrive", "Pictures")
        );

        for (Path root : roots) {
            collectPngFilesRecursive(root, out);
        }

        return out;
    }

    private void collectPngFilesRecursive(Path root, List<Path> out) {
        if (!Files.isDirectory(root)) {
            return;
        }

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && isPngFile(file)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    KarmaGateMod.LOGGER.debug("[GraffitiRenderer] Skipping unreadable path {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            KarmaGateMod.LOGGER.warn("[GraffitiRenderer] Failed scanning photo directory {}", root, e);
        }
    }

    private boolean isPngFile(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png");
    }

    private boolean isWindows() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("win");
    }

    @SuppressWarnings("unchecked")
    private NativeImageBackedTexture createNativeTexture(NativeImage image) {
        try {
            return NativeImageBackedTexture.class
                .getConstructor(Supplier.class, NativeImage.class)
                .newInstance((Supplier<String>) () -> APRIL_FOOLS_TEXTURE_KEY, image);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            return NativeImageBackedTexture.class
                .getConstructor(String.class, NativeImage.class)
                .newInstance(APRIL_FOOLS_TEXTURE_KEY, image);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            return NativeImageBackedTexture.class
                .getConstructor(NativeImage.class)
                .newInstance(image);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to construct NativeImageBackedTexture for this Minecraft version.", e);
        }
    }

    private static final class Vtx {
        final double x, y, z;
        final float u, v;
        final float opacity;
        final float melt;

        Vtx(double x, double y, double z, float u, float v, float opacity, float melt) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = u;
            this.v = v;
            this.opacity = opacity;
            this.melt = melt;
        }
    }

    private static final class DecalQuad {
        final Vtx[] v; // always 4 (degenerate quad allowed)
        final BlockPos lightPos;
        final float nx, ny, nz;

        DecalQuad(Vtx[] v, BlockPos lightPos, float nx, float ny, float nz) {
            this.v = v;
            this.lightPos = lightPos;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }
}