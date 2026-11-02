package dev.greencorps.stasis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PearlSettleTrackerTest {
	private static PearlSettleTracker.Sample sample(UUID id, double x, double y, double z,
													String ownerUuid, String ownerIgn) {
		return new PearlSettleTracker.Sample(
			id, x, y, z, "minecraft:overworld", ownerUuid, ownerIgn,
			true, StasisGeometry.Match.WATER);
	}

	@Test
	void settlesWhenStationaryWithGeometry() {
		PearlSettleTracker tracker = new PearlSettleTracker(1.5, 5);
		UUID id = UUID.randomUUID();
		PearlSettleTracker.Settled settled = null;
		for (int i = 0; i < 5; i++) {
			settled = tracker.observe(sample(id, 10.2, 64.1, 20.3, null, null));
			tracker.endTick(List.of(id));
		}
		assertNotNull(settled);
		assertEquals(id, settled.entityId);
		assertEquals(10, settled.blockX());
		assertTrue(tracker.isAnnounced(id));

		PearlSettleTracker.Settled again = tracker.observe(sample(id, 10.2, 64.1, 20.3, null, null));
		assertNull(again);
	}

	@Test
	void dropsWhenGeometryFails() {
		PearlSettleTracker tracker = new PearlSettleTracker(1.5, 5);
		UUID id = UUID.randomUUID();
		tracker.observe(sample(id, 1, 64, 1, null, null));
		assertEquals(1, tracker.trackedCount());
		tracker.observe(new PearlSettleTracker.Sample(
			id, 1, 64, 1, "minecraft:overworld", null, null, false, StasisGeometry.Match.NONE));
		assertEquals(0, tracker.trackedCount());
	}

	@Test
	void resetsWhenLeavingBox() {
		PearlSettleTracker tracker = new PearlSettleTracker(1.5, 3);
		UUID id = UUID.randomUUID();
		for (int i = 0; i < 2; i++) {
			assertNull(tracker.observe(sample(id, 0, 64, 0, null, null)));
			tracker.endTick(List.of(id));
		}
		assertNull(tracker.observe(sample(id, 20, 64, 0, null, null)));
		tracker.endTick(List.of(id));
		assertFalse(tracker.isAnnounced(id));
		PearlSettleTracker.Settled settled = null;
		for (int i = 0; i < 5; i++) {
			PearlSettleTracker.Settled s = tracker.observe(sample(id, 20.1, 64.0, 0.1, "u", "Name"));
			tracker.endTick(List.of(id));
			if (s != null) settled = s;
		}
		assertNotNull(settled);
		assertEquals("Name", settled.ownerIgn);
	}

	@Test
	void dropsAfterMissing() {
		PearlSettleTracker tracker = new PearlSettleTracker(1.5, 3, 2);
		UUID id = UUID.randomUUID();
		tracker.observe(sample(id, 1, 1, 1, null, null));
		tracker.endTick(List.of(id));
		assertEquals(1, tracker.trackedCount());
		tracker.endTick(List.of());
		assertEquals(1, tracker.trackedCount());
		tracker.endTick(List.of());
		assertEquals(0, tracker.trackedCount());
	}

	@Test
	void bubbleBobStaysInsideBox() {
		PearlSettleTracker tracker = new PearlSettleTracker(1.5, 6);
		UUID id = UUID.randomUUID();
		PearlSettleTracker.Settled settled = null;
		double[] ys = {64.0, 64.4, 64.8, 65.0, 64.6, 64.2};
		for (double y : ys) {
			settled = tracker.observe(sample(id, 5.0, y, 5.0, null, null));
			tracker.endTick(List.of(id));
		}
		assertNotNull(settled);
	}
}
