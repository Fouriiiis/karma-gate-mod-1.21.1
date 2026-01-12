package dev.fouriis.karmagate.client.swarmer;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single neuron swarmer entity with flocking behavior.
 * Based on Rain World's SSOracleSwarmer implementation.
 */
public class NeuronSwarmer {

    /**
     * Movement modes mirroring Rain World's SSOracleSwarmer.
     */
    public enum MovementMode {
        Swarm,
        SuckleMycelia,
        FollowDijkstra
    }

    // Position and physics
    public Vec3d position;
    public Vec3d lastPosition;
    public Vec3d velocity;

    /**
     * The "desired" movement direction for this tick (Rain World uses travelDirection).
     * This is normalized and then applied to velocity.
     */
    public Vec3d travelDirection;

    public Vec3d direction;
    public Vec3d lastDirection;
    public Vec3d lazyDirection;
    public Vec3d lastLazyDirection;

    // Rotation for visual effect
    public float rotation;
    public float lastRotation;
    public float revolveSpeed;

    // Behavior parameters (flattened version of SSOracleSwarmer.Behavior)
    private float torque;
    public float idealDistance;
    public float aimInFront;
    public float randomVibrations;
    public float behaviorLife;
    public float behaviorDeathSpeed;
    public boolean suckle;

    // Color (hue and saturation encoded)
    public float colorX;
    public float colorY;
    public float targetColorX;
    public float targetColorY;

    // Dominance for behavior propagation
    private float dominance;

    // Zone reference
    private final String zoneName;

    // Age tracking
    public int age = 0;
    public boolean markedForRemoval = false;

    // ---- Mode state (SSOracleSwarmer parity) ----
    public MovementMode mode = MovementMode.Swarm;

    // “Mycelium” proxy target
    private BlockPos suckleTarget;
    private boolean attachedToSuckle;
    private int onlySwarm = 0;

    // “Exit” target for FollowDijkstra
    private BlockPos dijkstraTarget;

    // “stuck” detection similar to SSOracleSwarmer.stuckList
    private final List<Vec3d> stuckList = new ArrayList<>();
    private int stuckListCounter = 10;

    // Work spreading / throttling like listBreakPoint in the original
    private int listBreakPoint = 0;

    private static final Random RANDOM = Random.create();

    public NeuronSwarmer(String zoneName, Vec3d spawnPosition) {
        this.zoneName = zoneName;
        this.position = spawnPosition;
        this.lastPosition = spawnPosition;

        // Random initial direction
        this.direction = randomNormalizedVector();
        this.lastDirection = direction;
        this.lazyDirection = direction;
        this.lastLazyDirection = direction;

        // Random initial velocity
        this.velocity = direction.multiply(0.1);

        // Rain World keeps a separate travelDirection and copies it into direction each tick.
        this.travelDirection = direction;

        // Initialize behavior
        initNewBehavior();

        // Initial rotation
        this.rotation = 0.25f;
        this.lastRotation = rotation;
    }

    /**
     * Initialize a new random behavior pattern.
     */
    public void initNewBehavior() {
        this.dominance = RANDOM.nextFloat();
        this.idealDistance = lerp(10f, 300f, RANDOM.nextFloat() * RANDOM.nextFloat());
        this.behaviorLife = 1f;
        this.behaviorDeathSpeed = 1f / lerp(40f, 220f, RANDOM.nextFloat());

        // Color: x = hue variant (0, 0.5, 1), y = saturation
        this.targetColorX = (float) RANDOM.nextInt(3) / 2f;
        this.targetColorY = RANDOM.nextFloat() < 0.75f ? 0f : 1f;
        if (colorX == 0 && colorY == 0) {
            colorX = targetColorX;
            colorY = targetColorY;
        }

        this.aimInFront = lerp(40f, 300f, RANDOM.nextFloat());
        this.torque = RANDOM.nextFloat() < 0.5f ? 0f : lerp(-1f, 1f, RANDOM.nextFloat());
        this.randomVibrations = RANDOM.nextFloat() * RANDOM.nextFloat() * RANDOM.nextFloat();
        this.revolveSpeed = (RANDOM.nextFloat() < 0.5f ? -1f : 1f) / lerp(15f, 65f, RANDOM.nextFloat());
        this.suckle = RANDOM.nextFloat() < 1f / 6f;
    }

