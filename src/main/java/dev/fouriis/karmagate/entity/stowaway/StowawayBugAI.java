package dev.fouriis.karmagate.entity.stowaway;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * AI brain for the Stowaway Bug.
 * Manages behavior states and decision-making for attacking, hiding, and interacting with prey.
 */
public class StowawayBugAI {
    public enum Behavior {
        IDLE,
        ATTACKING,
        HIDDEN,
        SLEEPING,
        ESCAPE_RAIN,
        DIGESTING
    }
    
    private StowawayBugEntity bug;
    private Behavior behavior;
    private Behavior lastBehavior;
    private float currentUtility;
    private boolean activeThisCycle;
    
    // Behavior timers
    private int behaveCounter;
    
    // Detection/hunting
    private Entity focusCreature;  // Currently focused prey
    private int detectionRange = 30;  // blocks
    private int attackCooldown;
    private int attachCooldown = 0;  // Cooldown for being attached/hit
    
    public StowawayBugAI(StowawayBugEntity bug) {
        this.bug = bug;
        this.behavior = Behavior.HIDDEN;
        this.lastBehavior = Behavior.HIDDEN;
        this.currentUtility = 0;
        this.activeThisCycle = true;
        this.behaveCounter = 0;
        this.attackCooldown = 0;
    }
    
    /**
     * Update AI behavior each tick (server-side only).
     */
    public void update() {
        behaveCounter++;
        attackCooldown--;
        
        // Decide on behavior based on utilities and current state
        decideBehavior();
    }
    
    private void decideBehavior() {
        Behavior newBehavior = Behavior.IDLE;
        
        // Check for threats (would check for nearby predators)
        // For now, simplified - just check if something attacked recently
        if (attachCooldown > 0) {
            newBehavior = Behavior.HIDDEN;
        }
        // Check for nearby prey
        else if (canFindPreyNearby()) {
            newBehavior = Behavior.ATTACKING;
            if (bug.getAI().attackCooldown <= 0) {
                fireAtRandomHead();
            }
        }
        // Default idle when nothing interesting
        else {
            newBehavior = Behavior.IDLE;
        }
        
        if (newBehavior != behavior) {
            behavior = newBehavior;
            behaveCounter = 0;
        }
    }
    
    private boolean canFindPreyNearby() {
        // Simplified prey detection - in a full implementation this would scan nearby entities
        // For now, just return false as a placeholder
        return false;
    }
    
    private void fireAtRandomHead() {
        if (bug.getGrabbingTentacles() != null && bug.getGrabbingTentacles().length > 0) {
            int randomHeadIndex = (int) (Math.random() * bug.getGrabbingTentacles().length);
            GrabbingTentacle head = bug.getGrabbingTentacles()[randomHeadIndex];
            
            // Fire toward a random direction in front of the bug
            double angle = Math.random() * Math.PI * 2;
            Vec3d fireDir = new Vec3d(
                Math.cos(angle),
                Math.random() - 0.3f,
                Math.sin(angle)
            ).normalize().multiply(15);
            
            head.fireToward(bug.getPos().add(fireDir));
            attackCooldown = 30;  // Cooldown between fires
        }
    }
    
    public void onDamaged() {
        if (behavior != Behavior.HIDDEN) {
            behavior = Behavior.HIDDEN;
            behaveCounter = 0;
        }
    }
    
    public boolean shouldMouthBeOpen() {
        return behavior == Behavior.ATTACKING || behavior == Behavior.DIGESTING;
    }
    
    // ============ Getters ============
    
    public Behavior getBehavior() {
        return behavior;
    }
    
    public boolean isActive() {
        return activeThisCycle;
    }
    
    public Entity getFocusCreature() {
        return focusCreature;
    }
}
