package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.entity.GraffitiEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public final class GraffitiConfigHandler {
    private static final float CORNER_SELECT_RADIUS = 0.18f;
    private static final float MAX_EDIT_DISTANCE = 10f;

    private static boolean wasUsePressed = false;

    private GraffitiConfigHandler() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(GraffitiConfigHandler::onClientTick);
    }

    private static void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (client.currentScreen != null) {
            wasUsePressed = client.options.useKey.isPressed();
            return;
        }
        if (GraffitiCornerHandler.isDragging()) {
            wasUsePressed = client.options.useKey.isPressed();
            return;
        }

        boolean usePressed = client.options.useKey.isPressed();
        if (usePressed && !wasUsePressed) {
            GraffitiEntity target = findTargetGraffiti(client);
            if (target != null) {
                client.setScreen(new GraffitiConfigScreen(target));
            }
        }
        wasUsePressed = usePressed;
    }

    private static GraffitiEntity findTargetGraffiti(MinecraftClient client) {
        Vec3d playerPos = client.player.getEyePos();
        Vec3d lookDir = client.player.getRotationVec(1.0f);

        List<GraffitiEntity> nearbyGraffiti = client.world.getEntitiesByClass(
            GraffitiEntity.class,
            new Box(playerPos.subtract(MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE),
                    playerPos.add(MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE, MAX_EDIT_DISTANCE)),
            e -> true
        );

        double closestT = Double.MAX_VALUE;
        GraffitiEntity closest = null;

        for (GraffitiEntity graffiti : nearbyGraffiti) {
            Double hitT = rayHitGraffiti(playerPos, lookDir, graffiti);
            if (hitT != null && hitT < closestT) {
                closestT = hitT;
                closest = graffiti;
            }
        }

        return closest;
    }

    private static Double rayHitGraffiti(Vec3d rayOrigin, Vec3d rayDir, GraffitiEntity graffiti) {
        Direction facing = graffiti.getFacing();
        Vec3d planeNormal = new Vec3d(facing.getOffsetX(), facing.getOffsetY(), facing.getOffsetZ());
        Vec3d planePoint = graffiti.getPos();

        Double t = rayPlaneIntersect(rayOrigin, rayDir, planePoint, planeNormal);
        if (t == null || t < 0.0 || t > MAX_EDIT_DISTANCE) return null;

        Vec3d hitPoint = rayOrigin.add(rayDir.multiply(t));
        float[][] corners = graffiti.getCorners();
        Direction rightDir = facing.rotateYClockwise();
        Vec3d relative = hitPoint.subtract(planePoint);

        float h = (float) (relative.x * rightDir.getOffsetX() + relative.z * rightDir.getOffsetZ());
        float v = (float) relative.y;

        float[] uv = inverseBilinear(h, v,
            corners[0][0], corners[0][1],
            corners[1][0], corners[1][1],
            corners[2][0], corners[2][1],
            corners[3][0], corners[3][1]
        );

        float u = uv[0];
        float vv = uv[1];
        float pad = 0.02f;
        if (u >= -pad && u <= 1.0f + pad && vv >= -pad && vv <= 1.0f + pad) {
            return t;
        }

        return null;
    }

    private static Double rayPlaneIntersect(Vec3d rayOrigin, Vec3d rayDir, Vec3d planePoint, Vec3d planeNormal) {
        double denom = rayDir.dotProduct(planeNormal);
        if (Math.abs(denom) < 0.0001) return null;

        double t = planePoint.subtract(rayOrigin).dotProduct(planeNormal) / denom;
        return t < 0.0 ? null : t;
    }

    private static float[] inverseBilinear(float px, float py,
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

    private static float cross(float ax, float ay, float bx, float by) {
        return ax * by - ay * bx;
    }
}
