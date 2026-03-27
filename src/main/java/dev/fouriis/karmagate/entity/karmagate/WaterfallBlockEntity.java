package dev.fouriis.karmagate.entity.karmagate;

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
import net.minecraft.world.World;

public class WaterfallBlockEntity extends BlockEntity {
    private float flow = 1.0f;

    public WaterfallBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.WATERFALL_BLOCK_ENTITY, pos, state);
    }

    protected WaterfallBlockEntity(net.minecraft.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static <T extends BlockEntity> void clientTick(World world, BlockPos pos, BlockState state, T blockEntity) {
        // No client keyframe propagation.
    }

    public float getFlow() {
        return flow;
    }

    public void setFlow(float next) {
        float clamped = clamp01(next);
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
        return flow;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
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
            flow = clamp01(nbt.getFloat("flow"));
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