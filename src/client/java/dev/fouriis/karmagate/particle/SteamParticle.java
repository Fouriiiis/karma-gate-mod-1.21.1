package dev.fouriis.karmagate.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.render.shader.ShaderRenderer;
import net.brickcraftdream.librainworldmc.client.render.shader.Shaders;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

@Environment(EnvType.CLIENT)
public class SteamParticle extends SpriteBillboardParticle {
    // Rain World's literal 20 px tile scale is visually enormous in Minecraft.
    // 64 px/block preserves the source proportions while fitting a block-scale vent.
    private static final float RAIN_WORLD_PIXELS_PER_BLOCK = 64.0f;
    private static final float MIN_RADIUS = 4.0f / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final float MIN_CORE_RADIUS = 2.0f / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final float STRETCH_START = 60.0f / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final float STRETCH_END = 200.0f / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final float CONFINES_RADIUS = 200.0f / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final float WANDER_ACCELERATION = 1.8f / RAIN_WORLD_PIXELS_PER_BLOCK;
    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise.png");
    private static final Identifier GRAB_TEXTURE =
            Identifier.of("librainworldmc", "grabtex");
    private static Identifier rainWorldWhiteTexture;

    private static final class ImmediateBuffer {
        private static final BufferAllocator ALLOCATOR = new BufferAllocator(
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT.getVertexSizeByte() * 4
        );
    }

    private final float intensity;
    private final float targetRadius;
    private final float upwardAcceleration;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final double lingerX;
    private final double lingerY;
    private final double lingerZ;
    private float moveDirection;

    protected SteamParticle(ClientWorld world, double x, double y, double z,
                            double vx, double vyAsIntensity, double vz) {
        super(world, x, y, z, 0, 0, 0);

        this.intensity = MathHelper.clamp((float) vyAsIntensity, 0.0f, 1.0f);
        this.maxAge = Math.round(MathHelper.lerp(
                this.random.nextFloat() * this.intensity,
                60.0f,
                180.0f
        ));
        this.targetRadius = MathHelper.lerp(
                this.random.nextFloat(),
                108.0f,
                286.0f
        ) / RAIN_WORLD_PIXELS_PER_BLOCK * MathHelper.lerp(this.intensity, 0.5f, 1.0f);
        this.upwardAcceleration = this.random.nextFloat()
                * (280.0f / RAIN_WORLD_PIXELS_PER_BLOCK) / this.maxAge * this.intensity;
        this.moveDirection = this.random.nextFloat() * MathHelper.TAU;

        this.originX = x;
        this.originY = y;
        this.originZ = z;

        float lingerDirection = this.random.nextFloat() * MathHelper.TAU;
        float lingerOffset = 1.0f / RAIN_WORLD_PIXELS_PER_BLOCK;
        this.lingerX = x + Math.cos(lingerDirection) * lingerOffset;
        this.lingerY = y + (this.random.nextFloat() * 2.0f - 1.0f) * lingerOffset;
        this.lingerZ = z + Math.sin(lingerDirection) * lingerOffset;

        this.velocityX = vx + Math.cos(this.moveDirection) * (0.006f + 0.014f * this.intensity);
        this.velocityY = 0.015f + 0.035f * this.intensity;
        this.velocityZ = vz + Math.sin(this.moveDirection) * (0.006f + 0.014f * this.intensity);

        // Rain World's SteamSmoke is tinted slightly toward the room fog color.
        float fogWhite = MathHelper.lerp(this.intensity, 0.82f, 0.94f);
        this.red = this.green = this.blue = fogWhite;
        this.alpha = 1.0f;
        this.gravityStrength = 0.0f;
        this.collidesWithWorld = false;

    }

    @Override
    public void tick() {
        this.prevPosX = this.x;
        this.prevPosY = this.y;
        this.prevPosZ = this.z;

        if (this.age++ >= this.maxAge) {
            this.markDead();
            return;
        }

        this.move(this.velocityX, this.velocityY, this.velocityZ);

        float life = life(1.0f);
        this.moveDirection += (this.random.nextFloat() * 2.0f - 1.0f)
                * (50.0f * MathHelper.RADIANS_PER_DEGREE);

        this.velocityX *= 0.8;
        this.velocityY *= 0.8;
        this.velocityZ *= 0.8;

        float wander = WANDER_ACCELERATION * this.intensity * life;
        this.velocityX += Math.cos(this.moveDirection) * wander;
        this.velocityZ += Math.sin(this.moveDirection) * wander;
        this.velocityY += this.upwardAcceleration;

        this.x = MathHelper.clamp(this.x, this.originX - CONFINES_RADIUS, this.originX + CONFINES_RADIUS);
        this.y = MathHelper.clamp(this.y, this.originY - CONFINES_RADIUS, this.originY + CONFINES_RADIUS);
        this.z = MathHelper.clamp(this.z, this.originZ - CONFINES_RADIUS, this.originZ + CONFINES_RADIUS);

        this.alpha = life;
    }

