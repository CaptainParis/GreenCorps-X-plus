package dev.greencorps.mapdupe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out how the maps of each mapart fit together, for Map Duper+ <i>Sort Maps</i> and <i>Bundle Sort</i>.
 * <p>
 * <b>Names</b> ({@link MapartNames}): tiles whose names share a prefix and suffix are one artwork and the
 * middle is the position. Where a name can be read more than one way (row,column or column,row; which axis a
 * letter is; how wide an index runs), the edges pick the reading.
 * <p>
 * <b>Edges</b>: neighbouring tiles continue each other across the seam. A seam is scored on the map's palette
 * bytes the way EV mod does (same colour across, or one pixel up / down, with a bonus where a colour change
 * carries on across the seam, so shapes count far more than flat background), plus how well the second pixel
 * ring predicts the neighbour's first ring. A layout is judged by its seams' <i>gain</i>: how much better each
 * seam is than those edges' typical (median) match, summed. Real seams add, random ones add about nothing, so
 * the true shape wins over strips and holey grids without any averaging bias. Placement uses confidences
 * relative to each edge's second-best match, so an edge that fits everything (flat white) counts for little.
 * Mutual best matches ("best buddies") split loose maps into artworks; each is placed confident pairs first
 * inside a W×H frame, then improved by swapping tiles, rows and columns while the gain grows. Every frame size
 * that fits the count (a few holes allowed) is tried and the best kept.
 */
public final class MapartLayout {
	public static final int SIZE = 128;
	/** Names are kept, but flagged, when edges alone find this much more gain per seam. */
	private static final double DISAGREE = 0.08;
	/** Tie-break per tile for layouts at least as wide as tall (map walls rarely are one tall column). */
	private static final double LANDSCAPE = 0.02;
	/** Clusters only join along seams (and with joins) at least this much better than a random match. */
	private static final double JOIN = 0.06;
	/** Seams that build the first pure pieces must be this good (pieces are rejoined later, never split). */
	private static final double PIECE = 0.2;
	/** Pieces whose colour histograms overlap this much are one artwork even without telling seams (flat art). */
	private static final double SAME_PALETTE = 0.5;

	/** Map colour byte (0-255) → RGB. */
	public interface Palette {
		int rgb(int colorByte);
	}

	/** One distinct map: its key, custom name (or null) and its 128×128 colour bytes (or null if unknown). */
	public record Tile(long key, String name, byte[] colors) {
		public boolean hasColors() {
			return colors != null && colors.length >= SIZE * SIZE;
		}
	}

	/** One artwork: {@code cells} row by row, {@code width} per row; null cells are holes (missing tiles). */
	public record Group(String title, List<Long> cells, int width, int height, String method) {
		/** Keys in reading order, holes skipped. */
		public List<Long> order() {
			List<Long> out = new ArrayList<>();
			for (Long k : cells) if (k != null) out.add(k);
			return out;
		}
	}

	private record Grid(int w, int h, int[] cells) {}

	private MapartLayout() {}

	/** Groups in display order: named artworks by title, then artworks put together from edges, then the rest. */
	public static List<Group> arrange(List<Tile> tiles, Palette palette) {
		Seams seams = new Seams(tiles, palette);
		List<String> names = new ArrayList<>();
		for (Tile t : tiles) names.add(t.name());
		List<Integer> loose = new ArrayList<>();
		List<MapartNames.NameGroup> nameGroups = MapartNames.groups(names, loose);

		List<Group> named = new ArrayList<>();
		List<Object[]> namedGrids = new ArrayList<>();
		for (MapartNames.NameGroup ng : nameGroups) {
			Object[] res = fromNames(seams, ng);
			if (res == null) {
				loose.addAll(ng.tiles());
				continue;
			}
			namedGrids.add(new Object[] { ng.title(), res[0], res[1] });
		}
		loose.sort(Integer::compare);
		for (Object[] ng : namedGrids) {
			Grid g = (Grid) ng[1];
			String method = (String) ng[2];
			if (fillHoles(seams, g, loose)) method += " + edges";
			named.add(toGroup(tiles, (String) ng[0], g, method));
		}
		named.sort(Comparator.comparing(Group::title, String.CASE_INSENSITIVE_ORDER));
		List<Group> out = new ArrayList<>(named);

		List<Integer> colored = new ArrayList<>();
		List<Integer> rest = new ArrayList<>();
		for (int i : loose) (seams.has[i] ? colored : rest).add(i);
		List<Integer> singles = new ArrayList<>();
		for (List<Integer> part : artworks(seams, colored)) {
			if (part.size() < 2) {
				singles.addAll(part);
				continue;
			}
			out.add(toGroup(tiles, "", assemble(seams, part), "edges"));
		}
		singles.addAll(rest);
		if (!singles.isEmpty()) {
			singles.sort(Integer::compare);
			int w = Math.min(9, singles.size());
			int[] cells = new int[w * ((singles.size() + w - 1) / w)];
			Arrays.fill(cells, -1);
			for (int i = 0; i < singles.size(); i++) cells[i] = singles.get(i);
			out.add(toGroup(tiles, "", new Grid(w, cells.length / w, cells), "as is"));
		}
		return out;
	}

