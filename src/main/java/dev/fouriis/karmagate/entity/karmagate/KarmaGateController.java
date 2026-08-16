package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.karmagate.KarmaGateBlock;
import dev.fouriis.karmagate.block.karmagate.SteamEmitterBlock;
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
 * Server-authoritative port of RegionGate, WaterGate, ElectricGate, and the
 * state-derived controls from RegionGateGraphics. One Minecraft tick advances
 * two Rain World logic steps so all source constants remain at their native
 * 40 Hz values.
 */
public final class KarmaGateController {
    private enum Mode {
        MiddleClosed, ClosingAirLock, Waiting, OpeningMiddle,
        MiddleOpen, ClosingMiddle, OpeningSide, Closed, Broken
    }

    private enum Side { SIDE1, SIDE2 }

    public enum GateType { WATER, ELECTRIC, BROKEN }

    public enum KarmaLevel {
        LEVEL_0(0), LEVEL_1(1), LEVEL_2(2), LEVEL_3(3),
        LEVEL_4(4), LEVEL_5(5), LEVEL_D(-1);

        private final int index;

        KarmaLevel(int index) { this.index = index; }
        public int getIndex() { return index; }
        public float asFloat() { return index < 0 ? 1.0f : index / 5.0f; }

        public static KarmaLevel fromFloat(float value) {
            int bucket = Math.min(5, (int) Math.floor(clamp01(value) * 6.0f));
            return fromIndex(bucket);
        }

        public static KarmaLevel fromIndex(int index) {
            if (index == -1 || index == 6) return LEVEL_D;
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

    private static final int RAIN_WORLD_STEPS_PER_MC_TICK = 2;
    private static final float DOOR_CLOSE_SPEED = 1.0f / 180.0f;
    private static final float DOOR_OPEN_SPEED = 1.0f / 220.0f;
    private static final int RECOVERY_DURATION = 40 * 30;
    private static final int STATIONARY_REQUIRED = 20;
    private static final double STATIONARY_DISTANCE_SQUARED = 0.0025;
    private static final double HALF_WIDTH = 6.5;
    private static final double FALLBACK_SIDE_OFFSET_NEGATIVE = -4.0;
    private static final double FALLBACK_SIDE_OFFSET_POSITIVE = 5.0;
    private static final double DETECTION_GATE_PADDING = 2.0;

    private final KarmaGateBlockEntity controllerBE;
    private final GateLightGroup lightsSide1 = new GateLightGroup(GateLightGroup.Side.SIDE1);
    private final GateLightGroup lightsSide2 = new GateLightGroup(GateLightGroup.Side.SIDE2);
    private final List<BlockPos> waterSide1 = new ArrayList<>();
    private final List<BlockPos> waterSide2 = new ArrayList<>();
    private final List<BlockPos> heatSide1 = new ArrayList<>();
    private final List<BlockPos> heatSide2 = new ArrayList<>();
    private final List<BlockPos> steamSide1 = new ArrayList<>();
    private final List<BlockPos> steamSide2 = new ArrayList<>();
    private final List<BlockPos> hologramSide1 = new ArrayList<>();
    private final List<BlockPos> hologramSide2 = new ArrayList<>();
    private final Random random = new Random(1979L);
    private final Map<UUID, Vec3d> previousPlayerPositions = new HashMap<>();
    private final Map<UUID, Integer> stationarySteps = new HashMap<>();

    private BlockPos gate1;
    private BlockPos gate2;
    private GateType gateType = GateType.BROKEN;
    private Mode mode = Mode.MiddleClosed;
    private Side entrySide;
    private boolean dontOpen;
    private int startCounter;
    private int washingCounter;
    private int recoveryCounter;
    private int lampBlink;
    private boolean recoveryActive;
    private float recoveryStartResource = 1.0f;
    private final float[] doorClosed = { 0.0f, 1.0f, 0.0f };
    private final float[] doorGoal = { 0.0f, 1.0f, 0.0f };

    private float waterLeft = 1.0f;
    private final float[] waterFlow = new float[2];
    private final float[] waterSetFlow = new float[2];
    private float outletLag;
    private final float[] heaterHeat = new float[2];
    private final float[] heaterTarget = new float[2];

    private float batteryLeft = 1.0f;
    private boolean batteryChanging;
    private final boolean[] lampsOn = new boolean[4];
    private float electricSteam;

    private KarmaLevel karmaSide1 = KarmaLevel.LEVEL_0;
    private KarmaLevel karmaSide2 = KarmaLevel.LEVEL_0;

    public KarmaGateController(KarmaGateBlockEntity controllerBE) {
        this.controllerBE = controllerBE;
    }

    public void setGates(BlockPos first, BlockPos second) {
        World world = controllerBE.getWorld();
        if (world == null || first == null || second == null) {
            gate1 = first;
            gate2 = second;
            return;
        }
        Direction.Axis axis = controllerBE.getCachedState().get(KarmaGateBlock.AXIS);
        if (axisCoordinate(first, axis) <= axisCoordinate(second, axis)) {
            gate1 = first;
            gate2 = second;
        } else {
            gate1 = second;
            gate2 = first;
        }
    }

    public void bindLights(World world, BlockPos pos, BlockState state, int radius) {
        Direction.Axis pathAxis = state.get(KarmaGateBlock.AXIS);
        Direction.Axis modelAxis = pathAxis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        lightsSide1.bindLights(world, pos, modelAxis, radius);
        lightsSide2.bindLights(world, pos, modelAxis, radius);
        lightsSide1.allOff(world);
        lightsSide2.allOff(world);
    }

    public void bindLightsAndEffects(World world, BlockPos pos, BlockState state, int radius) {
        bindLights(world, pos, state, radius);
        waterSide1.clear();
        waterSide2.clear();
        heatSide1.clear();
        heatSide2.clear();
        steamSide1.clear();
        steamSide2.clear();
        hologramSide1.clear();
        hologramSide2.clear();

        Direction.Axis axis = state.get(KarmaGateBlock.AXIS);
        double center = axisCoordinate(pos, axis) - 0.5;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos foundPos = pos.add(dx, dy, dz);
                    BlockEntity found = world.getBlockEntity(foundPos);
                    boolean negative = axisCoordinate(foundPos, axis) + 0.5 < center;
                    if (found instanceof WaterStreamBlockEntity) {
                        (negative ? waterSide1 : waterSide2).add(foundPos);
                    } else if (found instanceof HeatCoilBlockEntity) {
                        (negative ? heatSide1 : heatSide2).add(foundPos);
                    } else if (found instanceof SteamEmitterBlockEntity) {
                        (negative ? steamSide1 : steamSide2).add(foundPos);
                    } else if (found instanceof HologramProjectorBlockEntity hologram) {
                        (negative ? hologramSide1 : hologramSide2).add(foundPos);
                        hologram.bindController(this);
                    }
                }
            }
        }

