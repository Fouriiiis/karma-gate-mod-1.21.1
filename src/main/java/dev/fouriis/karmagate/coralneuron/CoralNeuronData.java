package dev.fouriis.karmagate.coralneuron;

import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Server-side data container for a named CoralNeuron entity.
 * Stores the entity's UUID and configuration for persistence and lookup.
 */
public record CoralNeuronData(
        String name,
        UUID entityUuid,
        Vec3d anchorA,
        Vec3d anchorB,
        boolean anchoredA,
        boolean anchoredB
) {
    /**
     * Creates CoralNeuron data from coordinate values.
     */
    public static CoralNeuronData of(
            String name,
            UUID entityUuid,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            boolean anchoredA,
            boolean anchoredB
    ) {
        return new CoralNeuronData(
                name,
                entityUuid,
                new Vec3d(x1, y1, z1),
                new Vec3d(x2, y2, z2),
                anchoredA,
                anchoredB
        );
    }
}
