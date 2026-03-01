package dev.fouriis.karmagate.entity.stowaway;

import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * A feeler tentacle: passive, swaying rope that drapes along terrain.
 * Uses verlet integration with gravity, damping, and terrain collision.
 * Client-side only for rope physics.
 * 
 * This matches the C# StowawayBug tentacle physics:
 * - tentacles[n][num8, 0] = pos
 * - tentacles[n][num8, 1] = lastPos
 * - tentacles[n][num8, 2] = velocity
 */
public class FeelerTentacle {
    private TentacleSegment[] segments;
    private float segmentLength;
    private float targetWidth;
    private float width;
    private float initialAngle;
    
    // C# constants adjusted for Minecraft (20 TPS vs 40 FPS, 1 block = 20 pixels)
    // In C#: segment distance is ~10 pixels, gravity is 0.9 * 0.6
    // C# smoothness push between segments 2 apart is 0.2 - this creates the curve!
    private static final float GRAVITY = 0.027f;  // room.gravity * 0.6 scaled for MC
    private static final float AIR_DRAG = 0.99f;  // Slightly more drag than C#'s 0.999 for MC's lower tick rate
    private static final float WATER_DRAG = 0.5f;  // Approximate from C# LerpMap
    private static final float SMOOTHNESS_PUSH = 0.1f;  // C# uses 0.2, halved for 20 TPS
    
    public FeelerTentacle(Vec3d basePos, int segmentCount, float segmentLength, float angle) {
        this.segments = new TentacleSegment[segmentCount];
        this.segmentLength = segmentLength;
        this.targetWidth = 1.0f;
        this.width = this.targetWidth;
        this.initialAngle = angle;
        
        // Initialize chain with natural log-curve draping appearance
        // The feelers should spread outward radially while draping down
        for (int i = 0; i < segmentCount; i++) {
            float t = (float) i / (float) segmentCount;
            
            // Logarithmic curve: more horizontal spread near base, more vertical near tip
            // This creates the natural "draping" look
            float spreadFactor = (float) Math.log(1 + t * 2.5) / 1.25f;  // 0 to ~1
            float droopFactor = t * t;  // Quadratic droop - accelerates toward tip
            
            // Radial spread in the direction of this tentacle's angle
            float radialDist = spreadFactor * segmentLength * 4.0f;  // Total radial extent
            float verticalDist = droopFactor * segmentLength * segmentCount * 0.7f;  // Total vertical droop
            
            Vec3d startPos = basePos.add(
                Math.cos(angle) * radialDist,
                -verticalDist,
                Math.sin(angle) * radialDist
            );
            segments[i] = new TentacleSegment(startPos);
            segments[i].vel = Vec3d.ZERO;
        }
    }
    
    public TentacleSegment[] getSegments() {
        return segments;
    }
    