	private static Group toGroup(List<Tile> tiles, String title, Grid g, String method) {
		List<Long> keys = new ArrayList<>();
		for (int c : g.cells()) keys.add(c < 0 ? null : tiles.get(c).key());
		return new Group(title, keys, g.w(), g.h(), method);
	}

	// ------------------------------------------------------------- names --

	/** {Grid, method} for the reading of the names whose seams fit best, or null when no reading forms a grid. */
	private static Object[] fromNames(Seams s, MapartNames.NameGroup ng) {
		List<MapartNames.Reading> readings = MapartNames.readings(ng.positions());
		boolean anyColors = false;
		for (int t : ng.tiles()) anyColors |= s.has[t];
		Grid best = null;
		String bestLabel = null;
		double bestGain = -Double.MAX_VALUE;
		for (MapartNames.Reading r : readings) {
			if (r.index()) {
				int span = 0;
				for (int[] rc : r.cells()) span = Math.max(span, rc[0] + 1);
				if (span > 4 * ng.tiles().size() + 8) continue;
				int fixedW = anyColors ? -1 : span <= 9 ? span : (int) Math.ceil(Math.sqrt(span));
				for (int w = span; w >= 1; w--) {
					if (fixedW > 0 && w != fixedW) continue;
					int h = (span + w - 1) / w;
					int[] cells = new int[w * h];
					Arrays.fill(cells, -1);
					boolean clash = false;
					for (int i = 0; i < ng.tiles().size() && !clash; i++) {
						int at = r.cells()[i][0];
						clash = cells[at] >= 0;
						cells[at] = ng.tiles().get(i);
					}
					if (clash) return null;
					double gain = gain(s, w, h, cells) + landscape(w, h, ng.tiles().size());
					if (gain > bestGain + 1e-9) {
						bestGain = gain;
						best = new Grid(w, h, cells);
						bestLabel = r.label();
					}
				}
			} else {
				int h = 0, w = 0;
				for (int[] rc : r.cells()) {
					h = Math.max(h, rc[0] + 1);
					w = Math.max(w, rc[1] + 1);
				}
				if ((long) w * h > 4L * ng.tiles().size() + 8) continue;
				int[] cells = new int[w * h];
				Arrays.fill(cells, -1);
				boolean clash = false;
				for (int i = 0; i < ng.tiles().size() && !clash; i++) {
					int at = r.cells()[i][0] * w + r.cells()[i][1];
					clash = cells[at] >= 0;
					cells[at] = ng.tiles().get(i);
				}
				if (clash) continue;
				double gain = gain(s, w, h, cells);
				if (gain > bestGain + 1e-9) {
					bestGain = gain;
					best = new Grid(w, h, cells);
					bestLabel = r.label();
				}
			}
		}
		if (best == null) return null;
		boolean allColors = true;
		for (int t : ng.tiles()) allColors &= s.has[t];
		if (allColors && ng.tiles().size() >= 3) {
			Grid edges = assemble(s, ng.tiles());
			double edgeGain = gain(s, edges.w(), edges.h(), edges.cells());
			if (edgeGain > bestGain + DISAGREE * Math.max(1, ng.tiles().size())) bestLabel += " (edges disagree)";
		}
		return new Object[] { best, bestLabel };
	}

