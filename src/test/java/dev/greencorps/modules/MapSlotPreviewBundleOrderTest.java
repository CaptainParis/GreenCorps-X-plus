package dev.greencorps.modules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapSlotPreviewBundleOrderTest {
	private static List<Long> keys(int n) {
		List<Long> out = new ArrayList<>();
		for (long i = 0; i < n; i++) out.add(100 + i);
		return out;
	}

	@Test
	void expectedOrderMatchesPutBackAtTheFront() {
		for (int n = 1; n <= 10; n++) {
			List<Long> order = keys(n);
			List<Long> bundle = new ArrayList<>(order);
			for (int round = 1; round <= n; round++) {
				bundle.add(0, bundle.remove(n - 1)); // take the last, it goes back to the front
				assertEquals(MapSlotPreview.rotated(order, false, round), bundle, "n=" + n + " round " + round);
			}
			assertEquals(order, bundle);
		}
	}

	@Test
	void expectedOrderMatchesPutBackAtTheEnd() {
		for (int n = 1; n <= 10; n++) {
			List<Long> order = keys(n);
			List<Long> bundle = new ArrayList<>(order);
			for (int round = 1; round <= n; round++) {
				bundle.add(bundle.remove(0)); // 2b2t: take the first, it shows up at the end
				assertEquals(MapSlotPreview.rotated(order, true, round), bundle, "n=" + n + " round " + round);
			}
			assertEquals(order, bundle);
		}
	}
}
