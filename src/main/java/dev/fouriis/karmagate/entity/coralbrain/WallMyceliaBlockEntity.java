package dev.fouriis.karmagate.entity.coralbrain;

import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 3D port of {@code CoralBrain.WallMycelia}. */
public final class WallMyceliaBlockEntity extends BlockEntity implements Mycelium.Owner {
    public static final float DEFAULT_RADIUS = 10.0f;
    public static final float MIN_RADIUS = 1.0f;
    public static final float MAX_RADIUS = 32.0f;
    private static final int MAX_STRANDS = 384;

    private final ArrayList<Mycelium> mycelia = new ArrayList<>();
    private final ArrayList<Vec3d> roots = new ArrayList<>();
    private final ArrayList<Vec3d> directions = new ArrayList<>();
    private final ArrayList<Vec3d> randomMovements = new ArrayList<>();
    private float radius = DEFAULT_RADIUS;
    private boolean initialized;

    public WallMyceliaBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WALL_MYCELIA_BLOCK_ENTITY, pos, state);
    }

    public static void clientTick(World world, BlockPos pos, BlockState state,
                                  WallMyceliaBlockEntity wall) {
        if (!wall.initialized) wall.initialize(world);
        if (wall.mycelia.isEmpty()) return;

        Vec3d wind = CoralBrainSystem.wind(world);
        List<Mycelium> pool = CoralBrainSystem.mycelia(world);
        long seed = pos.asLong() ^ world.getTime() * 0x9E3779B97F4A7C15L;
        Random random = new Random(seed);
        for (int i = 0; i < wall.mycelia.size(); i++) {
            Mycelium strand = wall.mycelia.get(i);
            Vec3d movement = clampMagnitude(wall.randomMovements.get(i)
                    .add(randomUnit(random).multiply(0.005)), 0.05);
            wall.randomMovements.set(i, movement);

            for (int substep = 0; substep < 2; substep++) {
                strand.tick(world, wind, 1.0, seed + i * 1013L + substep * 67L, pool);
                int affected = Math.min(5, strand.pointCount());
                for (int point = 1; point < affected; point++) {
                    double phase = point / (double) (affected - 1);
                    Vec3d impulse = wall.directions.get(i).add(movement)
                            .multiply(Math.sin(phase * Math.PI) * 0.0375);
                    strand.addPointImpulse(point, impulse);
                }
            }
        }
    }

    private void initialize(World world) {
        initialized = true;
        CoralBrainSystem.unregisterOwner(world, this);
        mycelia.clear();
        roots.clear();
        directions.clear();
        randomMovements.clear();

        Vec3d center = Vec3d.ofCenter(pos);
        Random random = new Random(pos.asLong());
        int reach = MathHelper.ceil(radius);
        outer:
        for (int x = -reach; x <= reach; x++) {
            for (int y = -reach; y <= reach; y++) {
                for (int z = -reach; z <= reach; z++) {
                    BlockPos support = pos.add(x, y, z);
                    if (world.getBlockState(support).getBlock() == ModBlocks.WALL_MYCELIA
                            || world.getBlockState(support).getCollisionShape(world, support).isEmpty()) continue;

                    for (Direction face : Direction.values()) {
                        BlockPos outside = support.offset(face);
                        if (!world.getBlockState(outside).getCollisionShape(world, outside).isEmpty()) continue;
                        Vec3d normal = Vec3d.of(face.getVector());
                        Vec3d faceCenter = Vec3d.ofCenter(support).add(normal.multiply(0.501));
                        double distance = faceCenter.distanceTo(center);
                        if (distance > radius) continue;

                        double falloff = 1.0 - MathHelper.clamp(distance / radius, 0.0, 1.0);
                        int count = random.nextDouble() < 0.18 + falloff * 0.58 ? 1 : 0;
                        if (count > 0 && random.nextDouble() < falloff * 0.18) count++;
                        for (int strandIndex = 0; strandIndex < count; strandIndex++) {
                            Vec3d tangentA = tangentA(face);
                            Vec3d tangentB = normal.crossProduct(tangentA).normalize();
                            Vec3d root = faceCenter
                                    .add(tangentA.multiply(random.nextDouble() * 0.8 - 0.4))
                                    .add(tangentB.multiply(random.nextDouble() * 0.8 - 0.4));
                            Vec3d radial = safeNormalize(root.subtract(center), normal);
                            Vec3d growth = safeNormalize(normal.add(radial), normal);

                            double extra = lerpMap(radius, 2.5, 25.0, 1.0, 9.0);
                            double nearLength = 1.0 + extra * Math.pow(random.nextDouble(), 0.25);
                            double edge = inverseLerp(radius / 3.0, radius, distance);
                            double length = MathHelper.lerp(edge, nearLength, 1.0);

                            int index = roots.size();
                            roots.add(root);
                            directions.add(growth);
                            randomMovements.add(Vec3d.ZERO);
                            Mycelium strand = new Mycelium(this, index, length, root,
                                    pos.asLong() ^ index * 0x5DEECE66DL);
                            mycelia.add(strand);
                            CoralBrainSystem.register(world, strand);
                            if (mycelia.size() >= MAX_STRANDS) break outer;
                        }
                    }
                }
            }
        }
    }

    public List<Mycelium> getMycelia() {
        return Collections.unmodifiableList(mycelia);
    }

    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        float next = MathHelper.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        if (Math.abs(this.radius - next) < 1.0e-4f) return;
        this.radius = next;
        initialized = false;
        markDirty();
        if (world instanceof ServerWorld serverWorld) serverWorld.getChunkManager().markForUpdate(pos);
    }

    @Override
    public Vec3d connectionPos(int index, float timeStacker) {
        return index >= 0 && index < roots.size() ? roots.get(index) : Vec3d.ofCenter(pos);
    }

    @Override
    public Vec3d resetDir(int index) {
        return index >= 0 && index < directions.size() ? directions.get(index) : new Vec3d(0, 1, 0);
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putFloat("Radius", radius);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        radius = nbt.contains("Radius")
                ? MathHelper.clamp(nbt.getFloat("Radius"), MIN_RADIUS, MAX_RADIUS)
                : DEFAULT_RADIUS;
        initialized = false;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        return createNbt(lookup);
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public void markRemoved() {
        if (world != null) CoralBrainSystem.unregisterOwner(world, this);
        super.markRemoved();
    }

    private static Vec3d tangentA(Direction face) {
        Vec3d normal = Vec3d.of(face.getVector());
        Vec3d reference = Math.abs(normal.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        return normal.crossProduct(reference).normalize();
    }

    private static Vec3d randomUnit(Random random) {
        double y = random.nextDouble() * 2.0 - 1.0;
        double angle = random.nextDouble() * Math.PI * 2.0;
        double ring = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        return new Vec3d(Math.cos(angle) * ring, y, Math.sin(angle) * ring);
    }

    private static Vec3d safeNormalize(Vec3d value, Vec3d fallback) {
        return value.lengthSquared() < 1.0e-10 ? fallback : value.normalize();
    }

    private static Vec3d clampMagnitude(Vec3d value, double maximum) {
        return value.lengthSquared() <= maximum * maximum ? value : value.normalize().multiply(maximum);
    }

    private static double inverseLerp(double a, double b, double value) {
        return a == b ? 0.0 : MathHelper.clamp((value - a) / (b - a), 0.0, 1.0);
    }

    private static double lerpMap(double value, double a, double b, double c, double d) {
        return MathHelper.lerp(inverseLerp(a, b, value), c, d);
    }
}
