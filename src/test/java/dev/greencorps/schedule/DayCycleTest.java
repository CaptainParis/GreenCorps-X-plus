package dev.greencorps.schedule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DayCycleTest {
	@Test
	void clockFormatAndParseRoundTrip() {
		assertEquals("06:00", DayCycle.formatClock(0));
		assertEquals("12:00", DayCycle.formatClock(6000));
		assertEquals("18:00", DayCycle.formatClock(12_000));
		assertEquals("00:00", DayCycle.formatClock(18_000));
		assertEquals("05:00", DayCycle.formatClock(23_000));

		assertEquals(0, DayCycle.parseTime("06:00").orElseThrow());
		assertEquals(6000, DayCycle.parseTime("12:00").orElseThrow());
		assertEquals(12_000, DayCycle.parseTime("18:00").orElseThrow());
		assertEquals(18_000, DayCycle.parseTime("00:00").orElseThrow());
		assertEquals(23_000, DayCycle.parseTime("05:00").orElseThrow());
		assertEquals(12542, DayCycle.parseTime("12542").orElseThrow());
		assertEquals(23_460, DayCycle.parseDayTick("23,460").orElseThrow());
		assertEquals(23_460, DayCycle.parseDayTick("23460").orElseThrow());
		assertEquals(23_460, DayCycle.parseTime("23,460").orElseThrow());
		assertEquals("23,460", DayCycle.formatDayTick(23_460));
		assertTrue(DayCycle.parseDayTick("06:00").isEmpty());
		assertTrue(DayCycle.parseTime("24:00").isEmpty());
		assertTrue(DayCycle.parseTime("24000").isEmpty());
		assertTrue(DayCycle.parseTime("nope").isEmpty());
		assertEquals(1_000_000L, DayCycle.parseLongTick("1,000,000").orElseThrow());
	}

	@Test
	void sunriseSunsetOffset() {
		assertEquals(23_000, DayCycle.resolveSunrise(0));
		assertEquals(12_000, DayCycle.resolveSunset(0));
		assertEquals(12_542, DayCycle.resolveSunset(542));
		assertEquals(22_900, DayCycle.resolveSunrise(-100));
		assertEquals(wrapExpect(12_000 - 200), DayCycle.resolveSunset(-200));
	}

	@Test
	void stepCrossesTarget() {
		assertTrue(DayCycle.crossedDayTick(999, 1000, 1000, 40));
		assertFalse(DayCycle.crossedDayTick(1000, 1001, 1000, 40));
		assertTrue(DayCycle.crossedDayTick(990, 1005, 1000, 40));
		assertFalse(DayCycle.crossedDayTick(990, 1005, 1000, 10));
	}

	@Test
	void wrapAtMidnight() {
		// Target 10, prev 23990 → curr 15 (delta 25): crosses
		assertTrue(DayCycle.crossedDayTick(23_990, 15, 10, 40));
		assertFalse(DayCycle.crossedDayTick(23_990, 15, 10, 20));
		assertFalse(DayCycle.crossedDayTick(23_990, 5, 10, 40));
	}

	@Test
	void sleepSizedSkipDoesNotFire() {
		// Sleep often jumps thousands of ticks past dusk into morning
		assertFalse(DayCycle.crossedDayTick(12_000, 0, 12_542, 40));
		assertFalse(DayCycle.crossedDayTick(11_000, 23_000, 12_000, 40));
	}

	@Test
	void worldAgeOnceAndEveryN() {
		assertTrue(DayCycle.crossedWorldAge(99, 100, 100, 40));
		assertFalse(DayCycle.crossedWorldAge(100, 101, 100, 40));
		assertFalse(DayCycle.crossedWorldAge(50, 200, 100, 40));

		assertTrue(DayCycle.crossedEveryN(99, 100, 100));
		assertTrue(DayCycle.crossedEveryN(100, 250, 100)); // one fire after lag
		assertFalse(DayCycle.crossedEveryN(100, 150, 100));
		assertFalse(DayCycle.crossedEveryN(200, 200, 100));
	}

	@Test
	void dayIndex() {
		assertEquals(0, DayCycle.dayIndex(0));
		assertEquals(0, DayCycle.dayIndex(23_999));
		assertEquals(1, DayCycle.dayIndex(24_000));
		assertEquals(-1, DayCycle.dayIndex(-1));
	}

	@Test
	void leadTarget() {
		assertEquals(1000, DayCycle.leadTarget(1000, 0));
		assertEquals(990, DayCycle.leadTarget(1000, 10));
		assertEquals(23_995, DayCycle.leadTarget(5, 10));
		assertEquals(0, DayCycle.leadTarget(0, -3));
		assertEquals(12_000 - 2, DayCycle.leadWorldAge(12_000, 2));
		assertEquals(0, DayCycle.leadWorldAge(1, 5));
		assertEquals(0, DayCycle.leadFromPingMs(0, 10));
		assertEquals(1, DayCycle.leadFromPingMs(50, 10));
		assertEquals(2, DayCycle.leadFromPingMs(100, 10));
		assertEquals(10, DayCycle.leadFromPingMs(10_000, 10));
	}

	private static int wrapExpect(int t) {
		return DayCycle.wrap(t);
	}
}