    /**
     * Returns true if this behavior has expired.
     */
    public boolean isBehaviorDead() {
        return behaviorLife <= 0f;
    }

    /**
     * Returns the effective dominance of this swarmer's behavior.
     */
    public float getDominance() {
        if (isBehaviorDead()) return -1f;
        return dominance * (float) Math.pow(behaviorLife, 0.25);
    }

    /**
     * Main update tick for this swarmer.
     */
    public void tick(List<NeuronSwarmer> otherSwarmers, Vec3d zoneMin, Vec3d zoneMax, ClientWorld world) {
        age++;

        // Store last values
        lastPosition = position;
        lastDirection = direction;
        lastLazyDirection = lazyDirection;
        lastRotation = rotation;

        // Update rotation
        rotation += revolveSpeed;

        // Update lazy direction (smooth interpolation)
        lazyDirection = slerp(lazyDirection, direction, 0.06);

        // Rain World sets direction = travelDirection before computing movement.
        direction = travelDirection;

        // Per-mode movement logic
        if (mode == MovementMode.Swarm) {
            swarmBehavior(otherSwarmers, world);

            if (onlySwarm > 0) {
                onlySwarm--;
            } else if (suckle && RANDOM.nextFloat() < 0.10f && world != null) {
                // Pick a "mycelium" proxy: a random solid block with line-of-sight
                // (and avoid multiple swarmers targeting the same block, like the original).
                tryStartSuckle(otherSwarmers, world, zoneMin, zoneMax);
            } else {
                // Stuck detection (near terrain)
                if (isNearSolid(world)) {
                    if (stuckListCounter > 0) {
                        stuckListCounter--;
                    } else {
                        stuckList.add(0, position);
                        if (stuckList.size() > 10) {
                            stuckList.remove(stuckList.size() - 1);
                        }
                        stuckListCounter = 80;
                    }

                    // If we seem stuck, occasionally enter FollowDijkstra.
                    if (RANDOM.nextFloat() < 0.025f && stuckList.size() > 1) {
                        Vec3d oldest = stuckList.get(stuckList.size() - 1);
                        if (position.squaredDistanceTo(oldest) < (6.0 * 6.0)) {
                            startFollowDijkstra(zoneMin, zoneMax, world);
                        }
                    }
                }
            }
        } else if (mode == MovementMode.SuckleMycelia) {
            updateSuckle(world);
        } else if (mode == MovementMode.FollowDijkstra) {
            updateFollowDijkstra(world);
        }

        // Apply travelDirection to velocity (closer to Rain World's integration)
        // original: vel += travelDirection * 0.8; vel *= LerpMap(|vel|)
        velocity = velocity.add(travelDirection.multiply(0.08));
        double spd = velocity.length();
        double damp = lerpMap(spd, 0.2, 3.0, 1.0, 0.90);
        velocity = velocity.multiply(damp);

        // Clamp to avoid runaway (Minecraft scale is large vs RW pixels)
        double maxSpeed = 0.55;
        if (spd > maxSpeed) {
            velocity = velocity.normalize().multiply(maxSpeed);
        }

        // Check block collisions and adjust velocity before applying
        if (world != null) {
            handleBlockCollisions(world);
        }

        // Integrate position
        position = position.add(velocity);

        // Update direction based on velocity (RW: direction is travelDirection; here we keep both coherent)
        if (velocity.lengthSquared() > 0.0001) {
            travelDirection = velocity.normalize();
            direction = travelDirection;
        }

        // Boundary avoidance - stay within zone (analogue for RW terrain avoidance / aimap)
        double margin = 10.0;

        // Push back from boundaries
        Vec3d boundaryPush = Vec3d.ZERO;
        if (position.x < zoneMin.x + margin) {
            boundaryPush = boundaryPush.add(1, 0, 0);
        } else if (position.x > zoneMax.x - margin) {
            boundaryPush = boundaryPush.add(-1, 0, 0);
        }
        if (position.y < zoneMin.y + margin) {
            boundaryPush = boundaryPush.add(0, 1, 0);
        } else if (position.y > zoneMax.y - margin) {
            boundaryPush = boundaryPush.add(0, -1, 0);
        }
        if (position.z < zoneMin.z + margin) {
            boundaryPush = boundaryPush.add(0, 0, 1);
        } else if (position.z > zoneMax.z - margin) {
            boundaryPush = boundaryPush.add(0, 0, -1);
        }

        if (boundaryPush.lengthSquared() > 0) {
            velocity = velocity.add(boundaryPush.normalize().multiply(0.15));
        }

        // Clamp to zone bounds
        position = new Vec3d(
                clamp(position.x, zoneMin.x + 1, zoneMax.x - 1),
                clamp(position.y, zoneMin.y + 1, zoneMax.y - 1),
                clamp(position.z, zoneMin.z + 1, zoneMax.z - 1)
        );

        // Final collision check - push out of any solid blocks
        if (world != null) {
            BlockPos currentBlock = BlockPos.ofFloored(position);
            if (isBlockSolid(world, currentBlock)) {
                // We're inside a solid block, revert to last position
                position = lastPosition;
                velocity = velocity.multiply(-0.5); // Bounce back
            }
        }

        // Decay behavior life (rough parity with SSOracleSwarmer leader ticking down)
        if (!isBehaviorDead()) {
            behaviorLife -= behaviorDeathSpeed;
        }

        // If behavior died, start a new one
        if (isBehaviorDead()) {
            float oldColorX = targetColorX;
            initNewBehavior();
            // 75% chance to keep previous color
            if (RANDOM.nextFloat() < 0.75f) {
                targetColorX = oldColorX;
            }
        }

        // Smoothly interpolate color
        colorX = lerp(colorX, targetColorX, 0.05f);
        colorY = lerp(colorY, targetColorY, 0.05f);
    }