        gateType = inferGateType();

        applyKarmaToList(world, hologramSide1, karmaSide1);
        applyKarmaToList(world, hologramSide2, karmaSide2);
        KarmaGateMod.LOGGER.info(
                "[GateCtrl @{}] type={} water(S1={},S2={}) heat(S1={},S2={}) steam(S1={},S2={}) lights={} holograms(S1={},S2={})",
                controllerBE.getPos(), gateType, waterSide1.size(), waterSide2.size(),
                heatSide1.size(), heatSide2.size(), steamSide1.size(), steamSide2.size(),
                lightsSide1.getRefs().size() + lightsSide2.getRefs().size(),
                hologramSide1.size(), hologramSide2.size());
        if (gateType == GateType.BROKEN) {
            KarmaGateMod.LOGGER.warn("[GateCtrl @{}] component set is ambiguous/incomplete; gate is inert",
                    controllerBE.getPos());
        }
    }

    public void resetOnBind() {
        resetSimulation();
        applyOutputs(controllerBE.getWorld());
    }

    private void resetSimulation() {
        mode = gateType == GateType.BROKEN ? Mode.Broken : Mode.MiddleClosed;
        entrySide = null;
        dontOpen = false;
        startCounter = 0;
        washingCounter = 0;
        recoveryCounter = 0;
        lampBlink = 0;
        recoveryActive = false;
        recoveryStartResource = 1.0f;
        doorClosed[0] = doorGoal[0] = 0.0f;
        doorClosed[1] = doorGoal[1] = 1.0f;
        doorClosed[2] = doorGoal[2] = 0.0f;
        waterLeft = 1.0f;
        waterFlow[0] = waterFlow[1] = 0.0f;
        waterSetFlow[0] = waterSetFlow[1] = 0.0f;
        outletLag = 0.0f;
        heaterHeat[0] = heaterHeat[1] = 0.0f;
        heaterTarget[0] = heaterTarget[1] = 0.0f;
        batteryLeft = 1.0f;
        batteryChanging = false;
        lampsOn[0] = lampsOn[1] = lampsOn[2] = lampsOn[3] = false;
        electricSteam = 0.0f;
        previousPlayerPositions.clear();
        stationarySteps.clear();
    }

    public void tick(World world, BlockPos pos, BlockState state) {
        if (world == null || world.isClient) return;
        Detection detection = detectPlayers(world, pos, state);
        for (int step = 0; step < RAIN_WORLD_STEPS_PER_MC_TICK; step++) {
            simulationStep(detection);
        }
        applyOutputs(world);
        controllerBE.markDirty();
    }

    private void simulationStep(Detection detection) {
        lampBlink++;
        if (gateType == GateType.WATER) updateWaterGate();
        else if (gateType == GateType.ELECTRIC) updateElectricGate();
        updateGraphicsControls();
        updateBaseGate(detection);
        updateDoors();
        updateRecovery();
    }

    private void updateBaseGate(Detection detection) {
        switch (mode) {
            case MiddleClosed -> {
                Side occupied = detection.startingSide();
                if (occupied != null) {
                    entrySide = occupied;
                    boolean still = occupied == Side.SIDE1
                            ? detection.side1Stationary : detection.side2Stationary;
                    if (!dontOpen && still && energyEnoughToOpen() && isSideEnabled(occupied)) {
                        startCounter++;
                        if (startCounter > 60) {
                            mode = Mode.ClosingAirLock;
                            doorGoal[index(occupied)] = 1.0f;
                            startCounter = 0;
                            KarmaGateMod.LOGGER.info("[GateCtrl @{}] MiddleClosed -> ClosingAirLock ({})",
                                    controllerBE.getPos(), occupied);
                        }
                    } else {
                        startCounter = 0;
                    }
                } else {
                    entrySide = null;
                    startCounter = 0;
                    if (!detection.anyInsideGate()) dontOpen = false;
                }
            }
            case ClosingAirLock -> {
                if (allDoorsInPosition()) {
                    washingCounter = 0;
                    mode = Mode.Waiting;
                }
            }
            case Waiting -> {
                washingCounter++;
                if (washingCounter > 400) {
                    mode = Mode.OpeningMiddle;
                    doorGoal[1] = 0.0f;
                }
            }
            case OpeningMiddle -> {
                if (allDoorsInPosition()) mode = Mode.MiddleOpen;
            }
            case MiddleOpen -> {
                if (entrySide != null && detection.allThrough(entrySide)) {
                    doorGoal[1] = 1.0f;
                    mode = Mode.ClosingMiddle;
                }
            }
            case ClosingMiddle -> {
                if (allDoorsInPosition()) {
                    mode = Mode.OpeningSide;
                    doorGoal[0] = 0.0f;
                    doorGoal[2] = 0.0f;
                }
            }
            case OpeningSide -> {
                if (allDoorsInPosition()) {
                    mode = Mode.Closed;
                    dontOpen = true;
                    recoveryActive = true;
                    recoveryCounter = 0;
                    recoveryStartResource = gateType == GateType.WATER ? waterLeft : batteryLeft;
                }
            }
            case Closed, Broken -> { }
        }
    }

    private void updateWaterGate() {
        int wash = index(entrySide == null ? Side.SIDE1 : entrySide);
        int outlet = 1 - wash;
        float pressure = waterPressure();
        switch (mode) {
            case MiddleClosed -> waterSetFlow[outlet] =
                    inverseLerp(0.0f, 60.0f, startCounter) * 0.5f * pressure;
            case ClosingAirLock -> {
                waterSetFlow[outlet] = pressure;
                waterRunning(1.0f);
            }
            case Waiting -> {
                washingCounter++;
                waterSetFlow[wash] = (float) Math.pow(
                        inverseLerp(0.0f, 160.0f, washingCounter), 1.5) * 0.5f * pressure;
                outletLag = Math.max(0.0f, outletLag - 1.0f / 60.0f);
                waterSetFlow[outlet] = outletLag * pressure;
                if (washingCounter > 400) waterSetFlow[wash] = 0.0f;
            }
            case OpeningMiddle -> {
                waterSetFlow[outlet] = pressure;
                waterRunning(1.0f);
                if (allDoorsInPosition()) outletLag = 1.0f;
            }
            case MiddleOpen -> {
                outletLag = Math.max(0.0f, outletLag - 1.0f / 60.0f);
                waterSetFlow[outlet] = outletLag * pressure;
            }
            case ClosingMiddle -> {
                waterSetFlow[wash] = pressure;
                waterSetFlow[outlet] = 0.0f;
                waterRunning(1.0f);
            }
            case OpeningSide -> {
                waterSetFlow[wash] = pressure;
                waterSetFlow[outlet] = 0.0f;
                waterRunning(1.0f);
                if (allDoorsInPosition()) {
                    waterSetFlow[wash] = 0.0f;
                    outletLag = 1.0f;
                }
            }
            case Closed -> {
                outletLag = Math.max(0.0f, outletLag - 1.0f / 60.0f);
                waterSetFlow[wash] = outletLag > 0.05f ? outletLag * pressure : 0.0f;
                waterSetFlow[outlet] = 0.0f;
                waterRunning(outletLag * 0.5f);
            }
            case Broken -> { }
        }
        for (int i = 0; i < 2; i++) {
            float rate = waterSetFlow[i] > waterFlow[i] ? 1.0f / 40.0f : 1.0f / 60.0f;
            waterFlow[i] = moveTowards(waterFlow[i], waterSetFlow[i], rate);
        }
    }

    private void updateElectricGate() {
        batteryChanging = false;
        for (int i = 0; i < lampsOn.length; i++) {
            if (random.nextFloat() < 1.0f / 60.0f) lampsOn[i] = false;
        }
        switch (mode) {
            case MiddleClosed -> {
                if (startCounter > 0 && entrySide != null) {
                    boolean firstHalf = lampBlink % 20 < 10;
                    if (entrySide == Side.SIDE1) {
                        lampsOn[1] = firstHalf;
                        lampsOn[2] = !firstHalf;
                        lampsOn[0] = lampsOn[3] = false;
                    } else {
                        lampsOn[0] = firstHalf;
                        lampsOn[3] = !firstHalf;
                        lampsOn[1] = lampsOn[2] = false;
                    }
                }
            }
            case ClosingAirLock, OpeningMiddle -> batteryRunning(1.0f);
            case Waiting -> washingCounter++;
            case MiddleOpen -> {
                int active = (lampBlink % 40) / 10;
                for (int i = 0; i < 4; i++) lampsOn[i] = false;
                lampsOn[entrySide == Side.SIDE1 ? active : 3 - active] = true;
            }
            case ClosingMiddle, OpeningSide -> {
                batteryRunning(1.0f);
                boolean on = lampBlink % 20 < 10;
                for (int i = 0; i < 4; i++) lampsOn[i] = on;
            }
            case Closed, Broken -> { }
        }
    }

    private void updateGraphicsControls() {
        if (gateType == GateType.WATER) {
            for (int i = 0; i < 2; i++) {
                boolean selected = entrySide != null && i == index(entrySide);
                if ((mode == Mode.ClosingAirLock || mode == Mode.Waiting) && selected) {
                    heaterTarget[i] = Math.min(1.0f, heaterTarget[i] + 1.0f / 600.0f);
                } else {
                    heaterTarget[i] = Math.max(0.0f, heaterTarget[i] - 1.0f / 600.0f);
                }
                heaterHeat[i] = lerp(heaterHeat[i], heaterTarget[i], 0.7f);
            }
        } else if (gateType == GateType.ELECTRIC) {
            electricSteam = mode == Mode.Waiting && washingCounter > 0
                    ? Math.min(1.0f, electricSteam + 0.025f)
                    : Math.max(0.0f, electricSteam - 0.025f);
        }
    }

    private void updateDoors() {
        for (int i = 0; i < doorClosed.length; i++) {
            float speed = doorClosed[i] > doorGoal[i] ? DOOR_OPEN_SPEED : DOOR_CLOSE_SPEED;
            doorClosed[i] = moveTowards(doorClosed[i], doorGoal[i], speed);
        }
    }

    private void updateRecovery() {
        if (!recoveryActive || mode != Mode.Closed) return;
        recoveryCounter++;
        float progress = clamp01(recoveryCounter / (float) RECOVERY_DURATION);
        if (gateType == GateType.WATER) waterLeft = lerp(recoveryStartResource, 1.0f, progress);
        else if (gateType == GateType.ELECTRIC) batteryLeft = lerp(recoveryStartResource, 1.0f, progress);
        if (recoveryCounter >= RECOVERY_DURATION) resetSimulation();
    }

    private void applyOutputs(World world) {
        if (world == null) return;
        setGateOpen(world, gate1, doorGoal[0] < 0.5f);
        controllerBE.setOpen(doorGoal[1] < 0.5f);
        setGateOpen(world, gate2, doorGoal[2] < 0.5f);

        if (gateType == GateType.WATER) {
            setWaterFlow(world, waterSide1, waterFlow[0]);
            setWaterFlow(world, waterSide2, waterFlow[1]);
            setHeaterHeat(world, heatSide1, heaterHeat[0]);
            setHeaterHeat(world, heatSide2, heaterHeat[1]);
            setSteamFlow(world, steamSide1, 0.0f);
            setSteamFlow(world, steamSide2, 0.0f);
            lightsSide1.allOff(world);
            lightsSide2.allOff(world);
        } else if (gateType == GateType.ELECTRIC) {
            setWaterFlow(world, waterSide1, 0.0f);
            setWaterFlow(world, waterSide2, 0.0f);
            setHeaterHeat(world, heatSide1, 0.0f);
            setHeaterHeat(world, heatSide2, 0.0f);
            setSteamFlow(world, steamSide1, electricSteam);
            setSteamFlow(world, steamSide2, electricSteam);
            lightsSide1.setPairStates(world, lampsOn[1], lampsOn[2]);
            lightsSide2.setPairStates(world, lampsOn[0], lampsOn[3]);
        } else {
            stopAllEffects(world);
            lightsSide1.allOff(world);
            lightsSide2.allOff(world);
        }
        applyHologramControls(world);
    }

    private void applyHologramControls(World world) {
        boolean lowPower = !energyEnoughToOpen();
        float side1Fade;
        float side2Fade;
        if (mode == Mode.MiddleClosed) {
            side1Fade = 1.0f;
            side2Fade = 1.0f;
            if (entrySide != null && startCounter > 0) {
                if (gateType == GateType.WATER) {
                    float selectedFade = inverseLerp(40.0f, 0.0f, startCounter);
                    if (entrySide == Side.SIDE1) side1Fade = selectedFade;
                    else side2Fade = selectedFade;
                } else if (gateType == GateType.ELECTRIC) {
                    float preparingFade = inverseLerp(10.0f, 0.0f, startCounter);
                    side1Fade = lampsOn[1] || lampsOn[2] ? 0.0f : preparingFade;
                    side2Fade = lampsOn[0] || lampsOn[3] ? 0.0f : preparingFade;
                }
            }
        } else if (mode == Mode.Closed) {
            side1Fade = side2Fade = lowPower ? 0.82f : 1.0f;
        } else {
            side1Fade = side2Fade = 0.0f;
        }
        setHologramTargetLevels(world, 1.0f - side1Fade, 1.0f - side2Fade);
        setHologramLowPower(world, lowPower, lowPower);
    }

    private Detection detectPlayers(World world, BlockPos pos, BlockState state) {
        updateStationaryPlayers(world);
        Direction.Axis axis = state.get(KarmaGateBlock.AXIS);
        double centerX = pos.getX() + 0.5;
        double centerZ = pos.getZ() + 0.5;
        double axisCenter = axis == Direction.Axis.X ? pos.getX() - 0.5 : pos.getZ() - 0.5;
        double gate1Coordinate = gate1 == null ? Double.NaN : axisCoordinate(gate1, axis) + 0.5;
        double gate2Coordinate = gate2 == null ? Double.NaN : axisCoordinate(gate2, axis) + 0.5;

        Region side1 = makeSideRegion(axis, centerX, centerZ, axisCenter, gate1Coordinate,
                FALLBACK_SIDE_OFFSET_NEGATIVE);
        Region side2 = makeSideRegion(axis, centerX, centerZ, axisCenter, gate2Coordinate,
                FALLBACK_SIDE_OFFSET_POSITIVE);
        double centerHalfAxis = Math.max(2.5,
                Math.min(side1.axisHalf, side2.axisHalf) - 2.0);
        Region center = axis == Direction.Axis.X
                ? new Region(centerX, centerZ, centerHalfAxis, HALF_WIDTH, centerHalfAxis)
                : new Region(centerX, centerZ, HALF_WIDTH, centerHalfAxis, centerHalfAxis);

        boolean in1 = false, in2 = false, inCenter = false;
        boolean stationary1 = false, stationary2 = false;
        for (PlayerEntity player : world.getPlayers()) {
            if (!playerEligible(player)) continue;
            boolean p1 = isSideEnabled(Side.SIDE1) && side1.contains(player);
            boolean p2 = isSideEnabled(Side.SIDE2) && side2.contains(player);
            boolean pc = center.contains(player);
            in1 |= p1;
            in2 |= p2;
            inCenter |= pc;
            int still = stationarySteps.getOrDefault(player.getUuid(), 0);
            stationary1 |= p1 && still >= STATIONARY_REQUIRED;
            stationary2 |= p2 && still >= STATIONARY_REQUIRED;
        }
        return new Detection(in1, in2, inCenter, stationary1, stationary2);
    }

    private void updateStationaryPlayers(World world) {
        Set<UUID> seen = new HashSet<>();
        for (PlayerEntity player : world.getPlayers()) {
            if (!playerEligible(player)) continue;
            UUID id = player.getUuid();
            seen.add(id);
            Vec3d position = player.getPos();
            Vec3d previous = previousPlayerPositions.put(id, position);
            boolean still = previous != null
                    && previous.squaredDistanceTo(position) <= STATIONARY_DISTANCE_SQUARED;
            stationarySteps.put(id, still
                    ? Math.min(120, stationarySteps.getOrDefault(id, 0) + RAIN_WORLD_STEPS_PER_MC_TICK)
                    : 0);
        }
        previousPlayerPositions.keySet().removeIf(id -> !seen.contains(id));
        stationarySteps.keySet().removeIf(id -> !seen.contains(id));
    }

    private Region makeSideRegion(Direction.Axis axis, double centerX, double centerZ,
                                  double axisCenter, double gateCoordinate, double fallbackOffset) {
        double halfAxis = HALF_WIDTH;
        double middle = axisCenter + fallbackOffset;
        if (!Double.isNaN(gateCoordinate)) {
            double usable = Math.max(0.0, Math.abs(gateCoordinate - axisCenter) - DETECTION_GATE_PADDING);
            halfAxis = Math.max(3.0, usable * 0.5);
            middle = axisCenter + Math.signum(gateCoordinate - axisCenter) * usable * 0.5;
        }
        return axis == Direction.Axis.X
                ? new Region(middle, centerZ, halfAxis, HALF_WIDTH, halfAxis)
                : new Region(centerX, middle, HALF_WIDTH, halfAxis, halfAxis);
    }

    private boolean energyEnoughToOpen() {
        return gateType == GateType.WATER ? waterLeft > 0.5f
                : gateType == GateType.ELECTRIC && batteryLeft > 0.5f;
    }

    private GateType inferGateType() {
        boolean hasLights = !lightsSide1.getRefs().isEmpty() || !lightsSide2.getRefs().isEmpty();
        boolean hasSteam = !steamSide1.isEmpty() || !steamSide2.isEmpty();
        boolean hasWater = !waterSide1.isEmpty() || !waterSide2.isEmpty();
        boolean hasHeaters = !heatSide1.isEmpty() || !heatSide2.isEmpty();
        boolean electric = hasLights && hasSteam;
        boolean water = hasWater && hasHeaters;
        return electric == water ? GateType.BROKEN
                : electric ? GateType.ELECTRIC : GateType.WATER;
    }

    private float waterPressure() {
        return (float) Math.pow(inverseLerp(0.0f, 0.5f, waterLeft), 0.6);
    }

    private void waterRunning(float flow) {
        waterLeft = Math.max(0.0f, waterLeft - flow / 1450.0f);
    }

    private void batteryRunning(float flow) {
        batteryLeft = Math.max(0.0f, batteryLeft - flow / 1300.0f);
        batteryChanging = true;
    }

    private boolean allDoorsInPosition() {
        for (int i = 0; i < doorClosed.length; i++) {
            if (Math.abs(doorClosed[i] - doorGoal[i]) > 1.0e-6f) return false;
        }
        return true;
    }

    private static int index(Side side) { return side == Side.SIDE1 ? 0 : 1; }
    private static double axisCoordinate(BlockPos pos, Direction.Axis axis) {
        return axis == Direction.Axis.X ? pos.getX() : pos.getZ();
    }
    private static boolean playerEligible(PlayerEntity player) { return !player.isSpectator(); }
    private boolean isSideEnabled(Side side) {
        return side == Side.SIDE1 ? karmaSide1 != KarmaLevel.LEVEL_D : karmaSide2 != KarmaLevel.LEVEL_D;
    }

    private static void setGateOpen(World world, BlockPos pos, boolean open) {
        if (pos == null) return;
        if (world.getBlockEntity(pos) instanceof KarmaGateBlockEntity gate) gate.setOpen(open);
    }

    private static void setWaterFlow(World world, List<BlockPos> positions, float flow) {
        for (BlockPos pos : positions) {
            if (world.getBlockEntity(pos) instanceof WaterStreamBlockEntity stream) {
                stream.setTargetFlow(flow);
            }
        }
    }

    private static void setHeaterHeat(World world, List<BlockPos> positions, float heat) {
        for (BlockPos pos : positions) {
            if (world.getBlockEntity(pos) instanceof HeatCoilBlockEntity coil) coil.setGateHeat(heat);
        }
    }

    private static void setSteamFlow(World world, List<BlockPos> positions, float flow) {
        boolean enabled = flow > 1.0e-4f;
        for (BlockPos pos : positions) {
            if (world.getBlockEntity(pos) instanceof SteamEmitterBlockEntity emitter) {
                emitter.setFlow(flow);
                BlockState state = world.getBlockState(pos);
                if (state.getBlock() instanceof SteamEmitterBlock
                        && state.get(SteamEmitterBlock.ENABLED) != enabled) {
                    world.setBlockState(pos, state.with(SteamEmitterBlock.ENABLED, enabled), 3);
                }
            }
        }
    }

    private void stopAllEffects(World world) {
        setWaterFlow(world, waterSide1, 0.0f);
        setWaterFlow(world, waterSide2, 0.0f);
        setHeaterHeat(world, heatSide1, 0.0f);
        setHeaterHeat(world, heatSide2, 0.0f);
        setSteamFlow(world, steamSide1, 0.0f);
        setSteamFlow(world, steamSide2, 0.0f);
    }

    public List<BlockPos> getWaterSide1() { return waterSide1; }
    public List<BlockPos> getWaterSide2() { return waterSide2; }
    public List<BlockPos> getHeatSide1() { return heatSide1; }
    public List<BlockPos> getHeatSide2() { return heatSide2; }
    public List<BlockPos> getHologramSide1() { return hologramSide1; }
    public List<BlockPos> getHologramSide2() { return hologramSide2; }
    public KarmaLevel getKarmaSide1() { return karmaSide1; }
    public KarmaLevel getKarmaSide2() { return karmaSide2; }
    public GateType getGateType() { return gateType; }
    public void setKarmaSide1(World world, KarmaLevel level) { setKarmaForSide(world, Side.SIDE1, level); }
    public void setKarmaSide2(World world, KarmaLevel level) { setKarmaForSide(world, Side.SIDE2, level); }

    public void reapplyKarma(World world) {
        applyKarmaToList(world, hologramSide1, karmaSide1);
        applyKarmaToList(world, hologramSide2, karmaSide2);
    }

    public void setHologramTargetLevelSide1(World world, float level) {
        setHologramTargetLevelForSide(world, Side.SIDE1, level);
    }
    public void setHologramTargetLevelSide2(World world, float level) {
        setHologramTargetLevelForSide(world, Side.SIDE2, level);
    }
    public void setHologramTargetLevels(World world, Float side1, Float side2) {
        if (world == null) return;
        if (side1 != null) setHologramTargetLevelForSide(world, Side.SIDE1, side1);
        if (side2 != null) setHologramTargetLevelForSide(world, Side.SIDE2, side2);
    }
    private void setHologramTargetLevelForSide(World world, Side side, float level) {
        if (world == null) return;
        for (BlockPos pos : side == Side.SIDE1 ? hologramSide1 : hologramSide2) {
            if (world.getBlockEntity(pos) instanceof HologramProjectorBlockEntity hologram) {
                hologram.setTargetLevel(clamp01(level));
            }
        }
    }

    public void setHologramLowPowerSide1(World world, boolean lowPower) {
        setHologramLowPowerForSide(world, Side.SIDE1, lowPower);
    }
    public void setHologramLowPowerSide2(World world, boolean lowPower) {
        setHologramLowPowerForSide(world, Side.SIDE2, lowPower);
    }
    public void setHologramLowPower(World world, Boolean side1, Boolean side2) {
        if (world == null) return;
        if (side1 != null) setHologramLowPowerForSide(world, Side.SIDE1, side1);
        if (side2 != null) setHologramLowPowerForSide(world, Side.SIDE2, side2);
    }
    private void setHologramLowPowerForSide(World world, Side side, boolean lowPower) {
        for (BlockPos pos : side == Side.SIDE1 ? hologramSide1 : hologramSide2) {
            if (world.getBlockEntity(pos) instanceof HologramProjectorBlockEntity hologram) {
                hologram.setLowpower(lowPower);
            }
        }
    }

    public void setKarma(BlockPos hologramPos, KarmaLevel level) {
        World world = controllerBE.getWorld();
        if (world == null || level == null) return;
        Side side = hologramSide1.contains(hologramPos) ? Side.SIDE1
                : hologramSide2.contains(hologramPos) ? Side.SIDE2 : classifySide(hologramPos, world);
        if (side == null) return;
        List<BlockPos> list = side == Side.SIDE1 ? hologramSide1 : hologramSide2;
        if (!list.contains(hologramPos)) list.add(hologramPos);
        setKarmaForSide(world, side, level);
    }

    private Side classifySide(BlockPos pos, World world) {
        BlockState state = world.getBlockState(controllerBE.getPos());
        if (!(state.getBlock() instanceof KarmaGateBlock)) return null;
        Direction.Axis axis = state.get(KarmaGateBlock.AXIS);
        return axisCoordinate(pos, axis) < axisCoordinate(controllerBE.getPos(), axis)
                ? Side.SIDE1 : Side.SIDE2;
    }

    private void setKarmaForSide(World world, Side side, KarmaLevel level) {
        if (level == null) return;
        if (side == Side.SIDE1) karmaSide1 = level;
        else karmaSide2 = level;
        applyKarmaToList(world, side == Side.SIDE1 ? hologramSide1 : hologramSide2, level);
        controllerBE.markDirty();
    }

    private static void applyKarmaToList(World world, List<BlockPos> positions, KarmaLevel level) {
        if (world == null) return;
        for (BlockPos pos : positions) {
            if (world.getBlockEntity(pos) instanceof HologramProjectorBlockEntity hologram) {
                hologram.setKarmaLevelEnum(level);
            }
        }
    }

    public void writeNbt(NbtCompound nbt) {
        writePos(nbt, "gate1", gate1);
        writePos(nbt, "gate2", gate2);
        nbt.putString("gateType", gateType.name());
        nbt.putString("mode", mode.name());
        nbt.putString("entrySide", entrySide == null ? "null" : entrySide.name());
        nbt.putBoolean("dontOpen", dontOpen);
        nbt.putInt("startCounter", startCounter);
        nbt.putInt("washingCounter", washingCounter);
        nbt.putInt("recoveryCounter", recoveryCounter);
        nbt.putInt("lampBlink", lampBlink);
        nbt.putBoolean("recoveryActive", recoveryActive);
        nbt.putFloat("recoveryStartResource", recoveryStartResource);
        nbt.putFloat("waterLeft", waterLeft);
        nbt.putFloat("outletLag", outletLag);
        nbt.putFloat("batteryLeft", batteryLeft);
        nbt.putFloat("electricSteam", electricSteam);
        for (int i = 0; i < 3; i++) {
            nbt.putFloat("doorClosed" + i, doorClosed[i]);
            nbt.putFloat("doorGoal" + i, doorGoal[i]);
        }
        for (int i = 0; i < 2; i++) {
            nbt.putFloat("waterFlow" + i, waterFlow[i]);
            nbt.putFloat("waterSetFlow" + i, waterSetFlow[i]);
            nbt.putFloat("heaterHeat" + i, heaterHeat[i]);
            nbt.putFloat("heaterTarget" + i, heaterTarget[i]);
        }
        for (int i = 0; i < 4; i++) nbt.putBoolean("lamp" + i, lampsOn[i]);
        lightsSide1.writeNbt(nbt, "lightsSide1");
        lightsSide2.writeNbt(nbt, "lightsSide2");
        writePosList(nbt, "waterSide1", waterSide1);
        writePosList(nbt, "waterSide2", waterSide2);
        writePosList(nbt, "heatSide1", heatSide1);
        writePosList(nbt, "heatSide2", heatSide2);
        writePosList(nbt, "steamSide1", steamSide1);
        writePosList(nbt, "steamSide2", steamSide2);
        writePosList(nbt, "holoSide1", hologramSide1);
        writePosList(nbt, "holoSide2", hologramSide2);
        nbt.putString("karmaSide1", karmaSide1.name());
        nbt.putString("karmaSide2", karmaSide2.name());
    }

    public void readNbt(NbtCompound nbt) {
        gate1 = readPos(nbt, "gate1");
        gate2 = readPos(nbt, "gate2");
        boolean storedGateType = nbt.contains("gateType");
        gateType = readEnum(nbt.getString("gateType"), GateType.class, GateType.BROKEN);
        mode = readEnum(nbt.getString("mode"), Mode.class, Mode.MiddleClosed);
        entrySide = "null".equals(nbt.getString("entrySide"))
                ? null : readEnum(nbt.getString("entrySide"), Side.class, null);
        dontOpen = nbt.getBoolean("dontOpen");
        startCounter = nbt.contains("startCounter") ? nbt.getInt("startCounter")
                : Math.max(nbt.getInt("prepare1"), nbt.getInt("prepare2")) * 2;
        washingCounter = nbt.contains("washingCounter") ? nbt.getInt("washingCounter")
                : nbt.getInt("washTicks") * 2;
        recoveryCounter = nbt.getInt("recoveryCounter");
        lampBlink = nbt.getInt("lampBlink");
        recoveryActive = nbt.getBoolean("recoveryActive");
        recoveryStartResource = nbt.contains("recoveryStartResource")
                ? nbt.getFloat("recoveryStartResource") : 1.0f;
        waterLeft = nbt.contains("waterLeft") ? nbt.getFloat("waterLeft") : 1.0f;
        outletLag = nbt.getFloat("outletLag");
        batteryLeft = nbt.contains("batteryLeft") ? nbt.getFloat("batteryLeft") : 1.0f;
        electricSteam = nbt.getFloat("electricSteam");
        for (int i = 0; i < 3; i++) {
            doorClosed[i] = nbt.contains("doorClosed" + i) ? nbt.getFloat("doorClosed" + i)
                    : i == 1 ? 1.0f : 0.0f;
            doorGoal[i] = nbt.contains("doorGoal" + i) ? nbt.getFloat("doorGoal" + i)
                    : i == 1 ? 1.0f : 0.0f;
        }
        for (int i = 0; i < 2; i++) {
            waterFlow[i] = nbt.getFloat("waterFlow" + i);
            waterSetFlow[i] = nbt.getFloat("waterSetFlow" + i);
            heaterHeat[i] = nbt.getFloat("heaterHeat" + i);
            heaterTarget[i] = nbt.getFloat("heaterTarget" + i);
        }
        for (int i = 0; i < 4; i++) lampsOn[i] = nbt.getBoolean("lamp" + i);
        lightsSide1.readNbt(nbt, "lightsSide1");
        lightsSide2.readNbt(nbt, "lightsSide2");
        readPosList(nbt, "waterSide1", waterSide1);
        readPosList(nbt, "waterSide2", waterSide2);
        readPosList(nbt, "heatSide1", heatSide1);
        readPosList(nbt, "heatSide2", heatSide2);
        readPosList(nbt, "steamSide1", steamSide1);
        readPosList(nbt, "steamSide2", steamSide2);
        readPosList(nbt, "holoSide1", hologramSide1);
        readPosList(nbt, "holoSide2", hologramSide2);
        if (!storedGateType) gateType = inferGateType();
        karmaSide1 = readEnum(nbt.getString("karmaSide1"), KarmaLevel.class, KarmaLevel.LEVEL_0);
        karmaSide2 = readEnum(nbt.getString("karmaSide2"), KarmaLevel.class, KarmaLevel.LEVEL_0);
    }

    private static void writePos(NbtCompound root, String key, BlockPos pos) {
        if (pos == null) return;
        NbtCompound value = new NbtCompound();
        value.putInt("x", pos.getX());
        value.putInt("y", pos.getY());
        value.putInt("z", pos.getZ());
        root.put(key, value);
    }

    private static BlockPos readPos(NbtCompound root, String key) {
        if (!root.contains(key)) return null;
        NbtCompound value = root.getCompound(key);
        return new BlockPos(value.getInt("x"), value.getInt("y"), value.getInt("z"));
    }

    private static void writePosList(NbtCompound root, String key, List<BlockPos> positions) {
        NbtCompound value = new NbtCompound();
        value.putInt("n", positions.size());
        for (int i = 0; i < positions.size(); i++) writePos(value, "p" + i, positions.get(i));
        root.put(key, value);
    }

    private static void readPosList(NbtCompound root, String key, List<BlockPos> output) {
        output.clear();
        if (!root.contains(key)) return;
        NbtCompound value = root.getCompound(key);
        for (int i = 0; i < value.getInt("n"); i++) {
            BlockPos pos = readPos(value, "p" + i);
            if (pos != null) output.add(pos);
        }
    }

    private static <E extends Enum<E>> E readEnum(String value, Class<E> type, E fallback) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return fallback; }
    }

    public BlockPos getPos() { return controllerBE.getPos(); }

    private static float clamp01(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }
    private static float lerp(float from, float to, float amount) { return from + (to - from) * amount; }
    private static float inverseLerp(float from, float to, float value) {
        return clamp01((value - from) / (to - from));
    }
    private static float moveTowards(float current, float target, float maximumDelta) {
        if (Math.abs(target - current) <= maximumDelta) return target;
        return current + Math.copySign(maximumDelta, target - current);
    }

    private record Region(double centerX, double centerZ, double halfX, double halfZ, double axisHalf) {
        boolean contains(PlayerEntity player) {
            return Math.abs(player.getX() - centerX) <= halfX
                    && Math.abs(player.getZ() - centerZ) <= halfZ;
        }
    }

    private record Detection(boolean side1, boolean side2, boolean center,
                             boolean side1Stationary, boolean side2Stationary) {
        Side startingSide() {
            if (center || side1 == side2) return null;
            return side1 ? Side.SIDE1 : Side.SIDE2;
        }
        boolean allThrough(Side entry) {
            return !center && (entry == Side.SIDE1 ? !side1 && side2 : !side2 && side1);
        }
        boolean anyInsideGate() { return side1 || side2 || center; }
    }
}
