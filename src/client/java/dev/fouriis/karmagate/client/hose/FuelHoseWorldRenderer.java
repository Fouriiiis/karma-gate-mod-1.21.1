package dev.fouriis.karmagate.client.hose;

import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.hose.FuelHoseData;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class FuelHoseWorldRenderer {
    private static final Vector3f WORLD_UP = new Vector3f(0.0f, 1.0f, 0.0f);
    private static final Vector3f WORLD_RIGHT = new Vector3f(1.0f, 0.0f, 0.0f);

    // Your model is stretched along local Z.
    // Change this to 0, 90, or -90 if the pipe's texture/details are rotated wrong,
    // but this value will stay constant for every segment.
    private static final float MODEL_ROLL_DEGREES = -90.0f;

    private FuelHoseWorldRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(FuelHoseWorldRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (
            client.world == null ||
            context.camera() == null ||
            context.matrixStack() == null ||
            context.consumers() == null
        ) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        Vec3d cameraPos = context.camera().getPos();

        var worldKey = client.world.getRegistryKey();
        var blockRenderManager = client.getBlockRenderManager();

        int light = 0xF000F0;
        int overlay = 0;

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        for (FuelHoseData hose : FuelHoseClientState.getHoses()) {
            if (!hose.dimension().equals(worldKey)) {
                continue;
            }

            var points = hose.points();

            for (int i = 0; i < points.size() - 1; i++) {
                Vec3d a = points.get(i);
                Vec3d b = points.get(i + 1);

                Vec3d delta = b.subtract(a);
                double length = delta.length();

                if (length < 1.0e-5) {
                    continue;
                }

                Vector3f direction = new Vector3f(
                    (float) delta.x,
                    (float) delta.y,
                    (float) delta.z
                ).normalize();

                Quaternionf rotation = makeNoTwistRotation(direction);

                matrices.push();

                matrices.translate(
                    a.x + delta.x * 0.5,
                    a.y + delta.y * 0.5,
                    a.z + delta.z * 0.5
                );

                // Stable rotation: local Z points along the hose,
                // local Y stays as close to world-up as possible.
                matrices.multiply(rotation);

                // Fixed model correction only. This no longer changes per segment.
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MODEL_ROLL_DEGREES));

                // Stretch along the pipe model's actual length axis.
                matrices.scale(1.0f, 1.0f, (float) length);

                // Center the 0..1 block model around the segment midpoint.
                matrices.translate(-0.5, -0.5, -0.5);

                blockRenderManager.renderBlockAsEntity(
                    ModBlocks.FUEL_HOSE_SEGMENT.getDefaultState(),
                    matrices,
                    context.consumers(),
                    light,
                    overlay
                );

                matrices.pop();
            }
        }

        matrices.pop();
    }

    private static Quaternionf makeNoTwistRotation(Vector3f forward) {
        Vector3f up = new Vector3f(WORLD_UP);

        // Project world-up onto the plane perpendicular to the hose direction.
        projectOntoPlane(up, forward);

        // If the segment is almost vertical, world-up is unusable.
        // Fall back to world-right so vertical sections still have a stable roll.
        if (up.lengthSquared() < 1.0e-6f) {
            up.set(WORLD_RIGHT);
            projectOntoPlane(up, forward);
        }

        up.normalize();

        // Build a right-handed basis:
        // local X = right
        // local Y = up
        // local Z = forward, the hose length axis
        Vector3f right = new Vector3f(up).cross(forward).normalize();
        Vector3f correctedUp = new Vector3f(forward).cross(right).normalize();

        return quaternionFromAxes(right, correctedUp, forward);
    }

    private static void projectOntoPlane(Vector3f vector, Vector3f planeNormal) {
        float dot = vector.dot(planeNormal);

        vector.x -= planeNormal.x * dot;
        vector.y -= planeNormal.y * dot;
        vector.z -= planeNormal.z * dot;
    }

    private static Quaternionf quaternionFromAxes(Vector3f xAxis, Vector3f yAxis, Vector3f zAxis) {
        float m00 = xAxis.x;
        float m01 = yAxis.x;
        float m02 = zAxis.x;

        float m10 = xAxis.y;
        float m11 = yAxis.y;
        float m12 = zAxis.y;

        float m20 = xAxis.z;
        float m21 = yAxis.z;
        float m22 = zAxis.z;

        Quaternionf q = new Quaternionf();

        float trace = m00 + m11 + m22;

        if (trace > 0.0f) {
            float s = (float) Math.sqrt(trace + 1.0f) * 2.0f;

            q.w = 0.25f * s;
            q.x = (m21 - m12) / s;
            q.y = (m02 - m20) / s;
            q.z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1.0f + m00 - m11 - m22) * 2.0f;

            q.w = (m21 - m12) / s;
            q.x = 0.25f * s;
            q.y = (m01 + m10) / s;
            q.z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1.0f + m11 - m00 - m22) * 2.0f;

            q.w = (m02 - m20) / s;
            q.x = (m01 + m10) / s;
            q.y = 0.25f * s;
            q.z = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1.0f + m22 - m00 - m11) * 2.0f;

            q.w = (m10 - m01) / s;
            q.x = (m02 + m20) / s;
            q.y = (m12 + m21) / s;
            q.z = 0.25f * s;
        }

        return q.normalize();
    }
}