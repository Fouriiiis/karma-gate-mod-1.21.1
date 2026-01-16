package dev.fouriis.karmagate.client.swarmer;

import dev.fouriis.karmagate.client.gridproject.IProjectedCircleOwner;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * Neuron swarmer with Rain World SSOracleSwarmer-style flocking.
 *
 * Key parity fixes vs your prior version:
 *  1) RW scale: Rain World tiles are 20px => 20px = 1 MC block (as you stated).
 *  2) DO NOT overwrite travelDirection from velocity each tick (RW does not do this).
 *     travelDirection is steering; velocity integrates from it.
 *  3) Behavior "death" semantics: RW Behavior is a struct and becomes dead if the LEADER's
 *     currentBehavior no longer equals that behavior (leader has moved on).
 *     We replicate this by: isDead() = life<=0 OR leader removed OR leader.currentBehavior != this.
 *  4) Terrain avoidance lerp factor shaped like RW (approx, since we don't have RW's AIMAP).
 */
public class NeuronSwarmer implements IProjectedCircleOwner {

    public enum MovementMode {
        Swarm,
        SuckleMycelia,
        FollowDijkstra
    }

    // --- RW scale mapping ---
    // User rule: Rain World tile = 20 pixels => 20 pixels = 1 MC block
    private static final double PX_PER_BLOCK = 20.0;
    private static final double RW = 1.0 / PX_PER_BLOCK; // pixels -> blocks

    // RW constants mapped to blocks
    private static final double INTERACTION_RANGE = 400.0 * RW; // 400px
    private static final double INTERACTION_RANGE_SQ = INTERACTION_RANGE * INTERACTION_RANGE;

    // RW integration: vel += travelDirection * 0.8 (px/frame)
    private static final double RW_ACCEL = 0.8 * RW;

    // RW damping map in px: LerpMap(|vel|, 0.2, 3.0, 1.0, 0.9)
    private static final double RW_DAMP_IN_MIN = 0.2 * RW;
    private static final double RW_DAMP_IN_MAX = 3.0 * RW;

    // Work budget like RW (stops at ~30 interacting neighbors)
    private static final int MAX_INTERACTING_NEIGHBORS = 30;

    // Position and physics
    public Vec3d position;
    public Vec3d lastPosition;
    public Vec3d velocity;

    // RW: travelDirection is STEERING, not "velocity direction"
    public Vec3d travelDirection;

    public Vec3d direction;
    public Vec3d lastDirection;
    public Vec3d lazyDirection;
    public Vec3d lastLazyDirection;

    // Rotation
    public float rotation;
    public float lastRotation;
    public float revolveSpeed;

    private float torque;

    // Color is per-swarmer and lerped toward behavior color
    public float colorX;
    public float colorY;

    // Shared behavior object (reference shared, but RW "dies" when leader moves on)
    public Behavior currentBehavior;

    // Zone reference
    private final String zoneName;

    // Age tracking
    public int age = 0;
    public boolean markedForRemoval = false;

    // ---- Mode state ----
    public MovementMode mode = MovementMode.Swarm;

    private BlockPos suckleTarget;
    private boolean attachedToSuckle;
    private int onlySwarm = 0;

    private BlockPos dijkstraTarget;

    private final List<Vec3d> stuckList = new ArrayList<>();
    private int stuckListCounter = 10;

    private int listBreakPoint = 0;

    private static final Random RANDOM = Random.create();

    /**
     * RW Behavior struct equivalent.
     * We keep it as an object for sharing-by-reference, but emulate RW's "dead if leader moved on" rule.
     */
    public static final class Behavior {
        private final float dom;

        public final float idealDistance;     // blocks
        public final float aimInFront;        // blocks
        public final float torque;            // [-1..1]
        public final float randomVibrations;  // 0..1-ish
        public final float revolveSpeed;      // tendency

        public float life;
        public final float deathSpeed;

        public final NeuronSwarmer leader;

        public float colorX;
        public float colorY;

        public final boolean suckle;

