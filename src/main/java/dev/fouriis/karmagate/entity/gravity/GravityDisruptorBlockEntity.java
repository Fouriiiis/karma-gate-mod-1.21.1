package dev.fouriis.karmagate.entity.gravity;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.Random;

/**
 * Client-side port of GravityDisruptor3D's 16-panel light field. The source
 * simulation runs at 40 Hz, so every Minecraft client tick advances it twice.
 */
public final class GravityDisruptorBlockEntity extends BlockEntity {
    public static final int PANEL_COUNT = 16;

    private final Random random = new Random(19331L);
    private final float[][] lights = new float[PANEL_COUNT][4];
    private final float[] previousPanelValues = new float[PANEL_COUNT];
    private final float[] panelValues = new float[PANEL_COUNT];

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
        disruptor.updateLights();
        disruptor.updateLights();
        for (int i = 0; i < PANEL_COUNT; i++) {
            disruptor.panelValues[i] = disruptor.lights[i][0];
        }
    }

    public float getPanelValue(int index, float tickDelta) {
        if (index < 0 || index >= PANEL_COUNT) return 0.0f;
        return MathHelper.lerp(MathHelper.clamp(tickDelta, 0.0f, 1.0f),
                previousPanelValues[index], panelValues[index]);
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
}
