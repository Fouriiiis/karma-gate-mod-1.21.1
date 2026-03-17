package dev.fouriis.karmagate.entity.daddy;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

public class DaddyTentacleController {
    public enum Task {
        SEARCHING,
        EXTENDING,
        ANCHORED,
        RELEASING
    }

    public record Snapshot(
            Task task,
            boolean support,
            boolean anchored,
            float extensionProgress,
            Vec3d anchorPos,
            Vec3d temporaryGoalPos,
            Vec3d currentTipPos
    ) {
    }

    private final int index;
    private final boolean support;
    private final double personalPhase;
    private final double reachBias;

    private Task task = Task.SEARCHING;
    private Vec3d anchorPos;
    private Vec3d idealAnchorPos;
    private Vec3d temporaryGoalPos;
    private Vec3d currentTipPos;
    private Vec3d currentTipVel = Vec3d.ZERO;
    private float extensionProgress;
    private float gripStrength;
    private int stateTicks;
    private int searchTicksWithoutImprovement;

    private final List<TentacleSegmentState> segmentStates = new ArrayList<>();

    public DaddyTentacleController(int index, boolean support, DaddyVariantConfig config) {
        this.index = index;
        this.support = support;
        this.personalPhase = index * 1.137 + (support ? 0.8 : 0.0);
        this.reachBias = 0.85 + (index % 3) * 0.08;
        this.extensionProgress = 0f;
        for (int i = 0; i <= config.tentacleSegments(); i++) {
            segmentStates.add(new TentacleSegmentState(Vec3d.ZERO));
        }
    }

    public Vec3d tick(
            ServerWorld world,
            DaddyLongLegsEntity entity,
            Vec3d bodyPos,
            Vec3d socketPos,
            Vec3d targetPos,
            DaddyVariantConfig config,
            Random random,
            float searchBiasBoost,
            boolean forceReanchor
    ) {
        stateTicks++;

        if (currentTipPos == null) {
            currentTipPos = socketPos;
            currentTipVel = Vec3d.ZERO;
            initializeSegments(socketPos, config);
        }

        if (forceReanchor && task == Task.ANCHORED) {
            task = Task.RELEASING;
            stateTicks = 0;
        }

        updateTaskState(world, entity, bodyPos, socketPos, targetPos, config, random, searchBiasBoost);

        Vec3d desiredTip = switch (task) {
            case SEARCHING -> getSearchingDesiredTip(socketPos, targetPos, config);
            case EXTENDING -> getExtendingDesiredTip(socketPos, config);
            case ANCHORED -> anchorPos != null ? anchorPos : socketPos;
            case RELEASING -> getReleasingDesiredTip(socketPos);
        };

        updateTipMotion(socketPos, desiredTip, config, task == Task.ANCHORED);
        updateSegmentPath(world, entity, socketPos, currentTipPos, config);

        if (task == Task.ANCHORED || task == Task.EXTENDING) {
            return computePull(socketPos, bodyPos, targetPos, config);
        }
        return Vec3d.ZERO;
    }

    public Snapshot toSnapshot() {
        return new Snapshot(
                task,
                support,
                task == Task.ANCHORED || task == Task.EXTENDING,
                extensionProgress,
                anchorPos == null ? Vec3d.ZERO : anchorPos,
                temporaryGoalPos == null ? Vec3d.ZERO : temporaryGoalPos,
                currentTipPos == null ? Vec3d.ZERO : currentTipPos
        );
    }

    public List<TentacleSegmentState> getSegmentStates() {
        return segmentStates;
    }

    public boolean isSupport() {
        return support;
    }

    public boolean isAnchored() {
        return task == Task.ANCHORED || task == Task.EXTENDING;
    }

