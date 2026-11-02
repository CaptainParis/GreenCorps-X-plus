package dev.greencorps.modules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndFloorPatchTest {
	@Test
	void gridAlignsDownIncludingNegatives() {
		assertEquals(0, EndFloor.alignDown(0));
		assertEquals(0, EndFloor.alignDown(15));
		assertEquals(16, EndFloor.alignDown(16));
		assertEquals(-16, EndFloor.alignDown(-1));
		assertEquals(-16, EndFloor.alignDown(-16));
	}

	@Test
	void patchStaysOnSixteenBlockLinesNearTheCamera() {
		int min = EndFloor.patchMin(100, EndFloor.PATCH);
		int max = EndFloor.patchMax(100, EndFloor.PATCH);
		assertEquals(0, min % EndFloor.GRID);
		assertEquals(0, max % EndFloor.GRID);
		assertTrue(min <= 100 - EndFloor.PATCH);
		assertTrue(max >= 100 + EndFloor.PATCH);
		assertTrue(max - min <= EndFloor.PATCH * 2 + EndFloor.GRID);
	}

	@Test
	void widerRadiusStillAlignsAndCoversTheCamera() {
		int min = EndFloor.patchMin(100, 200);
		int max = EndFloor.patchMax(100, 200);
		assertEquals(0, min % EndFloor.GRID);
		assertEquals(0, max % EndFloor.GRID);
		assertTrue(min <= 100 - 200);
		assertTrue(max >= 100 + 200);
	}

	@Test
	void floorHidesPastVisibleDistanceAndPullsInBeforeTheFarPlane() {
		assertEquals(0.0, EndFloor.drawPlaneY(100, 0, 512, 1000), 0.01);
		assertTrue(Double.isNaN(EndFloor.drawPlaneY(1000, 0, 512, 200)));
		double pulled = EndFloor.drawPlaneY(1000, 0, 2000, 200);
		assertEquals(1000 - 200 * 0.85, pulled, 0.01);
		assertTrue(EndFloor.drawnAtRealHeight(100, 0, 512, 1000));
		assertFalse(EndFloor.drawnAtRealHeight(1000, 0, 2000, 200));
	}

	@Test
	void layerDrawsFromCheckboxOrElytraMode() {
		assertTrue(EndFloor.layerActive(true, false, false));
		assertFalse(EndFloor.layerActive(false, false, false));
		assertFalse(EndFloor.layerActive(false, true, false));
		assertTrue(EndFloor.layerActive(false, true, true));
		assertTrue(EndFloor.OnElytra.END_FLOOR_ONLY.includesFloor());
		assertFalse(EndFloor.OnElytra.END_FLOOR_ONLY.includesHelper());
		assertTrue(EndFloor.OnElytra.HELPER_ONLY.includesHelper());
		assertFalse(EndFloor.OnElytra.HELPER_ONLY.includesFloor());
		assertTrue(EndFloor.OnElytra.BOTH.includesFloor());
		assertTrue(EndFloor.OnElytra.BOTH.includesHelper());
		assertFalse(EndFloor.OnElytra.OFF.includesFloor());
		assertFalse(EndFloor.OnElytra.OFF.includesHelper());
	}

	@Test
	void blockGridFadesWithVerticalDistance() {
		assertEquals(0, EndFloor.blockGridAlpha(100, 0));
		assertEquals(0, EndFloor.blockGridAlpha(32, 0));
		assertEquals(EndFloor.BLOCK_GRID_ALPHA, EndFloor.blockGridAlpha(0, 0));
		assertEquals(EndFloor.BLOCK_GRID_ALPHA / 2, EndFloor.blockGridAlpha(16, 0));
		assertEquals(EndFloor.BLOCK_GRID_ALPHA / 2, EndFloor.blockGridAlpha(275 + 16, 275));
	}
}