    /**
     * Update the feeler tentacle with physics simulation.
     * Matches C# StowawayBug.Update() tentacle physics.
     */
    public void update(Vec3d basePos, float sleepScale, float withdrawnScale, World world) {
        // Calculate target segment length based on withdrawal
        // C#: num7 = Mathf.Lerp(10f, 1f, tentaclesWithdrawn)
        // In MC blocks: 10 pixels = 0.5 blocks, 1 pixel = 0.05 blocks
        float targetSegmentLength = MathHelper.lerp(withdrawnScale, segmentLength, segmentLength * 0.1f);
        
        // === Phase 1: Apply velocity and forces ===
        for (int i = 0; i < segments.length; i++) {
            TentacleSegment seg = segments[i];
            float t = (float) i / (float) (segments.length - 1);
            
            if (i == 0) {
                seg.lastPos = basePos;
                seg.pos = basePos;
                seg.vel = Vec3d.ZERO;
                continue;
            }
            
            // Store last position (C#: tentacles[n][num8, 1] = tentacles[n][num8, 0])
            seg.lastPos = seg.pos;
            
            // Apply velocity (C#: tentacles[n][num8, 0] += tentacles[n][num8, 2])
            seg.pos = seg.pos.add(seg.vel);
            
            // Check if underwater (simplified - just check if below certain Y)
            // In full implementation, would check world.isWater(BlockPos)
            boolean submerged = isSubmerged(world, seg.pos);
            
            if (submerged) {
                // C#: underwater behavior - more drag, random movement
                float dragFactor = MathHelper.lerp(
                    (float) Math.min(seg.vel.length(), 10.0) / 10.0f,
                    1.0f, WATER_DRAG
                ) * MathHelper.lerp(t, 1.4f, 0.4f);
                seg.vel = seg.vel.multiply(dragFactor);
                // Add small random movement underwater
                seg.vel = seg.vel.add(
                    (Math.random() - 0.5) * 0.01,
                    (Math.random() - 0.5) * 0.01,
                    (Math.random() - 0.5) * 0.01
                );
            } else {
                // C#: tentacles[n][num8, 2] *= 0.999f
                seg.vel = seg.vel.multiply(AIR_DRAG);
                // C#: tentacles[n][num8, 2].y -= room.gravity * 0.6f
                seg.vel = seg.vel.add(0, -GRAVITY, 0);
            }
            
            // === Terrain collision ===
            // C#: SharedPhysics.HorizontalCollision, VerticalCollision, SlopesVertically
            seg.terrainCollisionDrape(world);
        }
        
        // === Phase 2: Constraint satisfaction ===
        // C# performs distance constraints after physics
        for (int i = 1; i < segments.length; i++) {
            TentacleSegment prev = segments[i - 1];
            TentacleSegment curr = segments[i];
            
            // Distance constraint between adjacent segments
            Vec3d delta = curr.pos.subtract(prev.pos);
            double dist = delta.length();
            
            if (dist > 0.001) {
                Vec3d normalized = delta.normalize();
                double correction = (targetSegmentLength - dist) * 0.5;
                
                // Reduce constraint strength when segment is on floor to prevent sliding
                // On floor = almost no horizontal movement from constraints
                double constraintStrength = curr.onFloor ? 0.05 : (curr.onSurface ? 0.3 : 1.0);
                double prevConstraintStrength = prev.onFloor ? 0.05 : (prev.onSurface ? 0.3 : 1.0);
                
                // Apply correction to both segments
                Vec3d correctionVec = normalized.multiply(correction * constraintStrength);
                // If on floor, only apply vertical correction
                if (curr.onFloor) {
                    correctionVec = new Vec3d(0, correctionVec.y, 0);
                }
                curr.pos = curr.pos.add(correctionVec);
                // Only add to velocity if not on surface (prevents jitter)
                if (!curr.onSurface) {
                    curr.vel = curr.vel.add(normalized.multiply(correction * 0.5));
                }
                
                // Don't move pinned first segment
                if (i > 1) {
                    Vec3d prevCorrectionVec = normalized.multiply(correction * prevConstraintStrength);
                    if (prev.onFloor) {
                        prevCorrectionVec = new Vec3d(0, prevCorrectionVec.y, 0);
                    }
                    prev.pos = prev.pos.subtract(prevCorrectionVec);
                    if (!prev.onSurface) {
                        prev.vel = prev.vel.subtract(normalized.multiply(correction * 0.5));
                    }
                }
            }
            
            // Smoothness constraint between segments 2 apart
            // C#: normalized = (tentacles[n][num9, 0] - tentacles[n][num9 - 2, 0]).normalized;
            //     tentacles[n][num9, 2] += normalized * 0.2f;
            // This creates the natural curve by pushing segments apart!
            if (i > 1) {
                TentacleSegment prevPrev = segments[i - 2];
                Vec3d delta2 = curr.pos.subtract(prevPrev.pos);
                if (delta2.length() > 0.001) {
                    Vec3d normalized2 = delta2.normalize();
                    // Apply smoothness push (reduced when on surface to prevent sliding)
                    float pushStrength = curr.onFloor ? SMOOTHNESS_PUSH * 0.1f : 
                                        (curr.onSurface ? SMOOTHNESS_PUSH * 0.3f : SMOOTHNESS_PUSH);
                    curr.vel = curr.vel.add(normalized2.multiply(pushStrength));
                    if (i > 2) {
                        float prevPushStrength = prevPrev.onFloor ? SMOOTHNESS_PUSH * 0.1f : 
                                                (prevPrev.onSurface ? SMOOTHNESS_PUSH * 0.3f : SMOOTHNESS_PUSH);
                        prevPrev.vel = prevPrev.vel.subtract(normalized2.multiply(prevPushStrength));
                    }
                }
            }
        }
        
        // === Second collision pass ===
        // Constraints can push segments into blocks
        for (int i = 1; i < segments.length; i++) {
            segments[i].pushOutOfBlock(world);
        }

        for (int i = 1; i < segments.length; i++) {
            segments[i].lastPos = segments[i].pos;
        }

        // Update visual width based on withdrawal state
        float targetW = MathHelper.lerp(withdrawnScale, 1.0f, 0.4f);
        this.width = MathHelper.lerp(0.1f, width, targetW);
    }
    
    /**
     * Check if a position is underwater.
     */
    private boolean isSubmerged(World world, Vec3d pos) {
        if (world == null) return false;
        try {
            return world.isWater(net.minecraft.util.math.BlockPos.ofFloored(pos));
        } catch (Exception e) {
            return false;
        }
    }
    
    public float getWidth() {
        return width;
    }
    
    public float getSegmentLength() {
        return segmentLength;
    }
    
    public float getInitialAngle() {
        return initialAngle;
    }
}