        public Behavior(NeuronSwarmer leader) {
            this.leader = leader;

            dom = RANDOM.nextFloat();

            // RW: idealDistance = Lerp(10,300, r^2) px
            idealDistance = (float) (lerp(10f, 300f, RANDOM.nextFloat() * RANDOM.nextFloat()) * RW);

            // RW: life=1; deathSpeed = 1 / Lerp(40,220)
            life = 1f;
            deathSpeed = 1f / lerp(40f, 220f, RANDOM.nextFloat());

            // RW: color = (rand{0,0.5,1}, sat either 0 or 1 25% of time)
            colorX = (float) (RANDOM.nextInt(3)) / 2f;
            colorY = RANDOM.nextFloat() < 0.75f ? 0f : 1f;

            // RW: aimInFront = Lerp(40,300) px
            aimInFront = (float) (lerp(40f, 300f, RANDOM.nextFloat()) * RW);

            // RW: torque = 50% 0 else Lerp(-1,1)
            torque = (RANDOM.nextFloat() < 0.5f) ? 0f : lerp(-1f, 1f, RANDOM.nextFloat());

            // RW: randomVibrations = r^3
            float rv = RANDOM.nextFloat();
            randomVibrations = rv * rv * rv;

            // RW: revolveSpeed = (+/-) / Lerp(15,65)
            revolveSpeed = (RANDOM.nextFloat() < 0.5f ? -1f : 1f) / lerp(15f, 65f, RANDOM.nextFloat());

            // RW: suckle chance 1/6
            suckle = RANDOM.nextFloat() < (1f / 6f);
        }

        /**
         * RW equivalence:
         * - dead if life <= 0
         * - OR leader removed
         * - OR leader.currentBehavior != this (leader moved on => everyone holding this treats it as dead)
         */
        public boolean isDead() {
            if (life <= 0f) return true;
            if (leader == null) return true;
            if (leader.markedForRemoval) return true;
            return leader.currentBehavior != this;
        }

        public float dominance() {
            if (isDead()) return -1f;
            return dom * (float) Math.pow(life, 0.25);
        }
    }

    public NeuronSwarmer(String zoneName, Vec3d spawnPosition) {
        this.zoneName = zoneName;
        this.position = spawnPosition;
        this.lastPosition = spawnPosition;

        this.travelDirection = randomNormalizedVector();
        this.direction = travelDirection;

        this.lastDirection = direction;
        this.lazyDirection = direction;
        this.lastLazyDirection = direction;

        this.velocity = Vec3d.ZERO; // RW starts calm

        // Own behavior initially
        this.currentBehavior = new Behavior(this);

        // Start with behavior color
        this.colorX = currentBehavior.colorX;
        this.colorY = currentBehavior.colorY;

        // Start torque/revolve close to behavior tendency
        this.torque = currentBehavior.torque;
        this.revolveSpeed = currentBehavior.revolveSpeed;

        this.rotation = 0.25f;
        this.lastRotation = rotation;
    }

