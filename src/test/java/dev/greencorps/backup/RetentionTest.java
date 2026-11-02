package dev.greencorps.backup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetentionTest {
	@Test
	void keepsPinnedAndRecent() {
		long now = 1_700_000_000_000L;
		List<SnapshotIndex.Entry> entries = new ArrayList<>();
		// Recent (within 24h)
		entries.add(entry(now - 1000, false));
		// Old unpinned (should prune under monthly forever only if many)
		SnapshotIndex.Entry pinned = entry(now - Retention.D * 400, true);
		entries.add(pinned);
		entries.add(entry(now - Retention.D * 400 - 1000, false));

		Retention.Plan plan = Retention.plan(entries, now);
		assertTrue(plan.keep().stream().anyMatch(e -> e.pinned));
		assertTrue(plan.keep().stream().anyMatch(e -> e.epochMs == now - 1000));
	}

	private static SnapshotIndex.Entry entry(long epoch, boolean pinned) {
		SnapshotIndex.Entry e = new SnapshotIndex.Entry();
		e.epochMs = epoch;
		e.filename = epoch + ".gcsnap";
		e.pinned = pinned;
		return e;
	}
}
