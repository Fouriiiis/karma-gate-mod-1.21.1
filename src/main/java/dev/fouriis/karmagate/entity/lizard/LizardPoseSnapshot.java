package dev.fouriis.karmagate.entity.lizard;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public record LizardPoseSnapshot(
        Vec3d head,
        Vec3d[] body,
        Vec3d[] tail,
        LegPose[] legs,
        float walkCycle
) {
    public static final LegPose ZERO_LEG = new LegPose(Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO);
    public static final LizardPoseSnapshot EMPTY = new LizardPoseSnapshot(Vec3d.ZERO, new Vec3d[0], new Vec3d[0], new LegPose[0], 0.0f);

    public record LegPose(Vec3d attach, Vec3d knee, Vec3d foot) {
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.put("Attach", vecToNbt(attach));
            nbt.put("Knee", vecToNbt(knee));
            nbt.put("Foot", vecToNbt(foot));
            return nbt;
        }

        public static LegPose fromNbt(NbtCompound nbt) {
            return new LegPose(
                    vecFromNbt(nbt.getCompound("Attach")),
                    vecFromNbt(nbt.getCompound("Knee")),
                    vecFromNbt(nbt.getCompound("Foot"))
            );
        }
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.put("Head", vecToNbt(head));
        nbt.put("Body", vecArrayToNbt(body));
        nbt.put("Tail", vecArrayToNbt(tail));
        NbtList legsList = new NbtList();
        for (LegPose leg : legs) {
            legsList.add(leg.toNbt());
        }
        nbt.put("Legs", legsList);
        nbt.putFloat("WalkCycle", walkCycle);
        return nbt;
    }

    public static LizardPoseSnapshot fromNbt(NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return EMPTY;
        }
        Vec3d head = vecFromNbt(nbt.getCompound("Head"));
        Vec3d[] body = vecArrayFromNbt(nbt.getList("Body", NbtElement.COMPOUND_TYPE));
        Vec3d[] tail = vecArrayFromNbt(nbt.getList("Tail", NbtElement.COMPOUND_TYPE));
        NbtList legsList = nbt.getList("Legs", NbtElement.COMPOUND_TYPE);
        LegPose[] legs = new LegPose[legsList.size()];
        for (int i = 0; i < legs.length; i++) {
            legs[i] = LegPose.fromNbt(legsList.getCompound(i));
        }
        return new LizardPoseSnapshot(head, body, tail, legs, nbt.getFloat("WalkCycle"));
    }

    public static LizardPoseSnapshot interpolate(LizardPoseSnapshot from, LizardPoseSnapshot to, float tickDelta) {
        if (from == null || from == EMPTY) {
            return to == null ? EMPTY : to;
        }
        if (to == null || to == EMPTY) {
            return from;
        }
        if (from.body.length != to.body.length || from.tail.length != to.tail.length || from.legs.length != to.legs.length) {
            return to;
        }

        Vec3d[] body = new Vec3d[to.body.length];
        for (int i = 0; i < body.length; i++) {
            body[i] = lerp(from.body[i], to.body[i], tickDelta);
        }
        Vec3d[] tail = new Vec3d[to.tail.length];
        for (int i = 0; i < tail.length; i++) {
            tail[i] = lerp(from.tail[i], to.tail[i], tickDelta);
        }
        LegPose[] legs = new LegPose[to.legs.length];
        for (int i = 0; i < legs.length; i++) {
            legs[i] = new LegPose(
                    lerp(from.legs[i].attach(), to.legs[i].attach(), tickDelta),
                    lerp(from.legs[i].knee(), to.legs[i].knee(), tickDelta),
                    lerp(from.legs[i].foot(), to.legs[i].foot(), tickDelta)
            );
        }
        return new LizardPoseSnapshot(
                lerp(from.head, to.head, tickDelta),
                body,
                tail,
                legs,
                MathHelper.lerp(tickDelta, from.walkCycle, to.walkCycle)
        );
    }

    private static NbtList vecArrayToNbt(Vec3d[] values) {
        NbtList list = new NbtList();
        for (Vec3d value : values) {
            list.add(vecToNbt(value));
        }
        return list;
    }

    private static Vec3d[] vecArrayFromNbt(NbtList list) {
        Vec3d[] values = new Vec3d[list.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = vecFromNbt(list.getCompound(i));
        }
        return values;
    }

    private static NbtCompound vecToNbt(Vec3d vec) {
        NbtCompound nbt = new NbtCompound();
        nbt.putDouble("X", vec.x);
        nbt.putDouble("Y", vec.y);
        nbt.putDouble("Z", vec.z);
        return nbt;
    }

    private static Vec3d vecFromNbt(NbtCompound nbt) {
        return new Vec3d(nbt.getDouble("X"), nbt.getDouble("Y"), nbt.getDouble("Z"));
    }

    private static Vec3d lerp(Vec3d from, Vec3d to, float t) {
        return new Vec3d(
                MathHelper.lerp(t, from.x, to.x),
                MathHelper.lerp(t, from.y, to.y),
                MathHelper.lerp(t, from.z, to.z)
        );
    }
}
