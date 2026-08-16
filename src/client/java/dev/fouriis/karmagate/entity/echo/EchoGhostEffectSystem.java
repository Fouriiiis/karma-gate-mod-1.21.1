package dev.fouriis.karmagate.entity.echo;

import com.mojang.blaze3d.systems.RenderSystem;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.ShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.Shaders;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 3-D port of GhostDistortionParticles3D. Each echo owns one fixed 20x20x20
 * temporary room volume. Far flakes are drawn into GrabTexture, the centered
 * GhostDistortion pass warps them and the world, and near flakes are then drawn
 * in front, matching the isolated demo's render order.
 */
public final class EchoGhostEffectSystem {
    private static final float PIXELS_PER_BLOCK = 20.0f;
    private static final float ROOM_HALF_EXTENT = 10.0f;
    private static final float GHOST_MODE = 0.85f;
    private static final float GHOST_SCALE = 0.75f;
    /** Compensates for Minecraft not applying Rain World's dark ghost palette first. */
    private static final float DISTORTION_OPACITY = 0.68f;
    private static final int ACTIVE_FLAKES = (int) (200.0f * GHOST_MODE * GHOST_MODE);
    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    private static final int EFFECT_PRIORITY = 1004;
    private static final float DISTORTION_MAX_HALF_SIZE =
            (933.0f * GHOST_SCALE / PIXELS_PER_BLOCK) * 0.5f;

    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise2.png");
    private static final Identifier GRAB_TEXTURE = Identifier.of("librainworldmc", "grabtex");

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static FAtlasElement[] pebbleElements;
    private static World activeWorld;

    private EchoGhostEffectSystem() {
    }

    /** Advances the original 40 Hz simulation twice for every Minecraft tick. */
    public static void tick(MinecraftClient client) {
        if (client.world == null) {
            clear();
            return;
        }
        if (activeWorld != client.world) {
            STATES.clear();
            activeWorld = client.world;
        }

        STATES.entrySet().removeIf(entry -> {
            State state = entry.getValue();
            if (!(client.world.getEntityById(state.entityId) instanceof EchoEntity echo)
                    || echo.isRemoved()) {
                return true;
            }
            state.tick();
            return false;
        });
    }

    public static void clear() {
        STATES.clear();
        activeWorld = null;
    }

    public static void queue(EchoEntity echo, float tickDelta) {
        if (echo.getWorld() == null || echo.isRemoved()) return;
        State state = STATES.computeIfAbsent(echo.getUuid(),
                id -> new State(echo.getId(), id));
        state.entityId = echo.getId();
        Vec3d center = echo.getVisualCenter();
        float rain = (echo.getWorld().getTime() + tickDelta) / 100.0f;
        float delta = MathHelper.clamp(tickDelta, 0.0f, 1.0f);
        Box bounds = echo.getBoundingBox();

        // Draw the flakes behind the Echo before capturing the scene used by
        // the distortion shell.
        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(camera ->
                renderFlakeLayer(camera, state, center, delta, true), false), EFFECT_PRIORITY - 1);

        // Fit the GhostDistortion quad to a camera-facing surface on the sphere
        // enclosing the entity. This keeps it around the Echo instead of
        // intersecting or rotating through its model as the camera moves.
        if (Shaders.GHOST_DISTORTION != null
                && Shaders.GHOST_DISTORTION.getProgram() != null) {
            RenderUtils.drawCameraFacingBillboardFitSphere(
                    () -> bindDistortion(rain),
                    center.x, center.y, center.z,
                    bounds,
                    DISTORTION_MAX_HALF_SIZE, DISTORTION_MAX_HALF_SIZE,
                    0.0f,
                    1.0f, 1.0f, 1.0f, 1.0f, FULL_BRIGHT,
                    true, EFFECT_PRIORITY);
        }

