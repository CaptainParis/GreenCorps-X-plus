package dev.greencorps.stasis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StasisGeometryTest {
	@Test
	void waterTakesPriority() {
		assertEquals(StasisGeometry.Match.WATER, StasisGeometry.match(true, true));
		assertEquals(StasisGeometry.Match.WATER, StasisGeometry.match(true, false));
	}

	@Test
	void honeyOnly() {
		assertEquals(StasisGeometry.Match.HONEY, StasisGeometry.match(false, true));
		assertTrue(StasisGeometry.isInstalled(StasisGeometry.Match.HONEY));
	}

	@Test
	void none() {
		assertEquals(StasisGeometry.Match.NONE, StasisGeometry.match(false, false));
		assertFalse(StasisGeometry.isInstalled(StasisGeometry.Match.NONE));
	}
}
