package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Shared interface between all centipede controller entity types (Red, Normal, etc.).
 * Segments, renderers, and AI goals reference the parent controller through this interface,
 * allowing the same CentipedeHeadEntity/CentipedeBodyEntity/renderers/goals to work
 * with any centipede variant.
 *
 * Implementing classes extend HostileEntity, so MobEntity methods (getTarget(), getPos(),
 * getLookControl(), etc.) are available when using bounded type parameters
 * {@code <T extends HostileEntity & CentipedeController>}.
 */
public interface CentipedeController {

    // --- Segment management ---
    CentipedeSegmentEntity[] getSegments();
    void registerClientSegment(CentipedeSegmentEntity seg);
    int getTotalSegments();

    // --- Movement state ---
    float getWalkCycle();
    boolean isBodyDirection();

    // --- Size system (C# Centipede.size: 0-1 for normal, 1.0 for Red) ---
    float getSize();

    // --- Per-segment radius (C# body chunk radius formula, varies by centipede type) ---
    float computeSegmentRadius(int segIndex);
    float getMaxRadius();
    double getSegmentSpacing(int a, int b);

    // --- Rendering colors ---
    /** Shell color as packed 0xRRGGBB. */
    int getShellColorRGB();
    /** Secondary (darker) shell color as packed 0xRRGGBB. */
    int getSecondaryShellColorRGB();
    /** Leg sprite scale factor (C#: 1.3 for Red, 1.0 for normal, 0.65 for Centiwing). */
    float getLegScale();

    // --- Wing system (Centiwing) ---
    /**
     * Overall scale factor for head segment rendering (relative to red centipede = 1.0).
     * Small variants return 0.5 to match their halved body radius.
     */
    default float getHeadScaleFactor() { return 1.0f; }

    /** Whether this centipede variant has wings. */
    default boolean hasWings() { return false; }
    /** Whether the centipede is currently flying. */
    default boolean isFlying() { return false; }
    /** Wing deployment factor 0..1 (0 = folded, 1 = fully deployed). */
    default float getWingsStartedUp() { return 0f; }
    /** Wing flap cycle (accumulated angle for wing animation). */
    default float getWingFlapCycle() { return 0f; }
    /** Previous tick's wing flap cycle for interpolation. */
    default float getLastWingFlapCycle() { return 0f; }
    /** Wings-folded factor 0..1 (1 = fully folded). */
    default float getWingsFolded() { return 1f; }
    /** Previous tick's wings-folded for interpolation. */
    default float getLastWingsFolded() { return 1f; }
    /** Compute wing length at the given segment index. Returns 0 if no wings. */
    default float getWingLength(int segIndex) { return 0f; }

    // --- AI / Movement (used by shared Goal classes) ---
    void setMoveTarget(Vec3d target);
    void stopMoving();
    boolean isMoving();
    void requestPathTo(Vec3d target);
    void requestPath(BlockPos goal);
    void followCurrentPath();
    boolean needsPathRecalc(Vec3d goalPos);
    boolean areSegmentsSpawned();

    // --- Hunt / Combat ---
    void setHuntTarget(LivingEntity target);
    LivingEntity getHuntTarget();
    void updateDirectionChange();

    /** Whether the entity is in forced-pathing debug mode (ignores AI goals). */
    default boolean isForcedPathing() { return false; }
    /** Set forced-pathing target. Null clears it. */
    default void setForcedPathTarget(BlockPos target) { }
    CentipedeHeadEntity getFrontHead();
    CentipedeHeadEntity getRearHead();

    // --- Lifecycle (satisfied by Entity/LivingEntity superclass) ---
    boolean isRemoved();
    boolean isDead();
    boolean damage(DamageSource source, float amount);
    int getId();
}