    @Override
    public void buildGeometry(VertexConsumer ignored, Camera camera, float tickDelta) {
        Identifier particleTexture = getRainWorldWhiteTexture();
        if (Shaders.STEAM == null || particleTexture == null) {
            return;
        }

        Quaternionf rotation = new Quaternionf();
        this.getRotator().setRotation(rotation, camera, tickDelta);
        Vector3f cameraRight = new Vector3f(1.0f, 0.0f, 0.0f).rotate(rotation);
        Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f).rotate(rotation);

        Vec3d cameraPos = camera.getPos();
        float centerX = (float) (MathHelper.lerp(tickDelta, this.prevPosX, this.x) - cameraPos.x);
        float centerY = (float) (MathHelper.lerp(tickDelta, this.prevPosY, this.y) - cameraPos.y);
        float centerZ = (float) (MathHelper.lerp(tickDelta, this.prevPosZ, this.z) - cameraPos.z);
        Vector3f center = new Vector3f(centerX, centerY, centerZ);
        Vector3f linger = new Vector3f(
                (float) (this.lingerX - cameraPos.x),
                (float) (this.lingerY - cameraPos.y),
                (float) (this.lingerZ - cameraPos.z)
        );

        float life = life(tickDelta);
        float distanceFromLinger = center.distance(linger);
        float stretched = MathHelper.clamp(
                (distanceFromLinger - STRETCH_START) / (STRETCH_END - STRETCH_START),
                0.0f,
                1.0f
        );
        float plumeWidth = radius(0, life, stretched);
        float coreSize = radius(1, life, stretched);
        float plumeEndSize = radius(2, life, stretched);

        Vector3f toLinger = new Vector3f(linger).sub(center);
        float screenDx = toLinger.dot(cameraRight);
        float screenDy = toLinger.dot(cameraUp);
        float screenDistance = (float) Math.sqrt(screenDx * screenDx + screenDy * screenDy);

        Vector3f plumeAxis;
        Vector3f plumePerpendicular;
        if (screenDistance <= 1.0e-5f) {
            plumeAxis = new Vector3f(cameraUp);
            plumePerpendicular = new Vector3f(cameraRight);
        } else {
            plumeAxis = new Vector3f(cameraRight).mul(screenDx)
                    .add(new Vector3f(cameraUp).mul(screenDy))
                    .normalize();
            plumePerpendicular = new Vector3f(cameraRight).mul(screenDy)
                    .sub(new Vector3f(cameraUp).mul(screenDx))
                    .normalize();
        }

        Vector3f plumeCenter = new Vector3f(center).lerp(linger, 0.5f);
        Vector3f[] plumeVertices = quadVertices(
                plumeCenter,
                plumePerpendicular,
                plumeAxis,
                plumeWidth,
                screenDistance + plumeEndSize
        );
        Vector3f[] coreVertices = quadVertices(
                center,
                cameraRight,
                cameraUp,
                coreSize,
                coreSize
        );

        // The steam shader returns transparent pixels instead of discarding them.
        // Do not let those invisible parts of the billboard write square holes
        // into the depth buffer and occlude steam particles rendered afterward.
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderTexture(0, particleTexture);

        int light = this.getBrightness(tickDelta);
        drawSteamQuad(plumeVertices, particleTexture, light);
        drawSteamQuad(coreVertices, particleTexture, light);

