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

import java.util.ArrayList;

public final class DeathRainWeatherRenderer {

	private static final int RADIUS_BLOCKS = 12;
	private static final float CORNER_X_HALF_SIZE = 0.085f;

	private static final float LIGHT_ANGLE_X_DEGREES = 20.0f;
	private static final float LIGHT_ANGLE_Z_DEGREES = 20.0f;
	private static final float SHAFT_DEPTH_BLOCKS = 1.0f;
	private static final float PROJECTION_SAMPLE_EPSILON = 0.001f;
	private static final float SHADOW_CLIP_EPSILON = 1.0e-4f;

	// Inset blocker cells very slightly so a shadow sweep that only grazes a voxel
	// corner does not create a fake split interval.
	private static final float BLOCKER_INTERIOR_EPSILON = 0.001f;
	private static final float INTERVAL_SNAP_EPSILON = 0.001f;

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

					if (isOneBlockHole(world, x, y, z)) {
						emitOneBlockHoleShadowOutline(world, lines, mat, x, y, z, diagA, diagB);
						continue;
					}

					if (isOpaqueFullCube(world, x, y, z)) {
						emitSolidBlockSilhouetteShadow(world, lines, mat, x, y, z, diagA, diagB);
					}
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

	private static boolean isOneBlockHole(World world, int x, int y, int z) {
		if (!isSkyExposedAir(world, x, y, z)) {
			return false;
		}

		return isOpaqueFullCube(world, x + 1, y, z)
				&& isOpaqueFullCube(world, x - 1, y, z)
				&& isOpaqueFullCube(world, x, y, z + 1)
				&& isOpaqueFullCube(world, x, y, z - 1);
	}

	private static boolean isExposedAir(World world, int x, int y, int z) {
		if (world.isOutOfHeightLimit(y)) {
			return false;
		}

		BlockPos pos = new BlockPos(x, y, z);
		return !world.getBlockState(pos).isOpaqueFullCube(world, pos);
	}

	private static boolean isFaceExposedForSolidSilhouette(World world, int x, int y, int z) {
		return isExposedAir(world, x, y, z)
				&& !isOneBlockHole(world, x, y, z);
	}

