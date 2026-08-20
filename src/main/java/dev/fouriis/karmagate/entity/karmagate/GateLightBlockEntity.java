package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.karmagate.GateLightBlock;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class GateLightBlockEntity extends BlockEntity implements GeoBlockEntity {
    private static final String ANIM_ON  = "on";
    private static final String ANIM_OFF = "off";

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** Server-authoritative lit state, synced to clients. */
    private boolean lit = false;

    /** Client flag to (re)pose once after fresh NBT arrives. */
    private boolean clientInitialized = false;

    public GateLightBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.GATE_LIGHT_BLOCK_ENTITY, pos, state);
    }

    public GateLightBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public void toggle() {
        if (world == null || world.isClient) return;
        setLit(!lit);
    }

    /** Server-only setter that syncs state, block light, and animation. */
    public void setLit(boolean value) {
        if (world == null || world.isClient) return;
        // If the block is broken, it must never be lit
        BlockState st = world.getBlockState(pos);
        boolean isBroken = (st.getBlock() instanceof GateLightBlock)
                && st.contains(GateLightBlock.BROKEN)
                && st.get(GateLightBlock.BROKEN);

        if (isBroken && value) {
            value = false; // clamp to off when broken
        }

        if (this.lit == value) return;

        this.lit = value;

        // Sync the BlockState's LIT property so luminance updates immediately
        if (st.getBlock() instanceof GateLightBlock && st.contains(GateLightBlock.LIT)) {
            if (st.get(GateLightBlock.LIT) != value) {
                world.setBlockState(pos, st.with(GateLightBlock.LIT, value), 3); // notifies + relights
            }
        }

        // Network sync for BE data + render
        markDirtySync();

        // Play the flip animation and hold on last frame
        this.triggerAnim("controller", value ? "on" : "off");
        KarmaGateMod.LOGGER.debug("GateLight @{} -> {}", pos, value ? "ON" : "OFF");
    }

    public boolean isLit() { return lit; }

    /**
     * Gate-controller-specific setter.
     *
     * <p>The controller now resolves the physical a/b/c/d lamp state itself.
     * GateLightGroup calls this method for each physical fixture. The block
     * entity remains responsible only for applying/syncing the final lit
     * state.</p>
     */
    public void setGateLit(boolean value) {
        setLit(value);
    }

    public void tick(World world, BlockPos pos, BlockState state, GateLightBlockEntity be) {
        // Server-side safety: if the block becomes broken while lit, force it off
        if (!world.isClient) {
            if (state.getBlock() instanceof GateLightBlock
                    && state.contains(GateLightBlock.BROKEN)
                    && state.get(GateLightBlock.BROKEN)
                    && this.lit) {
                setLit(false);
            }
        }
    }

    // ---------------- GeckoLib ----------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        AnimationController<GateLightBlockEntity> controller =
            new AnimationController<>(this, "controller", 0, this::predicate);

        controller
            .triggerableAnim("on",  RawAnimation.begin().then(ANIM_ON,  Animation.LoopType.HOLD_ON_LAST_FRAME))
            .triggerableAnim("off", RawAnimation.begin().then(ANIM_OFF, Animation.LoopType.HOLD_ON_LAST_FRAME));

        registrar.add(controller);
    }

    private PlayState predicate(AnimationState<GateLightBlockEntity> state) {
        if (world != null && world.isClient && !clientInitialized) {
            AnimationController<GateLightBlockEntity> ctrl = state.getController();
            ctrl.forceAnimationReset();
            ctrl.setAnimation(RawAnimation.begin()
                .then(lit ? ANIM_ON : ANIM_OFF, Animation.LoopType.HOLD_ON_LAST_FRAME));
            clientInitialized = true;
            return PlayState.CONTINUE;
        }
        return PlayState.CONTINUE;
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    // ---------------- Sync & NBT ----------------

    private void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld sw) sw.getChunkManager().markForUpdate(pos);
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putBoolean("lit", lit);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        this.lit = nbt.getBoolean("lit");

        // Keep block state's LIT in sync on both sides (server does real relight)
        if (world != null) {
            BlockState st = world.getBlockState(pos);
            // If the block state says it's broken, ensure lit is false
            if (st.getBlock() instanceof GateLightBlock && st.contains(GateLightBlock.BROKEN)
                    && st.get(GateLightBlock.BROKEN) && this.lit) {
                this.lit = false;
            }
            if (st.getBlock() instanceof GateLightBlock && st.contains(GateLightBlock.LIT)
                && st.get(GateLightBlock.LIT) != lit) {
                world.setBlockState(pos, st.with(GateLightBlock.LIT, lit), 3);
            }
            if (world.isClient) clientInitialized = false;
        }
    }

    @Override public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) { return createNbt(lookup); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }
}
