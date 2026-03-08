package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/**
 * Rain World-inspired centipede pathfinder for Minecraft.
 *
 * Design goals:
 * - Reverse pathfinding from destination outward, like Rain World's PathFinder.
 * - Separate accessibility mapping from path-cost propagation.
 * - Distinguish:
 *     reachable            = can get there from the creature's current local area
 *     possibleToGetBackFrom = can safely return / not a point of no return
 * - Use legality + resistance, not just one scalar cost.
 * - Let movement follow the pathfield by choosing the best local next move,
 *   instead of trusting a static waypoint path as ground truth.
 *
 * Important approximation notes:
 * - Rain World has a hand-authored AI map and creature-specific MovementConnections.
 *   Minecraft does not, so this class synthesizes a local graph from nearby voxel cells.
 * - "possibleToGetBackFrom" is approximated using a stricter flood fill over locally
 *   valid cells; it is still much closer to Rain World's model than plain A*.
 * - This class is bounded to a local search volume for performance.
 */
public final class CentipedePathfinder {

    private CentipedePathfinder() {
    }

    // =========================================================================
    // Config
    // =========================================================================

    public static final int DEFAULT_MAX_RANGE = 40;
    public static final int DEFAULT_ACCESSIBILITY_STEPS = 200;
    public static final int DEFAULT_PATH_STEPS = 300;
    public static final int DEFAULT_FOLLOW_LOOK_RADIUS = 2;

    /** StandardPather-like: prioritize true path cost heavily, current-creature distance lightly. */
    private static final double HEURISTIC_COST_FAC = 40.0;
    private static final double HEURISTIC_DEST_FAC = 1.0;

    /** Surface hugging preference; tiles at proximity 1 are preferred. */
    private static final double SURFACE_PREFERENCE_PENALTY = 0.35;

    /** Mild penalty for leaving strong surface contact. */
    private static final double LOOSE_SURFACE_PENALTY = 0.20;

    /** Movement resistance by connection type. */
    private static final double FACE_MOVE_COST = 1.00;
    private static final double EDGE_MOVE_COST = 1.45;
    private static final double CORNER_MOVE_COST = 1.80;

    /** Penalty for unsupported / risky transfers that are still technically possible. */
    private static final double UNWANTED_MOVE_PENALTY = 0.75;

    /** How many recent connections make a move "off limits" / unwanted. */
    private static final int RECENT_CONNECTION_LIMIT = 3;
    private static final int SAVED_RECENT_CONNECTIONS = 20;

    /** Search hard caps. */
    private static final int MAX_ACCESSIBILITY_VISITED = 12000;
    private static final int MAX_PATH_VISITED = 20000;

    // =========================================================================
    // Legality / cost
    // =========================================================================

    /**
     * Ordering mirrors Rain World's behavior:
     * lower ordinal = better.
     */
    public enum Legality {
        ALLOWED,
        UNWANTED,
        ILLEGAL,
        UNALLOWED
    }

    public static final class PathCost implements Comparable<PathCost> {
        public final double resistance;
        public final Legality legality;

        public PathCost(double resistance, Legality legality) {
            this.resistance = resistance;
            this.legality = legality;
        }

        public boolean allowed() {
            return legality.ordinal() <= Legality.UNWANTED.ordinal();
        }

        public boolean considerable() {
            return legality != Legality.UNALLOWED;
        }

        public PathCost plus(PathCost other) {
            Legality worst = this.legality.ordinal() >= other.legality.ordinal() ? this.legality : other.legality;
            return new PathCost(this.resistance + other.resistance, worst);
        }

        @Override
        public int compareTo(PathCost o) {
            if (this.legality != o.legality) {
                return Integer.compare(this.legality.ordinal(), o.legality.ordinal());
            }
            return Double.compare(this.resistance, o.resistance);
        }

        public boolean betterThan(PathCost o) {
            return compareTo(o) < 0;
        }

        @Override
        public String toString() {
            return "PathCost{" + legality + ", " + resistance + '}';
        }
    }

    // =========================================================================
    // Local graph
    // =========================================================================

    public enum MoveType {
        FACE,
        EDGE_DIAGONAL,
        CORNER_DIAGONAL,
        OFF_SURFACE_TRANSFER
    }

