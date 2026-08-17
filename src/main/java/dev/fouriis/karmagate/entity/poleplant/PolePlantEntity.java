package dev.fouriis.karmagate.entity.poleplant;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * A server-authoritative 3D port of Rain World's PoleMimic. The simulation is
 * stepped twice per Minecraft tick to retain the source game's 40 Hz timing.
 */
public final class PolePlantEntity extends HostileEntity {
    public static final int MIN_SIZE_BLOCKS = 4;
    public static final int MAX_SIZE_BLOCKS = 28;
    public static final double HITBOX_SIZE = 0.5;

    private static final int SIMULATION_STEPS_PER_TICK = 2;
    private static final double SOURCE_UNIT = 1.0 / 20.0;
    private static final int CAMOUFLAGE_DELAY = 40;
    private static final double STEM_COLLISION_RADIUS = 0.20;
    private static final double COLLISION_EPSILON = 1.0E-5;
    private static final double SURFACE_FRICTION = 0.82;
    private static final double STEM_COLLISION_SAMPLE_SPACING = 0.15;
    private static final int TERRAIN_SOLVER_PASSES = 4;
    private static final double SNAG_CONTACT_MARGIN = 2.0 * SOURCE_UNIT;
    private static final double SNAG_MASS = 0.18;
    private static final double GRAB_MASS = 0.07;

