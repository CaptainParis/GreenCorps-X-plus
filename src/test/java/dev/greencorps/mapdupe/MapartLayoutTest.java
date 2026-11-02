package dev.greencorps.mapdupe;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapartLayoutTest {
	private static final int S = MapartLayout.SIZE;
	/** 64 map-like colours (bytes 4..255 in use, like the game's palette; 0..3 transparent). */
	private static final int[] PALETTE = new int[256];
	private static final MapartLayout.Palette PAL = b -> PALETTE[b & 0xFF];

	static {
		Random r = new Random(1);
		for (int i = 4; i < 256; i++) PALETTE[i] = r.nextInt(0x1000000);
	}

	// ------------------------------------------------------------- names --

	@Test
	void parsesPositionsAnywhereInTheName() {
		pos("2,3", MapartNames.Kind.PAIR, 2, 3);
		pos("(2-3)", MapartNames.Kind.PAIR, 2, 3);
		pos("r2c3", MapartNames.Kind.ROW_COL, 2, 3);
		pos("Row 2 Col 3", MapartNames.Kind.ROW_COL, 2, 3);
		pos("x3 y2", MapartNames.Kind.COL_ROW, 3, 2);
		pos("A3", MapartNames.Kind.LETTER_NUM, 1, 3);
		pos("4/12", MapartNames.Kind.OF, 4, 12);
		pos("#7", MapartNames.Kind.INDEX, 7, 0);
		assertEquals(MapartNames.Kind.WORDS, MapartNames.parse("Top Left").kind());
		assertEquals("TL", MapartNames.parse("Top Left").words());
		assertEquals("M", MapartNames.parse("Center").words());
		assertNull(MapartNames.parse("Castle"));
	}

	private static void pos(String middle, MapartNames.Kind kind, int a, int b) {
		MapartNames.Pos p = MapartNames.parse(middle);
		assertNotNull(p, middle);
		assertEquals(kind, p.kind(), middle);
		assertEquals(a, p.a(), middle);
		assertEquals(b, p.b(), middle);
	}

	@Test
	void groupsSeparateArtworksAndIgnoreCredits() {
		List<String> names = List.of("Castle 1,1 by Bob", "Castle 1,2 by Bob", "Bridge 1,1 by Bob", "Bridge 1,2 by Bob", "Castle 2,1 by Bob");
		List<Integer> loose = new ArrayList<>();
		List<MapartNames.NameGroup> groups = MapartNames.groups(names, loose);
		assertEquals(2, groups.size());
		assertTrue(loose.isEmpty());
		assertEquals(List.of(0, 1, 4), groups.get(0).tiles());
		assertEquals(List.of(2, 3), groups.get(1).tiles());
	}

	// ----------------------------------------------------- names + edges --

	@Test
	void namedWithCreditSuffix() {
		Art art = Art.dense(4, 3, 11);
		assertExact(art, sortOf(art, (r, c) -> "Castle " + (r + 1) + "," + (c + 1) + " by Bob"));
	}

	@Test
	void columnRowNamesAreReadThatWay() {
		Art art = Art.dense(4, 3, 12);
		assertExact(art, sortOf(art, (r, c) -> "Castle (" + (c + 1) + "-" + (r + 1) + ")"));
	}

	@Test
	void letterRowsAndColumns() {
		Art art = Art.dense(4, 3, 13);
		assertExact(art, sortOf(art, (r, c) -> "Sky " + (char) ('A' + r) + (c + 1)));
		Art tall = Art.dense(2, 4, 14);
		assertExact(tall, sortOf(tall, (r, c) -> "Sky " + (char) ('A' + c) + (r + 1)));
	}

	@Test
	void indexOfTotalPicksTheRowWidthFromEdges() {
		Art art = Art.dense(4, 3, 15);
		assertExact(art, sortOf(art, (r, c) -> "Flag (" + (r * 4 + c + 1) + "/12) v2"));
	}

	@Test
	void wordPositions() {
		Art art = Art.dense(3, 3, 16);
		String[] rows = { "Top", "Middle", "Bottom" };
		String[] cols = { "Left", "Center", "Right" };
		assertExact(art, sortOf(art, (r, c) -> "Logo " + rows[r] + " " + cols[c]));
	}

	// ------------------------------------------------------- edges only --

	@Test
	void unnamedDenseArt() {
		Art art = Art.dense(4, 3, 21);
		assertExact(art, sortOf(art, null));
	}

	@Test
	void unnamedLineArtOnFlatBackground() {
		Art art = Art.lineArt(3, 3, 22);
		assertExact(art, sortOf(art, null));
	}

	@Test
	void twoUnnamedArtsComeOutSeparately() {
		Art a = Art.dense(3, 2, 31);
		Art b = Art.dense(2, 2, 32).offsetKeys(100);
		List<MapartLayout.Tile> tiles = new ArrayList<>(a.tiles(null));
		tiles.addAll(b.tiles(null));
		Collections.shuffle(tiles, new Random(5));
		List<MapartLayout.Group> groups = MapartLayout.arrange(tiles, PAL);
		assertExact(a, groups);
		assertExact(b, groups);
	}

	@Test
	void incompleteArtKeepsItsShape() {
		Art art = Art.dense(4, 3, 41);
		List<MapartLayout.Tile> tiles = new ArrayList<>(art.tiles(null));
		tiles.removeIf(t -> t.key() == 5); // a missing middle tile
		Collections.shuffle(tiles, new Random(9));
		assertExact(art, MapartLayout.arrange(tiles, PAL));
	}

	// ------------------------------------------------------------- dumps --

	@Test
	void dumpRoundTrips() throws Exception {
		Art art = Art.dense(2, 2, 51);
		List<MapartLayout.Tile> tiles = art.tiles((r, c) -> "Dump " + (r + 1) + "," + (c + 1));
		List<MapartLayout.Group> groups = MapartLayout.arrange(tiles, PAL);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		MapSortDump.write(out, tiles, groups, PAL);
		MapSortDump.Fixture f = MapSortDump.read(new ByteArrayInputStream(out.toByteArray()));
		assertEquals(4, f.tiles().size());
		assertEquals("Dump 1,2", f.tiles().get(1).name());
		assertEquals(PALETTE[200], f.palette()[200]);
		assertTrue(java.util.Arrays.equals(tiles.get(3).colors(), f.tiles().get(3).colors()));
	}

	/** Real sorts dumped in game (debug-log) with hand-written expect lines. */
	@Test
	void recordedFixtures() throws Exception {
		Path dir = Path.of("src/test/resources/mapsort");
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".gz")).toList()) {
				MapSortDump.Fixture f;
				try (InputStream in = Files.newInputStream(file)) {
					f = MapSortDump.read(in);
				}
				List<MapartLayout.Group> got = MapartLayout.arrange(f.tiles(), b -> f.palette()[b & 0xFF]);
				for (MapartLayout.Group want : f.expected()) {
					assertTrue(got.stream().anyMatch(g -> sameLayout(want, g)), file + ": " + MapSortDump.groupLine("expect", want)
						+ " not found in " + got.stream().map(g -> MapSortDump.groupLine("got", g)).toList());
				}
			}
		}
	}

	private static boolean sameLayout(MapartLayout.Group want, MapartLayout.Group got) {
		Map<Long, int[]> w = positions(want), g = positions(got);
		if (!w.keySet().equals(g.keySet())) return false;
		int[] off = null;
		for (Map.Entry<Long, int[]> e : w.entrySet()) {
			int[] p = g.get(e.getKey());
			int[] d = { p[0] - e.getValue()[0], p[1] - e.getValue()[1] };
			if (off == null) off = d;
			else if (off[0] != d[0] || off[1] != d[1]) return false;
		}
		return true;
	}

	private static Map<Long, int[]> positions(MapartLayout.Group g) {
		Map<Long, int[]> out = new HashMap<>();
		for (int i = 0; i < g.cells().size(); i++) {
			Long k = g.cells().get(i);
			if (k != null) out.put(k, new int[] { i / g.width(), i % g.width() });
		}
		return out;
	}

	// ----------------------------------------------------------- helpers --

	interface Namer {
		String name(int r, int c);
	}

	private static List<MapartLayout.Group> sortOf(Art art, Namer namer) {
		List<MapartLayout.Tile> tiles = new ArrayList<>(art.tiles(namer));
		Collections.shuffle(tiles, new Random(art.seed));
		return MapartLayout.arrange(tiles, PAL);
	}

	/** The group holding the art's tiles must place every one of them where it belongs (up to a shift). */
	private static void assertExact(Art art, List<MapartLayout.Group> groups) {
		MapartLayout.Group g = groups.stream().filter(x -> x.order().contains(art.key(0, 0)) || x.order().contains(art.key(0, 1)))
			.findFirst().orElseThrow();
		Map<Long, int[]> got = positions(g);
		int[] off = null;
		StringBuilder layout = new StringBuilder();
		for (int i = 0; i < g.cells().size(); i++) {
			if (i % g.width() == 0) layout.append('\n');
			layout.append(g.cells().get(i) == null ? "  _" : String.format("%3d", g.cells().get(i))).append(' ');
		}
		String msg = g.method() + " " + g.width() + "x" + g.height() + layout;
		for (int r = 0; r < art.h; r++) {
			for (int c = 0; c < art.w; c++) {
				int[] p = got.get(art.key(r, c));
				if (p == null) continue; // removed on purpose
				int[] d = { p[0] - r, p[1] - c };
				if (off == null) off = d;
				assertEquals(off[0], d[0], msg);
				assertEquals(off[1], d[1], msg);
			}
		}
	}

	/** A synthetic mapart: w×h tiles of palette bytes, key = r * w + c (+ offset). */
	static final class Art {
		final int w, h, seed;
		final byte[][] img;
		long keyOffset;

		private Art(int w, int h, int seed) {
			this.w = w;
			this.h = h;
			this.seed = seed;
			this.img = new byte[h * S][w * S];
		}

		Art offsetKeys(long off) {
			keyOffset = off;
			return this;
		}

		long key(int r, int c) {
			return keyOffset + r * w + c;
		}

		/** Busy picture: dithered gradient background, many shapes and lines across the seams. */
		static Art dense(int w, int h, int seed) {
			Art a = new Art(w, h, seed);
			Random rnd = new Random(seed);
			int c1 = 4 + rnd.nextInt(252), c2 = 4 + rnd.nextInt(252);
			for (int y = 0; y < a.img.length; y++) {
				for (int x = 0; x < a.img[0].length; x++) {
					double t = (x + y * 0.7) / (a.img[0].length + a.img.length * 0.7);
					int bayer = ((x & 1) * 2 + (y & 1)) ;
					a.img[y][x] = (byte) (t * 4 > bayer + (x * 7 + y * 3) % 2 ? c1 : c2);
				}
			}
			a.shapes(rnd, 10 * w * h, 6, 40);
			a.lines(rnd, 4 * w * h);
			return a;
		}

		/** Mostly flat white with dark lines and a few blobs (sketch-style mapart). */
		static Art lineArt(int w, int h, int seed) {
			Art a = new Art(w, h, seed);
			Random rnd = new Random(seed);
			byte white = (byte) 34;
			for (byte[] row : a.img) java.util.Arrays.fill(row, white);
			a.lines(rnd, 6 * w * h);
			a.shapes(rnd, 2 * w * h, 8, 30);
			return a;
		}

		private void shapes(Random rnd, int count, int minR, int maxR) {
			int H = img.length, W = img[0].length;
			for (int i = 0; i < count; i++) {
				int cx = rnd.nextInt(W), cy = rnd.nextInt(H), r = minR + rnd.nextInt(maxR - minR);
				byte col = (byte) (4 + rnd.nextInt(252));
				boolean square = rnd.nextBoolean();
				for (int y = Math.max(0, cy - r); y < Math.min(H, cy + r); y++) {
					for (int x = Math.max(0, cx - r); x < Math.min(W, cx + r); x++) {
						if (square || (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r) img[y][x] = col;
					}
				}
			}
		}

		private void lines(Random rnd, int count) {
			int H = img.length, W = img[0].length;
			for (int i = 0; i < count; i++) {
				double x = rnd.nextInt(W), y = rnd.nextInt(H);
				double ang = rnd.nextDouble() * Math.PI * 2;
				int len = 60 + rnd.nextInt(200);
				byte col = (byte) (4 + rnd.nextInt(252));
				for (int k = 0; k < len; k++) {
					int ix = (int) x, iy = (int) y;
					for (int t = -1; t <= 1; t++) {
						if (iy + t >= 0 && iy + t < H && ix >= 0 && ix < W) img[iy + t][ix] = col;
					}
					x += Math.cos(ang);
					y += Math.sin(ang);
				}
			}
		}

		List<MapartLayout.Tile> tiles(Namer namer) {
			List<MapartLayout.Tile> out = new ArrayList<>();
			for (int r = 0; r < h; r++) {
				for (int c = 0; c < w; c++) {
					byte[] colors = new byte[S * S];
					for (int y = 0; y < S; y++) System.arraycopy(img[r * S + y], c * S, colors, y * S, S);
					out.add(new MapartLayout.Tile(key(r, c), namer == null ? null : namer.name(r, c), colors));
				}
			}
			return out;
		}
	}
}
