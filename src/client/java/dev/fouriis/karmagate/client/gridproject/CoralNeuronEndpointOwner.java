package dev.fouriis.karmagate.client.gridproject;

import dev.fouriis.karmagate.entity.coralbrain.CoralNeuronEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Represents a non-anchored endpoint of a CoralNeuronEntity that can own a projected circle.
 * When a CoralNeuron has a floating (non-pinned) end, that end projects a circle onto the shader wall.
 * 
 * This is analogous to how NeuronSwarmers project circles, but for the dangling ends of coral neurons.
 */
public class CoralNeuronEndpointOwner implements IProjectedCircleOwner {
    
    /** Which endpoint this represents: true = endpoint A, false = endpoint B */
    private final boolean isEndpointA;
    
    /** The entity ID of the owning CoralNeuronEntity */
    private final int entityId;
    
    /** Current world position of this endpoint */
    private Vec3d position = Vec3d.ZERO;
    
    /** Previous tick's position for interpolation */
    private Vec3d lastPosition = Vec3d.ZERO;
    
    /** Whether this owner should be removed */
    private boolean markedForRemoval = false;
    
    /**
     * Creates a new endpoint owner.
     * 
     * @param entityId The entity ID of the CoralNeuronEntity
     * @param isEndpointA True for endpoint A, false for endpoint B
     */
    public CoralNeuronEndpointOwner(int entityId, boolean isEndpointA) {
        this.entityId = entityId;
        this.isEndpointA = isEndpointA;
    }
    
    /**
     * Updates the position from the entity's current state.
     * Called each tick to sync with the entity's simulation.
     * 
     * @param entity The CoralNeuronEntity to sync from
     */
    public void updateFromEntity(CoralNeuronEntity entity) {
        if (entity == null || entity.isRemoved()) {
            markedForRemoval = true;
            return;
        }
        
        // Store last position for interpolation
        lastPosition = position;
        
        // Get the endpoint position from the entity
        // The entity stores positions in local space, so we need to add entity position
        Vec3d entityPos = entity.getPos();
        Vec3d[] points = entity.getPointsLocalCopy();
        
        if (points == null || points.length == 0) {
            markedForRemoval = true;
            return;
        }
        
        // Get the appropriate endpoint (first or last point)
        int index = isEndpointA ? 0 : points.length - 1;
        Vec3d localPos = points[index];
        
        // Convert to world position
        if (localPos != null) {
            position = entityPos.add(localPos);
        } else {
            position = entityPos;
        }
    }
    
    /**
     * Checks if this endpoint is still non-anchored (floating).
     * If it becomes anchored, the circle should be removed.
     * 
     * @param entity The entity to check
     * @return True if still floating (non-anchored)
     */
    public boolean isStillFloating(CoralNeuronEntity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        
        // Check the pinned state - we only project circles for NON-pinned endpoints
        if (isEndpointA) {
            return !entity.isAnchorAPinned();
        } else {
            return !entity.isAnchorBPinned();
        }
    }
    
    // ========== Getters ==========
    
    public int getEntityId() {
        return entityId;
    }
    
    public boolean isEndpointA() {
        return isEndpointA;
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
    
    /**
     * Creates a unique key for this endpoint (used for tracking).
     */
    public String getKey() {
        return entityId + "_" + (isEndpointA ? "A" : "B");
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CoralNeuronEndpointOwner other)) return false;
        return entityId == other.entityId && isEndpointA == other.isEndpointA;
    }
    
    @Override
    public int hashCode() {
        return 31 * entityId + (isEndpointA ? 1 : 0);
    }
}
