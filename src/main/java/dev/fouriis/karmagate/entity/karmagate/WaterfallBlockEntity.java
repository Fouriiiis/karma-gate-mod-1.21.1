package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

public class WaterfallBlockEntity extends BlockEntity {
    public static final int MAX_BLOCKS_DOWN = 128;
    private static final float SOURCE_TOP_Y = 1.0f;
    private static final float FALL_ACCEL_BLOCKS = 0.9f / 20.0f;
    private static final float FLOW_EPSILON = 1.0e-4f;
    private static final float MAX_FLOW = 0.5f;

    private float flow = MAX_FLOW;
    private float lastRenderedFlow = MAX_FLOW;
    private float renderedFlow = MAX_FLOW;
    private float visualDensity = MAX_FLOW;
    private float lastVisualDensity = MAX_FLOW;
    private float topPos = SOURCE_TOP_Y;
    private float prevTopPos = SOURCE_TOP_Y;
    private float topVelocity = 0.0f;
    private float bottomPos = SOURCE_TOP_Y;
    private float prevBottomPos = SOURCE_TOP_Y;
    private float bottomVelocity = 0.0f;
    private boolean clientStateInitialized = false;

    public WaterfallBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.WATERFALL_BLOCK_ENTITY, pos, state);
    }

    protected WaterfallBlockEntity(net.minecraft.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static <T extends BlockEntity> void clientTick(World world, BlockPos pos, BlockState state, T blockEntity) {
        if (!world.isClient || !(blockEntity instanceof WaterfallBlockEntity waterfall)) {
            return;
        }

        waterfall.tickClient(world, pos);
    }

    public float getFlow() {
        return flow;
    }

    public void setFlow(float next) {
        float clamped = clampFlow(next);
        if (Math.abs(clamped - this.flow) <= 1e-4f) {
            return;
        }

        this.flow = clamped;
        markDirty();

        if (world != null) {
            if (world instanceof ServerWorld sw && !world.isClient) {
                sw.getChunkManager().markForUpdate(pos);
            }
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    public float getEffectiveFlow(double clientTimeTicks, double distanceBlocks) {
        float yAtDistance = SOURCE_TOP_Y - (float) distanceBlocks;
        if (yAtDistance > topPos + FLOW_EPSILON || yAtDistance < bottomPos - FLOW_EPSILON) {
            return 0.0f;
        }

        return renderedFlow;
    }

    public float getVisualDensity(float tickDelta) {
        return lerp(lastVisualDensity, visualDensity, tickDelta);
    }

    public float getInterpolatedTopLocalY(float tickDelta) {
        return lerp(prevTopPos, topPos, tickDelta);
    }

    public float getInterpolatedBottomLocalY(float tickDelta) {
        return lerp(prevBottomPos, bottomPos, tickDelta);
    }

    public void ensureClientVisualState(float impactY) {
        if (clientStateInitialized) {
            return;
        }

        clientStateInitialized = true;
        lastRenderedFlow = flow;
        renderedFlow = flow;
        topVelocity = 0.0f;
        bottomVelocity = 0.0f;

        if (flow <= FLOW_EPSILON) {
            visualDensity = 0.0f;
            lastVisualDensity = 0.0f;
            topPos = impactY;
            prevTopPos = impactY;
            bottomPos = impactY;
            prevBottomPos = impactY;
            return;
        }

        visualDensity = flow;
        lastVisualDensity = flow;
        topPos = SOURCE_TOP_Y;
        prevTopPos = SOURCE_TOP_Y;
        bottomPos = impactY;
        prevBottomPos = impactY;
    }

    public static float measureFallDistance(World world, BlockPos origin, int maxBlocksDown) {
        int bottomY = world.getBottomY();
        int y = origin.getY() - 1;
        int blocks = 0;

        BlockPos.Mutable p = new BlockPos.Mutable();
        p.setX(origin.getX());
        p.setZ(origin.getZ());

        while (y >= bottomY && blocks < maxBlocksDown) {
            p.setY(y);
            BlockState state = world.getBlockState(p);
            FluidState fluid = world.getFluidState(p);

            if (!fluid.isEmpty()) {
                float fluidHeight = fluid.getHeight(world, p);
                return blocks + (1.0f - fluidHeight);
            }

            if (state.isOpaqueFullCube(world, p)) {
                VoxelShape shape = state.getCollisionShape(world, p);
                double maxY = shape.isEmpty() ? 0.0 : shape.getMax(Direction.Axis.Y);
                return blocks + (1.0f - (float) maxY);
            }

            blocks++;
            y--;
        }

        return blocks;
    }

    private static float clampFlow(float v) {
        return Math.max(0f, Math.min(MAX_FLOW, v));
    }

    private void tickClient(World world, BlockPos pos) {
        float blocksDown = measureFallDistance(world, pos, MAX_BLOCKS_DOWN);
        float impactY = -blocksDown;
        ensureClientVisualState(impactY);

        // Rain World's WaterFall updates at 40 Hz. Keep Minecraft's render
        // interpolation at 20 Hz, but advance the endpoint propagation twice
        // between the captured render states.
        lastVisualDensity = visualDensity;
        prevTopPos = topPos;
        prevBottomPos = bottomPos;
        tickClientStep(impactY);
        tickClientStep(impactY);
    }

    private void tickClientStep(float impactY) {
        float flowBeforeStep = renderedFlow;

        if (isAtSourceTop(topPos)) {
            visualDensity = lerp(visualDensity, flow, 0.1f);
        }

        if (isAtSourceTop(topPos) || isAtImpact(bottomPos, impactY)) {
            renderedFlow = flow;
        }

        bottomPos += bottomVelocity;
        bottomVelocity -= FALL_ACCEL_BLOCKS;
        if (bottomPos < impactY) {
            bottomPos = impactY;
            bottomVelocity = 0.0f;
        }

        if (renderedFlow <= FLOW_EPSILON) {
            topPos += topVelocity;
            topVelocity -= FALL_ACCEL_BLOCKS;
            if (topPos < impactY) {
                topPos = impactY;
                topVelocity = 0.0f;
                visualDensity = 0.0f;
            }
        } else {
            topPos = SOURCE_TOP_Y;
            topVelocity = 0.0f;

            if (flowBeforeStep <= FLOW_EPSILON) {
                bottomPos = SOURCE_TOP_Y;
                bottomVelocity = 0.0f;
            }
        }

        lastRenderedFlow = renderedFlow;
    }

    protected final void setInitialFlow(float initialFlow) {
        flow = clampFlow(initialFlow);
        lastRenderedFlow = flow;
        renderedFlow = flow;
        visualDensity = flow;
        lastVisualDensity = flow;
        topPos = SOURCE_TOP_Y;
        prevTopPos = SOURCE_TOP_Y;
        bottomPos = SOURCE_TOP_Y;
        prevBottomPos = SOURCE_TOP_Y;
        topVelocity = 0.0f;
        bottomVelocity = 0.0f;
        clientStateInitialized = false;
    }

    private static boolean isAtSourceTop(float y) {
        return Math.abs(y - SOURCE_TOP_Y) <= FLOW_EPSILON;
    }

    private static boolean isAtImpact(float y, float impactY) {
        return Math.abs(y - impactY) <= FLOW_EPSILON;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putFloat("flow", flow);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        if (nbt.contains("flow")) {
            float syncedFlow = clampFlow(nbt.getFloat("flow"));
            if (world != null && world.isClient && clientStateInitialized) {
                flow = syncedFlow;
            } else {
                setInitialFlow(syncedFlow);
            }
        }
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = super.toInitialChunkDataNbt(lookup);
        nbt.putFloat("flow", flow);
        return nbt;
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}
