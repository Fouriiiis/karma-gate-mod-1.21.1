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
	private static final float GEOMETRY_EPSILON = 1.0e-5f;

	// Inset blocker cells slightly so a ray that only grazes a voxel corner does not
	// split the visible interval into fake micro-segments.
	private static final float BLOCKER_INTERIOR_EPSILON = 0.001f;
	private static final float INTERVAL_SNAP_EPSILON = 0.001f;

	private static final int FACE_POS_X = 0;
	private static final int FACE_NEG_X = 1;
	private static final int FACE_POS_Y = 2;
	private static final int FACE_NEG_Y = 3;
	private static final int FACE_POS_Z = 4;
	private static final int FACE_NEG_Z = 5;
	private static final int FACE_COUNT = 6;


	private static final float LIGHT_SLOPE_X =
			(float) Math.tan(Math.toRadians(LIGHT_ANGLE_X_DEGREES));
	private static final float LIGHT_SLOPE_Z =
			(float) Math.tan(Math.toRadians(LIGHT_ANGLE_Z_DEGREES));

	private static final float LIGHT_DIR_LENGTH =
			(float) Math.sqrt(LIGHT_SLOPE_X * LIGHT_SLOPE_X + 1.0f + LIGHT_SLOPE_Z * LIGHT_SLOPE_Z);

	private static final float LIGHT_DIR_X = LIGHT_SLOPE_X / LIGHT_DIR_LENGTH;
	private static final float LIGHT_DIR_Y = -1.0f / LIGHT_DIR_LENGTH;
	private static final float LIGHT_DIR_Z = LIGHT_SLOPE_Z / LIGHT_DIR_LENGTH;

	private static final EdgeDescriptor[] SILHOUETTE_EDGES = new EdgeDescriptor[] {
			new EdgeDescriptor(FACE_POS_Y, FACE_NEG_X, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f),
			new EdgeDescriptor(FACE_POS_Y, FACE_POS_X, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f),
			new EdgeDescriptor(FACE_POS_Y, FACE_NEG_Z, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f),
			new EdgeDescriptor(FACE_POS_Y, FACE_POS_Z, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f),

			new EdgeDescriptor(FACE_NEG_Y, FACE_NEG_X, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f),
			new EdgeDescriptor(FACE_NEG_Y, FACE_POS_X, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f),
			new EdgeDescriptor(FACE_NEG_Y, FACE_NEG_Z, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f),
			new EdgeDescriptor(FACE_NEG_Y, FACE_POS_Z, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f),

			new EdgeDescriptor(FACE_NEG_X, FACE_NEG_Z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f),
			new EdgeDescriptor(FACE_POS_X, FACE_NEG_Z, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f),
			new EdgeDescriptor(FACE_NEG_X, FACE_POS_Z, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f),
			new EdgeDescriptor(FACE_POS_X, FACE_POS_Z, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f)
	};

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
						emitSolidBlockSilhouetteShadow(world, lines, mat, x, y, z);
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
		return isExposedAir(world, x, y, z) && !isOneBlockHole(world, x, y, z);
	}

	private static void emitSolidBlockSilhouetteShadow(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			int blockX,
			int blockY,
			int blockZ
	) {
		if (!isSkyExposedAir(world, blockX, blockY + 1, blockZ)) {
			return;
		}

		boolean[] exposedFaces = gatherExposedFaces(world, blockX, blockY, blockZ);
		for (EdgeDescriptor edge : SILHOUETTE_EDGES) {
			emitSilhouetteEdgeIfNeeded(world, vc, mat, blockX, blockY, blockZ, exposedFaces, edge);
		}
	}

	private static boolean[] gatherExposedFaces(World world, int blockX, int blockY, int blockZ) {
		boolean[] exposed = new boolean[FACE_COUNT];
		exposed[FACE_POS_X] = isFaceExposedForSolidSilhouette(world, blockX + 1, blockY, blockZ);
		exposed[FACE_NEG_X] = isFaceExposedForSolidSilhouette(world, blockX - 1, blockY, blockZ);
		exposed[FACE_POS_Y] = isFaceExposedForSolidSilhouette(world, blockX, blockY + 1, blockZ);
		exposed[FACE_NEG_Y] = isFaceExposedForSolidSilhouette(world, blockX, blockY - 1, blockZ);
		exposed[FACE_POS_Z] = isFaceExposedForSolidSilhouette(world, blockX, blockY, blockZ + 1);
		exposed[FACE_NEG_Z] = isFaceExposedForSolidSilhouette(world, blockX, blockY, blockZ - 1);
		return exposed;
	}

	private static void emitSilhouetteEdgeIfNeeded(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			int blockX,
			int blockY,
			int blockZ,
			boolean[] exposedFaces,
			EdgeDescriptor edge
	) {
		if (!exposedFaces[edge.faceA] || !exposedFaces[edge.faceB]) {
			return;
		}

		if (isFaceFrontFacing(edge.faceA) == isFaceFrontFacing(edge.faceB)) {
			return;
		}

		float ax = blockX + edge.ax;
		float ay = blockY + edge.ay;
		float az = blockZ + edge.az;
		float bx = blockX + edge.bx;
		float by = blockY + edge.by;
		float bz = blockZ + edge.bz;

		float sampleAx = chooseProjectionSampleX(edge.ax, exposedFaces[FACE_NEG_X], exposedFaces[FACE_POS_X]);
		float sampleAz = chooseProjectionSampleZ(edge.az, exposedFaces[FACE_NEG_Z], exposedFaces[FACE_POS_Z]);
		float sampleBx = chooseProjectionSampleX(edge.bx, exposedFaces[FACE_NEG_X], exposedFaces[FACE_POS_X]);
		float sampleBz = chooseProjectionSampleZ(edge.bz, exposedFaces[FACE_NEG_Z], exposedFaces[FACE_POS_Z]);

		emitProjectedShadowEdge(
				world,
				vc,
				mat,
				edge.faceA,
				edge.faceB,
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

	private static float chooseProjectionSampleX(float localX, boolean negXExposed, boolean posXExposed) {
		if (localX < 0.5f && negXExposed) {
			return -1.0f;
		}
		if (localX > 0.5f && posXExposed) {
			return 1.0f;
		}
		return Math.signum(LIGHT_DIR_X);
	}

	private static float chooseProjectionSampleZ(float localZ, boolean negZExposed, boolean posZExposed) {
		if (localZ < 0.5f && negZExposed) {
			return -1.0f;
		}
		if (localZ > 0.5f && posZExposed) {
			return 1.0f;
		}
		return Math.signum(LIGHT_DIR_Z);
	}

	private static boolean isFaceFrontFacing(int face) {
		return switch (face) {
			case FACE_POS_X -> LIGHT_DIR_X > 0.0f;
			case FACE_NEG_X -> LIGHT_DIR_X < 0.0f;
			case FACE_POS_Y -> LIGHT_DIR_Y > 0.0f;
			case FACE_NEG_Y -> LIGHT_DIR_Y < 0.0f;
			case FACE_POS_Z -> LIGHT_DIR_Z > 0.0f;
			case FACE_NEG_Z -> LIGHT_DIR_Z < 0.0f;
			default -> false;
		};
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
				new Vector3f(holeX, topY, holeZ),
				new Vector3f(holeX + 1, topY, holeZ),
				new Vector3f(holeX + 1, topY, holeZ + 1),
				new Vector3f(holeX, topY, holeZ + 1)
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
			emitCross(vc, mat, topCorners[i].x, topCorners[i].y, topCorners[i].z, diagA, diagB, DebugLineColor.HOLE_TOP_CROSS_A, DebugLineColor.HOLE_TOP_CROSS_B);
			emitCross(vc, mat, exitCorners[i].x, exitCorners[i].y, exitCorners[i].z, diagA, diagB, DebugLineColor.HOLE_EXIT_CROSS_A, DebugLineColor.HOLE_EXIT_CROSS_B);
			emitCross(vc, mat, projectedCorners[i].x, projectedCorners[i].y, projectedCorners[i].z, diagA, diagB, DebugLineColor.HOLE_RECEIVER_CROSS_A, DebugLineColor.HOLE_RECEIVER_CROSS_B);
		}

		emitLoop(vc, mat, topCorners, DebugLineColor.HOLE_TOP_LOOP);
		emitLoop(vc, mat, exitCorners, DebugLineColor.HOLE_EXIT_LOOP);

		for (int i = 0; i < 4; i++) {
			emitLine(
					vc, mat,
					topCorners[i].x, topCorners[i].y, topCorners[i].z,
					exitCorners[i].x, exitCorners[i].y, exitCorners[i].z,
					DebugLineColor.HOLE_SHAFT_CONNECTOR
			);

			emitLine(
					vc, mat,
					exitCorners[i].x, exitCorners[i].y, exitCorners[i].z,
					projectedCorners[i].x, projectedCorners[i].y, projectedCorners[i].z,
					DebugLineColor.HOLE_RECEIVER_CONNECTOR
			);
		}

		emitLoop(vc, mat, projectedCorners, DebugLineColor.HOLE_RECEIVER_LOOP);
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
			int faceA,
			int faceB,
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
		if (isHorizontalAxisAlignedEdge(ax, ay, az, bx, by, bz)) {
			emitClippedHorizontalShadowEdge(world, vc, mat, faceA, faceB, ax, ay, az, bx, bz);
			return;
		}

		emitProjectedShadowEdgeDirect(
				world,
				vc,
				mat,
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

	private static boolean isHorizontalAxisAlignedEdge(float ax, float ay, float az, float bx, float by, float bz) {
		if (Math.abs(ay - by) > GEOMETRY_EPSILON) {
			return false;
		}

		boolean alongX = Math.abs(ax - bx) > GEOMETRY_EPSILON && Math.abs(az - bz) <= GEOMETRY_EPSILON;
		boolean alongZ = Math.abs(az - bz) > GEOMETRY_EPSILON && Math.abs(ax - bx) <= GEOMETRY_EPSILON;
		return alongX || alongZ;
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
		Vector3f exitA = computeAdjustedExitPointForSourceCorner(world, ax, ay, az);
		Vector3f exitB = computeAdjustedExitPointForSourceCorner(world, bx, by, bz);

		Vector3f projA = projectPointToReceiver(world, exitA.x, exitA.y, exitA.z, sampleAx, sampleAz);
		Vector3f projB = projectPointToReceiver(world, exitB.x, exitB.y, exitB.z, sampleBx, sampleBz);
		if (projA == null || projB == null) {
			return;
		}

		emitLine(vc, mat, ax, ay, az, bx, by, bz, DebugLineColor.SOURCE_EDGE);
		emitLine(vc, mat, ax, ay, az, exitA.x, exitA.y, exitA.z, DebugLineColor.DIRECT_CONNECTOR_A);
		emitLine(vc, mat, bx, by, bz, exitB.x, exitB.y, exitB.z, DebugLineColor.DIRECT_CONNECTOR_B);
		emitLine(vc, mat, exitA.x, exitA.y, exitA.z, exitB.x, exitB.y, exitB.z, DebugLineColor.EXIT_EDGE);
		emitLine(vc, mat, exitA.x, exitA.y, exitA.z, projA.x, projA.y, projA.z, DebugLineColor.RECEIVER_CONNECTOR_A);
		emitLine(vc, mat, exitB.x, exitB.y, exitB.z, projB.x, projB.y, projB.z, DebugLineColor.RECEIVER_CONNECTOR_B);
		emitLine(vc, mat, projA.x, projA.y, projA.z, projB.x, projB.y, projB.z, DebugLineColor.RECEIVER_EDGE);
	}

	private static Vector3f computeAdjustedExitPointForSourceCorner(
			World world,
			float sourceX,
			float sourceY,
			float sourceZ
	) {
		float rawExitX = sourceX + LIGHT_SLOPE_X;
		float rawExitZ = sourceZ + LIGHT_SLOPE_Z;
		float exitY = sourceY - SHAFT_DEPTH_BLOCKS;

		if (!isNearInteger(sourceX) || !isNearInteger(sourceZ)) {
			return new Vector3f(rawExitX, exitY, rawExitZ);
		}

		int ix = Math.round(sourceX);
		int iz = Math.round(sourceZ);
		int blockY = MathHelper.floor(sourceY - PROJECTION_SAMPLE_EPSILON);

		boolean nw = isOpaqueFullCube(world, ix - 1, blockY, iz - 1);
		boolean ne = isOpaqueFullCube(world, ix,     blockY, iz - 1);
		boolean sw = isOpaqueFullCube(world, ix - 1, blockY, iz);
		boolean se = isOpaqueFullCube(world, ix,     blockY, iz);

		int solidCount = 0;
		solidCount += nw ? 1 : 0;
		solidCount += ne ? 1 : 0;
		solidCount += sw ? 1 : 0;
		solidCount += se ? 1 : 0;

		if (solidCount != 3) {
			return new Vector3f(rawExitX, exitY, rawExitZ);
		}

		float adjustedX = rawExitX;
		float adjustedZ = rawExitZ;

		if (!nw) {
			adjustedX = Math.min(adjustedX, sourceX);
			adjustedZ = Math.min(adjustedZ, sourceZ);
		} else if (!ne) {
			adjustedX = Math.max(adjustedX, sourceX);
			adjustedZ = Math.min(adjustedZ, sourceZ);
		} else if (!sw) {
			adjustedX = Math.min(adjustedX, sourceX);
			adjustedZ = Math.max(adjustedZ, sourceZ);
		} else {
			adjustedX = Math.max(adjustedX, sourceX);
			adjustedZ = Math.max(adjustedZ, sourceZ);
		}

		return new Vector3f(adjustedX, exitY, adjustedZ);
	}

	private static boolean isNearInteger(float value) {
		return Math.abs(value - Math.round(value)) <= 1.0e-4f;
	}

	private static void emitClippedHorizontalShadowEdge(
			World world,
			VertexConsumer vc,
			Matrix4f mat,
			int faceA,
			int faceB,
			float ax,
			float ay,
			float az,
			float bx,
			float bz
	) {
		boolean alongX = Math.abs(ax - bx) > GEOMETRY_EPSILON;
		float axisStart = alongX ? ax : az;
		float axisEnd = alongX ? bx : bz;
		float constantCoord = alongX ? az : ax;

		if (axisEnd < axisStart) {
			float tmp = axisStart;
			axisStart = axisEnd;
			axisEnd = tmp;
		}

		float edgeLen = axisEnd - axisStart;
		if (edgeLen <= SHADOW_CLIP_EPSILON) {
			return;
		}

		float exitY = ay - SHAFT_DEPTH_BLOCKS;
		int clipBlockY = MathHelper.floor(ay - PROJECTION_SAMPLE_EPSILON);

		ArrayList<ParamInterval> visible = new ArrayList<>();
		visible.add(new ParamInterval(0.0f, 1.0f));

		float minSweepX = Math.min(Math.min(ax, bx), Math.min(ax + LIGHT_SLOPE_X, bx + LIGHT_SLOPE_X));
		float maxSweepX = Math.max(Math.max(ax, bx), Math.max(ax + LIGHT_SLOPE_X, bx + LIGHT_SLOPE_X));
		float minSweepZ = Math.min(Math.min(az, bz), Math.min(az + LIGHT_SLOPE_Z, bz + LIGHT_SLOPE_Z));
		float maxSweepZ = Math.max(Math.max(az, bz), Math.max(az + LIGHT_SLOPE_Z, bz + LIGHT_SLOPE_Z));

		int minCellX = MathHelper.floor(minSweepX) - 1;
		int maxCellX = MathHelper.floor(maxSweepX) + 1;
		int minCellZ = MathHelper.floor(minSweepZ) - 1;
		int maxCellZ = MathHelper.floor(maxSweepZ) + 1;

		for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
			for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
				ParamInterval blocked = computeBlockedIntervalForHorizontalEdge(
						world,
						alongX,
						axisStart,
						constantCoord,
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

			float edgeA = axisStart + edgeLen * t0;
			float edgeB = axisStart + edgeLen * t1;

			float srcAx = alongX ? edgeA : constantCoord;
			float srcAz = alongX ? constantCoord : edgeA;
			float srcBx = alongX ? edgeB : constantCoord;
			float srcBz = alongX ? constantCoord : edgeB;
			float srcY = ay;

			Vector3f exitA = computeAdjustedExitPointForSourceCorner(world, srcAx, srcY, srcAz);
			Vector3f exitB = computeAdjustedExitPointForSourceCorner(world, srcBx, srcY, srcBz);

			Vector3f projA = projectPointToReceiver(
					world,
					exitA.x,
					exitA.y,
					exitA.z,
					Math.signum(LIGHT_DIR_X),
					Math.signum(LIGHT_DIR_Z)
			);
			Vector3f projB = projectPointToReceiver(
					world,
					exitB.x,
					exitB.y,
					exitB.z,
					Math.signum(LIGHT_DIR_X),
					Math.signum(LIGHT_DIR_Z)
			);

			if (projA == null || projB == null) {
				continue;
			}

			emitLine(vc, mat, srcAx, srcY, srcAz, srcBx, srcY, srcBz, DebugLineColor.SOURCE_EDGE);
			emitLine(vc, mat, srcAx, srcY, srcAz, exitA.x, exitA.y, exitA.z, DebugLineColor.SHAFT_CONNECTOR_A);
			emitLine(vc, mat, srcBx, srcY, srcBz, exitB.x, exitB.y, exitB.z, DebugLineColor.SHAFT_CONNECTOR_B);
			emitLine(vc, mat, exitA.x, exitA.y, exitA.z, exitB.x, exitB.y, exitB.z, DebugLineColor.EXIT_EDGE);
			emitLine(vc, mat, exitA.x, exitA.y, exitA.z, projA.x, projA.y, projA.z, DebugLineColor.RECEIVER_CONNECTOR_A);
			emitLine(vc, mat, exitB.x, exitB.y, exitB.z, projB.x, projB.y, projB.z, DebugLineColor.RECEIVER_CONNECTOR_B);
			emitLine(vc, mat, projA.x, projA.y, projA.z, projB.x, projB.y, projB.z, DebugLineColor.RECEIVER_EDGE);
		}
	}

	private static ParamInterval computeBlockedIntervalForHorizontalEdge(
			World world,
			boolean alongX,
			float edgeStart,
			float edgeConstCoord,
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

		if (alongX) {
			if (Math.abs(LIGHT_SLOPE_Z) < GEOMETRY_EPSILON) {
				if (!(edgeConstCoord > blockMinZ && edgeConstCoord < blockMaxZ)) {
					return null;
				}
			} else {
				float uz0 = (blockMinZ - edgeConstCoord) / LIGHT_SLOPE_Z;
				float uz1 = (blockMaxZ - edgeConstCoord) / LIGHT_SLOPE_Z;
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

			float tMin = (blockMinX - edgeStart - maxShift) / edgeLen;
			float tMax = (blockMaxX - edgeStart - minShift) / edgeLen;
			return buildInterval(tMin, tMax);
		}

		if (Math.abs(LIGHT_SLOPE_X) < GEOMETRY_EPSILON) {
			if (!(edgeConstCoord > blockMinX && edgeConstCoord < blockMaxX)) {
				return null;
			}
		} else {
			float ux0 = (blockMinX - edgeConstCoord) / LIGHT_SLOPE_X;
			float ux1 = (blockMaxX - edgeConstCoord) / LIGHT_SLOPE_X;
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

		float tMin = (blockMinZ - edgeStart - maxShift) / edgeLen;
		float tMax = (blockMaxZ - edgeStart - minShift) / edgeLen;
		return buildInterval(tMin, tMax);
	}

	private static ParamInterval buildInterval(float minT, float maxT) {
		float snappedMin = snapUnitInterval(Math.max(0.0f, minT));
		float snappedMax = snapUnitInterval(Math.min(1.0f, maxT));
		if (snappedMax <= snappedMin + SHADOW_CLIP_EPSILON) {
			return null;
		}
		return new ParamInterval(snappedMin, snappedMax);
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
		ParamInterval interval = buildInterval(minT, maxT);
		if (interval != null) {
			out.add(interval);
		}
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

	private static void emitLoop(VertexConsumer vc, Matrix4f mat, Vector3f[] points, DebugLineColor color) {
		for (int i = 0; i < points.length; i++) {
			Vector3f a = points[i];
			Vector3f b = points[(i + 1) % points.length];
			emitLine(vc, mat, a.x, a.y, a.z, b.x, b.y, b.z, color);
		}
	}

	private static void emitCross(
			VertexConsumer vc,
			Matrix4f mat,
			float px,
			float py,
			float pz,
			Vector3f diagA,
			Vector3f diagB,
			DebugLineColor colorA,
			DebugLineColor colorB
	) {
		emitLine(
				vc, mat,
				px - diagA.x, py - diagA.y, pz - diagA.z,
				px + diagA.x, py + diagA.y, pz + diagA.z,
				colorA
		);

		emitLine(
				vc, mat,
				px - diagB.x, py - diagB.y, pz - diagB.z,
				px + diagB.x, py + diagB.y, pz + diagB.z,
				colorB
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
		emitLine(vc, mat, x1, y1, z1, x2, y2, z2, DebugLineColor.DEFAULT);
	}

	private static void emitLine(
			VertexConsumer vc,
			Matrix4f mat,
			float x1,
			float y1,
			float z1,
			float x2,
			float y2,
			float z2,
			DebugLineColor color
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
				.color(color.r, color.g, color.b, 255)
				.normal(nx, ny, nz);

		vc.vertex(mat, x2, y2, z2)
				.color(color.r, color.g, color.b, 255)
				.normal(nx, ny, nz);
	}


	private enum DebugLineColor {
		DEFAULT(255, 0, 0),
		SOURCE_EDGE(255, 0, 0),
		SHAFT_CONNECTOR_A(0, 255, 0),
		SHAFT_CONNECTOR_B(0, 120, 255),
		EXIT_EDGE(255, 255, 0),
		RECEIVER_CONNECTOR_A(255, 0, 255),
		RECEIVER_CONNECTOR_B(0, 255, 255),
		RECEIVER_EDGE(255, 136, 0),
		DIRECT_CONNECTOR_A(128, 255, 128),
		DIRECT_CONNECTOR_B(128, 192, 255),
		HOLE_TOP_LOOP(255, 255, 255),
		HOLE_EXIT_LOOP(160, 160, 160),
		HOLE_RECEIVER_LOOP(255, 200, 80),
		HOLE_TOP_CROSS_A(255, 180, 180),
		HOLE_TOP_CROSS_B(255, 120, 120),
		HOLE_EXIT_CROSS_A(180, 255, 180),
		HOLE_EXIT_CROSS_B(120, 255, 120),
		HOLE_RECEIVER_CROSS_A(180, 180, 255),
		HOLE_RECEIVER_CROSS_B(120, 120, 255),
		HOLE_SHAFT_CONNECTOR(200, 255, 200),
		HOLE_RECEIVER_CONNECTOR(200, 200, 255);

		final int r;
		final int g;
		final int b;

		DebugLineColor(int r, int g, int b) {
			this.r = r;
			this.g = g;
			this.b = b;
		}
	}

	private static final class EdgeDescriptor {
		final int faceA;
		final int faceB;
		final float ax;
		final float ay;
		final float az;
		final float bx;
		final float by;
		final float bz;

		EdgeDescriptor(int faceA, int faceB, float ax, float ay, float az, float bx, float by, float bz) {
			this.faceA = faceA;
			this.faceB = faceB;
			this.ax = ax;
			this.ay = ay;
			this.az = az;
			this.bx = bx;
			this.by = by;
			this.bz = bz;
		}
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
