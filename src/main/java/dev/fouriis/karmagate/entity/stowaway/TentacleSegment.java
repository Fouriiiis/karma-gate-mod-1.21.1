package dev.fouriis.karmagate.entity.stowaway;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A single verlet-integrated segment of a tentacle.
 * Used for both feeler (passive) and grabbing (active) tentacles.
 * 
 * Matches C# behavior: pos (current), lastPos (previous), vel (velocity).
 * The C# code uses a [segment, 3] array where index 0=pos, 1=lastPos, 2=vel.
 */
public class TentacleSegment {
    public Vec3d pos;
    public Vec3d lastPos;
    public Vec3d vel;  // Explicit velocity like C# code
    public boolean onSurface;  // Track if resting on a surface
    public boolean onFloor;    // Track if specifically resting on top of a block (horizontal surface)
    
    // Surface friction - segments on surfaces should barely move
    private static final double SURFACE_FRICTION = 0.5;   // General surface friction
    private static final double FLOOR_FRICTION = 0.15;    // Very strong friction on floors to prevent sliding
    private static final double SETTLE_THRESHOLD = 0.003; // Velocity below this = settled
    
    public TentacleSegment(Vec3d initialPos) {
        this.pos = initialPos;
        this.lastPos = initialPos;
        this.vel = Vec3d.ZERO;
        this.onSurface = false;
        this.onFloor = false;
    }
    
    public TentacleSegment(double x, double y, double z) {
        this(new Vec3d(x, y, z));
    }
    
    /**
     * Get velocity from verlet (difference between current and last position).
     */
    public Vec3d getVelocity() {
        return pos.subtract(lastPos);
    }
    
    public void setVelocity(Vec3d vel) {
        this.vel = vel;
        this.lastPos = pos.subtract(vel);
    }
    
    /**
     * Standard verlet integration step: store last, apply velocity.
     */
    public void applyVelocity(Vec3d vel) {
        this.lastPos = this.pos;
        this.vel = vel;
        this.pos = this.pos.add(vel);
    }
    
    /**
     * Apply verlet integration with explicit velocity storage (C# style).
     */
    public void update() {
        this.lastPos = this.pos;
        this.pos = this.pos.add(this.vel);
    }
    
    /**
     * Check if a position is inside a solid block.
     */
    public static boolean isSolid(World world, Vec3d position) {
        BlockPos blockPos = BlockPos.ofFloored(position);
        BlockState state = world.getBlockState(blockPos);
        return state.isSolidBlock(world, blockPos);
    }
    
    /**
     * Perform terrain collision detection and resolution.
     * This approximates the C# SharedPhysics collision system:
     * - SharedPhysics.HorizontalCollision
     * - SharedPhysics.VerticalCollision
     * - SharedPhysics.SlopesVertically
     * 
     * Pushes the segment out of solid blocks and zeroes velocity along collision axis.
     */
    public void terrainCollision(World world) {
        if (world == null) return;
        
        BlockPos currentBlock = BlockPos.ofFloored(pos);
        BlockState state = world.getBlockState(currentBlock);
        
        if (!state.isSolidBlock(world, currentBlock)) {
            return; // Not in solid block, no collision needed
        }
        
        // We're inside a solid block - push out to nearest open space
        // Check last position to determine push direction
        BlockPos lastBlock = BlockPos.ofFloored(lastPos);
        
        // Try pushing in each cardinal direction to find open space
        double pushDistance = 0.05;  // Small push amount per tick
        
        // Horizontal collision (X and Z axes)
        // Check X direction
        if (pos.x != lastPos.x) {
            double dx = pos.x - lastPos.x;
            if (dx > 0) {
                // Moving +X, check if block to the west is open
                BlockPos westBlock = currentBlock.west();
                if (!world.getBlockState(westBlock).isSolidBlock(world, westBlock)) {
                    pos = new Vec3d(currentBlock.getX() - pushDistance, pos.y, pos.z);
                    vel = new Vec3d(0, vel.y, vel.z);
                }
            } else {
                // Moving -X, check if block to the east is open
                BlockPos eastBlock = currentBlock.east();
                if (!world.getBlockState(eastBlock).isSolidBlock(world, eastBlock)) {
                    pos = new Vec3d(currentBlock.getX() + 1 + pushDistance, pos.y, pos.z);
                    vel = new Vec3d(0, vel.y, vel.z);
                }
            }
        }
        
        // Check Z direction
        if (pos.z != lastPos.z) {
            double dz = pos.z - lastPos.z;
            if (dz > 0) {
                BlockPos northBlock = currentBlock.north();
                if (!world.getBlockState(northBlock).isSolidBlock(world, northBlock)) {
                    pos = new Vec3d(pos.x, pos.y, currentBlock.getZ() - pushDistance);
                    vel = new Vec3d(vel.x, vel.y, 0);
                }
            } else {
                BlockPos southBlock = currentBlock.south();
                if (!world.getBlockState(southBlock).isSolidBlock(world, southBlock)) {
                    pos = new Vec3d(pos.x, pos.y, currentBlock.getZ() + 1 + pushDistance);
                    vel = new Vec3d(vel.x, vel.y, 0);
                }
            }
        }
        
        // Vertical collision (Y axis) - most important for hanging tentacles
        if (pos.y != lastPos.y) {
            double dy = pos.y - lastPos.y;
            if (dy < 0) {
                // Falling down, check if block above is open
                BlockPos upBlock = currentBlock.up();
                if (!world.getBlockState(upBlock).isSolidBlock(world, upBlock)) {
                    // Push up to top of current block
                    pos = new Vec3d(pos.x, currentBlock.getY() + 1 + pushDistance, pos.z);
                    vel = new Vec3d(vel.x, 0, vel.z);
                }
            } else {
                // Moving up, check if block below is open
                BlockPos downBlock = currentBlock.down();
                if (!world.getBlockState(downBlock).isSolidBlock(world, downBlock)) {
                    pos = new Vec3d(pos.x, currentBlock.getY() - pushDistance, pos.z);
                    vel = new Vec3d(vel.x, 0, vel.z);
                }
            }
        }
    }
    
