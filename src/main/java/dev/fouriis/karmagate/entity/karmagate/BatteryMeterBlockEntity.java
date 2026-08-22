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

/** Server-authoritative charge state for the electric gate's meter sprite. */
public final class BatteryMeterBlockEntity extends BlockEntity {
    private static final float EPSILON = 1.0e-6f;

    private boolean gateManaged;
    private boolean electricGate;
    private float batteryLeft = 1.0f;
    private boolean batteryChanging;

    private boolean clientInitialized;
    private float previousClientBattery = 1.0f;

    public BatteryMeterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BATTERY_METER_BLOCK_ENTITY, pos, state);
    }

    public void setGateBatteryState(boolean electric, float charge, boolean changing) {
        if (world != null && world.isClient) return;

        float nextCharge = clamp01(charge);
        boolean changed = !gateManaged
                || electricGate != electric
                || Math.abs(nextCharge - batteryLeft) > EPSILON
                || batteryChanging != changing;

        gateManaged = true;
        electricGate = electric;
        batteryLeft = nextCharge;
        batteryChanging = changing;

        if (changed) markDirtySync();
    }

    public boolean shouldRenderMeter() {
        // A freshly placed, unbound meter is shown full so builders can align
        // its 420-pixel footprint. Once bound, only an electric gate displays it.
        return !gateManaged || electricGate;
    }

    public boolean isBatteryChanging() {
        return batteryChanging;
    }

    public float getInterpolatedBattery(float tickDelta) {
        if (world == null || !world.isClient || !clientInitialized) return batteryLeft;
        return previousClientBattery
                + (batteryLeft - previousClientBattery) * clamp01(tickDelta);
    }

    private void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.getChunkManager().markForUpdate(pos);
        }
        if (world != null) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putBoolean("gateManaged", gateManaged);
        nbt.putBoolean("electricGate", electricGate);
        nbt.putFloat("batteryLeft", batteryLeft);
        nbt.putBoolean("batteryChanging", batteryChanging);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);

        float incomingBattery = nbt.contains("batteryLeft")
                ? clamp01(nbt.getFloat("batteryLeft"))
                : 1.0f;
        if (world != null && world.isClient && clientInitialized) {
            previousClientBattery = batteryLeft;
        } else {
            previousClientBattery = incomingBattery;
        }

        gateManaged = nbt.getBoolean("gateManaged");
        electricGate = nbt.getBoolean("electricGate");
        batteryLeft = incomingBattery;
        batteryChanging = nbt.getBoolean("batteryChanging");
        clientInitialized = true;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        return createNbt(lookup);
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
