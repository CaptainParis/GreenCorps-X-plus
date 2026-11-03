package dev.greencorps.anticlient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryTest {
	static final class MemSink implements LineSink {
		final List<String> lines = new ArrayList<>();

		@Override
		public void write(String line) {
			lines.add(line);
		}

		@Override
		public void flush() {}

		@Override
		public void close() {}
	}

	@Test
	void jsonEscapesAndFormatsNumbers() {
		Map<String, Integer> parts = new LinkedHashMap<>();
		parts.put("a", 3);
		String s = new JsonLine("t", 4).str("n", "a\"b\\c\n").num("x", 1.23456).num("i", 7.0).num("nan", Double.NaN)
			.bool("b", true).ints("p", parts).strs("f", List.of("X", "Y")).build();
		assertEquals("{\"type\":\"t\",\"tick\":4,\"n\":\"a\\\"b\\\\c\\n\",\"x\":1.235,\"i\":7,\"nan\":null,"
			+ "\"b\":true,\"p\":{\"a\":3},\"f\":[\"X\",\"Y\"]}", s);
	}

	@Test
	void eventsCarryScenarioTag() {
		MemSink sink = new MemSink();
		Telemetry t = new Telemetry(sink, 40);
		t.scenario("T3");
		t.emit(t.event("mark", 12).str("label", "hello"));
		assertTrue(sink.lines.get(0).contains("\"sc\":\"T3\""));
		assertEquals(1, t.events());
	}

	@Test
	void flightRecorderKeepsOnlyLastRowsAndDumpsTagged() {
		MemSink sink = new MemSink();
		Telemetry t = new Telemetry(sink, 10);
		for (int i = 0; i < 25; i++) t.record(t.event("tk", i).num("v", i));
		assertEquals(0, sink.lines.size(), "ring rows are not written until dumped");
		int n = t.dump("hit", 25);
		assertEquals(10, n);
		assertEquals(11, sink.lines.size());
		assertTrue(sink.lines.get(1).contains("\"tick\":15"));
		assertTrue(sink.lines.get(10).endsWith("\"fr\":\"hit\"}"));
		assertEquals(0, t.dump("again", 26));
	}

	@Test
	void seriesPercentilesAndPing() {
		Series s = new Series();
		for (int i = 1; i <= 100; i++) s.add(i);
		assertEquals(50, s.percentile(50), 1e-9);
		assertEquals(95, s.percentile(95), 1e-9);
		PingTracker p = new PingTracker();
		assertEquals(2, p.leadTicks(0));
		for (int i = 0; i < 20; i++) p.add(120);
		assertEquals(3, p.leadTicks(0));
		assertEquals(4, p.leadTicks(1));
	}

	@Test
	void shadowPlannerChoosesToolsAndTiming() {
		ThreatAssessment mace = new ThreatAssessment(1, 90, ThreatLevel.COMMIT, Archetype.MACE, 10, 2, 8, 10, 0, 0, Map.of());
		ShadowPlan p = ShadowPlanner.plan(mace, new Inventory(4, 2, 0, 0, 3), 3, null);
		assertEquals(ShadowPlan.Action.WIND_CLUTCH, p.action());
		assertEquals(6, p.fireInTicks());
		ShadowPlan q = ShadowPlanner.plan(mace, new Inventory(0, 2, 0, 0, 3), 3, null);
		assertEquals(ShadowPlan.Action.PEARL_AWAY, q.action());
		ThreatAssessment cry = new ThreatAssessment(1, 90, ThreatLevel.COMMIT, Archetype.CRYSTAL, 0, 2, 0, 0, 20, 2, Map.of());
		assertEquals(ShadowPlan.Action.SNOWBALL_STEAL, ShadowPlanner.plan(cry, new Inventory(0, 0, 4, 8, 3), 3, null).action());
		assertEquals(ShadowPlan.Action.STRING_JAM, ShadowPlanner.plan(cry, new Inventory(0, 0, 4, 0, 3), 3, null).action());
		ThreatAssessment watch = new ThreatAssessment(1, 40, ThreatLevel.WATCH, Archetype.MACE, 10, 2, 8, 10, 0, 0, Map.of());
		assertEquals(ShadowPlan.Action.NONE, ShadowPlanner.plan(watch, new Inventory(4, 2, 4, 8, 3), 3, null).action());
	}
}