	private static void emitSolidBlockSilhouetteShadow(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			int blockX,
			int blockY,
			int blockZ,
			Vector3f diagA,
			Vector3f diagB
	) {
		if (!isSkyExposedAir(world, blockX, blockY + 1, blockZ)) {
			return;
		}

		boolean posX = isFaceExposedForSolidSilhouette(world, blockX + 1, blockY, blockZ);
		boolean negX = isFaceExposedForSolidSilhouette(world, blockX - 1, blockY, blockZ);
		boolean posY = isFaceExposedForSolidSilhouette(world, blockX, blockY + 1, blockZ);
		boolean negY = isFaceExposedForSolidSilhouette(world, blockX, blockY - 1, blockZ);
		boolean posZ = isFaceExposedForSolidSilhouette(world, blockX, blockY, blockZ + 1);
		boolean negZ = isFaceExposedForSolidSilhouette(world, blockX, blockY, blockZ - 1);

		boolean frontPosX = isFrontFacing(1.0f, 0.0f, 0.0f);
		boolean frontNegX = isFrontFacing(-1.0f, 0.0f, 0.0f);
		boolean frontPosY = isFrontFacing(0.0f, 1.0f, 0.0f);
		boolean frontNegY = isFrontFacing(0.0f, -1.0f, 0.0f);
		boolean frontPosZ = isFrontFacing(0.0f, 0.0f, 1.0f);
		boolean frontNegZ = isFrontFacing(0.0f, 0.0f, -1.0f);

		float sampleNX = computeCornerSampleX(negX, posX, -1);
		float samplePX = computeCornerSampleX(negX, posX, 1);
		float sampleNZ = computeCornerSampleZ(negZ, posZ, -1);
		float samplePZ = computeCornerSampleZ(negZ, posZ, 1);

		// Top edges
		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				posY, negX, frontPosY, frontNegX,
				sampleNX, sampleNZ,
				sampleNX, samplePZ,
				blockX,     blockY + 1.0f, blockZ,
				blockX,     blockY + 1.0f, blockZ + 1.0f);

		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				posY, posX, frontPosY, frontPosX,
				samplePX, sampleNZ,
				samplePX, samplePZ,
				blockX + 1.0f, blockY + 1.0f, blockZ,
				blockX + 1.0f, blockY + 1.0f, blockZ + 1.0f);

		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				posY, negZ, frontPosY, frontNegZ,
				sampleNX, sampleNZ,
				samplePX, sampleNZ,
				blockX,         blockY + 1.0f, blockZ,
				blockX + 1.0f, blockY + 1.0f, blockZ);

		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				posY, posZ, frontPosY, frontPosZ,
				sampleNX, samplePZ,
				samplePX, samplePZ,
				blockX,         blockY + 1.0f, blockZ + 1.0f,
				blockX + 1.0f, blockY + 1.0f, blockZ + 1.0f);

		// Bottom edges
		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				negY, negX, frontNegY, frontNegX,
				sampleNX, sampleNZ,
				sampleNX, samplePZ,
				blockX,     blockY, blockZ,
				blockX,     blockY, blockZ + 1.0f);

		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				negY, posX, frontNegY, frontPosX,
				samplePX, sampleNZ,
				samplePX, samplePZ,
				blockX + 1.0f, blockY, blockZ,
				blockX + 1.0f, blockY, blockZ + 1.0f);

		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				negY, negZ, frontNegY, frontNegZ,
				sampleNX, sampleNZ,
				samplePX, sampleNZ,
				blockX,         blockY, blockZ,
				blockX + 1.0f, blockY, blockZ);

		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				negY, posZ, frontNegY, frontPosZ,
				sampleNX, samplePZ,
				samplePX, samplePZ,
				blockX,         blockY, blockZ + 1.0f,
				blockX + 1.0f, blockY, blockZ + 1.0f);

		// Vertical edges
		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				negX, negZ, frontNegX, frontNegZ,
				sampleNX, sampleNZ,
				sampleNX, sampleNZ,
				blockX, blockY,         blockZ,
				blockX, blockY + 1.0f, blockZ);

		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				posX, negZ, frontPosX, frontNegZ,
				samplePX, sampleNZ,
				samplePX, sampleNZ,
				blockX + 1.0f, blockY,         blockZ,
				blockX + 1.0f, blockY + 1.0f, blockZ);

		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				negX, posZ, frontNegX, frontPosZ,
				sampleNX, samplePZ,
				sampleNX, samplePZ,
				blockX, blockY,         blockZ + 1.0f,
				blockX, blockY + 1.0f, blockZ + 1.0f);

		emitSilhouetteEdgeIfNeeded(vc, mat, diagA, diagB, world,
				posX, posZ, frontPosX, frontPosZ,
				samplePX, samplePZ,
				samplePX, samplePZ,
				blockX + 1.0f, blockY,         blockZ + 1.0f,
				blockX + 1.0f, blockY + 1.0f, blockZ + 1.0f);
	}

	private static float computeCornerSampleX(boolean negXExposed, boolean posXExposed, int cornerXSign) {
		if (cornerXSign < 0 && negXExposed) {
			return -1.0f;
		}
		if (cornerXSign > 0 && posXExposed) {
			return 1.0f;
		}
		return Math.signum(LIGHT_DIR_X);
	}

	private static float computeCornerSampleZ(boolean negZExposed, boolean posZExposed, int cornerZSign) {
		if (cornerZSign < 0 && negZExposed) {
			return -1.0f;
		}
		if (cornerZSign > 0 && posZExposed) {
			return 1.0f;
		}
		return Math.signum(LIGHT_DIR_Z);
	}

	private static boolean isFrontFacing(float nx, float ny, float nz) {
		return nx * LIGHT_DIR_X + ny * LIGHT_DIR_Y + nz * LIGHT_DIR_Z > 0.0f;
	}

	private static void emitSilhouetteEdgeIfNeeded(
			VertexConsumer vc,
			Matrix4f mat,
			Vector3f diagA,
			Vector3f diagB,
			World world,
			boolean faceAExposed,
			boolean faceBExposed,
			boolean faceAFront,
			boolean faceBFront,
			float sampleAx,
			float sampleAz,
			float sampleBx,
			float sampleBz,
			float ax,
			float ay,
			float az,
			float bx,
			float by,
			float bz
	) {
		if (!faceAExposed || !faceBExposed) {
			return;
		}

		if (faceAFront == faceBFront) {
			return;
		}

		emitProjectedShadowEdge(
				world,
				vc,
				mat,
				diagA,
				diagB,
				sampleAx,
				sampleAz,
				sampleBx,
				sampleBz,
				ax,
				ay,
				az,
				bx,
				by,
				bz
		);
	}

	private static void emitOneBlockHoleShadowOutline(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			int holeX,
			int holeY,
			int holeZ,
			Vector3f diagA,
			Vector3f diagB
	) {
		float topY = holeY + 1.0f;
		float exitY = holeY;

		Vector3f[] topCorners = new Vector3f[] {
				new Vector3f(holeX,     topY, holeZ),
				new Vector3f(holeX + 1, topY, holeZ),
				new Vector3f(holeX + 1, topY, holeZ + 1),
				new Vector3f(holeX,     topY, holeZ + 1)
		};

		Vector3f[] exitCorners = computeExitApertureCorners(holeX, exitY, holeZ);
		if (exitCorners == null) {
			return;
		}

		Vector3f[] projectedCorners = new Vector3f[4];
		for (int i = 0; i < 4; i++) {
			projectedCorners[i] = projectPointToReceiver(world, exitCorners[i].x, exitCorners[i].y, exitCorners[i].z);
			if (projectedCorners[i] == null) {
				return;
			}
		}

		for (int i = 0; i < 4; i++) {
			emitCross(vc, mat, topCorners[i].x, topCorners[i].y, topCorners[i].z, diagA, diagB);
			emitCross(vc, mat, exitCorners[i].x, exitCorners[i].y, exitCorners[i].z, diagA, diagB);
			emitCross(vc, mat, projectedCorners[i].x, projectedCorners[i].y, projectedCorners[i].z, diagA, diagB);
		}

		emitLoop(vc, mat, topCorners);
		emitLoop(vc, mat, exitCorners);

		for (int i = 0; i < 4; i++) {
			emitLine(
					vc, mat,
					topCorners[i].x, topCorners[i].y, topCorners[i].z,
					exitCorners[i].x, exitCorners[i].y, exitCorners[i].z
			);

			emitLine(
					vc, mat,
					exitCorners[i].x, exitCorners[i].y, exitCorners[i].z,
					projectedCorners[i].x, projectedCorners[i].y, projectedCorners[i].z
			);
		}

		emitLoop(vc, mat, projectedCorners);
	}

	private static Vector3f[] computeExitApertureCorners(int holeX, float exitY, int holeZ) {
		float offsetX = LIGHT_SLOPE_X * SHAFT_DEPTH_BLOCKS;
		float offsetZ = LIGHT_SLOPE_Z * SHAFT_DEPTH_BLOCKS;

		float minX = holeX + Math.max(0.0f, offsetX);
		float maxX = holeX + Math.min(1.0f, 1.0f + offsetX);

		float minZ = holeZ + Math.max(0.0f, offsetZ);
		float maxZ = holeZ + Math.min(1.0f, 1.0f + offsetZ);

		if (maxX <= minX || maxZ <= minZ) {
			return null;
		}

		return new Vector3f[] {
				new Vector3f(minX, exitY, minZ),
				new Vector3f(maxX, exitY, minZ),
				new Vector3f(maxX, exitY, maxZ),
				new Vector3f(minX, exitY, maxZ)
		};
	}

	private static void emitProjectedShadowEdge(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			Vector3f diagA,
			Vector3f diagB,
			float sampleAx,
			float sampleAz,
			float sampleBx,
			float sampleBz,
			float ax,
			float ay,
			float az,
			float bx,
			float by,
			float bz
	) {
		// Horizontal X-edge
		if (Math.abs(ay - by) < 1.0e-5f && Math.abs(az - bz) < 1.0e-5f && Math.abs(ax - bx) > 1.0e-5f) {
			emitClippedHorizontalXShadowEdge(world, vc, mat, ax, ay, az, bx);
			return;
		}

		// Horizontal Z-edge
		if (Math.abs(ay - by) < 1.0e-5f && Math.abs(ax - bx) < 1.0e-5f && Math.abs(az - bz) > 1.0e-5f) {
			emitClippedHorizontalZShadowEdge(world, vc, mat, ax, ay, az, bz);
			return;
		}

		// Fallback for vertical / unusual edges.
		emitProjectedShadowEdgeDirect(
				world,
				vc,
				mat,
				sampleAx,
				sampleAz,
				sampleBx,
				sampleBz,
				ax, ay, az,
				bx, by, bz
		);
	}

	private static void emitProjectedShadowEdgeDirect(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			float sampleAx,
			float sampleAz,
			float sampleBx,
			float sampleBz,
			float ax,
			float ay,
			float az,
			float bx,
			float by,
			float bz
	) {
		Vector3f projA = projectPointToReceiver(world, ax, ay, az, sampleAx, sampleAz);
		Vector3f projB = projectPointToReceiver(world, bx, by, bz, sampleBx, sampleBz);

		if (projA == null || projB == null) {
			return;
		}

		emitLine(vc, mat, ax, ay, az, bx, by, bz);
		emitLine(vc, mat, ax, ay, az, projA.x, projA.y, projA.z);
		emitLine(vc, mat, bx, by, bz, projB.x, projB.y, projB.z);
		emitLine(vc, mat, projA.x, projA.y, projA.z, projB.x, projB.y, projB.z);
	}

	private static void emitClippedHorizontalXShadowEdge(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			float ax,
			float ay,
			float az,
			float bx
	) {
		float x0 = ax;
		float x1 = bx;
		float z0 = az;

		if (x1 < x0) {
			float tmp = x0;
			x0 = x1;
			x1 = tmp;
		}

		float edgeLen = x1 - x0;
		if (edgeLen <= SHADOW_CLIP_EPSILON) {
			return;
		}

		float exitY = ay - 1.0f;
		int clipBlockY = MathHelper.floor(ay - PROJECTION_SAMPLE_EPSILON);

		ArrayList<ParamInterval> visible = new ArrayList<>();
		visible.add(new ParamInterval(0.0f, 1.0f));

		float minSweepX = Math.min(x0, x0 + LIGHT_SLOPE_X);
		float maxSweepX = Math.max(x1, x1 + LIGHT_SLOPE_X);
		float minSweepZ = Math.min(z0, z0 + LIGHT_SLOPE_Z);
		float maxSweepZ = Math.max(z0, z0 + LIGHT_SLOPE_Z);

		int minCellX = MathHelper.floor(minSweepX) - 1;
		int maxCellX = MathHelper.floor(maxSweepX) + 1;
		int minCellZ = MathHelper.floor(minSweepZ) - 1;
		int maxCellZ = MathHelper.floor(maxSweepZ) + 1;

		for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
			for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
				ParamInterval blocked = computeBlockedIntervalForHorizontalXEdge(
						world,
						x0,
						z0,
						edgeLen,
						clipBlockY,
						cellX,
						cellZ
				);

				if (blocked != null) {
					subtractIntervalList(visible, blocked);
					if (visible.isEmpty()) {
						return;
					}
				}
			}
		}

		for (ParamInterval interval : visible) {
			float t0 = snapUnitInterval(interval.minT);
			float t1 = snapUnitInterval(interval.maxT);

			if (t1 - t0 <= SHADOW_CLIP_EPSILON) {
				continue;
			}

			float srcAx = x0 + edgeLen * t0;
			float srcBx = x0 + edgeLen * t1;
			float srcY = ay;
			float srcZ = z0;

			float exitAx = srcAx + LIGHT_SLOPE_X;
			float exitBx = srcBx + LIGHT_SLOPE_X;
			float exitZ = srcZ + LIGHT_SLOPE_Z;

			Vector3f projA = projectPointToReceiver(
					world,
					exitAx,
					exitY,
					exitZ,
					Math.signum(LIGHT_DIR_X),
					Math.signum(LIGHT_DIR_Z)
			);
			Vector3f projB = projectPointToReceiver(
					world,
					exitBx,
					exitY,
					exitZ,
					Math.signum(LIGHT_DIR_X),
					Math.signum(LIGHT_DIR_Z)
			);

			if (projA == null || projB == null) {
				continue;
			}

			emitLine(vc, mat, srcAx, srcY, srcZ, srcBx, srcY, srcZ);
			emitLine(vc, mat, srcAx, srcY, srcZ, exitAx, exitY, exitZ);
			emitLine(vc, mat, srcBx, srcY, srcZ, exitBx, exitY, exitZ);
			emitLine(vc, mat, exitAx, exitY, exitZ, exitBx, exitY, exitZ);
			emitLine(vc, mat, exitAx, exitY, exitZ, projA.x, projA.y, projA.z);
			emitLine(vc, mat, exitBx, exitY, exitZ, projB.x, projB.y, projB.z);
			emitLine(vc, mat, projA.x, projA.y, projA.z, projB.x, projB.y, projB.z);
		}
	}

	private static void emitClippedHorizontalZShadowEdge(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			float ax,
			float ay,
			float az,
			float bz
	) {
		float z0 = az;
		float z1 = bz;
		float x0 = ax;

		if (z1 < z0) {
			float tmp = z0;
			z0 = z1;
			z1 = tmp;
		}

		float edgeLen = z1 - z0;
		if (edgeLen <= SHADOW_CLIP_EPSILON) {
			return;
		}

		float exitY = ay - 1.0f;
		int clipBlockY = MathHelper.floor(ay - PROJECTION_SAMPLE_EPSILON);

		ArrayList<ParamInterval> visible = new ArrayList<>();
		visible.add(new ParamInterval(0.0f, 1.0f));

		float minSweepX = Math.min(x0, x0 + LIGHT_SLOPE_X);
		float maxSweepX = Math.max(x0, x0 + LIGHT_SLOPE_X);
		float minSweepZ = Math.min(z0, z0 + LIGHT_SLOPE_Z);
		float maxSweepZ = Math.max(z1, z1 + LIGHT_SLOPE_Z);

		int minCellX = MathHelper.floor(minSweepX) - 1;
		int maxCellX = MathHelper.floor(maxSweepX) + 1;
		int minCellZ = MathHelper.floor(minSweepZ) - 1;
		int maxCellZ = MathHelper.floor(maxSweepZ) + 1;

		for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
			for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
				ParamInterval blocked = computeBlockedIntervalForHorizontalZEdge(
						world,
						x0,
						z0,
						edgeLen,
						clipBlockY,
						cellX,
						cellZ
				);

				if (blocked != null) {
					subtractIntervalList(visible, blocked);
					if (visible.isEmpty()) {
						return;
					}
				}
			}
		}

		for (ParamInterval interval : visible) {
			float t0 = snapUnitInterval(interval.minT);
			float t1 = snapUnitInterval(interval.maxT);

			if (t1 - t0 <= SHADOW_CLIP_EPSILON) {
				continue;
			}

			float srcAz = z0 + edgeLen * t0;
			float srcBz = z0 + edgeLen * t1;
			float srcX = x0;
			float srcY = ay;

			float exitX = srcX + LIGHT_SLOPE_X;
			float exitAz = srcAz + LIGHT_SLOPE_Z;
			float exitBz = srcBz + LIGHT_SLOPE_Z;

			Vector3f projA = projectPointToReceiver(
					world,
					exitX,
					exitY,
					exitAz,
					Math.signum(LIGHT_DIR_X),
					Math.signum(LIGHT_DIR_Z)
			);
			Vector3f projB = projectPointToReceiver(
					world,
					exitX,
					exitY,
					exitBz,
					Math.signum(LIGHT_DIR_X),
					Math.signum(LIGHT_DIR_Z)
			);

			if (projA == null || projB == null) {
				continue;
			}

			emitLine(vc, mat, srcX, srcY, srcAz, srcX, srcY, srcBz);
			emitLine(vc, mat, srcX, srcY, srcAz, exitX, exitY, exitAz);
			emitLine(vc, mat, srcX, srcY, srcBz, exitX, exitY, exitBz);
			emitLine(vc, mat, exitX, exitY, exitAz, exitX, exitY, exitBz);
			emitLine(vc, mat, exitX, exitY, exitAz, projA.x, projA.y, projA.z);
			emitLine(vc, mat, exitX, exitY, exitBz, projB.x, projB.y, projB.z);
			emitLine(vc, mat, projA.x, projA.y, projA.z, projB.x, projB.y, projB.z);
		}
	}

	private static ParamInterval computeBlockedIntervalForHorizontalXEdge(
			World world,
			float edgeStartX,
			float edgeZ,
			float edgeLen,
			int clipBlockY,
			int blockX,
			int blockZ
	) {
		if (!isOpaqueFullCube(world, blockX, clipBlockY, blockZ)) {
			return null;
		}

		float blockMinX = blockX + BLOCKER_INTERIOR_EPSILON;
		float blockMaxX = blockX + 1.0f - BLOCKER_INTERIOR_EPSILON;
		float blockMinZ = blockZ + BLOCKER_INTERIOR_EPSILON;
		float blockMaxZ = blockZ + 1.0f - BLOCKER_INTERIOR_EPSILON;

		float uMin = SHADOW_CLIP_EPSILON;
		float uMax = 1.0f - SHADOW_CLIP_EPSILON;

		if (Math.abs(LIGHT_SLOPE_Z) < 1.0e-6f) {
			if (!(edgeZ > blockMinZ && edgeZ < blockMaxZ)) {
				return null;
			}
		} else {
			float uz0 = (blockMinZ - edgeZ) / LIGHT_SLOPE_Z;
			float uz1 = (blockMaxZ - edgeZ) / LIGHT_SLOPE_Z;
			float zLo = Math.min(uz0, uz1);
			float zHi = Math.max(uz0, uz1);

			uMin = Math.max(uMin, zLo);
			uMax = Math.min(uMax, zHi);

			if (uMax <= uMin + SHADOW_CLIP_EPSILON) {
				return null;
			}
		}

		float shift0 = LIGHT_SLOPE_X * uMin;
		float shift1 = LIGHT_SLOPE_X * uMax;
		float minShift = Math.min(shift0, shift1);
		float maxShift = Math.max(shift0, shift1);

		float tMin = (blockMinX - edgeStartX - maxShift) / edgeLen;
		float tMax = (blockMaxX - edgeStartX - minShift) / edgeLen;

		tMin = Math.max(0.0f, tMin);
		tMax = Math.min(1.0f, tMax);

		tMin = snapUnitInterval(tMin);
		tMax = snapUnitInterval(tMax);

		if (tMax <= tMin + SHADOW_CLIP_EPSILON) {
			return null;
		}

		return new ParamInterval(tMin, tMax);
	}

	private static ParamInterval computeBlockedIntervalForHorizontalZEdge(
			World world,
			float edgeX,
			float edgeStartZ,
			float edgeLen,
			int clipBlockY,
			int blockX,
			int blockZ
	) {
		if (!isOpaqueFullCube(world, blockX, clipBlockY, blockZ)) {
			return null;
		}

		float blockMinX = blockX + BLOCKER_INTERIOR_EPSILON;
		float blockMaxX = blockX + 1.0f - BLOCKER_INTERIOR_EPSILON;
		float blockMinZ = blockZ + BLOCKER_INTERIOR_EPSILON;
		float blockMaxZ = blockZ + 1.0f - BLOCKER_INTERIOR_EPSILON;

		float uMin = SHADOW_CLIP_EPSILON;
		float uMax = 1.0f - SHADOW_CLIP_EPSILON;

		if (Math.abs(LIGHT_SLOPE_X) < 1.0e-6f) {
			if (!(edgeX > blockMinX && edgeX < blockMaxX)) {
				return null;
			}
		} else {
			float ux0 = (blockMinX - edgeX) / LIGHT_SLOPE_X;
			float ux1 = (blockMaxX - edgeX) / LIGHT_SLOPE_X;
			float xLo = Math.min(ux0, ux1);
			float xHi = Math.max(ux0, ux1);

			uMin = Math.max(uMin, xLo);
			uMax = Math.min(uMax, xHi);

			if (uMax <= uMin + SHADOW_CLIP_EPSILON) {
				return null;
			}
		}

		float shift0 = LIGHT_SLOPE_Z * uMin;
		float shift1 = LIGHT_SLOPE_Z * uMax;
		float minShift = Math.min(shift0, shift1);
		float maxShift = Math.max(shift0, shift1);

		float tMin = (blockMinZ - edgeStartZ - maxShift) / edgeLen;
		float tMax = (blockMaxZ - edgeStartZ - minShift) / edgeLen;

		tMin = Math.max(0.0f, tMin);
		tMax = Math.min(1.0f, tMax);

		tMin = snapUnitInterval(tMin);
		tMax = snapUnitInterval(tMax);

		if (tMax <= tMin + SHADOW_CLIP_EPSILON) {
			return null;
		}

		return new ParamInterval(tMin, tMax);
	}

	private static void subtractIntervalList(ArrayList<ParamInterval> visible, ParamInterval blocked) {
		ArrayList<ParamInterval> next = new ArrayList<>();

		for (ParamInterval interval : visible) {
			float intervalMin = snapUnitInterval(interval.minT);
			float intervalMax = snapUnitInterval(interval.maxT);
			float blockedMin = snapUnitInterval(blocked.minT);
			float blockedMax = snapUnitInterval(blocked.maxT);

			if (blockedMax <= intervalMin + SHADOW_CLIP_EPSILON
					|| blockedMin >= intervalMax - SHADOW_CLIP_EPSILON) {
				addIntervalIfValid(next, intervalMin, intervalMax);
				continue;
			}

			addIntervalIfValid(next, intervalMin, blockedMin);
			addIntervalIfValid(next, blockedMax, intervalMax);
		}

		visible.clear();
		visible.addAll(next);
	}

	private static void addIntervalIfValid(ArrayList<ParamInterval> out, float minT, float maxT) {
		float snappedMin = snapUnitInterval(minT);
		float snappedMax = snapUnitInterval(maxT);

		if (snappedMax <= snappedMin + SHADOW_CLIP_EPSILON) {
			return;
		}

		out.add(new ParamInterval(snappedMin, snappedMax));
	}

	private static float snapUnitInterval(float t) {
		float clamped = MathHelper.clamp(t, 0.0f, 1.0f);

		if (clamped <= INTERVAL_SNAP_EPSILON) {
			return 0.0f;
		}
		if (clamped >= 1.0f - INTERVAL_SNAP_EPSILON) {
			return 1.0f;
		}

		return clamped;
	}

	private static Vector3f projectPointToReceiver(World world, float startX, float startY, float startZ) {
		return projectPointToReceiver(
				world,
				startX,
				startY,
				startZ,
				Math.signum(LIGHT_DIR_X),
				Math.signum(LIGHT_DIR_Z)
		);
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

	private static void emitLoop(VertexConsumer vc, Matrix4f mat, Vector3f[] points) {
		for (int i = 0; i < points.length; i++) {
			Vector3f a = points[i];
			Vector3f b = points[(i + 1) % points.length];
			emitLine(vc, mat, a.x, a.y, a.z, b.x, b.y, b.z);
		}
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

	private static final class ParamInterval {
		final float minT;
		final float maxT;

		ParamInterval(float minT, float maxT) {
			this.minT = minT;
			this.maxT = maxT;
		}
	}
}