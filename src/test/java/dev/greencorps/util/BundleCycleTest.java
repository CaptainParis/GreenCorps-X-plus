package dev.greencorps.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleCycleTest {
	@Test
	void fullCycleKeepsOrderAndVisitsEachStackOnce() {
		for (int n = 0; n <= 12; n++) {
			List<Integer> bundle = new ArrayList<>();
			for (int i = 0; i < n; i++) bundle.add(i);
			List<Integer> visited = new ArrayList<>();
			BundleCycle cycle = new BundleCycle(n);
			while (!cycle.done()) {
				int taken = bundle.remove(cycle.takeIndex());
				assertEquals(cycle.originalIndex(), taken);
				visited.add(taken);
				bundle.add(0, taken); // vanilla bundles insert at the front
				cycle.roundDone();
			}
			List<Integer> original = new ArrayList<>();
			for (int i = 0; i < n; i++) original.add(i);
			assertEquals(original, bundle, "order after the cycle, n=" + n);
			assertEquals(n, visited.size());
			assertTrue(visited.containsAll(original));
		}
	}

	/**
	 * 2b2t: a stack put back shows up at the end. Map Slot Preview then takes the first stack each round (after a
	 * first round that took the last one and changed nothing), which also keeps the order.
	 */
	@Test
	void putBackAtTheEndTakingTheFirstKeepsOrder() {
		for (int n = 2; n <= 12; n++) {
			List<Integer> bundle = new ArrayList<>();
			for (int i = 0; i < n; i++) bundle.add(i);
			List<Integer> original = new ArrayList<>(bundle);
			// First round as for a vanilla server: take the last, it goes back to the end.
			BundleCycle first = new BundleCycle(n);
			bundle.add(bundle.remove(first.takeIndex()));
			assertEquals(original, bundle);
			BundleCycle cycle = new BundleCycle(n);
			while (!cycle.done()) {
				int taken = bundle.remove(0);
				assertEquals(original.get(cycle.round()), taken);
				bundle.add(taken);
				cycle.roundDone();
			}
			assertEquals(original, bundle, "order after the cycle, n=" + n);
		}
	}
}
