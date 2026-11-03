package dev.greencorps.anticlient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Drives the pure pipeline through a scripted mace dive and checks the measured outcome. */
class ScenarioReplayTest {
	@Test
	void scriptedMaceDiveIsCommittedMeasuredAndLogged() throws IOException {
		Path out = Path.of("build", "test-output", "anticlient-sample.jsonl");
		Files.createDirectories(out.getParent());
		Files.deleteIfExists(out);
		FileSink sink = new FileSink(out);
		Telemetry tel = new Telemetry(sink, 120);
		tel.scenario("T-sim");
		SessionStats stats = new SessionStats();
		ThreatConfig tc = new ThreatConfig();
		SelfView me = new SelfView(0, 64, 0, 2);

		PlayerTrack t = new PlayerTrack(9);
		t.name = "Diver";
		double x = 5, y = 80, vy = 0, vx = -0.6;
		int hitTick = -1;
		int commitTick = -1;
		for (int tick = 0; tick < 40; tick++) {
			boolean ground = y <= 64;
			t.update(new Observation(tick, x, ground ? 64 : y, 0, ground, 64, false, HeldKind.MACE,
				HeldKind.NONE, ChestKind.ARMOR, 55));
			ThreatAssessment a = ThreatModel.evaluate(t, me, List.of(), tc, tick);
			tel.record(tel.event("tk", tick).num("score", a.score()).str("l", a.level().name()));
			if (commitTick < 0 && a.level() == ThreatLevel.COMMIT && stats.scorecard.openCommit(t.id, a.archetype(), tick, a.etaTicks(), a.score())) {
				commitTick = tick;
				tel.emit(tel.event("commit", tick).num("score", a.score()).num("eta", a.etaTicks()).ints("parts", a.parts()));
				tel.dump("commit", tick);
			}
			double dist = Math.hypot(x - me.x(), (ground ? 64 : y) - me.y());
			if (hitTick < 0 && commitTick >= 0 && dist <= 3.0) {
				hitTick = tick;
				Scorecard.Resolved r = stats.scorecard.onHit(Archetype.MACE, tick, t.id);
				assertNotNull(r, "the hit must resolve the open commit");
				tel.emit(tel.event("hit", tick).str("arch", "MACE").bool("had_commit", true)
					.num("lead", r.leadTicks()).num("eta_err", r.etaErrorTicks()));
			}
			x += vx;
			y += vy;
			vy = (vy - 0.08) * 0.98;
			vx *= 0.91;
		}
		tel.emit(tel.event("session_end", 40).strs("summary", stats.lines("T-sim")));
		tel.close();

		assertTrue(commitTick > 0 && hitTick > commitTick, "commit " + commitTick + " hit " + hitTick);
		assertEquals(1, stats.scorecard.tp());
		assertEquals(0, stats.scorecard.fp());
		assertTrue(stats.scorecard.leadTicks().max() >= 2, "need at least a couple of ticks of warning");
		assertTrue(Files.readAllLines(out).stream().anyMatch(l -> l.contains("\"fr\":\"commit\"")));
	}
}