    private void initializeSegments(Vec3d socketPos, DaddyVariantConfig config) {
        for (int i = 0; i < segmentStates.size(); i++) {
            double t = i / (double) Math.max(1, segmentStates.size() - 1);
            Vec3d p = socketPos.add(0.0, -t * Math.min(0.6, config.tentacleLength() * 0.10), 0.0);
            segmentStates.set(i, new TentacleSegmentState(p));
        }
    }

    private void updateTaskState(
            ServerWorld world,
            DaddyLongLegsEntity entity,
            Vec3d bodyPos,
            Vec3d socketPos,
            Vec3d targetPos,
            DaddyVariantConfig config,
            Random random,
            float searchBiasBoost
    ) {
        if (task == Task.SEARCHING) {
            updateTemporaryGoal(world, entity, bodyPos, socketPos, targetPos, config, random, searchBiasBoost);
            extensionProgress = 0f;
            gripStrength = Math.max(0f, gripStrength - 0.06f);

            if (temporaryGoalPos != null && (stateTicks > 6 || searchTicksWithoutImprovement > 8)) {
                anchorPos = temporaryGoalPos;
                task = Task.EXTENDING;
                extensionProgress = 0.08f;
                stateTicks = 0;
            }
        } else if (task == Task.EXTENDING) {
            gripStrength = Math.max(0f, gripStrength - 0.03f);
            extensionProgress = Math.min(1f, extensionProgress + (support ? 0.08f : 0.10f));
            if (anchorPos == null) {
                task = Task.SEARCHING;
                stateTicks = 0;
                return;
            }
            if (currentTipPos.distanceTo(anchorPos) < 0.30 || extensionProgress >= 1f) {
                task = Task.ANCHORED;
                gripStrength = 1f;
                stateTicks = 0;
            }
        } else if (task == Task.ANCHORED) {
            gripStrength = Math.min(1f, gripStrength + 0.08f);
            if (shouldRelease(bodyPos, socketPos, targetPos, config, searchBiasBoost)) {
                task = Task.RELEASING;
                stateTicks = 0;
            }
        } else if (task == Task.RELEASING) {
            gripStrength = Math.max(0f, gripStrength - 0.08f);
            extensionProgress = Math.max(0f, extensionProgress - 0.12f);
            if (extensionProgress <= 0f || anchorPos == null) {
                task = Task.SEARCHING;
                anchorPos = null;
                temporaryGoalPos = null;
                stateTicks = 0;
                searchTicksWithoutImprovement = 0;
            }
        }
    }

    private void updateTemporaryGoal(
            ServerWorld world,
            DaddyLongLegsEntity entity,
            Vec3d bodyPos,
            Vec3d socketPos,
            Vec3d targetPos,
            DaddyVariantConfig config,
            Random random,
            float searchBiasBoost
    ) {
        idealAnchorPos = computeIdealAnchor(bodyPos, socketPos, targetPos, config, searchBiasBoost);

        if (temporaryGoalPos == null) {
            temporaryGoalPos = idealAnchorPos;
        }

        boolean improved = false;
        for (int i = 0; i < 4; i++) {
            Vec3d candidate = TentacleAnchorFinder.findBestAnchor(
                    world,
                    entity,
                    bodyPos,
                    socketPos,
                    targetPos,
                    idealAnchorPos,
                    config,
                    support,
                    random,
                    searchBiasBoost
            );
            if (candidate == null) {
                continue;
            }

            double currentScore = scoreCandidate(world, bodyPos, socketPos, targetPos, temporaryGoalPos, config, searchBiasBoost);
            double newScore = scoreCandidate(world, bodyPos, socketPos, targetPos, candidate, config, searchBiasBoost);
            if (newScore > currentScore) {
                temporaryGoalPos = candidate;
                improved = true;
            }
        }

        if (improved) {
            searchTicksWithoutImprovement = 0;
        } else {
            searchTicksWithoutImprovement++;
        }
    }