    public static final class MovementConnection {
        public final BlockPos start;
        public final BlockPos end;
        public final MoveType type;

        public MovementConnection(BlockPos start, BlockPos end, MoveType type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MovementConnection other)) return false;
            return start.equals(other.start) && end.equals(other.end) && type == other.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end, type);
        }
    }

    private static final class PathingCell {
        final BlockPos pos;
        int generation = -1;
        PathCost heuristicValue = new PathCost(Double.POSITIVE_INFINITY, Legality.UNALLOWED);
        PathCost costToGoal = new PathCost(Double.POSITIVE_INFINITY, Legality.UNALLOWED);
        boolean inOpen = false;
        boolean reachable = false;
        boolean possibleToGetBackFrom = false;

        PathingCell(BlockPos pos) {
            this.pos = pos;
        }
    }

    private static final class LocalCache {
        final World world;
        final BlockPos center;
        final int range;

        private final Map<BlockPos, Integer> terrainProximityCache = new HashMap<>();
        private final Map<BlockPos, Boolean> occupiableCache = new HashMap<>();
        private final Map<BlockPos, Boolean> accessibleCache = new HashMap<>();
        private final Map<BlockPos, List<MovementConnection>> outgoingCache = new HashMap<>();
        private final Map<BlockPos, List<MovementConnection>> incomingCache = new HashMap<>();
        private final Map<BlockPos, PathingCell> cells = new HashMap<>();

        LocalCache(World world, BlockPos center, int range) {
            this.world = world;
            this.center = center;
            this.range = range;
        }

        boolean inBounds(BlockPos pos) {
            return Math.abs(pos.getX() - center.getX()) <= range
                && Math.abs(pos.getY() - center.getY()) <= range
                && Math.abs(pos.getZ() - center.getZ()) <= range;
        }

        PathingCell cell(BlockPos pos) {
            return cells.computeIfAbsent(pos.toImmutable(), PathingCell::new);
        }

        boolean occupiable(BlockPos pos) {
            return occupiableCache.computeIfAbsent(pos.toImmutable(), p -> {
                if (!inBounds(p)) return false;
                BlockState state = world.getBlockState(p);
                return !state.blocksMovement();
            });
        }

        int terrainProximity(BlockPos pos) {
            return terrainProximityCache.computeIfAbsent(pos.toImmutable(), p -> {
                if (!occupiable(p)) return 0;

                for (Direction d : Direction.values()) {
                    if (world.getBlockState(p.offset(d)).blocksMovement()) {
                        return 1;
                    }
                }

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos q = p.add(dx, dy, dz);
                            if (!inBounds(q)) continue;
                            if (world.getBlockState(q).blocksMovement()) {
                                return 2;
                            }
                        }
                    }
                }

                return 3;
            });
        }

        boolean accessible(BlockPos pos) {
            return accessibleCache.computeIfAbsent(pos.toImmutable(), p -> {
                if (!occupiable(p)) return false;
                int prox = terrainProximity(p);
                return prox >= 1 && prox <= 2;
            });
        }

        List<MovementConnection> outgoing(BlockPos pos) {
            return outgoingCache.computeIfAbsent(pos.toImmutable(), this::buildOutgoing);
        }

        List<MovementConnection> incoming(BlockPos pos) {
            return incomingCache.computeIfAbsent(pos.toImmutable(), this::buildIncoming);
        }

        private List<MovementConnection> buildOutgoing(BlockPos from) {
            if (!accessible(from)) return Collections.emptyList();

            List<MovementConnection> out = new ArrayList<>(26);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        BlockPos to = from.add(dx, dy, dz);
                        if (!inBounds(to)) continue;
                        if (!accessible(to)) continue;

                        int nonZero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
                        MoveType moveType;
                        if (nonZero == 1) {
                            moveType = MoveType.FACE;
                        } else if (nonZero == 2) {
                            if (!canMoveDiagonalEdge(from, dx, dy, dz)) continue;
                            moveType = MoveType.EDGE_DIAGONAL;
                        } else {
                            if (!canMoveDiagonalCorner(from, dx, dy, dz)) continue;
                            moveType = MoveType.CORNER_DIAGONAL;
                        }

                        // Surface transfer detection: still allowed, but may be marked unwanted by cost.
                        if (terrainProximity(from) == 1 && terrainProximity(to) == 2) {
                            moveType = MoveType.OFF_SURFACE_TRANSFER;
                        }

                        out.add(new MovementConnection(from, to, moveType));
                    }
                }
            }

            return out;
        }

        private List<MovementConnection> buildIncoming(BlockPos to) {
            if (!accessible(to)) return Collections.emptyList();

            List<MovementConnection> in = new ArrayList<>(26);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        BlockPos from = to.add(-dx, -dy, -dz);
                        if (!inBounds(from)) continue;
                        if (!accessible(from)) continue;

                        int nonZero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
                        MoveType moveType;
                        if (nonZero == 1) {
                            moveType = MoveType.FACE;
                        } else if (nonZero == 2) {
                            if (!canMoveDiagonalEdge(from, dx, dy, dz)) continue;
                            moveType = MoveType.EDGE_DIAGONAL;
                        } else {
                            if (!canMoveDiagonalCorner(from, dx, dy, dz)) continue;
                            moveType = MoveType.CORNER_DIAGONAL;
                        }

                        if (terrainProximity(from) == 1 && terrainProximity(to) == 2) {
                            moveType = MoveType.OFF_SURFACE_TRANSFER;
                        }

                        in.add(new MovementConnection(from, to, moveType));
                    }
                }
            }

            return in;
        }

        private boolean canMoveDiagonalEdge(BlockPos from, int dx, int dy, int dz) {
            // At least one of the relevant face slides must remain occupiable.
            if (dx != 0 && dy != 0 && dz == 0) {
                return occupiable(from.add(dx, 0, 0)) || occupiable(from.add(0, dy, 0));
            }
            if (dx != 0 && dz != 0 && dy == 0) {
                return occupiable(from.add(dx, 0, 0)) || occupiable(from.add(0, 0, dz));
            }
            if (dy != 0 && dz != 0 && dx == 0) {
                return occupiable(from.add(0, dy, 0)) || occupiable(from.add(0, 0, dz));
            }
            return false;
        }

        private boolean canMoveDiagonalCorner(BlockPos from, int dx, int dy, int dz) {
            int passable = 0;
            if (occupiable(from.add(dx, 0, 0))) passable++;
            if (occupiable(from.add(0, dy, 0))) passable++;
            if (occupiable(from.add(0, 0, dz))) passable++;
            return passable >= 2;
        }
    }

    // =========================================================================
    // Incremental reverse pathfield
    // =========================================================================

    public static final class Search {
        private final World world;
        private final BlockPos start;
        private BlockPos destination;
        private final int maxRange;
        private final LocalCache cache;

        private final List<PathingCell> open = new ArrayList<>();
        private final Deque<BlockPos> accQueueReachable = new ArrayDeque<>();
        private final Deque<BlockPos> accQueueReturnable = new ArrayDeque<>();

        private final Deque<MovementConnection> pastConnections = new ArrayDeque<>();
        private final Set<BlockPos> accVisited = new HashSet<>();
        private final Set<BlockPos> retVisited = new HashSet<>();

        private int generation = 1;
        private boolean mappingFinished = false;
        private boolean pathFinished = false;
        private boolean lookingForImpossiblePath = false;

        private PathingCell closestCellToDestinationFromStart = null;

        public Search(World world, BlockPos creatureStart, BlockPos rawDestination, int maxRange) {
            this.world = world;
            this.maxRange = maxRange > 0 ? maxRange : DEFAULT_MAX_RANGE;

            BlockPos adjustedStart = findNearestAccessible(world, creatureStart, 6, this.maxRange);
            if (adjustedStart == null) adjustedStart = creatureStart;
            this.start = adjustedStart.toImmutable();

            BlockPos adjustedDest = findNearestAccessible(world, rawDestination, 6, this.maxRange);
            this.destination = (adjustedDest != null ? adjustedDest : rawDestination).toImmutable();

            BlockPos center = midpoint(this.start, this.destination);
            this.cache = new LocalCache(world, center, this.maxRange + 4);

            beginAccessibilityMapping();
        }

        public BlockPos getStart() {
            return start;
        }

        public BlockPos getDestination() {
            return destination;
        }

        public boolean isAccessibilityFinished() {
            return mappingFinished;
        }

        public boolean isPathfieldFinished() {
            return pathFinished;
        }

        public boolean isFinished() {
            return mappingFinished && pathFinished;
        }

        public boolean isLookingForImpossiblePath() {
            return lookingForImpossiblePath;
        }

        public void setDestination(BlockPos newDestination) {
            BlockPos adjusted = findNearestAccessible(world, newDestination, 6, maxRange);
            this.destination = (adjusted != null ? adjusted : newDestination).toImmutable();
            this.generation++;
            this.pathFinished = false;
            this.open.clear();
            this.closestCellToDestinationFromStart = null;

            if (mappingFinished) {
                seedDestination();
            }
        }

        public void update(int accessibilitySteps, int pathSteps) {
            if (!mappingFinished) {
                stepAccessibility(accessibilitySteps <= 0 ? DEFAULT_ACCESSIBILITY_STEPS : accessibilitySteps);
            } else if (!pathFinished) {
                stepPathfield(pathSteps <= 0 ? DEFAULT_PATH_STEPS : pathSteps);
            }
        }

        // ---------------------------------------------------------------------
        // Accessibility mapping
        // ---------------------------------------------------------------------

        private void beginAccessibilityMapping() {
            mappingFinished = false;
            pathFinished = false;
            open.clear();
            accQueueReachable.clear();
            accQueueReturnable.clear();
            accVisited.clear();
            retVisited.clear();

            for (PathingCell cell : cache.cells.values()) {
                cell.reachable = false;
                cell.possibleToGetBackFrom = false;
                cell.inOpen = false;
                cell.generation = -1;
                cell.costToGoal = new PathCost(Double.POSITIVE_INFINITY, Legality.UNALLOWED);
                cell.heuristicValue = new PathCost(Double.POSITIVE_INFINITY, Legality.UNALLOWED);
            }

            if (cache.accessible(start)) {
                cache.cell(start).reachable = true;
                accQueueReachable.add(start);
                accVisited.add(start);
            }
        }

        private void stepAccessibility(int steps) {
            int visitedCount = 0;

            // Phase 1: reachable flood
            while (steps > 0 && !accQueueReachable.isEmpty() && visitedCount < MAX_ACCESSIBILITY_VISITED) {
                BlockPos cur = accQueueReachable.pollFirst();
                visitedCount++;
                steps--;

                for (MovementConnection c : cache.outgoing(cur)) {
                    if (!accVisited.add(c.end)) continue;
                    PathingCell cell = cache.cell(c.end);
                    cell.reachable = true;
                    accQueueReachable.addLast(c.end);
                }
            }

            if (!accQueueReachable.isEmpty()) {
                return;
            }

            // Start returnability phase once reachable fill is done.
            if (accQueueReturnable.isEmpty() && retVisited.isEmpty()) {
                if (cache.accessible(start)) {
                    cache.cell(start).possibleToGetBackFrom = true;
                    accQueueReturnable.add(start);
                    retVisited.add(start);
                }
            }

            while (steps > 0 && !accQueueReturnable.isEmpty() && visitedCount < MAX_ACCESSIBILITY_VISITED) {
                BlockPos cur = accQueueReturnable.pollFirst();
                visitedCount++;
                steps--;

                for (MovementConnection c : cache.incoming(cur)) {
                    if (!retVisited.add(c.start)) continue;
                    PathingCell cell = cache.cell(c.start);
                    cell.possibleToGetBackFrom = true;
                    accQueueReturnable.addLast(c.start);
                }
            }

            if (accQueueReturnable.isEmpty()) {
                mappingFinished = true;
                seedDestination();
            }
        }

        // ---------------------------------------------------------------------
        // Reverse pathfield
        // ---------------------------------------------------------------------

        private void seedDestination() {
    open.clear();

    PathingCell destCell = cache.cell(destination);
    lookingForImpossiblePath = !(destCell.reachable);

    PathCost destLegality = coordinateLegality(destination);

    destCell.generation = generation;
    destCell.costToGoal = new PathCost(0.0, destLegality.legality);
    destCell.heuristicValue = new PathCost(0.0, destLegality.legality);
    destCell.inOpen = true;
    addToOpen(destCell);

    closestCellToDestinationFromStart = destCell;
}

        private void stepPathfield(int steps) {
            int visited = 0;

            while (steps > 0 && !open.isEmpty() && visited < MAX_PATH_VISITED) {
                PathingCell current = open.remove(0);
                current.inOpen = false;
                visited++;
                steps--;

                checkNeighbours(current);

                if (current.pos.equals(start)) {
                    pathFinished = true;
                    return;
                }
            }

            if (open.isEmpty()) {
                pathFinished = true;
            }
        }

        private void checkNeighbours(PathingCell checkNow) {
            for (MovementConnection incoming : cache.incoming(checkNow.pos)) {
                PathingCell fromCell = cache.cell(incoming.start);
                PathCost edgeCost = connectionCost(incoming.start, incoming.end, incoming, false);

                if ((!edgeCost.allowed() || !fromCell.reachable) && (!lookingForImpossiblePath || !edgeCost.considerable())) {
                    continue;
                }

                PathCost newCostToGoal = checkNow.costToGoal.plus(edgeCost);
                PathCost heuristic = heuristicForCell(fromCell.pos, newCostToGoal);

                if (fromCell.generation == generation) {
                    boolean changed = false;

                    if (heuristic.betterThan(fromCell.heuristicValue)) {
                        fromCell.heuristicValue = heuristic;
                        changed = true;
                    }
                    if (newCostToGoal.betterThan(fromCell.costToGoal)) {
                        fromCell.costToGoal = newCostToGoal;
                        changed = true;
                    }

                    if (changed && !fromCell.inOpen) {
                        fromCell.inOpen = true;
                        addToOpen(fromCell);
                    }
                } else {
                    fromCell.generation = generation;
                    fromCell.costToGoal = newCostToGoal;
                    fromCell.heuristicValue = heuristic;
                    fromCell.inOpen = true;
                    addToOpen(fromCell);
                }

                if (fromCell.pos.equals(start)) {
                    closestCellToDestinationFromStart = fromCell;
                }
            }
        }

        private void addToOpen(PathingCell cell) {
            int i = 0;
            while (i < open.size()) {
                if (cell.heuristicValue.compareTo(open.get(i).heuristicValue) <= 0) {
                    open.add(i, cell);
                    return;
                }
                i++;
            }
            open.add(cell);
        }

        // ---------------------------------------------------------------------
        // Following
        // ---------------------------------------------------------------------

        /**
         * Mirror of StandardPather.FollowPath:
         * choose the best immediate outgoing move from the current local position
         * using legality, generation, then total path cost.
         */
        public BlockPos chooseNextStep(BlockPos currentPos, boolean actuallyFollowing) {
            BlockPos current = cache.accessible(currentPos) ? currentPos.toImmutable() : findNearestAccessible(world, currentPos, 4, maxRange);
            if (current == null) return null;

            PathingCell currentCell = cache.cell(current);
            if (!currentCell.reachable || !currentCell.possibleToGetBackFrom) {
                // Equivalent spirit to OutOfElement: best effort fallback.
                BlockPos rescue = findNearestReachableAndReturnable(current, 4);
                if (rescue != null) current = rescue;
            }

            MovementConnection bestConn = null;
            PathCost bestPathCost = new PathCost(Double.POSITIVE_INFINITY, Legality.UNALLOWED);
            Legality bestLegality = Legality.UNALLOWED;
            int bestGen = Integer.MIN_VALUE;

            for (MovementConnection conn : cache.outgoing(current)) {
                PathingCell next = cache.cell(conn.end);
                PathCost moveCost = connectionCost(conn.start, conn.end, conn, true);

                if (!next.possibleToGetBackFrom) {
                    moveCost = moveCost.plus(new PathCost(0.0, Legality.UNALLOWED));
                }

                PathCost combined = next.costToGoal.plus(moveCost);

                if (conn.end.equals(destination)) {
                    combined = new PathCost(0.0, combined.legality);
                } else if (connectionAlreadyFollowedSeveralTimes(conn)) {
                    combined = combined.plus(new PathCost(0.0, Legality.UNWANTED));
                    moveCost = moveCost.plus(new PathCost(0.0, Legality.UNWANTED));
                }

                if (moveCost.legality.ordinal() < bestLegality.ordinal()) {
                    bestConn = conn;
                    bestLegality = moveCost.legality;
                    bestGen = next.generation;
                    bestPathCost = combined;
                } else if (moveCost.legality == bestLegality) {
                    if (next.generation > bestGen) {
                        bestConn = conn;
                        bestGen = next.generation;
                        bestPathCost = combined;
                    } else if (next.generation == bestGen && combined.betterThan(bestPathCost)) {
                        bestConn = conn;
                        bestPathCost = combined;
                    }
                }
            }

            if (bestConn != null && bestLegality.ordinal() <= Legality.UNWANTED.ordinal()) {
                if (actuallyFollowing) {
                    rememberConnection(bestConn);
                }
                return bestConn.end;
            }

            return bestEffortTowardDestination(current);
        }

        /**
         * Debug/helper: reconstruct a local best-effort path by repeatedly calling chooseNextStep.
         * This is not how Rain World fundamentally follows paths, but useful if your entity code still
         * wants a list of waypoints.
         */
        public List<BlockPos> reconstructCurrentBestPath(BlockPos from, int maxSteps) {
            List<BlockPos> path = new ArrayList<>();
            BlockPos cur = from;
            Set<BlockPos> seen = new HashSet<>();
            path.add(cur);

            for (int i = 0; i < maxSteps; i++) {
                if (cur.equals(destination)) break;
                if (!seen.add(cur)) break;

                BlockPos next = chooseNextStep(cur, false);
                if (next == null || next.equals(cur)) break;

                path.add(next);
                cur = next;
            }

            return path;
        }

        public boolean tileClosestToGoal(BlockPos a, BlockPos b) {
            PathingCell ca = cache.cell(a);
            PathingCell cb = cache.cell(b);

            boolean aGood = ca.reachable && ca.possibleToGetBackFrom;
            boolean bGood = cb.reachable && cb.possibleToGetBackFrom;

            if (aGood && !bGood) return true;
            if (bGood && !aGood) return false;

            if (ca.costToGoal.legality.ordinal() < cb.costToGoal.legality.ordinal()) return true;
            if (cb.costToGoal.legality.ordinal() < ca.costToGoal.legality.ordinal()) return false;

            if (ca.generation > cb.generation) return true;
            if (cb.generation > ca.generation) return false;

            return ca.costToGoal.resistance < cb.costToGoal.resistance;
        }

        // ---------------------------------------------------------------------
        // Cost / heuristic
        // ---------------------------------------------------------------------

        private PathCost heuristicForCell(BlockPos cell, PathCost costToGoal) {
            if (lookingForImpossiblePath && !cache.cell(cell).reachable) {
                return costToGoal;
            }

            double distToCreature = euclidean(cell, start);
            double h = costToGoal.resistance * HEURISTIC_COST_FAC + distToCreature * HEURISTIC_DEST_FAC;
            return new PathCost(h, costToGoal.legality);
        }

        private PathCost coordinateLegality(BlockPos pos) {
            if (!cache.accessible(pos)) {
                return new PathCost(Double.POSITIVE_INFINITY, Legality.UNALLOWED);
            }

            int prox = cache.terrainProximity(pos);
            if (prox == 1) {
                return new PathCost(0.0, Legality.ALLOWED);
            }
            if (prox == 2) {
                return new PathCost(SURFACE_PREFERENCE_PENALTY, Legality.ALLOWED);
            }
            return new PathCost(0.0, Legality.UNALLOWED);
        }

        private PathCost connectionCost(BlockPos startPos, BlockPos endPos, MovementConnection conn, boolean followingPath) {
            if (!cache.accessible(startPos) || !cache.accessible(endPos)) {
                return new PathCost(Double.POSITIVE_INFINITY, Legality.UNALLOWED);
            }

            double base;
            switch (conn.type) {
                case FACE -> base = FACE_MOVE_COST;
                case EDGE_DIAGONAL -> base = EDGE_MOVE_COST;
                case CORNER_DIAGONAL -> base = CORNER_MOVE_COST;
                case OFF_SURFACE_TRANSFER -> base = FACE_MOVE_COST + LOOSE_SURFACE_PENALTY;
                default -> base = FACE_MOVE_COST;
            }

            PathCost coordCost = coordinateLegality(endPos);
            PathCost startCoordCost = coordinateLegality(startPos);

            if (!coordCost.considerable() || !startCoordCost.considerable()) {
                return new PathCost(Double.POSITIVE_INFINITY, Legality.UNALLOWED);
            }

            PathCost result = new PathCost(base, Legality.ALLOWED)
                .plus(coordCost)
                .plus(new PathCost(0.0, startCoordCost.legality));

            // Approximate point-of-no-return logic:
            if (cache.cell(endPos).reachable && !cache.cell(endPos).possibleToGetBackFrom) {
                result = result.plus(new PathCost(0.0, Legality.UNALLOWED));
            }

            // Moves that reduce surface attachment are allowed but unwanted.
            int startProx = cache.terrainProximity(startPos);
            int endProx = cache.terrainProximity(endPos);
            if (startProx == 1 && endProx == 2) {
                result = result.plus(new PathCost(UNWANTED_MOVE_PENALTY, Legality.UNWANTED));
            }

            // Penalize upward moves that lack wall support at the destination.
            if (endPos.getY() > startPos.getY() && !hasStrongWallSupport(endPos)) {
                result = result.plus(new PathCost(2.0, Legality.UNWANTED));
            }

            return result;
        }

        private boolean hasStrongWallSupport(BlockPos pos) {
            int solidSides = 0;

            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP || dir == Direction.DOWN) continue;
                BlockPos adj = pos.offset(dir);
                if (world.getBlockState(adj).isSolidBlock(world, adj)) {
                    solidSides++;
                }
            }

            return solidSides >= 1;
        }

        // ---------------------------------------------------------------------
        // Recent connection memory
        // ---------------------------------------------------------------------

        private void rememberConnection(MovementConnection connection) {
            if (!pastConnections.isEmpty() && pastConnections.peekFirst().equals(connection)) {
                return;
            }
            pastConnections.addFirst(connection);
            while (pastConnections.size() > SAVED_RECENT_CONNECTIONS) {
                pastConnections.removeLast();
            }
        }

        private boolean connectionAlreadyFollowedSeveralTimes(MovementConnection connection) {
            int count = 0;
            for (MovementConnection c : pastConnections) {
                if (c.equals(connection)) {
                    count++;
                    if (count >= RECENT_CONNECTION_LIMIT) {
                        return true;
                    }
                }
            }
            return false;
        }

        // ---------------------------------------------------------------------
        // Fallback helpers
        // ---------------------------------------------------------------------

        private BlockPos bestEffortTowardDestination(BlockPos current) {
            List<MovementConnection> outgoing = cache.outgoing(current);
            BlockPos best = null;
            double bestScore = Double.POSITIVE_INFINITY;

            for (MovementConnection c : outgoing) {
                if (!cache.accessible(c.end)) continue;
                double score = euclidean(c.end, destination);
                if (cache.terrainProximity(c.end) == 2) score += 0.35;
                if (score < bestScore) {
                    bestScore = score;
                    best = c.end;
                }
            }

            return best;
        }

        private BlockPos findNearestReachableAndReturnable(BlockPos center, int radius) {
            if (center == null) return null;
            PathingCell cell = cache.cell(center);
            if (cell.reachable && cell.possibleToGetBackFrom) return center;

            for (int r = 1; r <= radius; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;
                            BlockPos q = center.add(dx, dy, dz);
                            if (!cache.inBounds(q)) continue;
                            PathingCell qc = cache.cell(q);
                            if (qc.reachable && qc.possibleToGetBackFrom) {
                                return q;
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    // =========================================================================
    // Static convenience API
    // =========================================================================

    public static Search beginSearch(World world, BlockPos start, BlockPos destination, int maxRange) {
        return new Search(world, start, destination, maxRange);
    }

    public static List<BlockPos> findPath(World world, BlockPos start, BlockPos destination, int maxRange, int maxTicks) {
        Search search = beginSearch(world, start, destination, maxRange);
        int ticks = Math.max(1, maxTicks);

        for (int i = 0; i < ticks && !search.isFinished(); i++) {
            search.update(DEFAULT_ACCESSIBILITY_STEPS, DEFAULT_PATH_STEPS);
        }

        BlockPos adjustedStart = search.getStart();
        return search.reconstructCurrentBestPath(adjustedStart, Math.max(16, maxRange * 3));
    }

    /**
     * Follow helper that mirrors Rain World's style better than "closest waypoint in a static path":
     * choose the next block directly from the reverse pathfield.
     */
    public static BlockPos followPathfield(Search search, Vec3d currentPos) {
        if (search == null || !search.isAccessibilityFinished()) return null;

        BlockPos current = BlockPos.ofFloored(currentPos);
        return search.chooseNextStep(current, true);
    }

    /**
     * A debug/helper version if you still want a waypoint to aim for.
     * It simply walks a few steps through the local pathfield and returns the lookahead node.
     */
    public static BlockPos followPathfieldLookAhead(Search search, Vec3d currentPos, int lookAhead) {
        if (search == null || !search.isAccessibilityFinished()) return null;

        BlockPos cur = BlockPos.ofFloored(currentPos);
        int steps = Math.max(1, lookAhead);
        for (int i = 0; i < steps; i++) {
            BlockPos next = search.chooseNextStep(cur, false);
            if (next == null) return cur;
            if (next.equals(cur)) return cur;
            cur = next;
        }
        return cur;
    }

    public static boolean tileClosestToGoal(Search search, BlockPos a, BlockPos b) {
        if (search == null) return false;
        return search.tileClosestToGoal(a, b);
    }

    /**
     * Validity is now based on search state, not just "is each waypoint still air".
     */
    public static boolean isSearchStillUseful(Search search, Vec3d creaturePos, Vec3d goalPos) {
        if (search == null) return false;

        BlockPos creature = BlockPos.ofFloored(creaturePos);
        BlockPos goal = BlockPos.ofFloored(goalPos);

        if (creature.getManhattanDistance(search.getStart()) > search.maxRange + 4) return false;
        return goal.getManhattanDistance(search.getDestination()) <= 3;
    }

    // =========================================================================
    // World helpers
    // =========================================================================

    public static boolean isOccupiable(World world, BlockPos pos) {
        return !world.getBlockState(pos).blocksMovement();
    }

    public static int getTerrainProximity(World world, BlockPos pos) {
        if (!isOccupiable(world, pos)) return 0;

        for (Direction dir : Direction.values()) {
            if (world.getBlockState(pos.offset(dir)).blocksMovement()) {
                return 1;
            }
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (world.getBlockState(pos.add(dx, dy, dz)).blocksMovement()) {
                        return 2;
                    }
                }
            }
        }

        return 3;
    }

    public static boolean isAccessible(World world, BlockPos pos) {
        int prox = getTerrainProximity(world, pos);
        return prox >= 1 && prox <= 2;
    }

    public static BlockPos findNearestAccessible(World world, BlockPos center, int radius, int maxRangeFromCenter) {
        if (isAccessible(world, center)) return center.toImmutable();

        for (int r = 1; r <= radius; r++) {
            BlockPos best = null;
            double bestScore = Double.POSITIVE_INFINITY;

            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;

                        BlockPos q = center.add(dx, dy, dz);
                        if (!isAccessible(world, q)) continue;
                        if (Math.abs(q.getX() - center.getX()) > maxRangeFromCenter
                            || Math.abs(q.getY() - center.getY()) > maxRangeFromCenter
                            || Math.abs(q.getZ() - center.getZ()) > maxRangeFromCenter) {
                            continue;
                        }

                        double score = euclidean(center, q);
                        if (getTerrainProximity(world, q) == 1) score -= 0.2;
                        if (score < bestScore) {
                            bestScore = score;
                            best = q.toImmutable();
                        }
                    }
                }
            }

            if (best != null) return best;
        }

        return null;
    }

    public static BlockPos findNearestAccessible(World world, BlockPos center, int radius) {
        return findNearestAccessible(world, center, radius, DEFAULT_MAX_RANGE);
    }

    // =========================================================================
    // Math helpers
    // =========================================================================

    private static double euclidean(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static BlockPos midpoint(BlockPos a, BlockPos b) {
        return new BlockPos(
            (a.getX() + b.getX()) >> 1,
            (a.getY() + b.getY()) >> 1,
            (a.getZ() + b.getZ()) >> 1
        );
    }
}