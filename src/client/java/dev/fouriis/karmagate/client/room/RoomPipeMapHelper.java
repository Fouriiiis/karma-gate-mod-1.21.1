package dev.fouriis.karmagate.client.room;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import rainworld.mechanics.common.block.pipes.PipeBlock;
import rainworld.mechanics.common.block.pipes.PipeEntrance;
import rainworld.mechanics.common.block.pipes.ShelterPipeEntrance;
import rainworld.mechanics.common.block.pipes.TelePipeBlock;
import rainworld.mechanics.common.block.pipes.TelePipeBlockEntity;

public class RoomPipeMapHelper {
	private static final int MAX_TRACE_STEPS = 4096;

	public static @Nullable BlockPos findOtherEntrance(World world, BlockPos entrancePos) {
		BlockState entranceState = world.getBlockState(entrancePos);
		if (!(entranceState.getBlock() instanceof PipeEntrance)) {
			return null;
		}

		PipeEntrance.Orientation connection = entranceState.get(PipeEntrance.CONNECTION);
		Direction initialDirection = getNextDirection(world, entrancePos, connection.getDirection());
		return traceToExit(world, entrancePos, initialDirection);
	}

	private static @Nullable BlockPos traceToExit(World world, BlockPos startPos, @Nullable Direction direction) {
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
			if (steps >= MAX_TRACE_STEPS) {
				return null;
			}
		}

		return null;
	}

	private static @Nullable BlockPos getLinkedPos(World world, BlockPos telePos) {
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

	private static @Nullable Direction getNextDirection(World world, BlockPos pos, Direction currentDirection) {
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
}