    private double scoreCandidate(
            ServerWorld world,
            Vec3d bodyPos,
            Vec3d socketPos,
            Vec3d targetPos,
            Vec3d candidate,
            DaddyVariantConfig config,
            float searchBiasBoost
    ) {
        if (candidate == null) {
            return -1.0e9;
        }

        double score = 100.0 / Math.max(0.25, candidate.distanceTo(idealAnchorPos));
        Vec3d toTarget = targetPos.subtract(bodyPos);
        if (toTarget.lengthSquared() > 1.0e-5) {
            Vec3d targetDir = toTarget.normalize();
            double forward = candidate.subtract(bodyPos).normalize().dotProduct(targetDir);
            score += forward * (support ? 4.0 : 8.0 + searchBiasBoost * 1.5);
        }

        double socketDistance = socketPos.distanceTo(candidate);
        score -= Math.max(0.0, socketDistance - config.tentacleLength()) * 10.0;

        if (support) {
            score += Math.max(0.0, bodyPos.y - candidate.y) * 0.8;
            score += Math.max(0.0, candidate.y - bodyPos.y) * 0.35;
        } else {
            score += Math.max(0.0, candidate.y - bodyPos.y) * 0.65;
        }

        if (TentacleAnchorFinder.isInsideSolid(world, candidate)) {
            score -= 1000.0;
        }

        return score;
    }

    private Vec3d computeIdealAnchor(Vec3d bodyPos, Vec3d socketPos, Vec3d targetPos, DaddyVariantConfig config, float searchBiasBoost) {
        Vec3d toTarget = targetPos.subtract(bodyPos);
        Vec3d targetDir = toTarget.lengthSquared() > 1.0e-5 ? toTarget.normalize() : new Vec3d(0, 0, 1);

        double angle = personalPhase + bodyPos.y * 0.05 + stateTicks * 0.01;
        double verticalBias = support
                ? MathHelper.clamp(-0.28 + targetDir.y * 0.75, -0.50, 0.45)
                : MathHelper.clamp(0.05 + targetDir.y * 0.95, -0.35, 0.85);
        Vec3d radial = new Vec3d(Math.cos(angle), verticalBias, Math.sin(angle));
        radial = radial.normalize();

        double reach = config.anchorSearchRadius() * reachBias * (support ? 0.84 : 1.0);
        Vec3d forwardBias = targetDir.multiply(config.anchorSearchForwardBias() + searchBiasBoost);
        return socketPos.add(radial.multiply(reach)).add(forwardBias);
    }

    private Vec3d getSearchingDesiredTip(Vec3d socketPos, Vec3d targetPos, DaddyVariantConfig config) {
        Vec3d desired = temporaryGoalPos != null ? temporaryGoalPos : socketPos;
        Vec3d toward = desired.subtract(socketPos);
        if (toward.lengthSquared() > 1.0e-5) {
            double probe = Math.min(1.0, 0.35 + searchTicksWithoutImprovement * 0.04);
            desired = socketPos.add(toward.multiply(probe));
        }

        double wiggle = 0.18 + (support ? 0.05 : 0.12);
        double phase = personalPhase + stateTicks * 0.18;
        Vec3d sway = new Vec3d(
                Math.cos(phase) * wiggle,
                Math.sin(phase * 1.4) * wiggle * 0.30 - config.tentacleLength() * 0.015,
                Math.sin(phase) * wiggle
        );

        Vec3d towardTarget = targetPos.subtract(socketPos);
        if (!support && towardTarget.lengthSquared() > 1.0e-5) {
            sway = sway.add(towardTarget.normalize().multiply(0.18));
        }

        return desired.add(sway);
    }

    private Vec3d getExtendingDesiredTip(Vec3d socketPos, DaddyVariantConfig config) {
        if (anchorPos == null) {
            return socketPos;
        }
        Vec3d desired = socketPos.lerp(anchorPos, MathHelper.clamp(extensionProgress, 0f, 1f));
        double overshoot = support ? 0.03 : 0.08;
        Vec3d dir = anchorPos.subtract(socketPos);
        if (dir.lengthSquared() > 1.0e-5) {
            desired = desired.add(dir.normalize().multiply(Math.sin(extensionProgress * Math.PI) * overshoot));
        }
        desired = desired.add(0.0, -config.tentacleLength() * 0.010 * (1.0 - extensionProgress), 0.0);
        return desired;
    }

