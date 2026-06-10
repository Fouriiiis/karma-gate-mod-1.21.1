package dev.fouriis.karmagate.client.hose;

import dev.fouriis.karmagate.hose.FuelHoseData;
import dev.fouriis.karmagate.network.FuelHoseSyncPayload;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FuelHoseClientState {
    private static final List<FuelHoseData> HOSes = new ArrayList<>();

    private FuelHoseClientState() {
    }

    public static synchronized void applySync(FuelHoseSyncPayload payload) {
        HOSes.clear();
        for (FuelHoseSyncPayload.HoseEntry entry : payload.hoses()) {
            RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(entry.dimensionId()));
            List<net.minecraft.util.math.Vec3d> points = new ArrayList<>();
            for (FuelHoseSyncPayload.PointEntry point : entry.points()) {
                points.add(new net.minecraft.util.math.Vec3d(point.x(), point.y(), point.z()));
            }
            HOSes.add(FuelHoseData.create(
                    entry.id(),
                    dimension,
                    entry.startPos(),
                    entry.endPos(),
                    entry.segmentCount(),
                    entry.simulationTicks(),
                    entry.gravity(),
                    points
            ));
        }
    }

    public static synchronized List<FuelHoseData> getHoses() {
        return Collections.unmodifiableList(new ArrayList<>(HOSes));
    }

    public static synchronized void clear() {
        HOSes.clear();
    }
}