	/** Loose maps go into a named artwork's holes when they clearly belong there. @return true if any did */
	private static boolean fillHoles(Seams s, Grid g, List<Integer> loose) {
		boolean any = false;
		List<Integer> pool = new ArrayList<>();
		for (int t : loose) if (s.has[t]) pool.add(t);
		if (pool.isEmpty()) return false;
		List<Integer> subset = new ArrayList<>(pool);
		for (int c : g.cells()) if (c >= 0) subset.add(c);
		Compat c = new Compat(s, subset);
		for (int cell = 0; cell < g.cells().length && !pool.isEmpty(); cell++) {
			if (g.cells()[cell] >= 0) continue;
			int r = cell / g.w(), col = cell % g.w();
			int pick = -1;
			double pickVal = 0;
			for (int t : pool) {
				int lt = c.local(t);
				double sum = 0;
				int k = 0, bb = 0;
				if (col > 0 && g.cells()[cell - 1] >= 0) {
					int o = c.local(g.cells()[cell - 1]);
					sum += c.ch[o][lt];
					k++;
					if (c.buddyH(o, lt)) bb++;
				}
				if (col + 1 < g.w() && g.cells()[cell + 1] >= 0) {
					int o = c.local(g.cells()[cell + 1]);
					sum += c.ch[lt][o];
					k++;
					if (c.buddyH(lt, o)) bb++;
				}
				if (r > 0 && g.cells()[cell - g.w()] >= 0) {
					int o = c.local(g.cells()[cell - g.w()]);
					sum += c.cv[o][lt];
					k++;
					if (c.buddyV(o, lt)) bb++;
				}
				if (r + 1 < g.h() && g.cells()[cell + g.w()] >= 0) {
					int o = c.local(g.cells()[cell + g.w()]);
					sum += c.cv[lt][o];
					k++;
					if (c.buddyV(lt, o)) bb++;
				}
				if (k == 0 || bb == 0 || sum / k < 0.6) continue;
				if (sum / k > pickVal) {
					pickVal = sum / k;
					pick = t;
				}
			}
			if (pick >= 0) {
				g.cells()[cell] = pick;
				pool.remove(Integer.valueOf(pick));
				loose.remove(Integer.valueOf(pick));
				any = true;
			}
		}
		return any;
	}

	// ------------------------------------------------------------- edges --

	/** Seam dissimilarities between every pair of tiles: 0 = continues perfectly, 1 = nothing in common. */
	static final class Seams {
		final boolean[] has;
		/** dh[a][b]: a left of b. dv[a][b]: a above b. */
		final double[][] dh;
		final double[][] dv;
		/** gh / gv: how much better than those two edges' median match (positive = likely real). */
		final double[][] gh;
		final double[][] gv;
		/** Per tile: share of each colour byte (sums to 1), or null without colours. */
		final double[][] histogram;

		Seams(List<Tile> tiles, Palette palette) {
			int n = tiles.size();
			has = new boolean[n];
			dh = new double[n][n];
			dv = new double[n][n];
			Ring[][] rings = new Ring[n][];
			histogram = new double[n][];
			for (int i = 0; i < n; i++) {
				has[i] = tiles.get(i).hasColors();
				if (!has[i]) continue;
				rings[i] = Ring.of(tiles.get(i).colors(), palette);
				double[] hist = new double[256];
				byte[] colors = tiles.get(i).colors();
				for (int p = 0; p < SIZE * SIZE; p++) hist[colors[p] & 0xFF]++;
				for (int b = 0; b < 256; b++) hist[b] /= SIZE * SIZE;
				histogram[i] = hist;
			}
			for (int a = 0; a < n; a++) {
				for (int b = 0; b < n; b++) {
					if (a == b) {
						dh[a][b] = dv[a][b] = Double.MAX_VALUE;
					} else if (!has[a] || !has[b]) {
						dh[a][b] = dv[a][b] = 0.5;
					} else {
						dh[a][b] = dissim(rings[a][Ring.RIGHT], rings[b][Ring.LEFT]);
						dv[a][b] = dissim(rings[a][Ring.BOTTOM], rings[b][Ring.TOP]);
					}
				}
			}
			gh = gains(dh, has);
			gv = gains(dv, has);
		}

		/** g[a][b] = ½(median of a's outgoing, median of b's incoming) − d[a][b]; 0 without colours. */
		private static double[][] gains(double[][] d, boolean[] has) {
			int n = d.length;
			double[] out = new double[n], in = new double[n];
			for (int i = 0; i < n; i++) {
				double[] row = new double[n - 1], col = new double[n - 1];
				int k = 0;
				for (int j = 0; j < n; j++) {
					if (j == i) continue;
					row[k] = d[i][j];
					col[k++] = d[j][i];
				}
				out[i] = median(row);
				in[i] = median(col);
			}
			double[][] g = new double[n][n];
			for (int a = 0; a < n; a++) {
				for (int b = 0; b < n; b++) {
					if (a != b && has[a] && has[b]) g[a][b] = 0.5 * (out[a] + in[b]) - d[a][b];
				}
			}
			return g;
		}

		private static double median(double[] v) {
			if (v.length == 0) return 0.5;
			double[] c = v.clone();
			Arrays.sort(c);
			return c.length % 2 == 1 ? c[c.length / 2] : 0.5 * (c[c.length / 2 - 1] + c[c.length / 2]);
		}
	}

	/** One side of a tile: outermost and second pixel ring, as bytes and RGB, ordered along the side. */
	record Ring(byte[] b1, byte[] b2, int[] c1, int[] c2) {
		static final int TOP = 0, BOTTOM = 1, LEFT = 2, RIGHT = 3;