    /**
     * Simple collision that just pushes segment out of solid blocks.
     * Use this after constraint solving when raycast is not appropriate.
     */
    public void pushOutOfBlock(World world) {
        if (world == null) return;
        
        BlockPos currentBlock = BlockPos.ofFloored(pos);
        BlockState state = world.getBlockState(currentBlock);
        
        if (!state.isSolidBlock(world, currentBlock)) {
            return;  // Not inside a block
        }
        
        // We're inside a solid block - find the best exit direction
        double localX = pos.x - currentBlock.getX();
        double localY = pos.y - currentBlock.getY();
        double localZ = pos.z - currentBlock.getZ();
        
        double[] distances = {
            localY,      // Distance to bottom (Y=0)
            1 - localY,  // Distance to top (Y=1)
            localZ,      // Distance to north (Z=0)
            1 - localZ,  // Distance to south (Z=1)
            localX,      // Distance to west (X=0)
            1 - localX   // Distance to east (X=1)
        };
        BlockPos[] neighbors = {
            currentBlock.down(), currentBlock.up(),
            currentBlock.north(), currentBlock.south(),
            currentBlock.west(), currentBlock.east()
        };
        
        int bestFace = -1;
        double bestDist = Double.MAX_VALUE;
        
        for (int i = 0; i < 6; i++) {
            if (!world.getBlockState(neighbors[i]).isSolidBlock(world, neighbors[i])) {
                if (distances[i] < bestDist) {
                    bestDist = distances[i];
                    bestFace = i;
                }
            }
        }
        
        if (bestFace >= 0) {
            double margin = 0.01;
            switch (bestFace) {
                case 0: pos = new Vec3d(pos.x, currentBlock.getY() - margin, pos.z); break;
                case 1: pos = new Vec3d(pos.x, currentBlock.getY() + 1 + margin, pos.z); break;
                case 2: pos = new Vec3d(pos.x, pos.y, currentBlock.getZ() - margin); break;
                case 3: pos = new Vec3d(pos.x, pos.y, currentBlock.getZ() + 1 + margin); break;
                case 4: pos = new Vec3d(currentBlock.getX() - margin, pos.y, pos.z); break;
                case 5: pos = new Vec3d(currentBlock.getX() + 1 + margin, pos.y, pos.z); break;
            }
            vel = vel.multiply(SURFACE_FRICTION);
        }
    }
    