        // CUSTOM particles share this render pass. Put back its vanilla state so
        // the libMod shader and atlas do not leak into particles rendered after us.
        RenderSystem.setShader(GameRenderer::getParticleProgram);
        RenderSystem.setShaderTexture(0, SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private void drawSteamQuad(Vector3f[] vertices, Identifier particleTexture, int light) {
        float[] spriteRect = projectSpriteRect(vertices);
        if (spriteRect == null) {
            return;
        }

        Shaders.STEAM
                .setSampler1_LevelTex(particleTexture)
                .setSampler2_NoiseTex(NOISE_TEXTURE)
                .setSampler7_GrabTexture(GRAB_TEXTURE)
                .setRipple(false)
                .setRipple_Both_Sides(false)
                .setRipple_Other_Side(false)
                .setRipple_Other_Side_Alt(false)
                .setScreenspace(false)
                .apply();
        ShaderRenderer.setUniformF(Shaders.STEAM.getProgram(), "u_spriteRect", spriteRect);

        BufferBuilder buffer = new BufferBuilder(
                ImmediateBuffer.ALLOCATOR,
                VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT
        );

        emitVertex(buffer, vertices[0], 1.0f, 1.0f, light);
        emitVertex(buffer, vertices[1], 1.0f, 0.0f, light);
        emitVertex(buffer, vertices[2], 0.0f, 0.0f, light);
        emitVertex(buffer, vertices[3], 0.0f, 1.0f, light);

        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static Vector3f[] quadVertices(
            Vector3f center,
            Vector3f widthAxis,
            Vector3f heightAxis,
            float width,
            float height
    ) {
        Vector3f halfWidth = new Vector3f(widthAxis).mul(width * 0.5f);
        Vector3f halfHeight = new Vector3f(heightAxis).mul(height * 0.5f);
        return new Vector3f[]{
                new Vector3f(center).add(halfWidth).sub(halfHeight),
                new Vector3f(center).add(halfWidth).add(halfHeight),
                new Vector3f(center).sub(halfWidth).add(halfHeight),
                new Vector3f(center).sub(halfWidth).sub(halfHeight)
        };
    }

    private float life(float tickDelta) {
        return MathHelper.clamp(
                1.0f - (this.age - 1.0f + tickDelta) / this.maxAge,
                0.0f,
                1.0f
        );
    }

    private float radius(int type, float life, float stretched) {
        float radiusProgress = (float) Math.pow(Math.max(
                0.0f,
                MathHelper.lerp(0.7f, (float) Math.sin(life * Math.PI), 1.0f - life)
        ), 0.8f);

        return switch (type) {
            case 0 -> MathHelper.lerp(
                    MathHelper.clamp(radiusProgress + stretched, 0.0f, 1.0f),
                    MIN_RADIUS,
                    this.targetRadius
            );
            case 1 -> 1.5f * MathHelper.lerp(radiusProgress, MIN_CORE_RADIUS, this.targetRadius);
            default -> MathHelper.lerp(radiusProgress, MIN_RADIUS, this.targetRadius);
        };
    }

    private static float[] projectSpriteRect(Vector3f[] vertices) {
        Matrix4f modelViewProjection = new Matrix4f(RenderSystem.getProjectionMatrix())
                .mul(RenderSystem.getModelViewMatrix());
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        for (Vector3f vertex : vertices) {
            Vector4f clip = modelViewProjection.transform(
                    new Vector4f(vertex.x, vertex.y, vertex.z, 1.0f)
            );
            if (clip.w <= 1.0e-5f) {
                return null;
            }

            float screenX = clip.x / clip.w * 0.5f + 0.5f;
            float screenY = clip.y / clip.w * 0.5f + 0.5f;
            minX = Math.min(minX, screenX);
            minY = Math.min(minY, screenY);
            maxX = Math.max(maxX, screenX);
            maxY = Math.max(maxY, screenY);
        }

        if (maxX - minX <= 1.0e-5f || maxY - minY <= 1.0e-5f) {
            return null;
        }
        return new float[]{minX, minY, maxX, maxY};
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
            // libMod has not completed its client atlas initialization yet.
        }
        return rainWorldWhiteTexture;
    }

    private void emitVertex(BufferBuilder buffer, Vector3f vertex, float u, float v, int light) {
        buffer.vertex(vertex.x, vertex.y, vertex.z)
                .color(this.red, this.green, this.blue, this.alpha)
                .texture(u, v)
                .light(light);
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.CUSTOM;
    }

    // Factory
    @Environment(EnvType.CLIENT)
    public static class Factory implements ParticleFactory<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientWorld world,
                                       double x, double y, double z,
                                       double vx, double vyAsIntensity, double vz) {
            return new SteamParticle(world, x, y, z, vx, vyAsIntensity, vz);
        }
    }
}
