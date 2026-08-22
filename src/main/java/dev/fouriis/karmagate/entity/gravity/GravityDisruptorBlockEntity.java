package dev.fouriis.karmagate.entity.gravity;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Random;

/**
 * Client-side port of GravityDisruptor3D's 16-panel light field. The source
 * simulation runs at 40 Hz, so every Minecraft client tick advances it twice.
 */
public final class GravityDisruptorBlockEntity extends BlockEntity {
    public static final int PANEL_COUNT = 16;
    public static final int PARTICLE_COUNT = 20;

    private final Random random = new Random(19331L);
    private final float[][] lights = new float[PANEL_COUNT][4];
    private final float[] previousPanelValues = new float[PANEL_COUNT];
    private final float[] panelValues = new float[PANEL_COUNT];
    private final DisruptorParticle[] particles = new DisruptorParticle[PARTICLE_COUNT];

    private float pointDirectionX;
    private float pointDirectionY;
    private float targetPointDirectionX;
    private float targetPointDirectionY;
    private float directionFactor;
    private float targetDirectionFactor;

    public GravityDisruptorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GRAVITY_DISRUPTOR_BLOCK_ENTITY, pos, state);
        resetAnimation();
    }

    private void resetAnimation() {
        for (int i = 0; i < PANEL_COUNT; i++) {
            lights[i][0] = 0.0f;
            lights[i][1] = 0.0f;
            lights[i][2] = 0.0f;
            lights[i][3] = random.nextFloat();
        }
        setRandomTargetDirection(true);
        directionFactor = targetDirectionFactor = random.nextFloat();
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particles[i] = new DisruptorParticle(random);
        }

        // In Rain World the object normally exists before entering view. Warm
        // the field exactly like the standalone reference does.
        for (int i = 0; i < 240; i++) updateLights();
        for (int i = 0; i < PANEL_COUNT; i++) {
            lights[i][1] = lights[i][2] = lights[i][0];
            previousPanelValues[i] = panelValues[i] = lights[i][0];
        }
    }

    public static void clientTick(World world, BlockPos pos, BlockState state,
                                  GravityDisruptorBlockEntity disruptor) {
        for (int i = 0; i < PANEL_COUNT; i++) {
            disruptor.previousPanelValues[i] = disruptor.panelValues[i];
        }
        for (DisruptorParticle particle : disruptor.particles) {
            particle.beginClientTick();
        }
        for (int step = 0; step < 2; step++) {
            disruptor.updateLights();
            for (DisruptorParticle particle : disruptor.particles) {
                particle.update(disruptor.random);
            }
        }
        for (int i = 0; i < PANEL_COUNT; i++) {
            disruptor.panelValues[i] = disruptor.lights[i][0];
        }
    }

    public float getPanelValue(int index, float tickDelta) {
        if (index < 0 || index >= PANEL_COUNT) return 0.0f;
        return MathHelper.lerp(MathHelper.clamp(tickDelta, 0.0f, 1.0f),
                previousPanelValues[index], panelValues[index]);
    }

    /** Interpolated source-space particle offset converted from pixels to blocks. */
    public Vec3d getParticleOffset(int index, float tickDelta) {
        if (index < 0 || index >= PARTICLE_COUNT) return Vec3d.ZERO;
        return particles[index].interpolatedOffset(tickDelta).multiply(1.0 / 20.0);
    }

    private void updateLights() {
        float fromAngle = (float) Math.atan2(pointDirectionY, pointDirectionX);
        float toAngle = (float) Math.atan2(targetPointDirectionY, targetPointDirectionX);
        float difference = (float) Math.atan2(
                Math.sin(toAngle - fromAngle), Math.cos(toAngle - fromAngle));
        float angle = fromAngle + difference * 0.01f;
        pointDirectionX = MathHelper.cos(angle);
        pointDirectionY = MathHelper.sin(angle);

        if (random.nextFloat() < 0.0125f) setRandomTargetDirection(false);
        directionFactor = MathHelper.lerp(0.01f, directionFactor, targetDirectionFactor);
        if (random.nextFloat() < 0.0125f) targetDirectionFactor = random.nextFloat();

        for (int i = 0; i < PANEL_COUNT; i++) {
            float[] light = lights[i];
            light[2] = light[1];
            light[1] = light[0];
            light[0] = MathHelper.clamp(
                    light[0] + lerp(-1.0f, 1.0f, random.nextFloat()) / 120.0f
                            + lerp(-1.0f, 1.0f, light[3]) / 60.0f,
                    0.0f, 1.0f);

            float panelAngle = i / (float) PANEL_COUNT * MathHelper.TAU;
            // Rain World's DegreeVector: zero degrees points upward.
            float directionX = MathHelper.sin(panelAngle);
            float directionY = MathHelper.cos(panelAngle);
            float dot = directionX * pointDirectionX + directionY * pointDirectionY;
            float target = (float) Math.pow(inverseLerp(-1.0f, 1.0f, dot), 1.5);
            float blend = (float) Math.pow(Math.abs(dot), 8.0) * 0.3f * directionFactor
                    * inverseLerp(0.5f, 0.0f, Math.abs(0.5f - light[3]));
            light[0] = lerp(light[0], target, blend);

            float neighbors = lights[(i + 1) % PANEL_COUNT][2]
                    + lights[(i + PANEL_COUNT - 1) % PANEL_COUNT][2];
            light[0] = lerp(light[0], neighbors * 0.5f, 0.05f);
            if (random.nextFloat() < 0.005f) light[3] = random.nextFloat();
        }
    }

    private void setRandomTargetDirection(boolean alsoSetCurrent) {
        float angle = random.nextFloat() * MathHelper.TAU;
        targetPointDirectionX = MathHelper.sin(angle);
        targetPointDirectionY = MathHelper.cos(angle);
        if (alsoSetCurrent) {
            pointDirectionX = targetPointDirectionX;
            pointDirectionY = targetPointDirectionY;
        }
    }

    private static float inverseLerp(float a, float b, float value) {
        return MathHelper.clamp((value - a) / (b - a), 0.0f, 1.0f);
    }

    private static float lerp(float a, float b, float amount) {
        return a + (b - a) * amount;
    }

    /** Exact behavioral port of the reference DisruptorParticle. */
    private static final class DisruptorParticle {
        private float x;
        private float y;
        private float z;
        private float previousRenderX;
        private float previousRenderY;
        private float previousRenderZ;
        private float velocityX;
        private float velocityY;
        private float velocityZ;
        private final float orbitAxisX;
        private final float orbitAxisY;
        private final float orbitAxisZ;
        private final float orbitDirection;
        private final float floatSpeed;
        private float orbitRadius;
        private float targetOrbitRadius;

        private DisruptorParticle(Random random) {
            float[] initialDirection = randomUnit3(random);
            float initialDistance = 400.0f * random.nextFloat();
            x = initialDirection[0] * initialDistance;
            y = initialDirection[1] * initialDistance;
            z = initialDirection[2] * initialDistance;
            previousRenderX = x;
            previousRenderY = y;
            previousRenderZ = z;

            floatSpeed = lerp(2.0f, 8.0f, random.nextFloat());
            float[] initialVelocity = randomUnit3(random);
            velocityX = initialVelocity[0] * floatSpeed;
            velocityY = initialVelocity[1] * floatSpeed;
            velocityZ = initialVelocity[2] * floatSpeed;
            orbitRadius = targetOrbitRadius = length(x, y, z);
            float[] axis = randomUnit3(random);
            orbitAxisX = axis[0];
            orbitAxisY = axis[1];
            orbitAxisZ = axis[2];
            orbitDirection = random.nextBoolean() ? -1.0f : 1.0f;
        }

        private void beginClientTick() {
            previousRenderX = x;
            previousRenderY = y;
            previousRenderZ = z;
        }

        private void update(Random random) {
            x += velocityX;
            y += velocityY;
            z += velocityZ;
            if (random.nextFloat() < 0.01f) {
                targetOrbitRadius = lerp(50.0f, 400.0f, random.nextFloat());
            }
            orbitRadius = lerp(orbitRadius, targetOrbitRadius, 0.01f);

            float distance = Math.max(0.001f, length(x, y, z));
            float radialX = x / distance;
            float radialY = y / distance;
            float radialZ = z / distance;
            float tangentX = orbitAxisY * radialZ - orbitAxisZ * radialY;
            float tangentY = orbitAxisZ * radialX - orbitAxisX * radialZ;
            float tangentZ = orbitAxisX * radialY - orbitAxisY * radialX;
            float tangentLengthSquared = tangentX * tangentX
                    + tangentY * tangentY + tangentZ * tangentZ;
            if (tangentLengthSquared < 0.0001f) {
                // Vector3.Cross(UnitY, radial)
                tangentX = radialZ;
                tangentY = 0.0f;
                tangentZ = -radialX;
                tangentLengthSquared = tangentX * tangentX + tangentZ * tangentZ;
            }
            float inverseTangentLength = 1.0f
                    / (float) Math.sqrt(Math.max(0.000001f, tangentLengthSquared));
            tangentX *= inverseTangentLength * orbitDirection;
            tangentY *= inverseTangentLength * orbitDirection;
            tangentZ *= inverseTangentLength * orbitDirection;

            float correctionAmount = (orbitRadius - distance) * 0.05f;
            float correctionX = radialX * correctionAmount;
            float correctionY = radialY * correctionAmount;
            float correctionZ = radialZ * correctionAmount;
            x += correctionX;
            y += correctionY;
            z += correctionZ;
            velocityX += correctionX;
            velocityY += correctionY;
            velocityZ += correctionZ;

            float tangentAcceleration = lerpMap(distance, 400.0f, 100.0f, 0.01f, 0.15f);
            velocityX += tangentX * tangentAcceleration;
            velocityY += tangentY * tangentAcceleration;
            velocityZ += tangentZ * tangentAcceleration;

            float velocityLength = length(velocityX, velocityY, velocityZ);
            if (velocityLength > 0.0001f) {
                float targetSpeed = floatSpeed
                        / (lerp(orbitRadius, 100.0f, 0.5f) * 0.01f);
                float targetX = velocityX / velocityLength * targetSpeed;
                float targetY = velocityY / velocityLength * targetSpeed;
                float targetZ = velocityZ / velocityLength * targetSpeed;
                velocityX = lerp(velocityX, targetX, 0.01f);
                velocityY = lerp(velocityY, targetY, 0.01f);
                velocityZ = lerp(velocityZ, targetZ, 0.01f);
            }
        }

        private Vec3d interpolatedOffset(float tickDelta) {
            float delta = MathHelper.clamp(tickDelta, 0.0f, 1.0f);
            return new Vec3d(
                    MathHelper.lerp(delta, previousRenderX, x),
                    MathHelper.lerp(delta, previousRenderY, y),
                    MathHelper.lerp(delta, previousRenderZ, z));
        }

        private static float[] randomUnit3(Random random) {
            float z = lerp(-1.0f, 1.0f, random.nextFloat());
            float angle = random.nextFloat() * MathHelper.TAU;
            float radial = (float) Math.sqrt(Math.max(0.0f, 1.0f - z * z));
            return new float[]{radial * MathHelper.cos(angle), z,
                    radial * MathHelper.sin(angle)};
        }

        private static float length(float x, float y, float z) {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }

        private static float lerpMap(float value, float fromA, float toA,
                                     float fromB, float toB) {
            return lerp(fromB, toB,
                    MathHelper.clamp((value - fromA) / (toA - fromA), 0.0f, 1.0f));
        }
    }
}
