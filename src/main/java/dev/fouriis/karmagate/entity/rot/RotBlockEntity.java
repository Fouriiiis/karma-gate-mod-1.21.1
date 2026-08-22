package dev.fouriis.karmagate.entity.rot;

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

/** Persistent placed-object data for a DaddyCorruption zone. */
public final class RotBlockEntity extends BlockEntity {
    public static final float DEFAULT_RADIUS = 10.0f;
    public static final float MIN_RADIUS = 1.0f;
    public static final float MAX_RADIUS = 64.0f;

    private float radius = DEFAULT_RADIUS;

    public RotBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROT_BLOCK_ENTITY, pos, state);
    }

    public float getRadius() {
        return radius;
    }

    /** Ready for a future editor/config screen; defaults to ten blocks. */
    public void setRadius(float radius) {
        float next = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        if (Math.abs(this.radius - next) < 1.0e-4f) return;
        this.radius = next;
        markDirty();
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.getChunkManager().markForUpdate(pos);
        }
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
                ? Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, nbt.getFloat("Radius")))
                : DEFAULT_RADIUS;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        return createNbt(lookup);
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}