    /**
     * Swarm behavior tuned to be closer to Rain World's SSOracleSwarmer.
     */
    private void swarmBehavior(List<NeuronSwarmer> otherSwarmers, ClientWorld world) {
        // Try to stay close to Rain World's implementation. Differences:
        // - 3D space instead of 2D
        // - No aimap / terrain proximity map; we approximate with local solidity sampling.

        final double INTERACTION_RANGE = 16.0; // RW uses 400px; this maps better to Minecraft blocks

        Vec3d weightedNeighborPos = Vec3d.ZERO;
        float weightSum = 0f;
        float torqueSum = torque;
        float revolveSum = revolveSpeed;

        // "close" color blending similar to InverseLerp(0.9,1,num8)
        Vec3d colorSum = Vec3d.ZERO; // use (x,y,0)
        float colorW = 0f;

        float targetTorque = torque;
        float targetRevolve = revolveSpeed;

        int processed = 0;
        int breakIndex = -1;

        // Spread work over frames like SSOracleSwarmer.listBreakPoint
        int start = Math.max(0, Math.min(listBreakPoint, otherSwarmers.size()));
        for (int idx = start; idx < otherSwarmers.size(); idx++) {
            NeuronSwarmer other = otherSwarmers.get(idx);
            if (other == this || other.markedForRemoval) continue;
            if (other.mode == MovementMode.SuckleMycelia) continue;

            double dist = position.distanceTo(other.position);
            if (dist < INTERACTION_RANGE && dist > 1e-6) {
                float w = (float) inverseLerp(INTERACTION_RANGE, 0.0, dist);

                // Weighted centroid / parameters
                weightedNeighborPos = weightedNeighborPos.add(other.position.multiply(w));
                torqueSum += other.torque * w;
                revolveSum += other.revolveSpeed * w;
                weightSum += w;

                // Close-range color averaging
                float cw = (float) inverseLerp(0.90, 1.0, w);
                if (cw > 0f) {
                    colorSum = colorSum.add(new Vec3d(other.colorX, other.colorY, 0).multiply(cw));
                    colorW += cw;
                }

                // Steering toward neighbors + aimInFront prediction
                Vec3d predicted = other.position.add(other.travelDirection.multiply(aimInFront * w * 0.02));
                Vec3d toward = predicted.subtract(position);
                if (toward.lengthSquared() > 1e-6) {
                    travelDirection = travelDirection.add(toward.normalize().multiply(w * 0.01));
                }

                // Separation based on idealDistance (RW: InverseLerp(idealDistance,0,dist)*0.1)
                float sepW = (float) inverseLerp(idealDistance, 0.0, dist);
                if (sepW > 0f) {
                    Vec3d away = position.subtract(other.position);
                    if (away.lengthSquared() > 1e-6) {
                        travelDirection = travelDirection.add(away.normalize().multiply(sepW * 0.10));
                    }
                }

                // Dominance-based behavior adoption (RW uses pow(w,4))
                float otherDom = other.getDominance();
                float myDom = getDominance();
                if (myDom < otherDom * (float) Math.pow(w, 4.0)) {
                    adoptBehaviorFrom(other);
                }

                processed++;
                if (processed > 30) {
                    breakIndex = idx;
                    break;
                }
            }
        }

        listBreakPoint = (breakIndex >= 0) ? (breakIndex + 1) : 0;

        // Random vibrations
        travelDirection = travelDirection.add(randomNormalizedVector().multiply(0.5 * randomVibrations));

        // Torque/revolve averaging + orbit around centroid
        if (weightSum > 0f) {
            targetTorque = torqueSum / (1f + weightSum);
            targetRevolve = revolveSum / (1f + weightSum);

            Vec3d centroid = weightedNeighborPos.multiply(1.0 / weightSum);
            Vec3d toCentroid = centroid.subtract(position);
            if (toCentroid.lengthSquared() > 1e-6) {
                Vec3d perp = perpendicular3D(toCentroid).normalize();
                travelDirection = travelDirection.add(perp.multiply(torque));
            }
        }

        torque = lerp(torque, targetTorque, 0.10f);
        revolveSpeed = lerp(revolveSpeed, targetRevolve, 0.20f);

        // Color mixing
        if (colorW > 0f) {
            double cx = colorSum.x / colorW;
            double cy = colorSum.y / colorW;
            colorX = lerp(colorX, (float) cx, 0.40f);
            colorY = lerp(colorY, (float) cy, 0.40f);
        }
        colorX = lerp(colorX, targetColorX, 0.05f);
        colorY = lerp(colorY, targetColorY, 0.05f);

        // Terrain avoidance approximation (RW uses aimap terrain proximity)
        if (world != null && isNearSolid(world)) {
            Vec3d avoid = terrainAvoidanceVector(world);
            if (avoid.lengthSquared() > 1e-6) {
                travelDirection = lerpVec(travelDirection, avoid.normalize().multiply(2.0), 0.45);
            }
        }

        // Normalize like RW
        if (travelDirection.lengthSquared() > 1e-8) {
            travelDirection = travelDirection.normalize();
        }
    }