        // Flakes nearer than the Echo stay in front of the distortion shell.
        RenderUtils.recordLateWorldDraw(new RenderUtils.QueuedDrawCall(camera ->
                renderFlakeLayer(camera, state, center, delta, false), false), EFFECT_PRIORITY + 1);
    }

    private static void renderFlakeLayer(Camera camera, State state, Vec3d center,
                                         float tickDelta, boolean farLayer) {
        ArrayList<VisibleFlake> visible = collectVisible(camera, state, center, tickDelta);
        float centerDepth = cameraDepth(camera, center);
        int split = 0;
        while (split < visible.size() && visible.get(split).depth >= centerDepth) split++;

        FAtlasElement[] pebbles = getPebbleElements();
        if (pebbles == null) return;
        if (farLayer && split > 0) renderFlakes(camera, visible, 0, split, pebbles);
        if (!farLayer && split < visible.size()) {
            renderFlakes(camera, visible, split, visible.size(), pebbles);
        }
    }

    private static ArrayList<VisibleFlake> collectVisible(Camera camera, State state,
                                                          Vec3d center, float tickDelta) {
        ArrayList<VisibleFlake> visible = new ArrayList<>(state.flakes.length);
        for (Flake flake : state.flakes) {
            double x = center.x + MathHelper.lerp(tickDelta, flake.previousX, flake.x);
            double y = center.y + MathHelper.lerp(tickDelta, flake.previousY, flake.y);
            double z = center.z + MathHelper.lerp(tickDelta, flake.previousZ, flake.z);
            Vec3d worldPosition = new Vec3d(x, y, z);
            float depth = cameraDepth(camera, worldPosition);
            if (depth > 0.01f) {
                flake.renderDelta = tickDelta;
                visible.add(new VisibleFlake(flake, worldPosition, depth));
            }
        }
        visible.sort(Comparator.comparingDouble(VisibleFlake::depth).reversed());
        return visible;
    }

    private static float cameraDepth(Camera camera, Vec3d point) {
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f).rotate(camera.getRotation());
        Vec3d offset = point.subtract(camera.getPos());
        return (float) (offset.x * forward.x + offset.y * forward.y + offset.z * forward.z);
    }

    private static void renderFlakes(Camera camera, ArrayList<VisibleFlake> visible,
                                     int start, int end, FAtlasElement[] pebbles) {
        Identifier texture = firstTexture(pebbles);
        if (texture == null) return;

        Vec3d cameraPosition = camera.getPos();
        Quaternionf billboard = new Quaternionf(camera.getRotation());
        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f).rotate(billboard);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f).rotate(billboard);
        Matrix4f view = new Matrix4f(RenderUtils.getCameraMatrix(camera));
        BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);

        int rendered = 0;
        for (int i = start; i < end; i++) {
            VisibleFlake item = visible.get(i);
            Flake flake = item.flake;
            FAtlasElement element = pebbles[flake.spriteIndex - 1];
            if (element == null || !texture.equals(element.textureIdentifier)) continue;
            appendFlake(buffer, view, cameraPosition, right, up, item.position, flake, element);
            rendered++;
        }

        var built = buffer.endNullable();
        if (built == null || rendered == 0) {
            if (built != null) built.close();
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
            if (Shaders.RIPPLE_MASKED_BASIC != null
                    && Shaders.RIPPLE_MASKED_BASIC.getProgram() != null) {
                CoreShaderRenderer.bindShader$RippleMaskedBasic(
                        texture, null, null,
                        false, true, false, false, false, false);
                shaderApplied = true;
            }
        } catch (RuntimeException ignored) {
            // A resource reload can briefly make a libMod program unavailable.
        }
        if (!shaderApplied) RenderSystem.setShader(GameRenderer::getParticleProgram);
        RenderSystem.setShaderTexture(0, texture);
        BufferRenderer.drawWithGlobalProgram(built);
        restoreParticleRenderState();
    }

    private static void appendFlake(VertexConsumer vertices, Matrix4f view, Vec3d camera,
                                    Vector3f cameraRight, Vector3f cameraUp, Vec3d position,
                                    Flake flake, FAtlasElement element) {
        float sourceWidth = positive(element.sourcePixelSize.x, element.sourceSize.x);
        float sourceHeight = positive(element.sourcePixelSize.y, element.sourceSize.y);
        float width = positive(element.sourceRect.width, sourceWidth);
        float height = positive(element.sourceRect.height, sourceHeight);

        float rotation = MathHelper.lerp(flake.renderDelta, flake.previousRotation, flake.rotation);
        float yRotation = MathHelper.lerp(flake.renderDelta, flake.previousYRotation, flake.yRotation);
        float scaleX = MathHelper.lerp(flake.scaleSeed, 0.25f, 0.45f)
                * MathHelper.sin(yRotation * MathHelper.PI);
        float scaleY = MathHelper.lerp(flake.scaleSeed, 0.35f, 0.65f);

        float left = (-0.5f * sourceWidth + element.sourceRect.x) * scaleX / PIXELS_PER_BLOCK;
        float rightEdge = left + width * scaleX / PIXELS_PER_BLOCK;
        float bottom = (-0.5f * sourceHeight + sourceHeight - element.sourceRect.y - height)
                * scaleY / PIXELS_PER_BLOCK;
        float top = bottom + height * scaleY / PIXELS_PER_BLOCK;

        float radians = rotation * MathHelper.RADIANS_PER_DEGREE;
        float cos = MathHelper.cos(radians);
        float sin = MathHelper.sin(radians);
        Vector3f rotatedRight = new Vector3f(cameraRight).mul(cos)
                .add(new Vector3f(cameraUp).mul(sin));
        Vector3f rotatedUp = new Vector3f(cameraUp).mul(cos)
                .sub(new Vector3f(cameraRight).mul(sin));
        float cx = (float) (position.x - camera.x);
        float cy = (float) (position.y - camera.y);
        float cz = (float) (position.z - camera.z);

        float facing = inverseLerp(-1.0f, 1.0f,
                dotDegVectors(45.0f, yRotation * 57.29578f + rotation));
        Rgb dark = hslToRgb(0.08611111f, 0.65f,
                MathHelper.lerp(GHOST_MODE, 0.53f, 0.0f));
        Rgb bright = hslToRgb(0.08611111f,
                MathHelper.lerp(GHOST_MODE, 1.0f, 0.65f),
                MathHelper.lerp(GHOST_MODE, 1.0f, 0.53f));
        Rgb color = mix(dark, bright, facing);

        flakeVertex(vertices, view, corner(cx, cy, cz, rotatedRight, rotatedUp, left, bottom),
                element.uvBottomLeft, color);
        flakeVertex(vertices, view, corner(cx, cy, cz, rotatedRight, rotatedUp, rightEdge, bottom),
                element.uvBottomRight, color);
        flakeVertex(vertices, view, corner(cx, cy, cz, rotatedRight, rotatedUp, rightEdge, top),
                element.uvTopRight, color);
        flakeVertex(vertices, view, corner(cx, cy, cz, rotatedRight, rotatedUp, left, top),
                element.uvTopLeft, color);
    }

    private static Vector3f corner(float cx, float cy, float cz, Vector3f right, Vector3f up,
                                   float horizontal, float vertical) {
        return new Vector3f(right).mul(horizontal)
                .add(new Vector3f(up).mul(vertical)).add(cx, cy, cz);
    }

    private static void flakeVertex(VertexConsumer vertices, Matrix4f view, Vector3f point,
                                    FAtlasElement.Vec2 uv, Rgb color) {
        vertices.vertex(view, point.x, point.y, point.z)
                .color(color.r, color.g, color.b, 1.0f)
                .texture(uv.x, uv.y)
                .light(FULL_BRIGHT);
    }

    private static void bindDistortion(float rain) {
        CoreShaderRenderer.bindShader$GhostDistortion(
                NOISE_TEXTURE, GRAB_TEXTURE, null, null,
                false, true, false, false, false, false);
        ShaderRenderer.setUniformF(Shaders.GHOST_DISTORTION.getProgram(), "u_RAIN", rain);
        MinecraftClient client = MinecraftClient.getInstance();
        ShaderRenderer.setUniformF(Shaders.GHOST_DISTORTION.getProgram(), "u_screenSize",
                client.getFramebuffer().textureWidth, client.getFramebuffer().textureHeight);
        ShaderRenderer.setUniformF(Shaders.GHOST_DISTORTION.getProgram(),
                "u_spriteRect", 0.0f, 0.0f, 1.0f, 1.0f);
        RenderSystem.setShaderTexture(3, NOISE_TEXTURE);
        RenderSystem.setShaderTexture(7, GRAB_TEXTURE);
        // Preserve full vertex alpha for the shader's displacement strength,
        // but soften its blue-graded result when compositing over Minecraft.
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, DISTORTION_OPACITY);
    }

    private static void restoreParticleRenderState() {
        RenderSystem.setShader(GameRenderer::getParticleProgram);
        RenderSystem.setShaderTexture(0, SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static FAtlasElement[] getPebbleElements() {
        if (pebbleElements != null) return pebbleElements;
        FAtlasElement[] resolved = new FAtlasElement[14];
        try {
            for (int i = 0; i < resolved.length; i++) {
                resolved[i] = LibrainworldmcClient.getAtlasManager()
                        .getElementWithName("Pebble" + (i + 1));
                if (resolved[i] == null || resolved[i].textureIdentifier == null) return null;
            }
            pebbleElements = resolved;
            return resolved;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static Identifier firstTexture(FAtlasElement[] elements) {
        for (FAtlasElement element : elements) {
            if (element != null && element.textureIdentifier != null) return element.textureIdentifier;
        }
        return null;
    }

    private static float positive(float preferred, float fallback) {
        return preferred > 0.0f ? preferred : Math.max(fallback, 1.0f);
    }

    private static float dotDegVectors(float first, float second) {
        float a = first * MathHelper.RADIANS_PER_DEGREE;
        float b = second * MathHelper.RADIANS_PER_DEGREE;
        return MathHelper.sin(a) * MathHelper.sin(b) + MathHelper.cos(a) * MathHelper.cos(b);
    }

    private static float inverseLerp(float a, float b, float value) {
        return MathHelper.clamp((value - a) / (b - a), 0.0f, 1.0f);
    }

    private static Rgb mix(Rgb a, Rgb b, float amount) {
        return new Rgb(MathHelper.lerp(amount, a.r, b.r),
                MathHelper.lerp(amount, a.g, b.g),
                MathHelper.lerp(amount, a.b, b.b));
    }

    private static Rgb hslToRgb(float hue, float saturation, float lightness) {
        float maximum = lightness <= 0.5f
                ? lightness * (1.0f + saturation)
                : lightness + saturation - lightness * saturation;
        if (maximum <= 0.0f) return new Rgb(lightness, lightness, lightness);
        float minimum = lightness + lightness - maximum;
        float range = (maximum - minimum) / maximum;
        float sectorValue = hue * 6.0f;
        int sector = (int) sectorValue;
        float fraction = sectorValue - sector;
        float rise = maximum * range * fraction;
        float up = minimum + rise;
        float down = maximum - rise;
        return switch (sector) {
            case 0 -> new Rgb(maximum, up, minimum);
            case 1 -> new Rgb(down, maximum, minimum);
            case 2 -> new Rgb(minimum, maximum, up);
            case 3 -> new Rgb(minimum, down, maximum);
            case 4 -> new Rgb(up, minimum, maximum);
            default -> new Rgb(maximum, minimum, down);
        };
    }

    private static final class State {
        private final Random random;
        private final Flake[] flakes = new Flake[ACTIVE_FLAKES];
        private int entityId;

        private State(int entityId, UUID id) {
            this.entityId = entityId;
            random = new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ 77431L);
            for (int i = 0; i < flakes.length; i++) {
                flakes[i] = new Flake(i % 14 + 1);
                resetFlake(flakes[i]);
                placeRandomly(flakes[i]);
            }
        }

        private void tick() {
            for (Flake flake : flakes) {
                flake.previousX = flake.x;
                flake.previousY = flake.y;
                flake.previousZ = flake.z;
                flake.previousRotation = flake.rotation;
                flake.previousYRotation = flake.yRotation;
            }
            step();
            step();
        }

        private void step() {
            for (Flake flake : flakes) {
                flake.x += flake.velocityX;
                flake.y += flake.velocityY;
                flake.z += flake.velocityZ;
                flake.velocityX *= 0.82f;
                flake.velocityY = flake.velocityY * 0.82f - 0.25f / PIXELS_PER_BLOCK;
                flake.velocityZ *= 0.82f;

                float fallAngle = (180.0f + lerp(-45.0f, 45.0f, random.nextFloat()))
                        * MathHelper.RADIANS_PER_DEGREE;
                float facingAngle = (flake.rotation + flake.velocityRotationAdd + flake.yRotation)
                        * MathHelper.RADIANS_PER_DEGREE;
                float facingAmount = lerp(0.1f, 0.25f, random.nextFloat()) / PIXELS_PER_BLOCK;
                flake.velocityX += MathHelper.sin(fallAngle) * 0.1f / PIXELS_PER_BLOCK
                        + MathHelper.sin(facingAngle) * facingAmount;
                flake.velocityY += MathHelper.cos(fallAngle) * 0.1f / PIXELS_PER_BLOCK
                        + MathHelper.cos(facingAngle) * facingAmount;
                flake.velocityZ += (MathHelper.sin((flake.rotation + flake.velocityRotationAdd)
                        * MathHelper.RADIANS_PER_DEGREE) * 0.06f
                        + lerp(-0.03f, 0.03f, random.nextFloat())) / PIXELS_PER_BLOCK;

                if (Math.abs(flake.x) > ROOM_HALF_EXTENT
                        || flake.y < -ROOM_HALF_EXTENT
                        || flake.y > ROOM_HALF_EXTENT
                        || Math.abs(flake.z) > ROOM_HALF_EXTENT) {
                    flake.x = lerp(-ROOM_HALF_EXTENT, ROOM_HALF_EXTENT, random.nextFloat());
                    flake.y = ROOM_HALF_EXTENT;
                    flake.z = lerp(-ROOM_HALF_EXTENT, ROOM_HALF_EXTENT, random.nextFloat());
                    resetFlake(flake);
                    flake.velocityX = flake.velocityY = flake.velocityZ = 0.0f;
                    flake.previousX = flake.x;
                    flake.previousY = flake.y;
                    flake.previousZ = flake.z;
                    continue;
                }

                flake.rotation += flake.rotationSpeed;
                flake.rotationSpeed = MathHelper.clamp(flake.rotationSpeed
                        + lerp(-1.0f, 1.0f, random.nextFloat()) / 30.0f, -10.0f, 10.0f);
                flake.yRotation += flake.yRotationSpeed;
                flake.yRotationSpeed = MathHelper.clamp(flake.yRotationSpeed
                        + lerp(-1.0f, 1.0f, random.nextFloat()) / 320.0f, -0.05f, 0.05f);
            }
        }

        private void placeRandomly(Flake flake) {
            flake.x = lerp(-ROOM_HALF_EXTENT, ROOM_HALF_EXTENT, random.nextFloat());
            flake.y = lerp(-ROOM_HALF_EXTENT, ROOM_HALF_EXTENT, random.nextFloat());
            flake.z = lerp(-ROOM_HALF_EXTENT, ROOM_HALF_EXTENT, random.nextFloat());
            flake.previousX = flake.x;
            flake.previousY = flake.y;
            flake.previousZ = flake.z;
        }

        private void resetFlake(Flake flake) {
            flake.velocityRotationAdd = random.nextFloat() * 360.0f;
            float azimuth = random.nextFloat() * MathHelper.TAU;
            float z = lerp(-1.0f, 1.0f, random.nextFloat());
            float radial = MathHelper.sqrt(Math.max(0.0f, 1.0f - z * z));
            flake.velocityX = MathHelper.cos(azimuth) * radial / PIXELS_PER_BLOCK;
            flake.velocityY = MathHelper.sin(azimuth) * radial / PIXELS_PER_BLOCK;
            flake.velocityZ = z / PIXELS_PER_BLOCK;
            flake.scaleSeed = random.nextFloat();
            flake.rotation = random.nextFloat() * 360.0f;
            flake.previousRotation = flake.rotation;
            flake.rotationSpeed = lerp(2.0f, 10.0f, random.nextFloat())
                    * (random.nextBoolean() ? -1.0f : 1.0f);
            flake.yRotation = random.nextFloat() * MathHelper.PI;
            flake.previousYRotation = flake.yRotation;
            flake.yRotationSpeed = lerp(0.02f, 0.05f, random.nextFloat())
                    * (random.nextBoolean() ? -1.0f : 1.0f);
        }
    }

    private static final class Flake {
        private final int spriteIndex;
        private float x;
        private float y;
        private float z;
        private float previousX;
        private float previousY;
        private float previousZ;
        private float velocityX;
        private float velocityY;
        private float velocityZ;
        private float scaleSeed;
        private float rotation;
        private float previousRotation;
        private float rotationSpeed;
        private float yRotation;
        private float previousYRotation;
        private float yRotationSpeed;
        private float velocityRotationAdd;
        private float renderDelta;

        private Flake(int spriteIndex) {
            this.spriteIndex = spriteIndex;
        }
    }

    private static float lerp(float a, float b, float amount) {
        return a + (b - a) * amount;
    }

    private record VisibleFlake(Flake flake, Vec3d position, float depth) {
    }

    private record Rgb(float r, float g, float b) {
    }
}
