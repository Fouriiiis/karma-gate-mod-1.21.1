package dev.fouriis.karmagate.client.weather;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class DeathRainWeatherRenderer {

	private static final int RADIUS_BLOCKS = 12;
	private static final float CORNER_X_HALF_SIZE = 0.085f;

	private static final float LIGHT_ANGLE_X_DEGREES = 20.0f;
	private static final float LIGHT_ANGLE_Z_DEGREES = 20.0f;
	private static final float SHAFT_DEPTH_BLOCKS = 1.0f;
	private static final float PROJECTION_SAMPLE_EPSILON = 0.001f;

	private static final float LIGHT_SLOPE_X =
			(float) Math.tan(Math.toRadians(LIGHT_ANGLE_X_DEGREES));
	private static final float LIGHT_SLOPE_Z =
			(float) Math.tan(Math.toRadians(LIGHT_ANGLE_Z_DEGREES));

	private static final float LIGHT_DIR_LENGTH =
			(float) Math.sqrt(LIGHT_SLOPE_X * LIGHT_SLOPE_X + 1.0f + LIGHT_SLOPE_Z * LIGHT_SLOPE_Z);

	private static final float LIGHT_DIR_X = LIGHT_SLOPE_X / LIGHT_DIR_LENGTH;
	private static final float LIGHT_DIR_Y = -1.0f / LIGHT_DIR_LENGTH;
	private static final float LIGHT_DIR_Z = LIGHT_SLOPE_Z / LIGHT_DIR_LENGTH;

	private DeathRainWeatherRenderer() {
	}

	public static void render(World world, Camera camera, float tickDelta, MatrixStack matrices) {
		if (world == null || camera == null || matrices == null) {
			return;
		}

		if (!isDeathRainActive()) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer lines = immediate.getBuffer(RenderLayer.LINES);

		Vec3d camPos = camera.getPos();
		int baseX = MathHelper.floor(camPos.x);
		int baseY = MathHelper.floor(camPos.y);
		int baseZ = MathHelper.floor(camPos.z);
		int radiusSq = RADIUS_BLOCKS * RADIUS_BLOCKS;

		Vector3f camRight = new Vector3f(1.0f, 0.0f, 0.0f).rotate(camera.getRotation());
		Vector3f camUp = new Vector3f(0.0f, 1.0f, 0.0f).rotate(camera.getRotation());

		Vector3f diagA = new Vector3f(camRight).add(camUp);
		if (diagA.lengthSquared() > 1.0e-6f) {
			diagA.normalize().mul(CORNER_X_HALF_SIZE);
		}

		Vector3f diagB = new Vector3f(camRight).sub(camUp);
		if (diagB.lengthSquared() > 1.0e-6f) {
			diagB.normalize().mul(CORNER_X_HALF_SIZE);
		}

		matrices.push();
		matrices.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f mat = matrices.peek().getPositionMatrix();

		for (int x = baseX - RADIUS_BLOCKS; x <= baseX + RADIUS_BLOCKS; x++) {
			for (int y = baseY - RADIUS_BLOCKS; y <= baseY + RADIUS_BLOCKS; y++) {
				for (int z = baseZ - RADIUS_BLOCKS; z <= baseZ + RADIUS_BLOCKS; z++) {
					double cx = x + 0.5;
					double cy = y + 0.5;
					double cz = z + 0.5;

					if (camPos.squaredDistanceTo(cx, cy, cz) > radiusSq) {
						continue;
					}

					if (!isShadowCastingAirCell(world, x, y, z)) {
						continue;
					}

					emitAirCellShadowGeometry(world, lines, mat, x, y, z, diagA, diagB);
				}
			}
		}

		matrices.pop();
		immediate.draw();
	}

	private static boolean isDeathRainActive() {
		if (GlobalRainClientState.hasSync()) {
			return true;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		return client.getServer() != null;
	}

	private static boolean isOpaqueFullCube(World world, int x, int y, int z) {
		if (world.isOutOfHeightLimit(y)) {
			return false;
		}

		BlockPos pos = new BlockPos(x, y, z);
		return world.getBlockState(pos).isOpaqueFullCube(world, pos);
	}

	private static boolean isSkyExposedAir(World world, int x, int y, int z) {
		if (world.isOutOfHeightLimit(y)) {
			return false;
		}

		BlockPos pos = new BlockPos(x, y, z);
		return !world.getBlockState(pos).isOpaqueFullCube(world, pos) && world.isSkyVisible(pos);
	}

	private static boolean isShadowCastingAirCell(World world, int x, int y, int z) {
		if (!isSkyExposedAir(world, x, y, z)) {
			return false;
		}

		return isOpaqueFullCube(world, x - 1, y, z)
				|| isOpaqueFullCube(world, x + 1, y, z)
				|| isOpaqueFullCube(world, x, y, z - 1)
				|| isOpaqueFullCube(world, x, y, z + 1);
	}

	private static void emitAirCellShadowGeometry(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			int cellX,
			int cellY,
			int cellZ,
			Vector3f diagA,
			Vector3f diagB
	) {
		boolean negX = isOpaqueFullCube(world, cellX - 1, cellY, cellZ);
		boolean posX = isOpaqueFullCube(world, cellX + 1, cellY, cellZ);
		boolean negZ = isOpaqueFullCube(world, cellX, cellY, cellZ - 1);
		boolean posZ = isOpaqueFullCube(world, cellX, cellY, cellZ + 1);

		float[] bounds = computeExitApertureBounds(cellX, cellZ, negX, posX, negZ, posZ);
		float minX = bounds[0];
		float maxX = bounds[1];
		float minZ = bounds[2];
		float maxZ = bounds[3];

		if (maxX <= minX || maxZ <= minZ) {
			return;
		}

		float topY = cellY + 1.0f;
		float exitY = cellY;

		if (negX) {
			emitApertureSideShadow(
					world, vc, mat, diagA, diagB,
					cellX, cellY, cellZ,
					cellX, topY, cellZ,
					cellX, topY, cellZ + 1.0f,
					minX, exitY, minZ,
					minX, exitY, maxZ,
					true, false, negZ, false,
					true, false, false, posZ
			);
		}

		if (posX) {
			emitApertureSideShadow(
					world, vc, mat, diagA, diagB,
					cellX, cellY, cellZ,
					cellX + 1.0f, topY, cellZ,
					cellX + 1.0f, topY, cellZ + 1.0f,
					maxX, exitY, minZ,
					maxX, exitY, maxZ,
					false, true, negZ, false,
					false, true, false, posZ
			);
		}

		if (negZ) {
			emitApertureSideShadow(
					world, vc, mat, diagA, diagB,
					cellX, cellY, cellZ,
					cellX, topY, cellZ,
					cellX + 1.0f, topY, cellZ,
					minX, exitY, minZ,
					maxX, exitY, minZ,
					negX, false, true, false,
					false, posX, true, false
			);
		}

		if (posZ) {
			emitApertureSideShadow(
					world, vc, mat, diagA, diagB,
					cellX, cellY, cellZ,
					cellX, topY, cellZ + 1.0f,
					cellX + 1.0f, topY, cellZ + 1.0f,
					minX, exitY, maxZ,
					maxX, exitY, maxZ,
					negX, false, false, true,
					false, posX, false, true
			);
		}
	}

	private static float[] computeExitApertureBounds(
			int cellX,
			int cellZ,
			boolean negX,
			boolean posX,
			boolean negZ,
			boolean posZ
	) {
		float clippedSlopeX = MathHelper.clamp(LIGHT_SLOPE_X, -1.0f, 1.0f);
		float clippedSlopeZ = MathHelper.clamp(LIGHT_SLOPE_Z, -1.0f, 1.0f);

		float minX = cellX;
		float maxX = cellX + 1.0f;
		float minZ = cellZ;
		float maxZ = cellZ + 1.0f;

		if (clippedSlopeX > 0.0f && negX) {
			minX += clippedSlopeX * SHAFT_DEPTH_BLOCKS;
		} else if (clippedSlopeX < 0.0f && posX) {
			maxX += clippedSlopeX * SHAFT_DEPTH_BLOCKS;
		}

		if (clippedSlopeZ > 0.0f && negZ) {
			minZ += clippedSlopeZ * SHAFT_DEPTH_BLOCKS;
		} else if (clippedSlopeZ < 0.0f && posZ) {
			maxZ += clippedSlopeZ * SHAFT_DEPTH_BLOCKS;
		}

		return new float[] { minX, maxX, minZ, maxZ };
	}

	private static void emitApertureSideShadow(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			Vector3f diagA,
			Vector3f diagB,
			int cellX,
			int cellY,
			int cellZ,
			float topAx,
			float topAy,
			float topAz,
			float topBx,
			float topBy,
			float topBz,
			float exitAx,
			float exitAy,
			float exitAz,
			float exitBx,
			float exitBy,
			float exitBz,
			boolean aNegX,
			boolean aPosX,
			boolean aNegZ,
			boolean aPosZ,
			boolean bNegX,
			boolean bPosX,
			boolean bNegZ,
			boolean bPosZ
	) {
		Vector3f projA = projectAperturePoint(world, exitAx, exitAy, exitAz, aNegX, aPosX, aNegZ, aPosZ);
		Vector3f projB = projectAperturePoint(world, exitBx, exitBy, exitBz, bNegX, bPosX, bNegZ, bPosZ);

		if (projA == null || projB == null) {
			return;
		}

		emitCross(vc, mat, topAx, topAy, topAz, diagA, diagB);
		emitCross(vc, mat, topBx, topBy, topBz, diagA, diagB);
		emitCross(vc, mat, exitAx, exitAy, exitAz, diagA, diagB);
		emitCross(vc, mat, exitBx, exitBy, exitBz, diagA, diagB);
		emitCross(vc, mat, projA.x, projA.y, projA.z, diagA, diagB);
		emitCross(vc, mat, projB.x, projB.y, projB.z, diagA, diagB);

		emitLine(vc, mat, topAx, topAy, topAz, topBx, topBy, topBz);
		emitLine(vc, mat, topAx, topAy, topAz, exitAx, exitAy, exitAz);
		emitLine(vc, mat, topBx, topBy, topBz, exitBx, exitBy, exitBz);
		emitLine(vc, mat, exitAx, exitAy, exitAz, exitBx, exitBy, exitBz);
		emitLine(vc, mat, exitAx, exitAy, exitAz, projA.x, projA.y, projA.z);
		emitLine(vc, mat, exitBx, exitBy, exitBz, projB.x, projB.y, projB.z);
		emitLine(vc, mat, projA.x, projA.y, projA.z, projB.x, projB.y, projB.z);
	}

	private static Vector3f projectAperturePoint(
			World world,
			float startX,
			float startY,
			float startZ,
			boolean onNegX,
			boolean onPosX,
			boolean onNegZ,
			boolean onPosZ
	) {
		float sampleOffsetX = onNegX ? -1.0f : (onPosX ? 1.0f : Math.signum(LIGHT_DIR_X));
		float sampleOffsetZ = onNegZ ? -1.0f : (onPosZ ? 1.0f : Math.signum(LIGHT_DIR_Z));

		return projectPointToReceiver(world, startX, startY, startZ, sampleOffsetX, sampleOffsetZ);
	}

	private static Vector3f projectPointToReceiver(
			World world,
			float startX,
			float startY,
			float startZ,
			float sampleOffsetX,
			float sampleOffsetZ
	) {
		int sampleX = MathHelper.floor(startX + sampleOffsetX * PROJECTION_SAMPLE_EPSILON);
		int sampleZ = MathHelper.floor(startZ + sampleOffsetZ * PROJECTION_SAMPLE_EPSILON);
		int startScanY = MathHelper.floor(startY) - 1;

		for (int y = startScanY; !world.isOutOfHeightLimit(y); y--) {
			BlockPos pos = new BlockPos(sampleX, y, sampleZ);
			if (world.getBlockState(pos).isOpaqueFullCube(world, pos)) {
				float verticalDrop = startY - (y + 1.0f);
				if (verticalDrop <= 0.0f) {
					return null;
				}

				float travelDistance = verticalDrop / -LIGHT_DIR_Y;

				return new Vector3f(
						startX + LIGHT_DIR_X * travelDistance,
						startY + LIGHT_DIR_Y * travelDistance,
						startZ + LIGHT_DIR_Z * travelDistance
				);
			}
		}

		return null;
	}

	private static void emitCross(
			VertexConsumer vc,
			Matrix4f mat,
			float px,
			float py,
			float pz,
			Vector3f diagA,
			Vector3f diagB
	) {
		emitLine(
				vc, mat,
				px - diagA.x, py - diagA.y, pz - diagA.z,
				px + diagA.x, py + diagA.y, pz + diagA.z
		);

		emitLine(
				vc, mat,
				px - diagB.x, py - diagB.y, pz - diagB.z,
				px + diagB.x, py + diagB.y, pz + diagB.z
		);
	}

	private static void emitLine(
			VertexConsumer vc,
			Matrix4f mat,
			float x1,
			float y1,
			float z1,
			float x2,
			float y2,
			float z2
	) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		float dz = z2 - z1;

		float lenSq = dx * dx + dy * dy + dz * dz;
		float nx = 0.0f;
		float ny = 1.0f;
		float nz = 0.0f;

		if (lenSq > 1.0e-6f) {
			float invLen = MathHelper.inverseSqrt(lenSq);
			nx = dx * invLen;
			ny = dy * invLen;
			nz = dz * invLen;
		}

		vc.vertex(mat, x1, y1, z1)
				.color(255, 0, 0, 255)
				.normal(nx, ny, nz);

		vc.vertex(mat, x2, y2, z2)
				.color(255, 0, 0, 255)
				.normal(nx, ny, nz);
	}
}