package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.karmagate.KarmaGateBlock;
import dev.fouriis.karmagate.entity.hologram.HologramProjectorBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative Region Gate controller.
 *
 * <p>This class intentionally owns the Rain World gate simulation rather than
 * adapting it to the old Minecraft component APIs. The component calls at the
 * bottom of this file are therefore the API that the components should be
 * changed to consume.</p>
 *
 * <p>Required component-side API after this rewrite:</p>
 * <pre>
 * KarmaGateBlockEntity.setGateDoorState(float closedFac, float goalClosedFac, boolean stalled)
 * KarmaGateBlockEntity.setGateResourceState(GateType type, float resource,
 *                                           boolean changing, boolean recharging,
 *                                           float rechargeProgress)
 *
 * WaterStreamBlockEntity.setGateWaterState(float requestedFlow, float actualFlow,
 *                                          float visualDensity,
 *                                          float topProgress, float bottomProgress)
 *
 * HeatCoilBlockEntity.setGateHeatState(float currentHeat, float targetHeat,
 *                                      float lightAlpha, float lightRadius,
 *                                      float distortionAlpha)
 *
 * SteamEmitterBlockEntity.setGateSteamState(float continuousLevel,
 *                                           int puffCount, float puffIntensity)
 *
 * GateLightGroup.setPhysicalStates(World world, boolean a, boolean b, boolean c, boolean d)
 * </pre>
 *
 * <p>One Minecraft server tick advances two Rain World source updates, retaining
 * the original 40 Hz gate constants and update order.</p>
 */
public final class KarmaGateController {
    private enum Mode {
        MiddleClosed,
        ClosingAirLock,
        Waiting,
        OpeningMiddle,
        MiddleOpen,
        ClosingMiddle,
        OpeningSide,
        Closed,
        Recharge,
        Broken
    }

    private enum Side {
        SIDE1,
        SIDE2
    }

    private enum ClampMode {
        Stacked,
        MovingDown,
        WaitingForPartner,
        Locked,
        MovingUp
    }

    public enum GateType {
        WATER,
        ELECTRIC,
        BROKEN
    }

    public enum KarmaLevel {
        LEVEL_0(0),
        LEVEL_1(1),
        LEVEL_2(2),
        LEVEL_3(3),
        LEVEL_4(4),
        LEVEL_5(5),
        LEVEL_D(-1);

        private final int index;

        KarmaLevel(int index) {
            this.index = index;
        }

        public int getIndex() {
            return index;
        }

        public float asFloat() {
            return index < 0 ? 1.0f : index / 5.0f;
        }

        public static KarmaLevel fromFloat(float value) {
            int bucket = Math.min(5, (int) Math.floor(clamp01(value) * 6.0f));
            return fromIndex(bucket);
        }

        public static KarmaLevel fromIndex(int index) {
            if (index == -1 || index == 6) {
                return LEVEL_D;
            }
            return switch (Math.max(0, Math.min(5, index))) {
                case 1 -> LEVEL_1;
                case 2 -> LEVEL_2;
                case 3 -> LEVEL_3;
                case 4 -> LEVEL_4;
                case 5 -> LEVEL_5;
                default -> LEVEL_0;
            };
        }
    }

    private static final int CONTROLLER_DATA_VERSION = 4;

    /*
     * Rain World source timing.
     */
    private static final int RAIN_WORLD_STEPS_PER_MC_TICK = 2;
    private static final int START_COUNTER_THRESHOLD = 60;
    private static final int WASHING_COUNTER_THRESHOLD = 400;

    private static final float DOOR_CLOSE_SPEED = 1.0f / 180.0f;
    private static final float DOOR_OPEN_SPEED = 0.0045454544f;
    private static final float CLAMPS_PAUSE = 0.2f;

    /*
     * Minecraft-only reusable-gate lifecycle.
     *
     * Closed preserves Rain World's post-cycle behavior for 30 seconds.
     * Recharge then stops the waterfalls and restores the resource over another
     * 90 seconds before returning to MiddleClosed.
     */
    private static final int COOLDOWN_SOURCE_STEPS = 40 * 30;
    private static final int RECHARGE_SOURCE_STEPS = 40 * 90;

    /*
     * Detection.
     */
    private static final int STATIONARY_REQUIRED = 20;
    private static final double STATIONARY_DISTANCE_SQUARED = 0.0025;
    private static final double HALF_WIDTH = 6.5;
    private static final double FALLBACK_SIDE_OFFSET_NEGATIVE = -4.0;
    private static final double FALLBACK_SIDE_OFFSET_POSITIVE = 5.0;
    private static final double DETECTION_GATE_PADDING = 2.0;
    private static final double CROSSING_THRESHOLD = 0.65;

    /*
     * Source WaterFall vertical coordinates. These are only used to reproduce
     * the C# waterfall-front timing and heater intersection. Components receive
     * normalized top/bottom progress values.
     */
    private static final float WATERFALL_START_Y = 485.0f;
    private static final float WATERFALL_HEATER_Y = 30.0f;
    private static final float WATERFALL_BOTTOM_Y = -200.0f;
    private static final float WATERFALL_GRAVITY = 0.9f;

    private static final int DOOR_SIDE1 = 0;
    private static final int DOOR_MIDDLE = 1;
    private static final int DOOR_SIDE2 = 2;

    private final KarmaGateBlockEntity controllerBE;

    private final GateLightGroup lightsSide1 =
            new GateLightGroup(GateLightGroup.Side.SIDE1);
    private final GateLightGroup lightsSide2 =
            new GateLightGroup(GateLightGroup.Side.SIDE2);

    private final List<BlockPos> waterSide1 = new ArrayList<>();
    private final List<BlockPos> waterSide2 = new ArrayList<>();
    private final List<BlockPos> heatSide1 = new ArrayList<>();
    private final List<BlockPos> heatSide2 = new ArrayList<>();
    private final List<BlockPos> steamSide1 = new ArrayList<>();
    private final List<BlockPos> steamSide2 = new ArrayList<>();
    private final List<BlockPos> hologramSide1 = new ArrayList<>();
    private final List<BlockPos> hologramSide2 = new ArrayList<>();
    private final List<BlockPos> batteryMeters = new ArrayList<>();

    private final Random random = new Random(1979L);

    private final Map<UUID, Vec3d> previousPlayerPositions = new HashMap<>();
    private final Map<UUID, Integer> stationarySteps = new HashMap<>();

    private BlockPos gate1;
    private BlockPos gate2;

    private GateType gateType = GateType.BROKEN;
    private Mode mode = Mode.MiddleClosed;
    private Side entrySide;

    private boolean dontOpen;

    /*
     * Multiplayer startup arbitration.
     *
     * Rain World only has one startCounter because all progressing players are
     * expected to agree on a side. Minecraft can have players waiting in both
     * chambers, so the sides count independently. The first side to cross
     * >60 wins the cycle.
     */
    private int startCounterSide1;
    private int startCounterSide2;
    private long startOrderSide1;
    private long startOrderSide2;
    private long nextStartOrder = 1L;

    private int washingCounter;
    private int cooldownCounter;
    private int rechargeCounter;
    private float rechargeStartResource = 1.0f;

    private int lampBlink;

    /*
     * Door source state.
     */
    private final float[] doorClosed = { 0.0f, 1.0f, 0.0f };
    private final float[] doorGoal = { 0.0f, 1.0f, 0.0f };
    private final DoorMechanics[] doorMechanics = new DoorMechanics[3];

    /*
     * WaterGate + WaterFall state.
     */
    private float waterLeft = 1.0f;
    private float waterLevelDisplay = 1.0f;

    private final float[] waterSetFlow = new float[2];
    private final float[] waterFlow = new float[2];
    private final float[] waterVisualDensity = new float[2];

    private final float[] waterTopY = {
            WATERFALL_BOTTOM_Y, WATERFALL_BOTTOM_Y
    };
    private final float[] waterBottomY = {
            WATERFALL_BOTTOM_Y, WATERFALL_BOTTOM_Y
    };
    private final float[] waterTopVelocity = new float[2];
    private final float[] waterBottomVelocity = new float[2];

    private float outletLag;

    private final float[] heaterHeat = new float[2];
    private final float[] heaterLastHeat = new float[2];
    private final float[] heaterTarget = new float[2];

    private int activeHeaterLight = -1;
    private float heaterLightAlpha;
    private float heaterLightRadius;

    /*
     * ElectricGate state.
     */
    private float batteryLeft = 1.0f;
    private boolean batteryChanging;

    private final boolean[] lampsOn = new boolean[4];
    private final boolean[] lampVisible = new boolean[4];

    private float electricSteam;

    /*
     * RegionGateGraphics loop/effect state.
     */
    private float backgroundWorkingLoopVolume;
    private float waterfallLoopVolume;
    private float steamLoopVolume;

    /*
     * Steam puffs are source events. Multiple source puffs may occur before one
     * Minecraft output application, so they are accumulated per side.
     */
    private final int[] pendingSteamPuffs = new int[2];
    private final float[] pendingSteamIntensity = new float[2];

    private KarmaLevel karmaSide1 = KarmaLevel.LEVEL_0;
    private KarmaLevel karmaSide2 = KarmaLevel.LEVEL_0;

    public KarmaGateController(KarmaGateBlockEntity controllerBE) {
        this.controllerBE = controllerBE;
        initialiseDoorMechanics();
    }

    public void setGates(BlockPos first, BlockPos second) {
        World world = controllerBE.getWorld();

        if (world == null || first == null || second == null) {
            gate1 = first;
            gate2 = second;
            return;
        }

        Direction.Axis axis =
                controllerBE.getCachedState().get(KarmaGateBlock.AXIS);

        if (axisCoordinate(first, axis) <= axisCoordinate(second, axis)) {
            gate1 = first;
            gate2 = second;
        } else {
            gate1 = second;
            gate2 = first;
        }
    }

    public void bindLights(
            World world,
            BlockPos pos,
            BlockState state,
            int radius
    ) {
        Direction.Axis pathAxis = state.get(KarmaGateBlock.AXIS);
        Direction.Axis modelAxis =
                pathAxis == Direction.Axis.X
                        ? Direction.Axis.Z
                        : Direction.Axis.X;

        lightsSide1.bindLights(world, pos, modelAxis, radius);
        lightsSide2.bindLights(world, pos, modelAxis, radius);

        lightsSide1.allOff(world);
        lightsSide2.allOff(world);
    }