    public void tick(List<NeuronSwarmer> otherSwarmers, Vec3d zoneMin, Vec3d zoneMax, ClientWorld world) {
        age++;

        lastPosition = position;
        lastDirection = direction;
        lastLazyDirection = lazyDirection;
        lastRotation = rotation;

        rotation += revolveSpeed;

        // RW: lazyDirection is smoothed visual direction
        lazyDirection = slerp(lazyDirection, direction, 0.06);

        // RW: direction = travelDirection at start of update
        direction = travelDirection;

        if (mode == MovementMode.Swarm) {
            swarmBehavior(otherSwarmers, world);

            if (onlySwarm > 0) {
                onlySwarm--;
            } else if (currentBehavior.suckle && RANDOM.nextFloat() < 0.10f && world != null) {
                tryStartSuckle(otherSwarmers, world, zoneMin, zoneMax);
            } else {
                if (isNearSolid(world)) {
                    if (stuckListCounter > 0) {
                        stuckListCounter--;
                    } else {
                        stuckList.add(0, position);
                        if (stuckList.size() > 10) stuckList.remove(stuckList.size() - 1);
                        stuckListCounter = 80;
                    }

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

        // === RW integration ===
        // vel += travelDirection * 0.8px
        velocity = velocity.add(travelDirection.multiply(RW_ACCEL));

        // vel *= LerpMap(|vel|, 0.2, 3, 1, 0.9)
        double spd = velocity.length();
        double damp = lerpMap(spd, RW_DAMP_IN_MIN, RW_DAMP_IN_MAX, 1.0, 0.90);
        velocity = velocity.multiply(damp);

        if (world != null) {
            handleBlockCollisions(world);
        }

        position = position.add(velocity);

        // IMPORTANT PARITY FIX:
        // RW does NOT do: travelDirection = velocity.normalized each tick.
        // We only update 'direction' for visuals if we're moving.
        if (velocity.lengthSquared() > 1e-8) {
            direction = velocity.normalize();
        }

        // Keep within zone (minimal clamp; no extra "boundary push" impulse)
        position = new Vec3d(
                clamp(position.x, zoneMin.x + 1, zoneMax.x - 1),
                clamp(position.y, zoneMin.y + 1, zoneMax.y - 1),
                clamp(position.z, zoneMin.z + 1, zoneMax.z - 1)
        );

        if (world != null) {
            BlockPos currentBlock = BlockPos.ofFloored(position);
            if (isBlockSolid(world, currentBlock)) {
                position = lastPosition;
                velocity = velocity.multiply(-0.5);
            }
        }

        // === RW behavior lifetime logic ===
        // In RW: if behavior is dead, create a new behavior; 75% chance keep old color.
        // Else: only the behavior leader decays the life each tick.
        if (currentBehavior.isDead()) {
            float oldCX = currentBehavior.colorX;
            float oldCY = currentBehavior.colorY;

            currentBehavior = new Behavior(this);
            if (RANDOM.nextFloat() > 0.25f) {
                currentBehavior.colorX = oldCX;
                currentBehavior.colorY = oldCY;
            }
        } else if (currentBehavior.leader == this && currentBehavior.leader.currentBehavior == currentBehavior) {
            currentBehavior.life -= currentBehavior.deathSpeed;
        }

        // Drift swarmer color toward behavior color (RW-ish)
        colorX = lerp(colorX, currentBehavior.colorX, 0.05f);
        colorY = lerp(colorY, currentBehavior.colorY, 0.05f);
    }

    /**
     * Near line-for-line port of SSOracleSwarmer.SwarmBehavior (extended to 3D).
     */
    private void swarmBehavior(List<NeuronSwarmer> otherSwarmers, ClientWorld world) {
        Vec3d centroidAccum = Vec3d.ZERO;
        float num = 0f;

        float torqueSum = currentBehavior.torque;
        float revolveSum = currentBehavior.revolveSpeed;

        // Color averaging for close neighbors
        float colAccumX = 0f;
        float colAccumY = 0f;
        float colW = 0f;

        // Rotation syncing (optional; kept)
        float rotAccum = 0f;

        int interacted = 0;
        int breakAt = -1;

        int start = Math.max(0, Math.min(listBreakPoint, otherSwarmers.size()));
        for (int i = start; i < otherSwarmers.size(); i++) {
            NeuronSwarmer other = otherSwarmers.get(i);
            if (other == this || other.markedForRemoval) continue;
            if (other.mode == MovementMode.SuckleMycelia) continue;

            double d2 = position.squaredDistanceTo(other.position);
            if (d2 < INTERACTION_RANGE_SQ) {
                double dist = Math.sqrt(Math.max(d2, 1e-12));

                // RW: num8 = InverseLerp(400,0,dist)
                float num8 = (float) inverseLerp(INTERACTION_RANGE, 0.0, dist);

                centroidAccum = centroidAccum.add(other.position.multiply(num8));
                torqueSum += other.torque * num8;
                revolveSum += other.revolveSpeed * num8;

                rotAccum += (other.rotation - (float) Math.floor(other.rotation)) * num8;

                num += num8;

                // RW: vector2 += other.color * InverseLerp(0.9,1,num8)
                float closeW = (float) inverseLerp(0.90, 1.0, num8);
                if (closeW > 0f) {
                    colAccumX += other.colorX * closeW;
                    colAccumY += other.colorY * closeW;
                    colW += closeW;
                }

                // RW: travelDirection += (otherPos + otherDir*(aimInFront*num8) - pos).normalized*(num8*0.01)
                Vec3d predicted = other.position.add(other.travelDirection.multiply(currentBehavior.aimInFront * num8));
                Vec3d toward = predicted.subtract(position);
                if (toward.lengthSquared() > 1e-12) {
                    travelDirection = travelDirection.add(toward.normalize().multiply(num8 * 0.01));
                }

                // RW: travelDirection += (pos - otherPos).normalized*(InverseLerp(idealDistance,0,dist)*0.1)
                float sepW = (float) inverseLerp(currentBehavior.idealDistance, 0.0, dist);
                if (sepW > 0f) {
                    Vec3d away = position.subtract(other.position);
                    if (away.lengthSquared() > 1e-12) {
                        travelDirection = travelDirection.add(away.normalize().multiply(sepW * 0.1));
                    }
                }

                // RW: if (myDom < otherDom * pow(num8,4)) currentBehavior = other.currentBehavior;
                float myDom = currentBehavior.dominance();
                float otherDom = other.currentBehavior.dominance();
                if (myDom < otherDom * (float) Math.pow(num8, 4.0)) {
                    currentBehavior = other.currentBehavior; // shared reference
                }

                interacted++;
                if (interacted > MAX_INTERACTING_NEIGHBORS) {
                    breakAt = i;
                    break;
                }
            }
        }

        listBreakPoint = breakAt + 1; // RW: num7 + 1 (num7=-1 => 0)

        // RW: travelDirection += RNV * (0.5 * randomVibrations)
        travelDirection = travelDirection.add(randomNormalizedVector().multiply(0.5 * currentBehavior.randomVibrations));

        if (num > 0f) {
            // RW: travelDirection += PerpendicularVector(pos, centroid/num) * torque
            Vec3d centroid = centroidAccum.multiply(1.0 / num);
            Vec3d toCentroid = centroid.subtract(position);
            if (toCentroid.lengthSquared() > 1e-12) {
                Vec3d perp = perpendicular3D(toCentroid).normalize();
                travelDirection = travelDirection.add(perp.multiply(torque));
            }

            // RW rotation smoothing
            float rot = rotAccum / num;
            rot += (float) Math.floor(rotation);
            if (Math.abs(rotation - rot) < 0.4f) {
                rotation = lerp(rotation, rot, 0.05f);
            }
        }

        // RW: torque = Lerp(torque, torqueSum/(1+num), 0.1)
        // RW: revolveSpeed = Lerp(revolveSpeed, revolveSum/(1+num), 0.2)
        torque = lerp(torque, torqueSum / (1f + num), 0.1f);
        revolveSpeed = lerp(revolveSpeed, revolveSum / (1f + num), 0.2f);

        // RW: if (num3>0) color = Lerp(color, vector2/num3, 0.4)
        if (colW > 0f) {
            float nx = colAccumX / colW;
            float ny = colAccumY / colW;
            colorX = lerp(colorX, nx, 0.4f);
            colorY = lerp(colorY, ny, 0.4f);
        }

        // RW: color = Lerp(color, currentBehavior.color, 0.05)
        colorX = lerp(colorX, currentBehavior.colorX, 0.05f);
        colorY = lerp(colorY, currentBehavior.colorY, 0.05f);

        // === Terrain avoidance (RW-shaped factor; still an approximation without AIMAP) ===
        if (world != null) {
            double prox = terrainProximity(world, 5.0); // approximate "terrainProximity" within 5 blocks
            if (prox < 5.0) {
                Vec3d avoid = terrainAvoidanceVector(world);
                if (avoid.lengthSquared() > 1e-12) {
                    // RW factor: 0.5 * Pow(InverseLerp(5,1,prox), 0.25)
                    double t = 0.5 * Math.pow(inverseLerp(5.0, 1.0, prox), 0.25);
                    // RW targets (vector3.normalized * 2)
                    Vec3d target = avoid.normalize().multiply(2.0);
                    travelDirection = lerpVec(travelDirection, target, t);
                }
            }
        }

        if (travelDirection.lengthSquared() > 1e-12) {
            travelDirection = travelDirection.normalize();
        } else {
            travelDirection = randomNormalizedVector();
        }
    }

    // ---- SuckleMycelia (proxy) ----

    private void tryStartSuckle(List<NeuronSwarmer> otherSwarmers, ClientWorld world, Vec3d zoneMin, Vec3d zoneMax) {
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
            if (position.squaredDistanceTo(tip) > (INTERACTION_RANGE * INTERACTION_RANGE)) continue;
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
            Vec3d dirTo = tip.subtract(position);
            double dist = dirTo.length();
            if (dist > 1e-12) {
                Vec3d dirN = dirTo.multiply(1.0 / dist);
                Vec3d v1 = dirN.multiply((2.0 - dist) * 0.15);
                velocity = velocity.subtract(v1);
                position = position.subtract(v1);
                travelDirection = Vec3d.ZERO;
            }

            if (RANDOM.nextFloat() < 0.0125f) {
                suckleTarget = null;
                mode = MovementMode.Swarm;
                onlySwarm = 40 + RANDOM.nextInt(361);
            }
        } else {
            travelDirection = tip.subtract(position);
            if (travelDirection.lengthSquared() > 1e-12) {
                travelDirection = travelDirection.normalize();
            }

            if (position.squaredDistanceTo(tip) < (0.8 * 0.8)) {
                attachedToSuckle = true;
            } else if (RANDOM.nextFloat() < 0.05f && !hasLineOfSight(world, position, tip)) {
                suckleTarget = null;
                mode = MovementMode.Swarm;
            }
        }

        colorX = lerp(colorX, currentBehavior.colorX, 0.05f);
        colorY = lerp(colorY, currentBehavior.colorY, 0.05f);
    }

    // ---- FollowDijkstra (approximate) ----

    private void startFollowDijkstra(Vec3d zoneMin, Vec3d zoneMax, ClientWorld world) {
        if (world == null) return;
        mode = MovementMode.FollowDijkstra;

        int minX = (int) Math.floor(zoneMin.x + 1);
        int minY = (int) Math.floor(zoneMin.y + 1);
        int minZ = (int) Math.floor(zoneMin.z + 1);
        int maxX = (int) Math.floor(zoneMax.x - 1);
        int maxY = (int) Math.floor(zoneMax.y - 1);
        int maxZ = (int) Math.floor(zoneMax.z - 1);

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

        if (travelDirection.lengthSquared() > 1e-12) {
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

    public String getZoneName() {
        return zoneName;
    }

    // ========== Utility methods ==========

    private static Vec3d randomNormalizedVector() {
        double theta = RANDOM.nextDouble() * Math.PI * 2.0;
        double phi = Math.acos(2.0 * RANDOM.nextDouble() - 1.0);
        return new Vec3d(
                Math.sin(phi) * Math.cos(theta),
                Math.sin(phi) * Math.sin(theta),
                Math.cos(phi)
        );
    }

    private static Vec3d slerp(Vec3d a, Vec3d b, double t) {
        Vec3d result = new Vec3d(
                lerp(a.x, b.x, t),
                lerp(a.y, b.y, t),
                lerp(a.z, b.z, t)
        );
        double len = result.length();
        if (len > 1e-6) {
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
        if (Math.abs(b - a) < 1e-12) return 0.0;
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
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d p = v.crossProduct(up);
        if (p.lengthSquared() < 1e-10) {
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
        Vec3d delta = to.subtract(from);
        double len = delta.length();
        if (len < 1e-12) return true;

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

    /**
     * Approximate RW terrain proximity: distance in blocks to the nearest solid block within maxDist.
     * (RW uses AIMAP terrainProximity; we approximate via a small cubic search.)
     */
    private double terrainProximity(ClientWorld world, double maxDist) {
        if (world == null) return maxDist;
        BlockPos base = BlockPos.ofFloored(position);
        int r = (int) Math.ceil(maxDist);

        double best = maxDist;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.add(dx, dy, dz);
                    if (!isBlockSolid(world, p)) continue;
                    Vec3d c = new Vec3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                    double d = position.distanceTo(c);
                    if (d < best) best = d;
                }
            }
        }
        return best;
    }

    /**
     * A cheap "move toward open space" vector.
     * (Not RW AIMAP, but feeds the RW-shaped lerp factor above.)
     */
    private Vec3d terrainAvoidanceVector(ClientWorld world) {
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

    private void handleBlockCollisions(ClientWorld world) {
        BlockPos currentBlock = BlockPos.ofFloored(position);
        double collisionMargin = 0.3;

        Vec3d pushForce = Vec3d.ZERO;

        for (int axis = 0; axis < 3; axis++) {
            for (int dir = -1; dir <= 1; dir += 2) {
                BlockPos checkPos = switch (axis) {
                    case 0 -> currentBlock.add(dir, 0, 0);
                    case 1 -> currentBlock.add(0, dir, 0);
                    case 2 -> currentBlock.add(0, 0, dir);
                    default -> currentBlock;
                };

                if (isBlockSolid(world, checkPos)) {
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
                        double pushStrength = (collisionMargin - distToBlock) / collisionMargin * 0.2;
                        Vec3d push = switch (axis) {
                            case 0 -> new Vec3d(-dir * pushStrength, 0, 0);
                            case 1 -> new Vec3d(0, -dir * pushStrength, 0);
                            case 2 -> new Vec3d(0, 0, -dir * pushStrength);
                            default -> Vec3d.ZERO;
                        };
                        pushForce = pushForce.add(push);

                        double velComponent = switch (axis) {
                            case 0 -> velocity.x;
                            case 1 -> velocity.y;
                            case 2 -> velocity.z;
                            default -> 0;
                        };

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

        if (isBlockSolid(world, currentBlock)) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = currentBlock.add(dx, dy, dz);
                        if (!isBlockSolid(world, neighbor)) {
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

    private boolean isBlockSolid(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    // ========== IProjectedCircleOwner implementation ==========

    @Override
    public Vec3d getCirclePosition() {
        return position;
    }

    @Override
    public Vec3d getLastCirclePosition() {
        return lastPosition;
    }

    @Override
    public boolean isMarkedForRemoval() {
        return markedForRemoval;
    }

    @Override
    public void markForRemoval() {
        markedForRemoval = true;
    }
}
