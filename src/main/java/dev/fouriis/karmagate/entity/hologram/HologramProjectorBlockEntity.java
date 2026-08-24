// dev/fouriis/karmagate/entity/hologram/HologramProjectorBlockEntity.java
package dev.fouriis.karmagate.entity.hologram;

import dev.fouriis.karmagate.block.hologram.HologramProjectorBlock;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateController;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateController.KarmaLevel;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Random;

public class HologramProjectorBlockEntity extends BlockEntity {
    private static final int ELECTRIC_GATE_COLOR = 0xFFBF80;
    // Rain World palette1, Unity pixel (13, 7). Unity addresses textures from
    // the bottom, so this is the PNG's top-row white pixel, not the dark pixel
    // at top-origin image coordinate (13, 7).
    private static final int WATER_GATE_COLOR = 0xFFFFFF;
    private static final int UNLOCKED_COLOR = 0x33CCFF;

    // server-authoritative selected symbol (0..5 plus D mapped to 6)
    // 0..5 => gateSymbol0.png..gateSymbol5.png, 6 => gateSymbolD.png
    private int symbolIdx = 0;
    // derived key used by the client renderer (computed from karma/symbol)
    private String symbolKey = keyFor(KarmaLevel.LEVEL_0);

    // Client-only GateKarmaGlyph simulation state. Rain World updates this at
    // 40 Hz, so the Minecraft ticker advances it twice per game tick.
    private float fade = 1.0f;
    private float previousFade = 1.0f;
    // RegionGateGlyph3D initializes all three fade values together.
    private float goalFade = 1.0f;
    private float flicker;
    private float sinAdder;
    private float redSine;
    private float colorRed = 1.0f;
    private float colorGreen = 1.0f;
    private float colorBlue = 1.0f;
    private float previousColorRed = 1.0f;
    private float previousColorGreen = 1.0f;
    private float previousColorBlue = 1.0f;
    private boolean visualStateInitialized;
    private final Random visualRandom;
    private float targetLevel = 0.0f;

    /*
     * Server-synchronised inputs to the original GateKarmaGlyph state machine.
     * Sending these instead of a precomputed alpha is important: goalFade,
     * fade and flicker all have independent history in the C# implementation.
     */
    private boolean controllerDriven;
    private boolean electricGate;
    private boolean gateClosedLike = true;
    private boolean thisSideSelected;
    private boolean electricLampsActive;
    private boolean energyEnough = true;
    private boolean gateUnlocked;
    private int gateStartCounter;

    // Authoritative enum (no raw float/int for karma kept as state)
    private KarmaLevel karmaLevel = KarmaLevel.LEVEL_0;

    // Custom base color used only by an unbound/standalone projector.
    private int colorRGB = 0xFFFFFF;
    // red for low power mode
    private int lowPowerRGB = 0xFF0000;
    private boolean lowPower = false;
    private KarmaGateController controller = null;
    private BlockPos pendingControllerPos = null; // stored controller position to resolve after world load