    private void adoptBehaviorFrom(NeuronSwarmer other) {
        // In Rain World, this swaps the entire Behavior struct reference.
        // We approximate by copying the behavior parameters as a bundle.
        this.dominance = other.dominance;
        this.idealDistance = other.idealDistance;
        this.aimInFront = other.aimInFront;
        this.torque = other.torque;
        this.randomVibrations = other.randomVibrations;
        this.revolveSpeed = other.revolveSpeed;
        this.behaviorLife = other.behaviorLife;
        this.behaviorDeathSpeed = other.behaviorDeathSpeed;
        this.suckle = other.suckle;
        this.targetColorX = other.targetColorX;
        this.targetColorY = other.targetColorY;
    }

    // ---- SuckleMycelia (proxy) ----

    private void tryStartSuckle(List<NeuronSwarmer> otherSwarmers, ClientWorld world, Vec3d zoneMin, Vec3d zoneMax) {
        // Try a handful of random solid blocks near the swarmer; pick the first with LoS.
        final int tries = 12;
        final int radius = 12;

        BlockPos base = BlockPos.ofFloored(position);
        for (int t = 0; t < tries; t++) {
            int dx = RANDOM.nextInt(radius * 2 + 1) - radius;
            int dy = RANDOM.nextInt(radius * 2 + 1) - radius;
            int dz = RANDOM.nextInt(radius * 2 + 1) - radius;

            BlockPos cand = base.add(dx, dy, dz);
            if (!isWithinZone(cand, zoneMin, zoneMax)) continue;
            if (!isBlockSolid(world, cand)) continue;

            Vec3d tip = new Vec3d(cand.getX() + 0.5, cand.getY() + 0.5, cand.getZ() + 0.5);
            if (position.squaredDistanceTo(tip) > (16.0 * 16.0)) continue;
            if (!hasLineOfSight(world, position, tip)) continue;

            boolean taken = false;
            for (NeuronSwarmer other : otherSwarmers) {
                if (other != this && other.mode == MovementMode.SuckleMycelia && cand.equals(other.suckleTarget)) {
                    taken = true;
                    break;
                }
            }
            if (taken) continue;

            mode = MovementMode.SuckleMycelia;
            suckleTarget = cand;
            attachedToSuckle = false;
            return;
        }
    }

