package dev.fouriis.karmagate.entity.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.karmagate.HeatCoilBlock;
import dev.fouriis.karmagate.client.waterfall.WaterfallShaders;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.WaterfallBlockEntity;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.sound.SteamAudioController;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/** Rain World's WaterFall graphic adapted to a one-block crossed-plane volume. */
public class WaterfallBlockRenderer<T extends WaterfallBlockEntity> implements BlockEntityRenderer<T> {
    private static final float HALF_EXTENT = 0.5f;
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final Identifier WATER_FLOW_TEXTURE =
            Identifier.ofVanilla("textures/block/water_flow.png");
    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise-hq.png");
    private static final Map<WaterfallBlockEntity, BubbleSystem> BUBBLE_SYSTEMS = new WeakHashMap<>();
    private static final Map<WaterfallBlockEntity, WaterDripSystem> DRIP_SYSTEMS = new WeakHashMap<>();
    private static FAtlasElement bubbleSprite;

    public WaterfallBlockRenderer(BlockEntityRendererFactory.Context ctx) {
    }

    @Override
    public void render(T be, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        World world = be.getWorld();
        if (world == null || world.getBlockEntity(be.getPos().up()) instanceof WaterfallBlockEntity) return;

        BlockPos pos = be.getPos();
        float blocksDown = WaterfallBlockEntity.measureFallDistance(world, pos, WaterfallBlockEntity.MAX_BLOCKS_DOWN);
        be.ensureClientVisualState(-blocksDown);
        handleHeatSteam(be, tickDelta, blocksDown);

        BubbleSystem bubbles = BUBBLE_SYSTEMS.computeIfAbsent(be, ignored -> new BubbleSystem(pos));
        bubbles.update(be, blocksDown);
        WaterDripSystem drips = DRIP_SYSTEMS.computeIfAbsent(be, ignored -> new WaterDripSystem(pos));
        drips.update(be, blocksDown);
        float topY = be.getInterpolatedTopLocalY(tickDelta);
        float bottomY = be.getInterpolatedBottomLocalY(tickDelta);
        float density = MathHelper.clamp(be.getVisualDensity(tickDelta), 0.0f, 1.0f);
        boolean drawPlanes = density > 0.001f && bottomY < topY - 0.001f;
        if (!drawPlanes && !bubbles.hasVisibleBubbles() && !drips.hasVisibleDrips()) return;

        float lengthFraction = Math.min((topY - bottomY) / Math.max(blocksDown + 1.0f, 0.001f), 1.0f);
        float sourceReveal = inverseLerpClamped(1.0f, -blocksDown, topY);
        float strikeReveal = inverseLerpClamped(-blocksDown, 1.0f, bottomY);
        sourceReveal = MathHelper.lerp(1.0f - lengthFraction, (float) Math.pow(sourceReveal, 0.2), 1.0f);
        strikeReveal = MathHelper.lerp(1.0f - lengthFraction, (float) Math.pow(strikeReveal, 0.2), 1.0f);
        float sourceEdge = 1.0f / MathHelper.lerp(sourceReveal, 100.0f, 2.0f);
        float strikeEdge = 1.0f / MathHelper.lerp(strikeReveal, 100.0f, 2.0f);
        float rain = (world.getTime() + tickDelta) / 100.0f;
        int waterColor = BiomeColors.getWaterColor(world, pos);
        float waterRed = ((waterColor >> 16) & 0xFF) / 255.0f;
        float waterGreen = ((waterColor >> 8) & 0xFF) / 255.0f;
        float waterBlue = (waterColor & 0xFF) / 255.0f;
        int sourceX = pos.getX(), sourceY = pos.getY(), sourceZ = pos.getZ();

        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(camera -> {
            if (drawPlanes) {
                renderPlanes(camera, sourceX, sourceY, sourceZ, topY, bottomY,
                        density, sourceEdge, strikeEdge, rain,
                        waterRed, waterGreen, waterBlue);
            }
            renderBubbles(camera, bubbles, tickDelta, waterRed, waterGreen, waterBlue);
            renderWaterDrips(camera, drips, tickDelta, waterRed, waterGreen, waterBlue);
        }, false), 900);
    }

    private static void renderPlanes(Camera camera, int sourceX, int sourceY, int sourceZ,
                                     float topY, float bottomY, float density,
                                     float sourceEdge, float strikeEdge, float rain,
                                     float waterRed, float waterGreen, float waterBlue) {
        ShaderProgram program = WaterfallShaders.PROGRAM;
        if (program == null) return;

        Vec3d cameraPos = camera.getPos();
        float centerX = (float) (sourceX + 0.5 - cameraPos.x);
        float centerZ = (float) (sourceZ + 0.5 - cameraPos.z);
        float top = (float) (sourceY + topY - cameraPos.y);
        float bottom = (float) (sourceY + bottomY - cameraPos.y);
        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);
        emitPlane(buffer, centerX - HALF_EXTENT, centerZ - HALF_EXTENT,
                centerX + HALF_EXTENT, centerZ + HALF_EXTENT,
                top, bottom, density, sourceEdge, strikeEdge);
        emitPlane(buffer, centerX - HALF_EXTENT, centerZ + HALF_EXTENT,
                centerX + HALF_EXTENT, centerZ - HALF_EXTENT,
                top, bottom, density, sourceEdge, strikeEdge);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(() -> program);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShaderTexture(0, WATER_FLOW_TEXTURE);
        RenderSystem.setShaderTexture(1, NOISE_TEXTURE);
        MinecraftClient client = MinecraftClient.getInstance();
        program.addSampler("Sampler0", client.getTextureManager().getTexture(WATER_FLOW_TEXTURE));
        program.addSampler("Sampler1", client.getTextureManager().getTexture(NOISE_TEXTURE));
        setUniform(program, "uViewMat", new Matrix4f().rotation(camera.getRotation()).transpose());
        setUniform(program, "uCameraWorldPos", (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        setUniform(program, "uRain", rain);
        setUniform(program, "uSourceWorldY", sourceY + 1.0f);
        setUniform(program, "uNoiseFeatureBlocks", 48.0f / 20.0f);
        setUniform(program, "uBiomeWaterColor", waterRed, waterGreen, waterBlue);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void emitPlane(BufferBuilder buffer, float xA, float zA, float xB, float zB,
                                  float topY, float bottomY, float density,
                                  float sourceEdge, float strikeEdge) {
        buffer.vertex(xA, topY, zA).color(density, sourceEdge, strikeEdge, 1.0f)
                .texture(0.0f, 0.0f).light(FULL_BRIGHT);
        buffer.vertex(xB, topY, zB).color(density, sourceEdge, strikeEdge, 1.0f)
                .texture(1.0f, 0.0f).light(FULL_BRIGHT);
        buffer.vertex(xB, bottomY, zB).color(density, sourceEdge, strikeEdge, 1.0f)
                .texture(1.0f, 1.0f).light(FULL_BRIGHT);
        buffer.vertex(xA, bottomY, zA).color(density, sourceEdge, strikeEdge, 1.0f)
                .texture(0.0f, 1.0f).light(FULL_BRIGHT);
    }

    private static void renderBubbles(Camera camera, BubbleSystem system, float tickDelta,
                                      float waterRed, float waterGreen, float waterBlue) {
        FAtlasElement sprite = getBubbleSprite();
        if (sprite == null || sprite.textureIdentifier == null) return;
        Vec3d cameraPos = camera.getPos();
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f).rotate(camera.getRotation());
        ArrayList<VisibleBubble> visible = new ArrayList<>();
        for (WaterBubble bubble : system.bubbles) {
            if (!bubble.active) continue;
            float life = MathHelper.lerp(tickDelta, bubble.previousLife, bubble.life);
            if (life <= 0.0f) continue;
            Vector3d position = interpolate(bubble.previousPosition, bubble.position, tickDelta);
            float dx = (float) (position.x - cameraPos.x);
            float dy = (float) (position.y - cameraPos.y);
            float dz = (float) (position.z - cameraPos.z);
            float depth = dx * forward.x + dy * forward.y + dz * forward.z;
            if (depth > 0.01f) visible.add(new VisibleBubble(position, life, depth));
        }
        if (visible.isEmpty()) return;
        visible.sort(Comparator.comparingDouble(VisibleBubble::depth).reversed());

        Matrix4f projection = RenderSystem.getProjectionMatrix();
        int frameHeight = Math.max(1, MinecraftClient.getInstance().getWindow().getFramebufferHeight());
        Matrix4f view = new Matrix4f().rotation(camera.getRotation()).transpose();
        Quaternionf billboard = new Quaternionf(camera.getRotation());
        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);
        for (VisibleBubble item : visible) {
            float diameter = 16.0f * (2.0f * item.depth / (projection.m11() * frameHeight))
                    * MathHelper.sqrt(item.life);
            if (diameter > 0.0001f) {
                appendBubble(buffer, cameraPos, view, billboard, item.position, diameter * 0.5f,
                        waterRed, waterGreen, waterBlue);
            }
        }
        var built = buffer.endNullable();
        if (built == null) return;
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getParticleProgram);
        RenderSystem.setShaderTexture(0, sprite.textureIdentifier);
        BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * Renders the three-vertex streak used by Rain World's WaterDrip. The
     * triangle is turned about its direction to face the camera, preserving
     * the original 1.5-pixel half-width at the 20-pixels-per-block scale.
     */
    private static void renderWaterDrips(Camera camera, WaterDripSystem system, float tickDelta,
                                         float waterRed, float waterGreen, float waterBlue) {
        Vec3d cameraPos = camera.getPos();
        Vector3f cameraForward3f = new Vector3f(0.0f, 0.0f, -1.0f).rotate(camera.getRotation());
        Vector3d cameraForward = new Vector3d(cameraForward3f.x, cameraForward3f.y, cameraForward3f.z);
        ArrayList<VisibleDrip> visible = new ArrayList<>();
        for (FallingWaterDrip drip : system.drips) {
            if (!drip.active || drip.life <= 0.0f) continue;
            Vector3d head = interpolate(drip.previousRenderPosition, drip.position, tickDelta);
            Vector3d tail = interpolate(drip.previousRenderTail, drip.lastLastPosition, tickDelta);
            double depth = new Vector3d(head).sub(cameraPos.x, cameraPos.y, cameraPos.z).dot(cameraForward);
            if (depth > 0.01) {
                visible.add(new VisibleDrip(head, tail,
                        MathHelper.lerp(tickDelta, drip.previousShine, drip.shine), depth));
            }
        }
        if (visible.isEmpty()) return;
        visible.sort(Comparator.comparingDouble(VisibleDrip::depth).reversed());

        Matrix4f view = new Matrix4f().rotation(camera.getRotation()).transpose();
        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        for (VisibleDrip drip : visible) {
            Vector3d direction = new Vector3d(drip.head).sub(drip.tail);
            if (direction.lengthSquared() < 1.0e-8) direction.set(0.0, -0.02, 0.0);
            Vector3d side = new Vector3d(direction).cross(cameraForward);
            if (side.lengthSquared() < 1.0e-8) {
                Vector3f cameraRight = new Vector3f(1.0f, 0.0f, 0.0f).rotate(camera.getRotation());
                side.set(cameraRight.x, cameraRight.y, cameraRight.z);
            }
            side.normalize().mul(1.5 / 20.0);

            // The C# palette progresses from the water colour to white as
            // randomLightness rises. Keeping this full-bright is intentional:
            // the reference droplets remain legible in very dark rooms.
            float shine = MathHelper.clamp(drip.shine, 0.0f, 1.0f);
            float red = MathHelper.lerp(shine, waterRed * 0.85f, 1.0f);
            float green = MathHelper.lerp(shine, waterGreen * 0.85f, 1.0f);
            float blue = MathHelper.lerp(shine, waterBlue * 0.85f, 1.0f);
            dripVertex(buffer, view, cameraPos, new Vector3d(drip.head).add(side), red, green, blue);
            dripVertex(buffer, view, cameraPos, new Vector3d(drip.head).sub(side), red, green, blue);
            dripVertex(buffer, view, cameraPos, drip.tail, red, green, blue);
        }
        var built = buffer.endNullable();
        if (built == null) return;
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void dripVertex(VertexConsumer vertices, Matrix4f view, Vec3d camera,
                                   Vector3d position, float red, float green, float blue) {
        vertices.vertex(view,
                        (float) (position.x - camera.x),
                        (float) (position.y - camera.y),
                        (float) (position.z - camera.z))
                .color(red, green, blue, 1.0f);
    }

    private static void appendBubble(VertexConsumer vertices, Vec3d camera, Matrix4f view,
                                     Quaternionf rotation, Vector3d position, float halfSize,
                                     float waterRed, float waterGreen, float waterBlue) {
        float cx = (float) (position.x - camera.x);
        float cy = (float) (position.y - camera.y);
        float cz = (float) (position.z - camera.z);
        bubbleVertex(vertices, view, corner(1, -1, halfSize, rotation, cx, cy, cz), 1, 1,
                waterRed, waterGreen, waterBlue);
        bubbleVertex(vertices, view, corner(1, 1, halfSize, rotation, cx, cy, cz), 1, 0,
                waterRed, waterGreen, waterBlue);
        bubbleVertex(vertices, view, corner(-1, 1, halfSize, rotation, cx, cy, cz), 0, 0,
                waterRed, waterGreen, waterBlue);
        bubbleVertex(vertices, view, corner(-1, -1, halfSize, rotation, cx, cy, cz), 0, 1,
                waterRed, waterGreen, waterBlue);
    }

    private static Vector3f corner(float x, float y, float size, Quaternionf rotation,
                                   float cx, float cy, float cz) {
        return new Vector3f(x, y, 0.0f).rotate(rotation).mul(size).add(cx, cy, cz);
    }

    private static void bubbleVertex(VertexConsumer vertices, Matrix4f view,
                                     Vector3f position, float u, float v,
                                     float waterRed, float waterGreen, float waterBlue) {
        // Bubbles use the local biome tint, lifted toward white so the thin
        // LizardBubble5 outline remains readable over water and dark blocks.
        float red = MathHelper.lerp(0.35f, waterRed, 1.0f);
        float green = MathHelper.lerp(0.35f, waterGreen, 1.0f);
        float blue = MathHelper.lerp(0.35f, waterBlue, 1.0f);
        vertices.vertex(view, position.x, position.y, position.z)
                .color(red, green, blue, 1.0f).texture(u, v).light(FULL_BRIGHT);
    }

    private static FAtlasElement getBubbleSprite() {
        if (bubbleSprite != null) return bubbleSprite;
        try {
            bubbleSprite = LibrainworldmcClient.getAtlasManager().getElementWithName("LizardBubble5");
            if (bubbleSprite == null) {
                bubbleSprite = LibrainworldmcClient.getAtlasManager().getElementWithName("Futile_White_Circle");
            }
        } catch (IllegalStateException ignored) {
            // Atlas initialization/resource reload can briefly make it unavailable.
        }
        return bubbleSprite;
    }

    private static void handleHeatSteam(WaterfallBlockEntity be, float tickDelta, float blocksDown) {
        World world = be.getWorld();
        if (world == null) return;
        BlockPos source = be.getPos();
        double clientTime = world.getTime() + tickDelta;
        for (int i = 1; i <= (int) blocksDown + 1; i++) {
            BlockPos hitPos = source.down(i);
            BlockState state = world.getBlockState(hitPos);
            if (!(state.getBlock() instanceof HeatCoilBlock)) continue;
            BlockEntity hitEntity = world.getBlockEntity(hitPos);
            if (!(hitEntity instanceof HeatCoilBlockEntity coil)) continue;
            float heat = MathHelper.clamp(coil.getHeat(), 0.0f, 1.0f);
            float flow = be.getEffectiveFlow(clientTime, i - 0.5f);
            if (heat <= 0.01f || flow <= 0.05f) continue;
            SteamAudioController.get().onSteamBurst(hitPos, heat * flow);

            // Gate-managed puffs are rolled by KarmaGateController at the
            // original 40 Hz using heaterTarget, then synchronized through the
            // coil. Rolling here as well used the water-cooled current heat,
            // suppressed later puffs, and made output depend on render timing.
            if (coil.isGateManaged()) continue;

            if (!coil.beginClientSteamEmissionTick()) continue;
            for (int step = 0; step < 2; step++) {
                if (squared(world.random.nextFloat()) >= flow * 2.0f
                        || squared(world.random.nextFloat()) >= heat * 2.0f) continue;
                double centerX = hitPos.getX() + 0.5;
                double centerY = hitPos.getY() + 0.5;
                double centerZ = hitPos.getZ() + 0.5;
                double px = centerX + (world.random.nextDouble() * 2.0 - 1.0) * 0.75;
                double py = centerY + (world.random.nextDouble() * 2.0 - 1.0) * 0.5;
                double pz = centerZ + (world.random.nextDouble() * 2.0 - 1.0) * 0.75;
                world.addParticle(ModParticles.STEAM, px, py, pz,
                        centerX - px, Math.pow(heat, 0.75), centerZ - pz);
                coil.clientPulseCool(heat * world.random.nextFloat() * flow, 5);
            }
        }
    }

    @Override
    public boolean rendersOutsideBoundingBox(T blockEntity) { return true; }

    @Override
    public int getRenderDistance() { return 256; }

    private static float inverseLerpClamped(float a, float b, float value) {
        if (Math.abs(b - a) <= 1.0e-5f) return 0.0f;
        return MathHelper.clamp((value - a) / (b - a), 0.0f, 1.0f);
    }

    private static float squared(float value) { return value * value; }

    private static void setUniform(ShaderProgram program, String name, float value) {
        GlUniform uniform = program.getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void setUniform(ShaderProgram program, String name, float x, float y, float z) {
        GlUniform uniform = program.getUniform(name);
        if (uniform != null) uniform.set(x, y, z);
    }

    private static void setUniform(ShaderProgram program, String name, Matrix4f matrix) {
        GlUniform uniform = program.getUniform(name);
        if (uniform != null) uniform.set(matrix);
    }

    private static Vector3d interpolate(Vector3d previous, Vector3d current, float delta) {
        return new Vector3d(MathHelper.lerp(delta, previous.x, current.x),
                MathHelper.lerp(delta, previous.y, current.y),
                MathHelper.lerp(delta, previous.z, current.z));
    }

    private record VisibleBubble(Vector3d position, float life, float depth) { }

    private record VisibleDrip(Vector3d head, Vector3d tail, float shine, double depth) { }

    private record DripTerrain(BlockPos pos, List<Box> collisionBoxes, int emissionRolls) { }

    /** Client-side 3D port of WaterFall.hitTerrainTiles and WaterDrip. */
    private static final class WaterDripSystem {
        private static final int MAX_DRIPS = 512;
        private static final double GRAVITY_PER_SOURCE_STEP = 0.9 / 20.0;

        private final Random random;
        private final ArrayList<FallingWaterDrip> drips = new ArrayList<>();
        private long lastTick = Long.MIN_VALUE;

        private WaterDripSystem(BlockPos source) {
            random = new Random(source.asLong() ^ 0x5741544552445249L);
        }

        private void update(WaterfallBlockEntity waterfall, float blocksDown) {
            World world = waterfall.getWorld();
            if (world == null || lastTick == world.getTime()) return;
            for (FallingWaterDrip drip : drips) drip.captureRenderState();

            long now = world.getTime();
            int elapsed = lastTick == Long.MIN_VALUE
                    ? 1 : (int) MathHelper.clamp(now - lastTick, 1L, 20L);
            lastTick = now;
            List<DripTerrain> terrain = findDripTerrain(world, waterfall.getPos(), blocksDown);
            float flow = MathHelper.clamp(waterfall.getFlow(), 0.0f, 1.0f);

            // Rain World advances at 40 updates per second. Run two source
            // steps for every Minecraft client tick, as the bubble simulation does.
            for (int tick = 0; tick < elapsed; tick++) {
                for (int sourceStep = 0; sourceStep < 2; sourceStep++) {
                    for (FallingWaterDrip drip : drips) updateDrip(world, drip);
                    drips.removeIf(drip -> !drip.active);
                    if (flow > 0.0f) spawnDrips(waterfall, terrain, flow);
                }
            }
        }

        private List<DripTerrain> findDripTerrain(World world, BlockPos source, float blocksDown) {
            ArrayList<DripTerrain> result = new ArrayList<>();
            int scanLength = Math.min(WaterfallBlockEntity.MAX_BLOCKS_DOWN,
                    Math.max(1, MathHelper.ceil(blocksDown) + 1));
            for (int distance = 1; distance <= scanLength; distance++) {
                BlockPos pos = source.down(distance);
                BlockState state = world.getBlockState(pos);
                if (state.getBlock() instanceof HeatCoilBlock || state.isOpaqueFullCube(world, pos)) continue;
                VoxelShape shape = state.getCollisionShape(world, pos);
                if (shape.isEmpty()) continue;

                // WaterFall.cs only records a solid tile when the tile above
                // it is not solid. This makes a vertical glass stack emit at
                // its exposed top rather than once from every block.
                BlockPos abovePos = pos.up();
                if (!world.getBlockState(abovePos).getCollisionShape(world, abovePos).isEmpty()) continue;
                List<Box> boxes = shape.getBoundingBoxes();
                if (!boxes.isEmpty()) {
                    Box bounds = shape.getBoundingBox();
                    double horizontalArea = (bounds.maxX - bounds.minX) * (bounds.maxZ - bounds.minZ);
                    // The C# renderer samples a line. In 3D the water occupies
                    // an X/Z footprint, so a full-block surface gets two rolls
                    // per source step. Narrow panes retain one roll rather than
                    // producing the same volume as a full glass block.
                    int emissionRolls = Math.max(1, MathHelper.ceil((float) (horizontalArea * 2.0)));
                    result.add(new DripTerrain(pos.toImmutable(), boxes, emissionRolls));
                }
            }
            return result;
        }

        private void spawnDrips(WaterfallBlockEntity waterfall, List<DripTerrain> terrain, float flow) {
            if (drips.size() >= MAX_DRIPS) return;
            BlockPos source = waterfall.getPos();
            double top = source.getY() + waterfall.getInterpolatedTopLocalY(1.0f);
            double bottom = source.getY() + waterfall.getInterpolatedBottomLocalY(1.0f);
            for (DripTerrain hit : terrain) {
                if (drips.size() >= MAX_DRIPS) break;
                for (int roll = 0; roll < hit.emissionRolls && drips.size() < MAX_DRIPS; roll++) {
                    if (random.nextFloat() >= flow || random.nextFloat() >= 1.0f / 3.0f) continue;
                    Box box = hit.collisionBoxes.get(random.nextInt(hit.collisionBoxes.size()));
                    Vector3d position = new Vector3d(
                            hit.pos.getX() + lerp(box.minX, box.maxX, random.nextDouble()),
                            hit.pos.getY() + lerp(box.minY, box.maxY, random.nextDouble()),
                            hit.pos.getZ() + lerp(box.minZ, box.maxZ, random.nextDouble()));
                    if (position.y >= top || position.y <= bottom) continue;

                    double speed = random.nextDouble() * 7.0 / 20.0 * random.nextDouble() * flow;
                    Vector3d velocity = randomUnitVector().add(0.0, 1.0, 0.0).mul(speed);
                    drips.add(new FallingWaterDrip(position, velocity, hit.pos,
                            10 + random.nextInt(110), nextShine()));
                }
            }
        }

        private void updateDrip(World world, FallingWaterDrip drip) {
            drip.lastLastLastPosition.set(drip.lastLastPosition);
            drip.lastLastPosition.set(drip.lastPosition);
            drip.lastPosition.set(drip.position);
            drip.velocity.y -= GRAVITY_PER_SOURCE_STEP;
            drip.position.add(drip.velocity);
            drip.life -= 1.0f / drip.lifeTime;
            drip.previousShine = drip.shine;
            drip.shine = nextShine();
            if (drip.life <= 0.0f || drip.position.y < world.getBottomY() - 1.0) {
                drip.active = false;
                return;
            }

            BlockPos currentPos = BlockPos.ofFloored(drip.position.x, drip.position.y, drip.position.z);
            if (!world.getFluidState(currentPos).isEmpty()) {
                drip.active = false;
                return;
            }
            boolean insideTerrain = pointInsideCollision(world, drip.position);
            if (drip.mustExitTerrain) {
                if (!insideTerrain) drip.mustExitTerrain = false;
            } else if (insideTerrain) {
                drip.active = false;
            }
        }

        private boolean pointInsideCollision(World world, Vector3d point) {
            BlockPos pos = BlockPos.ofFloored(point.x, point.y, point.z);
            VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
            if (shape.isEmpty()) return false;
            double localX = point.x - pos.getX();
            double localY = point.y - pos.getY();
            double localZ = point.z - pos.getZ();
            for (Box box : shape.getBoundingBoxes()) {
                if (localX > box.minX && localX < box.maxX
                        && localY > box.minY && localY < box.maxY
                        && localZ > box.minZ && localZ < box.maxZ) return true;
            }
            return false;
        }

        private Vector3d randomUnitVector() {
            double y = lerp(-1.0, 1.0, random.nextDouble());
            double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double angle = random.nextDouble() * Math.PI * 2.0;
            return new Vector3d(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
        }

        private float nextShine() {
            return MathHelper.lerp(0.35f, random.nextFloat(), 1.0f);
        }

        private boolean hasVisibleDrips() {
            for (FallingWaterDrip drip : drips) {
                if (drip.active && drip.life > 0.0f) return true;
            }
            return false;
        }
    }

    private static final class FallingWaterDrip {
        private final Vector3d position;
        private final Vector3d previousRenderPosition;
        private final Vector3d previousRenderTail;
        private final Vector3d lastPosition;
        private final Vector3d lastLastPosition;
        private final Vector3d lastLastLastPosition;
        private final Vector3d velocity;
        @SuppressWarnings("unused")
        private final BlockPos originTerrain;
        private final float lifeTime;
        private float life = 1.0f;
        private float shine;
        private float previousShine;
        private boolean mustExitTerrain = true;
        private boolean active = true;

        private FallingWaterDrip(Vector3d position, Vector3d velocity, BlockPos originTerrain,
                                 float lifeTime, float shine) {
            this.position = new Vector3d(position);
            previousRenderPosition = new Vector3d(position);
            previousRenderTail = new Vector3d(position);
            lastPosition = new Vector3d(position);
            lastLastPosition = new Vector3d(position);
            lastLastLastPosition = new Vector3d(position);
            this.velocity = velocity;
            this.originTerrain = originTerrain;
            this.lifeTime = lifeTime;
            this.shine = this.previousShine = shine;
        }

        private void captureRenderState() {
            previousRenderPosition.set(position);
            previousRenderTail.set(lastLastPosition);
        }
    }

    private static final class BubbleSystem {
        private final Random random;
        private final ArrayList<WaterBubble> bubbles = new ArrayList<>();
        private long lastTick = Long.MIN_VALUE;

        private BubbleSystem(BlockPos source) { random = new Random(source.asLong() ^ 91827L); }

        private void update(WaterfallBlockEntity waterfall, float blocksDown) {
            World world = waterfall.getWorld();
            if (world == null || lastTick == world.getTime()) return;
            int wanted = (int) MathHelper.lerp(
                    MathHelper.clamp(waterfall.getFlow(), 0.0f, 1.0f), 5.0f, 10.0f);
            while (bubbles.size() < wanted) bubbles.add(new WaterBubble());
            while (bubbles.size() > wanted) bubbles.remove(bubbles.size() - 1);
            for (WaterBubble bubble : bubbles) bubble.captureRenderState();

            long now = world.getTime();
            int elapsed = lastTick == Long.MIN_VALUE ? 1 : (int) MathHelper.clamp(now - lastTick, 1L, 20L);
            lastTick = now;
            BlockPos source = waterfall.getPos();
            float strikeY = source.getY() - blocksDown;
            // Match WaterFall.WaterContact directly: bubbles begin once the
            // propagated lower endpoint is within ten RW pixels (half a block)
            // of the measured strike surface. getEffectiveFlow() describes a
            // point inside the column and is not a reliable endpoint-contact
            // test at fractional fluid heights.
            boolean contact = waterfall.getFlow() > 0.0f
                    && waterfall.getInterpolatedBottomLocalY(1.0f) <= -blocksDown + 0.5f;
            for (int tick = 0; tick < elapsed; tick++) {
                for (int step = 0; step < 2; step++) {
                    for (WaterBubble bubble : bubbles) updateBubble(bubble, source, strikeY, waterfall.getFlow(), contact);
                }
            }
        }

        private void updateBubble(WaterBubble bubble, BlockPos source, float strikeY,
                                  float flow, boolean contact) {
            bubble.position.add(bubble.velocity);
            bubble.velocity.mul(0.9);
            bubble.velocity.y += 0.2 / 20.0;
            bubble.life -= 1.0f / bubble.lifeTime;
            if (bubble.life <= 0.0f || bubble.position.y > strikeY + 0.5f) {
                resetBubble(bubble, source, strikeY, flow, contact);
            }
        }

        private void resetBubble(WaterBubble bubble, BlockPos source, float strikeY,
                                 float flow, boolean contact) {
            if (!contact) {
                bubble.position.set(-1000.0, 1000.0, -1000.0);
                bubble.previousPosition.set(bubble.position);
                bubble.velocity.zero();
                bubble.life = bubble.previousLife = 1.0f;
                bubble.lifeTime = 5.0f;
                bubble.active = false;
                return;
            }
            bubble.position.set(source.getX() + 0.5 + lerp(-0.5, 0.5, random.nextDouble()),
                    strikeY + 0.12,
                    source.getZ() + 0.5 + lerp(-0.5, 0.5, random.nextDouble()));
            float heading = random.nextFloat() * MathHelper.TAU;
            Vector3d lateral = new Vector3d(Math.cos(heading), 0.0, Math.sin(heading));
            float angle = (float) lerp(160.0, 200.0, random.nextDouble()) * MathHelper.RADIANS_PER_DEGREE;
            Vector3d direction = lateral.mul(Math.sin(angle)).add(0.0, Math.cos(angle), 0.0);
            direction.mul(random.nextDouble() * MathHelper.lerp(flow, 8.0f, 12.0f) / 20.0);
            direction.fma(random.nextDouble() * 2.0 / 20.0, randomUnitVector());
            bubble.velocity.set(direction);
            bubble.lifeTime = 5 + random.nextInt(15);
            bubble.life = bubble.previousLife = 1.0f;
            bubble.previousPosition.set(bubble.position);
            bubble.active = true;
        }

        private Vector3d randomUnitVector() {
            double y = lerp(-1.0, 1.0, random.nextDouble());
            double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double angle = random.nextDouble() * Math.PI * 2.0;
            return new Vector3d(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
        }

        private boolean hasVisibleBubbles() {
            for (WaterBubble bubble : bubbles) {
                if (bubble.active && bubble.life > 0.0f) return true;
            }
            return false;
        }
    }

    private static final class WaterBubble {
        private final Vector3d position = new Vector3d(-1000.0, 1000.0, -1000.0);
        private final Vector3d previousPosition = new Vector3d(position);
        private final Vector3d velocity = new Vector3d();
        private float life = 1.0f, previousLife = 1.0f, lifeTime = 5.0f;
        private boolean active;

        private void captureRenderState() {
            previousPosition.set(position);
            previousLife = life;
        }
    }

    private static double lerp(double a, double b, double delta) { return a + (b - a) * delta; }
}