    private static final TrackedData<Integer> SIZE_BLOCKS = DataTracker.registerData(
            PolePlantEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> MIMIC = DataTracker.registerData(
            PolePlantEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> EXTENDED = DataTracker.registerData(
            PolePlantEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Integer> BEHAVIOUR = DataTracker.registerData(
            PolePlantEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Boolean> RETREATING = DataTracker.registerData(
            PolePlantEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> IN_DEN = DataTracker.registerData(
            PolePlantEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<String> STEM_NODES = DataTracker.registerData(
            PolePlantEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> SNAG_TARGETS = DataTracker.registerData(
            PolePlantEntity.class, TrackedDataHandlerRegistry.STRING);

    private final List<PolePlantSegmentEntity> serverHitboxes = new ArrayList<>();

    private Vec3d root = Vec3d.ZERO;
    private Vec3d[] nodes = new Vec3d[0];
    private Vec3d[] lastNodes = new Vec3d[0];
    private Vec3d[] velocities = new Vec3d[0];
    private LivingEntity[] snagTargets = new LivingEntity[0];
    private Vec3d tentacleGrabDestination = Vec3d.ZERO;
    private boolean anchorSet;
    private boolean initialized;
    private boolean loadedFromNbt;

    private float extended = 1.0f;
    private float mimic = 1.0f;
    private float getToGoalForce;
    private float idealLength;
    private float forceIntoShortCut;
    private int wakeUpCounter;
    private int mimicDelayCounter = CAMOUFLAGE_DELAY;
    private int huntCounter;
    private int angeredAndAggressive;
    private boolean tipAttached = true;
    private boolean retreating;
    private boolean inDen;
    private LivingEntity huntTarget;
    private LivingEntity grabbedTarget;
    private int grabDamageCooldown;
    private String clientPackedNodes = "";
    private Vec3d[] clientLastNodes = new Vec3d[0];
    private Vec3d[] clientNodes = new Vec3d[0];
    private String clientPackedSnagTargets = "";
    private int[] clientSnagTargetIds = new int[0];

    public PolePlantEntity(EntityType<? extends PolePlantEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 12.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 40.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void initGoals() {
        // Pole mimic behavior is driven by the source simulation below.
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(SIZE_BLOCKS, 0);
        builder.add(MIMIC, 1.0f);
        builder.add(EXTENDED, 1.0f);
        builder.add(BEHAVIOUR, Behaviour.POLE_CAMOUFLAGE.ordinal());
        builder.add(RETREATING, false);
        builder.add(IN_DEN, false);
        builder.add(STEM_NODES, "");
        builder.add(SNAG_TARGETS, "");
    }

    @Override
    public void tick() {
        noClip = true;
        setNoGravity(true);
        super.tick();
        setVelocity(Vec3d.ZERO);

        if (getWorld().isClient) {
            updateClientNodeSnapshot();
            updateClientSnagTargets();
            return;
        }

        if (!anchorSet) {
            root = getPos();
            anchorSet = true;
        }
        setPosition(root.x, root.y, root.z);

        if (!initialized) initializeSimulation(!loadedFromNbt);
        if (inDen) {
            discard();
            return;
        }

        for (int i = 0; i < SIMULATION_STEPS_PER_TICK && !inDen; i++) simulationStep();
        syncTrackedState();
        syncHitboxes();

        if (inDen) discard();
    }

    private void initializeSimulation(boolean emergeFromDen) {
        int size = dataTracker.get(SIZE_BLOCKS);
        if (size < MIN_SIZE_BLOCKS) {
            int choices = (MAX_SIZE_BLOCKS - MIN_SIZE_BLOCKS) / 2 + 1;
            size = MIN_SIZE_BLOCKS + random.nextInt(choices) * 2;
            dataTracker.set(SIZE_BLOCKS, size);
        }

        int chunkCount = Math.max(2, size / 2);
        nodes = new Vec3d[chunkCount];
        lastNodes = new Vec3d[chunkCount];
        velocities = new Vec3d[chunkCount];
        snagTargets = new LivingEntity[chunkCount];
        idealLength = size;

        if (emergeFromDen) {
            for (int i = 0; i < chunkCount; i++) {
                nodes[i] = root;
                lastNodes[i] = root;
                velocities[i] = new Vec3d(0.0, MathHelper.clamp(i, 1, 15) * SOURCE_UNIT, 0.0);
            }
            extended = 1.0f;
            mimic = 0.0f;
            wakeUpCounter = 250;
            mimicDelayCounter = 0;
            tipAttached = false;
        } else {
            for (int i = 0; i < chunkCount; i++) {
                Vec3d position = root.add(0.0, (i + 1) * 2.0, 0.0);
                nodes[i] = position;
                lastNodes[i] = position;
                velocities[i] = Vec3d.ZERO;
            }
            if (!retreating && isAlive()) {
                extended = 1.0f;
                mimic = 1.0f;
                wakeUpCounter = 0;
                mimicDelayCounter = CAMOUFLAGE_DELAY + 1;
                tipAttached = true;
            }
        }
        initialized = true;
        tentacleGrabDestination = root.add(0.0, getPlantHeight(), 0.0);
        syncTrackedState();
    }

    private void simulationStep() {
        if (angeredAndAggressive > 0) {
            angeredAndAggressive--;
            wakeUpCounter = Math.max(wakeUpCounter, angeredAndAggressive);
        }
        if (grabDamageCooldown > 0) grabDamageCooldown--;

        if (!isValidPrey(grabbedTarget)) grabbedTarget = null;
        if (!isValidPrey(huntTarget)) huntTarget = null;
        Vec3d tipAnchor = root.add(0.0, getPlantHeight(), 0.0);
        LivingEntity destinationTarget = grabbedTarget != null ? grabbedTarget : huntTarget;
        Vec3d grabDestination = chooseGrabDestination(tipAnchor, destinationTarget);

        updateTentacle(grabDestination);
        LivingEntity bestSnag = updateSnaggedTargets();
        selectHuntTarget(bestSnag);
        updateAi(tipAnchor);
        updateGrabbedTarget();
        idealLength = getPlantHeight() * MathHelper.lerp(
                (1.0f - getToGoalForce) * (1.0f - mimic), 1.0f, 0.75f);
        applyPoleForces();
        solveTerrainConstraints();

        if (retreating || !isAlive()) {
            retreating = true;
            grabbedTarget = null;
            clearSnags();
            extended = Math.max(0.0f, extended - 1.0f / 60.0f);
            if (extended <= 0.0f) {
                for (int i = 0; i < nodes.length; i++) {
                    nodes[i] = lastNodes[i] = root;
                    velocities[i] = Vec3d.ZERO;
                }
                inDen = true;
            }
        }
    }

    private Vec3d chooseGrabDestination(Vec3d tipAnchor, LivingEntity target) {
        if (target == null || tipAttached) {
            tentacleGrabDestination = tipAnchor;
            return tentacleGrabDestination;
        }

        Vec3d targetCenter = target.getBoundingBox().getCenter();
        Vec3d tip = nodes.length == 0 ? tipAnchor : nodes[nodes.length - 1];
        HitResult obstruction = getWorld().raycast(new RaycastContext(
                tip, targetCenter, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, this));
        if (obstruction.getType() == HitResult.Type.MISS) tentacleGrabDestination = targetCenter;
        return tentacleGrabDestination;
    }

    /** Source Tentacle.Update: maximum-length correction, goal pull, and integration. */
    private void updateTentacle(Vec3d grabDestination) {
        double maximum = idealLength / nodes.length * MathHelper.lerp(1.0f - extended, 1.0f, 0.1f);
        for (int i = 0; i < nodes.length; i++) lastNodes[i] = nodes[i];

        for (int i = 0; i < nodes.length; i++) {
            Vec3d connection = i == 0 ? root : nodes[i - 1];
            Vec3d toward = direction(nodes[i], connection);
            double distance = nodes[i].distanceTo(connection);
            if (distance > maximum) {
                double excess = distance - maximum;
                double share = i == 0 ? 1.0 : 0.5;
                Vec3d correction = toward.multiply(excess * share);
                Vec3d moved = moveNodeWithCollision(i, correction, false);
                velocities[i] = velocities[i].add(moved);
                if (i > 0) {
                    Vec3d opposite = toward.multiply(excess * 0.5);
                    moved = moveNodeWithCollision(i - 1, opposite.negate(), false);
                    velocities[i - 1] = velocities[i - 1].add(moved);
                }
            }

            double goalSpeed = i == nodes.length - 1 ? 0.2 : getToGoalForce * 0.4;
            velocities[i] = velocities[i].add(clampMagnitude(
                    grabDestination.subtract(nodes[i]), 1.0).multiply(goalSpeed * SOURCE_UNIT));
            Vec3d segmentGoal = root.add(0.0, (i + 1.0) * idealLength / nodes.length, 0.0);
            velocities[i] = velocities[i].add(clampMagnitude(
                    segmentGoal.subtract(nodes[i]), 1.0).multiply(getToGoalForce * 0.5 * SOURCE_UNIT));
            velocities[i] = clampMagnitude(velocities[i], 12.0 * SOURCE_UNIT);
            moveNodeWithCollision(i, velocities[i], true);
        }
    }

    private void updateAi(Vec3d tipAnchor) {
        boolean wantWake = huntCounter > 100 || hasAnySnag() || angeredAndAggressive > 0 || retreating;
        if (huntCounter > 100) {
            getToGoalForce = Math.min(1.0f, getToGoalForce + wakeUpFactor() / 140.0f);
        }
        if (wantWake && wakeUpCounter < 250) wakeUpCounter++;
        else if (!wantWake && wakeUpCounter > 0) wakeUpCounter--;

        double attachDistance = map(getToGoalForce, 0.7, 1.0, 1.0, 1.5);
        if (tipAttached) {
            if (huntTarget == null && grabbedTarget == null) {
                setNode(nodes.length - 1, tipAnchor);
                getToGoalForce = Math.max(0.0f, getToGoalForce - 1.0f / 30.0f);
            } else if (random.nextFloat() < 1.0f / 30.0f || grabbedTarget != null) {
                tipAttached = false;
            }
        } else {
            if (wakeUpCounter == 0) {
                getToGoalForce = Math.min(1.0f, getToGoalForce + 1.0f / 140.0f);
            }
            if (nodes[nodes.length - 1].distanceTo(tipAnchor) < attachDistance) tipAttached = true;
        }
        if (random.nextFloat() < Math.pow(wakeUpFactor(), 5.0)) tipAttached = false;

        mimicDelayCounter = tipAttached && wakeUpCounter == 0 ? mimicDelayCounter + 1 : 0;
        mimic = lerpAndTick(mimic, mimicDelayCounter > CAMOUFLAGE_DELAY ? 1.0f : 0.0f,
                0.01f, 0.0125f);

        if (huntTarget != null && grabbedTarget == null) {
            Vec3d tip = nodes[nodes.length - 1];
            if (distanceToBox(tip, huntTarget.getBoundingBox()) <= STEM_COLLISION_RADIUS
                    && wakeUpFactor() > 0.3f) {
                grabbedTarget = huntTarget;
            }
        }
        if (huntCounter > 0) huntCounter--;
        if (huntTarget != null && (huntCounter < 1 || !isValidPrey(huntTarget))) huntTarget = null;
    }

    /** Port of PoleMimic.stickChunks: every tentacle chunk can snag one prey body. */
    private LivingEntity updateSnaggedTargets() {
        if (retreating || !isAlive() || nodes.length == 0) {
            clearSnags();
            return null;
        }

        Box search = stemBounds().expand(1.5);
        List<LivingEntity> candidates = getWorld().getEntitiesByClass(LivingEntity.class, search, candidate ->
                isValidPrey(candidate));
        LivingEntity best = null;
        double bestTaste = 0.0;
        double anyWake = inverseLerp(0.0, 40.0, wakeUpCounter);

        for (int i = 0; i < nodes.length; i++) {
            LivingEntity snag = snagTargets[i];
            if (snag != null) {
                Vec3d center = snag.getBoundingBox().getCenter();
                Vec3d fromCenter = nodes[i].subtract(center);
                double radius = targetRadiusInDirection(snag, fromCenter);
                double releaseSlack = MathHelper.lerp(anyWake, 14.0,
                        20.0 + 5.0 * wakeUpFactor()) * SOURCE_UNIT;
                if (!isValidPrey(snag) || nodes[i].distanceTo(center) > radius + releaseSlack) {
                    snagTargets[i] = null;
                    continue;
                }

                if (!constrainSnag(i, snag, center, radius)) continue;
                double taste = targetTastiness(snag);
                if (taste > bestTaste) {
                    bestTaste = taste;
                    best = snag;
                }
                continue;
            }

            LivingEntity neighbor = bestNeighborSnag(i);
            if (neighbor != null) {
                velocities[i] = velocities[i].add(clampMagnitude(
                        neighbor.getBoundingBox().getCenter().subtract(nodes[i]),
                        10.0 * SOURCE_UNIT).multiply(0.2));
            }

            if (candidates.isEmpty()) continue;
            LivingEntity candidate = candidates.get(random.nextInt(candidates.size()));
            Vec3d center = candidate.getBoundingBox().getCenter();
            Vec3d fromCenter = nodes[i].subtract(center);
            double radius = targetRadiusInDirection(candidate, fromCenter);
            if (nodes[i].distanceTo(center) >= radius + SNAG_CONTACT_MARGIN) continue;

            boolean latch = (angeredAndAggressive > 0 && !(candidate instanceof PlayerEntity))
                    || random.nextFloat() < Math.pow(anyWake, 0.25)
                    || random.nextInt(7) == 0;
            if (latch) {
                snagTargets[i] = candidate;
                double taste = targetTastiness(candidate);
                if (taste > bestTaste) {
                    bestTaste = taste;
                    best = candidate;
                }
            }
        }

        return best;
    }

    private void selectHuntTarget(LivingEntity bestSnag) {
        if (bestSnag != null
                && Math.pow(random.nextFloat(), 3.0) < wakeUpFactor()
                && targetTastiness(bestSnag) > targetTastiness(huntTarget)) {
            huntTarget = bestSnag;
            huntCounter = 110 + random.nextInt(80);
        }
    }

    private boolean constrainSnag(int nodeIndex, LivingEntity target,
                                  Vec3d targetCenter, double targetRadius) {
        Vec3d towardTarget = direction(nodes[nodeIndex], targetCenter);
        double stretch = nodes[nodeIndex].distanceTo(targetCenter) - targetRadius;
        double mass = targetMass(target);
        double nodeShare = mass / (mass + SNAG_MASS);
        double strength = MathHelper.lerp(wakeUpFactor(), 0.35, 0.6);

        Vec3d nodeCorrection = towardTarget.multiply(stretch * nodeShare * strength);
        Vec3d moved = moveNodeWithCollision(nodeIndex, nodeCorrection, true);
        velocities[nodeIndex] = velocities[nodeIndex].add(moved);

        Vec3d targetCorrection = towardTarget.multiply(-stretch * (1.0 - nodeShare) * strength);
        moveCaughtEntity(target, targetCorrection, true);

        if (target instanceof PlayerEntity player
                && (player.isSprinting() || player.isSneaking())
                && random.nextFloat() < 1.0f / 11.0f) {
            velocities[nodeIndex] = velocities[nodeIndex].add(
                    direction(targetCenter, nodes[nodeIndex])
                            .multiply(MathHelper.lerp(random.nextFloat(), 4.0, 8.0) * SOURCE_UNIT));
            snagTargets[nodeIndex] = null;
            return false;
        }
        return true;
    }

    private LivingEntity bestNeighborSnag(int index) {
        LivingEntity previous = index > 0 ? snagTargets[index - 1] : null;
        LivingEntity next = index + 1 < snagTargets.length ? snagTargets[index + 1] : null;
        return targetTastiness(previous) >= targetTastiness(next) ? previous : next;
    }

    /** Port of PoleMimic.Carry and the grasp-driven retraction in Update. */
    private void updateGrabbedTarget() {
        if (!isValidPrey(grabbedTarget) || retreating || !isAlive()) {
            grabbedTarget = null;
            if (!retreating && isAlive()) {
                extended = 1.0f;
                forceIntoShortCut = 0.0f;
            }
            return;
        }

        int tipIndex = nodes.length - 1;
        Vec3d targetCenter = grabbedTarget.getBoundingBox().getCenter();
        Vec3d towardTip = direction(targetCenter, nodes[tipIndex]);
        double distance = targetCenter.distanceTo(nodes[tipIndex]);
        double mass = targetMass(grabbedTarget);
        double targetShare = 1.0 - mass / (mass + GRAB_MASS);
        Vec3d targetCorrection = towardTip.multiply(distance * targetShare);
        moveCaughtEntity(grabbedTarget, targetCorrection, true);
        Vec3d tipCorrection = moveNodeWithCollision(tipIndex,
                towardTip.multiply(-distance * (1.0 - targetShare)), true);
        velocities[tipIndex] = velocities[tipIndex].add(tipCorrection);

        targetCenter = grabbedTarget.getBoundingBox().getCenter();
        double rootDistance = targetCenter.distanceTo(root);
        extended -= 1.0f / map(rootDistance, 7.5, 2.5, 280.0, 30.0);

        float shortcutForce = MathHelper.clamp(forceIntoShortCut, 0.0f, 1.0f);
        double maximumPull = MathHelper.lerp(shortcutForce, 25.0, 400.0) * SOURCE_UNIT;
        double pullMass = MathHelper.lerp(shortcutForce, mass, 0.1);
        Vec3d rootPull = clampMagnitude(root.subtract(targetCenter), maximumPull)
                .multiply((1.0 - extended) / 20.0 / Math.max(0.1, pullMass)
                        * inverseLerp(10.0, 2.5, rootDistance));
        grabbedTarget.addVelocity(rootPull.x, rootPull.y, rootPull.z);
        grabbedTarget.velocityModified = true;

        if (extended < 0.0f) {
            extended = 0.0f;
            forceIntoShortCut = Math.min(1.0f, forceIntoShortCut
                    + (float) inverseLerp(10.0, 2.5, rootDistance) / 30.0f);
        } else {
            forceIntoShortCut = 0.0f;
        }

        if (grabbedTarget instanceof PlayerEntity player
                && (player.isSprinting() || player.isSneaking())
                && random.nextFloat() < 1.0f / 15.0f) {
            velocities[tipIndex] = velocities[tipIndex].add(
                    direction(targetCenter, nodes[tipIndex])
                            .multiply(MathHelper.lerp(random.nextFloat(), 4.0, 8.0) * SOURCE_UNIT));
            grabbedTarget = null;
            extended = 1.0f;
            forceIntoShortCut = 0.0f;
            return;
        }

        // The source resolves prey through the den shortcut. Until rooms can
        // perform that transition, apply damage only after full retraction.
        if (extended <= 0.0f && grabDamageCooldown == 0) {
            grabbedTarget.damage(getDamageSources().mobAttack(this), 2.0f);
            grabDamageCooldown = 40;
        }
        if (!grabbedTarget.isAlive()) {
            grabbedTarget = null;
        }
    }

    private void moveCaughtEntity(LivingEntity target, Vec3d movement, boolean addMomentum) {
        if (movement.lengthSquared() < 1.0E-10) return;
        Vec3d limited = clampMagnitude(movement, 0.75);
        target.move(MovementType.SELF, limited);
        if (addMomentum) target.addVelocity(limited.x, limited.y, limited.z);
        target.velocityModified = true;
    }

    private boolean isValidPrey(LivingEntity candidate) {
        return candidate != null
                && candidate != this
                && !(candidate instanceof PolePlantEntity)
                && !(candidate instanceof PolePlantSegmentEntity)
                && candidate.isAlive()
                && !candidate.isRemoved()
                && candidate.getWorld() == getWorld()
                && (!(candidate instanceof PlayerEntity player)
                || (!player.isCreative() && !player.isSpectator()));
    }

    private boolean hasAnySnag() {
        for (LivingEntity target : snagTargets) {
            if (isValidPrey(target)) return true;
        }
        return false;
    }

    private void clearSnags() {
        for (int i = 0; i < snagTargets.length; i++) snagTargets[i] = null;
    }

    private double targetTastiness(LivingEntity target) {
        return isValidPrey(target) ? targetMass(target) : 0.0;
    }

    private static double targetMass(LivingEntity target) {
        return MathHelper.clamp(target.getWidth() * target.getWidth() * target.getHeight(), 0.1, 20.0);
    }

    private static double targetRadiusInDirection(LivingEntity target, Vec3d fromCenter) {
        Box box = target.getBoundingBox();
        double halfX = Math.max(0.05, box.getLengthX() * 0.5);
        double halfY = Math.max(0.05, box.getLengthY() * 0.5);
        double halfZ = Math.max(0.05, box.getLengthZ() * 0.5);
        Vec3d direction = fromCenter.lengthSquared() < 1.0E-8
                ? new Vec3d(1.0, 0.0, 0.0) : fromCenter.normalize();
        double scale = Double.POSITIVE_INFINITY;
        if (Math.abs(direction.x) > 1.0E-6) scale = Math.min(scale, halfX / Math.abs(direction.x));
        if (Math.abs(direction.y) > 1.0E-6) scale = Math.min(scale, halfY / Math.abs(direction.y));
        if (Math.abs(direction.z) > 1.0E-6) scale = Math.min(scale, halfZ / Math.abs(direction.z));
        return Double.isFinite(scale) ? scale : Math.min(halfX, Math.min(halfY, halfZ));
    }

    private Box stemBounds() {
        double minX = root.x;
        double minY = root.y;
        double minZ = root.z;
        double maxX = root.x;
        double maxY = root.y;
        double maxZ = root.z;
        for (Vec3d node : nodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            minZ = Math.min(minZ, node.z);
            maxX = Math.max(maxX, node.x);
            maxY = Math.max(maxY, node.y);
            maxZ = Math.max(maxZ, node.z);
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static double distanceToBox(Vec3d point, Box box) {
        double x = Math.max(box.minX - point.x, Math.max(0.0, point.x - box.maxX));
        double y = Math.max(box.minY - point.y, Math.max(0.0, point.y - box.maxY));
        double z = Math.max(box.minZ - point.z, Math.max(0.0, point.z - box.maxZ));
        return Math.sqrt(x * x + y * y + z * z);
    }

    private void applyPoleForces() {
        float healthRatio = getMaxHealth() <= 0.0f ? 0.0f : getHealth() / getMaxHealth();
        for (int i = 0; i < nodes.length; i++) {
            float f = i / (float) Math.max(1, nodes.length - 1);
            velocities[i] = velocities[i].multiply(0.96);
            if (healthRatio < 0.5f) {
                velocities[i] = velocities[i].add(randomUnit().multiply(
                        inverseLerp(0.5, 0.0, healthRatio) * 2.0 * SOURCE_UNIT));
            }
            if (angeredAndAggressive > 0) {
                float envelope = (float) Math.sin(Math.PI * f);
                velocities[i] = velocities[i].add(randomUnit().multiply(0.035 * (0.25 + envelope)));
            }

            Vec3d tile = root.add(0.0, (i + 1) * 2.0, 0.0);
            if (mimic >= 0.999f && tipAttached && nodes[i].distanceTo(tile) < 0.25
                    && velocities[i].length() < MathHelper.lerp(mimic, 0.1f, 0.4f)) {
                Vec3d requested = tile.subtract(nodes[i]);
                Vec3d moved = moveNodeWithCollision(i, requested, false);
                if (moved.squaredDistanceTo(requested) < COLLISION_EPSILON * COLLISION_EPSILON) {
                    velocities[i] = Vec3d.ZERO;
                    continue;
                }
            }
            if (tipAttached) {
                Vec3d pull = clampMagnitude(tile.subtract(nodes[i]), 0.5);
                velocities[i] = velocities[i].add(pull.multiply(
                        inverseLerp(0.5, 1.0, mimic) / 5.0));
            }

            Vec3d guide = i == 0 ? root.add(0.0, -1.5, 0.0)
                    : i == 1 ? root : nodes[i - 2];
            velocities[i] = velocities[i].add(direction(guide, nodes[i]).multiply(SOURCE_UNIT));
            if (i > 1) {
                velocities[i - 2] = velocities[i - 2].add(
                        direction(nodes[i], nodes[i - 2]).multiply(SOURCE_UNIT));
            }
            velocities[i] = velocities[i].add(0.0,
                    MathHelper.lerp(f, 0.3f, 0.0f) * SOURCE_UNIT, 0.0);
        }
    }

    private void setNode(int index, Vec3d position) {
        if (index < 0 || index >= nodes.length) return;
        moveNodeWithCollision(index, position.subtract(nodes[index]), true);
        lastNodes[index] = nodes[index];
        velocities[index] = Vec3d.ZERO;
    }

    /**
     * Keeps both the simulated chunks and the two-block links between them out
     * of block collision shapes. Multiple passes let a link settle around a
     * corner while the maximum-length constraint remains intact.
     */
    private void solveTerrainConstraints() {
        if (nodes.length == 0) return;
        double maximum = idealLength / nodes.length
                * MathHelper.lerp(1.0f - extended, 1.0f, 0.1f);

        for (int pass = 0; pass < TERRAIN_SOLVER_PASSES; pass++) {
            for (int i = 0; i < nodes.length; i++) {
                Vec3d resolved = resolveNodePenetration(nodes[i]);
                Vec3d correction = resolved.subtract(nodes[i]);
                if (correction.lengthSquared() > COLLISION_EPSILON * COLLISION_EPSILON) {
                    nodes[i] = resolved;
                    removeInwardVelocity(i, correction.normalize());
                }
            }

            resolveStemLinkCollisions();
            enforceMaximumLinkLength(maximum);
        }

        // Length correction is allowed to slide along terrain, but the last
        // operation must always leave the actual stem geometry outside blocks.
        resolveStemLinkCollisions();
        for (int i = 0; i < nodes.length; i++) {
            Vec3d resolved = resolveNodePenetration(nodes[i]);
            Vec3d correction = resolved.subtract(nodes[i]);
            if (correction.lengthSquared() > COLLISION_EPSILON * COLLISION_EPSILON) {
                nodes[i] = resolved;
                removeInwardVelocity(i, correction.normalize());
            }
        }
    }

    private void enforceMaximumLinkLength(double maximum) {
        for (int i = 0; i < nodes.length; i++) {
            Vec3d connection = i == 0 ? root : nodes[i - 1];
            Vec3d delta = nodes[i].subtract(connection);
            double distance = delta.length();
            if (distance <= maximum || distance < COLLISION_EPSILON) continue;

            Vec3d correction = delta.multiply((distance - maximum) / distance);
            if (i == 0) {
                moveNodeWithCollision(i, correction.negate(), true);
            } else {
                moveNodeWithCollision(i - 1, correction.multiply(0.5), true);
                moveNodeWithCollision(i, correction.multiply(-0.5), true);
            }
        }
    }

    private void resolveStemLinkCollisions() {
        for (int i = 0; i < nodes.length; i++) {
            Vec3d start = i == 0 ? root : nodes[i - 1];
            Vec3d end = nodes[i];
            SegmentCorrection hit = findStemLinkCollision(start, end, i == 0);
            if (hit == null) continue;

            Vec3d correction = clampMagnitude(hit.correction, 0.5);
            Vec3d normal = correction.normalize();
            if (i == 0) {
                double tipWeight = Math.max(0.15, hit.t);
                moveNodeWithCollision(i, correction.multiply(1.0 / tipWeight), true);
                removeInwardVelocity(i, normal);
            } else {
                double previousWeight = 1.0 - hit.t;
                double currentWeight = hit.t;
                double denominator = previousWeight * previousWeight + currentWeight * currentWeight;
                moveNodeWithCollision(i - 1,
                        correction.multiply(previousWeight / denominator), true);
                moveNodeWithCollision(i,
                        correction.multiply(currentWeight / denominator), true);
                removeInwardVelocity(i - 1, normal);
                removeInwardVelocity(i, normal);
            }
        }
    }

    private SegmentCorrection findStemLinkCollision(Vec3d start, Vec3d end, boolean rootLink) {
        Vec3d segment = end.subtract(start);
        double length = segment.length();
        if (length < COLLISION_EPSILON) return null;

        Box broadPhase = new Box(start, end).expand(STEM_COLLISION_RADIUS + COLLISION_EPSILON);
        List<Box> obstacles = getBlockCollisionBoxes(broadPhase);
        if (obstacles.isEmpty()) return null;

        Vec3d strongest = Vec3d.ZERO;
        double strongestLengthSquared = 0.0;
        double strongestT = 0.5;
        int intervals = Math.max(2, (int) Math.ceil(length / STEM_COLLISION_SAMPLE_SPACING));
        for (int sampleIndex = 1; sampleIndex < intervals; sampleIndex++) {
            double t = sampleIndex / (double) intervals;
            if (rootLink && length * t <= STEM_COLLISION_RADIUS + COLLISION_EPSILON) continue;

            Vec3d sample = start.add(segment.multiply(t));
            Vec3d resolved = pushPointOutOfBoxes(sample, obstacles, STEM_COLLISION_RADIUS);
            Vec3d correction = resolved.subtract(sample);
            if (correction.lengthSquared() > strongestLengthSquared) {
                strongest = correction;
                strongestLengthSquared = correction.lengthSquared();
                strongestT = t;
            }
        }
        return strongestLengthSquared > COLLISION_EPSILON * COLLISION_EPSILON
                ? new SegmentCorrection(strongest, strongestT) : null;
    }

    /** Swept AABB collision using Minecraft's actual block voxel shapes. */
    private Vec3d moveNodeWithCollision(int index, Vec3d requested, boolean affectVelocity) {
        if (index < 0 || index >= nodes.length || requested.lengthSquared() < 1.0E-14) {
            return Vec3d.ZERO;
        }

        Vec3d start = resolveNodePenetration(nodes[index]);
        Vec3d depenetration = start.subtract(nodes[index]);
        if (depenetration.lengthSquared() > COLLISION_EPSILON * COLLISION_EPSILON) {
            nodes[index] = start;
            removeInwardVelocity(index, depenetration.normalize());
        }

        Box movingBox = nodeBox(start);
        Box broadPhase = stretch(movingBox, requested).expand(COLLISION_EPSILON);
        List<VoxelShape> collisions = getBlockCollisionShapes(broadPhase);
        if (collisions.isEmpty()) {
            nodes[index] = start.add(requested);
            return requested;
        }

        double x = requested.x;
        double y = requested.y;
        double z = requested.z;
        if (y != 0.0) {
            y = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, movingBox, collisions, y);
            movingBox = movingBox.offset(0.0, y, 0.0);
        }
        if (Math.abs(x) < Math.abs(z)) {
            if (z != 0.0) {
                z = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, movingBox, collisions, z);
                movingBox = movingBox.offset(0.0, 0.0, z);
            }
            if (x != 0.0) x = VoxelShapes.calculateMaxOffset(Direction.Axis.X, movingBox, collisions, x);
        } else {
            if (x != 0.0) {
                x = VoxelShapes.calculateMaxOffset(Direction.Axis.X, movingBox, collisions, x);
                movingBox = movingBox.offset(x, 0.0, 0.0);
            }
            if (z != 0.0) z = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, movingBox, collisions, z);
        }

        Vec3d moved = new Vec3d(x, y, z);
        nodes[index] = start.add(moved);
        if (affectVelocity) applyBlockedAxisResponse(index, requested, moved);
        return moved;
    }

    private void applyBlockedAxisResponse(int index, Vec3d requested, Vec3d moved) {
        boolean blockedX = Math.abs(requested.x - moved.x) > COLLISION_EPSILON;
        boolean blockedY = Math.abs(requested.y - moved.y) > COLLISION_EPSILON;
        boolean blockedZ = Math.abs(requested.z - moved.z) > COLLISION_EPSILON;
        if (!blockedX && !blockedY && !blockedZ) return;

        Vec3d velocity = velocities[index];
        double x = blockedX ? 0.0 : velocity.x;
        double y = blockedY ? 0.0 : velocity.y;
        double z = blockedZ ? 0.0 : velocity.z;
        if (!blockedX) x *= SURFACE_FRICTION;
        if (!blockedY) y *= SURFACE_FRICTION;
        if (!blockedZ) z *= SURFACE_FRICTION;
        velocities[index] = new Vec3d(x, y, z);
    }

    private void removeInwardVelocity(int index, Vec3d outwardNormal) {
        Vec3d velocity = velocities[index];
        double inward = velocity.dotProduct(outwardNormal);
        if (inward < 0.0) velocity = velocity.subtract(outwardNormal.multiply(inward));
        velocities[index] = velocity.multiply(SURFACE_FRICTION);
    }

    private Vec3d resolveNodePenetration(Vec3d point) {
        Vec3d result = point;
        for (int pass = 0; pass < 4; pass++) {
            List<Box> obstacles = getBlockCollisionBoxes(nodeBox(result).expand(COLLISION_EPSILON));
            Vec3d resolved = pushPointOutOfBoxes(result, obstacles, STEM_COLLISION_RADIUS);
            if (resolved.squaredDistanceTo(result) <= COLLISION_EPSILON * COLLISION_EPSILON) break;
            result = resolved;
        }
        return result;
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

    private static boolean contains(Box box, Vec3d point) {
        return point.x >= box.minX && point.x <= box.maxX
                && point.y >= box.minY && point.y <= box.maxY
                && point.z >= box.minZ && point.z <= box.maxZ;
    }

    private static Box nodeBox(Vec3d center) {
        return new Box(center, center).expand(STEM_COLLISION_RADIUS);
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

    private record SegmentCorrection(Vec3d correction, double t) {
    }

    private List<Vec3d> buildHitboxCenters() {
        List<Vec3d> result = new ArrayList<>();
        if (nodes.length == 0 || inDen) return result;
        Vec3d previous = positionAlongStem(0.0);
        result.add(previous);
        double carry = 0.0;
        for (int i = 1; i <= 512; i++) {
            Vec3d current = positionAlongStem(i / 512.0);
            Vec3d start = previous;
            double distance = start.distanceTo(current);
            while (carry + distance >= HITBOX_SIZE && distance > 0.0001) {
                double step = (HITBOX_SIZE - carry) / distance;
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
        if (nodes.length == 1) return nodes[0];
        double x = MathHelper.clamp(f, 0.0, 1.0) * nodes.length;
        int i = MathHelper.clamp((int) x, 0, nodes.length - 1);
        if (i == 0) return root.lerp(nodes[0], x);
        int j = Math.min(i, nodes.length - 1);
        return nodes[j - 1].lerp(nodes[j], x - i);
    }

    private void syncHitboxes() {
        List<Vec3d> centers = buildHitboxCenters();
        while (serverHitboxes.size() < centers.size()) {
            PolePlantSegmentEntity segment = new PolePlantSegmentEntity(
                    KarmaGateMod.POLE_PLANT_SEGMENT_ENTITY_TYPE, getWorld());
            segment.setParent(this, serverHitboxes.size());
            segment.setPosition(root.x, root.y, root.z);
            getWorld().spawnEntity(segment);
            serverHitboxes.add(segment);
        }
        while (serverHitboxes.size() > centers.size()) {
            serverHitboxes.removeLast().discard();
        }
        for (int i = 0; i < centers.size(); i++) {
            PolePlantSegmentEntity segment = serverHitboxes.get(i);
            if (segment.isRemoved()) {
                segment = new PolePlantSegmentEntity(KarmaGateMod.POLE_PLANT_SEGMENT_ENTITY_TYPE, getWorld());
                segment.setParent(this, i);
                getWorld().spawnEntity(segment);
                serverHitboxes.set(i, segment);
            }
            Vec3d center = centers.get(i);
            segment.setParent(this, i);
            segment.setPosition(center.x, center.y, center.z);
            segment.setVelocity(Vec3d.ZERO);
            segment.velocityDirty = true;
        }
    }

    private void syncTrackedState() {
        dataTracker.set(MIMIC, mimic);
        dataTracker.set(EXTENDED, extended);
        dataTracker.set(RETREATING, retreating);
        dataTracker.set(IN_DEN, inDen);
        dataTracker.set(BEHAVIOUR, getBehaviour().ordinal());
        dataTracker.set(STEM_NODES, packStemNodes());
        dataTracker.set(SNAG_TARGETS, packSnagTargets());
    }

    public void registerClientHitbox(PolePlantSegmentEntity segment) {
        // Hitbox entities are collision-only. Visuals use the coherent parent snapshot.
    }

    public List<Vec3d> getClientStemPositions(float tickDelta) {
        Vec3d base = getLerpedPos(tickDelta);
        if (clientNodes.length > 0) {
            List<Vec3d> snapshot = new ArrayList<>(clientNodes.length + 1);
            snapshot.add(base);
            for (int i = 0; i < clientNodes.length; i++) {
                Vec3d previous = clientLastNodes.length == clientNodes.length
                        ? clientLastNodes[i] : clientNodes[i];
                snapshot.add(base.add(previous.lerp(clientNodes[i], tickDelta)));
            }
            return snapshot;
        }

        // Avoid flashing a full-height pole before the first authoritative snapshot arrives.
        return List.of(base);
    }

    private String packStemNodes() {
        if (nodes.length == 0) return "";
        StringBuilder packed = new StringBuilder(nodes.length * 32);
        for (int i = 0; i < nodes.length; i++) {
            if (i > 0) packed.append(';');
            Vec3d relative = nodes[i].subtract(root);
            packed.append((float) relative.x).append(',')
                    .append((float) relative.y).append(',')
                    .append((float) relative.z);
        }
        return packed.toString();
    }

    private String packSnagTargets() {
        if (snagTargets.length == 0) return "";
        StringBuilder packed = new StringBuilder(snagTargets.length * 4);
        for (int i = 0; i < snagTargets.length; i++) {
            if (i > 0) packed.append(',');
            LivingEntity target = snagTargets[i];
            packed.append(isValidPrey(target) ? target.getId() : -1);
        }
        return packed.toString();
    }

    private void updateClientNodeSnapshot() {
        String packed = dataTracker.get(STEM_NODES);
        if (packed.equals(clientPackedNodes)) {
            clientLastNodes = clientNodes;
            return;
        }
        Vec3d[] decoded = unpackStemNodes(packed);
        if (decoded.length == 0) return;
        clientLastNodes = clientNodes.length == decoded.length ? clientNodes : decoded;
        clientNodes = decoded;
        clientPackedNodes = packed;
    }

    private void updateClientSnagTargets() {
        String packed = dataTracker.get(SNAG_TARGETS);
        if (packed.equals(clientPackedSnagTargets)) return;
        if (packed.isEmpty()) {
            clientSnagTargetIds = new int[0];
            clientPackedSnagTargets = packed;
            return;
        }

        String[] entries = packed.split(",");
        int[] decoded = new int[entries.length];
        try {
            for (int i = 0; i < entries.length; i++) decoded[i] = Integer.parseInt(entries[i]);
            clientSnagTargetIds = decoded;
            clientPackedSnagTargets = packed;
        } catch (NumberFormatException ignored) {
            clientSnagTargetIds = new int[0];
        }
    }

    private static Vec3d[] unpackStemNodes(String packed) {
        if (packed == null || packed.isEmpty()) return new Vec3d[0];
        String[] entries = packed.split(";");
        Vec3d[] decoded = new Vec3d[entries.length];
        try {
            for (int i = 0; i < entries.length; i++) {
                String[] components = entries[i].split(",");
                if (components.length != 3) return new Vec3d[0];
                decoded[i] = new Vec3d(Double.parseDouble(components[0]),
                        Double.parseDouble(components[1]), Double.parseDouble(components[2]));
            }
            return decoded;
        } catch (NumberFormatException ignored) {
            return new Vec3d[0];
        }
    }

    public int getPlantHeight() {
        int size = dataTracker.get(SIZE_BLOCKS);
        return Math.max(MIN_SIZE_BLOCKS, size);
    }

    public float getMimic() {
        return dataTracker.get(MIMIC);
    }

    public float getExtended() {
        return dataTracker.get(EXTENDED);
    }

    public Entity getClientSnagTarget(float stemPosition) {
        if (clientSnagTargetIds.length == 0) return null;
        int index = MathHelper.clamp((int) (stemPosition * clientSnagTargetIds.length),
                0, clientSnagTargetIds.length - 1);
        int entityId = clientSnagTargetIds[index];
        return entityId < 0 ? null : getWorld().getEntityById(entityId);
    }

    public Behaviour getBehaviour() {
        if (!isAlive()) return Behaviour.LIMP;
        if (!getWorld().isClient && (grabbedTarget != null || huntTarget != null || huntCounter > 0)) {
            return Behaviour.HUNTING;
        }
        int tracked = dataTracker.get(BEHAVIOUR);
        if (getWorld().isClient) return Behaviour.values()[MathHelper.clamp(tracked, 0, Behaviour.values().length - 1)];
        if (wakeUpCounter > 0) return Behaviour.IDLE_AWAKE;
        return Behaviour.POLE_CAMOUFLAGE;
    }

    private float wakeUpFactor() {
        return (float) inverseLerp(10.0, 80.0, wakeUpCounter);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (inDen || isInvulnerableTo(source)) return false;
        boolean damaged = super.damage(source, amount);
        if (damaged) {
            triggerThrash(amount);
            if (!isAlive()) beginDeathRetreat();
        }
        return damaged;
    }

    private void triggerThrash(float damage) {
        angeredAndAggressive = Math.max(angeredAndAggressive, 80 + random.nextInt(40));
        wakeUpCounter = Math.max(wakeUpCounter, angeredAndAggressive);
        mimicDelayCounter = 0;
        tipAttached = false;
        double strength = Math.min(0.18, 0.055 + damage * 0.012);
        for (int i = 0; i < velocities.length; i++) {
            float envelope = (float) Math.sin(Math.PI * (i + 1.0) / (velocities.length + 1.0));
            velocities[i] = velocities[i].add(randomUnit().multiply(strength * (0.35 + envelope)));
        }
    }

    private void beginDeathRetreat() {
        retreating = true;
        angeredAndAggressive = Math.max(angeredAndAggressive, 120);
        tipAttached = false;
    }

    @Override
    protected void updatePostDeath() {
        // Keep the dead controller alive until the source 60-step retreat finishes.
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
            for (PolePlantSegmentEntity segment : serverHitboxes) {
                if (!segment.isRemoved()) segment.discard();
            }
            serverHitboxes.clear();
        }
        super.remove(reason);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("PolePlantAnchorSet", anchorSet);
        nbt.putDouble("PolePlantRootX", root.x);
        nbt.putDouble("PolePlantRootY", root.y);
        nbt.putDouble("PolePlantRootZ", root.z);
        nbt.putInt("PolePlantSize", dataTracker.get(SIZE_BLOCKS));
        nbt.putFloat("PolePlantExtended", extended);
        nbt.putFloat("PolePlantMimic", mimic);
        nbt.putFloat("PolePlantForceIntoShortcut", forceIntoShortCut);
        nbt.putBoolean("PolePlantRetreating", retreating);
        nbt.putBoolean("PolePlantInDen", inDen);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        anchorSet = nbt.getBoolean("PolePlantAnchorSet");
        root = new Vec3d(nbt.getDouble("PolePlantRootX"), nbt.getDouble("PolePlantRootY"),
                nbt.getDouble("PolePlantRootZ"));
        dataTracker.set(SIZE_BLOCKS, nbt.getInt("PolePlantSize"));
        extended = nbt.getFloat("PolePlantExtended");
        mimic = nbt.getFloat("PolePlantMimic");
        forceIntoShortCut = nbt.getFloat("PolePlantForceIntoShortcut");
        retreating = nbt.getBoolean("PolePlantRetreating");
        inDen = nbt.getBoolean("PolePlantInDen");
        loadedFromNbt = true;
        initialized = false;
    }

    private Vec3d randomUnit() {
        double z = random.nextDouble() * 2.0 - 1.0;
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(Math.max(0.0, 1.0 - z * z));
        return new Vec3d(Math.cos(angle) * radius, z, Math.sin(angle) * radius);
    }

    private static Vec3d direction(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        return delta.lengthSquared() < 0.0000001 ? Vec3d.ZERO : delta.normalize();
    }

    private static Vec3d clampMagnitude(Vec3d value, double maximum) {
        return value.lengthSquared() > maximum * maximum ? value.normalize().multiply(maximum) : value;
    }

    private static double inverseLerp(double from, double to, double value) {
        if (from == to) return 0.0;
        return MathHelper.clamp((value - from) / (to - from), 0.0, 1.0);
    }

    private static double map(double value, double inA, double inB, double outA, double outB) {
        return MathHelper.lerp(inverseLerp(inA, inB, value), outA, outB);
    }

    private static float lerpAndTick(float value, float target, float lerp, float tick) {
        value = MathHelper.lerp(lerp, value, target);
        return value < target ? Math.min(target, value + tick) : Math.max(target, value - tick);
    }

    public enum Behaviour {
        POLE_CAMOUFLAGE,
        IDLE_AWAKE,
        HUNTING,
        LIMP
    }
}