    private void updateSuckle(ClientWorld world) {
        if (world == null || suckleTarget == null) {
            mode = MovementMode.Swarm;
            return;
        }

        Vec3d tip = new Vec3d(suckleTarget.getX() + 0.5, suckleTarget.getY() + 0.5, suckleTarget.getZ() + 0.5);

        if (attachedToSuckle) {
            // Lock/spring the swarmer to the target (RW uses a 2px-ish spring).
            Vec3d dirTo = tip.subtract(position);
            double dist = dirTo.length();
            if (dist > 1e-6) {
                Vec3d dirN = dirTo.multiply(1.0 / dist);
                // Match the RW spring math: vector = dir * ((2 - dist) * k)
                Vec3d v1 = dirN.multiply((2.0 - dist) * 0.15);
                velocity = velocity.subtract(v1);
                position = position.subtract(v1);
                travelDirection = Vec3d.ZERO;
            }

            // Occasionally detach (RW: 0.0125)
            if (RANDOM.nextFloat() < 0.0125f) {
                suckleTarget = null;
                mode = MovementMode.Swarm;
                onlySwarm = 40 + RANDOM.nextInt(361);
            }
        } else {
            // Approach
            travelDirection = tip.subtract(position);
            if (travelDirection.lengthSquared() > 1e-8) {
                travelDirection = travelDirection.normalize();
            }

            if (position.squaredDistanceTo(tip) < (0.8 * 0.8)) {
                attachedToSuckle = true;
            } else if (RANDOM.nextFloat() < 0.05f && !hasLineOfSight(world, position, tip)) {
                // Abort if LoS is lost
                suckleTarget = null;
                mode = MovementMode.Swarm;
            }
        }

        // In RW, color drifts toward currentBehavior.color; we approximate by drifting toward target.
        colorX = lerp(colorX, targetColorX, 0.05f);
        colorY = lerp(colorY, targetColorY, 0.05f);
    }

    // ---- FollowDijkstra (approximate) ----

    private void startFollowDijkstra(Vec3d zoneMin, Vec3d zoneMax, ClientWorld world) {
        if (world == null) return;
        mode = MovementMode.FollowDijkstra;

        // Choose a random point near the zone boundary as an "exit" target.
        int minX = (int) Math.floor(zoneMin.x + 1);
        int minY = (int) Math.floor(zoneMin.y + 1);
        int minZ = (int) Math.floor(zoneMin.z + 1);
        int maxX = (int) Math.floor(zoneMax.x - 1);
        int maxY = (int) Math.floor(zoneMax.y - 1);
        int maxZ = (int) Math.floor(zoneMax.z - 1);

        // Pick a boundary face
        int face = RANDOM.nextInt(6);
        int x = RANDOM.nextInt(maxX - minX + 1) + minX;
        int y = RANDOM.nextInt(maxY - minY + 1) + minY;
        int z = RANDOM.nextInt(maxZ - minZ + 1) + minZ;
        if (face == 0) x = minX;
        if (face == 1) x = maxX;
        if (face == 2) y = minY;
        if (face == 3) y = maxY;
        if (face == 4) z = minZ;
        if (face == 5) z = maxZ;
        dijkstraTarget = new BlockPos(x, y, z);
    }

