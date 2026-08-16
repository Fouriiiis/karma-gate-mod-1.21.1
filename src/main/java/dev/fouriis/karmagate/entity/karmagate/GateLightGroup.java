package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages gate lights for one side (SIDE1 / SIDE2).
 *
 * Per-side we now support up to 4 lamps: two columns along the gate axis (NEAR/FAR),
 * each column can have a bottom and a top. We sort each column bottom->top.
 *
 * Mirroring rule:
 *  - "bottom pair" lights NEAR-bottom + FAR-top
 *  - "top pair"    lights NEAR-top    + FAR-bottom
 */
public class GateLightGroup {
    /** Save connected gate lights to NBT. */
    public void writeNbt(net.minecraft.nbt.NbtCompound nbt, String key) {
        net.minecraft.nbt.NbtList nearList = new net.minecraft.nbt.NbtList();
        for (LightRef ref : nearCol) {
            net.minecraft.nbt.NbtCompound tag = new net.minecraft.nbt.NbtCompound();
            tag.putInt("x", ref.pos.getX());
            tag.putInt("y", ref.pos.getY());
            tag.putInt("z", ref.pos.getZ());
            nearList.add(tag);
        }
        nbt.put(key + "_near", nearList);

        net.minecraft.nbt.NbtList farList = new net.minecraft.nbt.NbtList();
        for (LightRef ref : farCol) {
            net.minecraft.nbt.NbtCompound tag = new net.minecraft.nbt.NbtCompound();
            tag.putInt("x", ref.pos.getX());
            tag.putInt("y", ref.pos.getY());
            tag.putInt("z", ref.pos.getZ());
            farList.add(tag);
        }
        nbt.put(key + "_far", farList);
    }

    /** Load connected gate lights from NBT. */
    public void readNbt(net.minecraft.nbt.NbtCompound nbt, String key) {
        nearCol.clear();
        farCol.clear();
        if (nbt.contains(key + "_near")) {
            net.minecraft.nbt.NbtList nearList = nbt.getList(key + "_near", 10);
            for (int i = 0; i < nearList.size(); i++) {
                net.minecraft.nbt.NbtCompound tag = nearList.getCompound(i);
                BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                nearCol.add(new LightRef(pos));
            }
        }
        if (nbt.contains(key + "_far")) {
            net.minecraft.nbt.NbtList farList = nbt.getList(key + "_far", 10);
            for (int i = 0; i < farList.size(); i++) {
                net.minecraft.nbt.NbtCompound tag = farList.getCompound(i);
                BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                farCol.add(new LightRef(pos));
            }
        }
    }

    public enum Side { SIDE1, SIDE2 } // SIDE1 = normal-axis NEG; SIDE2 = normal-axis POS
    private static final int BLINK_PERIOD_TICKS = 15;

    /** Immutable reference to a light with ordering info. */
    public static final class LightRef {
        public final BlockPos pos;
        public final double y;
        public LightRef(BlockPos pos) {
            this.pos = pos.toImmutable();
            this.y = pos.getY();
        }
    }

    private final Side side;

    // Split by gate-axis sign: NEAR = gate-axis negative; FAR = gate-axis positive (relative to controller center)
    private final List<LightRef> nearCol = new ArrayList<>(); // bottom->top
    private final List<LightRef> farCol  = new ArrayList<>(); // bottom->top

    public GateLightGroup(Side side) { this.side = side; }
    public Side side() { return side; }