    private Vec3d getReleasingDesiredTip(Vec3d socketPos) {
        if (anchorPos == null) {
            return socketPos;
        }
        Vec3d dir = socketPos.subtract(anchorPos);
        Vec3d desired = socketPos.lerp(anchorPos, extensionProgress * 0.7f);
        if (dir.lengthSquared() > 1.0e-5) {
            desired = desired.add(dir.normalize().multiply(0.10));
        }
        return desired;
    }

    private void updateTipMotion(Vec3d socketPos, Vec3d desiredTip, DaddyVariantConfig config, boolean anchored) {
        Vec3d toDesired = desiredTip.subtract(currentTipPos);
        currentTipVel = currentTipVel.multiply(anchored ? 0.86 : 0.92);
        currentTipVel = currentTipVel.add(toDesired.multiply(anchored ? 0.08 : 0.05));

        double tipGravity = support ? 0.010 : 0.014;
        if (anchored) {
            tipGravity *= 0.55;
        }
        currentTipVel = currentTipVel.add(0.0, -tipGravity, 0.0);

        currentTipPos = currentTipPos.add(currentTipVel);

        Vec3d fromSocket = currentTipPos.subtract(socketPos);
        double maxReach = configuredSoftReach(config) * (anchored ? 1.0 : 0.92);
        if (fromSocket.lengthSquared() > maxReach * maxReach) {
            currentTipPos = socketPos.add(fromSocket.normalize().multiply(maxReach));
            currentTipVel = currentTipVel.multiply(0.72);
        }

    }

    private double configuredSoftReach(DaddyVariantConfig config) {
        return Math.max(2.5, config.tentacleLength() * (support ? 0.82 : 0.94));
    }


    private void updateSegmentPath(ServerWorld world, DaddyLongLegsEntity entity, Vec3d socketPos, Vec3d tip, DaddyVariantConfig config) {
        List<Vec3d> guide = TentaclePathSolver.solvePath(world, entity, socketPos, tip, config.tentacleSegments(), config.tentacleLength());
        if (guide.isEmpty()) {
            return;
        }

        if (segmentStates.size() != guide.size()) {
            segmentStates.clear();
            for (Vec3d p : guide) {
                segmentStates.add(new TentacleSegmentState(p));
            }
        }

        Vec3d bodyDrift = socketPos.subtract(segmentStates.get(0).getPos()).multiply(0.12);
        for (int i = 1; i < segmentStates.size() - 1; i++) {
            double along = i / (double) (segmentStates.size() - 1);
            double centerSag = Math.sin(along * Math.PI);
            double gravityAmount = (task == Task.ANCHORED ? 0.018 : 0.026) * centerSag;
            if (task == Task.RELEASING) {
                gravityAmount *= 1.20;
            }
            if (support) {
                gravityAmount *= 0.90;
            }

            double guideFollow = task == Task.ANCHORED ? 0.05 : 0.09;
            Vec3d toGuide = guide.get(i).subtract(segmentStates.get(i).getPos()).multiply(guideFollow);
            Vec3d accel = toGuide.add(bodyDrift.multiply(1.0 - along)).add(0.0, -gravityAmount, 0.0);
            segmentStates.get(i).verlet(accel, task == Task.ANCHORED ? 0.92 : 0.90);
            segmentStates.get(i).setPos(TentacleAnchorFinder.pushOutOfSolid(world, segmentStates.get(i).getPos()));
        }

        segmentStates.get(0).pin(socketPos);
        segmentStates.get(segmentStates.size() - 1).pin(tip);

        double rest = config.tentacleLength() / Math.max(1, segmentStates.size() - 1);
        for (int pass = 0; pass < 10; pass++) {
            segmentStates.get(0).pin(socketPos);
            segmentStates.get(segmentStates.size() - 1).pin(tip);

            for (int i = 0; i < segmentStates.size() - 1; i++) {
                TentacleSegmentState a = segmentStates.get(i);
                TentacleSegmentState b = segmentStates.get(i + 1);

                Vec3d delta = b.getPos().subtract(a.getPos());
                double dist = Math.max(1.0e-6, delta.length());
                double diff = (dist - rest) / dist;

                double aWeight = (i == 0) ? 0.0 : 0.55;
                double bWeight = (i + 1 == segmentStates.size() - 1) ? 0.0 : 0.55;
                Vec3d correction = delta.multiply(diff);

                if (aWeight > 0.0) {
                    a.setPos(TentacleAnchorFinder.pushOutOfSolid(world, a.getPos().add(correction.multiply(aWeight))));
                }
                if (bWeight > 0.0) {
                    b.setPos(TentacleAnchorFinder.pushOutOfSolid(world, b.getPos().subtract(correction.multiply(bWeight))));
                }
            }
        }
    }

