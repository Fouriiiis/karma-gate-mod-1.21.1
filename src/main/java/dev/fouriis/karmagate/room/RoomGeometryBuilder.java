package dev.fouriis.karmagate.room;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import org.jetbrains.annotations.Nullable;
import rainworld.mechanics.common.block.pipes.PipeBlock;
import rainworld.mechanics.common.block.pipes.PipeEntrance;
import rainworld.mechanics.common.block.pipes.ShelterPipeEntrance;
import rainworld.mechanics.common.block.pipes.TelePipeBlock;
import rainworld.mechanics.common.block.pipes.TelePipeBlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bakes room geometry from the world once at room creation time.
 */
public final class RoomGeometryBuilder {

    private static final float SHAPE_UNIT = 16.0f;

    private RoomGeometryBuilder() {
    }

    public static RoomGeometry build(ServerWorld world, RoomData room) {
        Map<FaceKey, Face> exteriorFaces = new HashMap<>();
        Map<PipeLinkKey, RoomGeometry.PipeLinkData> pipeLinks = new HashMap<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        BlockPos min = room.getMin();
        BlockPos max = room.getMax();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    mutable.set(x, y, z);
                    BlockState state = world.getBlockState(mutable);
                    if (state.isAir()) {
                        continue;
                    }

                    if (state.getBlock() instanceof PipeEntrance) {
                        BlockPos exitPos = findOtherEntrance(world, mutable);
                        if (exitPos != null) {
                            Direction startDir = getEntranceDirection(state);
                            Direction endDir = getEntranceDirection(world.getBlockState(exitPos));
                            pipeLinks.putIfAbsent(
                                pipeLinkKey(mutable, exitPos),
                                new RoomGeometry.PipeLinkData(
                                    mutable.getX(), mutable.getY(), mutable.getZ(),
                                    exitPos.getX(), exitPos.getY(), exitPos.getZ(),
                                    startDir == null ? null : startDir.name(),
                                    endDir == null ? null : endDir.name()
                                )
                            );
                        }
                    }

                    VoxelShape shape = state.getCollisionShape(world, mutable);
                    if (shape.isEmpty()) {
                        continue;
                    }

                    final int blockX = x;
                    final int blockY = y;
                    final int blockZ = z;

                    shape.forEachBox((boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ) -> {
                        int x0 = toUnit(blockX + boxMinX);
                        int y0 = toUnit(blockY + boxMinY);
                        int z0 = toUnit(blockZ + boxMinZ);
                        int x1 = toUnit(blockX + boxMaxX);
                        int y1 = toUnit(blockY + boxMaxY);
                        int z1 = toUnit(blockZ + boxMaxZ);

                        if (x0 == x1 || y0 == y1 || z0 == z1) {
                            return;
                        }

                        addBoxFaces(exteriorFaces, x0, y0, z0, x1, y1, z1);
                    });
                }
            }
        }

        List<RoomGeometry.FaceData> faces = new ArrayList<>();
        for (Face face : exteriorFaces.values()) {
            faces.add(new RoomGeometry.FaceData(
                face.axis.name(),
                face.plane,
                face.a0,
                face.b0,
                face.a1,
                face.b1,
                face.normalSign
            ));
        }

        List<RoomGeometry.LineData> lines = mergeCollinearEdges(buildMergedSurfaceEdges(exteriorFaces.values()));
        return new RoomGeometry(faces, lines, new ArrayList<>(pipeLinks.values()));
    }

    private static @Nullable BlockPos findOtherEntrance(ServerWorld world, BlockPos entrancePos) {
        BlockState entranceState = world.getBlockState(entrancePos);
        if (!(entranceState.getBlock() instanceof PipeEntrance)) {
            return null;
        }

        PipeEntrance.Orientation connection = entranceState.get(PipeEntrance.CONNECTION);
        Direction initialDirection = getNextDirection(world, entrancePos, connection.getDirection());
        return traceToExit(world, entrancePos, initialDirection);
    }

    private static @Nullable BlockPos traceToExit(ServerWorld world, BlockPos startPos, @Nullable Direction direction) {
        if (direction == null) {
            return null;
        }

        BlockPos nextPos = startPos.offset(direction);
        BlockState nextState = world.getBlockState(nextPos);
        Block nextBlock = nextState.getBlock();
        int steps = 0;

        while (isPipeNetworkBlock(nextBlock)) {
            if (isEntranceBlock(nextBlock) && !nextPos.equals(startPos)) {
                return nextPos;
            }

            if (nextBlock instanceof TelePipeBlock) {
                BlockPos linkedPos = getLinkedPos(world, nextPos);
                if (linkedPos == null) {
                    return null;
                }

                direction = getNextDirection(world, linkedPos, direction);
                if (direction == null) {
                    return null;
                }

                nextPos = linkedPos.offset(direction);
                nextState = world.getBlockState(nextPos);
                nextBlock = nextState.getBlock();
            } else {
                direction = getNextDirection(world, nextPos, direction);
                if (direction == null) {
                    return null;
                }

                nextPos = nextPos.offset(direction);
                nextState = world.getBlockState(nextPos);
                nextBlock = nextState.getBlock();
            }

            steps++;
            if (steps >= 4096) {
                return null;
            }
        }

        return null;
    }

    private static @Nullable BlockPos getLinkedPos(ServerWorld world, BlockPos telePos) {
        if (world.getBlockEntity(telePos) instanceof TelePipeBlockEntity telePipe) {
            return telePipe.linkedPos;
        }

        return null;
    }

    private static boolean isPipeNetworkBlock(Block block) {
        return block instanceof PipeBlock
            || block instanceof PipeEntrance
            || block instanceof TelePipeBlock
            || block instanceof ShelterPipeEntrance;
    }

    private static boolean isEntranceBlock(Block block) {
        return block instanceof PipeEntrance || block instanceof ShelterPipeEntrance;
    }

    private static @Nullable Direction getNextDirection(ServerWorld world, BlockPos pos, Direction currentDirection) {
        int connections = 0;
        Direction nextDirection = currentDirection;

        for (Direction direction : Direction.values()) {
            if (direction != currentDirection.getOpposite() || world.getBlockState(pos).getBlock() instanceof TelePipeBlock) {
                BlockState neighborState = world.getBlockState(pos.offset(direction));
                Block neighborBlock = neighborState.getBlock();
                if (neighborBlock instanceof PipeBlock
                    || neighborBlock instanceof PipeEntrance
                    || neighborBlock instanceof TelePipeBlock
                    || neighborBlock instanceof ShelterPipeEntrance) {
                    connections++;
                    if (connections == 1 || connections == 2) {
                        nextDirection = direction;
                    }
                }
            }
        }

        if (connections == 0) {
            return null;
        }

        if (connections > 2) {
            nextDirection = currentDirection;
        }

        return nextDirection;
    }

    private static Direction getEntranceDirection(BlockState state) {
        if (!(state.getBlock() instanceof PipeEntrance)) {
            return null;
        }

        PipeEntrance.Orientation orientation = state.get(PipeEntrance.ORIENTATION);
        return orientation == null ? null : orientation.getDirection();
    }

    private static int toUnit(double value) {
        return (int) Math.round(value * SHAPE_UNIT);
    }

    private static void addBoxFaces(Map<FaceKey, Face> faces,
                                    int x0, int y0, int z0,
                                    int x1, int y1, int z1) {
        toggleFace(faces, Axis.X, x0, y0, z0, y1, z1, -1);
        toggleFace(faces, Axis.X, x1, y0, z0, y1, z1, 1);

        toggleFace(faces, Axis.Y, y0, x0, z0, x1, z1, -1);
        toggleFace(faces, Axis.Y, y1, x0, z0, x1, z1, 1);

        toggleFace(faces, Axis.Z, z0, x0, y0, x1, y1, -1);
        toggleFace(faces, Axis.Z, z1, x0, y0, x1, y1, 1);
    }

    private static void toggleFace(Map<FaceKey, Face> faces,
                                   Axis axis,
                                   int plane,
                                   int a0,
                                   int b0,
                                   int a1,
                                   int b1,
                                   int normalSign) {
        FaceKey key = new FaceKey(axis, plane, a0, b0, a1, b1);
        if (faces.containsKey(key)) {
            faces.remove(key);
        } else {
            faces.put(key, new Face(axis, plane, a0, b0, a1, b1, normalSign));
        }
    }

    private static List<EdgeKey> buildMergedSurfaceEdges(Iterable<Face> faces) {
        Map<EdgeKey, EdgeUse> edgeUses = new HashMap<>();

        for (Face face : faces) {
            addFaceEdgeUses(edgeUses, face);
        }

        List<EdgeKey> outlineEdges = new ArrayList<>();
        for (Map.Entry<EdgeKey, EdgeUse> entry : edgeUses.entrySet()) {
            if (entry.getValue().isVisibleOutline()) {
                outlineEdges.add(entry.getKey());
            }
        }

        return outlineEdges;
    }

    private static void addFaceEdgeUses(Map<EdgeKey, EdgeUse> edgeUses, Face face) {
        PlaneKey plane = new PlaneKey(face.axis, face.plane);
        switch (face.axis) {
            case X -> {
                int x = face.plane;
                addEdgeUse(edgeUses, plane, x, face.a0, face.b0, x, face.a1, face.b0);
                addEdgeUse(edgeUses, plane, x, face.a1, face.b0, x, face.a1, face.b1);
                addEdgeUse(edgeUses, plane, x, face.a1, face.b1, x, face.a0, face.b1);
                addEdgeUse(edgeUses, plane, x, face.a0, face.b1, x, face.a0, face.b0);
            }
            case Y -> {
                int y = face.plane;
                addEdgeUse(edgeUses, plane, face.a0, y, face.b0, face.a1, y, face.b0);
                addEdgeUse(edgeUses, plane, face.a1, y, face.b0, face.a1, y, face.b1);
                addEdgeUse(edgeUses, plane, face.a1, y, face.b1, face.a0, y, face.b1);
                addEdgeUse(edgeUses, plane, face.a0, y, face.b1, face.a0, y, face.b0);
            }
            case Z -> {
                int z = face.plane;
                addEdgeUse(edgeUses, plane, face.a0, face.b0, z, face.a1, face.b0, z);
                addEdgeUse(edgeUses, plane, face.a1, face.b0, z, face.a1, face.b1, z);
                addEdgeUse(edgeUses, plane, face.a1, face.b1, z, face.a0, face.b1, z);
                addEdgeUse(edgeUses, plane, face.a0, face.b1, z, face.a0, face.b0, z);
            }
        }
    }

    private static void addEdgeUse(Map<EdgeKey, EdgeUse> edgeUses,
                                   PlaneKey plane,
                                   int x1, int y1, int z1,
                                   int x2, int y2, int z2) {
        EdgeKey key = edgeKey(x1, y1, z1, x2, y2, z2);
        edgeUses.computeIfAbsent(key, ignored -> new EdgeUse()).add(plane);
    }

    private static List<RoomGeometry.LineData> mergeCollinearEdges(List<EdgeKey> edges) {
        Map<LineKey, List<IntRange>> rangesByLine = new HashMap<>();

        for (EdgeKey edge : edges) {
            if (edge.x1 == edge.x2 && edge.y1 == edge.y2 && edge.z1 == edge.z2) {
                continue;
            }

            LineKey line;
            int start;
            int end;

            if (edge.x1 != edge.x2) {
                line = new LineKey(0, edge.y1, edge.z1);
                start = Math.min(edge.x1, edge.x2);
                end = Math.max(edge.x1, edge.x2);
            } else if (edge.y1 != edge.y2) {
                line = new LineKey(1, edge.x1, edge.z1);
                start = Math.min(edge.y1, edge.y2);
                end = Math.max(edge.y1, edge.y2);
            } else {
                line = new LineKey(2, edge.x1, edge.y1);
                start = Math.min(edge.z1, edge.z2);
                end = Math.max(edge.z1, edge.z2);
            }

            rangesByLine.computeIfAbsent(line, ignored -> new ArrayList<>()).add(new IntRange(start, end));
        }

        List<RoomGeometry.LineData> merged = new ArrayList<>();
        for (Map.Entry<LineKey, List<IntRange>> entry : rangesByLine.entrySet()) {
            LineKey line = entry.getKey();
            List<IntRange> ranges = entry.getValue();
            ranges.sort((a, b) -> {
                if (a.start != b.start) {
                    return Integer.compare(a.start, b.start);
                }
                return Integer.compare(a.end, b.end);
            });

            int currentStart = Integer.MIN_VALUE;
            int currentEnd = Integer.MIN_VALUE;

            for (IntRange range : ranges) {
                if (currentStart == Integer.MIN_VALUE) {
                    currentStart = range.start;
                    currentEnd = range.end;
                    continue;
                }

                if (range.start <= currentEnd) {
                    currentEnd = Math.max(currentEnd, range.end);
                } else {
                    addMergedLine(merged, line, currentStart, currentEnd);
                    currentStart = range.start;
                    currentEnd = range.end;
                }
            }

            if (currentStart != Integer.MIN_VALUE) {
                addMergedLine(merged, line, currentStart, currentEnd);
            }
        }

        return merged;
    }

    private static void addMergedLine(List<RoomGeometry.LineData> merged, LineKey line, int start, int end) {
        if (start == end) {
            return;
        }

        switch (line.axis) {
            case 0 -> merged.add(new RoomGeometry.LineData(start, line.fixedA, line.fixedB, end, line.fixedA, line.fixedB));
            case 1 -> merged.add(new RoomGeometry.LineData(line.fixedA, start, line.fixedB, line.fixedA, end, line.fixedB));
            case 2 -> merged.add(new RoomGeometry.LineData(line.fixedA, line.fixedB, start, line.fixedA, line.fixedB, end));
        }
    }

    private static PipeLinkKey pipeLinkKey(BlockPos a, BlockPos b) {
        if (compareBlockPos(a, b) > 0) {
            BlockPos temp = a;
            a = b;
            b = temp;
        }
        return new PipeLinkKey(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    private static int compareBlockPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) {
            return Integer.compare(a.getX(), b.getX());
        }
        if (a.getY() != b.getY()) {
            return Integer.compare(a.getY(), b.getY());
        }
        return Integer.compare(a.getZ(), b.getZ());
    }

    private static EdgeKey edgeKey(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (compare(x1, y1, z1, x2, y2, z2) > 0) {
            int tx = x1;
            int ty = y1;
            int tz = z1;
            x1 = x2;
            y1 = y2;
            z1 = z2;
            x2 = tx;
            y2 = ty;
            z2 = tz;
        }
        return new EdgeKey(x1, y1, z1, x2, y2, z2);
    }

    private static int compare(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (x1 != x2) {
            return Integer.compare(x1, x2);
        }
        if (y1 != y2) {
            return Integer.compare(y1, y2);
        }
        return Integer.compare(z1, z2);
    }

    private record PipeLinkKey(int ax, int ay, int az, int bx, int by, int bz) {
    }

    private enum Axis {
        X,
        Y,
        Z
    }

    private record Face(Axis axis, int plane, int a0, int b0, int a1, int b1, int normalSign) {
    }

    private record FaceKey(Axis axis, int plane, int a0, int b0, int a1, int b1) {
    }

    private record PlaneKey(Axis axis, int plane) {
    }

    private record LineKey(int axis, int fixedA, int fixedB) {
    }

    private record EdgeKey(int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    private record IntRange(int start, int end) {
    }

    private static final class EdgeUse {
        private final Map<PlaneKey, Integer> planes = new HashMap<>();

        private void add(PlaneKey plane) {
            planes.merge(plane, 1, Integer::sum);
        }

        private boolean isVisibleOutline() {
            for (int count : planes.values()) {
                if ((count & 1) == 1) {
                    return true;
                }
            }
            return false;
        }
    }
}
