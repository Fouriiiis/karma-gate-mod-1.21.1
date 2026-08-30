package dev.fouriis.karmagate.entity.monsterkelp;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.poleplant.PolePlantEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Server-authoritative three-dimensional port of Rain World's TentaclePlant
 * (Monster Kelp). Source-time values are stepped twice per game tick because
 * Rain World simulates at 40 Hz while Minecraft simulates at 20 Hz.
 */
public final class MonsterKelpEntity extends HostileEntity {
    public static final double IDEAL_LENGTH = 15.0;
    public static final double HITBOX_SPACING = 0.5;

    private static final int CHUNK_COUNT = 8;
    private static final int SOURCE_STEPS = 2;
    private static final double SOURCE_UNIT = 1.0 / 20.0;
    private static final double NODE_RADIUS = 0.20;
    private static final double TIP_RADIUS = 0.08;
    private static final double ATTACK_TIP_RADIUS = 0.45;
    private static final double COLLISION_EPSILON = 1.0E-5;
    private static final double SURFACE_FRICTION = 0.82;
    private static final int TERRAIN_PASSES = 4;

    private static final TrackedData<String> STEM_NODES = DataTracker.registerData(
            MonsterKelpEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Float> ATTACK = DataTracker.registerData(
            MonsterKelpEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> EXTENDED = DataTracker.registerData(
            MonsterKelpEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> OCEAN_KELP = DataTracker.registerData(
            MonsterKelpEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> RETREATING = DataTracker.registerData(
            MonsterKelpEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    private final List<MonsterKelpSegmentEntity> hitboxes = new ArrayList<>();
    private final Vec3d[] nodes = new Vec3d[CHUNK_COUNT];
    private final Vec3d[] lastNodes = new Vec3d[CHUNK_COUNT];
    private final Vec3d[] velocities = new Vec3d[CHUNK_COUNT];

    private Vec3d root = Vec3d.ZERO;
    private Vec3d outward = new Vec3d(0.0, 1.0, 0.0);
    private Vec3d idlePosition = Vec3d.ZERO;
    private Vec3d attackDirection = Vec3d.ZERO;
    private LivingEntity prey;
    private LivingEntity grabbed;
    private boolean anchorSet;
    private boolean initialized;
    private boolean loadedFromNbt;
    private boolean oceanKelp;
    private float attack;
    private float canGrab;
    private float extended = 1.0f;
    private int preyScanCounter;
    private int idleWanderCounter;
    private int grabDamageCounter;
    private int hurtThrashSteps;
    private String clientPackedNodes = "";
    private Vec3d[] clientNodes = new Vec3d[0];
    private Vec3d[] clientLastNodes = new Vec3d[0];

    public MonsterKelpEntity(EntityType<? extends MonsterKelpEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
        for (int i = 0; i < CHUNK_COUNT; i++) {
            nodes[i] = lastNodes[i] = Vec3d.ZERO;
            velocities[i] = Vec3d.ZERO;
        }
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 18.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 22.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void initGoals() {
        // The original creature is a fixed tentacle controller, not a walking mob.
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(STEM_NODES, "");
        builder.add(ATTACK, 0.0f);
        builder.add(EXTENDED, 1.0f);
        builder.add(OCEAN_KELP, false);
        builder.add(RETREATING, false);
    }

    @Override
    public void tick() {
        noClip = true;
        setNoGravity(true);
        super.tick();
        setVelocity(Vec3d.ZERO);

        if (getWorld().isClient) {
            updateClientSnapshot();
            return;
        }

        if (!anchorSet) establishAnchor();
        setPosition(root.x, root.y, root.z);
        if (!initialized) initializeSimulation(!loadedFromNbt);

        for (int i = 0; i < SOURCE_STEPS && !isRemoved(); i++) simulationStep();
        if (isRemoved()) return;
        syncTrackedState();
        syncHitboxes();
    }

    /**
     * Equivalent to ShorcutEntranceHoleDirection: choose the cardinal normal
     * whose back side meets nearby collision geometry and whose front side has
     * the longest clear run for the resting tentacle.
     */
    private void establishAnchor() {
        root = getPos();
        outward = inferOutwardNormal(root);
        oceanKelp = getWorld().getBiome(BlockPos.ofFloored(root)).isIn(BiomeTags.IS_OCEAN);
        double restingLength = MathHelper.lerp(random.nextDouble(), 10.0, 15.0);
        idlePosition = clearRestingPoint(root.add(outward.multiply(restingLength)));
        anchorSet = true;
    }

    private Vec3d inferOutwardNormal(Vec3d origin) {
        Direction best = Direction.UP;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Direction direction : Direction.values()) {
            Vec3d normal = Vec3d.of(direction.getVector());
            double supportDistance = firstCollisionDistance(origin, normal.negate(), 1.75);
            double clearance = firstCollisionDistance(origin.add(normal.multiply(0.12)), normal, IDEAL_LENGTH);
            boolean supported = supportDistance < 1.76;
            double score = (supported ? 100.0 - supportDistance * 12.0 : -30.0)
                    + clearance * 2.0;
            // Spawn eggs normally place the controller on a floor. This only
            // breaks ties; a nearby wall or ceiling still wins on proximity.
            if (direction == Direction.UP) score += 0.2;
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        return Vec3d.of(best.getVector());
    }

    private double firstCollisionDistance(Vec3d origin, Vec3d direction, double maximum) {
        for (double distance = 0.1; distance <= maximum; distance += 0.1) {
            Vec3d sample = origin.add(direction.multiply(distance));
            if (pointTouchesTerrain(sample, 0.055)) return distance;
        }
        return maximum + 0.01;
    }

    private Vec3d clearRestingPoint(Vec3d requested) {
        Vec3d start = root.add(outward.multiply(0.15));
        HitResult hit = getWorld().raycast(new RaycastContext(start, requested,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getPos().subtract(outward.multiply(0.35));
        }
        return requested;
    }

    private void initializeSimulation(boolean freshSpawn) {
        if (!anchorSet) establishAnchor();
        double initialLength = Math.min(IDEAL_LENGTH, root.distanceTo(idlePosition));
        if (initialLength < 1.0) initialLength = 10.0;
        for (int i = 0; i < CHUNK_COUNT; i++) {
            double f = (i + 1.0) / CHUNK_COUNT;
            Vec3d position = root.add(outward.multiply(initialLength * f));
            nodes[i] = resolveNodePenetration(position);
            lastNodes[i] = nodes[i];
            velocities[i] = freshSpawn ? outward.multiply(0.01 * (i + 1)) : Vec3d.ZERO;
        }
        initialized = true;
        syncTrackedState();
    }

    private void simulationStep() {
        if (!isAlive() || dataTracker.get(RETREATING)) {
            retreatStep();
            return;
        }

        if (hurtThrashSteps > 0) hurtThrashSteps--;
        if (preyScanCounter-- <= 0) {
            selectPrey();
            preyScanCounter = 10;
        }
        if (!isValidPrey(prey)) prey = null;
        if (!isValidPrey(grabbed)) grabbed = null;

        updateIdlePosition();
        updateAttack();
        Vec3d goal = grabbed != null ? grabbed.getBoundingBox().getCenter()
                : prey != null ? prey.getBoundingBox().getCenter() : idlePosition;

        integrateNodes(goal);
        solveLengthAndTerrain();
        tryGrab();
        if (grabbed != null) carryGrabbed();
        else extended = Math.min(1.0f, extended + 0.01f);
    }

    private void selectPrey() {
        Box search = new Box(root, root).expand(IDEAL_LENGTH + 2.0);
        prey = getWorld().getEntitiesByClass(LivingEntity.class, search, this::isValidPrey).stream()
                .filter(target -> target.getBoundingBox().getCenter().distanceTo(root) <= IDEAL_LENGTH)
                .filter(this::hasVisualContact)
                .max(Comparator.comparingDouble(this::preyAttractiveness))
                .orElse(null);
    }

    private double preyAttractiveness(LivingEntity target) {
        Box box = target.getBoundingBox();
        double mass = Math.max(0.1, box.getLengthX() * box.getLengthY() * box.getLengthZ());
        double distance = Math.max(1.0, target.getBoundingBox().getCenter().distanceTo(root));
        return mass * 4.0 + 1.0 / distance;
    }

    private boolean isValidPrey(LivingEntity target) {
        if (target == null || !target.isAlive() || target == this
                || target instanceof MonsterKelpEntity || target instanceof MonsterKelpSegmentEntity
                || target instanceof PolePlantEntity) return false;
        return !(target instanceof PlayerEntity player) || (!player.isCreative() && !player.isSpectator());
    }

    private boolean hasVisualContact(LivingEntity target) {
        Vec3d tip = nodes[CHUNK_COUNT - 1];
        Vec3d destination = target.getBoundingBox().getCenter();
        HitResult result = getWorld().raycast(new RaycastContext(tip, destination,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        return result.getType() == HitResult.Type.MISS;
    }

    private void updateIdlePosition() {
        if (prey != null || grabbed != null) return;
        if (--idleWanderCounter > 0) return;
        idleWanderCounter = 4 + random.nextInt(8);

        Vec3d center = root.add(outward.multiply(10.0));
        Vec3d randomDirection = randomUnit();
        Vec3d tangent = randomDirection.subtract(outward.multiply(randomDirection.dotProduct(outward)));
        if (tangent.lengthSquared() < 1.0E-6) tangent = perpendicular(outward);
        Vec3d candidate = idlePosition.add(tangent.normalize().multiply(0.25))
                .add(randomUnit().multiply(0.15));
        if (candidate.distanceTo(center) > 7.5) {
            candidate = idlePosition.add(direction(idlePosition, center).multiply(0.35));
        }
        if (candidate.distanceTo(root) > IDEAL_LENGTH) {
            candidate = root.add(direction(root, candidate).multiply(IDEAL_LENGTH));
        }
        if (!pointTouchesTerrain(candidate, 0.25)) idlePosition = clearRestingPoint(candidate);

        if (random.nextFloat() < 1.0f / 170.0f
                && (nodes[CHUNK_COUNT - 1].distanceTo(idlePosition) > 2.5
                || pointTouchesTerrain(idlePosition, 0.25))) {
            idlePosition = nodes[CHUNK_COUNT - 1];
        }
    }

    private void updateAttack() {
        float previousAttack = attack;
        if (grabbed != null) {
            attack = 0.0f;
        } else if (attack >= 1.0f) {
            attack += 0.1f;
            if (attack > 2.0f) attack = 0.0f;
        } else if (prey != null && hasVisualContact(prey)
                && prey.getBoundingBox().getCenter().distanceTo(root) <= IDEAL_LENGTH) {
            Vec3d tip = nodes[CHUNK_COUNT - 1];
            Vec3d target = prey.getBoundingBox().getCenter();
            double distance = tip.distanceTo(target);
            Vec3d prediction = target.add(prey.getVelocity().multiply(distance * 0.6));
            attackDirection = direction(tip, prediction);
            attack += 1.0f / 90.0f;
        } else {
            attack = Math.max(0.0f, attack - 1.0f / 180.0f);
        }

        if (attack > 1.0f) canGrab = 1.0f;
        else canGrab = Math.max(0.0f, canGrab - 0.025f);
        if (previousAttack <= 1.0f && attack > 1.0f && attackDirection.lengthSquared() < 0.001) {
            attackDirection = outward;
        }
    }

    private void integrateNodes(Vec3d goal) {
        for (int i = 0; i < CHUNK_COUNT; i++) {
            lastNodes[i] = nodes[i];
            float f = i / (float) (CHUNK_COUNT - 1);
            velocities[i] = velocities[i].multiply(0.96);

            double goalForce = MathHelper.lerp(f, 0.0125, 0.05);
            velocities[i] = velocities[i].add(clampMagnitude(goal.subtract(nodes[i]), 1.0)
                    .multiply(goalForce));

            Vec3d guide = i == 0 ? root.subtract(outward.multiply(1.5))
                    : i == 1 ? root : nodes[i - 2];
            velocities[i] = velocities[i].add(direction(guide, nodes[i]).multiply(SOURCE_UNIT));
            if (i > 1) {
                velocities[i - 2] = velocities[i - 2].add(
                        direction(nodes[i], nodes[i - 2]).multiply(SOURCE_UNIT));
            }
            velocities[i] = velocities[i].add(outward.multiply(MathHelper.lerp(f, 0.3, 0.0) * SOURCE_UNIT));

            if (attack > 0.5f && attack < 1.0f) {
                Vec3d shakeCenter = nodes[CHUNK_COUNT - 1].lerp(root.add(outward.multiply(10.0)), 0.5);
                velocities[i] = velocities[i].add(direction(goal, shakeCenter).multiply(attack * 0.04));
            }
            if (attack >= 1.0f) velocities[i] = velocities[i].add(attackDirection.multiply(0.72));
            if (hurtThrashSteps > 0) velocities[i] = velocities[i].add(randomUnit().multiply(0.035));

            velocities[i] = clampMagnitude(velocities[i], attack >= 1.0f ? 1.0 : 0.55);
            Vec3d moved = moveNodeWithCollision(i, velocities[i], true);
            if (moved.squaredDistanceTo(velocities[i]) > 0.0001) velocities[i] = moved;
        }
    }

    private void solveLengthAndTerrain() {
        double maximum = IDEAL_LENGTH / CHUNK_COUNT
                * MathHelper.lerp(1.0f - extended, 1.0f, 0.1f);
        for (int pass = 0; pass < TERRAIN_PASSES; pass++) {
            for (int i = 0; i < CHUNK_COUNT; i++) {
                Vec3d connection = i == 0 ? root : nodes[i - 1];
                Vec3d delta = nodes[i].subtract(connection);
                double distance = delta.length();
                if (distance > maximum && distance > COLLISION_EPSILON) {
                    Vec3d correction = delta.multiply((distance - maximum) / distance);
                    if (i == 0) moveNodeWithCollision(i, correction.negate(), true);
                    else {
                        moveNodeWithCollision(i - 1, correction.multiply(0.5), true);
                        moveNodeWithCollision(i, correction.multiply(-0.5), true);
                    }
                }
                Vec3d resolved = resolveNodePenetration(nodes[i]);
                Vec3d correction = resolved.subtract(nodes[i]);
                if (correction.lengthSquared() > COLLISION_EPSILON * COLLISION_EPSILON) {
                    nodes[i] = resolved;
                    removeInwardVelocity(i, correction.normalize());
                }
            }
            resolveStemLinks();
        }
    }

    private void resolveStemLinks() {
        for (int i = 0; i < CHUNK_COUNT; i++) {
            Vec3d start = i == 0 ? root : nodes[i - 1];
            Vec3d end = nodes[i];
            Vec3d delta = end.subtract(start);
            double length = delta.length();
            if (length < COLLISION_EPSILON) continue;
            List<Box> obstacles = getBlockCollisionBoxes(new Box(start, end).expand(NODE_RADIUS));
            if (obstacles.isEmpty()) continue;

            int samples = Math.max(2, (int) Math.ceil(length / 0.15));
            Vec3d strongest = Vec3d.ZERO;
            double strongestLength = 0.0;
            double strongestT = 0.5;
            for (int sampleIndex = 1; sampleIndex < samples; sampleIndex++) {
                double t = sampleIndex / (double) samples;
                if (i == 0 && length * t <= NODE_RADIUS) continue;
                Vec3d sample = start.add(delta.multiply(t));
                Vec3d correction = pushPointOutOfBoxes(sample, obstacles, NODE_RADIUS).subtract(sample);
                if (correction.lengthSquared() > strongestLength) {
                    strongest = correction;
                    strongestLength = correction.lengthSquared();
                    strongestT = t;
                }
            }
            if (strongestLength <= COLLISION_EPSILON * COLLISION_EPSILON) continue;
            strongest = clampMagnitude(strongest, 0.5);
            if (i == 0) {
                moveNodeWithCollision(i, strongest.multiply(1.0 / Math.max(0.15, strongestT)), true);
            } else {
                double a = 1.0 - strongestT;
                double b = strongestT;
                double denominator = a * a + b * b;
                moveNodeWithCollision(i - 1, strongest.multiply(a / denominator), true);
                moveNodeWithCollision(i, strongest.multiply(b / denominator), true);
            }
        }
    }

    private void tryGrab() {
        if (grabbed != null || prey == null || !isValidPrey(prey)) return;
        Vec3d tip = nodes[CHUNK_COUNT - 1];
        double radius = attack > 1.0f ? ATTACK_TIP_RADIUS : TIP_RADIUS;
        if (distanceToBox(tip, prey.getBoundingBox()) > radius) return;

        Vec3d relativeVelocity = prey.getVelocity().subtract(velocities[CHUNK_COUNT - 1]);
        if (canGrab > 0.0f || relativeVelocity.length() < 0.05) {
            grabbed = prey;
            attack = 0.0f;
            grabDamageCounter = 0;
        } else {
            prey.setVelocity(prey.getVelocity().lerp(velocities[CHUNK_COUNT - 1], 0.2));
        }
    }

    /** Source Carry plus the source's gradual shortcut retraction. */
    private void carryGrabbed() {
        if (!isValidPrey(grabbed)) {
            grabbed = null;
            return;
        }
        Vec3d tip = nodes[CHUNK_COUNT - 1];
        Vec3d center = grabbed.getBoundingBox().getCenter();
        Vec3d correction = tip.subtract(center);
        double targetMass = Math.max(0.1, preyAttractiveness(grabbed) * 0.08);
        double targetShare = 0.1 / (targetMass + 0.1);
        grabbed.setVelocity(grabbed.getVelocity().add(correction.multiply(targetShare * 0.65)));
        velocities[CHUNK_COUNT - 1] = velocities[CHUNK_COUNT - 1]
                .subtract(correction.multiply((1.0 - targetShare) * 0.12));

        extended -= 0.0125f;
        Vec3d rootPull = clampMagnitude(root.subtract(center), MathHelper.lerp(1.0f - extended, 2.5, 12.0));
        grabbed.addVelocity(rootPull.x * (1.0 - extended) * 0.08,
                rootPull.y * (1.0 - extended) * 0.08,
                rootPull.z * (1.0 - extended) * 0.08);
        grabbed.velocityModified = true;

        if (++grabDamageCounter >= 24) {
            grabbed.damage(getDamageSources().mobAttack(this), 2.0f);
            grabDamageCounter = 0;
        }
        if (extended <= 0.0f) {
            grabbed.damage(getDamageSources().mobAttack(this), 6.0f);
            grabbed = null;
            prey = null;
            extended = 0.05f;
            idlePosition = clearRestingPoint(root.add(outward.multiply(
                    MathHelper.lerp(random.nextDouble(), 10.0, 15.0))));
        }
    }

    private void retreatStep() {
        dataTracker.set(RETREATING, true);
        prey = null;
        grabbed = null;
        attack = 0.0f;
        extended = Math.max(0.0f, extended - 1.0f / 60.0f);
        idlePosition = root;
        integrateNodes(root);
        solveLengthAndTerrain();
        if (extended <= 0.0f || nodes[CHUNK_COUNT - 1].distanceTo(root) < 0.12) discard();
    }

    private Vec3d moveNodeWithCollision(int index, Vec3d requested, boolean affectVelocity) {
        if (requested.lengthSquared() < 1.0E-14) return Vec3d.ZERO;
        Vec3d start = resolveNodePenetration(nodes[index]);
        nodes[index] = start;
        Box moving = nodeBox(start);
        List<VoxelShape> collisions = getBlockCollisionShapes(stretch(moving, requested).expand(COLLISION_EPSILON));
        if (collisions.isEmpty()) {
            nodes[index] = start.add(requested);
            return requested;
        }

        double x = requested.x;
        double y = requested.y;
        double z = requested.z;
        if (y != 0.0) {
            y = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, moving, collisions, y);
            moving = moving.offset(0.0, y, 0.0);
        }
        if (Math.abs(x) < Math.abs(z)) {
            if (z != 0.0) {
                z = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moving, collisions, z);
                moving = moving.offset(0.0, 0.0, z);
            }
            if (x != 0.0) x = VoxelShapes.calculateMaxOffset(Direction.Axis.X, moving, collisions, x);
        } else {
            if (x != 0.0) {
                x = VoxelShapes.calculateMaxOffset(Direction.Axis.X, moving, collisions, x);
                moving = moving.offset(x, 0.0, 0.0);
            }
            if (z != 0.0) z = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moving, collisions, z);
        }
        Vec3d moved = new Vec3d(x, y, z);
        nodes[index] = start.add(moved);
        if (affectVelocity) {
            Vec3d velocity = velocities[index];
            velocities[index] = new Vec3d(
                    Math.abs(x - requested.x) > COLLISION_EPSILON ? 0.0 : velocity.x * SURFACE_FRICTION,
                    Math.abs(y - requested.y) > COLLISION_EPSILON ? 0.0 : velocity.y * SURFACE_FRICTION,
                    Math.abs(z - requested.z) > COLLISION_EPSILON ? 0.0 : velocity.z * SURFACE_FRICTION);
        }
        return moved;
    }

    private Vec3d resolveNodePenetration(Vec3d point) {
        Vec3d result = point;
        for (int pass = 0; pass < 4; pass++) {
            Vec3d next = pushPointOutOfBoxes(result,
                    getBlockCollisionBoxes(nodeBox(result).expand(COLLISION_EPSILON)), NODE_RADIUS);
            if (next.squaredDistanceTo(result) <= COLLISION_EPSILON * COLLISION_EPSILON) break;
            result = next;
        }
        return result;
    }

    private void removeInwardVelocity(int index, Vec3d outwardNormal) {
        double inward = velocities[index].dotProduct(outwardNormal);
        if (inward < 0.0) velocities[index] = velocities[index].subtract(outwardNormal.multiply(inward));
        velocities[index] = velocities[index].multiply(SURFACE_FRICTION);
    }

    private boolean pointTouchesTerrain(Vec3d point, double radius) {
        return getWorld().getBlockCollisions(this, new Box(point, point).expand(radius)).iterator().hasNext();
    }

    private List<VoxelShape> getBlockCollisionShapes(Box bounds) {
        List<VoxelShape> result = new ArrayList<>();
        for (VoxelShape shape : getWorld().getBlockCollisions(this, bounds)) result.add(shape);
        return result;
    }

    private List<Box> getBlockCollisionBoxes(Box bounds) {
        List<Box> result = new ArrayList<>();
        for (VoxelShape shape : getWorld().getBlockCollisions(this, bounds)) {
            result.addAll(shape.getBoundingBoxes());
        }
        return result;
    }

    private static Vec3d pushPointOutOfBoxes(Vec3d point, List<Box> boxes, double radius) {
        Vec3d result = point;
        for (Box box : boxes) {
            Box expanded = box.expand(radius);
            if (!contains(expanded, result)) continue;
            double minX = result.x - expanded.minX;
            double maxX = expanded.maxX - result.x;
            double minY = result.y - expanded.minY;
            double maxY = expanded.maxY - result.y;
            double minZ = result.z - expanded.minZ;
            double maxZ = expanded.maxZ - result.z;
            double nearest = Math.min(Math.min(minX, maxX), Math.min(minY, maxY));
            nearest = Math.min(nearest, Math.min(minZ, maxZ));
            if (nearest == minX) result = new Vec3d(expanded.minX - COLLISION_EPSILON, result.y, result.z);
            else if (nearest == maxX) result = new Vec3d(expanded.maxX + COLLISION_EPSILON, result.y, result.z);
            else if (nearest == minY) result = new Vec3d(result.x, expanded.minY - COLLISION_EPSILON, result.z);
            else if (nearest == maxY) result = new Vec3d(result.x, expanded.maxY + COLLISION_EPSILON, result.z);
            else if (nearest == minZ) result = new Vec3d(result.x, result.y, expanded.minZ - COLLISION_EPSILON);
            else result = new Vec3d(result.x, result.y, expanded.maxZ + COLLISION_EPSILON);
        }
        return result;
    }

    private List<Vec3d> buildHitboxCenters() {
        List<Vec3d> result = new ArrayList<>();
        Vec3d previous = positionAlongStem(0.0);
        result.add(previous);
        double carry = 0.0;
        for (int i = 1; i <= 512; i++) {
            Vec3d current = positionAlongStem(i / 512.0);
            Vec3d start = previous;
            double distance = start.distanceTo(current);
            while (carry + distance >= HITBOX_SPACING && distance > 0.0001) {
                double step = (HITBOX_SPACING - carry) / distance;
                Vec3d center = start.lerp(current, step);
                result.add(center);
                start = center;
                distance = start.distanceTo(current);
                carry = 0.0;
            }
            carry += distance;
            previous = current;
        }
        return result;
    }

    private Vec3d positionAlongStem(double f) {
        double x = MathHelper.clamp(f, 0.0, 1.0) * CHUNK_COUNT;
        int index = MathHelper.clamp((int) x, 0, CHUNK_COUNT - 1);
        if (index == 0) return root.lerp(nodes[0], x);
        return nodes[index - 1].lerp(nodes[index], x - index);
    }

    private void syncHitboxes() {
        List<Vec3d> centers = buildHitboxCenters();
        while (hitboxes.size() < centers.size()) {
            MonsterKelpSegmentEntity segment = new MonsterKelpSegmentEntity(
                    KarmaGateMod.MONSTER_KELP_SEGMENT_ENTITY_TYPE, getWorld());
            segment.setParent(this, hitboxes.size());
            segment.setPosition(root.x, root.y, root.z);
            getWorld().spawnEntity(segment);
            hitboxes.add(segment);
        }
        while (hitboxes.size() > centers.size()) hitboxes.removeLast().discard();
        for (int i = 0; i < centers.size(); i++) {
            MonsterKelpSegmentEntity segment = hitboxes.get(i);
            if (segment.isRemoved()) {
                segment = new MonsterKelpSegmentEntity(KarmaGateMod.MONSTER_KELP_SEGMENT_ENTITY_TYPE, getWorld());
                segment.setParent(this, i);
                getWorld().spawnEntity(segment);
                hitboxes.set(i, segment);
            }
            Vec3d center = centers.get(i);
            segment.setParent(this, i);
            segment.setPosition(center.x, center.y, center.z);
            segment.setVelocity(Vec3d.ZERO);
            segment.velocityDirty = true;
        }
    }

    private void syncTrackedState() {
        dataTracker.set(STEM_NODES, packNodes());
        dataTracker.set(ATTACK, attack);
        dataTracker.set(EXTENDED, extended);
        dataTracker.set(OCEAN_KELP, oceanKelp);
    }

    private String packNodes() {
        StringBuilder packed = new StringBuilder(CHUNK_COUNT * 32);
        for (int i = 0; i < CHUNK_COUNT; i++) {
            if (i > 0) packed.append(';');
            Vec3d relative = nodes[i].subtract(root);
            packed.append((float) relative.x).append(',')
                    .append((float) relative.y).append(',')
                    .append((float) relative.z);
        }
        return packed.toString();
    }

    private void updateClientSnapshot() {
        String packed = dataTracker.get(STEM_NODES);
        if (packed.equals(clientPackedNodes)) {
            clientLastNodes = clientNodes;
            return;
        }
        Vec3d[] decoded = unpackNodes(packed);
        if (decoded.length == 0) return;
        clientLastNodes = clientNodes.length == decoded.length ? clientNodes : decoded;
        clientNodes = decoded;
        clientPackedNodes = packed;
    }

    private static Vec3d[] unpackNodes(String packed) {
        if (packed == null || packed.isEmpty()) return new Vec3d[0];
        String[] entries = packed.split(";");
        Vec3d[] result = new Vec3d[entries.length];
        try {
            for (int i = 0; i < entries.length; i++) {
                String[] components = entries[i].split(",");
                if (components.length != 3) return new Vec3d[0];
                result[i] = new Vec3d(Double.parseDouble(components[0]),
                        Double.parseDouble(components[1]), Double.parseDouble(components[2]));
            }
            return result;
        } catch (NumberFormatException ignored) {
            return new Vec3d[0];
        }
    }

    public List<Vec3d> getClientStemPositions(float tickDelta) {
        Vec3d base = getLerpedPos(tickDelta);
        if (clientNodes.length == 0) return List.of(base);
        List<Vec3d> result = new ArrayList<>(clientNodes.length + 1);
        result.add(base);
        for (int i = 0; i < clientNodes.length; i++) {
            Vec3d previous = clientLastNodes.length == clientNodes.length ? clientLastNodes[i] : clientNodes[i];
            result.add(base.add(previous.lerp(clientNodes[i], tickDelta)));
        }
        return result;
    }

    public float getAttackProgress() {
        return dataTracker.get(ATTACK);
    }

    public float getExtended() {
        return dataTracker.get(EXTENDED);
    }

    public boolean isOceanKelp() {
        return dataTracker.get(OCEAN_KELP);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isOf(DamageTypes.DROWN)
                || isInvulnerableTo(source) || dataTracker.get(RETREATING)) return false;
        boolean result = super.damage(source, amount);
        if (result) {
            hurtThrashSteps = Math.max(hurtThrashSteps, 80 + random.nextInt(40));
            for (int i = 0; i < CHUNK_COUNT; i++) {
                double envelope = Math.sin(Math.PI * (i + 1.0) / (CHUNK_COUNT + 1.0));
                velocities[i] = velocities[i].add(randomUnit().multiply(
                        Math.min(0.18, 0.055 + amount * 0.012) * (0.35 + envelope)));
            }
            if (!isAlive()) dataTracker.set(RETREATING, true);
        }
        return result;
    }

    @Override
    protected void updatePostDeath() {
        // The source creature remains until its stem finishes retreating.
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushAway(Entity entity) {
    }

    @Override
    public void travel(Vec3d movementInput) {
    }

    @Override
    public boolean isInsideWall() {
        return false;
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!getWorld().isClient) {
            for (MonsterKelpSegmentEntity hitbox : hitboxes) {
                if (!hitbox.isRemoved()) hitbox.discard();
            }
            hitboxes.clear();
        }
        super.remove(reason);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("MonsterKelpAnchorSet", anchorSet);
        nbt.putDouble("MonsterKelpRootX", root.x);
        nbt.putDouble("MonsterKelpRootY", root.y);
        nbt.putDouble("MonsterKelpRootZ", root.z);
        nbt.putDouble("MonsterKelpOutwardX", outward.x);
        nbt.putDouble("MonsterKelpOutwardY", outward.y);
        nbt.putDouble("MonsterKelpOutwardZ", outward.z);
        nbt.putDouble("MonsterKelpIdleX", idlePosition.x);
        nbt.putDouble("MonsterKelpIdleY", idlePosition.y);
        nbt.putDouble("MonsterKelpIdleZ", idlePosition.z);
        nbt.putFloat("MonsterKelpExtended", extended);
        nbt.putBoolean("MonsterKelpOcean", oceanKelp);
        nbt.putBoolean("MonsterKelpRetreating", dataTracker.get(RETREATING));
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        anchorSet = nbt.getBoolean("MonsterKelpAnchorSet");
        root = new Vec3d(nbt.getDouble("MonsterKelpRootX"), nbt.getDouble("MonsterKelpRootY"),
                nbt.getDouble("MonsterKelpRootZ"));
        outward = new Vec3d(nbt.getDouble("MonsterKelpOutwardX"), nbt.getDouble("MonsterKelpOutwardY"),
                nbt.getDouble("MonsterKelpOutwardZ"));
        if (outward.lengthSquared() < 0.5) outward = new Vec3d(0.0, 1.0, 0.0);
        idlePosition = new Vec3d(nbt.getDouble("MonsterKelpIdleX"), nbt.getDouble("MonsterKelpIdleY"),
                nbt.getDouble("MonsterKelpIdleZ"));
        extended = nbt.getFloat("MonsterKelpExtended");
        oceanKelp = nbt.getBoolean("MonsterKelpOcean");
        dataTracker.set(RETREATING, nbt.getBoolean("MonsterKelpRetreating"));
        loadedFromNbt = true;
        initialized = false;
    }

    private Vec3d randomUnit() {
        double y = random.nextDouble() * 2.0 - 1.0;
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        return new Vec3d(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
    }

    private static Vec3d perpendicular(Vec3d vector) {
        Vec3d axis = Math.abs(vector.y) < 0.9 ? new Vec3d(0.0, 1.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);
        return direction(Vec3d.ZERO, vector.crossProduct(axis));
    }

    private static Vec3d direction(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        return delta.lengthSquared() < 1.0E-8 ? Vec3d.ZERO : delta.normalize();
    }

    private static Vec3d clampMagnitude(Vec3d vector, double maximum) {
        return vector.lengthSquared() > maximum * maximum ? vector.normalize().multiply(maximum) : vector;
    }

    private static double distanceToBox(Vec3d point, Box box) {
        double x = Math.max(box.minX - point.x, Math.max(0.0, point.x - box.maxX));
        double y = Math.max(box.minY - point.y, Math.max(0.0, point.y - box.maxY));
        double z = Math.max(box.minZ - point.z, Math.max(0.0, point.z - box.maxZ));
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static boolean contains(Box box, Vec3d point) {
        return point.x >= box.minX && point.x <= box.maxX
                && point.y >= box.minY && point.y <= box.maxY
                && point.z >= box.minZ && point.z <= box.maxZ;
    }

    private static Box nodeBox(Vec3d center) {
        return new Box(center, center).expand(NODE_RADIUS);
    }

    private static Box stretch(Box box, Vec3d movement) {
        return new Box(
                movement.x < 0.0 ? box.minX + movement.x : box.minX,
                movement.y < 0.0 ? box.minY + movement.y : box.minY,
                movement.z < 0.0 ? box.minZ + movement.z : box.minZ,
                movement.x > 0.0 ? box.maxX + movement.x : box.maxX,
                movement.y > 0.0 ? box.maxY + movement.y : box.maxY,
                movement.z > 0.0 ? box.maxZ + movement.z : box.maxZ);
    }
}
