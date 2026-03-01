package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/**
 * 3D A* pathfinder for centipedes, ported from Rain World's
 * PathFinder → StandardPather → CentipedePather chain.
 *
 * Key Rain World concept: a tile is "accessible" if its terrainProximity < 2,
 * meaning it is within 1 tile of a solid surface. This allows centipedes to
 * path along walls, ceilings, and floors — any air block adjacent to solid.
 *
 * This version operates on Minecraft BlockPos (3D grid), using 6-face
 * connectivity with diagonal expansion for smoother movement.
 */
public class CentipedePathfinder {

    // =========================================================================
    // Configuration
    // =========================================================================

    /** Maximum nodes to expand before giving up (performance budget) */
    private static final int MAX_NODES = 3000;

    /** Maximum straight-line range for path search (in blocks) */
    private static final int MAX_RANGE = 48;

    /** Heuristic weight multiplier (> 1.0 = greedier, faster but less optimal) */
    private static final double HEURISTIC_WEIGHT = 1.4;

    /**
     * Cost multiplier for tiles that are accessible but not directly touching
     * a solid surface (terrainProximity == 1 in Rain World terms).
     * Centipedes prefer crawling right next to surfaces.
     */
    private static final double SURFACE_PREFERENCE = 0.3;

    /** Cost of moving to a direct face neighbor */
    private static final double MOVE_COST_FACE = 1.0;

    /** Cost of moving to an edge-diagonal neighbor */
    private static final double MOVE_COST_EDGE = 1.414;

    /** Cost of moving to a corner-diagonal neighbor */
    private static final double MOVE_COST_CORNER = 1.732;

    // =========================================================================
    // 26-neighbor offsets (6 face + 12 edge + 8 corner)
    // =========================================================================

    private static final int[][] FACE_OFFSETS = {
        {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}
    };

    private static final int[][] EDGE_OFFSETS = {
        {1,1,0}, {1,-1,0}, {-1,1,0}, {-1,-1,0},
        {1,0,1}, {1,0,-1}, {-1,0,1}, {-1,0,-1},
        {0,1,1}, {0,1,-1}, {0,-1,1}, {0,-1,-1}
    };

    private static final int[][] CORNER_OFFSETS = {
        {1,1,1}, {1,1,-1}, {1,-1,1}, {1,-1,-1},
        {-1,1,1}, {-1,1,-1}, {-1,-1,1}, {-1,-1,-1}
    };

    // =========================================================================
    // Node class for A*
    // =========================================================================

    private static class PathNode implements Comparable<PathNode> {
        final BlockPos pos;
        PathNode parent;
        double gCost; // cost from start
        double hCost; // heuristic cost to goal
        int terrainProximity; // 0 = solid (not accessible), 1 = touching solid, 2 = 1 away from solid

        PathNode(BlockPos pos) {
            this.pos = pos;
            this.gCost = Double.MAX_VALUE;
            this.hCost = 0;
            this.terrainProximity = 2;
        }

        double fCost() {
            return gCost + hCost * HEURISTIC_WEIGHT;
        }

        @Override
        public int compareTo(PathNode other) {
            return Double.compare(this.fCost(), other.fCost());
        }
    }

    // =========================================================================
    // Accessibility checks (mirrors C# AccessibleTile / terrainProximity)
    // =========================================================================

