package dev.fouriis.karmagate.client.gridproject;

import dev.fouriis.karmagate.network.StarMatrixSyncPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recreates and animates Rain World-style StarMatrices, then projects them onto projection-zone walls.
 */
public final class StarMatrixPatternManager {
    public static final int MAX_STAR_DOTS = 128;
    public static final int MAX_STAR_LINES = 160;
    public static final int MAX_STAR_RINGS = 96;

    private static final StarMatrixPatternManager INSTANCE = new StarMatrixPatternManager();
    private static final float TAU = (float) (Math.PI * 2.0);

    private final Map<String, List<Definition>> definitionsByZone = new HashMap<>();
    private final Map<String, RuntimeMatrix> runtimesByName = new HashMap<>();
    private final Map<String, PackedStarMatrixData> packedDataByZone = new HashMap<>();

    private StarMatrixPatternManager() {}

    public static StarMatrixPatternManager getInstance() {
        return INSTANCE;
    }

    public void applySync(List<StarMatrixSyncPayload.Entry> entries) {
        definitionsByZone.clear();
        runtimesByName.clear();
        packedDataByZone.clear();

        for (StarMatrixSyncPayload.Entry entry : entries) {
            Definition def = new Definition(
                entry.name(),
                entry.zoneName(),
                new Vec3d(entry.x() + 0.5, entry.y() + 0.5, entry.z() + 0.5),
                computeSeed(entry)
            );
            definitionsByZone.computeIfAbsent(def.zoneName(), key -> new ArrayList<>()).add(def);
        }

        for (List<Definition> zoneDefs : definitionsByZone.values()) {
            zoneDefs.sort(Comparator.comparing(Definition::name));
        }
    }

    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            packedDataByZone.clear();
            return;
        }

        Camera camera = client.gameRenderer.getCamera();
        Vec3d camPos = camera.getPos();
        float tickDelta = client.getRenderTickCounter().getTickDelta(true);

        Set<String> validZoneNames = new HashSet<>();
        for (ProjectionZone zone : ProjectionZone.getZones()) {
            validZoneNames.add(zone.getName());
            tickZone(zone, camPos, tickDelta);
        }

        packedDataByZone.keySet().removeIf(name -> !validZoneNames.contains(name));
    }

    public PackedStarMatrixData getPackedData(String zoneName) {
        return packedDataByZone.get(zoneName);
    }

    public void clear() {
        definitionsByZone.clear();
        runtimesByName.clear();
        packedDataByZone.clear();
    }

    private void tickZone(ProjectionZone zone, Vec3d camPos, float tickDelta) {
        PackedStarMatrixData packed = packedDataByZone.computeIfAbsent(zone.getName(), key -> new PackedStarMatrixData());
        packed.clear();

        List<Definition> defs = definitionsByZone.get(zone.getName());
        if (defs == null || defs.isEmpty()) {
            return;
        }

        for (Definition def : defs) {
            RuntimeMatrix runtime = runtimesByName.computeIfAbsent(def.name(), key -> new RuntimeMatrix(def));
            runtime.tick(zone);
            runtime.packInto(zone, camPos, tickDelta, packed);

            if (packed.dotCount >= MAX_STAR_DOTS
                && packed.lineCount >= MAX_STAR_LINES
                && packed.ringCount >= MAX_STAR_RINGS) {
                break;
            }
        }
    }

    private static long computeSeed(StarMatrixSyncPayload.Entry entry) {
        long seed = 1125899906842597L;
        seed = seed * 31L + entry.name().hashCode();
        seed = seed * 31L + entry.zoneName().hashCode();
        seed = seed * 31L + entry.x();
        seed = seed * 31L + entry.y();
        seed = seed * 31L + entry.z();
        return seed;
    }

    public static final class PackedStarMatrixData {
        public int dotCount;
        public int lineCount;
        public int ringCount;
        public final float[] dots = new float[MAX_STAR_DOTS * 4];
        public final float[] lines = new float[MAX_STAR_LINES * 4];
        public final float[] rings = new float[MAX_STAR_RINGS * 4];

        public void clear() {
            dotCount = 0;
            lineCount = 0;
            ringCount = 0;
            Arrays.fill(dots, 0f);
            Arrays.fill(lines, 0f);
            Arrays.fill(rings, 0f);
        }
    }

    private record Definition(String name, String zoneName, Vec3d worldPos, long seed) {}

    private static final class RuntimeMatrix {
        private final Definition definition;
        private final Random random;
        private final List<StarNode> stars = new ArrayList<>();

        private boolean initialized;
        private double displayRadius;

        private Vec3d focus = Vec3d.ZERO;
        private Vec3d lastFocus = Vec3d.ZERO;
        private Vec3d targetFocus = Vec3d.ZERO;

        private float lastXRot;
        private float xRot;
        private float xRotVel;
        private float xRotAccel;

        private float lastYRot;
        private float yRot;
        private float yRotVel;
        private float yRotAccel;

        private float lastZRot;
        private float zRot;
        private float zRotVel;
        private float zRotAccel;

        private RuntimeMatrix(Definition definition) {
            this.definition = definition;
            this.random = Random.create(definition.seed());
        }

        public String zoneName() {
            return definition.zoneName();
        }

        void tick(ProjectionZone zone) {
            if (!initialized) {
                initialize(zone);
            }

            lastFocus = focus;
            focus = moveTowards(focus, targetFocus, 0.002).lerp(targetFocus, 0.007);

            if (random.nextFloat() < 0.025f && !stars.isEmpty()) {
                StarNode selected = stars.get(random.nextInt(stars.size()));
                if (selected.position.length() < 0.6) {
                    targetFocus = selected.position.add(randomInsideUnitSphere(random).multiply(0.05));
                }
            }

            lastXRot = xRot;
            lastYRot = yRot;
            lastZRot = zRot;

            xRot += xRotVel;
            yRot += yRotVel;
            zRot += zRotVel;

            xRotVel = MathHelper.clamp(xRotVel, -0.1f, 0.1f) * 0.9f;
            yRotVel = MathHelper.clamp(yRotVel, -0.1f, 0.1f) * 0.9f;
            zRotVel = MathHelper.clamp(zRotVel, -0.1f, 0.1f) * 0.9f;

            xRotVel += xRotAccel;
            yRotVel += yRotAccel;
            zRotVel += zRotAccel;

            xRotAccel = MathHelper.clamp(xRotAccel, -0.01f, 0.01f) * 0.9f;
            yRotAccel = MathHelper.clamp(yRotAccel, -0.01f, 0.01f) * 0.9f;
            zRotAccel = MathHelper.clamp(zRotAccel, -0.01f, 0.01f) * 0.9f;

            if (random.nextFloat() < 0.05f) {
                xRotAccel += MathHelper.lerp(random.nextFloat(), -1f, 1f) * (float) Math.pow(random.nextFloat(), 6.0) * 0.005f;
            }
            if (random.nextFloat() < 0.05f) {
                yRotAccel += MathHelper.lerp(random.nextFloat(), -1f, 1f) * (float) Math.pow(random.nextFloat(), 6.0) * 0.005f;
            }
            if (random.nextFloat() < 0.05f) {
                zRotAccel += MathHelper.lerp(random.nextFloat(), -1f, 1f) * (float) Math.pow(random.nextFloat(), 6.0) * 0.005f;
            }

            for (StarNode star : stars) {
                star.tick(random);
            }
        }

        void packInto(ProjectionZone zone, Vec3d camPos, float tickDelta, PackedStarMatrixData packed) {
            if (!initialized || stars.isEmpty()) {
                return;
            }

            Vec3d interpFocus = lastFocus.lerp(focus, tickDelta);
            float interpXRot = MathHelper.lerp(tickDelta, lastXRot, xRot) * TAU;
            float interpYRot = MathHelper.lerp(tickDelta, lastYRot, yRot) * TAU;
            float interpZRot = MathHelper.lerp(tickDelta, lastZRot, zRot) * TAU;

            ProjectedPoint[] points = new ProjectedPoint[stars.size()];

            for (int i = 0; i < stars.size(); i++) {
                StarNode star = stars.get(i);
                Vec3d rotated = rotate(star.position.subtract(interpFocus), interpXRot, interpYRot, interpZRot);
                Vec3d worldPos = definition.worldPos.add(rotated.multiply(displayRadius));
                points[i] = projectOntoZone(zone, camPos, worldPos);
            }

            for (int i = 0; i < stars.size(); i++) {
                ProjectedPoint point = points[i];
                if (point == null) {
                    continue;
                }

                if (packed.dotCount < MAX_STAR_DOTS) {
                    int base = packed.dotCount * 4;
                    packed.dots[base] = point.u;
                    packed.dots[base + 1] = point.y;
                    packed.dots[base + 2] = 0.18f;
                    packed.dots[base + 3] = 1.0f;
                    packed.dotCount++;
                }

                StarNode star = stars.get(i);
                for (int ring = 0; ring < star.ringCount && packed.ringCount < MAX_STAR_RINGS; ring++) {
                    int base = packed.ringCount * 4;
                    packed.rings[base] = point.u;
                    packed.rings[base + 1] = point.y;
                    packed.rings[base + 2] = 0.42f + ring * 0.28f;
                    packed.rings[base + 3] = Math.max(0.3f, 0.9f - ring * 0.18f);
                    packed.ringCount++;
                }
            }

            for (int i = 0; i < stars.size() && packed.lineCount < MAX_STAR_LINES; i++) {
                StarNode star = stars.get(i);
                ProjectedPoint from = points[i];
                if (from == null) {
                    continue;
                }

                for (int connectionIndex = 0; connectionIndex < star.connections.length && packed.lineCount < MAX_STAR_LINES; connectionIndex++) {
                    if (!star.connectionsOn[connectionIndex]) {
                        continue;
                    }

                    int targetIndex = star.connections[connectionIndex];
                    if (targetIndex < 0 || targetIndex >= points.length) {
                        continue;
                    }

                    ProjectedPoint to = points[targetIndex];
                    if (to == null) {
                        continue;
                    }

                    int base = packed.lineCount * 4;
                    packed.lines[base] = from.u;
                    packed.lines[base + 1] = from.y;
                    packed.lines[base + 2] = to.u;
                    packed.lines[base + 3] = to.y;
                    packed.lineCount++;
                }
            }
        }

        private void initialize(ProjectionZone zone) {
            this.displayRadius = MathHelper.clamp(zone.getRadiusf() * 0.14f + 2.0f, 4.0f, 9.0f);

            float syntheticRad = (float) (displayRadius * 9.0);
            float densityRad = Math.max(0f, MathHelper.lerp(0.3f, syntheticRad, 500f));
            int starCount = MathHelper.clamp((int) ((densityRad * densityRad * Math.PI) / 4000.0), 16, 40);

            stars.clear();
            for (int index = 0; index < starCount; index++) {
                Vec3d position = randomInsideUnitSphere(random);
                int ringCount = 0;
                if (random.nextFloat() >= 0.5f) {
                    int outerUpperExclusive = nextIntInclusive(random, 1, 3) + 1;
                    ringCount = random.nextInt(outerUpperExclusive);
                }

                int targetConnections = index > 10 ? nextIntInclusive(random, 1, 2) : 0;
                List<Integer> connections = new ArrayList<>();
                for (int attempt = 0; attempt < targetConnections * 100; attempt++) {
                    int candidateIndex = random.nextInt(index);
                    if (position.distanceTo(stars.get(candidateIndex).position) < 0.4
                        && !connections.contains(candidateIndex)) {
                        connections.add(candidateIndex);
                    }
                    if (connections.size() >= targetConnections) {
                        break;
                    }
                }

                boolean[] connectionsOn = new boolean[connections.size()];
                for (int i = 0; i < connectionsOn.length; i++) {
                    connectionsOn[i] = random.nextFloat() < 0.5f;
                }

                stars.add(new StarNode(position, ringCount, toIntArray(connections), connectionsOn));
            }

            initialized = true;
        }
    }

    private static final class StarNode {
        private final Vec3d position;
        private final int ringCount;
        private final int[] connections;
        private final boolean[] connectionsOn;

        private StarNode(Vec3d position, int ringCount, int[] connections, boolean[] connectionsOn) {
            this.position = position;
            this.ringCount = ringCount;
            this.connections = connections;
            this.connectionsOn = connectionsOn;
        }

        void tick(Random random) {
            if (connections.length > 0 && random.nextFloat() < 0.05f) {
                int index = random.nextInt(connections.length);
                connectionsOn[index] = random.nextFloat() < 0.5f;
            }
        }
    }

    private record ProjectedPoint(float u, float y) {}

    private static Vec3d rotate(Vec3d vector, float xRot, float yRot, float zRot) {
        double x = vector.x;
        double y = vector.y;
        double z = vector.z;

        double y1 = y * Math.cos(xRot) - z * Math.sin(xRot);
        double z1 = y * Math.sin(xRot) + z * Math.cos(xRot);
        y = y1;
        z = z1;

        double z2 = z * Math.cos(yRot) - x * Math.sin(yRot);
        double x2 = z * Math.sin(yRot) + x * Math.cos(yRot);
        z = z2;
        x = x2;

        double x3 = x * Math.cos(zRot) - y * Math.sin(zRot);
        double y3 = x * Math.sin(zRot) + y * Math.cos(zRot);
        return new Vec3d(x3, y3, z);
    }

    private static ProjectedPoint projectOntoZone(ProjectionZone zone, Vec3d cameraPos, Vec3d worldPos) {
        double dirX = worldPos.x - cameraPos.x;
        double dirY = worldPos.y - cameraPos.y;
        double dirZ = worldPos.z - cameraPos.z;

        double lenXZ = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (lenXZ < 1.0e-3) {
            return new ProjectedPoint(computeAnglePerimeterU(worldPos.x, worldPos.z, zone), (float) worldPos.y);
        }

        double minX = zone.getMinX();
        double maxX = zone.getMaxX() + 1.0;
        double minZ = zone.getMinZ();
        double maxZ = zone.getMaxZ() + 1.0;

        double bestT = Double.MAX_VALUE;

        if (dirX < -1.0e-3) {
            double t = (minX - cameraPos.x) / dirX;
            if (t > 1.0) {
                double hitZ = cameraPos.z + dirZ * t;
                if (hitZ >= minZ && hitZ <= maxZ) {
                    bestT = Math.min(bestT, t);
                }
            }
        }
        if (dirX > 1.0e-3) {
            double t = (maxX - cameraPos.x) / dirX;
            if (t > 1.0) {
                double hitZ = cameraPos.z + dirZ * t;
                if (hitZ >= minZ && hitZ <= maxZ) {
                    bestT = Math.min(bestT, t);
                }
            }
        }
        if (dirZ < -1.0e-3) {
            double t = (minZ - cameraPos.z) / dirZ;
            if (t > 1.0) {
                double hitX = cameraPos.x + dirX * t;
                if (hitX >= minX && hitX <= maxX) {
                    bestT = Math.min(bestT, t);
                }
            }
        }
        if (dirZ > 1.0e-3) {
            double t = (maxZ - cameraPos.z) / dirZ;
            if (t > 1.0) {
                double hitX = cameraPos.x + dirX * t;
                if (hitX >= minX && hitX <= maxX) {
                    bestT = Math.min(bestT, t);
                }
            }
        }

        if (bestT < Double.MAX_VALUE - 1.0) {
            double wallX = cameraPos.x + dirX * bestT;
            double wallY = cameraPos.y + dirY * bestT;
            double wallZ = cameraPos.z + dirZ * bestT;
            return new ProjectedPoint(computeAnglePerimeterU(wallX, wallZ, zone), (float) wallY);
        }

        double projX = zone.getCenterX() + (dirX / lenXZ) * zone.getRadius();
        double projZ = zone.getCenterZ() + (dirZ / lenXZ) * zone.getRadius();
        return new ProjectedPoint(computeAnglePerimeterU(projX, projZ, zone), (float) worldPos.y);
    }

    private static float computeAnglePerimeterU(double worldX, double worldZ, ProjectionZone zone) {
        double relX = worldX - zone.getCenterX();
        double relZ = worldZ - zone.getCenterZ();
        double angle = Math.atan2(relZ, relX);
        double u01 = (angle + Math.PI) / (Math.PI * 2.0);
        return (float) (u01 * ProjectionMath.getPerimeterLength(zone.getRadiusf()));
    }

    private static Vec3d moveTowards(Vec3d from, Vec3d to, double maxDistanceDelta) {
        Vec3d delta = to.subtract(from);
        double length = delta.length();
        if (length <= maxDistanceDelta || length < 1.0e-8) {
            return to;
        }
        return from.add(delta.multiply(maxDistanceDelta / length));
    }

    private static Vec3d randomInsideUnitSphere(Random random) {
        for (int attempt = 0; attempt < 32; attempt++) {
            double x = random.nextDouble() * 2.0 - 1.0;
            double y = random.nextDouble() * 2.0 - 1.0;
            double z = random.nextDouble() * 2.0 - 1.0;
            Vec3d vec = new Vec3d(x, y, z);
            if (vec.lengthSquared() <= 1.0) {
                return vec;
            }
        }
        return Vec3d.ZERO;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    private static int nextIntInclusive(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
