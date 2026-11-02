package dev.greencorps.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickBudgetTest {
	private long now;
	private double tps;

	@BeforeEach
	void reset() {
		now = 1_000_000;
		tps = 20;
		ClickBudget.resetForTest(() -> now, () -> tps);
	}

	@Test
	void clicksExpireAfterTheWindow() {
		for (int i = 0; i < 70; i++) ClickBudget.record();
		assertFalse(ClickBudget.canSend(1));
		now += 3_999;
		assertEquals(0, ClickBudget.remaining());
		now += 1;
		assertEquals(70, ClickBudget.remaining());
	}

	@Test
	void windowStretchesWithLowServerTps() {
		assertEquals(4_000, ClickBudget.windowMs());
		tps = 10;
		assertEquals(8_000, ClickBudget.windowMs());
		tps = 1;
		assertEquals(16_000, ClickBudget.windowMs());
		for (int i = 0; i < 70; i++) ClickBudget.record();
		tps = 10;
		now += 4_000;
		assertEquals(0, ClickBudget.remaining(), "an 80-tick window at 10 TPS lasts 8 s");
	}

	@Test
	void backOffHalvesAndRecovers() {
		ClickBudget.backOff();
		assertEquals(35, ClickBudget.limit());
		assertTrue(ClickBudget.backedOff());
		ClickBudget.backOff();
		ClickBudget.backOff();
		assertEquals(ClickBudget.MIN_LIMIT, ClickBudget.limit());
		now += 30_000;
		assertEquals(25, ClickBudget.limit());
		now += 4_000;
		assertEquals(30, ClickBudget.limit());
		now += 4_000 * 10;
		for (int i = 0; i < 10; i++) {
			ClickBudget.limit();
			now += 4_000;
		}
		for (int i = 0; i < 10; i++) {
			ClickBudget.limit();
			now += 4_000;
		}
		assertEquals(70, ClickBudget.limit());
		assertFalse(ClickBudget.backedOff());
	}

	@Test
	void etaCountsWholeWindows() {
		assertEquals(0, ClickBudget.etaTicks(70));
		for (int i = 0; i < 70; i++) ClickBudget.record();
		assertEquals(80, ClickBudget.etaTicks(1));
		assertEquals(160, ClickBudget.etaTicks(71));
	}
}