    /**
     * Check if a block position is accessible to the centipede.
     * Mirrors C# AImap.TileAccessibleToCreature for centipedes:
     * - The block itself must NOT be solid (centipede can occupy it)
     * - The block must be adjacent to at least one solid block (terrainProximity < 2)
     *
     * This is what enables wall/ceiling/floor crawling — the centipede can
     * path through any air block that touches a surface.
     */
    public static boolean isAccessible(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        // Must not be solid — centipede can't be inside solid blocks
        if (state.blocksMovement()) return false;

        // Check 6 face neighbors for solid contact
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (world.getBlockState(neighbor).blocksMovement()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compute terrain proximity for a position.
     * 0 = solid (not accessible)
     * 1 = directly adjacent to solid (preferred for crawling)
     * 2 = one block away from solid (still accessible but less preferred)
     * 3+ = open air (not accessible to centipede)
     *
     * Mirrors C# AImap.getTerrainProximity().
     */
    public static int getTerrainProximity(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.blocksMovement()) return 0;

        // Check direct neighbors
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (world.getBlockState(neighbor).blocksMovement()) {
                return 1;
            }
        }

        // Check 2-block radius for any solid
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos check = pos.add(dx, dy, dz);
                    if (world.getBlockState(check).blocksMovement()) {
                        return 2;
                    }
                }
            }
        }

        return 3; // Open air
    }

    /**
     * Check if a tile is climbable (centipede can traverse it).
     * Mirrors C# ClimbableTile: wallbehind, beam, or terrainProximity < 2.
     * For Minecraft: accessible if terrainProximity <= 2.
     */
    public static boolean isClimbable(World world, BlockPos pos) {
        return getTerrainProximity(world, pos) >= 1 && getTerrainProximity(world, pos) <= 2;
    }

    // =========================================================================
    // Heuristic (mirrors C# StandardPather.HeuristicForCell)
    // =========================================================================

    /**
     * Heuristic cost estimate from a position to the goal.
     * Uses octile distance (3D generalization of Chebyshev/diagonal distance).
     *
     * C# StandardPather uses: costToGoal.resistance * heuristicCostFac + distance * heuristicDestFac
     */
    private static double heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dy = Math.abs(from.getY() - to.getY());
        int dz = Math.abs(from.getZ() - to.getZ());

        // Sort to get min, mid, max
        int min, mid, max;
        if (dx <= dy) {
            if (dy <= dz) { min = dx; mid = dy; max = dz; }
            else if (dx <= dz) { min = dx; mid = dz; max = dy; }
            else { min = dz; mid = dx; max = dy; }
        } else {
            if (dx <= dz) { min = dy; mid = dx; max = dz; }
            else if (dy <= dz) { min = dy; mid = dz; max = dx; }
            else { min = dz; mid = dy; max = dx; }
        }

        // 3D octile distance
        return (MOVE_COST_CORNER - MOVE_COST_EDGE) * min
             + (MOVE_COST_EDGE - MOVE_COST_FACE) * mid
             + MOVE_COST_FACE * max;
    }

    // =========================================================================
    // Core A* pathfinding
    // =========================================================================

    /**
     * Find a path from start to goal using A* on the 3D block grid.
     *
     * Mirrors the flow of:
     * - C# PathFinder.CheckNeighbours() (A* expansion)
     * - C# StandardPather.FollowPath() (path following)
     * - C# CentipedePather accessibility rules
     *
     * @param world    The Minecraft world
     * @param start    Starting block position (should be accessible)
     * @param goal     Goal block position
     * @param maxRange Maximum search radius in blocks
     * @return List of BlockPos waypoints from start to goal, or empty if no path found.
     *         If the exact goal is unreachable, returns path to the closest accessible
     *         position found (mirrors C# "looking for impossible path" fallback).
     */
    public static List<BlockPos> findPath(World world, BlockPos start, BlockPos goal, int maxRange) {
        // Clamp range
        if (maxRange <= 0) maxRange = MAX_RANGE;

        // If start is not accessible, try to find nearest accessible position
        if (!isAccessible(world, start)) {
            BlockPos adjusted = findNearestAccessible(world, start, 5);
            if (adjusted == null) return Collections.emptyList();
            start = adjusted;
        }

        // If goal is solid, find nearest accessible position to it
        BlockPos adjustedGoal = goal;
        if (!isAccessible(world, goal)) {
            BlockPos nearest = findNearestAccessible(world, goal, 5);
            if (nearest != null) {
                adjustedGoal = nearest;
            }
            // If goal is totally unreachable, we'll still try and return closest path
        }

        // Quick check: if start == goal
        if (start.equals(adjustedGoal)) {
            return Collections.singletonList(start);
        }

        // Distance check
        if (start.getManhattanDistance(adjustedGoal) > maxRange * 2) {
            return Collections.emptyList();
        }

        // A* search
        Map<BlockPos, PathNode> allNodes = new HashMap<>();
        PriorityQueue<PathNode> openSet = new PriorityQueue<>();

        PathNode startNode = new PathNode(start);
        startNode.gCost = 0;
        startNode.hCost = heuristic(start, adjustedGoal);
        startNode.terrainProximity = getTerrainProximity(world, start);
        allNodes.put(start, startNode);
        openSet.add(startNode);

        Set<BlockPos> closedSet = new HashSet<>();

        // Track closest node to goal (for fallback partial path)
        PathNode closestToGoal = startNode;
        double closestDist = heuristic(start, adjustedGoal);

        int nodesExpanded = 0;

        while (!openSet.isEmpty() && nodesExpanded < MAX_NODES) {
            PathNode current = openSet.poll();

            // Skip if already processed
            if (closedSet.contains(current.pos)) continue;
            closedSet.add(current.pos);
            nodesExpanded++;

            // Goal reached!
            if (current.pos.equals(adjustedGoal)) {
                return reconstructPath(current);
            }

            // Check if this is closer to goal than previous best
            double distToGoal = heuristic(current.pos, adjustedGoal);
            if (distToGoal < closestDist) {
                closestDist = distToGoal;
                closestToGoal = current;
            }

            // Range check
            if (current.pos.getManhattanDistance(start) > maxRange) continue;

            // Expand neighbors — 6 face neighbors (always checked)
            for (int[] offset : FACE_OFFSETS) {
                expandNeighbor(world, current, offset, MOVE_COST_FACE,
                        adjustedGoal, allNodes, openSet, closedSet);
            }

            // Expand edge-diagonal neighbors
            for (int[] offset : EDGE_OFFSETS) {
                // Only allow diagonal if at least one axis-aligned face toward the
                // diagonal is passable (prevents cutting through solid corners)
                BlockPos diag = current.pos.add(offset[0], offset[1], offset[2]);
                if (canMoveDiagonalEdge(world, current.pos, offset)) {
                    expandNeighbor(world, current, offset, MOVE_COST_EDGE,
                            adjustedGoal, allNodes, openSet, closedSet);
                }
            }

            // Expand corner-diagonal neighbors
            for (int[] offset : CORNER_OFFSETS) {
                if (canMoveDiagonalCorner(world, current.pos, offset)) {
                    expandNeighbor(world, current, offset, MOVE_COST_CORNER,
                            adjustedGoal, allNodes, openSet, closedSet);
                }
            }
        }

        // No exact path found — return partial path to closest node
        if (closestToGoal != startNode) {
            return reconstructPath(closestToGoal);
        }

        return Collections.emptyList();
    }

    /**
     * Expand a neighbor node in the A* search.
     */
    private static void expandNeighbor(World world, PathNode current, int[] offset,
                                        double moveCost, BlockPos goal,
                                        Map<BlockPos, PathNode> allNodes,
                                        PriorityQueue<PathNode> openSet,
                                        Set<BlockPos> closedSet) {
        BlockPos neighborPos = current.pos.add(offset[0], offset[1], offset[2]);

        if (closedSet.contains(neighborPos)) return;
        if (!isAccessible(world, neighborPos)) return;

        // Compute terrain proximity cost modifier
        // C# centipedes prefer terrainProximity == 1 (touching solid surface)
        int proximity = getTerrainProximity(world, neighborPos);
        double proximityCost = (proximity == 1) ? 0.0 : SURFACE_PREFERENCE;

        double tentativeG = current.gCost + moveCost + proximityCost;

        PathNode neighborNode = allNodes.get(neighborPos);
        if (neighborNode == null) {
            neighborNode = new PathNode(neighborPos);
            neighborNode.terrainProximity = proximity;
            allNodes.put(neighborPos, neighborNode);
        }

        if (tentativeG < neighborNode.gCost) {
            neighborNode.parent = current;
            neighborNode.gCost = tentativeG;
            neighborNode.hCost = heuristic(neighborPos, goal);
            openSet.add(neighborNode); // duplicate entries are OK — closedSet filters them
        }
    }

    /**
     * Check if edge-diagonal movement is valid (no cutting through solid corners).
     * For a diagonal on 2 axes, at least one of the 2 face-adjacent blocks must be passable.
     */
    private static boolean canMoveDiagonalEdge(World world, BlockPos from, int[] offset) {
        // Identify the two non-zero axes
        int ax = offset[0], ay = offset[1], az = offset[2];

        // Check the two "slide" positions — both intermediate face neighbors must not both be solid
        // For a move (1,1,0): check (1,0,0) and (0,1,0)
        boolean face1Blocked = world.getBlockState(from.add(ax, 0, 0)).blocksMovement()
                            && world.getBlockState(from.add(0, ay, 0)).blocksMovement()
                            && world.getBlockState(from.add(0, 0, az)).blocksMovement();

        if (ax != 0 && ay != 0) {
            return !world.getBlockState(from.add(ax, 0, 0)).blocksMovement()
                || !world.getBlockState(from.add(0, ay, 0)).blocksMovement();
        }
        if (ax != 0 && az != 0) {
            return !world.getBlockState(from.add(ax, 0, 0)).blocksMovement()
                || !world.getBlockState(from.add(0, 0, az)).blocksMovement();
        }
        if (ay != 0 && az != 0) {
            return !world.getBlockState(from.add(0, ay, 0)).blocksMovement()
                || !world.getBlockState(from.add(0, 0, az)).blocksMovement();
        }
        return false;
    }

    /**
     * Check if corner-diagonal movement is valid.
     * At least 2 of the 3 face-adjacent blocks must be passable.
     */
    private static boolean canMoveDiagonalCorner(World world, BlockPos from, int[] offset) {
        int passable = 0;
        if (!world.getBlockState(from.add(offset[0], 0, 0)).blocksMovement()) passable++;
        if (!world.getBlockState(from.add(0, offset[1], 0)).blocksMovement()) passable++;
        if (!world.getBlockState(from.add(0, 0, offset[2])).blocksMovement()) passable++;
        return passable >= 2;
    }

    // =========================================================================
    // Path reconstruction & smoothing
    // =========================================================================

    /**
     * Reconstruct path from goal node back to start by following parent pointers.
     */
    private static List<BlockPos> reconstructPath(PathNode goalNode) {
        List<BlockPos> path = new ArrayList<>();
        PathNode current = goalNode;
        while (current != null) {
            path.add(current.pos);
            current = current.parent;
        }
        Collections.reverse(path);

        // Smooth the raw A* path to remove unnecessary waypoints
        return smoothPath(path);
    }

    /**
     * Simple path smoothing: remove intermediate waypoints when a straight line
     * between two non-adjacent waypoints is clear.
     * This produces much smoother centipede movement.
     */
    private static List<BlockPos> smoothPath(List<BlockPos> rawPath) {
        if (rawPath.size() <= 2) return rawPath;

        List<BlockPos> smoothed = new ArrayList<>();
        smoothed.add(rawPath.get(0));

        int current = 0;
        while (current < rawPath.size() - 1) {
            // Try to skip as far ahead as possible while maintaining line-of-sight
            int farthest = current + 1;
            for (int test = rawPath.size() - 1; test > current + 1; test--) {
                // Simple check: all intermediate points should be roughly in line
                // (we don't do full raycasting, just check that the path doesn't
                // jump too far between consecutive skipped nodes)
                if (rawPath.get(current).getManhattanDistance(rawPath.get(test))
                        <= (test - current) * 2) {
                    farthest = test;
                    break;
                }
            }
            current = farthest;
            smoothed.add(rawPath.get(current));
        }

        return smoothed;
    }

    // =========================================================================
    // Utility: find nearest accessible position
    // =========================================================================

    /**
     * Find the nearest accessible block position within the given radius.
     * Searches in expanding shells from the center.
     */
    public static BlockPos findNearestAccessible(World world, BlockPos center, int radius) {
        if (isAccessible(world, center)) return center;

        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        // Only check the shell at distance r
                        if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;

                        BlockPos check = center.add(dx, dy, dz);
                        if (isAccessible(world, check)) return check;
                    }
                }
            }
        }
        return null;
    }

    // =========================================================================
    // TileClosestToGoal (from C# CentipedePather)
    // =========================================================================

    /**
     * Compare two positions to determine which is "closer" to the goal,
     * taking into account path accessibility and cost.
     *
     * Mirrors C# CentipedePather.TileClosestToGoal():
     * - Prefer reachable + get-back-able positions
     * - Then prefer lower cost-to-goal
     * - Then prefer lower distance
     *
     * @param world The world
     * @param a     First position to compare
     * @param b     Second position to compare
     * @param goal  The target goal position
     * @return true if A is closer to goal than B
     */
    public static boolean tileClosestToGoal(World world, BlockPos a, BlockPos b, BlockPos goal) {
        boolean aAccessible = isAccessible(world, a);
        boolean bAccessible = isAccessible(world, b);

        // Prefer accessible positions (mirrors CoordinateReachableAndGetbackable check)
        if (aAccessible && !bAccessible) return true;
        if (bAccessible && !aAccessible) return false;

        // Prefer positions with lower terrain proximity (closer to surface)
        int aProx = getTerrainProximity(world, a);
        int bProx = getTerrainProximity(world, b);
        if (aProx < bProx) return true;
        if (bProx < aProx) return false;

        // Prefer closer to goal (straight-line distance)
        double aDist = heuristic(a, goal);
        double bDist = heuristic(b, goal);
        return aDist < bDist;
    }

    // =========================================================================
    // Incremental pathfinder (spread across ticks like C# PathFinder.Update)
    // =========================================================================

    /**
     * An incremental version of the pathfinder that processes a limited number
     * of nodes per tick, mirroring C# PathFinder.Update() with stepsPerFrame.
     *
     * Usage:
     *   IncrementalSearch search = new IncrementalSearch(world, start, goal, maxRange);
     *   // Each tick:
     *   search.step(stepsPerTick);
     *   if (search.isFinished()) {
     *       List<BlockPos> path = search.getPath();
     *   }
     */
    public static class IncrementalSearch {
        private final World world;
        private final BlockPos start;
        private final BlockPos goal;
        private final int maxRange;

        private final Map<BlockPos, PathNode> allNodes = new HashMap<>();
        private final PriorityQueue<PathNode> openSet = new PriorityQueue<>();
        private final Set<BlockPos> closedSet = new HashSet<>();

        private PathNode closestToGoal;
        private double closestDist;
        private int nodesExpanded = 0;
        private boolean finished = false;
        private List<BlockPos> result = null;

        public IncrementalSearch(World world, BlockPos start, BlockPos goal, int maxRange) {
            this.world = world;
            this.maxRange = maxRange > 0 ? maxRange : MAX_RANGE;

            // Adjust start if not accessible
            BlockPos adjustedStart = start;
            if (!isAccessible(world, start)) {
                BlockPos nearest = findNearestAccessible(world, start, 5);
                if (nearest != null) adjustedStart = nearest;
            }
            this.start = adjustedStart;

            // Adjust goal if not accessible
            BlockPos adjustedGoal = goal;
            if (!isAccessible(world, goal)) {
                BlockPos nearest = findNearestAccessible(world, goal, 5);
                if (nearest != null) adjustedGoal = nearest;
            }
            this.goal = adjustedGoal;

            // Initialize start node
            PathNode startNode = new PathNode(this.start);
            startNode.gCost = 0;
            startNode.hCost = heuristic(this.start, this.goal);
            startNode.terrainProximity = getTerrainProximity(world, this.start);
            allNodes.put(this.start, startNode);
            openSet.add(startNode);

            closestToGoal = startNode;
            closestDist = startNode.hCost;

            // Quick finish if start == goal
            if (this.start.equals(this.goal)) {
                result = Collections.singletonList(this.start);
                finished = true;
            }
        }

        /**
         * Process up to `steps` nodes. Mirrors C# PathFinder.Update loop.
         */
        public void step(int steps) {
            if (finished) return;

            for (int i = 0; i < steps && !openSet.isEmpty() && nodesExpanded < MAX_NODES; i++) {
                PathNode current = openSet.poll();

                if (closedSet.contains(current.pos)) continue;
                closedSet.add(current.pos);
                nodesExpanded++;

                // Goal reached
                if (current.pos.equals(goal)) {
                    result = reconstructPath(current);
                    finished = true;
                    return;
                }

                // Track closest to goal
                double distToGoal = heuristic(current.pos, goal);
                if (distToGoal < closestDist) {
                    closestDist = distToGoal;
                    closestToGoal = current;
                }

                // Range check
                if (current.pos.getManhattanDistance(start) > maxRange) continue;

                // Expand face neighbors
                for (int[] offset : FACE_OFFSETS) {
                    expandNeighbor(world, current, offset, MOVE_COST_FACE,
                            goal, allNodes, openSet, closedSet);
                }

                // Expand edge-diagonals
                for (int[] offset : EDGE_OFFSETS) {
                    if (canMoveDiagonalEdge(world, current.pos, offset)) {
                        expandNeighbor(world, current, offset, MOVE_COST_EDGE,
                                goal, allNodes, openSet, closedSet);
                    }
                }

                // Expand corner-diagonals
                for (int[] offset : CORNER_OFFSETS) {
                    if (canMoveDiagonalCorner(world, current.pos, offset)) {
                        expandNeighbor(world, current, offset, MOVE_COST_CORNER,
                                goal, allNodes, openSet, closedSet);
                    }
                }
            }

            // Check if search is exhausted
            if (openSet.isEmpty() || nodesExpanded >= MAX_NODES) {
                // Return partial path to closest point
                if (closestToGoal != null && closestToGoal.parent != null) {
                    result = reconstructPath(closestToGoal);
                } else {
                    result = Collections.emptyList();
                }
                finished = true;
            }
        }

        public boolean isFinished() { return finished; }

        public List<BlockPos> getPath() { return result != null ? result : Collections.emptyList(); }

        public BlockPos getGoal() { return goal; }
    }

    // =========================================================================
    // Path following helper (mirrors C# StandardPather.FollowPath)
    // =========================================================================

    /**
     * Given a path and the current position, find the next waypoint to move toward.
     *
     * Mirrors C# StandardPather.FollowPath(): find current position in the path,
     * return the next connection toward the goal.
     *
     * @param path       The computed path waypoints
     * @param currentPos Current entity position (will snap to nearest waypoint)
     * @param lookAhead  How many waypoints ahead to target (for smoother movement)
     * @return The block position to move toward, or null if path is exhausted
     */
    public static BlockPos followPath(List<BlockPos> path, Vec3d currentPos, int lookAhead) {
        if (path == null || path.isEmpty()) return null;

        // Find the closest waypoint to current position
        int closestIndex = 0;
        double closestDist = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            double dist = currentPos.squaredDistanceTo(
                    path.get(i).getX() + 0.5,
                    path.get(i).getY() + 0.5,
                    path.get(i).getZ() + 0.5);
            if (dist < closestDist) {
                closestDist = dist;
                closestIndex = i;
            }
        }

        // Target a waypoint ahead of the closest one for smoother movement
        int targetIndex = Math.min(closestIndex + lookAhead, path.size() - 1);
        return path.get(targetIndex);
    }

    /**
     * Check if a path is still valid (no blocked waypoints).
     * Only checks a sample of waypoints for performance.
     */
    public static boolean isPathValid(World world, List<BlockPos> path) {
        if (path == null || path.isEmpty()) return false;

        // Check every 3rd waypoint for accessibility
        for (int i = 0; i < path.size(); i += 3) {
            if (!isAccessible(world, path.get(i))) return false;
        }
        // Always check last waypoint
        if (!isAccessible(world, path.get(path.size() - 1))) return false;

        return true;
    }
}