		static Ring[] of(byte[] colors, Palette palette) {
			Ring[] r = new Ring[4];
			r[TOP] = side(colors, palette, 0, 1, SIZE);
			r[BOTTOM] = side(colors, palette, (SIZE - 1) * SIZE, 1, -SIZE);
			r[LEFT] = side(colors, palette, 0, SIZE, 1);
			r[RIGHT] = side(colors, palette, SIZE - 1, SIZE, -1);
			return r;
		}

		/** @param start first pixel, {@code step} along the side, {@code inward} towards the tile's middle */
		private static Ring side(byte[] colors, Palette palette, int start, int step, int inward) {
			byte[] b1 = new byte[SIZE], b2 = new byte[SIZE];
			int[] c1 = new int[SIZE], c2 = new int[SIZE];
			for (int i = 0; i < SIZE; i++) {
				int at = start + i * step;
				b1[i] = colors[at];
				b2[i] = colors[at + inward];
				c1[i] = palette.rgb(b1[i] & 0xFF) & 0xFFFFFF;
				c2[i] = palette.rgb(b2[i] & 0xFF) & 0xFFFFFF;
			}
			return new Ring(b1, b2, c1, c2);
		}
	}

	/** @param out the side leaving tile A (its right / bottom); {@code in} the side entering tile B (left / top) */
	static double dissim(Ring out, Ring in) {
		double ev = 1.0 - evScore(out.b1(), in.b1()) / (3.0 * SIZE);
		double pred = 0;
		for (int i = 0; i < SIZE; i++) {
			pred += predicted(out.c1()[i], out.c2()[i], in.c1()[i]) + predicted(in.c1()[i], in.c2()[i], out.c1()[i]);
		}
		pred /= 2.0 * 765 * SIZE;
		return 0.65 * ev + 0.35 * pred;
	}

	/** |(2·ring1 − ring2) − other side's ring1| over R, G, B: the gradient carried one pixel across the seam. */
	private static int predicted(int r1, int r2, int other) {
		int sum = 0;
		for (int shift = 0; shift <= 16; shift += 8) {
			int a = (r1 >> shift) & 0xFF, b = (r2 >> shift) & 0xFF, o = (other >> shift) & 0xFF;
			int p = Math.max(0, Math.min(255, 2 * a - b));
			sum += Math.abs(p - o);
		}
		return sum;
	}

	/**
	 * EV mod's adjacentEdgeScore: per pixel 1 if the colour on the other side matches (straight across or one
	 * pixel up / down), +2 more when it keeps matching through a colour change. 0 … 3·128.
	 */
	static int evScore(byte[] a, byte[] b) {
		int score = 0;
		boolean lastAcross = true, lastUp = true, lastDown = true;
		int lastB = Integer.MIN_VALUE;
		for (int i = 0; i < SIZE; i++) {
			boolean across = a[i] == b[i];
			boolean up = i > 0 && a[i] == b[i - 1];
			boolean down = i + 1 < SIZE && a[i] == b[i + 1];
			if (across || up || down) {
				score++;
				if (b[i] != lastB && ((across && lastAcross) || (up && lastUp) || (down && lastDown))) score += 2;
			}
			lastB = b[i];
			lastAcross = across;
			lastUp = up;
			lastDown = down;
		}
		return score;
	}