    private Vec3d computePull(Vec3d socketPos, Vec3d bodyPos, Vec3d targetPos, DaddyVariantConfig config) {
        if (anchorPos == null) {
            return Vec3d.ZERO;
        }

        Vec3d toAnchor = anchorPos.subtract(socketPos);
        double dist = toAnchor.length();
        if (dist < 1.0e-4) {
            return Vec3d.ZERO;
        }

        Vec3d dir = toAnchor.multiply(1.0 / dist);
        double desired = support ? config.tentacleLength() * 0.52 : config.tentacleLength() * 0.64;
        double stretch = Math.max(0.0, dist - desired);
        float stateFactor = task == Task.EXTENDING ? MathHelper.clamp(extensionProgress, 0.15f, 0.75f) : 1f;

        Vec3d pull = dir.multiply(stretch * config.pullStrength() * stateFactor * Math.max(0.18f, gripStrength));

        Vec3d targetDir = targetPos.subtract(bodyPos);
        if (targetDir.lengthSquared() > 1.0e-5) {
            targetDir = targetDir.normalize();
            double towardTarget = Math.max(0.0, dir.dotProduct(targetDir));
            pull = pull.multiply(1.0 + towardTarget * (support ? 0.22 : 0.55));
        }

        if (support && anchorPos.y < bodyPos.y) {
            pull = pull.add(0.0, (bodyPos.y - anchorPos.y) * 0.006 * gripStrength, 0.0);
        }

        if (!support && targetPos.y > bodyPos.y) {
            pull = pull.add(0.0, Math.min(0.020, (targetPos.y - bodyPos.y) * 0.006) * gripStrength, 0.0);
        }

        return pull;
    }

    private boolean shouldRelease(Vec3d bodyPos, Vec3d socketPos, Vec3d targetPos, DaddyVariantConfig config, float searchBiasBoost) {
        if (anchorPos == null) {
            return true;
        }
        if (socketPos.distanceTo(anchorPos) > config.tentacleLength() * 1.10f) {
            return true;
        }

        Vec3d toTarget = targetPos.subtract(bodyPos);
        if (toTarget.lengthSquared() > 1.0e-5) {
            Vec3d targetDir = toTarget.normalize();
            double usefulness = anchorPos.subtract(bodyPos).normalize().dotProduct(targetDir);
            if (!support && usefulness < -0.30 - (searchBiasBoost * 0.05)) {
                return true;
            }
            if (support && anchorPos.y > bodyPos.y + 3.0 && targetPos.y <= bodyPos.y + 0.25) {
                return true;
            }
        }

        return stateTicks > (support ? 72 : 54);
    }
}
