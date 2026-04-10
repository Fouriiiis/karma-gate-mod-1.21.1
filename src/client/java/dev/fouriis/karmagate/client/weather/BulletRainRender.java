package dev.fouriis.karmagate.client.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.rain.GlobalRain;
import net.brickcraftdream.librainworldmc.client.api.RwSoundApi;
import net.brickcraftdream.librainworldmc.client.api.RwSoundsApi;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.Fluids;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class BulletRainRender {

    private static final float LOCAL_FALLBACK_INTENSITY = 0.0f;
    private static final int DRIP_POOL_SIZE = 32;
    private static final int STRIKE_RADIUS = 24;
    private static final int STRIKE_ATTEMPTS = 12;
    private static final float STREAK_WIDTH = 0.045f;
    private static final float SPLASH_MAX_SIZE = 0.42f;
    private static final float SOUND_RADIUS_SQ = 26.0f * 26.0f;
    private static final int MAX_SOUNDS_PER_TICK = 5;
    private static final boolean DEBUG_CAMERA_LOCAL_STRIKES = true;

    private static final Identifier RAIN_TEXTURE =
            Identifier.of("minecraft", "textures/block/water_flow.png");

    private static final String[] STRIKE_SOUND_IDS = new String[]{
            "bulletDripStrike",
            "Bullet_Drip_Strike",
            "bullet_drip_strike"
    };

    private static final String[] WATER_STRIKE_SOUND_IDS = new String[]{
            "smallObjectIntoWaterFast",
            "Small_Object_Into_Water_Fast",
            "small_object_into_water_fast"
    };

    private static final List<Drip> DRIPS = new ArrayList<>();
    private static final RwSoundApi RW_SOUNDS = RwSoundsApi.get();

    private static World activeWorld;
    private static long lastSimulatedWorldTick = Long.MIN_VALUE;
    private static long soundTick = Long.MIN_VALUE;
    private static int soundsThisTick;

    private BulletRainRender() {
    }

    public static void render(World world, Camera camera, float tickDelta, MatrixStack matrices) {
        if (world == null || camera == null || matrices == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        float intensity = resolveGlobalRainIntensity();
        if (intensity <= 0.0001f) {
            return;
        }

        ensureWorldState(world, intensity);
        stepSimulation(world, camera, intensity);
        renderDrips(world, camera, tickDelta, matrices, intensity);
    }

    private static void ensureWorldState(World world, float intensity) {
        if (activeWorld == world && !DRIPS.isEmpty()) {
            return;
        }

        activeWorld = world;
        lastSimulatedWorldTick = Long.MIN_VALUE;
        soundTick = Long.MIN_VALUE;
        soundsThisTick = 0;

        DRIPS.clear();
        for (int i = 0; i < DRIP_POOL_SIZE; i++) {
            Drip drip = new Drip();
            drip.delay = randomDelay(intensity);
            DRIPS.add(drip);
        }
    }

    private static float resolveGlobalRainIntensity() {
        if (GlobalRainClientState.hasSync()) {
            return MathHelper.clamp(GlobalRainClientState.bulletRainDensity(), 0.0f, 1.0f);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getServer() != null) {
            return MathHelper.clamp(GlobalRain.get(client.getServer()).getBulletRainDensity(), 0.0f, 1.0f);
        }

        return LOCAL_FALLBACK_INTENSITY;
    }

    private static void stepSimulation(World world, Camera camera, float intensity) {
        long worldTick = world.getTime();
        if (lastSimulatedWorldTick == worldTick) {
            return;
        }

        int steps;
        if (lastSimulatedWorldTick == Long.MIN_VALUE || worldTick < lastSimulatedWorldTick) {
            steps = 1;
        } else {
            steps = (int) Math.min(4L, worldTick - lastSimulatedWorldTick);
        }

        for (int i = 0; i < steps; i++) {
            tickDrips(world, camera, intensity);
        }

        lastSimulatedWorldTick = worldTick;
    }

    private static void tickDrips(World world, Camera camera, float intensity) {
        for (Drip drip : DRIPS) {
            drip.lastFalling = drip.falling;
            drip.falling = Math.min(1.0f, drip.falling + drip.fallSpeed);
            drip.moveTip = false;

            if (drip.lastFalling >= 1.0f) {
                drip.delay--;
                if (drip.delay < 1) {
                    strike(world, camera, drip, intensity);
                }
            }
        }
    }

    private static void strike(World world, Camera camera, Drip drip, float intensity) {
        Vec3d cam = camera.getPos();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < STRIKE_ATTEMPTS; attempt++) {
            int x = MathHelper.floor(cam.x) + random.nextInt(-STRIKE_RADIUS, STRIKE_RADIUS + 1);
            int z = MathHelper.floor(cam.z) + random.nextInt(-STRIKE_RADIUS, STRIKE_RADIUS + 1);

            Vec3d hitPos;
            if (DEBUG_CAMERA_LOCAL_STRIKES) {
                double startY = cam.y + random.nextDouble(8.0, 20.0);
                double endY = cam.y - random.nextDouble(2.0, 14.0);

                Vec3d rayStart = new Vec3d(
                        x + 0.5 + random.nextDouble(-0.45, 0.45),
                        startY,
                        z + 0.5 + random.nextDouble(-0.45, 0.45)
                );
                Vec3d rayEnd = new Vec3d(rayStart.x, endY, rayStart.z);

                MinecraftClient client = MinecraftClient.getInstance();
                BlockHitResult downHit = world.raycast(new RaycastContext(
                        rayStart,
                        rayEnd,
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.ANY,
                        client.player
                ));

                if (downHit.getType() == HitResult.Type.MISS) {
                    continue;
                }
                hitPos = downHit.getPos();
            } else {
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                if (topY <= world.getBottomY() + 1) {
                    continue;
                }

                BlockPos topPos = new BlockPos(x, topY, z);
                if (!world.isSkyVisible(topPos)) {
                    continue;
                }

                hitPos = new Vec3d(
                        x + 0.5 + random.nextDouble(-0.45, 0.45),
                        topY + 0.02,
                        z + 0.5 + random.nextDouble(-0.45, 0.45)
                );
            }

                double rise = random.nextDouble(42.0, 68.0);
                double maxTiltRadians = Math.toRadians(7.0);
                double tiltRadians = random.nextDouble(0.0, maxTiltRadians);
                double yaw = random.nextDouble(0.0, Math.PI * 2.0);
                double horizontalOffset = Math.tan(tiltRadians) * rise;

                double skyY = Math.min(world.getTopY() + 24.0, hitPos.y + rise);
                Vec3d skyPos = new Vec3d(
                    hitPos.x + Math.cos(yaw) * horizontalOffset,
                    skyY,
                    hitPos.z + Math.sin(yaw) * horizontalOffset
                );

            MinecraftClient client = MinecraftClient.getInstance();
            BlockHitResult hitResult = world.raycast(new RaycastContext(
                    skyPos,
                    hitPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.ANY,
                    client.player
            ));

            if (hitResult.getType() != HitResult.Type.MISS) {
                hitPos = hitResult.getPos();
            }

            drip.pos = hitPos;
            drip.skyPos = skyPos;
            drip.falling = 0.0f;
            drip.lastFalling = 0.0f;
            drip.moveTip = true;
            drip.fallSpeed = 1.0f / MathHelper.lerp(random.nextFloat(), 0.2f, 1.8f);
            drip.delay = randomDelay(intensity);
            drip.splashSpinDeg = random.nextFloat() * 360.0f;

            playStrikeSound(world, hitPos);
            return;
        }

        drip.delay = 2;
    }

    private static int randomDelay(float intensity) {
        int maxDelay = Math.max(1, 60 - (int) (intensity * 60.0f));
        return ThreadLocalRandom.current().nextInt(0, maxDelay);
    }

    private static void playStrikeSound(World world, Vec3d strikePos) {
        long worldTick = world.getTime();
        if (soundTick != worldTick) {
            soundTick = worldTick;
            soundsThisTick = 0;
        }
        if (soundsThisTick >= MAX_SOUNDS_PER_TICK) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.squaredDistanceTo(strikePos) > SOUND_RADIUS_SQ) {
            return;
        }

        if (ThreadLocalRandom.current().nextFloat() > 0.55f) {
            return;
        }

        SoundEvent event = resolveRwSound(isWaterImpact(world, strikePos) ? WATER_STRIKE_SOUND_IDS : STRIKE_SOUND_IDS);
        if (event == null) {
            return;
        }

        float pitch = ThreadLocalRandom.current().nextFloat() * 0.2f + 0.9f;
        world.playSound(
                client.player,
                strikePos.x,
                strikePos.y,
                strikePos.z,
                event,
                SoundCategory.WEATHER,
                0.45f,
                pitch
        );
        soundsThisTick++;
    }

    private static boolean isWaterImpact(World world, Vec3d strikePos) {
        BlockPos pos = BlockPos.ofFloored(strikePos);
        return world.getFluidState(pos).getFluid() == Fluids.WATER
                || world.getFluidState(pos.down()).getFluid() == Fluids.WATER;
    }

    private static SoundEvent resolveRwSound(String[] ids) {
        for (String id : ids) {
            try {
                SoundEvent event = RW_SOUNDS.getEvent(id);
                if (event != null) {
                    return event;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean bindBulletRainProgram(World world, Camera camera, float intensity) {
        ShaderProgram program = BulletRainShaders.PROGRAM;
        if (program == null) {
            return false;
        }

        RenderSystem.setShader(() -> program);
        RenderSystem.setShaderTexture(0, RAIN_TEXTURE);

        program.bind();
        setUniform1f(program, "uRainIntensity", MathHelper.clamp(intensity, 0.0f, 1.0f));
        setUniform1f(program, "uDistortionStrength", 0.05f);

        int waterColor = BiomeColors.getWaterColor(world, BlockPos.ofFloored(camera.getPos()));
        float red = ((waterColor >> 16) & 0xFF) / 255.0f;
        float green = ((waterColor >> 8) & 0xFF) / 255.0f;
        float blue = (waterColor & 0xFF) / 255.0f;
        RenderSystem.setShaderColor(red, green, blue, 1.0f);
        return true;
    }

    private static void setUniform1f(ShaderProgram program, String name, float value) {
        GlUniform uniform = program.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void renderDrips(World world, Camera camera, float tickDelta, MatrixStack matrices, float intensity) {
        if (DRIPS.isEmpty() || intensity <= 0.0f) {
            return;
        }

        try {
            if (!bindBulletRainProgram(world, camera, intensity)) {
                return;
            }

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);

            Vec3d camPos = camera.getPos();
            Vector3f camRight = new Vector3f(1.0f, 0.0f, 0.0f).rotate(camera.getRotation());
            Vector3f camUp = new Vector3f(0.0f, 1.0f, 0.0f).rotate(camera.getRotation());

            matrices.push();
            matrices.translate(-camPos.x, -camPos.y, -camPos.z);
            Matrix4f matrix = matrices.peek().getPositionMatrix();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_LIGHT
            );

            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

            for (Drip drip : DRIPS) {
                if (drip.pos == null || drip.skyPos == null) {
                    continue;
                }

                float progress = MathHelper.lerp(tickDelta, drip.lastFalling, drip.falling);
                Vec3d tip = drip.moveTip ? drip.skyPos.lerp(drip.pos, tickDelta) : drip.pos;
                Vec3d body = drip.skyPos.lerp(drip.pos, progress);

                emitStreak(buffer, matrix, tip, body, camPos, light, intensity);

                float splashScale = (float) Math.sin(progress * Math.PI) * SPLASH_MAX_SIZE * intensity;
                if (splashScale > 0.002f) {
                    emitSplashBillboard(buffer, matrix, tip, camRight, camUp, splashScale, drip.splashSpinDeg, light, intensity);
                }
            }

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        } catch (Throwable t) {
            System.err.println("[Karmagate/BulletRain] Exception while rendering bullet rain");
            t.printStackTrace();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static void emitStreak(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3d start,
            Vec3d end,
            Vec3d camPos,
            int light,
            float intensity
    ) {
        Vec3d axis = end.subtract(start);
        if (axis.lengthSquared() < 1.0e-6) {
            return;
        }

        Vec3d toCam = camPos.subtract(start.add(axis.multiply(0.5)));
        Vec3d right = axis.crossProduct(toCam);

        if (right.lengthSquared() < 1.0e-6) {
            right = axis.crossProduct(new Vec3d(0.0, 1.0, 0.0));
        }
        if (right.lengthSquared() < 1.0e-6) {
            right = new Vec3d(1.0, 0.0, 0.0);
        }

        right = right.normalize().multiply(STREAK_WIDTH * 0.5);

        Vec3d v0 = start.add(right);
        Vec3d v1 = start.subtract(right);
        Vec3d v2 = end.subtract(right);
        Vec3d v3 = end.add(right);

        int r = 240;
        int g = 245;
        int b = 255;
        int a = MathHelper.clamp((int) (160.0f + intensity * 95.0f), 0, 255);

        buffer.vertex(matrix, (float) v0.x, (float) v0.y, (float) v0.z)
                .color(r, g, b, a)
                .texture(0f, 0f)
            .light(light);

        buffer.vertex(matrix, (float) v1.x, (float) v1.y, (float) v1.z)
                .color(r, g, b, a)
                .texture(1f, 0f)
            .light(light);

        buffer.vertex(matrix, (float) v2.x, (float) v2.y, (float) v2.z)
                .color(r, g, b, a)
                .texture(1f, 1f)
            .light(light);

        buffer.vertex(matrix, (float) v3.x, (float) v3.y, (float) v3.z)
                .color(r, g, b, a)
                .texture(0f, 1f)
            .light(light);
    }

    private static void emitSplashBillboard(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3d center,
            Vector3f camRight,
            Vector3f camUp,
            float size,
            float spinDeg,
            int light,
            float intensity
    ) {
        Vector3f right = new Vector3f(camRight);
        Vector3f up = new Vector3f(camUp);

        float spinRad = (float) Math.toRadians(spinDeg);
        float c = (float) Math.cos(spinRad);
        float s = (float) Math.sin(spinRad);

        Vector3f rotatedRight = new Vector3f(right).mul(c).add(new Vector3f(up).mul(s));
        Vector3f rotatedUp = new Vector3f(up).mul(c).sub(new Vector3f(right).mul(s));

        rotatedRight.mul(size);
        rotatedUp.mul(size);

        Vec3d r = new Vec3d(rotatedRight.x, rotatedRight.y, rotatedRight.z);
        Vec3d u = new Vec3d(rotatedUp.x, rotatedUp.y, rotatedUp.z);

        Vec3d v0 = center.subtract(r).add(u);
        Vec3d v1 = center.add(r).add(u);
        Vec3d v2 = center.add(r).subtract(u);
        Vec3d v3 = center.subtract(r).subtract(u);

        int alpha = MathHelper.clamp((int) (120.0f + intensity * 110.0f), 0, 255);

        buffer.vertex(matrix, (float) v0.x, (float) v0.y, (float) v0.z)
                .color(255, 255, 255, alpha)
                .texture(0f, 0f)
            .light(light);

        buffer.vertex(matrix, (float) v1.x, (float) v1.y, (float) v1.z)
                .color(255, 255, 255, alpha)
                .texture(1f, 0f)
            .light(light);

        buffer.vertex(matrix, (float) v2.x, (float) v2.y, (float) v2.z)
                .color(255, 255, 255, alpha)
                .texture(1f, 1f)
            .light(light);

        buffer.vertex(matrix, (float) v3.x, (float) v3.y, (float) v3.z)
                .color(255, 255, 255, alpha)
                .texture(0f, 1f)
            .light(light);
    }

    private static final class Drip {
        Vec3d pos;
        Vec3d skyPos;
        int delay;
        float lastFalling = 1.0f;
        float falling = 1.0f;
        boolean moveTip;
        float fallSpeed = 1.0f;
        float splashSpinDeg;
    }
}