	/** Total seam gain of a grid (holes add nothing). Higher is better. */
	static double gain(Seams s, int w, int h, int[] cells) {
		double sum = 0;
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				int a = cells[r * w + c];
				if (c + 1 < w) sum += seam(s, a, cells[r * w + c + 1], true);
				if (r + 1 < h) sum += seam(s, a, cells[(r + 1) * w + c], false);
			}
		}
		return sum;
	}

	private static double landscape(int w, int h, int tiles) {
		return w >= h ? LANDSCAPE * tiles : 0;
	}

	private static double seam(Seams s, int a, int b, boolean horizontal) {
		if (a < 0 || b < 0) return 0;
		return horizontal ? s.gh[a][b] : s.gv[a][b];
	}

	/** Confidences and best matches within one set of tiles (local indices 0 … n-1). */
	static final class Compat {
		final int[] ids;
		final Map<Integer, Integer> local = new LinkedHashMap<>();
		final double[][] ch;
		final double[][] cv;
		final int[] bestR, bestL, bestD, bestU;

		Compat(Seams s, List<Integer> subset) {
			int n = subset.size();
			ids = new int[n];
			for (int i = 0; i < n; i++) {
				ids[i] = subset.get(i);
				local.put(ids[i], i);
			}
			double[][] dh = new double[n][n], dv = new double[n][n];
			for (int i = 0; i < n; i++) {
				for (int j = 0; j < n; j++) {
					dh[i][j] = s.dh[ids[i]][ids[j]];
					dv[i][j] = s.dv[ids[i]][ids[j]];
				}
			}
			bestR = new int[n];
			bestL = new int[n];
			bestD = new int[n];
			bestU = new int[n];
			double[] secR = new double[n], secL = new double[n], secD = new double[n], secU = new double[n];
			for (int i = 0; i < n; i++) {
				bestR[i] = best(dh, i, true, secR);
				bestL[i] = best(dh, i, false, secL);
				bestD[i] = best(dv, i, true, secD);
				bestU[i] = best(dv, i, false, secU);
			}
			ch = new double[n][n];
			cv = new double[n][n];
			for (int i = 0; i < n; i++) {
				for (int j = 0; j < n; j++) {
					if (i == j) continue;
					ch[i][j] = 0.5 * (Math.exp(-dh[i][j] / secR[i]) + Math.exp(-dh[i][j] / secL[j]));
					cv[i][j] = 0.5 * (Math.exp(-dv[i][j] / secD[i]) + Math.exp(-dv[i][j] / secU[j]));
				}
			}
		}

		/** Best partner of {@code i} along a row ({@code outgoing}: i's right / bottom), storing the second-best cost. */
		private static int best(double[][] d, int i, boolean outgoing, double[] sec) {
			int n = d.length;
			int best = -1;
			double b1 = Double.MAX_VALUE, b2 = Double.MAX_VALUE;
			for (int j = 0; j < n; j++) {
				if (j == i) continue;
				double v = outgoing ? d[i][j] : d[j][i];
				if (v < b1) {
					b2 = b1;
					b1 = v;
					best = j;
				} else if (v < b2) {
					b2 = v;
				}
			}
			sec[i] = Math.max(0.02, b2 == Double.MAX_VALUE ? 1.0 : b2);
			return best;
		}

		int local(int global) {
			return local.get(global);
		}

		boolean buddyH(int left, int right) {
			return bestR[left] == right && bestL[right] == left;
		}

		boolean buddyV(int top, int bottom) {
			return bestD[top] == bottom && bestU[bottom] == top;
		}

		int buddies(int i) {
			int n = 0;
			if (bestR[i] >= 0 && buddyH(i, bestR[i])) n++;
			if (bestL[i] >= 0 && buddyH(bestL[i], i)) n++;
			if (bestD[i] >= 0 && buddyV(i, bestD[i])) n++;
			if (bestU[i] >= 0 && buddyV(bestU[i], i)) n++;
			return n;
		}
	}

	/**
	 * Loose maps split into artworks (after Gallagher's tree-based jigsaw): every map starts as its own cluster;
	 * seams are taken best gain first, and two clusters join along one when the joined shapes do not overlap and
	 * all the new seams together still beat a random match. What is left unjoined is a separate artwork.
	 */
	static List<Grid> clusters(Seams s, List<Integer> subset) {
		int n = subset.size();
		List<double[]> seams = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				if (i == j) continue;
				int a = subset.get(i), b = subset.get(j);
				if (s.gh[a][b] >= PIECE) seams.add(new double[] { s.gh[a][b], i, j, 1 });
				if (s.gv[a][b] >= PIECE) seams.add(new double[] { s.gv[a][b], i, j, 0 });
			}
		}
		seams.sort((x, y) -> Double.compare(y[0], x[0]));
		int[] cluster = new int[n];
		int[][] pos = new int[n][];
		List<List<Integer>> members = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			cluster[i] = i;
			pos[i] = new int[] { 0, 0 };
			members.add(new ArrayList<>(List.of(i)));
		}
		for (double[] e : seams) {
			int a = (int) e[1], b = (int) e[2];
			boolean horizontal = e[3] == 1;
			int ca = cluster[a], cb = cluster[b];
			if (ca == cb) continue;
			int dr = pos[a][0] + (horizontal ? 0 : 1) - pos[b][0];
			int dc = pos[a][1] + (horizontal ? 1 : 0) - pos[b][1];
			Map<Long, Integer> occupied = new LinkedHashMap<>();
			for (int m : members.get(ca)) occupied.put(cellKey(pos[m][0], pos[m][1]), m);
			boolean overlap = false;
			double joinGain = 0;
			for (int m : members.get(cb)) {
				int r = pos[m][0] + dr, c = pos[m][1] + dc;
				if (occupied.containsKey(cellKey(r, c))) {
					overlap = true;
					break;
				}
				Integer left = occupied.get(cellKey(r, c - 1)), right = occupied.get(cellKey(r, c + 1));
				Integer up = occupied.get(cellKey(r - 1, c)), down = occupied.get(cellKey(r + 1, c));
				int g = subset.get(m);
				if (left != null) joinGain += s.gh[subset.get(left)][g];
				if (right != null) joinGain += s.gh[g][subset.get(right)];
				if (up != null) joinGain += s.gv[subset.get(up)][g];
				if (down != null) joinGain += s.gv[g][subset.get(down)];
			}
			if (overlap || joinGain < PIECE) continue;
			for (int m : members.get(cb)) {
				pos[m][0] += dr;
				pos[m][1] += dc;
				cluster[m] = ca;
			}
			members.get(ca).addAll(members.get(cb));
			members.get(cb).clear();
		}
		List<Grid> out = new ArrayList<>();
		for (List<Integer> ms : members) {
			if (ms.isEmpty()) continue;
			int minR = Integer.MAX_VALUE, minC = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE, maxC = Integer.MIN_VALUE;
			for (int m : ms) {
				minR = Math.min(minR, pos[m][0]);
				maxR = Math.max(maxR, pos[m][0]);
				minC = Math.min(minC, pos[m][1]);
				maxC = Math.max(maxC, pos[m][1]);
			}
			int w = maxC - minC + 1, h = maxR - minR + 1;
			int[] cells = new int[w * h];
			Arrays.fill(cells, -1);
			for (int m : ms) cells[(pos[m][0] - minR) * w + pos[m][1] - minC] = subset.get(m);
			out.add(new Grid(w, h, cells));
		}
		return out;
	}

	/**
	 * Loose maps → artworks. All of them are first laid out as one; {@link #clusters} then marks pieces that hold
	 * together. Pieces stay together when, in the joint layout, the seams between them still beat a random match
	 * on average (one artwork the merge could not join); otherwise they are separate artworks.
	 */
	static List<List<Integer>> artworks(Seams s, List<Integer> subset) {
		if (subset.size() < 4) return List.of(subset);
		List<Grid> pieces = clusters(s, subset);
		if (pieces.size() < 2) return List.of(subset);
		Map<Integer, Integer> piece = new LinkedHashMap<>();
		for (int i = 0; i < pieces.size(); i++) for (int t : pieces.get(i).cells()) if (t >= 0) piece.put(t, i);
		Grid joint = assemble(s, subset);
		int k = pieces.size();
		double[][] sum = new double[k][k];
		int[][] count = new int[k][k];
		int w = joint.w();
		for (int i = 0; i < joint.cells().length; i++) {
			int a = joint.cells()[i];
			if (a < 0) continue;
			int r = i / w, c = i % w;
			if (c + 1 < w) between(s, piece, a, joint.cells()[i + 1], true, sum, count);
			if (r + 1 < joint.h()) between(s, piece, a, joint.cells()[i + w], false, sum, count);
		}
		double[][] hist = new double[k][256];
		for (int t : subset) {
			double[] h = s.histogram[t];
			int pi = piece.get(t);
			for (int b = 0; b < 256; b++) hist[pi][b] += h[b];
		}
		for (double[] h : hist) {
			double total = 0;
			for (double v : h) total += v;
			for (int b = 0; b < 256; b++) h[b] /= Math.max(1e-9, total);
		}
		int[] parent = new int[k];
		for (int i = 0; i < k; i++) parent[i] = i;
		for (int a = 0; a < k; a++) {
			for (int b = a + 1; b < k; b++) {
				int n = count[a][b] + count[b][a];
				boolean seamsFit = n > 0 && (sum[a][b] + sum[b][a]) / n >= JOIN;
				double overlap = 0;
				for (int x = 0; x < 256; x++) overlap += Math.min(hist[a][x], hist[b][x]);
				if (seamsFit || overlap >= SAME_PALETTE) union(parent, a, b);
			}
		}
		Map<Integer, List<Integer>> byRoot = new LinkedHashMap<>();
		for (int t : subset) byRoot.computeIfAbsent(root(parent, piece.get(t)), x -> new ArrayList<>()).add(t);
		return new ArrayList<>(byRoot.values());
	}

	private static void between(Seams s, Map<Integer, Integer> piece, int a, int b, boolean horizontal, double[][] sum, int[][] count) {
		if (b < 0) return;
		int pa = piece.get(a), pb = piece.get(b);
		if (pa == pb) return;
		sum[pa][pb] += horizontal ? s.gh[a][b] : s.gv[a][b];
		count[pa][pb]++;
	}

	private static long cellKey(int r, int c) {
		return ((long) r << 32) ^ (c & 0xFFFFFFFFL);
	}

	private static int root(int[] p, int i) {
		while (p[i] != i) {
			p[i] = p[p[i]];
			i = p[i];
		}
		return i;
	}

	private static void union(int[] p, int a, int b) {
		p[root(p, a)] = root(p, b);
	}

	/** Jigsaw from edges: every frame size with few holes, a few seeds each; best mean seam wins. */
	static Grid assemble(Seams s, List<Integer> subset) {
		int n = subset.size();
		if (n == 1) return new Grid(1, 1, new int[] { subset.get(0) });
		Compat c = new Compat(s, subset);
		Integer[] bySeed = new Integer[n];
		for (int i = 0; i < n; i++) bySeed[i] = i;
		Arrays.sort(bySeed, Comparator.comparingInt((Integer i) -> -c.buddies(i)).thenComparingInt(i -> i));
		int seeds = n <= 16 ? n : 4; // small sets: every tile as a seed (cheap, avoids a bad start)
		int maxHoles = n / 5;
		Grid best = null;
		double bestGain = -Double.MAX_VALUE;
		for (int w = n; w >= 1; w--) {
			int h = (n + w - 1) / w;
			if (w * h - n > maxHoles) continue;
			for (int k = 0; k < seeds; k++) {
				Grid g = place(c, w, h, bySeed[k]);
				refine(s, c, g);
				int[] global = new int[g.cells().length];
				for (int i = 0; i < global.length; i++) global[i] = g.cells()[i] < 0 ? -1 : c.ids[g.cells()[i]];
				double gain = gain(s, g.w(), g.h(), global) + landscape(g.w(), g.h(), n);
				if (gain > bestGain + 1e-9) {
					bestGain = gain;
					best = new Grid(g.w(), g.h(), global);
				}
			}
		}
		return best;
	}

	/**
	 * Placer (after Pomeranz et al.): start from the seed in the middle of a board twice the frame; keep adding the
	 * tile and empty neighbouring cell with the highest confidence to the tiles already around it, best buddies
	 * first, never letting the placed area outgrow W×H.
	 */
	private static Grid place(Compat c, int w, int h, int seed) {
		int n = c.ids.length;
		int rows = 2 * h + 1, cols = 2 * w + 1;
		int[] board = new int[rows * cols];
		Arrays.fill(board, -1);
		boolean[] used = new boolean[n];
		List<Integer> placed = new ArrayList<>();
		int start = h * cols + w;
		board[start] = seed;
		used[seed] = true;
		placed.add(start);
		int minR = h, maxR = h, minC = w, maxC = w;
		int[] stamp = new int[rows * cols];
		for (int step = 1; step < n; step++) {
			double bestVal = -Double.MAX_VALUE;
			int bestCell = -1, bestTile = -1;
			for (int p : placed) {
				int pr = p / cols, pc = p % cols;
				int[][] around = { { pr, pc - 1 }, { pr, pc + 1 }, { pr - 1, pc }, { pr + 1, pc } };
				for (int[] q : around) {
					int qr = q[0], qc = q[1];
					if (qr < 0 || qc < 0 || qr >= rows || qc >= cols) continue;
					int cell = qr * cols + qc;
					if (board[cell] >= 0 || stamp[cell] == step) continue;
					stamp[cell] = step;
					if (Math.max(maxR, qr) - Math.min(minR, qr) + 1 > h || Math.max(maxC, qc) - Math.min(minC, qc) + 1 > w) continue;
					for (int t = 0; t < n; t++) {
						if (used[t]) continue;
						double v = value(c, board, cols, rows, qr, qc, t);
						if (v > bestVal) {
							bestVal = v;
							bestCell = cell;
							bestTile = t;
						}
					}
				}
			}
			if (bestCell < 0) break; // frame full (cannot happen while w*h ≥ n)
			board[bestCell] = bestTile;
			used[bestTile] = true;
			placed.add(bestCell);
			minR = Math.min(minR, bestCell / cols);
			maxR = Math.max(maxR, bestCell / cols);
			minC = Math.min(minC, bestCell % cols);
			maxC = Math.max(maxC, bestCell % cols);
		}
		int gw = maxC - minC + 1, gh = maxR - minR + 1;
		int[] cells = new int[gw * gh];
		for (int r = 0; r < gh; r++) {
			for (int col = 0; col < gw; col++) cells[r * gw + col] = board[(minR + r) * cols + minC + col];
		}
		// Tiles that found no room (never with w*h ≥ n) go into the first holes.
		for (int t = 0; t < n; t++) {
			if (used[t]) continue;
			for (int i = 0; i < cells.length; i++) {
				if (cells[i] < 0) {
					cells[i] = t;
					used[t] = true;
					break;
				}
			}
		}
		return new Grid(gw, gh, cells);
	}

	private static double value(Compat c, int[] board, int cols, int rows, int r, int col, int t) {
		double sum = 0;
		int k = 0, bb = 0;
		if (col > 0 && board[r * cols + col - 1] >= 0) {
			int o = board[r * cols + col - 1];
			sum += c.ch[o][t];
			k++;
			if (c.buddyH(o, t)) bb++;
		}
		if (col + 1 < cols && board[r * cols + col + 1] >= 0) {
			int o = board[r * cols + col + 1];
			sum += c.ch[t][o];
			k++;
			if (c.buddyH(t, o)) bb++;
		}
		if (r > 0 && board[(r - 1) * cols + col] >= 0) {
			int o = board[(r - 1) * cols + col];
			sum += c.cv[o][t];
			k++;
			if (c.buddyV(o, t)) bb++;
		}
		if (r + 1 < rows && board[(r + 1) * cols + col] >= 0) {
			int o = board[(r + 1) * cols + col];
			sum += c.cv[t][o];
			k++;
			if (c.buddyV(t, o)) bb++;
		}
		if (k == 0) return -Double.MAX_VALUE;
		return sum / k + (bb == k ? 1.0 : 0.35 * bb / k) + 0.06 * (k - 1);
	}

	/** Swap tiles (and holes), then whole rows and columns, while the gain grows. */
	private static void refine(Seams s, Compat c, Grid g) {
		int w = g.w(), h = g.h();
		int[] cells = g.cells();
		int[] global = new int[cells.length];
		for (int pass = 0; pass < 12; pass++) {
			boolean improved = false;
			for (int p = 0; p < cells.length; p++) {
				for (int q = p + 1; q < cells.length; q++) {
					if (cells[p] < 0 && cells[q] < 0) continue;
					double before = around(s, c, cells, w, h, p, q);
					swap(cells, p, q);
					double after = around(s, c, cells, w, h, p, q);
					if (after > before + 1e-9) improved = true;
					else swap(cells, p, q);
				}
			}
			for (int a = 0; a < h; a++) {
				for (int b = a + 1; b < h; b++) improved |= tryLines(s, c, cells, w, h, a, b, true, global);
			}
			for (int a = 0; a < w; a++) {
				for (int b = a + 1; b < w; b++) improved |= tryLines(s, c, cells, w, h, a, b, false, global);
			}
			if (!improved) break;
		}
	}

	private static boolean tryLines(Seams s, Compat c, int[] cells, int w, int h, int a, int b, boolean rows, int[] global) {
		double before = localGain(s, c, cells, w, h, global);
		swapLines(cells, w, h, a, b, rows);
		double after = localGain(s, c, cells, w, h, global);
		if (after > before + 1e-9) return true;
		swapLines(cells, w, h, a, b, rows);
		return false;
	}

	private static void swapLines(int[] cells, int w, int h, int a, int b, boolean rows) {
		if (rows) {
			for (int col = 0; col < w; col++) swap(cells, a * w + col, b * w + col);
		} else {
			for (int r = 0; r < h; r++) swap(cells, r * w + a, r * w + b);
		}
	}

	private static double localGain(Seams s, Compat c, int[] cells, int w, int h, int[] global) {
		for (int i = 0; i < cells.length; i++) global[i] = cells[i] < 0 ? -1 : c.ids[cells[i]];
		return gain(s, w, h, global);
	}

	/** Sum of the seams touching cells p and q (each seam once). */
	private static double around(Seams s, Compat c, int[] cells, int w, int h, int p, int q) {
		double sum = seamsOf(s, c, cells, w, h, p, -1);
		sum += seamsOf(s, c, cells, w, h, q, p);
		return sum;
	}

	private static double seamsOf(Seams s, Compat c, int[] cells, int w, int h, int at, int skip) {
		int r = at / w, col = at % w;
		double sum = 0;
		if (col > 0 && at - 1 != skip) sum += localSeam(s, c, cells[at - 1], cells[at], true);
		if (col + 1 < w && at + 1 != skip) sum += localSeam(s, c, cells[at], cells[at + 1], true);
		if (r > 0 && at - w != skip) sum += localSeam(s, c, cells[at - w], cells[at], false);
		if (r + 1 < h && at + w != skip) sum += localSeam(s, c, cells[at], cells[at + w], false);
		return sum;
	}

	private static double localSeam(Seams s, Compat c, int a, int b, boolean horizontal) {
		return seam(s, a < 0 ? -1 : c.ids[a], b < 0 ? -1 : c.ids[b], horizontal);
	}

	private static void swap(int[] a, int i, int j) {
		int t = a[i];
		a[i] = a[j];
		a[j] = t;
	}
}
