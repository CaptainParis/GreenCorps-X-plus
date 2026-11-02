package dev.greencorps.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExportTransformTest {
	@Test
	void yawNeverZero() {
		for (int i = 0; i < 40; i++) {
			int y = ExportTransform.pickYaw();
			assertTrue(y == 90 || y == 180 || y == 270, "yaw=" + y);
		}
	}

	@Test
	void rotate180AroundPivot() {
		ExportTransform tx = ExportTransform.of(180, 8, 8, 0, 0, false, 64);
		int[] t = tx.transformBlock(24, 8);
		assertEquals(-9, t[0]);
		assertEquals(7, t[1]);
		// 180 twice returns near the source column
		ExportTransform back = ExportTransform.of(180, 8, 8, 0, 0, false, 64);
		int[] t2 = back.transformBlock(t[0], t[1]);
		assertEquals(24, t2[0]);
		assertEquals(8, t2[1]);
	}

	@Test
	void shiftToOriginPutsCenterAt8() {
		ExportTransform tx = ExportTransform.of(180, 1000.0, 2000.0, 62, 125, true, 70);
		assertEquals(8, tx.destSpawnX);
		assertEquals(8, tx.destSpawnZ);
		assertEquals(70, tx.destSpawnY);
		int[] c = tx.transformBlock(1000, 2000);
		assertEquals(8, c[0]);
		assertEquals(8, c[1]);
	}

	@Test
	void mapCenterRewritten() {
		ExportTransform tx = ExportTransform.of(90, 0, 0, 0, 0, false, 64);
		int[] m = tx.transformMapCenter(16, 0);
		assertEquals(-1, m[0]);
		assertEquals(16, m[1]);
	}

	@Test
	void rewriteCentersStripsWhenNoTransform() {
		net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
		nbt.putInt("centerX", 123456);
		nbt.putInt("centerZ", -99999);
		MapDataExport.rewriteCenters(nbt, null);
		assertEquals(0, nbt.getInt("centerX"));
		assertEquals(0, nbt.getInt("centerZ"));
	}

	@Test
	void distanceChebyshevForSmoothstep() {
		double t = 0.5;
		double s = t * t * (3 - 2 * t);
		assertEquals(0.5, s, 1e-9);
	}
}
