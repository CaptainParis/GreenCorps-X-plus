package dev.greencorps.anticlient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SurvivalModelTest {
	private static final SurvivalConfig CFG = new SurvivalConfig();

	private static SurvivalInput in(double hp, boolean offhand, int inv, boolean auto, int refill,
									Archetype a, double burst, ThreatLevel level, int eta) {
		return new SurvivalInput(hp, 0, offhand, inv, auto, refill, 2, level, a, eta, burst);
	}

	@Test
	void plentyOfTotemsAgainstMaceIsOk() {
		SurvivalVerdict v = SurvivalModel.evaluate(
			in(20, true, 8, true, 6, Archetype.MACE, 12, ThreatLevel.COMMIT, 5), CFG);
		assertEquals(SurvivalVerdict.Kind.OK, v.kind());
		assertEquals(9, v.lives());
	}

	@Test
	void noTotemsAndHeavyBurstIsDoomed() {
		SurvivalVerdict v = SurvivalModel.evaluate(
			in(20, false, 0, false, 6, Archetype.CRYSTAL, 30, ThreatLevel.COMMIT, 0), CFG);
		assertTrue(v.doomed());
		assertTrue(v.flags().contains("NO_TOTEMS"));
		assertTrue(v.marginTicks() < 0);
	}

	@Test
	void sparesDoNotCountWhenRefillIsSlowerThanBursts() {
		SurvivalConfig cfg = new SurvivalConfig();
		cfg.neutralizeTicks = 30;
		SurvivalVerdict slow = SurvivalModel.evaluate(
			in(20, true, 10, true, 20, Archetype.CRYSTAL, 30, ThreatLevel.COMMIT, 0), cfg);
		SurvivalVerdict fast = SurvivalModel.evaluate(
			in(20, true, 10, true, 1, Archetype.CRYSTAL, 30, ThreatLevel.COMMIT, 0), cfg);
		assertEquals(1, slow.lives());
		assertEquals(11, fast.lives());
		assertTrue(slow.flags().contains("REFILL_SLOWER_THAN_BURSTS"));
		assertTrue(slow.doomed());
		assertTrue(!fast.doomed());
	}

	@Test
	void autoTotemOffIsFlaggedAndSparesIgnored() {
		SurvivalVerdict v = SurvivalModel.evaluate(
			in(20, true, 6, false, 6, Archetype.CRYSTAL, 30, ThreatLevel.COMMIT, 0), CFG);
		assertTrue(v.flags().contains("AUTOTOTEM_OFF_WITH_SPARES"));
		assertEquals(1, v.lives());
	}

	@Test
	void idleNeverDoomedEvenWithoutTotems() {
		SurvivalVerdict v = SurvivalModel.evaluate(
			in(20, false, 0, false, 6, Archetype.NONE, 0, ThreatLevel.IDLE, -1), CFG);
		assertTrue(!v.doomed());
		assertEquals(SurvivalVerdict.Kind.DEGRADED, v.kind());
	}

	@Test
	void burstsToDeathFormulaMatchesHandCalc() {
		// pool 20, burst 30 -> bursts0=1; one life, post-pop 9 hp -> 1 more burst; total 2
		SurvivalVerdict v = SurvivalModel.evaluate(
			in(20, true, 0, true, 1, Archetype.CRYSTAL, 30, ThreatLevel.ARMED, 0), CFG);
		assertEquals(2, v.burstsToDeath());
		assertEquals(CFG.crystalIntervalTicks, v.timeToDeathTicks());
		// burst 4 -> bursts0=5, per life ceil(9/4)=3 -> 8
		SurvivalVerdict w = SurvivalModel.evaluate(
			in(20, true, 0, true, 1, Archetype.MACE, 4, ThreatLevel.ARMED, 0), CFG);
		assertEquals(8, w.burstsToDeath());
	}

	@Test
	void absorptionCountsTowardPool() {
		SurvivalInput a = new SurvivalInput(10, 8, true, 0, true, 1, 2, ThreatLevel.ARMED, Archetype.MACE, 0, 6);
		SurvivalInput b = new SurvivalInput(10, 0, true, 0, true, 1, 2, ThreatLevel.ARMED, Archetype.MACE, 0, 6);
		assertTrue(SurvivalModel.evaluate(a, CFG).burstsToDeath() > SurvivalModel.evaluate(b, CFG).burstsToDeath());
	}
}