    /** Clears and (re)binds this side's lights relative to a controller/gate. */
    public void bindLights(World world, BlockPos gateCenter, Direction.Axis gateAxis, int radius) {
        nearCol.clear();
        farCol.clear();
        if (world == null) return;

        // Normal axis splits SIDE1 vs SIDE2; gate axis splits NEAR vs FAR
        Direction.Axis normalAxis = (gateAxis == Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;

        BlockPos min = gateCenter.add(-radius, -radius, -radius);
        BlockPos max = gateCenter.add( radius,  radius,  radius);
        BlockPos.Mutable m = new BlockPos.Mutable();

        final double cx = gateCenter.getX() + 0.5;
        final double cz = gateCenter.getZ() + 0.5;

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    m.set(x, y, z);
                    BlockEntity be = world.getBlockEntity(m);
                    if (!(be instanceof GateLightBlockEntity)) continue;

                    // Which SIDE by normal-axis sign
                    double normalDelta = (normalAxis == Direction.Axis.X) ? (x - cx) : (z - cz);
                    Side computedSide = (normalDelta < 0) ? Side.SIDE1 : Side.SIDE2;
                    if (computedSide != this.side) continue;

                    // Which column by gate-axis sign (negative=NEAR, positive=FAR)
                    double gateDelta = (gateAxis == Direction.Axis.X) ? (x - cx) : (z - cz);
                    List<LightRef> col = (gateDelta < 0) ? nearCol : farCol;

                    col.add(new LightRef(m.toImmutable()));
                }
            }
        }

        // Sort each column bottom -> top
        nearCol.sort(Comparator.comparingDouble(l -> l.y));
        farCol.sort(Comparator.comparingDouble(l -> l.y));

        KarmaGateMod.LOGGER.info("GateLightGroup {} bound NEAR:{} FAR:{} near {}",
                side, nearCol.size(), farCol.size(), gateCenter);
    }

    /** Bottom (near column), or null. */
    public LightRef bottomNear() { return nearCol.isEmpty() ? null : nearCol.get(0); }

    /** Top (near column), or null. */
    public LightRef topNear() { return nearCol.isEmpty() ? null : nearCol.get(nearCol.size() - 1); }

    /** Bottom (far column), or null. */
    public LightRef bottomFar() { return farCol.isEmpty() ? null : farCol.get(0); }

    /** Top (far column), or null. */
    public LightRef topFar() { return farCol.isEmpty() ? null : farCol.get(farCol.size() - 1); }

    /** Unmodifiable all refs (for debugging/tools). */
    public List<LightRef> getRefs() {
        List<LightRef> all = new ArrayList<>(nearCol.size() + farCol.size());
        all.addAll(nearCol);
        all.addAll(farCol);
        return Collections.unmodifiableList(all);
    }

    /** Force all lights off (e.g., on cooldown/end of cycle). */
    public void allOff(World world) {
        setAll(world, false);
    }

    /** Blink all lights together at 10-tick cadence. */
    /** Blink all lights together at half-period cadence. */
    public void blinkAll(World world, int tick) {
        boolean on = (tick % BLINK_PERIOD_TICKS) < (BLINK_PERIOD_TICKS / 2);
        for (LightRef r : nearCol) setOne(world, r.pos, on);
        for (LightRef r : farCol)  setOne(world, r.pos, on);
    }

    /**
     * Alternate bottom vs top every half-period while preparing.
     * Mirrored: bottom-pair = NEAR-bottom + FAR-top, top-pair = NEAR-top + FAR-bottom.
     */
    public void blinkBottomTopAlternate(World world, int tick) {
        boolean bottomOn = (tick % BLINK_PERIOD_TICKS) < (BLINK_PERIOD_TICKS / 2);
        setAll(world, false);
        if (bottomOn) {
            lightBottomPair(world, true);
        } else {
            lightTopPair(world, true);
        }
    }


    /** Light the “bottom pair”: NEAR-bottom + FAR-top (mirror). */
    public void lightBottomPairOnly(World world) {
        setAll(world, false);
        lightBottomPair(world, true);
    }

    /** Light the “top pair”: NEAR-top + FAR-bottom (mirror). */
    public void lightTopPairOnly(World world) {
        setAll(world, false);
        lightTopPair(world, true);
    }

    /** Apply the two logical RegionGate lamp states represented by this side. */
    public void setPairStates(World world, boolean bottom, boolean top) {
        Set<BlockPos> lit = new HashSet<>();
        if (bottom) {
            LightRef near = bottomNear();
            LightRef far = topFar();
            if (near != null) lit.add(near.pos);
            if (far != null) lit.add(far.pos);
            if (near == null && far == null) {
                LightRef fallback = topNear() != null ? topNear() : bottomFar();
                if (fallback != null) lit.add(fallback.pos);
            }
        }
        if (top) {
            LightRef near = topNear();
            LightRef far = bottomFar();
            if (near != null) lit.add(near.pos);
            if (far != null) lit.add(far.pos);
            if (near == null && far == null) {
                LightRef fallback = bottomNear() != null ? bottomNear() : topFar();
                if (fallback != null) lit.add(fallback.pos);
            }
        }
        for (LightRef ref : nearCol) setOne(world, ref.pos, lit.contains(ref.pos));
        for (LightRef ref : farCol) setOne(world, ref.pos, lit.contains(ref.pos));
    }

    // ---------------- internals ----------------

    private void lightBottomPair(World world, boolean on) {
        LightRef nb = bottomNear();
        LightRef ft = topFar();
        if (nb != null) setOne(world, nb.pos, on);
        if (ft != null) setOne(world, ft.pos, on);
        // if neither exists, fallback: try any one available
        if (nb == null && ft == null) {
            // degrade: try near top then far bottom
            LightRef alt = topNear();
            if (alt == null) alt = bottomFar();
            if (alt != null) setOne(world, alt.pos, on);
        }
    }

    private void lightTopPair(World world, boolean on) {
        LightRef nt = topNear();
        LightRef fb = bottomFar();
        if (nt != null) setOne(world, nt.pos, on);
        if (fb != null) setOne(world, fb.pos, on);
        if (nt == null && fb == null) {
            LightRef alt = bottomNear();
            if (alt == null) alt = topFar();
            if (alt != null) setOne(world, alt.pos, on);
        }
    }

    private void setAll(World world, boolean lit) {
        if (!(world instanceof ServerWorld sw)) return;
        for (LightRef ref : nearCol) {
            BlockEntity be = sw.getBlockEntity(ref.pos);
            if (be instanceof GateLightBlockEntity lamp) lamp.setLit(lit);
        }
        for (LightRef ref : farCol) {
            BlockEntity be = sw.getBlockEntity(ref.pos);
            if (be instanceof GateLightBlockEntity lamp) lamp.setLit(lit);
        }
    }

    private void setOne(World world, BlockPos pos, boolean lit) {
        if (!(world instanceof ServerWorld sw)) return;
        BlockEntity be = sw.getBlockEntity(pos);
        if (be instanceof GateLightBlockEntity lamp) lamp.setLit(lit);
    }
}