    private void updateFollowDijkstra(ClientWorld world) {
        if (world == null || dijkstraTarget == null) {
            mode = MovementMode.Swarm;
            return;
        }

        BlockPos here = BlockPos.ofFloored(position);
        Direction bestDir = null;
        int bestScore = Integer.MAX_VALUE;

        // Original is 2D + aimap, but the spirit is: take the neighbor that reduces exit distance.
        for (Direction d : Direction.values()) {
            BlockPos nb = here.offset(d);
            if (isBlockSolid(world, nb)) continue;
            int score = manhattan(nb, dijkstraTarget);
            if (score < bestScore) {
                bestScore = score;
                bestDir = d;
            }
        }

        if (bestDir != null) {
            Vec3d dir = Vec3d.of(bestDir.getVector()).normalize();
            travelDirection = travelDirection.add(dir.multiply(1.4)).add(randomNormalizedVector().multiply(RANDOM.nextFloat() * 0.5));
        } else {
            mode = MovementMode.Swarm;
            return;
        }

        if (travelDirection.lengthSquared() > 1e-8) {
            travelDirection = travelDirection.normalize();
        }

        int dist = manhattan(here, dijkstraTarget);
        if ((RANDOM.nextFloat() < 0.025f && dist < 34)
                || dist < 12
                || RANDOM.nextFloat() < 0.0025f
                || (!isNearSolid(world) && RANDOM.nextFloat() < (1f / 60f))) {
            mode = MovementMode.Swarm;
        }
    }

    /**
     * Get the zone this swarmer belongs to.
     */
    public String getZoneName() {
        return zoneName;
    }

    // ========== Utility methods ==========

    private static Vec3d randomNormalizedVector() {
        double theta = RANDOM.nextDouble() * Math.PI * 2;
        double phi = Math.acos(2 * RANDOM.nextDouble() - 1);
        return new Vec3d(
                Math.sin(phi) * Math.cos(theta),
                Math.sin(phi) * Math.sin(theta),
                Math.cos(phi)
        );
    }

    private static Vec3d slerp(Vec3d a, Vec3d b, double t) {
        // Simplified slerp - just lerp and normalize for our purposes
        Vec3d result = new Vec3d(
                lerp(a.x, b.x, t),
                lerp(a.y, b.y, t),
                lerp(a.z, b.z, t)
        );
        double len = result.length();
        if (len > 0.0001) {
            return result.multiply(1.0 / len);
        }
        return a;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double lerpMap(double value, double inMin, double inMax, double outMin, double outMax) {
        double t = inverseLerp(inMin, inMax, value);
        return lerp(outMin, outMax, t);
    }

    private static double inverseLerp(double a, double b, double v) {
        if (Math.abs(b - a) < 1e-9) return 0.0;
        double t = (v - a) / (b - a);
        return clamp(t, 0.0, 1.0);
    }

    private static Vec3d lerpVec(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                lerp(a.x, b.x, t),
                lerp(a.y, b.y, t),
                lerp(a.z, b.z, t)
        );
    }