    public HologramProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HOLOGRAM_PROJECTOR, pos, state);
        visualRandom = new Random(pos.asLong() ^ 52091L);
    }

    private static String keyFor(KarmaLevel k) {
        if (k == KarmaLevel.LEVEL_D) return "gateSymbolD.png";
        return "gateSymbol" + Math.max(0, Math.min(5, k.getIndex())) + ".png";
    }

    private void setSymbolFromKarma(KarmaLevel lvl) {
        int idx = (lvl == KarmaLevel.LEVEL_D) ? 6 : Math.max(0, Math.min(5, lvl.getIndex()));
        this.symbolIdx = idx;
        this.symbolKey = keyFor(lvl);
    }

    // server-side: advance 1->5 and wrap (D mapped from 6)
    public void cycleSymbol() {
        this.symbolIdx = (this.symbolIdx + 1) % 7;
        KarmaLevel lvl = KarmaLevel.fromIndex(this.symbolIdx);
        this.symbolKey = keyFor(lvl);
        if (controller != null) {
            controller.setKarma(this.pos, lvl); // will mirror to both sides
        } else {
            // No controller: update local karmaLevel and visuals anyway
            this.karmaLevel = lvl;
            setSymbolFromKarma(lvl);
        }
        markDirtySync();
        System.out.println("HologramProjectorBlockEntity: cycleSymbol to " + this.symbolIdx + " (" + this.symbolKey + ")");
    }

    // client: used by renderer
    public void setSymbolKey(String key) { this.symbolKey = key; }
    public String getSymbolKey() {
        return controllerDriven && gateUnlocked
                ? keyFor(KarmaLevel.LEVEL_0)
                : symbolKey;
    }
    public int getSymbolIndex() { return symbolIdx; }

    public float getStaticLevel() { return targetLevel; }
    public void setStaticLevel(float v) {
        this.targetLevel = Math.max(0f, Math.min(1f, v));
        markDirtySync();
    }

    public float getInterpolatedFade(float tickDelta) {
        return previousFade + (fade - previousFade) * tickDelta;
    }

    public int getInterpolatedGlyphColor(float tickDelta) {
        int red = Math.round(clamp01(previousColorRed + (colorRed - previousColorRed) * tickDelta) * 255.0f);
        int green = Math.round(clamp01(previousColorGreen + (colorGreen - previousColorGreen) * tickDelta) * 255.0f);
        int blue = Math.round(clamp01(previousColorBlue + (colorBlue - previousColorBlue) * tickDelta) * 255.0f);
        return red << 16 | green << 8 | blue;
    }

    /** Authoritative enum accessor. */
    public KarmaGateController.KarmaLevel getKarmaLevelEnum() { return karmaLevel; }

    /** Enum setter also updates symbolIdx/symbolKey to keep visuals in sync. */
    public void setKarmaLevelEnum(KarmaGateController.KarmaLevel lvl) {
        if (lvl != null && lvl != this.karmaLevel) {
            this.karmaLevel = lvl;
            setSymbolFromKarma(lvl);
            markDirtySync();
        }
    }

    /** Convenience alias (kept for readability at call sites). */
    public void setKarmaLevel(KarmaLevel lvl) { setKarmaLevelEnum(lvl); }

    /** Derived numeric view (if shaders/UI still need it). */
    @Deprecated
    public float getKarmaLevelValue() { return karmaLevel.asFloat(); }

    /** Set hologram color as 0xRRGGBB; server-side authoritative; syncs to clients. */
    public void setColorRGB(int rgb) {
        if (world != null && world.isClient) return;
        int val = rgb & 0xFFFFFF;
        if (val != this.colorRGB) {
            this.colorRGB = val;
            markDirtySync();
        }
    }

    public static void tick(net.minecraft.world.World w, BlockPos p, BlockState s, HologramProjectorBlockEntity be) {
        if (w.isClient) {
            be.updateClientVisuals();
        }
        // Server-side lazy controller resolution
        if (!w.isClient && be.controller == null && be.pendingControllerPos != null) {
            BlockEntity maybe = w.getBlockEntity(be.pendingControllerPos);
            if (maybe instanceof KarmaGateBlockEntity kbe) {
                be.controller = kbe.getController();
                if (be.controller != null) {
                    be.pendingControllerPos = null; // resolved
                }
            }
        }
    }

    private void updateClientVisuals() {
        if (!visualStateInitialized) {
            float[] base = unpack(defaultColorRGB());
            colorRed = previousColorRed = base[0];
            colorGreen = previousColorGreen = base[1];
            colorBlue = previousColorBlue = base[2];
            // GateKarmaGlyph constructs both fade values at one, then lets the
            // reference state machine settle them toward goalFade.
            fade = previousFade = 1.0f;
            visualStateInitialized = true;
        }

        previousFade = fade;
        previousColorRed = colorRed;
        previousColorGreen = colorGreen;
        previousColorBlue = colorBlue;
        // RegionGateGlyph3D runs at 40 updates per second.
        updateClientVisualStep();
        updateClientVisualStep();
        updateShaderLightState();
    }

    /**
     * Exposes GateKarmaGlyph's already-simulated fade and color to
     * Iris block materials. This is client-only because the cosmetic state
     * machine itself intentionally lives on the client, as it does in C#.
     */
    private void updateShaderLightState() {
        if (world == null || !world.isClient) return;
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof HologramProjectorBlock)) return;

        int level = fade <= 0.0f ? 0 : Math.max(1, Math.round(clamp01(fade) * 15.0f));
        int colorMode;
        if (karmaLevel == KarmaLevel.LEVEL_D) {
            colorMode = 6;
        } else if ((controllerDriven && !energyEnough) || (!controllerDriven && lowPower)) {
            colorMode = electricGate ? 5 : 4;
        } else if (controllerDriven && gateUnlocked) {
            colorMode = electricGate ? 3 : 2;
        } else {
            colorMode = electricGate ? 1 : 0;
        }

        // Dynamic colors must come from this exact GateKarmaGlyph simulation.
        // Recomputing sinAdder/redSine in GLSL gives every source a different
        // clock from the visible hologram and causes their colors to drift.
        float colorAmount = switch (colorMode) {
            case 4, 6 -> (colorGreen + colorBlue) * 0.5f;
            case 5 -> ((colorGreen / 0.75f) + (colorBlue / 0.5f)) * 0.5f;
            default -> 0.0f;
        };
        int colorLevel = Math.round(clamp01(colorAmount) * 31.0f);

        BlockState next = state
                .with(HologramProjectorBlock.LIGHT_LEVEL, level)
                .with(HologramProjectorBlock.LIGHT_COLOR, colorMode)
                .with(HologramProjectorBlock.LIGHT_COLOR_LEVEL, colorLevel);
        if (!next.equals(state)) {
            world.setBlockState(pos, next, Block.NOTIFY_ALL);
        }
    }

    private void updateClientVisualStep() {
        boolean noEnergy = controllerDriven ? !energyEnough : lowPower;

        if (controllerDriven) {
            // GateKarmaGlyph.Update, unchanged apart from receiving the gate
            // fields over block-entity sync and running two steps per MC tick.
            if (gateClosedLike) {
                if (thisSideSelected || electricGate) {
                    if (electricGate && electricLampsActive) {
                        goalFade = Math.max(0.0f, goalFade - 0.025f);
                    } else {
                        goalFade = noEnergy
                                ? 0.82f
                                : inverseLerp(
                                        electricGate ? 10.0f : 40.0f,
                                        0.0f,
                                        gateStartCounter);
                    }
                } else {
                    goalFade = Math.min(
                            noEnergy ? 0.82f : 1.0f,
                            goalFade + 1.0f / 30.0f);
                }
            } else {
                goalFade = Math.max(0.0f, goalFade - 0.025f);
            }
        } else {
            // Preserve the sneak-use visibility control for projectors that
            // are deliberately placed without a KarmaGateController.
            goalFade = 1.0f - targetLevel;
        }

        fade = lerpAndTick(fade, Math.min(goalFade, 1.0f - flicker), 0.01f, 0.05f);

        float period = flicker == 0.0f ? lerp(30.0f, 780.0f, goalFade) : 30.0f;
        if (visualRandom.nextFloat() < 1.0f / period) flicker = visualRandom.nextFloat();
        if (noEnergy && visualRandom.nextFloat() < 1.0f / 70.0f) {
            flicker = Math.max(flicker, visualRandom.nextFloat());
        }
        if (flicker > 0.0f) flicker = Math.max(0.0f, flicker - 0.05f);

        float[] target = unpack(defaultColorRGB());
        if (controllerDriven && gateUnlocked) {
            float[] unlocked = unpack(UNLOCKED_COLOR);
            target[0] = lerp(target[0], unlocked[0], 0.6f);
            target[1] = lerp(target[1], unlocked[1], 0.6f);
            target[2] = lerp(target[2], unlocked[2], 0.6f);
        }
        if (noEnergy) {
            float redMix = clamp01(0.4f + 0.5f * (float) Math.sin(sinAdder / 12.0f));
            float[] low = unpack(lowPowerRGB);
            target[0] = lerp(target[0], low[0], redMix);
            target[1] = lerp(target[1], low[1], redMix);
            target[2] = lerp(target[2], low[2], redMix);
        }
        colorRed = lerp(colorRed, target[0], 0.2f);
        colorGreen = lerp(colorGreen, target[1], 0.2f);
        colorBlue = lerp(colorBlue, target[2], 0.2f);

        if (karmaLevel == KarmaLevel.LEVEL_D) {
            redSine += 1.0f;
            float pulse = (float) Math.sin(redSine / 25.0f) * 0.5f + 0.5f;
            colorRed = 1.0f;
            colorGreen = pulse;
            colorBlue = pulse;
        }

        // The source increments this after evaluating GetToColor.
        if (noEnergy) sinAdder += 1.0f;
    }

    private int defaultColorRGB() {
        if (!controllerDriven) return colorRGB;
        return electricGate ? ELECTRIC_GATE_COLOR : WATER_GATE_COLOR;
    }

    private static float lerpAndTick(float from, float to, float lerp, float tick) {
        float value = HologramProjectorBlockEntity.lerp(from, to, lerp);
        return value < to ? Math.min(value + tick, to) : Math.max(value - tick, to);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float inverseLerp(float from, float to, float value) {
        if (from == to) return 0.0f;
        return clamp01((value - from) / (to - from));
    }

    private static float[] unpack(int rgb) {
        return new float[] {
                ((rgb >>> 16) & 0xFF) / 255.0f,
                ((rgb >>> 8) & 0xFF) / 255.0f,
                (rgb & 0xFF) / 255.0f
        };
    }

    public void setTargetLevel(float v) {
        float next = Math.max(0f, Math.min(1f, v));
        if (Math.abs(next - this.targetLevel) <= 1.0e-4f) return;
        this.targetLevel = next;
        markDirtySync();
    }

    public void setLowpower(boolean lowPower) {
        // Do NOT overwrite base color; we blend between existing base (colorRGB) and lowPowerRGB in getDisplayColor.
        boolean changed = (this.lowPower != lowPower);
        this.lowPower = lowPower;
        if (changed) markDirtySync();
    }

    /**
     * Supplies the semantic RegionGate fields consumed by GateKarmaGlyph.
     * Visual history remains client-local, matching Rain World's cosmetic
     * object instead of snapping to a server-computed opacity.
     */
    public void setGateGlyphState(
            KarmaGateController.GateType type,
            boolean closedLike,
            boolean sideSelected,
            int startCounter,
            boolean lampsActive,
            boolean hasEnergy,
            boolean unlocked
    ) {
        if (world != null && world.isClient) return;

        boolean nextElectric = type == KarmaGateController.GateType.ELECTRIC;
        int nextCounter = Math.max(0, startCounter);
        boolean changed = !controllerDriven
                || electricGate != nextElectric
                || gateClosedLike != closedLike
                || thisSideSelected != sideSelected
                || gateStartCounter != nextCounter
                || electricLampsActive != lampsActive
                || energyEnough != hasEnergy
                || gateUnlocked != unlocked;

        controllerDriven = true;
        electricGate = nextElectric;
        gateClosedLike = closedLike;
        thisSideSelected = sideSelected;
        gateStartCounter = nextCounter;
        electricLampsActive = lampsActive;
        energyEnough = hasEnergy;
        gateUnlocked = unlocked;
        lowPower = !hasEnergy;

        if (changed) markDirtySync();
    }

    /* ================= sync & NBT ================= */
    private void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld sw) sw.getChunkManager().markForUpdate(pos);
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putInt("symbolIdx", symbolIdx);
        nbt.putFloat("targetLevel", targetLevel);
        nbt.putString("karmaLevel", karmaLevel.name());
        nbt.putInt("colorRGB", colorRGB);
        nbt.putBoolean("lowPower", lowPower); // ensure client knows to pulse
        nbt.putInt("lowPowerRGB", lowPowerRGB); // in case this is customized later
        nbt.putBoolean("controllerDriven", controllerDriven);
        nbt.putBoolean("electricGate", electricGate);
        nbt.putBoolean("gateClosedLike", gateClosedLike);
        nbt.putBoolean("thisSideSelected", thisSideSelected);
        nbt.putBoolean("electricLampsActive", electricLampsActive);
        nbt.putBoolean("energyEnough", energyEnough);
        nbt.putBoolean("gateUnlocked", gateUnlocked);
        nbt.putInt("gateStartCounter", gateStartCounter);
        // put controller position
        if (controller != null) {
            BlockPos ctrlPos = controller.getPos();
            nbt.putInt("controllerX", ctrlPos.getX());
            nbt.putInt("controllerY", ctrlPos.getY());
            nbt.putInt("controllerZ", ctrlPos.getZ());
        }
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);

        // Load saved symbol (kept for back-compat), then override from karma (authoritative)
        this.symbolIdx = Math.max(0, Math.min(6, nbt.getInt("symbolIdx")));
        this.symbolKey = keyFor(KarmaLevel.fromIndex(this.symbolIdx));

        if (nbt.contains("targetLevel")) {
            this.targetLevel = Math.max(0f, Math.min(1f, nbt.getFloat("targetLevel")));
        }

        if (nbt.contains("karmaLevel")) {
            if (nbt.get("karmaLevel") instanceof net.minecraft.nbt.NbtString) {
                try {
                    this.karmaLevel = KarmaGateController.KarmaLevel.valueOf(nbt.getString("karmaLevel"));
                } catch (IllegalArgumentException e) {
                    this.karmaLevel = KarmaGateController.KarmaLevel.LEVEL_0;
                }
            } else {
                // Back-compat: if some old world stored a float, map it
                this.karmaLevel = KarmaGateController.KarmaLevel.fromFloat(nbt.getFloat("karmaLevel"));
            }
        }

        // Ensure visuals match authoritative karma
        setSymbolFromKarma(this.karmaLevel);

        if (nbt.contains("colorRGB")) {
            this.colorRGB = nbt.getInt("colorRGB") & 0xFFFFFF;
            // Migrate the former hard-coded blue default to Rain World's actual
            // palette shortcut color. Explicit custom colors remain untouched.
            if (this.colorRGB == 0x59CCFF) this.colorRGB = 0xFFFFFF;
        }
        if (nbt.contains("lowPower")) this.lowPower = nbt.getBoolean("lowPower");
        if (nbt.contains("lowPowerRGB")) this.lowPowerRGB = nbt.getInt("lowPowerRGB") & 0xFFFFFF;
        if (nbt.contains("controllerDriven")) this.controllerDriven = nbt.getBoolean("controllerDriven");
        if (nbt.contains("electricGate")) this.electricGate = nbt.getBoolean("electricGate");
        if (nbt.contains("gateClosedLike")) this.gateClosedLike = nbt.getBoolean("gateClosedLike");
        if (nbt.contains("thisSideSelected")) this.thisSideSelected = nbt.getBoolean("thisSideSelected");
        if (nbt.contains("electricLampsActive")) this.electricLampsActive = nbt.getBoolean("electricLampsActive");
        if (nbt.contains("energyEnough")) {
            this.energyEnough = nbt.getBoolean("energyEnough");
        } else {
            this.energyEnough = !lowPower;
        }
        if (nbt.contains("gateUnlocked")) this.gateUnlocked = nbt.getBoolean("gateUnlocked");
        if (nbt.contains("gateStartCounter")) this.gateStartCounter = Math.max(0, nbt.getInt("gateStartCounter"));
        // read controller position and link (if possible)
        if (nbt.contains("controllerX") && nbt.contains("controllerY") && nbt.contains("controllerZ")) {
            BlockPos ctrlPos = new BlockPos(nbt.getInt("controllerX"), nbt.getInt("controllerY"), nbt.getInt("controllerZ"));
            if (!ctrlPos.equals(BlockPos.ORIGIN)) {
                // Try immediate resolution if world is present, otherwise defer
                if (world != null) {
                    BlockEntity cbe = world.getBlockEntity(ctrlPos);
                    if (cbe instanceof KarmaGateBlockEntity kbe) {
                        this.controller = kbe.getController();
                        if (this.controller == null) this.pendingControllerPos = ctrlPos; // controller not ready yet
                    } else {
                        this.pendingControllerPos = ctrlPos; // will retry later
                    }
                } else {
                    this.pendingControllerPos = ctrlPos;
                }
            }
        }
    }

    @Override public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) { return createNbt(lookup); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }

    public void bindController(KarmaGateController karmaGateController) {
        this.controller = karmaGateController;
        if (karmaGateController != null) this.pendingControllerPos = null;
    }
}
