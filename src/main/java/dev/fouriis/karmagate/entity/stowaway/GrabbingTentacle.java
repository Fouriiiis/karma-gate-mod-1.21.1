package dev.fouriis.karmagate.entity.stowaway;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.List;

/**
 * A grabbing tentacle (head): active appendage that fires, hooks, and retracts.
 * These are weapon-like and AI-driven through impulses and retraction factors.
 * Client-side rope physics only.
 * 
 * Matches C# StowawayBug heads behavior:
 * - headFired[i] tracks if the head is currently attacking
 * - heads[i].retractFac controls extension (0 = out, 1 = retracted)
 * - heads[i].idealLength = headLength * (1 - retractFac)
 */
public class GrabbingTentacle {
    private TentacleSegment[] segments;
    private float segmentLength;
    private Vec3d basePos;
    private float headLength;  // Max length in blocks
    private Entity owner;  // The entity that owns this tentacle (for collision exclusion)
    
    // State
    public float retractFac;  // 0 = extended, 1 = fully retracted (C# style)
    public boolean fired;
    private float cooldown;
    private float headCooldown;  // C#: headCooldown[k] for grabbing window
    
    // Grasp simulation
    public Vec3d graspTarget;  // Where this head is pulling toward (if it has prey)
    private float graspForce;  // Pulling strength
    private LivingEntity grabbedEntity;  // Currently grabbed entity
    
    // Physics - C# constants scaled for Minecraft
    // C#: gravity = 0.9, applied as vel.y -= gravity * (1 - retractFac)
    private static final float GRAVITY = 0.04f;  // ~0.9 / 20 for MC scale
    private static final float FIRE_RETRACT_SPEED = 0.0055f;  // C#: 0.0055 (no grasp)
    private static final float GRASP_RETRACT_SPEED = 0.0025f;  // C#: 0.0025 (with grasp)
    private static final float GRAB_RADIUS = 0.8f;  // C#: radius + 16 pixels = ~0.8 blocks
    
    public GrabbingTentacle(Vec3d basePos, int segmentCount, float segmentLength) {
        this(basePos, segmentCount, segmentLength, null);
    }
    
