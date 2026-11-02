package dev.greencorps.export;

import dev.greencorps.backup.Home;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.security.SecureRandom;

/**
 * Yaw scramble (90/180/270) around home center, optional Shift Coords To 0.
 * Pure math — no world mutation.
 */
public final class ExportTransform {
	private static final SecureRandom RNG = new SecureRandom();

	public final int yawDegrees;
	public final BlockRotation blockRotation;
	public final double pivotX;
	public final double pivotZ;
	public final int srcCenterChunkX;
	public final int srcCenterChunkZ;
	public final boolean shiftToOrigin;
	/** Added after yaw so src center chunk lands at (0,0). */
	public final int shiftBlockX;
	public final int shiftBlockZ;
	public final int destSpawnX;
	public final int destSpawnY;
	public final int destSpawnZ;
	public final float destYaw;

	private ExportTransform(int yawDegrees, double pivotX, double pivotZ,
							int srcCenterChunkX, int srcCenterChunkZ,
							boolean shiftToOrigin, int spawnY) {
		this.yawDegrees = yawDegrees;
		this.blockRotation = switch (yawDegrees) {
			case 90 -> BlockRotation.CLOCKWISE_90;
			case 270 -> BlockRotation.COUNTERCLOCKWISE_90;
			default -> BlockRotation.CLOCKWISE_180;
		};
		this.pivotX = pivotX;
		this.pivotZ = pivotZ;
		this.srcCenterChunkX = srcCenterChunkX;
		this.srcCenterChunkZ = srcCenterChunkZ;
		this.shiftToOrigin = shiftToOrigin;

		int[] afterYaw = transformBlockNoShift((int) Math.floor(pivotX), (int) Math.floor(pivotZ));
		if (shiftToOrigin) {
			this.shiftBlockX = 8 - afterYaw[0];
			this.shiftBlockZ = 8 - afterYaw[1];
			this.destSpawnX = 8;
			this.destSpawnZ = 8;
		} else {
			this.shiftBlockX = 0;
			this.shiftBlockZ = 0;
			this.destSpawnX = afterYaw[0];
			this.destSpawnZ = afterYaw[1];
		}
		this.destSpawnY = spawnY > 0 ? spawnY : 64;
		this.destYaw = yawDegrees;
	}

	public static ExportTransform forHome(Home home, boolean shiftCoordsTo0) {
		int yaw = pickYaw();
		int spawnY = home.spawnY > 0 ? home.spawnY : 64;
		return new ExportTransform(yaw, home.centerBlockX(), home.centerBlockZ(),
			home.centerChunkX, home.centerChunkZ, shiftCoordsTo0, spawnY);
	}

	/** Deterministic factory for tests. */
	public static ExportTransform of(int yawDegrees, double pivotX, double pivotZ,
									 int centerChunkX, int centerChunkZ,
									 boolean shiftToOrigin, int spawnY) {
		int yaw = yawDegrees;
		if (yaw != 90 && yaw != 180 && yaw != 270) yaw = 180;
		return new ExportTransform(yaw, pivotX, pivotZ, centerChunkX, centerChunkZ, shiftToOrigin, spawnY);
	}

	public static int pickYaw() {
		return switch (RNG.nextInt(3)) {
			case 0 -> 90;
			case 1 -> 270;
			default -> 180;
		};
	}

	public int[] transformBlock(int x, int z) {
		int[] y = transformBlockNoShift(x, z);
		y[0] += shiftBlockX;
		y[1] += shiftBlockZ;
		return y;
	}

	public int[] transformBlockNoShift(int x, int z) {
		double rx = x + 0.5 - pivotX;
		double rz = z + 0.5 - pivotZ;
		double nx;
		double nz;
		switch (yawDegrees) {
			case 90 -> { nx = -rz; nz = rx; }
			case 270 -> { nx = rz; nz = -rx; }
			default -> { nx = -rx; nz = -rz; }
		}
		return new int[] {
			(int) Math.floor(pivotX + nx),
			(int) Math.floor(pivotZ + nz)
		};
	}

	public ChunkPos transformChunk(int cx, int cz) {
		// Rotate chunk by transforming its center block
		int bx = cx * 16 + 8;
		int bz = cz * 16 + 8;
		int[] t = transformBlock(bx, bz);
		return new ChunkPos(t[0] >> 4, t[1] >> 4);
	}

	public BlockPos transformPos(BlockPos pos) {
		int[] t = transformBlock(pos.getX(), pos.getZ());
		return new BlockPos(t[0], pos.getY(), t[1]);
	}

	public double[] transformEntityXZ(double x, double z) {
		double rx = x - pivotX;
		double rz = z - pivotZ;
		double nx;
		double nz;
		switch (yawDegrees) {
			case 90 -> { nx = -rz; nz = rx; }
			case 270 -> { nx = rz; nz = -rx; }
			default -> { nx = -rx; nz = -rz; }
		}
		return new double[] { pivotX + nx + shiftBlockX, pivotZ + nz + shiftBlockZ };
	}

	public float transformYaw(float entityYaw) {
		return entityYaw + yawDegrees;
	}

	/** Map center rewrite: rotate + shift like blocks. */
	public int[] transformMapCenter(int centerX, int centerZ) {
		return transformBlock(centerX, centerZ);
	}
}
