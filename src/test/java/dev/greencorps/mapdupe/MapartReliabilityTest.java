package dev.greencorps.mapdupe;

import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Sorting reliability over many random synthetic arts (40 seeds per case): guards against regressions in
 * {@link MapartLayout}. Misses are printed; each case must stay at or above its floor. Strips with a missing
 * tile are inherently ambiguous from edges alone, hence their lower floors.
 */
class MapartReliabilityTest {
	static final int[] PAL = new int[256];
	static { Random r = new Random(1); for (int i = 4; i < 256; i++) PAL[i] = r.nextInt(0x1000000); }

	static boolean exact(MapartLayoutTest.Art art, List<MapartLayout.Group> groups, Set<Long> removed) {
		for (MapartLayout.Group g : groups) {
			Map<Long, int[]> pos = new HashMap<>();
			for (int i = 0; i < g.cells().size(); i++) if (g.cells().get(i) != null) pos.put(g.cells().get(i), new int[] { i / g.width(), i % g.width() });
			if (!pos.containsKey(art.key(0, 0)) && !pos.containsKey(art.key(0, 1))) continue;
			int[] off = null;
			for (int r = 0; r < art.h; r++) for (int c = 0; c < art.w; c++) {
				if (removed.contains(art.key(r, c))) continue;
				int[] p = pos.get(art.key(r, c));
				if (p == null) return false;
				int[] d = { p[0] - r, p[1] - c };
				if (off == null) off = d; else if (off[0] != d[0] || off[1] != d[1]) return false;
			}
			return true;
		}
		return false;
	}

	@Test
	void reliability() {
		String[] kinds = { "dense", "line", "dense-holes", "line-holes", "named-pair-colrow", "named-index", "two-arts" };
		Map<String, Integer> floor = Map.of("dense", 37, "line", 36, "dense-holes", 32, "line-holes", 29,
			"named-pair-colrow", 40, "named-index", 40, "two-arts", 29);
		for (String kind : kinds) {
			int ok = 0, total = 0; long t0 = System.nanoTime(); long worst = 0;
			for (int seed = 100; seed < 140; seed++) {
				Random rnd = new Random(seed);
				int w = 2 + rnd.nextInt(5), h = 1 + rnd.nextInt(3);
				if (w * h < 3) w++;
				boolean line = kind.startsWith("line");
				MapartLayoutTest.Art art = line ? MapartLayoutTest.Art.lineArt(w, h, seed) : MapartLayoutTest.Art.dense(w, h, seed);
				final int W = w;
				List<MapartLayout.Tile> tiles = new ArrayList<>(switch (kind) {
					case "named-pair-colrow" -> art.tiles((r, c) -> "Art " + (c + 1) + "," + (r + 1) + " by X");
					case "named-index" -> art.tiles((r, c) -> "Art #" + (r * W + c + 1));
					default -> art.tiles(null);
				});
				Set<Long> removed = new HashSet<>();
				if (kind.endsWith("holes") && tiles.size() >= 6) { removed.add(tiles.get(tiles.size() / 2).key()); tiles.removeIf(t -> removed.contains(t.key())); }
				MapartLayoutTest.Art other = null;
				if (kind.equals("two-arts")) { other = MapartLayoutTest.Art.dense(2 + rnd.nextInt(3), 2, seed + 1000).offsetKeys(500); tiles.addAll(other.tiles(null)); }
				Collections.shuffle(tiles, new Random(seed));
				long s = System.nanoTime();
				List<MapartLayout.Group> groups = MapartLayout.arrange(tiles, b -> PAL[b & 0xFF]);
				worst = Math.max(worst, System.nanoTime() - s);
				boolean good = exact(art, groups, removed) && (other == null || exact(other, groups, Set.of()));
				total++; if (good) ok++;
				else System.out.println("  miss " + kind + " seed " + seed + " " + w + "x" + h + " -> " + groups.stream().map(g -> g.width() + "x" + g.height() + " " + g.method()).toList());
			}
			System.out.printf("RESULT %-18s %d/%d  avg %.1f ms  worst %.1f ms%n", kind, ok, total, (System.nanoTime() - t0) / 1e6 / total, worst / 1e6);
			org.junit.jupiter.api.Assertions.assertTrue(ok >= floor.get(kind), kind + ": " + ok + "/" + total + " below " + floor.get(kind));
			org.junit.jupiter.api.Assertions.assertTrue(worst < 500_000_000L, kind + " took " + worst / 1e6 + " ms");
		}
	}
}
