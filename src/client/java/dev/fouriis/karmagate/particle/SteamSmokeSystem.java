package dev.fouriis.karmagate.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.ShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.Shaders;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 3D port of the isolated RegionGateSteamDemo SteamSimulation and
 * SteamRenderer. Simulation remains linked-puff based and rendering emits the
 * same stretched bridge plus circular cap for every puff.
 */
@Environment(EnvType.CLIENT)
public final class SteamSmokeSystem {
    // Steam is composed in libMod's pre-capture pass. The explicit recapture
    // lets it preserve earlier translucent/custom draws, while the automatic
    // pass-boundary capture then exposes the completed steam to every normal
    // grabtex shader (heat distortion, ripple effects, and so on).
    private static final int STEAM_RENDER_PRIORITY = 950;
    private static final float RAIN_WORLD_PIXELS_PER_BLOCK = 20.0f;
    private static final int SIMULATION_STEPS_PER_MINECRAFT_TICK = 2;

    private static final double INITIAL_SPEED = 1.0 / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final double WANDER_ACCELERATION = 1.8 / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final double UPWARD_ACCELERATION = 2.8 / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final double CONFINE_HALF_WIDTH = 50.0 / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final double CONFINE_HEIGHT = 420.0 / RAIN_WORLD_PIXELS_PER_BLOCK;

    private static final float STEAM_RED = 190.0f / 255.0f;
    private static final float STEAM_GREEN = 196.0f / 255.0f;
    private static final float STEAM_BLUE = 200.0f / 255.0f;
    private static final int UNUSED_LIGHT = 0x00F000F0;

    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise.png");
    private static final Identifier GRAB_TEXTURE =
            Identifier.of("librainworldmc", "grabtex");

    private static final Random RANDOM = new Random();
    private static final List<SmokePuff> PUFFS = new ArrayList<>();
    private static final Map<BlockPos, SourceTrail> SOURCE_TRAILS = new HashMap<>();

    private static ClientWorld simulationWorld;
    private static Identifier rainWorldWhiteTexture;
    private static Identifier isolatedDepthTexture;
    private static boolean renderQueued;

    private SteamSmokeSystem() {
    }

    public static void emit(
            ClientWorld world,
            double x,
            double y,
            double z,
            double sourceOffsetX,
            double rawIntensity,
            double sourceOffsetZ
    ) {
        ensureWorld(world);

        float intensity = MathHelper.clamp((float) rawIntensity, 0.0f, 1.0f);
        if (intensity <= 0.0f) {
            return;
        }

        double sourceCenterX = x + sourceOffsetX;
        double sourceCenterZ = z + sourceOffsetZ;
        double sourceFloorY = Math.floor(y - 1.0);
        BlockPos source = BlockPos.ofFloored(sourceCenterX, sourceFloorY, sourceCenterZ);

        float lifeTime = lerp(60.0f, 180.0f, RANDOM.nextFloat() * intensity);
        Vector3d position = new Vector3d(x, y, z);
        Vector3d initialDirection = randomUnitVector();
        Vector3d linger = new Vector3d(position).fma(INITIAL_SPEED, randomUnitVector());
        float radius = lerp(108.0f, 286.0f, RANDOM.nextFloat())
                * lerp(0.5f, 1.0f, intensity)
                / RAIN_WORLD_PIXELS_PER_BLOCK;

        SmokePuff puff = new SmokePuff(
                position,
                new Vector3d(initialDirection).mul(INITIAL_SPEED),
                linger,
                lifeTime,
                radius,
                intensity,
                RANDOM.nextFloat() * 100.0f / lifeTime,
                randomUnitVector(),
                sourceCenterX,
                sourceFloorY,
                sourceCenterZ
        );

        long gameTick = world.getTime();
        SourceTrail trail = SOURCE_TRAILS.computeIfAbsent(source.toImmutable(), ignored -> new SourceTrail());
        if (trail.last != null
                && trail.last.life > 0.0f
                && PUFFS.size() > 2
                && gameTick - trail.lastEmissionTick <= 1L) {
            puff.next = trail.last;
        }
        trail.last = puff;
        trail.lastEmissionTick = gameTick;
        PUFFS.add(puff);
    }

