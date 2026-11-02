package dev.greencorps.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoordHomeScreenParseTest {
	@Test
	void parsesXzAndXyz() {
		var a = CoordParse.parse("100 -200");
		assertNotNull(a);
		assertEquals(100, a.x());
		assertNull(a.y());
		assertEquals(-200, a.z());

		var b = CoordParse.parse("1.5, 64, -3.2");
		assertNotNull(b);
		assertEquals(1, b.x());
		assertEquals(64, b.y());
		assertEquals(-4, b.z()); // floor of -3.2
	}

	@Test
	void parsesTpAndLabels() {
		var a = CoordParse.parse("/tp @p 10 70 20");
		assertNotNull(a);
		assertEquals(10, a.x());
		assertEquals(70, a.y());
		assertEquals(20, a.z());

		var b = CoordParse.parse("x: 5 z: -9");
		assertNotNull(b);
		assertEquals(5, b.x());
		assertEquals(-9, b.z());
	}

	@Test
	void rejectsGarbage() {
		assertNull(CoordParse.parse(""));
		assertNull(CoordParse.parse("hello"));
		assertNull(CoordParse.parse("42"));
	}
}