    public GrabbingTentacle(Vec3d basePos, int segmentCount, float segmentLength, Entity owner) {
        this.segments = new TentacleSegment[segmentCount];
        this.basePos = basePos;
        this.segmentLength = segmentLength;
        this.headLength = segmentCount * segmentLength;  // Total extended length
        this.retractFac = 1.0f;  // Start retracted
        this.fired = false;
        this.cooldown = 0;
        this.headCooldown = 0;
        this.graspForce = 0;
        this.grabbedEntity = null;
        this.owner = owner;
        
        // Initialize rope coiled near base
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new TentacleSegment(basePos);
            segments[i].vel = Vec3d.ZERO;
        }
    }
    
    public void setOwner(Entity owner) {
        this.owner = owner;
    }
    
    public TentacleSegment[] getSegments() {
        return segments;
    }
    
    /**
     * Fire this head toward a target position.
     * C#: headFired[j] = true; heads[j].retractFac = 0f;
     * C#: velocity = Custom.DirVec(...) * 45f
     * 45 pixels/frame at 40 FPS = ~2.25 blocks/tick at 20 TPS (accounting for frame vs tick)
     */
    public void fireToward(Vec3d targetPos) {
        if (!fired && cooldown <= 0) {
            fired = true;
            retractFac = 0;  // Start extended
            headCooldown = 30;  // C#: headCooldown[k] > 0 allows grabbing
            
            // First, uncoil all segments to the base position so they can launch
            for (int i = 1; i < segments.length; i++) {
                segments[i].pos = basePos;
                segments[i].vel = Vec3d.ZERO;
            }
            
            // Give initial impulse toward target
            // C#: vel = Custom.DirVec(...) * 45f
            // Stronger velocity to reach horizontal/upward targets
            Vec3d direction = targetPos.subtract(basePos).normalize();
            float launchSpeed = 2.0f;  // Blocks per tick - fast projectile
            Vec3d launchVelocity = direction.multiply(launchSpeed);
            
            // Apply launch velocity to tip segments (the projectile part)
            // C#: The tentacle chunk (tip) gets the launch velocity
            TentacleSegment tip = segments[segments.length - 1];
            tip.pos = basePos.add(direction.multiply(0.5));  // Start tip slightly ahead
            tip.vel = launchVelocity;
            
            // Spread impulse along segments - more velocity toward tip
            for (int i = 1; i < segments.length - 1; i++) {
                float t = (float) i / (float)(segments.length - 1);
                segments[i].vel = launchVelocity.multiply(t);
            }
        }
    }
    
    /**
     * Update rope physics and retraction behavior.
     * This matches C# StowawayBug.Update() for heads.
     * 
     * @param newBasePos The attachment point (entity eye position)
     * @param bodyDir Direction the body is facing (for retracted positioning)
     * @param world The world for collision checks
     */
    public void update(Vec3d newBasePos, Vec3d bodyDir, World world) {
        this.basePos = newBasePos;
        
        // Pin first segment to base
        segments[0].pos = basePos;
        segments[0].vel = Vec3d.ZERO;
        
        // Always update cooldowns (even when retracted)
        headCooldown = Math.max(0, headCooldown - 1);
        cooldown = Math.max(0, cooldown - 1);
        
        // === When retracted and not fired, coil all segments at base ===
        // C#: When retractFac is high and not attacking, heads stay coiled at body
        if (!fired && retractFac >= 0.9f) {
            // Keep all segments coiled near base, along body direction
            for (int i = 1; i < segments.length; i++) {
                // Coil segments in a spiral/line along body direction from base
                float coilOffset = i * 0.05f;  // Small offset per segment
                segments[i].pos = basePos.add(bodyDir.multiply(coilOffset));
                segments[i].vel = Vec3d.ZERO;
                segments[i].lastPos = segments[i].pos;
            }
            return;  // Skip physics when fully retracted and idle
        }
        
        // Calculate ideal length based on retraction
        // C#: heads[k].idealLength = headLength * (1f - heads[k].retractFac)
        float idealLength = headLength * (1.0f - retractFac);
        float idealSegmentLength = idealLength / segments.length;
        
        boolean isGrabbing = grabbedEntity != null && grabbedEntity.isAlive();
        
        // === Apply physics to each segment ===
        // C#: for (int l = 0; l < heads[k].tChunks.Length; l++) { ... }
        for (int i = 1; i < segments.length; i++) {
            TentacleSegment seg = segments[i];
            
            // Store last position
            seg.lastPos = seg.pos;
            
            // Apply velocity
            seg.pos = seg.pos.add(seg.vel);
            
            // Apply gravity - C# style: more gravity when NOT retracted
            // C#: tentacleChunk.vel.y = tentacleChunk.vel.y - gravity * (1f - heads[k].retractFac)
            float gravityStrength = GRAVITY * (1.0f - retractFac);
            seg.vel = seg.vel.add(0, -gravityStrength, 0);
            
            // Apply damping - C# style: more damping when retracted
            // C#: vel *= Lerp(1f, 0.95f, retractFac)
            float dampingFactor = MathHelper.lerp(retractFac, 1.0f, 0.95f);
            seg.vel = seg.vel.multiply(dampingFactor);
            
            // Terrain collision
            seg.terrainCollisionDrape(world);
        }
        
        // === When nearly retracted while fired, coil segments along body direction ===
        // C#: if (heads[k].retractFac >= 0.98f) { ... coil positions ... }
        if (fired && retractFac >= 0.9f && !isGrabbing) {
            for (int i = 1; i < segments.length; i++) {
                // Pull segments toward base as it retracts
                float pullStrength = (retractFac - 0.9f) * 10.0f;  // 0 to 1 as retractFac goes 0.9 to 1.0
                Vec3d toBase = basePos.subtract(segments[i].pos);
                segments[i].pos = segments[i].pos.add(toBase.multiply(pullStrength * 0.3));
            }
        }
        
        // === Distance constraints ===
        // === Distance constraints ===
        // C#: The Tentacle.Update() handles distance constraints internally
        // Keep simple constraint solving - rope should sag naturally
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < segments.length; i++) {
                TentacleSegment prev = segments[i - 1];
                TentacleSegment curr = segments[i];
                
                Vec3d delta = curr.pos.subtract(prev.pos);
                double dist = delta.length();
                
                float targetLen = Math.max(0.01f, idealSegmentLength);
                
                if (dist > 0.001) {
                    Vec3d normalized = delta.normalize();
                    double correction = (targetLen - dist) * 0.5;
                    
                    // Apply correction
                    curr.pos = curr.pos.add(normalized.multiply(correction));
                    curr.vel = curr.vel.add(normalized.multiply(correction * 0.5));
                    
                    // Don't move pinned first segment
                    if (i > 1) {
                        prev.pos = prev.pos.subtract(normalized.multiply(correction));
                        prev.vel = prev.vel.subtract(normalized.multiply(correction * 0.5));
                    }
                }
            }
        }
        
        // === Pull toward grasp target if grabbing ===
        // C#: The tip follows the grabbed entity, other segments follow naturally via rope physics
        if (grabbedEntity != null && grabbedEntity.isAlive() && retractFac < 0.95f) {
            // Lock tip position directly to grabbed entity's position
            graspTarget = grabbedEntity.getPos().add(0, grabbedEntity.getHeight() * 0.5, 0);
            TentacleSegment tip = segments[segments.length - 1];
            
            // Directly set tip position to entity
            tip.pos = graspTarget;
            tip.vel = Vec3d.ZERO;  // Don't apply physics to tip while grabbed
            
            // Let the rope sag naturally between base and tip - no straight-line forcing
            // The distance constraints will keep it connected
            
        } else if (graspTarget != null && graspForce > 0 && retractFac < 0.95f) {
            TentacleSegment tip = segments[segments.length - 1];
            Vec3d toTarget = graspTarget.subtract(tip.pos);
            if (toTarget.length() > 0.1) {
                tip.pos = tip.pos.add(toTarget.normalize().multiply(0.1));
            }
        }
        
        // Server handles grab detection and syncs via setGrabbedEntity()
        // No client-side checkForGrab needed
        
        // === Second terrain collision pass ===
        // Constraints and grasp pulling can push segments into blocks
        // Use simpler pushOutOfBlock since we're not moving fast here
        for (int i = 1; i < segments.length; i++) {
            segments[i].pushOutOfBlock(world);
        }
        
        // === Update retraction ===
        if (fired) {
            // C#: heads[i].retractFac += (hasGrasp ? 0.0025f : 0.0055f)
            float retractSpeed = (grabbedEntity != null) ? GRASP_RETRACT_SPEED : FIRE_RETRACT_SPEED;
            retractFac = Math.min(1.0f, retractFac + retractSpeed);
            
            // C#: if (headFired[i] && heads[i].retractFac == 1f) { headFired[i] = false; ... }
            if (retractFac >= 1.0f) {
                fired = false;
                cooldown = 40;  // Cooldown before next fire (C#: huntDelay)
                graspTarget = null;
                graspForce = 0;
                grabbedEntity = null;
            }
        }
    }
    
    /**
     * Check for entities near the tip that can be grabbed.
     * C#: Custom.DistLess(creature.bodyChunk.pos, heads[k].Tip.pos, rad + 16f)
     */
    private void checkForGrab(World world) {
        Vec3d tipPos = getTipPos();
        Box searchBox = new Box(
            tipPos.x - GRAB_RADIUS, tipPos.y - GRAB_RADIUS, tipPos.z - GRAB_RADIUS,
            tipPos.x + GRAB_RADIUS, tipPos.y + GRAB_RADIUS, tipPos.z + GRAB_RADIUS
        );
        
        List<LivingEntity> nearbyEntities = world.getEntitiesByClass(
            LivingEntity.class,
            searchBox,
            entity -> entity != owner && entity.isAlive() && !entity.isSpectator()
        );
        
        // Grab the closest entity within range
        double closestDist = GRAB_RADIUS;
        LivingEntity closest = null;
        
        for (LivingEntity entity : nearbyEntities) {
            double dist = entity.getPos().distanceTo(tipPos);
            if (dist < closestDist) {
                closestDist = dist;
                closest = entity;
            }
        }
        
        if (closest != null) {
            grabbedEntity = closest;
            graspTarget = closest.getEyePos();
            graspForce = 1.0f;
        }
    }
    
    /**
     * Simplified update without body direction (for backward compatibility).
     */
    public void update(Vec3d newBasePos) {
        update(newBasePos, new Vec3d(0, -1, 0), null);
    }
    
    /**
     * Get the tip position of this tentacle head.
     * C#: heads[k].Tip.pos
     */
    public Vec3d getTipPos() {
        return segments[segments.length - 1].pos;
    }
    
    public float getRetractFactor() {
        return retractFac;
    }
    
    public boolean isFired() {
        return fired;
    }
    
    public boolean canGrab() {
        return headCooldown > 0 && retractFac < 0.3f;
    }
    
    public boolean hasGrab() {
        return grabbedEntity != null && grabbedEntity.isAlive();
    }
    
    public LivingEntity getGrabbedEntity() {
        return grabbedEntity;
    }
    
    public void setGrabbedEntity(LivingEntity entity) {
        this.grabbedEntity = entity;
        if (entity != null) {
            this.graspTarget = entity.getEyePos();
            this.graspForce = 1.0f;
        }
    }
    
    public void setGraspTarget(Vec3d target, float force) {
        this.graspTarget = target;
        this.graspForce = force;
    }
    
    public float getSegmentThickness(float t) {
        // Tapering: thicker near base, thinner near tip
        // C#: Mathf.Lerp(3f, 5f, (float)k / (float)(heads[j].tChunks.Length - 1))
        float base = 0.15f;   // 3 pixels
        float tip = 0.25f;    // 5 pixels
        return MathHelper.lerp(t, base, tip) * MathHelper.lerp(retractFac, 1.0f, 0.6f);
    }
    
    public float getSegmentLength() {
        return segmentLength;
    }
}
