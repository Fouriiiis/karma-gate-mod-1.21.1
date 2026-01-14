package dev.fouriis.karmagate.client.gridproject;

import net.minecraft.util.math.Vec3d;

/**
 * Interface for entities/objects that can own a projected circle.
 * Both NeuronSwarmer and CoralNeuronEntity endpoints implement this
 * to project circles onto the shader wall.
 */
public interface IProjectedCircleOwner {
    /**
     * Gets the current world position of this circle owner.
     */
    Vec3d getCirclePosition();
    
    /**
     * Gets the previous tick's world position for interpolation.
     */
    Vec3d getLastCirclePosition();
    
    /**
     * Returns true if this owner should be removed (entity despawned, etc.)
     */
    boolean isMarkedForRemoval();
    
    /**
     * Marks this owner for removal.
     */
    void markForRemoval();
}