    /**
     * Terrain collision with raycast to prevent tunneling through blocks.
     * Checks the path from lastPos to pos and stops at first solid block.
     */
    public void terrainCollisionDrape(World world) {
        if (world == null) return;
        
        // Reset surface state
        onSurface = false;
        onFloor = false;
        
        // First, raycast from lastPos to pos to prevent tunneling
        Vec3d movement = pos.subtract(lastPos);
        double moveLength = movement.length();
        
        if (moveLength > 0.01) {  // Only raycast if we're actually moving
            Vec3d dir = movement.normalize();
            double stepSize = 0.1;  // Check every 0.1 blocks along path
            int steps = (int) Math.ceil(moveLength / stepSize);
            
            for (int i = 1; i <= steps; i++) {
                double t = Math.min(1.0, (i * stepSize) / moveLength);
                Vec3d checkPos = lastPos.add(movement.multiply(t));
                BlockPos checkBlock = BlockPos.ofFloored(checkPos);
                
                if (world.getBlockState(checkBlock).isSolidBlock(world, checkBlock)) {
                    // Hit a solid block along the path - stop here
                    // Move back to just before the collision
                    double safeT = Math.max(0, ((i - 1) * stepSize) / moveLength);
                    pos = lastPos.add(movement.multiply(safeT));
                    onSurface = true;
                    
                    // Kill velocity component in movement direction and apply friction
                    vel = vel.multiply(SURFACE_FRICTION);
                    break;
                }
            }
        }
        
        BlockPos currentBlock = BlockPos.ofFloored(pos);
        BlockState state = world.getBlockState(currentBlock);
        
        if (!state.isSolidBlock(world, currentBlock)) {
            // Not inside a block, but check if resting on top of a block below
            BlockPos blockBelow = currentBlock.down();
            if (world.getBlockState(blockBelow).isSolidBlock(world, blockBelow)) {
                double floorY = currentBlock.getY();  // Top of block below
                if (pos.y < floorY + 0.05) {  // Close to the floor surface
                    onFloor = true;
                    onSurface = true;
                    // Apply floor friction to horizontal velocity
                    vel = new Vec3d(vel.x * FLOOR_FRICTION, vel.y, vel.z * FLOOR_FRICTION);
                    // Settle small velocities
                    if (Math.abs(vel.x) < SETTLE_THRESHOLD) vel = new Vec3d(0, vel.y, vel.z);
                    if (Math.abs(vel.z) < SETTLE_THRESHOLD) vel = new Vec3d(vel.x, vel.y, 0);
                }
            }
            return;
        }
        
        // We're inside a solid block - find the best exit direction
        onSurface = true;
        
        // Calculate position within the block (0-1 range for each axis)
        double localX = pos.x - currentBlock.getX();
        double localY = pos.y - currentBlock.getY();
        double localZ = pos.z - currentBlock.getZ();
        
        // Find which face is closest and push to that face
        double[] distances = {
            localY,      // Distance to bottom (Y=0)
            1 - localY,  // Distance to top (Y=1)
            localZ,      // Distance to north (Z=0)
            1 - localZ,  // Distance to south (Z=1)
            localX,      // Distance to west (X=0)
            1 - localX   // Distance to east (X=1)
        };
        BlockPos[] neighbors = {
            currentBlock.down(), currentBlock.up(),
            currentBlock.north(), currentBlock.south(),
            currentBlock.west(), currentBlock.east()
        };
        Vec3d[] normals = {
            new Vec3d(0, -1, 0), new Vec3d(0, 1, 0),
            new Vec3d(0, 0, -1), new Vec3d(0, 0, 1),
            new Vec3d(-1, 0, 0), new Vec3d(1, 0, 0)
        };
        
        // Find the closest open face
        int bestFace = -1;
        double bestDist = Double.MAX_VALUE;
        
        for (int i = 0; i < 6; i++) {
            if (!world.getBlockState(neighbors[i]).isSolidBlock(world, neighbors[i])) {
                if (distances[i] < bestDist) {
                    bestDist = distances[i];
                    bestFace = i;
                }
            }
        }
        
        if (bestFace >= 0) {
            Vec3d normal = normals[bestFace];
            
            // Push to the block boundary with small margin
            double margin = 0.01;
            switch (bestFace) {
                case 0: // Bottom
                    pos = new Vec3d(pos.x, currentBlock.getY() - margin, pos.z);
                    break;
                case 1: // Top - this is a floor!
                    pos = new Vec3d(pos.x, currentBlock.getY() + 1 + margin, pos.z);
                    onFloor = true;
                    break;
                case 2: // North
                    pos = new Vec3d(pos.x, pos.y, currentBlock.getZ() - margin);
                    break;
                case 3: // South
                    pos = new Vec3d(pos.x, pos.y, currentBlock.getZ() + 1 + margin);
                    break;
                case 4: // West
                    pos = new Vec3d(currentBlock.getX() - margin, pos.y, pos.z);
                    break;
                case 5: // East
                    pos = new Vec3d(currentBlock.getX() + 1 + margin, pos.y, pos.z);
                    break;
            }
            
            // Remove velocity component going into the block
            double velIntoSurface = vel.dotProduct(normal.multiply(-1));
            if (velIntoSurface > 0) {
                // Remove the component going into the surface
                vel = vel.add(normal.multiply(velIntoSurface));
            }
            
            // Apply friction based on surface type
            if (onFloor) {
                // Very strong horizontal friction on floors
                vel = new Vec3d(vel.x * FLOOR_FRICTION, vel.y * SURFACE_FRICTION, vel.z * FLOOR_FRICTION);
            } else {
                // General surface friction
                vel = vel.multiply(SURFACE_FRICTION);
            }
            
            // If velocity is very small, zero it out completely to stop jittering
            if (vel.lengthSquared() < SETTLE_THRESHOLD * SETTLE_THRESHOLD) {
                vel = Vec3d.ZERO;
            }
        }
    }
}
