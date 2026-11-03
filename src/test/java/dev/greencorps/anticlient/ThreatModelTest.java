package dev.greencorps.anticlient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ThreatModelTest {
	private static final ThreatConfig CFG = new ThreatConfig();
	private static final SelfView ME = new SelfView(0, 64, 0, 2);

	/** Same physics the tracker projects with, so the test checks logic, not tuning. */
	private static PlayerTrack dive(HeldKind held, int ticks, double startX, double startY, double vx, double[] etaOut,
									ThreatLevel[] firstCommit, int[] commitTick) {
		PlayerTrack t = new PlayerTrack(7);
		double x = startX, y = startY, vy = 0;
		double vxx = vx;
		for (int tick = 0; tick < ticks; tick++) {
			boolean ground = y <= 64;
			t.update(new Observation(tick, x, ground ? 64 : y, 0, ground, 64, false, held, HeldKind.NONE,
				ChestKind.ARMOR, 60));
			ThreatAssessment a = ThreatModel.evaluate(t, ME, List.of(), CFG, tick);
			if (a.level() == ThreatLevel.COMMIT && firstCommit[0] == null) {
				firstCommit[0] = a.level();
				commitTick[0] = tick;
				etaOut[0] = a.etaTicks();
			}
			x += vxx;
			y += vy;
			vy = (vy - 0.08) * 0.98;
			vxx *= 0.91;
		}
		return t;
	}

	@Test
	void maceDiveReachesCommitBeforeImpactWithinWindow() {
		double[] eta = {-1};
		ThreatLevel[] first = {null};
		int[] at = {-1};
		dive(HeldKind.MACE, 24, 5, 80, -0.6, eta, first, at);
		assertEquals(ThreatLevel.COMMIT, first[0], "mace dive must commit");
		assertTrue(eta[0] >= 0 && eta[0] <= CFG.commitWindowTicks + ME.rttTicks(), "eta in window: " + eta[0]);
		assertTrue(at[0] > 3, "not on the first ticks: " + at[0]);
	}

	@Test
	void sameArcWithoutMaceNeverCommits() {
		double[] eta = {-1};
		ThreatLevel[] first = {null};
		int[] at = {-1};
		dive(HeldKind.NONE, 24, 5, 80, -0.6, eta, first, at);
		assertEquals(null, first[0]);
	}

	@Test
	void standingMaceHolderIsWatchedAtMostArmedNotCommit() {
		PlayerTrack t = new PlayerTrack(1);
		for (int i = 0; i < 10; i++) {
			t.update(new Observation(i, 2, 64, 0, true, 64, false, HeldKind.MACE, HeldKind.NONE, ChestKind.ARMOR, 50));
		}
		ThreatAssessment a = ThreatModel.evaluate(t, ME, List.of(), CFG, 10);
		assertNotEquals(ThreatLevel.COMMIT, a.level());
		assertTrue(a.parts().containsKey("mace_in_hand"));
	}

	@Test
	void chestSwapMidAirAddsSiren() {
		PlayerTrack t = new PlayerTrack(2);
		t.update(new Observation(0, 10, 90, 0, false, 64, true, HeldKind.NONE, HeldKind.NONE, ChestKind.ELYTRA, 50));
		t.update(new Observation(1, 10, 89, 0, false, 64, false, HeldKind.MACE, HeldKind.NONE, ChestKind.ARMOR, 50));
		ThreatAssessment a = ThreatModel.evaluate(t, ME, List.of(), CFG, 1);
		assertTrue(a.parts().containsKey("chest_swap_siren"));
	}

	@Test
	void crystalsNearUsCommitWhenDamageIsMeaningful() {
		PlayerTrack t = new PlayerTrack(3);
		for (int i = 0; i < 5; i++) {
			t.update(new Observation(i, 6, 64, 0, true, 64, false, HeldKind.CRYSTAL, HeldKind.NONE, ChestKind.ARMOR, 40));
		}
		List<CrystalView> crystals = List.of(new CrystalView(1, 3, 64, 0, 0), new CrystalView(2, 4, 64, 1, 0));
		ThreatAssessment a = ThreatModel.evaluate(t, ME, crystals, CFG, 5);
		assertEquals(Archetype.CRYSTAL, a.archetype());
		assertEquals(ThreatLevel.COMMIT, a.level());
		assertTrue(a.crystalDamage() > CFG.crystalCommitDamage);
		assertEquals(0, a.etaTicks());
	}

	@Test
	void singleCrystalWithCrystalHolderCommits() {
		PlayerTrack t = new PlayerTrack(6);
		t.update(new Observation(0, 6, 64, 0, true, 64, false, HeldKind.CRYSTAL, HeldKind.NONE, ChestKind.ARMOR, 40));
		ThreatAssessment a = ThreatModel.evaluate(t, ME, List.of(new CrystalView(1, 3, 64, 0, 0)), CFG, 0);
		assertEquals(ThreatLevel.COMMIT, a.level());
	}

	@Test
	void strangerNearAStaleCrystalWithoutCrystalInHandOnlyWatches() {
		PlayerTrack t = new PlayerTrack(8);
		t.update(new Observation(0, 6, 64, 0, true, 64, false, HeldKind.NONE, HeldKind.NONE, ChestKind.ARMOR, 40));
		ThreatAssessment a = ThreatModel.evaluate(t, ME, List.of(new CrystalView(1, 3, 64, 0, 0)), CFG, 0);
		assertNotEquals(ThreatLevel.COMMIT, a.level());
	}

	@Test
	void farCrystalsDoNotCount() {
		PlayerTrack t = new PlayerTrack(4);
		t.update(new Observation(0, 40, 64, 0, true, 64, false, HeldKind.NONE, HeldKind.NONE, ChestKind.ARMOR, 40));
		ThreatAssessment a = ThreatModel.evaluate(t, ME, List.of(new CrystalView(1, 40, 64, 0, 0)), CFG, 0);
		assertEquals(0, a.crystalsNear());
		assertFalse(a.level().atLeast(ThreatLevel.WATCH));
	}

	@Test
	void glidingMaceHolderIsNotADive() {
		PlayerTrack t = new PlayerTrack(5);
		for (int i = 0; i < 6; i++) {
			t.update(new Observation(i, 10 - i, 90 - i, 0, false, 64, true, HeldKind.MACE, HeldKind.NONE, ChestKind.ELYTRA, 50));
		}
		ThreatAssessment a = ThreatModel.evaluate(t, ME, List.of(), CFG, 6);
		assertFalse(a.parts().containsKey("diving"));
		assertNotEquals(ThreatLevel.COMMIT, a.level());
	}

	@Test
	void maceDamageMathMatchesVanillaBands() {
		assertEquals(0, Damage.maceBonus(1.0, 0), 1e-9);
		assertEquals(4.0 * 1.5, Damage.maceBonus(1.5, 0), 1e-9);
		assertEquals(12.0, Damage.maceBonus(3.0, 0), 1e-9);
		assertEquals(12.0 + 2.0 * 5, Damage.maceBonus(8.0, 0), 1e-9);
		assertEquals(12.0 + 10.0 + 4.0, Damage.maceBonus(12.0, 0), 1e-9);
		assertEquals(Damage.maceBonus(10, 0) + 0.5 * 3 * 10, Damage.maceBonus(10, 3), 1e-9);
	}

	@Test
	void crystalDamageFallsOffWithDistance() {
		double near = Damage.crystalRaw(1.0, 1.0, 1.0);
		double mid = Damage.crystalRaw(6.0, 1.0, 1.0);
		assertTrue(near > mid && mid > 0);
		assertEquals(0, Damage.crystalRaw(12.0, 1.0, 1.0), 1e-9);
		assertEquals(Damage.crystalRaw(3.0, 1.0, 1.0) * 1.5, Damage.crystalRaw(3.0, 1.0, 1.5), 1e-9);
	}
}