    public static void tick(MinecraftClient client) {
        // Also recovers if libMod clears its late-render queue during a shader
        // pack or resource reload before our queued call can execute.
        renderQueued = false;
        ClientWorld world = client.world;
        if (world == null) {
            clear();
            return;
        }
        ensureWorld(world);
        if (client.isPaused() || PUFFS.isEmpty()) {
            return;
        }

        for (SmokePuff puff : PUFFS) {
            puff.captureRenderState();
        }
        for (int i = 0; i < SIMULATION_STEPS_PER_MINECRAFT_TICK; i++) {
            for (SmokePuff puff : PUFFS) {
                puff.update();
            }
        }

        PUFFS.removeIf(puff -> puff.life <= 0.0f && puff.previousLife <= 0.0f);
        Iterator<SourceTrail> trails = SOURCE_TRAILS.values().iterator();
        while (trails.hasNext()) {
            SourceTrail trail = trails.next();
            if (trail.last == null || trail.last.life <= 0.0f) {
                trails.remove();
            }
        }
    }

    public static void queueRender(float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (renderQueued || world == null || world != simulationWorld || PUFFS.isEmpty()) {
            return;
        }

        renderQueued = true;
        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(camera -> {
                    try {
                        renderNow(camera, tickDelta);
                    } finally {
                        renderQueued = false;
                    }
                }, true),
                STEAM_RENDER_PRIORITY);
    }

    private static void renderNow(Camera camera, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || world != simulationWorld || PUFFS.isEmpty()) {
            return;
        }

        // libMod's late-world renderer deliberately uses an identity model-view
        // matrix. Match RenderUtils billboards by transforming camera-relative
        // world coordinates into view space in the submitted vertices.
        Matrix4f viewRotation = new Matrix4f()
                .rotation(camera.getRotation())
                .transpose();

        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT
        );
        int quadCount = 0;
        for (SmokePuff puff : PUFFS) {
            if (puff.previousLife <= 0.0f && puff.life <= 0.0f) {
                continue;
            }

            float life = lerp(puff.previousLife, puff.life, tickDelta);
            float stretched = lerp(puff.previousStretched, puff.stretched, tickDelta);
            Vector3d position = interpolate(puff.previousPosition, puff.position, tickDelta);
            Vector3d linger = interpolate(puff.previousLingerPosition, puff.lingerPosition, tickDelta);
            if (puff.next != null && puff.next.life > 0.0f) {
                linger = interpolate(
                        puff.next.previousPosition,
                        puff.next.position,
                        tickDelta
                );
            }

            float bodyRadius = radius(puff, 0, life, stretched);
            float endRadius = radius(puff, 2, life, stretched);
            if (appendBridge(buffer, camera, viewRotation, position, linger, bodyRadius, endRadius, life)) {
                quadCount++;
            }

            float capRadius = radius(puff, 1, life, stretched);
            appendCap(buffer, camera, viewRotation, position, capRadius, life);
            quadCount++;
        }

        var builtBuffer = buffer.endNullable();
        if (builtBuffer == null || quadCount == 0) {
            if (builtBuffer != null) {
                builtBuffer.close();
            }
            return;
        }

        Identifier white = getRainWorldWhiteTexture();
        Identifier levelDepth = getIsolatedDepthTexture(client);
        if (white == null || levelDepth == null) {
            builtBuffer.close();
            return;
        }

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        boolean shaderApplied = false;
        try {
            if (Shaders.STEAM != null && Shaders.STEAM.getProgram() != null) {
                // This is the same explicit program-binding path used by the
                // Iris-compatible heat-coil distortion effect.
                CoreShaderRenderer.bindShader$Steam(
                        0.0f,
                        levelDepth,
                        NOISE_TEXTURE,
                        GRAB_TEXTURE,
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        false
                );

                // The demo evaluates its depth/noise inputs in full-screen
                // coordinates. Every cap and bridge retains its own local UVs
                // solely for the shader's radial mask.
                ShaderRenderer.setUniformF(
                        Shaders.STEAM.getProgram(),
                        "u_spriteRect",
                        0.0f, 0.0f, 1.0f, 1.0f
                );
                float rain = (world.getTime() + tickDelta) / (20.0f * 5.0f);
                ShaderRenderer.setUniformF(Shaders.STEAM.getProgram(), "u_RAIN", rain);
                shaderApplied = true;
            }
        } catch (RuntimeException ignored) {
            // Shader programs can be momentarily unavailable during a resource
            // reload. The raw cap/bridge geometry remains visible for that frame.
        }

        if (!shaderApplied) {
            RenderSystem.setShader(GameRenderer::getParticleProgram);
        }
        RenderSystem.setShaderTexture(0, white);
        BufferRenderer.drawWithGlobalProgram(builtBuffer);

        RenderSystem.setShader(GameRenderer::getParticleProgram);
        RenderSystem.setShaderTexture(0, SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static boolean appendBridge(
            VertexConsumer vertices,
            Camera camera,
            Matrix4f viewRotation,
            Vector3d position,
            Vector3d linger,
            float width,
            float endRadius,
            float life
    ) {
        Vector3d axis = new Vector3d(linger).sub(position);
        double distance = axis.length();
        if (distance < 1.0e-5) {
            return false;
        }
        axis.div(distance);

        Vector3d center = new Vector3d(position).add(linger).mul(0.5);
        Vec3d cameraPos = camera.getPos();
        Vector3d toCamera = new Vector3d(
                cameraPos.x - center.x,
                cameraPos.y - center.y,
                cameraPos.z - center.z
        );
        Vector3d side = new Vector3d(axis).cross(toCamera);
        if (side.lengthSquared() < 1.0e-8) {
            Vector3f cameraRight = new Vector3f(1.0f, 0.0f, 0.0f)
                    .rotate(new Quaternionf(camera.getRotation()));
            side.set(cameraRight.x, cameraRight.y, cameraRight.z);
        }
        side.normalize().mul(width * 0.5);
        Vector3d along = new Vector3d(axis).mul((distance + endRadius) * 0.5);

        emitVertex(vertices, viewRotation, cameraPos, new Vector3d(center).add(side).sub(along), 1.0f, 1.0f, life);
        emitVertex(vertices, viewRotation, cameraPos, new Vector3d(center).add(side).add(along), 1.0f, 0.0f, life);
        emitVertex(vertices, viewRotation, cameraPos, new Vector3d(center).sub(side).add(along), 0.0f, 0.0f, life);
        emitVertex(vertices, viewRotation, cameraPos, new Vector3d(center).sub(side).sub(along), 0.0f, 1.0f, life);
        return true;
    }

    private static void appendCap(
            VertexConsumer vertices,
            Camera camera,
            Matrix4f viewRotation,
            Vector3d position,
            float diameter,
            float life
    ) {
        Vec3d cameraPos = camera.getPos();
        float centerX = (float) (position.x - cameraPos.x);
        float centerY = (float) (position.y - cameraPos.y);
        float centerZ = (float) (position.z - cameraPos.z);
        float halfSize = diameter * 0.5f;
        Quaternionf rotation = new Quaternionf(camera.getRotation());

        Vector3f bottomRight = billboardCorner(1.0f, -1.0f, halfSize, rotation, centerX, centerY, centerZ);
        Vector3f topRight = billboardCorner(1.0f, 1.0f, halfSize, rotation, centerX, centerY, centerZ);
        Vector3f topLeft = billboardCorner(-1.0f, 1.0f, halfSize, rotation, centerX, centerY, centerZ);
        Vector3f bottomLeft = billboardCorner(-1.0f, -1.0f, halfSize, rotation, centerX, centerY, centerZ);

        emitRelativeVertex(vertices, viewRotation, bottomRight, 1.0f, 1.0f, life);
        emitRelativeVertex(vertices, viewRotation, topRight, 1.0f, 0.0f, life);
        emitRelativeVertex(vertices, viewRotation, topLeft, 0.0f, 0.0f, life);
        emitRelativeVertex(vertices, viewRotation, bottomLeft, 0.0f, 1.0f, life);
    }

    private static Vector3f billboardCorner(
            float x,
            float y,
            float halfSize,
            Quaternionf rotation,
            float centerX,
            float centerY,
            float centerZ
    ) {
        return new Vector3f(x, y, 0.0f)
                .rotate(rotation)
                .mul(halfSize)
                .add(centerX, centerY, centerZ);
    }

    private static void emitVertex(
            VertexConsumer vertices,
            Matrix4f viewRotation,
            Vec3d camera,
            Vector3d worldPosition,
            float u,
            float v,
            float alpha
    ) {
        vertices.vertex(
                        viewRotation,
                        (float) (worldPosition.x - camera.x),
                        (float) (worldPosition.y - camera.y),
                        (float) (worldPosition.z - camera.z)
                )
                .color(STEAM_RED, STEAM_GREEN, STEAM_BLUE, alpha)
                .texture(u, v)
                .light(UNUSED_LIGHT);
    }

    private static void emitRelativeVertex(
            VertexConsumer vertices,
            Matrix4f viewRotation,
            Vector3f position,
            float u,
            float v,
            float alpha
    ) {
        vertices.vertex(viewRotation, position.x, position.y, position.z)
                .color(STEAM_RED, STEAM_GREEN, STEAM_BLUE, alpha)
                .texture(u, v)
                .light(UNUSED_LIGHT);
    }

    private static float radius(SmokePuff puff, int type, float life, float stretched) {
        float shape = (float) Math.pow(Math.max(
                0.0f,
                lerp((float) Math.sin(life * Math.PI), 1.0f - life, 0.7f)
        ), 0.8f);
        if (type == 0) {
            return lerp(4.0f / RAIN_WORLD_PIXELS_PER_BLOCK, puff.radius, shape + stretched);
        }
        if (type == 1) {
            return 1.5f * lerp(2.0f / RAIN_WORLD_PIXELS_PER_BLOCK, puff.radius, shape);
        }
        return lerp(4.0f / RAIN_WORLD_PIXELS_PER_BLOCK, puff.radius, shape);
    }

    private static Identifier getRainWorldWhiteTexture() {
        if (rainWorldWhiteTexture != null) {
            return rainWorldWhiteTexture;
        }
        try {
            FAtlasElement white = LibrainworldmcClient.getAtlasManager()
                    .getElementWithName("Futile_White");
            if (white != null) {
                rainWorldWhiteTexture = white.textureIdentifier;
            }
        } catch (IllegalStateException ignored) {
            // libMod has not completed atlas initialization yet.
        }
        return rainWorldWhiteTexture;
    }

    private static Identifier getIsolatedDepthTexture(MinecraftClient client) {
        if (isolatedDepthTexture != null) {
            return isolatedDepthTexture;
        }

        // Steam.shader stores depth as the red byte modulo 30. A value of six
        // exactly reproduces the demo's default 6/30 foreground clamp.
        NativeImage image = new NativeImage(1, 1, false);
        image.setColor(0, 0, 0xFF060606);
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        isolatedDepthTexture = client.getTextureManager()
                .registerDynamicTexture("karma_gate_steam_depth", texture);
        return isolatedDepthTexture;
    }

    private static Vector3d interpolate(Vector3d previous, Vector3d current, float delta) {
        return new Vector3d(
                lerp(previous.x, current.x, delta),
                lerp(previous.y, current.y, delta),
                lerp(previous.z, current.z, delta)
        );
    }

    private static Vector3d randomUnitVector() {
        double y = RANDOM.nextDouble() * 2.0 - 1.0;
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double horizontal = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        return new Vector3d(
                Math.cos(angle) * horizontal,
                y,
                Math.sin(angle) * horizontal
        );
    }

    private static void turnRandomly(Vector3d direction) {
        double turn = (RANDOM.nextDouble() * 2.0 - 1.0)
                * 50.0 * MathHelper.RADIANS_PER_DEGREE;
        Vector3d tangent = randomUnitVector();
        tangent.sub(new Vector3d(direction).mul(tangent.dot(direction)));
        if (tangent.lengthSquared() < 1.0e-8) {
            tangent.set(direction.y, -direction.x, 0.0);
            if (tangent.lengthSquared() < 1.0e-8) {
                tangent.set(1.0, 0.0, 0.0);
            }
        }
        tangent.normalize();
        direction.mul(Math.cos(turn)).add(tangent.mul(Math.sin(turn))).normalize();
    }

    private static void ensureWorld(ClientWorld world) {
        if (simulationWorld != world) {
            clear();
            simulationWorld = world;
        }
    }

    private static void clear() {
        PUFFS.clear();
        SOURCE_TRAILS.clear();
        renderQueued = false;
        simulationWorld = null;
    }

    private static float inverseLerp(float a, float b, double value) {
        return MathHelper.clamp((float) ((value - a) / (b - a)), 0.0f, 1.0f);
    }

    private static float lerp(float a, float b, float delta) {
        return a + (b - a) * delta;
    }

    private static double lerp(double a, double b, double delta) {
        return a + (b - a) * delta;
    }

    private static final class SourceTrail {
        private SmokePuff last;
        private long lastEmissionTick = Long.MIN_VALUE;
    }

    private static final class SmokePuff {
        private final Vector3d position;
        private final Vector3d previousPosition;
        private final Vector3d velocity;
        private final Vector3d lingerPosition;
        private final Vector3d previousLingerPosition;
        private final Vector3d wanderDirection;
        private final float lifeTime;
        private final float radius;
        private final float intensity;
        private final float upForce;
        private final double confineMinX;
        private final double confineMaxX;
        private final double confineBottom;
        private final double confineTop;
        private final double confineMinZ;
        private final double confineMaxZ;

        private SmokePuff next;
        private float life = 1.0f;
        private float previousLife = 1.0f;
        private float stretched;
        private float previousStretched;

        private SmokePuff(
                Vector3d position,
                Vector3d velocity,
                Vector3d lingerPosition,
                float lifeTime,
                float radius,
                float intensity,
                float upForce,
                Vector3d wanderDirection,
                double sourceCenterX,
                double sourceFloorY,
                double sourceCenterZ
        ) {
            this.position = position;
            this.previousPosition = new Vector3d(position);
            this.velocity = velocity;
            this.lingerPosition = lingerPosition;
            this.previousLingerPosition = new Vector3d(lingerPosition);
            this.lifeTime = lifeTime;
            this.radius = radius;
            this.intensity = intensity;
            this.upForce = upForce;
            this.wanderDirection = wanderDirection;
            this.confineMinX = sourceCenterX - CONFINE_HALF_WIDTH;
            this.confineMaxX = sourceCenterX + CONFINE_HALF_WIDTH;
            this.confineBottom = sourceFloorY;
            this.confineTop = sourceFloorY + CONFINE_HEIGHT;
            this.confineMinZ = sourceCenterZ - CONFINE_HALF_WIDTH;
            this.confineMaxZ = sourceCenterZ + CONFINE_HALF_WIDTH;
        }

        private void captureRenderState() {
            this.previousLife = this.life;
            this.previousPosition.set(this.position);
            this.previousLingerPosition.set(this.lingerPosition);
            this.previousStretched = this.stretched;
        }

        private void update() {
            this.position.add(this.velocity);
            this.life = Math.max(0.0f, this.life - 1.0f / this.lifeTime);

            if (this.next != null && this.next.life > 0.0f) {
                double midpointX = (this.velocity.x + this.next.velocity.x) * 0.5;
                double midpointY = (this.velocity.y + this.next.velocity.y) * 0.5;
                double midpointZ = (this.velocity.z + this.next.velocity.z) * 0.5;
                this.velocity.set(
                        lerp(this.velocity.x, midpointX, 0.4),
                        lerp(this.velocity.y, midpointY, 0.4),
                        lerp(this.velocity.z, midpointZ, 0.4)
                );
                this.next.velocity.set(
                        lerp(this.next.velocity.x, midpointX, 0.7),
                        lerp(this.next.velocity.y, midpointY, 0.7),
                        lerp(this.next.velocity.z, midpointZ, 0.7)
                );
                this.lingerPosition.set(this.next.position);
            } else {
                this.next = null;
            }

            this.stretched = inverseLerp(
                    60.0f / RAIN_WORLD_PIXELS_PER_BLOCK,
                    200.0f / RAIN_WORLD_PIXELS_PER_BLOCK,
                    this.position.distance(this.lingerPosition)
            );

            turnRandomly(this.wanderDirection);
            this.velocity.mul(0.8);
            this.velocity.fma(
                    WANDER_ACCELERATION * this.intensity * this.life,
                    this.wanderDirection
            );
            this.velocity.y += UPWARD_ACCELERATION * this.intensity * this.upForce;

            this.position.x = MathHelper.clamp(this.position.x, this.confineMinX, this.confineMaxX);
            this.position.y = MathHelper.clamp(this.position.y, this.confineBottom, this.confineTop);
            this.position.z = MathHelper.clamp(this.position.z, this.confineMinZ, this.confineMaxZ);
        }
    }
}
