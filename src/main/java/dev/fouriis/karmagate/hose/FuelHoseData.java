package dev.fouriis.karmagate.hose;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public record FuelHoseData(
        String id,
        RegistryKey<World> dimension,
        BlockPos startPos,
        BlockPos endPos,
        int segmentCount,
        int simulationTicks,
        float gravity,
        List<Vec3d> points
) {
    public static FuelHoseData create(String id,
                                      RegistryKey<World> dimension,
                                      BlockPos startPos,
                                      BlockPos endPos,
                                      int segmentCount,
                                      int simulationTicks,
                                      float gravity,
                                      List<Vec3d> points) {
        return new FuelHoseData(id, dimension, startPos, endPos, segmentCount, simulationTicks, gravity, List.copyOf(points));
    }

    public NbtCompound toNbt(RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", id);
        nbt.putString("dimension", dimension.getValue().toString());
        nbt.putInt("startX", startPos.getX());
        nbt.putInt("startY", startPos.getY());
        nbt.putInt("startZ", startPos.getZ());
        nbt.putInt("endX", endPos.getX());
        nbt.putInt("endY", endPos.getY());
        nbt.putInt("endZ", endPos.getZ());
        nbt.putInt("segmentCount", segmentCount);
        nbt.putInt("simulationTicks", simulationTicks);
        nbt.putFloat("gravity", gravity);

        NbtList pointList = new NbtList();
        for (Vec3d point : points) {
            NbtCompound pointTag = new NbtCompound();
            pointTag.putDouble("x", point.x);
            pointTag.putDouble("y", point.y);
            pointTag.putDouble("z", point.z);
            pointList.add(pointTag);
        }
        nbt.put("points", pointList);
        return nbt;
    }

    public static FuelHoseData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        String id = nbt.getString("id");
        RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(nbt.getString("dimension")));
        BlockPos startPos = new BlockPos(nbt.getInt("startX"), nbt.getInt("startY"), nbt.getInt("startZ"));
        BlockPos endPos = new BlockPos(nbt.getInt("endX"), nbt.getInt("endY"), nbt.getInt("endZ"));
        int segmentCount = nbt.getInt("segmentCount");
        int simulationTicks = nbt.getInt("simulationTicks");
        float gravity = nbt.getFloat("gravity");

        List<Vec3d> points = new ArrayList<>();
        NbtList pointList = nbt.getList("points", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < pointList.size(); i++) {
            NbtCompound pointTag = pointList.getCompound(i);
            points.add(new Vec3d(pointTag.getDouble("x"), pointTag.getDouble("y"), pointTag.getDouble("z")));
        }
        return create(id, dimension, startPos, endPos, segmentCount, simulationTicks, gravity, points);
    }
}