    private static Vec3d perpendicular3D(Vec3d v) {
        // Pick a stable perpendicular in 3D.
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d p = v.crossProduct(up);
        if (p.lengthSquared() < 1e-6) {
            p = v.crossProduct(new Vec3d(1, 0, 0));
        }
        return p;
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private static boolean isWithinZone(BlockPos p, Vec3d zoneMin, Vec3d zoneMax) {
        return p.getX() >= Math.floor(zoneMin.x) && p.getX() <= Math.floor(zoneMax.x)
                && p.getY() >= Math.floor(zoneMin.y) && p.getY() <= Math.floor(zoneMax.y)
                && p.getZ() >= Math.floor(zoneMin.z) && p.getZ() <= Math.floor(zoneMax.z);
    }

    private boolean hasLineOfSight(ClientWorld world, Vec3d from, Vec3d to) {
        // Manual LoS stepping to avoid version-specific RaycastContext signatures.
        Vec3d delta = to.subtract(from);
        double len = delta.length();
        if (len < 1e-6) return true;
        int steps = Math.max(1, (int) Math.ceil(len / 0.5));
        Vec3d step = delta.multiply(1.0 / steps);

        Vec3d p = from;
        for (int i = 0; i <= steps; i++) {
            BlockPos bp = BlockPos.ofFloored(p);
            if (isBlockSolid(world, bp)) return false;
            p = p.add(step);
        }
        return true;
    }

    private boolean isNearSolid(ClientWorld world) {
        if (world == null) return false;
        BlockPos p = BlockPos.ofFloored(position);
        if (isBlockSolid(world, p)) return true;
        for (Direction d : Direction.values()) {
            if (isBlockSolid(world, p.offset(d))) return true;
        }
        return false;
    }

    private Vec3d terrainAvoidanceVector(ClientWorld world) {
        // Sample neighbors and prefer directions with more open space.
        BlockPos p = BlockPos.ofFloored(position);
        Vec3d sum = Vec3d.ZERO;

        for (Direction d : Direction.values()) {
            BlockPos nb = p.offset(d);
            if (isBlockSolid(world, nb)) continue;

            int openness = 0;
            for (Direction d2 : Direction.values()) {
                if (!isBlockSolid(world, nb.offset(d2))) openness++;
            }
            sum = sum.add(Vec3d.of(d.getVector()).multiply(openness));
        }
        return sum;
    }

    /**
     * Handles block collisions by checking surrounding blocks and adjusting velocity.
     */
    private void handleBlockCollisions(ClientWorld world) {
        BlockPos currentBlock = BlockPos.ofFloored(position);
        double collisionMargin = 0.3; // How close to get before being repelled

        // Check all 6 directions for nearby solid blocks
        Vec3d pushForce = Vec3d.ZERO;

        // Check each axis
        for (int axis = 0; axis < 3; axis++) {
            for (int dir = -1; dir <= 1; dir += 2) {
                BlockPos checkPos = switch (axis) {
                    case 0 -> currentBlock.add(dir, 0, 0);
                    case 1 -> currentBlock.add(0, dir, 0);
                    case 2 -> currentBlock.add(0, 0, dir);
                    default -> currentBlock;
                };

                if (isBlockSolid(world, checkPos)) {
                    // Calculate distance to block face
                    double blockEdge = switch (axis) {
                        case 0 -> dir > 0 ? checkPos.getX() : checkPos.getX() + 1;
                        case 1 -> dir > 0 ? checkPos.getY() : checkPos.getY() + 1;
                        case 2 -> dir > 0 ? checkPos.getZ() : checkPos.getZ() + 1;
                        default -> 0;
                    };

                    double posComponent = switch (axis) {
                        case 0 -> position.x;
                        case 1 -> position.y;
                        case 2 -> position.z;
                        default -> 0;
                    };

                    double distToBlock = Math.abs(posComponent - blockEdge);

                    if (distToBlock < collisionMargin) {
                        // Push away from the block
                        double pushStrength = (collisionMargin - distToBlock) / collisionMargin * 0.2;
                        Vec3d push = switch (axis) {
                            case 0 -> new Vec3d(-dir * pushStrength, 0, 0);
                            case 1 -> new Vec3d(0, -dir * pushStrength, 0);
                            case 2 -> new Vec3d(0, 0, -dir * pushStrength);
                            default -> Vec3d.ZERO;
                        };
                        pushForce = pushForce.add(push);

                        // Also dampen velocity in this direction
                        double velComponent = switch (axis) {
                            case 0 -> velocity.x;
                            case 1 -> velocity.y;
                            case 2 -> velocity.z;
                            default -> 0;
                        };

                        // If moving toward the block, reduce/reverse that velocity
                        if ((dir > 0 && velComponent > 0) || (dir < 0 && velComponent < 0)) {
                            velocity = switch (axis) {
                                case 0 -> new Vec3d(velComponent * -0.3, velocity.y, velocity.z);
                                case 1 -> new Vec3d(velocity.x, velComponent * -0.3, velocity.z);
                                case 2 -> new Vec3d(velocity.x, velocity.y, velComponent * -0.3);
                                default -> velocity;
                            };
                        }
                    }
                }
            }
        }

        velocity = velocity.add(pushForce);

        // Also check the current block (in case we're inside one)
        if (isBlockSolid(world, currentBlock)) {
            // Find the nearest non-solid block and push toward it
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = currentBlock.add(dx, dy, dz);
                        if (!isBlockSolid(world, neighbor)) {
                            // Push toward this open space
                            Vec3d toOpen = new Vec3d(
                                    neighbor.getX() + 0.5 - position.x,
                                    neighbor.getY() + 0.5 - position.y,
                                    neighbor.getZ() + 0.5 - position.z
                            ).normalize();
                            velocity = velocity.add(toOpen.multiply(0.3));
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a block is solid (should be collided with).
     */
    private boolean isBlockSolid(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        // Check if the block has any collision shape
        return !state.getCollisionShape(world, pos).isEmpty();
    }
}
