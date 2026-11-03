package dev.greencorps.anticlient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScorecardTest {
	@Test
	void commitThenMatchingHitIsTruePositiveWithLead() {
		Scorecard s = new Scorecard();
		assertTrue(s.openCommit(5, Archetype.MACE, 100, 8, 80));
		Scorecard.Resolved r = s.onHit(Archetype.MACE, 110, 5);
		assertNotNull(r);
		assertEquals(10, r.leadTicks());
		assertEquals(2, r.etaErrorTicks());
		assertEquals(1, s.tp());
		assertEquals(0, s.openCount());
	}

	@Test
	void hitWithoutCommitIsMiss() {
		Scorecard s = new Scorecard();
		assertNull(s.onHit(Archetype.CRYSTAL, 50, 3));
		assertEquals(1, s.miss(Archetype.CRYSTAL));
		assertEquals(0.0, s.recall(), 1e-9);
	}

	@Test
	void staleCommitExpiresAsFalsePositive() {
		Scorecard s = new Scorecard();
		s.openCommit(1, Archetype.MACE, 0, 10, 80);
		assertTrue(s.tick(10 + Scorecard.EXPIRE_SLACK_TICKS).isEmpty());
		List<Scorecard.Resolved> fp = s.tick(10 + Scorecard.EXPIRE_SLACK_TICKS + 1);
		assertEquals(1, fp.size());
		assertFalse(fp.get(0).hit());
		assertEquals(1, s.fp(Archetype.MACE));
	}

	@Test
	void archetypeMustMatch() {
		Scorecard s = new Scorecard();
		s.openCommit(1, Archetype.MACE, 0, 5, 80);
		assertNull(s.onHit(Archetype.CRYSTAL, 3, 1));
		assertEquals(1, s.openCount());
	}

	@Test
	void precisionAndRecall() {
		Scorecard s = new Scorecard();
		s.openCommit(1, Archetype.MACE, 0, 5, 80);
		s.onHit(Archetype.MACE, 5, 1);
		s.openCommit(2, Archetype.MACE, 100, 5, 80);
		s.tick(200);
		s.onHit(Archetype.MACE, 300, 9);
		assertEquals(0.5, s.precision(), 1e-9);
		assertEquals(0.5, s.recall(), 1e-9);
	}

	@Test
	void duplicateOpenCommitIgnored() {
		Scorecard s = new Scorecard();
		assertTrue(s.openCommit(1, Archetype.MACE, 0, 5, 80));
		assertFalse(s.openCommit(1, Archetype.MACE, 1, 5, 90));
	}
}