    public void bindLightsAndEffects(
            World world,
            BlockPos pos,
            BlockState state,
            int radius
    ) {
        bindLights(world, pos, state, radius);

        waterSide1.clear();
        waterSide2.clear();
        heatSide1.clear();
        heatSide2.clear();
        steamSide1.clear();
        steamSide2.clear();
        hologramSide1.clear();
        hologramSide2.clear();
        batteryMeters.clear();

        Direction.Axis axis = state.get(KarmaGateBlock.AXIS);
        double center = axisCoordinate(pos, axis) - 0.5;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    BlockPos foundPos = pos.add(dx, dy, dz);
                    BlockEntity found = world.getBlockEntity(foundPos);

                    boolean negative =
                            axisCoordinate(foundPos, axis) + 0.5 < center;

                    if (found instanceof WaterStreamBlockEntity) {
                        (negative ? waterSide1 : waterSide2).add(foundPos);
                    } else if (found instanceof HeatCoilBlockEntity) {
                        (negative ? heatSide1 : heatSide2).add(foundPos);
                    } else if (found instanceof SteamEmitterBlockEntity) {
                        (negative ? steamSide1 : steamSide2).add(foundPos);
                    } else if (
                            found instanceof HologramProjectorBlockEntity hologram
                    ) {
                        (negative ? hologramSide1 : hologramSide2)
                                .add(foundPos);
                        hologram.bindController(this);
                    } else if (found instanceof BatteryMeterBlockEntity) {
                        batteryMeters.add(foundPos);
                    }
                }
            }
        }

        gateType = inferGateType();

        applyKarmaToList(world, hologramSide1, karmaSide1);
        applyKarmaToList(world, hologramSide2, karmaSide2);

        KarmaGateMod.LOGGER.info(
                "[GateCtrl @{}] type={} water(S1={},S2={}) "
                        + "heat(S1={},S2={}) steam(S1={},S2={}) "
                        + "lights={} holograms(S1={},S2={}) batteryMeters={}",
                controllerBE.getPos(),
                gateType,
                waterSide1.size(),
                waterSide2.size(),
                heatSide1.size(),
                heatSide2.size(),
                steamSide1.size(),
                steamSide2.size(),
                lightsSide1.getRefs().size() + lightsSide2.getRefs().size(),
                hologramSide1.size(),
                hologramSide2.size(),
                batteryMeters.size()
        );

        if (gateType == GateType.BROKEN) {
            KarmaGateMod.LOGGER.warn(
                    "[GateCtrl @{}] component set is ambiguous/incomplete; "
                            + "gate is inert",
                    controllerBE.getPos()
            );
        }
    }

    public void resetOnBind() {
        resetSimulation();
        applyOutputs(controllerBE.getWorld());
    }

    private void resetSimulation() {
        mode =
                gateType == GateType.BROKEN
                        ? Mode.Broken
                        : Mode.MiddleClosed;

        entrySide = null;
        dontOpen = false;

        startCounterSide1 = 0;
        startCounterSide2 = 0;
        startOrderSide1 = 0L;
        startOrderSide2 = 0L;
        nextStartOrder = 1L;

        washingCounter = 0;
        cooldownCounter = 0;
        rechargeCounter = 0;
        rechargeStartResource = 1.0f;

        lampBlink = 0;

        doorClosed[DOOR_SIDE1] = 0.0f;
        doorClosed[DOOR_MIDDLE] = 1.0f;
        doorClosed[DOOR_SIDE2] = 0.0f;

        doorGoal[DOOR_SIDE1] = 0.0f;
        doorGoal[DOOR_MIDDLE] = 1.0f;
        doorGoal[DOOR_SIDE2] = 0.0f;

        initialiseDoorMechanics();

        waterLeft = 1.0f;
        waterLevelDisplay = 1.0f;
        outletLag = 0.0f;

        for (int i = 0; i < 2; i++) {
            waterSetFlow[i] = 0.0f;
            waterFlow[i] = 0.0f;
            waterVisualDensity[i] = 0.0f;

            waterTopY[i] = WATERFALL_BOTTOM_Y;
            waterBottomY[i] = WATERFALL_BOTTOM_Y;
            waterTopVelocity[i] = 0.0f;
            waterBottomVelocity[i] = 0.0f;

            heaterHeat[i] = 0.0f;
            heaterLastHeat[i] = 0.0f;
            heaterTarget[i] = 0.0f;

            pendingSteamPuffs[i] = 0;
            pendingSteamIntensity[i] = 0.0f;
        }

        activeHeaterLight = -1;
        heaterLightAlpha = 0.0f;
        heaterLightRadius = 0.0f;

        batteryLeft = 1.0f;
        batteryChanging = false;

        clearLogicalLamps();
        clearVisibleLamps();

        electricSteam = 0.0f;

        backgroundWorkingLoopVolume = 0.0f;
        waterfallLoopVolume = 0.0f;
        steamLoopVolume = 0.0f;

        previousPlayerPositions.clear();
        stationarySteps.clear();
    }

    public void tick(World world, BlockPos pos, BlockState state) {
        if (world == null || world.isClient) {
            return;
        }

        Detection detection = detectPlayers(world, pos, state);

        pendingSteamPuffs[0] = 0;
        pendingSteamPuffs[1] = 0;
        pendingSteamIntensity[0] = 0.0f;
        pendingSteamIntensity[1] = 0.0f;

        for (int step = 0; step < RAIN_WORLD_STEPS_PER_MC_TICK; step++) {
            simulationStep(detection);
        }

        applyOutputs(world);

        controllerBE.markDirty();
    }

    private void simulationStep(Detection detection) {
        lampBlink++;

        /*
         * C# virtual-call order:
         * 1. WaterGate/ElectricGate.Update
         * 2. RegionGate.Update -> RegionGateGraphics.Update
         * 3. RegionGate state machine
         * 4. RegionGate.Door.Update
         * 5. WaterFall room-object update
         *
         * The custom reusable lifecycle is inserted after the source gate state
         * update and before the WaterFall object consumes its new setFlow.
         */
        if (gateType == GateType.WATER) {
            updateWaterGate();
        } else if (gateType == GateType.ELECTRIC) {
            updateElectricGate();
        }

        updateGraphicsControls();
        updateBaseGate(detection);
        updateDoors();
        updateReusableLifecycle();

        if (gateType == GateType.WATER) {
            updateWaterFalls();
        }
    }

    private void updateBaseGate(Detection detection) {
        switch (mode) {
            case MiddleClosed -> updateMiddleClosedStartup(detection);

            case ClosingAirLock -> {
                if (allDoorsInPosition()) {
                    washingCounter = 0;
                    mode = Mode.Waiting;
                }
            }

            case Waiting -> {
                /*
                 * The subclass also increments washingCounter before this,
                 * exactly as WaterGate/ElectricGate do in the source.
                 */
                washingCounter++;

                if (washingCounter > WASHING_COUNTER_THRESHOLD) {
                    mode = Mode.OpeningMiddle;
                    doorGoal[DOOR_MIDDLE] = 0.0f;
                }
            }

            case OpeningMiddle -> {
                if (allDoorsInPosition()) {
                    mode = Mode.MiddleOpen;
                }
            }

            case MiddleOpen -> {
                if (
                        entrySide != null
                                && detection.allThrough(entrySide)
                ) {
                    doorGoal[DOOR_MIDDLE] = 1.0f;
                    mode = Mode.ClosingMiddle;
                }
            }

            case ClosingMiddle -> {
                if (allDoorsInPosition()) {
                    mode = Mode.OpeningSide;
                    doorGoal[DOOR_SIDE1] = 0.0f;
                    doorGoal[DOOR_SIDE2] = 0.0f;
                }
            }

            case OpeningSide -> {
                if (allDoorsInPosition()) {
                    mode = Mode.Closed;
                    dontOpen = true;
                    cooldownCounter = 0;
                    rechargeCounter = 0;

                    KarmaGateMod.LOGGER.info(
                            "[GateCtrl @{}] cycle complete -> Closed",
                            controllerBE.getPos()
                    );
                }
            }

            case Closed, Recharge, Broken -> {
                /*
                 * Closed/Recharge are driven by updateReusableLifecycle().
                 */
            }
        }
    }

    private void updateMiddleClosedStartup(Detection detection) {
        updateStartupCounterForSide(Side.SIDE1, detection);
        updateStartupCounterForSide(Side.SIDE2, detection);

        Side leader = startupLeader();

        /*
         * entrySide is the currently leading chamber while preparing. Once the
         * cycle is committed it remains the winning direction for the rest of
         * the cycle.
         */
        entrySide = leader;

        boolean side1Ready =
                startCounterSide1 > START_COUNTER_THRESHOLD;
        boolean side2Ready =
                startCounterSide2 > START_COUNTER_THRESHOLD;

        if (!side1Ready && !side2Ready) {
            if (!detection.anyInsideGate()) {
                dontOpen = false;
            }
            return;
        }

        Side winner;

        if (side1Ready && !side2Ready) {
            winner = Side.SIDE1;
        } else if (side2Ready && !side1Ready) {
            winner = Side.SIDE2;
        } else {
            winner = startupLeader();
        }

        if (winner == null) {
            return;
        }

        Side loser =
                winner == Side.SIDE1
                        ? Side.SIDE2
                        : Side.SIDE1;

        entrySide = winner;
        mode = Mode.ClosingAirLock;

        doorGoal[outerDoorIndex(winner)] = 1.0f;

        cancelStartupOutputs(loser);

        startCounterSide1 = 0;
        startCounterSide2 = 0;
        startOrderSide1 = 0L;
        startOrderSide2 = 0L;

        KarmaGateMod.LOGGER.info(
                "[GateCtrl @{}] MiddleClosed -> ClosingAirLock "
                        + "(winner={}, D{} closing)",
                controllerBE.getPos(),
                winner,
                outerDoorIndex(winner)
        );
    }

    private void updateStartupCounterForSide(
            Side side,
            Detection detection
    ) {
        boolean occupied =
                side == Side.SIDE1
                        ? detection.side1()
                        : detection.side2();

        boolean stationary =
                side == Side.SIDE1
                        ? detection.side1Stationary()
                        : detection.side2Stationary();

        boolean eligible =
                occupied
                        && !detection.center()
                        && stationary
                        && !dontOpen
                        && energyEnoughToOpen()
                        && isSideEnabled(side);

        if (!eligible) {
            resetStartupSide(side);
            return;
        }

        if (startupCounter(side) == 0) {
            setStartupOrder(side, nextStartOrder++);
        }

        setStartupCounter(side, startupCounter(side) + 1);
    }

    private int startupCounter(Side side) {
        return side == Side.SIDE1
                ? startCounterSide1
                : startCounterSide2;
    }

    private void setStartupCounter(Side side, int value) {
        if (side == Side.SIDE1) {
            startCounterSide1 = Math.max(0, value);
        } else {
            startCounterSide2 = Math.max(0, value);
        }
    }

    private long startupOrder(Side side) {
        return side == Side.SIDE1
                ? startOrderSide1
                : startOrderSide2;
    }

    private void setStartupOrder(Side side, long value) {
        if (side == Side.SIDE1) {
            startOrderSide1 = value;
        } else {
            startOrderSide2 = value;
        }
    }

    private void resetStartupSide(Side side) {
        if (startupCounter(side) > 0) {
            cancelStartupOutputs(side);
        }

        setStartupCounter(side, 0);
        setStartupOrder(side, 0L);
    }

    private Side startupLeader() {
        int side1 = startCounterSide1;
        int side2 = startCounterSide2;

        if (side1 <= 0 && side2 <= 0) {
            return null;
        }

        if (side1 > side2) {
            return Side.SIDE1;
        }

        if (side2 > side1) {
            return Side.SIDE2;
        }

        long order1 =
                startOrderSide1 == 0L
                        ? Long.MAX_VALUE
                        : startOrderSide1;

        long order2 =
                startOrderSide2 == 0L
                        ? Long.MAX_VALUE
                        : startOrderSide2;

        return order1 <= order2
                ? Side.SIDE1
                : Side.SIDE2;
    }

    private void cancelStartupOutputs(Side side) {
        if (gateType == GateType.WATER) {
            waterSetFlow[outletIndex(side)] = 0.0f;
        } else if (gateType == GateType.ELECTRIC) {
            clearLogicalLampPair(side);
            clearVisibleLampPair(side);
        }
    }

    private void updateWaterGate() {
        float pressure = waterPressure();

        switch (mode) {
            case MiddleClosed -> {
                /*
                 * Opposite-side startup race.
                 *
                 * SIDE1 uses outlet WaterFall 1.
                 * SIDE2 uses outlet WaterFall 0.
                 */
                waterSetFlow[outletIndex(Side.SIDE1)] =
                        inverseLerp(
                                0.0f,
                                60.0f,
                                startCounterSide1
                        ) * 0.5f * pressure;

                waterSetFlow[outletIndex(Side.SIDE2)] =
                        inverseLerp(
                                0.0f,
                                60.0f,
                                startCounterSide2
                        ) * 0.5f * pressure;
            }

            case ClosingAirLock -> {
                if (entrySide == null) {
                    break;
                }

                int outlet = outletIndex(entrySide);
                waterSetFlow[outlet] = pressure;
                waterRunning(1.0f);
            }

            case Waiting -> {
                if (entrySide == null) {
                    break;
                }

                int wash = washIndex(entrySide);
                int outlet = outletIndex(entrySide);

                washingCounter++;

                waterSetFlow[wash] =
                        (float) Math.pow(
                                inverseLerp(
                                        0.0f,
                                        160.0f,
                                        washingCounter
                                ),
                                1.5
                        ) * 0.5f * pressure;

                outletLag =
                        Math.max(
                                outletLag - 1.0f / 60.0f,
                                0.0f
                        );

                waterSetFlow[outlet] =
                        outletLag * pressure;

                if (washingCounter > WASHING_COUNTER_THRESHOLD) {
                    waterSetFlow[wash] = 0.0f;
                }
            }

            case OpeningMiddle -> {
                if (entrySide == null) {
                    break;
                }

                int outlet = outletIndex(entrySide);

                waterSetFlow[outlet] = pressure;
                waterRunning(1.0f);

                if (allDoorsInPosition()) {
                    outletLag = 1.0f;
                }
            }

            case MiddleOpen -> {
                if (entrySide == null) {
                    break;
                }

                int outlet = outletIndex(entrySide);

                outletLag =
                        Math.max(
                                outletLag - 1.0f / 60.0f,
                                0.0f
                        );

                waterSetFlow[outlet] =
                        outletLag * pressure;
            }

            case ClosingMiddle -> {
                if (entrySide == null) {
                    break;
                }

                int wash = washIndex(entrySide);
                int outlet = outletIndex(entrySide);

                waterSetFlow[wash] = pressure;
                waterSetFlow[outlet] = 0.0f;

                waterRunning(1.0f);
            }

            case OpeningSide -> {
                if (entrySide == null) {
                    break;
                }

                int wash = washIndex(entrySide);
                int outlet = outletIndex(entrySide);

                waterSetFlow[wash] = pressure;
                waterSetFlow[outlet] = 0.0f;

                waterRunning(1.0f);

                if (allDoorsInPosition()) {
                    /*
                     * Do NOT pulse setFlow to zero here.
                     *
                     * updateWaterGate() runs before updateBaseGate(), so this
                     * source step is still OpeningSide even though the shared
                     * state machine will switch to Closed later in the same
                     * step. Sending 0 here creates a transient dry sample when
                     * this happens on the second 40 Hz substep of a Minecraft
                     * tick. The next tick then restores the Closed trickle,
                     * which appears as a visible off/on flicker.
                     *
                     * Keep the full-pressure return flow for this final source
                     * step and seed outletLag at 1. Closed will reduce it to
                     * 59/60 on the next source update and then smoothly decay
                     * toward the 0.1 floor with no discontinuity.
                     */
                    outletLag = 1.0f;
                }
            }

            case Closed -> {
                if (entrySide == null) {
                    break;
                }

                int wash = washIndex(entrySide);

                /*
                 * Literal WaterGate.cs floor: outletLag never drops below .1
                 * while Closed, so the post-cycle trickle/drain persists for
                 * the whole cooldown.
                 */
                outletLag =
                        Math.max(
                                outletLag - 1.0f / 60.0f,
                                0.1f
                        );

                waterSetFlow[wash] =
                        outletLag > 0.05f
                                ? outletLag * pressure
                                : 0.0f;

                waterRunning(outletLag * 0.5f);
            }

            case Recharge -> {
                /*
                 * Custom stage: requested waterfall flow is zero on BOTH
                 * sides. WaterFall itself retracts naturally.
                 */
                waterSetFlow[0] = 0.0f;
                waterSetFlow[1] = 0.0f;
                outletLag = 0.0f;
            }

            case Broken -> {
            }
        }
    }

    private void updateWaterFalls() {
        for (int i = 0; i < 2; i++) {
            float lastFlow = waterFlow[i];

            if (waterTopY[i] == WATERFALL_START_Y) {
                waterVisualDensity[i] =
                        lerp(
                                waterVisualDensity[i],
                                waterFlow[i],
                                0.1f
                        );
            }

            if (
                    waterTopY[i] == WATERFALL_START_Y
                            || waterTopY[i] <= WATERFALL_BOTTOM_Y
            ) {
                waterFlow[i] = waterSetFlow[i];
            }

            waterBottomY[i] += waterBottomVelocity[i];
            waterBottomVelocity[i] -= WATERFALL_GRAVITY;

            if (waterBottomY[i] < WATERFALL_BOTTOM_Y) {
                waterBottomY[i] = WATERFALL_BOTTOM_Y;
                waterBottomVelocity[i] = 0.0f;
            }

            if (waterFlow[i] == 0.0f) {
                waterTopY[i] += waterTopVelocity[i];
                waterTopVelocity[i] -= WATERFALL_GRAVITY;

                if (waterTopY[i] < WATERFALL_BOTTOM_Y) {
                    waterTopY[i] = WATERFALL_BOTTOM_Y;
                    waterTopVelocity[i] = 0.0f;
                    waterVisualDensity[i] = 0.0f;
                }
            } else {
                waterTopY[i] = WATERFALL_START_Y;
                waterTopVelocity[i] = 0.0f;

                if (lastFlow == 0.0f) {
                    waterBottomY[i] = WATERFALL_START_Y;
                    waterBottomVelocity[i] = 0.0f;
                }
            }
        }
    }

    private void updateElectricGate() {
        /*
         * ElectricGate.cs applies the old lampsOn state to LightSource objects
         * before it changes lampsOn for the next source step.
         */
        for (int i = 0; i < lampsOn.length; i++) {
            lampVisible[i] = lampsOn[i];
        }

        batteryChanging = false;

        for (int i = 0; i < lampsOn.length; i++) {
            if (random.nextFloat() < 1.0f / 60.0f) {
                lampsOn[i] = false;
            }
        }

        switch (mode) {
            case MiddleClosed -> {
                boolean firstHalf =
                        lampBlink % 20 < 10;

                if (startCounterSide1 > 0) {
                    lampsOn[1] = firstHalf;
                    lampsOn[2] = !firstHalf;
                } else {
                    lampsOn[1] = false;
                    lampsOn[2] = false;
                }

                if (startCounterSide2 > 0) {
                    lampsOn[0] = firstHalf;
                    lampsOn[3] = !firstHalf;
                } else {
                    lampsOn[0] = false;
                    lampsOn[3] = false;
                }
            }

            case ClosingAirLock, OpeningMiddle ->
                    batteryRunning(1.0f);

            case Waiting ->
                    washingCounter++;

            case MiddleOpen -> {
                if (entrySide == null) {
                    clearLogicalLamps();
                    break;
                }

                for (int l = 0; l < 4; l++) {
                    int mapped =
                            entrySide == Side.SIDE1
                                    ? l
                                    : 3 - l;

                    lampsOn[mapped] =
                            lampBlink % 40 >= l * 10
                                    && lampBlink % 40 < (l + 1) * 10;
                }
            }

            case ClosingMiddle, OpeningSide -> {
                batteryRunning(1.0f);

                boolean on =
                        lampBlink % 20 < 10;

                for (int i = 0; i < lampsOn.length; i++) {
                    lampsOn[i] = on;
                }
            }

            case Closed -> {
                /*
                 * The source leaves the logical state latched here; the 1/60
                 * random-off checks above gradually extinguish it.
                 */
            }

            case Recharge -> {
                batteryChanging = false;
                clearLogicalLamps();
            }

            case Broken -> {
            }
        }
    }

    private void updateGraphicsControls() {
        /*
         * RegionGateGraphics.Update begins by updating all DoorGraphic objects.
         * That is where movementStalledByGraphicsModule is established.
         */
        updateDoorGraphics();

        boolean idleLike =
                mode == Mode.MiddleClosed
                        || mode == Mode.Closed
                        || mode == Mode.Recharge
                        || mode == Mode.Waiting
                        || mode == Mode.MiddleOpen;

        backgroundWorkingLoopVolume =
                lerp(
                        backgroundWorkingLoopVolume,
                        idleLike ? 0.0f : 1.0f,
                        0.05f
                );

        float steamTarget = 0.0f;

        if (gateType == GateType.WATER) {
            steamTarget = updateWaterGraphicsControls();
        } else if (gateType == GateType.ELECTRIC) {
            steamTarget = updateElectricGraphicsControls();
        }

        if (steamLoopVolume < steamTarget) {
            steamLoopVolume =
                    Math.min(
                            steamTarget,
                            steamLoopVolume + 1.0f / 30.0f
                    );
        } else {
            steamLoopVolume =
                    Math.max(
                            steamTarget,
                            steamLoopVolume - 0.1f
                    );
        }
    }

    private float updateWaterGraphicsControls() {
        float steamTarget = 0.0f;
        float loudestFlow = 0.0f;

        waterLevelDisplay =
                lerp(
                        waterLevelDisplay,
                        waterLeft,
                        0.02f
                );

        int selected =
                entrySide == null
                        ? -1
                        : effectIndex(entrySide);

        int lastHotHeater = -1;

        for (int i = 0; i < 2; i++) {
            heaterLastHeat[i] = heaterHeat[i];

            boolean heatWanted =
                    (mode == Mode.ClosingAirLock
                            || mode == Mode.Waiting)
                            && i == selected;

            if (heatWanted) {
                heaterTarget[i] =
                        Math.min(
                                heaterTarget[i] + 1.0f / 600.0f,
                                1.0f
                        );
            } else {
                heaterTarget[i] =
                        Math.max(
                                heaterTarget[i] - 1.0f / 600.0f,
                                0.0f
                        );
            }

            heaterHeat[i] =
                    lerp(
                            heaterHeat[i],
                            heaterTarget[i],
                            0.7f
                    );

            if (
                    waterFlow[i] > 0.2f
                            && waterFlow[i] > loudestFlow
            ) {
                loudestFlow = waterFlow[i];
            }

            if (heaterHeat[i] > 0.0f) {
                if (waterfallIntersectsHeater(i)) {
                    steamTarget = 1.0f;

                    if (
                            Math.pow(random.nextFloat(), 2.0)
                                    < waterFlow[i] * 2.0f
                                    && Math.pow(random.nextFloat(), 2.0)
                                    < heaterTarget[i] * 2.0f
                    ) {
                        float intensity =
                                (float) Math.pow(
                                        heaterTarget[i],
                                        0.75
                                );

                        emitSteamPuff(i, intensity);

                        heaterHeat[i] =
                                Math.min(
                                        heaterHeat[i],
                                        (1.0f
                                                - random.nextFloat()
                                                * waterFlow[i])
                                                * heaterTarget[i]
                                );
                    }
                }

                lastHotHeater = i;
            }
        }

        waterfallLoopVolume =
                lerp(
                        waterfallLoopVolume,
                        (float) Math.pow(loudestFlow, 0.5),
                        0.1f
                );

        activeHeaterLight = lastHotHeater;

        if (activeHeaterLight < 0) {
            heaterLightAlpha = 0.0f;
            heaterLightRadius = 0.0f;
        } else {
            float h = heaterHeat[activeHeaterLight];

            heaterLightAlpha =
                    (float) Math.pow(
                            inverseLerp(0.05f, 0.5f, h)
                                    * (0.8f
                                    + 0.2f
                                    * random.nextFloat()),
                            0.75
                    );

            heaterLightRadius =
                    lerp(
                            200.0f,
                            300.0f,
                            (float) Math.sin(Math.PI * h)
                    );
        }

        return steamTarget;
    }

    private float updateElectricGraphicsControls() {
        if (
                mode == Mode.Waiting
                        && washingCounter > 0
        ) {
            electricSteam =
                    Math.min(
                            1.0f,
                            electricSteam + 0.025f
                    );
        } else {
            electricSteam =
                    Math.max(
                            0.0f,
                            electricSteam - 0.025f
                    );
        }

        float steamTarget =
                electricSteam > 0.5f
                        ? 1.0f
                        : 0.0f;

        if (
                Math.pow(random.nextFloat(), 1.5)
                        < electricSteam * 2.0f
                && entrySide != null
        ) {
            emitSteamPuff(
                    effectIndex(entrySide),
                    (float) Math.pow(
                            electricSteam,
                            0.75
                    )
            );
        }

        return steamTarget;
    }

    /*
     * Timing-only port of RegionGateGraphics.DoorGraphic.Update + Clamp.Update.
     * The Minecraft gate renderer does not need Rain World's procedural pieces;
     * these classes exist solely so closedFac takes exactly the same path/time.
     */
    private void initialiseDoorMechanics() {
        for (int i = 0; i < 3; i++) {
            doorMechanics[i] =
                    new DoorMechanics(
                            doorClosed[i],
                            random
                    );
        }
    }

    private void updateDoorGraphics() {
        for (int doorIndex = 0; doorIndex < 3; doorIndex++) {
            DoorMechanics mechanics =
                    doorMechanics[doorIndex];

            if (mechanics == null) {
                mechanics =
                        new DoorMechanics(
                                doorClosed[doorIndex],
                                random
                        );
                doorMechanics[doorIndex] = mechanics;
            }

            float closed = doorClosed[doorIndex];

            if (
                    closed > CLAMPS_PAUSE
                            && mechanics.lastClosedFac <= CLAMPS_PAUSE
            ) {
                mechanics.wantedClampStatus = 1;
            } else if (
                    closed < CLAMPS_PAUSE
                            && mechanics.lastClosedFac >= CLAMPS_PAUSE
            ) {
                mechanics.wantedClampStatus = -1;
            }

            mechanics.lastClosedFac = closed;

            int clampsStatus = -2;
            int movingCount = 0;

            for (int side = 0; side < 2; side++) {
                for (int number = 0; number < 9; number++) {
                    updateClamp(
                            mechanics,
                            side,
                            number,
                            mechanics.wantedClampStatus
                    );

                    ClampMode clampMode =
                            mechanics.clamps[side][number].mode;

                    int status;

                    if (clampMode == ClampMode.Stacked) {
                        status = -1;
                    } else if (clampMode == ClampMode.Locked) {
                        status = 1;
                    } else {
                        status = 0;
                        movingCount++;
                    }

                    if (clampsStatus == -2) {
                        clampsStatus = status;
                    } else if (status != clampsStatus) {
                        clampsStatus = 0;
                    }
                }
            }

            mechanics.clampsStatus = clampsStatus;
            mechanics.movingClampCount = movingCount;
            mechanics.stalled =
                    clampsStatus
                            != mechanics.wantedClampStatus;

            mechanics.sourceUpdates++;

            if (mechanics.stalled) {
                mechanics.stalledUpdates++;
            }
        }
    }

    private void updateClamp(
            DoorMechanics mechanics,
            int side,
            int number,
            int desiredPosition
    ) {
        ClampState clamp =
                mechanics.clamps[side][number];

        ClampState previous =
                number > 0
                        ? mechanics.clamps[side][number - 1]
                        : null;

        ClampState next =
                number < 8
                        ? mechanics.clamps[side][number + 1]
                        : null;

        ClampState partner =
                side == 1
                        ? mechanics.clamps[0][number]
                        : null;

        if (
                desiredPosition == -1
                        && clamp.mode != ClampMode.Stacked
        ) {
            clamp.mode = ClampMode.MovingUp;
        } else if (
                desiredPosition == 1
                        && clamp.mode != ClampMode.Locked
                        && clamp.mode
                        != ClampMode.WaitingForPartner
                        && (
                        next == null
                                || next.posY
                                < clamp.stackHeight - 30.0f
                )
        ) {
            clamp.mode = ClampMode.MovingDown;
        }

        switch (clamp.mode) {
            case Locked -> {
                /*
                 * Depth/rotation do not feed back into timing.
                 */
            }

            case Stacked -> {
                clamp.velY = 0.0f;
                clamp.posY =
                        lerp(
                                clamp.posY,
                                clampStackPosY(
                                        mechanics,
                                        side,
                                        number
                                ),
                                0.3f
                        );
            }

            case MovingDown -> {
                clamp.posY += clamp.velY;
                clamp.velY *= clamp.fric;
                clamp.velY -= 0.5f;

                if (clamp.posY < clamp.clampHeight) {
                    clamp.posY = clamp.clampHeight;
                    clamp.velY = 0.0f;
                    clamp.mode =
                            ClampMode.WaitingForPartner;
                } else if (
                        next != null
                                && clamp.posY
                                < next.posY + 20.0f
                ) {
                    next.velY += clamp.velY;
                    clamp.velY = 0.0f;
                    clamp.posY = next.posY + 20.0f;
                }
            }

            case WaitingForPartner -> {
                if (
                        partner != null
                                && partner.mode
                                == ClampMode.WaitingForPartner
                ) {
                    clamp.lockCounter++;

                    if (clamp.lockCounter > 10) {
                        clamp.mode = ClampMode.Locked;
                        partner.mode = ClampMode.Locked;
                        clamp.lockCounter = 0;
                    }
                }
            }

            case MovingUp -> {
                clamp.posY += clamp.velY;
                clamp.velY *= clamp.fric;
                clamp.velY =
                        lerp(
                                clamp.velY,
                                3.6f,
                                0.2f
                        );

                if (clamp.posY > clamp.stackHeight) {
                    clamp.mode = ClampMode.Stacked;
                } else if (
                        previous != null
                                && previous.mode
                                != ClampMode.Stacked
                                && clamp.posY
                                > previous.posY - 20.0f
                ) {
                    previous.velY += clamp.velY;
                    clamp.velY *= 0.2f;
                    clamp.posY = previous.posY - 20.0f;
                }
            }
        }
    }

    private float clampStackPosY(
            DoorMechanics mechanics,
            int side,
            int number
    ) {
        ClampState clamp =
                mechanics.clamps[side][number];

        int count = 0;

        for (int n = number + 1; n < 9; n++) {
            ClampState next =
                    mechanics.clamps[side][n];

            if (next.mode != ClampMode.Stacked) {
                break;
            }

            count++;
        }

        return clamp.stackHeight + 6.0f * count;
    }

    private void updateDoors() {
        for (int i = 0; i < doorClosed.length; i++) {
            DoorMechanics mechanics =
                    doorMechanics[i];

            if (
                    mechanics != null
                            && mechanics.stalled
            ) {
                continue;
            }

            if (doorClosed[i] > doorGoal[i]) {
                doorClosed[i] =
                        Math.max(
                                0.0f,
                                doorClosed[i]
                                        - DOOR_OPEN_SPEED
                        );
            } else if (doorClosed[i] < doorGoal[i]) {
                doorClosed[i] =
                        Math.min(
                                1.0f,
                                doorClosed[i]
                                        + DOOR_CLOSE_SPEED
                        );
            }

            if (
                    Math.abs(
                            doorClosed[i]
                                    - doorGoal[i]
                    ) <= 1.0e-6f
            ) {
                doorClosed[i] = doorGoal[i];
            }
        }
    }

    private void updateReusableLifecycle() {
        if (mode == Mode.Closed) {
            cooldownCounter++;

            if (
                    cooldownCounter
                            >= COOLDOWN_SOURCE_STEPS
            ) {
                beginRecharge();
            }

            return;
        }

        if (mode != Mode.Recharge) {
            return;
        }

        rechargeCounter++;

        float progress =
                RECHARGE_SOURCE_STEPS <= 0
                        ? 1.0f
                        : clamp01(
                        rechargeCounter
                                / (float)
                                RECHARGE_SOURCE_STEPS
                );

        if (gateType == GateType.WATER) {
            waterLeft =
                    lerp(
                            rechargeStartResource,
                            1.0f,
                            progress
                    );
        } else if (gateType == GateType.ELECTRIC) {
            batteryLeft =
                    lerp(
                            rechargeStartResource,
                            1.0f,
                            progress
                    );
        }

        if (
                rechargeCounter
                        >= RECHARGE_SOURCE_STEPS
        ) {
            finishRecharge();
        }
    }

    private void beginRecharge() {
        mode = Mode.Recharge;
        rechargeCounter = 0;

        rechargeStartResource =
                gateType == GateType.WATER
                        ? waterLeft
                        : batteryLeft;

        dontOpen = true;

        startCounterSide1 = 0;
        startCounterSide2 = 0;
        startOrderSide1 = 0L;
        startOrderSide2 = 0L;

        washingCounter = 0;
        outletLag = 0.0f;

        waterSetFlow[0] = 0.0f;
        waterSetFlow[1] = 0.0f;

        batteryChanging = false;
        clearLogicalLamps();

        KarmaGateMod.LOGGER.info(
                "[GateCtrl @{}] Closed -> Recharge "
                        + "(start resource={})",
                controllerBE.getPos(),
                rechargeStartResource
        );
    }

    private void finishRecharge() {
        if (gateType == GateType.WATER) {
            waterLeft = 1.0f;
        } else if (gateType == GateType.ELECTRIC) {
            batteryLeft = 1.0f;
        }

        mode = Mode.MiddleClosed;
        entrySide = null;
        dontOpen = false;

        startCounterSide1 = 0;
        startCounterSide2 = 0;
        startOrderSide1 = 0L;
        startOrderSide2 = 0L;
        nextStartOrder = 1L;

        washingCounter = 0;
        cooldownCounter = 0;
        rechargeCounter = 0;
        rechargeStartResource = 1.0f;

        outletLag = 0.0f;
        waterSetFlow[0] = 0.0f;
        waterSetFlow[1] = 0.0f;

        electricSteam = 0.0f;
        steamLoopVolume = 0.0f;
        waterfallLoopVolume = 0.0f;
        backgroundWorkingLoopVolume = 0.0f;

        clearLogicalLamps();
        clearVisibleLamps();

        /*
         * The door geometry/mechanics are already in the correct reusable
         * layout after OpeningSide:
         * D0 open/Stacked, D1 closed/Locked, D2 open/Stacked.
         */
        doorGoal[DOOR_SIDE1] = 0.0f;
        doorGoal[DOOR_MIDDLE] = 1.0f;
        doorGoal[DOOR_SIDE2] = 0.0f;

        KarmaGateMod.LOGGER.info(
                "[GateCtrl @{}] Recharge -> MiddleClosed",
                controllerBE.getPos()
        );
    }

    private Detection detectPlayers(
            World world,
            BlockPos pos,
            BlockState state
    ) {
        updateStationaryPlayers(world);

        Direction.Axis axis =
                state.get(KarmaGateBlock.AXIS);

        double centerX = pos.getX() + 0.5;
        double centerZ = pos.getZ() + 0.5;

        /*
         * Preserve the controller's existing gate-space origin.
         */
        double axisCenter =
                axis == Direction.Axis.X
                        ? pos.getX() - 0.5
                        : pos.getZ() - 0.5;

        double gate1Coordinate =
                gate1 == null
                        ? Double.NaN
                        : axisCoordinate(gate1, axis)
                        + 0.5;

        double gate2Coordinate =
                gate2 == null
                        ? Double.NaN
                        : axisCoordinate(gate2, axis)
                        + 0.5;

        Region side1 =
                makeSideRegion(
                        axis,
                        centerX,
                        centerZ,
                        axisCenter,
                        gate1Coordinate,
                        FALLBACK_SIDE_OFFSET_NEGATIVE
                );

        Region side2 =
                makeSideRegion(
                        axis,
                        centerX,
                        centerZ,
                        axisCenter,
                        gate2Coordinate,
                        FALLBACK_SIDE_OFFSET_POSITIVE
                );

        double centerHalfAxis =
                Math.max(
                        2.5,
                        Math.min(
                                side1.axisHalf(),
                                side2.axisHalf()
                        ) - 2.0
                );

        Region center =
                axis == Direction.Axis.X
                        ? new Region(
                        centerX,
                        centerZ,
                        centerHalfAxis,
                        HALF_WIDTH,
                        centerHalfAxis
                )
                        : new Region(
                        centerX,
                        centerZ,
                        HALF_WIDTH,
                        centerHalfAxis,
                        centerHalfAxis
                );

        double corridorMin =
                !Double.isNaN(gate1Coordinate)
                        && !Double.isNaN(gate2Coordinate)
                        ? Math.min(
                        gate1Coordinate,
                        gate2Coordinate
                )
                        : axisCenter - 8.0;

        double corridorMax =
                !Double.isNaN(gate1Coordinate)
                        && !Double.isNaN(gate2Coordinate)
                        ? Math.max(
                        gate1Coordinate,
                        gate2Coordinate
                )
                        : axisCenter + 8.0;

        boolean in1 = false;
        boolean in2 = false;
        boolean inCenter = false;
        boolean anyInCorridor = false;

        boolean side1AllStationary = true;
        boolean side2AllStationary = true;

        boolean allThroughSide1 = true;
        boolean allThroughSide2 = true;

        for (PlayerEntity player : world.getPlayers()) {
            if (!playerEligible(player)) {
                continue;
            }

            boolean p1 =
                    isSideEnabled(Side.SIDE1)
                            && side1.contains(player);

            boolean p2 =
                    isSideEnabled(Side.SIDE2)
                            && side2.contains(player);

            boolean pc =
                    center.contains(player);

            int still =
                    stationarySteps.getOrDefault(
                            player.getUuid(),
                            0
                    );

            if (p1) {
                in1 = true;
                side1AllStationary &=
                        still >= STATIONARY_REQUIRED;
            }

            if (p2) {
                in2 = true;
                side2AllStationary &=
                        still >= STATIONARY_REQUIRED;
            }

            if (pc) {
                inCenter = true;
            }

            double along =
                    axis == Direction.Axis.X
                            ? player.getX()
                            : player.getZ();

            double across =
                    axis == Direction.Axis.X
                            ? player.getZ()
                            : player.getX();

            double acrossCenter =
                    axis == Direction.Axis.X
                            ? centerZ
                            : centerX;

            boolean insideCorridor =
                    along >= corridorMin
                            && along <= corridorMax
                            && Math.abs(across - acrossCenter)
                            <= HALF_WIDTH;

            if (!insideCorridor) {
                continue;
            }

            anyInCorridor = true;

            /*
             * Dynamic multiplayer traversal:
             * every currently-present player inside the gate corridor has to
             * clear the destination-side threshold. Players outside the two
             * outer doors no longer block the gate.
             */
            if (
                    along
                            <= axisCenter
                            + CROSSING_THRESHOLD
            ) {
                allThroughSide1 = false;
            }

            if (
                    along
                            >= axisCenter
                            - CROSSING_THRESHOLD
            ) {
                allThroughSide2 = false;
            }
        }

        boolean stationary1 =
                in1 && side1AllStationary;

        boolean stationary2 =
                in2 && side2AllStationary;

        return new Detection(
                in1,
                in2,
                inCenter,
                stationary1,
                stationary2,
                anyInCorridor,
                allThroughSide1,
                allThroughSide2
        );
    }

    private void updateStationaryPlayers(World world) {
        Set<UUID> seen = new HashSet<>();

        for (PlayerEntity player : world.getPlayers()) {
            if (!playerEligible(player)) {
                continue;
            }

            UUID id = player.getUuid();
            seen.add(id);

            Vec3d position = player.getPos();
            Vec3d previous =
                    previousPlayerPositions.put(
                            id,
                            position
                    );

            boolean still =
                    previous != null
                            && previous.squaredDistanceTo(position)
                            <= STATIONARY_DISTANCE_SQUARED;

            stationarySteps.put(
                    id,
                    still
                            ? Math.min(
                            120,
                            stationarySteps.getOrDefault(
                                    id,
                                    0
                            ) + RAIN_WORLD_STEPS_PER_MC_TICK
                    )
                            : 0
            );
        }

        previousPlayerPositions
                .keySet()
                .removeIf(id -> !seen.contains(id));

        stationarySteps
                .keySet()
                .removeIf(id -> !seen.contains(id));
    }

    private Region makeSideRegion(
            Direction.Axis axis,
            double centerX,
            double centerZ,
            double axisCenter,
            double gateCoordinate,
            double fallbackOffset
    ) {
        double halfAxis = HALF_WIDTH;
        double middle =
                axisCenter + fallbackOffset;

        if (!Double.isNaN(gateCoordinate)) {
            double usable =
                    Math.max(
                            0.0,
                            Math.abs(
                                    gateCoordinate
                                            - axisCenter
                            )
                                    - DETECTION_GATE_PADDING
                    );

            halfAxis =
                    Math.max(
                            3.0,
                            usable * 0.5
                    );

            middle =
                    axisCenter
                            + Math.signum(
                            gateCoordinate
                                    - axisCenter
                    )
                            * usable
                            * 0.5;
        }

        return axis == Direction.Axis.X
                ? new Region(
                middle,
                centerZ,
                halfAxis,
                HALF_WIDTH,
                halfAxis
        )
                : new Region(
                centerX,
                middle,
                HALF_WIDTH,
                halfAxis,
                halfAxis
        );
    }

    private void applyOutputs(World world) {
        if (world == null) {
            return;
        }

        /*
         * Doors are driven by the actual source closedFac, not merely open/
         * closed goals. Components should render/animate directly from this.
         */
        setGateDoorState(
                world,
                gate1,
                doorClosed[DOOR_SIDE1],
                doorGoal[DOOR_SIDE1],
                isDoorStalled(DOOR_SIDE1)
        );

        controllerBE.setGateDoorState(
                doorClosed[DOOR_MIDDLE],
                doorGoal[DOOR_MIDDLE],
                isDoorStalled(DOOR_MIDDLE)
        );

        setGateDoorState(
                world,
                gate2,
                doorClosed[DOOR_SIDE2],
                doorGoal[DOOR_SIDE2],
                isDoorStalled(DOOR_SIDE2)
        );

        float resource =
                gateType == GateType.WATER
                        ? waterLeft
                        : batteryLeft;

        controllerBE.setGateResourceState(
                gateType,
                resource,
                batteryChanging,
                mode == Mode.Recharge,
                rechargeProgress()
        );

        setBatteryMeterState(
                world,
                gateType == GateType.ELECTRIC,
                batteryLeft,
                batteryChanging
        );

        if (gateType == GateType.WATER) {
            applyWaterOutputs(world);
            lightsSide1.allOff(world);
            lightsSide2.allOff(world);
        } else if (gateType == GateType.ELECTRIC) {
            applyElectricOutputs(world);
        } else {
            stopAllEffects(world);
            lightsSide1.allOff(world);
            lightsSide2.allOff(world);
        }

        applyHologramControls(world);
    }

    private void applyWaterOutputs(World world) {
        setWaterState(
                world,
                waterSide1,
                0
        );

        setWaterState(
                world,
                waterSide2,
                1
        );

        setHeaterState(
                world,
                heatSide1,
                0
        );

        setHeaterState(
                world,
                heatSide2,
                1
        );

        applySteamOutputs(world);
    }

    private void applyElectricOutputs(World world) {
        /*
         * No water/heater output on an electric gate.
         */
        setWaterOff(world, waterSide1);
        setWaterOff(world, waterSide2);
        setHeaterOff(world, heatSide1);
        setHeaterOff(world, heatSide2);

        applySteamOutputs(world);

        boolean[] side1 =
                physicalLampState(Side.SIDE1);

        boolean[] side2 =
                physicalLampState(Side.SIDE2);

        lightsSide1.setPhysicalStates(
                world,
                side1[0],
                side1[1],
                side1[2],
                side1[3]
        );

        lightsSide2.setPhysicalStates(
                world,
                side2[0],
                side2[1],
                side2[2],
                side2[3]
        );
    }

    private void applySteamOutputs(World world) {
        int activeSide =
                entrySide == null
                        ? -1
                        : effectIndex(entrySide);

        float continuousSide1 =
                activeSide == 0
                        ? steamLoopVolume
                        : 0.0f;

        float continuousSide2 =
                activeSide == 1
                        ? steamLoopVolume
                        : 0.0f;

        setSteamState(
                world,
                steamSide1,
                continuousSide1,
                pendingSteamPuffs[0],
                pendingSteamIntensity[0]
        );

        setSteamState(
                world,
                steamSide2,
                continuousSide2,
                pendingSteamPuffs[1],
                pendingSteamIntensity[1]
        );
    }

    private boolean[] physicalLampState(Side side) {
        boolean[] output =
                new boolean[4];

        /*
         * Startup is chamber-local and diagonal:
         *   Side 1: 1a + 1d, then 1b + 1c
         *   Side 2: 2a + 2d, then 2b + 2c
         *
         * Both sides can flash simultaneously while racing to start.
         */
        if (mode == Mode.MiddleClosed) {
            boolean active =
                    startupCounter(side) > 0;

            if (!active) {
                return output;
            }

            boolean phaseAD =
                    side == Side.SIDE1
                            ? lampVisible[1]
                            : lampVisible[0];

            boolean phaseBC =
                    side == Side.SIDE1
                            ? lampVisible[2]
                            : lampVisible[3];

            output[0] = phaseAD; // a
            output[1] = phaseBC; // b
            output[2] = phaseBC; // c
            output[3] = phaseAD; // d

            return output;
        }

        /*
         * Preserve the winning startup pattern locally while the entry outer
         * door initially closes.
         */
        if (
                mode == Mode.ClosingAirLock
                        && entrySide != null
        ) {
            if (side != entrySide) {
                return output;
            }

            boolean phaseAD =
                    side == Side.SIDE1
                            ? lampVisible[1]
                            : lampVisible[0];

            boolean phaseBC =
                    side == Side.SIDE1
                            ? lampVisible[2]
                            : lampVisible[3];

            output[0] = phaseAD;
            output[1] = phaseBC;
            output[2] = phaseBC;
            output[3] = phaseAD;

            return output;
        }

        if (
                mode == Mode.Recharge
                        || mode == Mode.Broken
        ) {
            return output;
        }

        /*
         * Normal 3-D offset mapping:
         *
         * Side 1:
         *   a = source lamp 1
         *   b = source lamp 2
         *   c = source lamp 3
         *   d = source lamp 0
         *
         * Side 2:
         *   a = source lamp 0
         *   b = source lamp 3
         *   c = source lamp 2
         *   d = source lamp 1
         */
        if (side == Side.SIDE1) {
            output[0] = lampVisible[1];
            output[1] = lampVisible[2];
            output[2] = lampVisible[3];
            output[3] = lampVisible[0];
        } else {
            output[0] = lampVisible[0];
            output[1] = lampVisible[3];
            output[2] = lampVisible[2];
            output[3] = lampVisible[1];
        }

        return output;
    }

    private void applyHologramControls(World world) {
        /*
         * GateKarmaGlyph is a stateful 40 Hz cosmetic object. Do not collapse
         * its inputs into a binary visibility value here: the client needs the
         * actual gate fields to reproduce goalFade, fade and flicker.
         */
        boolean closedLike =
                mode == Mode.MiddleClosed
                        || mode == Mode.Closed
                        || mode == Mode.Recharge
                        || mode == Mode.Broken;

        boolean anyLogicalLamp = false;
        for (boolean lampOn : lampsOn) {
            if (lampOn) {
                anyLogicalLamp = true;
                break;
            }
        }

        int activeStartCounter =
                entrySide == null
                        ? 0
                        : startupCounter(entrySide);

        /*
         * Closed and Recharge are the Minecraft reusable-gate lifecycle. Once
         * a cycle has completed, keep GateKarmaGlyph in its no-energy state
         * for the whole cooldown/recharge, even when the resource crosses the
         * source's 50% opening threshold. It becomes ready only after the
         * lifecycle has fully reset the controller to MiddleClosed.
         */
        boolean resetComplete =
                mode != Mode.Closed
                        && mode != Mode.Recharge;
        boolean hasEnergy =
                resetComplete
                        && energyEnoughToOpen();

        setHologramGateStateForSide(
                world,
                Side.SIDE1,
                closedLike,
                entrySide == Side.SIDE1,
                activeStartCounter,
                anyLogicalLamp,
                hasEnergy
        );

        setHologramGateStateForSide(
                world,
                Side.SIDE2,
                closedLike,
                entrySide == Side.SIDE2,
                activeStartCounter,
                anyLogicalLamp,
                hasEnergy
        );
    }

    private void setHologramGateStateForSide(
            World world,
            Side side,
            boolean closedLike,
            boolean sideSelected,
            int startCounter,
            boolean lampsActive,
            boolean hasEnergy
    ) {
        List<BlockPos> positions =
                side == Side.SIDE1
                        ? hologramSide1
                        : hologramSide2;

        for (BlockPos pos : positions) {
            if (
                    world.getBlockEntity(pos)
                            instanceof HologramProjectorBlockEntity hologram
            ) {
                hologram.setGateGlyphState(
                        gateType,
                        closedLike,
                        sideSelected,
                        startCounter,
                        lampsActive,
                        hasEnergy,
                        false
                );
            }
        }
    }

    private boolean energyEnoughToOpen() {
        return gateType == GateType.WATER
                ? waterLeft > 0.5f
                : gateType == GateType.ELECTRIC
                && batteryLeft > 0.5f;
    }

    private GateType inferGateType() {
        boolean hasLights =
                !lightsSide1.getRefs().isEmpty()
                        || !lightsSide2.getRefs().isEmpty();

        boolean hasSteam =
                !steamSide1.isEmpty()
                        || !steamSide2.isEmpty();

        boolean hasWater =
                !waterSide1.isEmpty()
                        || !waterSide2.isEmpty();

        boolean hasHeaters =
                !heatSide1.isEmpty()
                        || !heatSide2.isEmpty();

        boolean electric =
                hasLights && hasSteam;

        boolean water =
                hasWater && hasHeaters;

        return electric == water
                ? GateType.BROKEN
                : electric
                ? GateType.ELECTRIC
                : GateType.WATER;
    }

    private float waterPressure() {
        return (float) Math.pow(
                inverseLerp(
                        0.0f,
                        0.5f,
                        waterLeft
                ),
                0.6
        );
    }

    private void waterRunning(float flow) {
        waterLeft =
                Math.max(
                        0.0f,
                        waterLeft - flow / 1450.0f
                );
    }

    private void batteryRunning(float flow) {
        batteryLeft =
                Math.max(
                        0.0f,
                        batteryLeft - flow / 1300.0f
                );

        batteryChanging = true;
    }

    private boolean waterfallIntersectsHeater(int side) {
        return waterTopY[side] > WATERFALL_HEATER_Y
                && waterBottomY[side] < WATERFALL_HEATER_Y;
    }

    private void emitSteamPuff(
            int side,
            float intensity
    ) {
        if (side < 0 || side >= 2) {
            return;
        }

        pendingSteamPuffs[side]++;
        pendingSteamIntensity[side] =
                Math.max(
                        pendingSteamIntensity[side],
                        clamp01(intensity)
                );
    }

    private boolean allDoorsInPosition() {
        for (int i = 0; i < doorClosed.length; i++) {
            if (
                    Math.abs(
                            doorClosed[i]
                                    - doorGoal[i]
                    ) > 1.0e-6f
            ) {
                return false;
            }
        }

        return true;
    }

    private boolean isDoorStalled(int door) {
        DoorMechanics mechanics =
                door >= 0
                        && door < doorMechanics.length
                        ? doorMechanics[door]
                        : null;

        return mechanics != null
                && mechanics.stalled;
    }

    private int washIndex(Side side) {
        return effectIndex(side);
    }

    private int outletIndex(Side side) {
        return 1 - effectIndex(side);
    }

    private static int effectIndex(Side side) {
        return side == Side.SIDE1
                ? 0
                : 1;
    }

    private static int outerDoorIndex(Side side) {
        return side == Side.SIDE1
                ? DOOR_SIDE1
                : DOOR_SIDE2;
    }

    private float rechargeProgress() {
        if (mode != Mode.Recharge) {
            return 0.0f;
        }

        return RECHARGE_SOURCE_STEPS <= 0
                ? 1.0f
                : clamp01(
                rechargeCounter
                        / (float)
                        RECHARGE_SOURCE_STEPS
        );
    }

    private static double axisCoordinate(
            BlockPos pos,
            Direction.Axis axis
    ) {
        return axis == Direction.Axis.X
                ? pos.getX()
                : pos.getZ();
    }

    private static boolean playerEligible(
            PlayerEntity player
    ) {
        return !player.isSpectator();
    }

    private boolean isSideEnabled(Side side) {
        return side == Side.SIDE1
                ? karmaSide1 != KarmaLevel.LEVEL_D
                : karmaSide2 != KarmaLevel.LEVEL_D;
    }

    /*
     * ---------------------------------------------------------------------
     * Component output API
     * ---------------------------------------------------------------------
     *
     * These calls intentionally do NOT match the old component methods.
     * Change the components to consume the richer gate state instead of
     * flattening this controller back to setOpen()/setTargetFlow()/etc.
     */

    private static void setGateDoorState(
            World world,
            BlockPos pos,
            float closedFac,
            float goalClosedFac,
            boolean stalled
    ) {
        if (pos == null) {
            return;
        }

        if (
                world.getBlockEntity(pos)
                        instanceof KarmaGateBlockEntity gate
        ) {
            gate.setGateDoorState(
                    closedFac,
                    goalClosedFac,
                    stalled
            );
        }
    }

    private void setWaterState(
            World world,
            List<BlockPos> positions,
            int side
    ) {
        float topProgress =
                inverseLerp(
                        WATERFALL_BOTTOM_Y,
                        WATERFALL_START_Y,
                        waterTopY[side]
                );

        float bottomProgress =
                inverseLerp(
                        WATERFALL_BOTTOM_Y,
                        WATERFALL_START_Y,
                        waterBottomY[side]
                );

        for (BlockPos pos : positions) {
            if (
                    world.getBlockEntity(pos)
                            instanceof WaterStreamBlockEntity stream
            ) {
                stream.setGateWaterState(
                        waterSetFlow[side],
                        waterFlow[side],
                        waterVisualDensity[side],
                        topProgress,
                        bottomProgress
                );
            }
        }
    }

    private static void setWaterOff(
            World world,
            List<BlockPos> positions
    ) {
        for (BlockPos pos : positions) {
            if (
                    world.getBlockEntity(pos)
                            instanceof WaterStreamBlockEntity stream
            ) {
                stream.setGateWaterState(
                        0.0f,
                        0.0f,
                        0.0f,
                        0.0f,
                        0.0f
                );
            }
        }
    }

    private void setHeaterState(
            World world,
            List<BlockPos> positions,
            int side
    ) {
        float lightAlpha =
                activeHeaterLight == side
                        ? heaterLightAlpha
                        : 0.0f;

        float lightRadius =
                activeHeaterLight == side
                        ? heaterLightRadius
                        : 0.0f;

        float distortionAlpha =
                activeHeaterLight == side
                        ? sCurve(
                                heaterTarget[side],
                                1.5f
                        )
                        : 0.0f;

        for (BlockPos pos : positions) {
            if (
                    world.getBlockEntity(pos)
                            instanceof HeatCoilBlockEntity coil
            ) {
                coil.setGateHeatState(
                        heaterHeat[side],
                        heaterTarget[side],
                        lightAlpha,
                        lightRadius,
                        distortionAlpha,
                        pendingSteamPuffs[side],
                        pendingSteamIntensity[side]
                );
            }
        }
    }

    private static void setHeaterOff(
            World world,
            List<BlockPos> positions
    ) {
        for (BlockPos pos : positions) {
            if (
                    world.getBlockEntity(pos)
                            instanceof HeatCoilBlockEntity coil
            ) {
                coil.setGateHeatState(
                        0.0f,
                        0.0f,
                        0.0f,
                        0.0f,
                        0.0f
                );
            }
        }
    }

    private static void setSteamState(
            World world,
            List<BlockPos> positions,
            float continuousLevel,
            int puffCount,
            float puffIntensity
    ) {
        for (BlockPos pos : positions) {
            if (
                    world.getBlockEntity(pos)
                            instanceof SteamEmitterBlockEntity emitter
            ) {
                emitter.setGateSteamState(
                        continuousLevel,
                        puffCount,
                        puffIntensity
                );
            }
        }
    }

    private void setBatteryMeterState(
            World world,
            boolean electric,
            float charge,
            boolean changing
    ) {
        for (BlockPos pos : batteryMeters) {
            if (
                    world.getBlockEntity(pos)
                            instanceof BatteryMeterBlockEntity meter
            ) {
                meter.setGateBatteryState(
                        electric,
                        charge,
                        changing
                );
            }
        }
    }

    private void stopAllEffects(World world) {
        setWaterOff(world, waterSide1);
        setWaterOff(world, waterSide2);

        setHeaterOff(world, heatSide1);
        setHeaterOff(world, heatSide2);

        setSteamState(
                world,
                steamSide1,
                0.0f,
                0,
                0.0f
        );

        setSteamState(
                world,
                steamSide2,
                0.0f,
                0,
                0.0f
        );
    }

    public List<BlockPos> getWaterSide1() {
        return waterSide1;
    }

    public List<BlockPos> getWaterSide2() {
        return waterSide2;
    }

    public List<BlockPos> getHeatSide1() {
        return heatSide1;
    }

    public List<BlockPos> getHeatSide2() {
        return heatSide2;
    }

    public List<BlockPos> getHologramSide1() {
        return hologramSide1;
    }

    public List<BlockPos> getHologramSide2() {
        return hologramSide2;
    }

    public KarmaLevel getKarmaSide1() {
        return karmaSide1;
    }

    public KarmaLevel getKarmaSide2() {
        return karmaSide2;
    }

    public GateType getGateType() {
        return gateType;
    }

    public float getWaterLeft() {
        return waterLeft;
    }

    public float getWaterLevelDisplay() {
        return waterLevelDisplay;
    }

    public float getBatteryLeft() {
        return batteryLeft;
    }

    public boolean isBatteryChanging() {
        return batteryChanging;
    }

    public float getBackgroundWorkingLoopVolume() {
        return backgroundWorkingLoopVolume;
    }

    public float getWaterfallLoopVolume() {
        return waterfallLoopVolume;
    }

    public float getSteamLoopVolume() {
        return steamLoopVolume;
    }

    public void setKarmaSide1(
            World world,
            KarmaLevel level
    ) {
        setKarmaForSide(
                world,
                Side.SIDE1,
                level
        );
    }

    public void setKarmaSide2(
            World world,
            KarmaLevel level
    ) {
        setKarmaForSide(
                world,
                Side.SIDE2,
                level
        );
    }

    public void reapplyKarma(World world) {
        applyKarmaToList(
                world,
                hologramSide1,
                karmaSide1
        );

        applyKarmaToList(
                world,
                hologramSide2,
                karmaSide2
        );
    }

    public void setHologramTargetLevelSide1(
            World world,
            float level
    ) {
        setHologramTargetLevelForSide(
                world,
                Side.SIDE1,
                level
        );
    }

    public void setHologramTargetLevelSide2(
            World world,
            float level
    ) {
        setHologramTargetLevelForSide(
                world,
                Side.SIDE2,
                level
        );
    }

    public void setHologramTargetLevels(
            World world,
            Float side1,
            Float side2
    ) {
        if (world == null) {
            return;
        }

        if (side1 != null) {
            setHologramTargetLevelForSide(
                    world,
                    Side.SIDE1,
                    side1
            );
        }

        if (side2 != null) {
            setHologramTargetLevelForSide(
                    world,
                    Side.SIDE2,
                    side2
            );
        }
    }

    private void setHologramTargetLevelForSide(
            World world,
            Side side,
            float level
    ) {
        if (world == null) {
            return;
        }

        List<BlockPos> positions =
                side == Side.SIDE1
                        ? hologramSide1
                        : hologramSide2;

        for (BlockPos pos : positions) {
            if (
                    world.getBlockEntity(pos)
                            instanceof HologramProjectorBlockEntity hologram
            ) {
                hologram.setTargetLevel(
                        clamp01(level)
                );
            }
        }
    }

    public void setHologramLowPowerSide1(
            World world,
            boolean lowPower
    ) {
        setHologramLowPowerForSide(
                world,
                Side.SIDE1,
                lowPower
        );
    }

    public void setHologramLowPowerSide2(
            World world,
            boolean lowPower
    ) {
        setHologramLowPowerForSide(
                world,
                Side.SIDE2,
                lowPower
        );
    }

    public void setHologramLowPower(
            World world,
            Boolean side1,
            Boolean side2
    ) {
        if (world == null) {
            return;
        }

        if (side1 != null) {
            setHologramLowPowerForSide(
                    world,
                    Side.SIDE1,
                    side1
            );
        }

        if (side2 != null) {
            setHologramLowPowerForSide(
                    world,
                    Side.SIDE2,
                    side2
            );
        }
    }

    private void setHologramLowPowerForSide(
            World world,
            Side side,
            boolean lowPower
    ) {
        List<BlockPos> positions =
                side == Side.SIDE1
                        ? hologramSide1
                        : hologramSide2;

        for (BlockPos pos : positions) {
            if (
                    world.getBlockEntity(pos)
                            instanceof HologramProjectorBlockEntity hologram
            ) {
                hologram.setLowpower(lowPower);
            }
        }
    }

    public void setKarma(
            BlockPos hologramPos,
            KarmaLevel level
    ) {
        World world = controllerBE.getWorld();

        if (world == null || level == null) {
            return;
        }

        Side side =
                hologramSide1.contains(hologramPos)
                        ? Side.SIDE1
                        : hologramSide2.contains(hologramPos)
                        ? Side.SIDE2
                        : classifySide(
                        hologramPos,
                        world
                );

        if (side == null) {
            return;
        }

        List<BlockPos> list =
                side == Side.SIDE1
                        ? hologramSide1
                        : hologramSide2;

        if (!list.contains(hologramPos)) {
            list.add(hologramPos);
        }

        setKarmaForSide(
                world,
                side,
                level
        );
    }

    private Side classifySide(
            BlockPos pos,
            World world
    ) {
        BlockState state =
                world.getBlockState(
                        controllerBE.getPos()
                );

        if (!(state.getBlock() instanceof KarmaGateBlock)) {
            return null;
        }

        Direction.Axis axis =
                state.get(KarmaGateBlock.AXIS);

        return axisCoordinate(pos, axis)
                < axisCoordinate(
                controllerBE.getPos(),
                axis
        )
                ? Side.SIDE1
                : Side.SIDE2;
    }

    private void setKarmaForSide(
            World world,
            Side side,
            KarmaLevel level
    ) {
        if (level == null) {
            return;
        }

        if (side == Side.SIDE1) {
            karmaSide1 = level;
        } else {
            karmaSide2 = level;
        }

        applyKarmaToList(
                world,
                side == Side.SIDE1
                        ? hologramSide1
                        : hologramSide2,
                level
        );

        controllerBE.markDirty();
    }

    private static void applyKarmaToList(
            World world,
            List<BlockPos> positions,
            KarmaLevel level
    ) {
        if (world == null) {
            return;
        }

        for (BlockPos pos : positions) {
            if (
                    world.getBlockEntity(pos)
                            instanceof HologramProjectorBlockEntity hologram
            ) {
                hologram.setKarmaLevelEnum(level);
            }
        }
    }

    public void writeNbt(NbtCompound nbt) {
        nbt.putInt(
                "controllerDataVersion",
                CONTROLLER_DATA_VERSION
        );

        writePos(nbt, "gate1", gate1);
        writePos(nbt, "gate2", gate2);

        nbt.putString(
                "gateType",
                gateType.name()
        );

        nbt.putString(
                "mode",
                mode.name()
        );

        nbt.putString(
                "entrySide",
                entrySide == null
                        ? "null"
                        : entrySide.name()
        );

        nbt.putBoolean(
                "dontOpen",
                dontOpen
        );

        nbt.putInt(
                "startCounterSide1",
                startCounterSide1
        );

        nbt.putInt(
                "startCounterSide2",
                startCounterSide2
        );

        nbt.putLong(
                "startOrderSide1",
                startOrderSide1
        );

        nbt.putLong(
                "startOrderSide2",
                startOrderSide2
        );

        nbt.putLong(
                "nextStartOrder",
                nextStartOrder
        );

        nbt.putInt(
                "washingCounter",
                washingCounter
        );

        nbt.putInt(
                "cooldownCounter",
                cooldownCounter
        );

        nbt.putInt(
                "rechargeCounter",
                rechargeCounter
        );

        nbt.putFloat(
                "rechargeStartResource",
                rechargeStartResource
        );

        nbt.putInt(
                "lampBlink",
                lampBlink
        );

        nbt.putFloat(
                "waterLeft",
                waterLeft
        );

        nbt.putFloat(
                "waterLevelDisplay",
                waterLevelDisplay
        );

        nbt.putFloat(
                "outletLag",
                outletLag
        );

        nbt.putFloat(
                "batteryLeft",
                batteryLeft
        );

        nbt.putBoolean(
                "batteryChanging",
                batteryChanging
        );

        nbt.putFloat(
                "electricSteam",
                electricSteam
        );

        nbt.putFloat(
                "backgroundWorkingLoopVolume",
                backgroundWorkingLoopVolume
        );

        nbt.putFloat(
                "waterfallLoopVolume",
                waterfallLoopVolume
        );

        nbt.putFloat(
                "steamLoopVolume",
                steamLoopVolume
        );

        nbt.putInt(
                "activeHeaterLight",
                activeHeaterLight
        );

        nbt.putFloat(
                "heaterLightAlpha",
                heaterLightAlpha
        );

        nbt.putFloat(
                "heaterLightRadius",
                heaterLightRadius
        );

        for (int i = 0; i < 3; i++) {
            nbt.putFloat(
                    "doorClosed" + i,
                    doorClosed[i]
            );

            nbt.putFloat(
                    "doorGoal" + i,
                    doorGoal[i]
            );
        }

        for (int i = 0; i < 2; i++) {
            nbt.putFloat(
                    "waterSetFlow" + i,
                    waterSetFlow[i]
            );

            nbt.putFloat(
                    "waterFlow" + i,
                    waterFlow[i]
            );

            nbt.putFloat(
                    "waterVisualDensity" + i,
                    waterVisualDensity[i]
            );

            nbt.putFloat(
                    "waterTopY" + i,
                    waterTopY[i]
            );

            nbt.putFloat(
                    "waterBottomY" + i,
                    waterBottomY[i]
            );

            nbt.putFloat(
                    "waterTopVelocity" + i,
                    waterTopVelocity[i]
            );

            nbt.putFloat(
                    "waterBottomVelocity" + i,
                    waterBottomVelocity[i]
            );

            nbt.putFloat(
                    "heaterHeat" + i,
                    heaterHeat[i]
            );

            nbt.putFloat(
                    "heaterLastHeat" + i,
                    heaterLastHeat[i]
            );

            nbt.putFloat(
                    "heaterTarget" + i,
                    heaterTarget[i]
            );
        }

        for (int i = 0; i < 4; i++) {
            nbt.putBoolean(
                    "lamp" + i,
                    lampsOn[i]
            );

            nbt.putBoolean(
                    "lampVisible" + i,
                    lampVisible[i]
            );
        }

        writeDoorMechanics(
                nbt,
                "doorMechanics"
        );

        lightsSide1.writeNbt(
                nbt,
                "lightsSide1"
        );

        lightsSide2.writeNbt(
                nbt,
                "lightsSide2"
        );

        writePosList(
                nbt,
                "waterSide1",
                waterSide1
        );

        writePosList(
                nbt,
                "waterSide2",
                waterSide2
        );

        writePosList(
                nbt,
                "heatSide1",
                heatSide1
        );

        writePosList(
                nbt,
                "heatSide2",
                heatSide2
        );

        writePosList(
                nbt,
                "steamSide1",
                steamSide1
        );

        writePosList(
                nbt,
                "steamSide2",
                steamSide2
        );

        writePosList(
                nbt,
                "holoSide1",
                hologramSide1
        );

        writePosList(
                nbt,
                "holoSide2",
                hologramSide2
        );

        writePosList(
                nbt,
                "batteryMeters",
                batteryMeters
        );

        nbt.putString(
                "karmaSide1",
                karmaSide1.name()
        );

        nbt.putString(
                "karmaSide2",
                karmaSide2.name()
        );
    }

    public void readNbt(NbtCompound nbt) {
        int dataVersion =
                nbt.contains("controllerDataVersion")
                        ? nbt.getInt(
                        "controllerDataVersion"
                )
                        : 0;

        gate1 = readPos(nbt, "gate1");
        gate2 = readPos(nbt, "gate2");

        boolean storedGateType =
                nbt.contains("gateType");

        gateType =
                readEnum(
                        nbt.getString("gateType"),
                        GateType.class,
                        GateType.BROKEN
                );

        mode =
                readEnum(
                        nbt.getString("mode"),
                        Mode.class,
                        Mode.MiddleClosed
                );

        entrySide =
                "null".equals(
                        nbt.getString("entrySide")
                )
                        ? null
                        : readEnum(
                        nbt.getString("entrySide"),
                        Side.class,
                        null
                );

        dontOpen =
                nbt.getBoolean("dontOpen");

        if (nbt.contains("startCounterSide1")) {
            startCounterSide1 =
                    nbt.getInt(
                            "startCounterSide1"
                    );

            startCounterSide2 =
                    nbt.getInt(
                            "startCounterSide2"
                    );
        } else {
            /*
             * Legacy single-counter migration.
             */
            int oldCounter =
                    nbt.contains("startCounter")
                            ? nbt.getInt(
                            "startCounter"
                    )
                            : Math.max(
                            nbt.getInt("prepare1"),
                            nbt.getInt("prepare2")
                    ) * 2;

            if (entrySide == Side.SIDE2) {
                startCounterSide1 = 0;
                startCounterSide2 = oldCounter;
            } else {
                startCounterSide1 = oldCounter;
                startCounterSide2 = 0;
            }
        }

        startOrderSide1 =
                nbt.contains("startOrderSide1")
                        ? nbt.getLong(
                        "startOrderSide1"
                )
                        : startCounterSide1 > 0
                        ? 1L
                        : 0L;

        startOrderSide2 =
                nbt.contains("startOrderSide2")
                        ? nbt.getLong(
                        "startOrderSide2"
                )
                        : startCounterSide2 > 0
                        ? 2L
                        : 0L;

        nextStartOrder =
                nbt.contains("nextStartOrder")
                        ? Math.max(
                        1L,
                        nbt.getLong(
                                "nextStartOrder"
                        )
                )
                        : Math.max(
                        startOrderSide1,
                        startOrderSide2
                ) + 1L;

        washingCounter =
                nbt.contains("washingCounter")
                        ? nbt.getInt(
                        "washingCounter"
                )
                        : nbt.getInt("washTicks") * 2;

        cooldownCounter =
                nbt.contains("cooldownCounter")
                        ? nbt.getInt(
                        "cooldownCounter"
                )
                        : nbt.getInt(
                        "recoveryCounter"
                );

        rechargeCounter =
                nbt.contains("rechargeCounter")
                        ? nbt.getInt(
                        "rechargeCounter"
                )
                        : 0;

        rechargeStartResource =
                nbt.contains("rechargeStartResource")
                        ? nbt.getFloat(
                        "rechargeStartResource"
                )
                        : nbt.contains(
                        "recoveryStartResource"
                )
                        ? nbt.getFloat(
                        "recoveryStartResource"
                )
                        : 1.0f;

        lampBlink =
                nbt.getInt("lampBlink");

        waterLeft =
                nbt.contains("waterLeft")
                        ? nbt.getFloat(
                        "waterLeft"
                )
                        : 1.0f;

        waterLevelDisplay =
                nbt.contains("waterLevelDisplay")
                        ? nbt.getFloat(
                        "waterLevelDisplay"
                )
                        : waterLeft;

        outletLag =
                nbt.getFloat("outletLag");

        batteryLeft =
                nbt.contains("batteryLeft")
                        ? nbt.getFloat(
                        "batteryLeft"
                )
                        : 1.0f;

        batteryChanging =
                nbt.getBoolean(
                        "batteryChanging"
                );

        electricSteam =
                nbt.getFloat(
                        "electricSteam"
                );

        backgroundWorkingLoopVolume =
                nbt.getFloat(
                        "backgroundWorkingLoopVolume"
                );

        waterfallLoopVolume =
                nbt.getFloat(
                        "waterfallLoopVolume"
                );

        steamLoopVolume =
                nbt.getFloat(
                        "steamLoopVolume"
                );

        activeHeaterLight =
                nbt.contains("activeHeaterLight")
                        ? nbt.getInt(
                        "activeHeaterLight"
                )
                        : -1;

        heaterLightAlpha =
                nbt.getFloat(
                        "heaterLightAlpha"
                );

        heaterLightRadius =
                nbt.getFloat(
                        "heaterLightRadius"
                );

        for (int i = 0; i < 3; i++) {
            doorClosed[i] =
                    nbt.contains("doorClosed" + i)
                            ? nbt.getFloat(
                            "doorClosed" + i
                    )
                            : i == DOOR_MIDDLE
                            ? 1.0f
                            : 0.0f;

            doorGoal[i] =
                    nbt.contains("doorGoal" + i)
                            ? nbt.getFloat(
                            "doorGoal" + i
                    )
                            : i == DOOR_MIDDLE
                            ? 1.0f
                            : 0.0f;
        }

        for (int i = 0; i < 2; i++) {
            waterSetFlow[i] =
                    nbt.getFloat(
                            "waterSetFlow" + i
                    );

            waterFlow[i] =
                    nbt.getFloat(
                            "waterFlow" + i
                    );

            waterVisualDensity[i] =
                    nbt.getFloat(
                            "waterVisualDensity" + i
                    );

            waterTopY[i] =
                    nbt.contains("waterTopY" + i)
                            ? nbt.getFloat(
                            "waterTopY" + i
                    )
                            : waterFlow[i] > 0.0f
                            ? WATERFALL_START_Y
                            : WATERFALL_BOTTOM_Y;

            waterBottomY[i] =
                    nbt.contains(
                            "waterBottomY" + i
                    )
                            ? nbt.getFloat(
                            "waterBottomY" + i
                    )
                            : WATERFALL_BOTTOM_Y;

            waterTopVelocity[i] =
                    nbt.getFloat(
                            "waterTopVelocity" + i
                    );

            waterBottomVelocity[i] =
                    nbt.getFloat(
                            "waterBottomVelocity" + i
                    );

            heaterHeat[i] =
                    nbt.getFloat(
                            "heaterHeat" + i
                    );

            heaterLastHeat[i] =
                    nbt.contains(
                            "heaterLastHeat" + i
                    )
                            ? nbt.getFloat(
                            "heaterLastHeat" + i
                    )
                            : heaterHeat[i];

            heaterTarget[i] =
                    nbt.getFloat(
                            "heaterTarget" + i
                    );
        }

        for (int i = 0; i < 4; i++) {
            lampsOn[i] =
                    nbt.getBoolean(
                            "lamp" + i
                    );

            lampVisible[i] =
                    nbt.contains("lampVisible" + i)
                            ? nbt.getBoolean(
                            "lampVisible" + i
                    )
                            : lampsOn[i];
        }

        boolean readMechanics =
                dataVersion >= CONTROLLER_DATA_VERSION
                        && readDoorMechanics(
                        nbt,
                        "doorMechanics"
                );

        if (!readMechanics) {
            /*
             * Legacy saves did not persist the procedural timing state. Build
             * a settled clamp state from each current closedFac so loading an
             * old world cannot deadlock a door halfway through a cycle.
             */
            initialiseDoorMechanics();
        }

        lightsSide1.readNbt(
                nbt,
                "lightsSide1"
        );

        lightsSide2.readNbt(
                nbt,
                "lightsSide2"
        );

        readPosList(
                nbt,
                "waterSide1",
                waterSide1
        );

        readPosList(
                nbt,
                "waterSide2",
                waterSide2
        );

        readPosList(
                nbt,
                "heatSide1",
                heatSide1
        );

        readPosList(
                nbt,
                "heatSide2",
                heatSide2
        );

        readPosList(
                nbt,
                "steamSide1",
                steamSide1
        );

        readPosList(
                nbt,
                "steamSide2",
                steamSide2
        );

        readPosList(
                nbt,
                "holoSide1",
                hologramSide1
        );

        readPosList(
                nbt,
                "holoSide2",
                hologramSide2
        );

        readPosList(
                nbt,
                "batteryMeters",
                batteryMeters
        );

        if (!storedGateType) {
            gateType = inferGateType();
        }

        karmaSide1 =
                readEnum(
                        nbt.getString("karmaSide1"),
                        KarmaLevel.class,
                        KarmaLevel.LEVEL_0
                );

        karmaSide2 =
                readEnum(
                        nbt.getString("karmaSide2"),
                        KarmaLevel.class,
                        KarmaLevel.LEVEL_0
                );

        pendingSteamPuffs[0] = 0;
        pendingSteamPuffs[1] = 0;
        pendingSteamIntensity[0] = 0.0f;
        pendingSteamIntensity[1] = 0.0f;
    }

    private void writeDoorMechanics(
            NbtCompound root,
            String key
    ) {
        NbtCompound mechanicsRoot =
                new NbtCompound();

        for (int door = 0; door < 3; door++) {
            DoorMechanics mechanics =
                    doorMechanics[door];

            if (mechanics == null) {
                continue;
            }

            NbtCompound doorNbt =
                    new NbtCompound();

            doorNbt.putFloat(
                    "lastClosedFac",
                    mechanics.lastClosedFac
            );

            doorNbt.putInt(
                    "wantedClampStatus",
                    mechanics.wantedClampStatus
            );

            doorNbt.putInt(
                    "clampsStatus",
                    mechanics.clampsStatus
            );

            doorNbt.putBoolean(
                    "stalled",
                    mechanics.stalled
            );

            doorNbt.putInt(
                    "movingClampCount",
                    mechanics.movingClampCount
            );

            doorNbt.putLong(
                    "sourceUpdates",
                    mechanics.sourceUpdates
            );

            doorNbt.putLong(
                    "stalledUpdates",
                    mechanics.stalledUpdates
            );

            for (int side = 0; side < 2; side++) {
                for (int number = 0; number < 9; number++) {
                    ClampState clamp =
                            mechanics.clamps[side][number];

                    NbtCompound clampNbt =
                            new NbtCompound();

                    clampNbt.putString(
                            "mode",
                            clamp.mode.name()
                    );

                    clampNbt.putFloat(
                            "posY",
                            clamp.posY
                    );

                    clampNbt.putFloat(
                            "velY",
                            clamp.velY
                    );

                    clampNbt.putFloat(
                            "fric",
                            clamp.fric
                    );

                    clampNbt.putInt(
                            "lockCounter",
                            clamp.lockCounter
                    );

                    doorNbt.put(
                            "clamp_"
                                    + side
                                    + "_"
                                    + number,
                            clampNbt
                    );
                }
            }

            mechanicsRoot.put(
                    "door" + door,
                    doorNbt
            );
        }

        root.put(
                key,
                mechanicsRoot
        );
    }

    private boolean readDoorMechanics(
            NbtCompound root,
            String key
    ) {
        if (!root.contains(key)) {
            return false;
        }

        NbtCompound mechanicsRoot =
                root.getCompound(key);

        for (int door = 0; door < 3; door++) {
            String doorKey =
                    "door" + door;

            if (!mechanicsRoot.contains(doorKey)) {
                return false;
            }

            NbtCompound doorNbt =
                    mechanicsRoot.getCompound(
                            doorKey
                    );

            DoorMechanics mechanics =
                    new DoorMechanics(
                            doorClosed[door],
                            random
                    );

            mechanics.lastClosedFac =
                    doorNbt.getFloat(
                            "lastClosedFac"
                    );

            mechanics.wantedClampStatus =
                    doorNbt.getInt(
                            "wantedClampStatus"
                    );

            mechanics.clampsStatus =
                    doorNbt.getInt(
                            "clampsStatus"
                    );

            mechanics.stalled =
                    doorNbt.getBoolean(
                            "stalled"
                    );

            mechanics.movingClampCount =
                    doorNbt.getInt(
                            "movingClampCount"
                    );

            mechanics.sourceUpdates =
                    doorNbt.getLong(
                            "sourceUpdates"
                    );

            mechanics.stalledUpdates =
                    doorNbt.getLong(
                            "stalledUpdates"
                    );

            for (int side = 0; side < 2; side++) {
                for (int number = 0; number < 9; number++) {
                    String clampKey =
                            "clamp_"
                                    + side
                                    + "_"
                                    + number;

                    if (!doorNbt.contains(clampKey)) {
                        return false;
                    }

                    NbtCompound clampNbt =
                            doorNbt.getCompound(
                                    clampKey
                            );

                    ClampState clamp =
                            mechanics.clamps[side][number];

                    clamp.mode =
                            readEnum(
                                    clampNbt.getString("mode"),
                                    ClampMode.class,
                                    clamp.mode
                            );

                    clamp.posY =
                            clampNbt.getFloat(
                                    "posY"
                            );

                    clamp.velY =
                            clampNbt.getFloat(
                                    "velY"
                            );

                    clamp.fric =
                            clampNbt.getFloat(
                                    "fric"
                            );

                    clamp.lockCounter =
                            clampNbt.getInt(
                                    "lockCounter"
                            );
                }
            }

            doorMechanics[door] = mechanics;
        }

        return true;
    }

    private static void writePos(
            NbtCompound root,
            String key,
            BlockPos pos
    ) {
        if (pos == null) {
            return;
        }

        NbtCompound value =
                new NbtCompound();

        value.putInt("x", pos.getX());
        value.putInt("y", pos.getY());
        value.putInt("z", pos.getZ());

        root.put(key, value);
    }

    private static BlockPos readPos(
            NbtCompound root,
            String key
    ) {
        if (!root.contains(key)) {
            return null;
        }

        NbtCompound value =
                root.getCompound(key);

        return new BlockPos(
                value.getInt("x"),
                value.getInt("y"),
                value.getInt("z")
        );
    }

    private static void writePosList(
            NbtCompound root,
            String key,
            List<BlockPos> positions
    ) {
        NbtCompound value =
                new NbtCompound();

        value.putInt(
                "n",
                positions.size()
        );

        for (int i = 0; i < positions.size(); i++) {
            writePos(
                    value,
                    "p" + i,
                    positions.get(i)
            );
        }

        root.put(key, value);
    }

    private static void readPosList(
            NbtCompound root,
            String key,
            List<BlockPos> output
    ) {
        output.clear();

        if (!root.contains(key)) {
            return;
        }

        NbtCompound value =
                root.getCompound(key);

        for (int i = 0; i < value.getInt("n"); i++) {
            BlockPos pos =
                    readPos(
                            value,
                            "p" + i
                    );

            if (pos != null) {
                output.add(pos);
            }
        }
    }

    private static <E extends Enum<E>> E readEnum(
            String value,
            Class<E> type,
            E fallback
    ) {
        try {
            return Enum.valueOf(
                    type,
                    value
            );
        } catch (
                IllegalArgumentException
                        | NullPointerException ignored
        ) {
            return fallback;
        }
    }

    public BlockPos getPos() {
        return controllerBE.getPos();
    }

    private void clearLogicalLamps() {
        for (int i = 0; i < lampsOn.length; i++) {
            lampsOn[i] = false;
        }
    }

    private void clearVisibleLamps() {
        for (int i = 0; i < lampVisible.length; i++) {
            lampVisible[i] = false;
        }
    }

    private void clearLogicalLampPair(Side side) {
        if (side == Side.SIDE1) {
            lampsOn[1] = false;
            lampsOn[2] = false;
        } else {
            lampsOn[0] = false;
            lampsOn[3] = false;
        }
    }

    private void clearVisibleLampPair(Side side) {
        if (side == Side.SIDE1) {
            lampVisible[1] = false;
            lampVisible[2] = false;
        } else {
            lampVisible[0] = false;
            lampVisible[3] = false;
        }
    }

    private static float clamp01(float value) {
        return Math.max(
                0.0f,
                Math.min(
                        1.0f,
                        value
                )
        );
    }

    private static float lerp(
            float from,
            float to,
            float amount
    ) {
        return from
                + (to - from)
                * amount;
    }

    private static float inverseLerp(
            float from,
            float to,
            float value
    ) {
        if (from == to) {
            return value >= to
                    ? 1.0f
                    : 0.0f;
        }

        return clamp01(
                (value - from)
                        / (to - from)
        );
    }

    private static float sCurve(
            float value,
            float exponent
    ) {
        float x = clamp01(value);

        if (x <= 0.0f || x >= 1.0f) {
            return x;
        }

        float a =
                (float) Math.pow(
                        x,
                        exponent
                );

        float b =
                (float) Math.pow(
                        1.0f - x,
                        exponent
                );

        return a / (a + b);
    }

    private record Region(
            double centerX,
            double centerZ,
            double halfX,
            double halfZ,
            double axisHalf
    ) {
        boolean contains(PlayerEntity player) {
            return Math.abs(
                    player.getX() - centerX
            ) <= halfX
                    && Math.abs(
                    player.getZ() - centerZ
            ) <= halfZ;
        }
    }

    private record Detection(
            boolean side1,
            boolean side2,
            boolean center,
            boolean side1Stationary,
            boolean side2Stationary,
            boolean anyInsideGate,
            boolean allThroughSide1,
            boolean allThroughSide2
    ) {
        boolean allThrough(Side entry) {
            return entry == Side.SIDE1
                    ? allThroughSide1
                    : allThroughSide2;
        }
    }

    private static final class DoorMechanics {
        private float lastClosedFac;
        private int wantedClampStatus;
        private int clampsStatus;
        private boolean stalled;
        private int movingClampCount;
        private long sourceUpdates;
        private long stalledUpdates;

        private final ClampState[][] clamps =
                new ClampState[2][9];

        private DoorMechanics(
                float closedFac,
                Random random
        ) {
            lastClosedFac = closedFac;

            /*
             * Exact initial states are binary in the source. For a migrated
             * mid-animation save, choosing the mechanically corresponding side
             * of the 0.2 clamp threshold avoids an artificial deadlock.
             */
            boolean mechanicallyClosed =
                    closedFac > CLAMPS_PAUSE;

            wantedClampStatus =
                    mechanicallyClosed
                            ? 1
                            : -1;

            clampsStatus =
                    wantedClampStatus;

            for (int side = 0; side < 2; side++) {
                for (int number = 0; number < 9; number++) {
                    clamps[side][number] =
                            new ClampState(
                                    side,
                                    number,
                                    mechanicallyClosed,
                                    random
                            );
                }
            }
        }
    }

    private static final class ClampState {
        private final int side;
        private final int number;

        private ClampMode mode;

        private float posY;
        private float velY;
        private float fric;

        private final float clampHeight;
        private final float stackHeight;

        private int lockCounter;

        private ClampState(
                int side,
                int number,
                boolean mechanicallyClosed,
                Random random
        ) {
            this.side = side;
            this.number = number;

            stackHeight = 335.0f;

            clampHeight =
                    340.0f
                            - 180.0f
                            * (
                            (number + 1)
                                    / 9.0f
                    );

            fric =
                    lerp(
                            0.75f,
                            0.95f,
                            (float) Math.pow(
                                    random.nextFloat(),
                                    3.0
                            )
                    );

            if (mechanicallyClosed) {
                mode = ClampMode.Locked;
                posY = clampHeight;
            } else {
                mode = ClampMode.Stacked;
                posY = stackHeight;
            }
        }
    }
}
