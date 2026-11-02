package dev.greencorps.mapdupe;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mapart tile names → positions, the way EV mod reads them: tiles of one artwork share a prefix and a suffix
 * ({@code "Castle "} … {@code " by Bob"}), and what differs in the middle is the position. So the numbers may
 * sit anywhere in the name, in brackets, before an artist credit or a version tag.
 * <p>
 * Positions: {@code 3}, {@code #3} (index), {@code 2,4} / {@code 2-4} / {@code (2 4)} (pair), {@code 3/12}
 * ({@code a} of {@code b}), {@code A4} / {@code 4A} (letter + number), {@code r2c4} / {@code row 2 col 4},
 * {@code x4 y2}, and words: {@code Top Left}, {@code TL}, {@code Center}, ┌ ┐ └ ┘.
 */
final class MapartNames {
	enum Kind {
		/** a = index. */
		INDEX,
		/** Bare pair a,b: row,column or column,row. */
		PAIR,
		/** a/b: index of b tiles when every b is equal, else a pair. */
		OF,
		/** Explicit a = row, b = column. */
		ROW_COL,
		/** Explicit a = column, b = row (x / y). */
		COL_ROW,
		/** a = letter (A = 1), b = number. */
		LETTER_NUM,
		/** a = number, b = letter (A = 1). */
		NUM_LETTER,
		/** {@code words}: T / M / B for rows, L / M / R for columns. */
		WORDS
	}

	record Pos(Kind kind, int a, int b, String words) {}

	/** Tiles (by index into the caller's list) that share a naming pattern, with each tile's position. */
	record NameGroup(String title, List<Integer> tiles, List<Pos> positions) {}

	private MapartNames() {}

	/** EV's simplifyPosStr, plus row / col / x / y markers and letter-digit splitting. */
	static String simplify(String raw) {
		String s = Normalizer.normalize(raw, Normalizer.Form.NFKD).toUpperCase(Locale.ROOT);
		s = s.replace("┌", " TL ").replace("┐", " TR ").replace("└", " BL ").replace("┘", " BR ");
		s = s.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ");
		s = s.replaceAll("\\bTOP\\b", "T").replaceAll("\\bBOTTOM\\b", "B").replaceAll("\\bLEFT\\b", "L")
			.replaceAll("\\bRIGHT\\b", "R").replaceAll("\\b(?:MIDDLE|CENTER|CENTRE|MID)\\b", "M")
			.replaceAll("\\bUP\\b", "T").replaceAll("\\bDOWN\\b", "B")
			.replaceAll("\\bROW\\b", "R").replaceAll("\\b(?:COLUMN|COL)\\b", "C");
		s = s.replaceAll("(?<=\\p{IsAlphabetic})(?=\\p{IsDigit})|(?<=\\p{IsDigit})(?=\\p{IsAlphabetic})", " ");
		return s.trim().replaceAll("\\s+", " ");
	}

	/** Position in a name's middle part, or null when it is not one. */
	static Pos parse(String middle) {
		if (middle == null) return null;
		String s = simplify(middle);
		if (s.isEmpty()) return null;
		String[] t = s.split(" ");
		boolean slash = middle.contains("/") || s.matches(".*\\bOF\\b.*");
		if (slash) t = s.replaceAll("\\bOF\\b", " ").trim().split("\\s+");
		if (t.length == 4) {
			if (t[0].equals("R") && num(t[1]) && t[2].equals("C") && num(t[3])) return new Pos(Kind.ROW_COL, n(t[1]), n(t[3]), null);
			if (t[0].equals("C") && num(t[1]) && t[2].equals("R") && num(t[3])) return new Pos(Kind.COL_ROW, n(t[1]), n(t[3]), null);
			if (t[0].equals("X") && num(t[1]) && t[2].equals("Y") && num(t[3])) return new Pos(Kind.COL_ROW, n(t[1]), n(t[3]), null);
			return null;
		}
		if (t.length == 1) {
			if (num(t[0])) return new Pos(Kind.INDEX, n(t[0]), 0, null);
			if (words(t[0])) return new Pos(Kind.WORDS, 0, 0, t[0]);
			return null; // a lone letter is too often part of a title
		}
		if (t.length == 2) {
			if (num(t[0]) && num(t[1])) return new Pos(slash ? Kind.OF : Kind.PAIR, n(t[0]), n(t[1]), null);
			if (words(t[0]) && words(t[1])) return new Pos(Kind.WORDS, 0, 0, t[0] + t[1]);
			if (letter(t[0]) && num(t[1])) return new Pos(Kind.LETTER_NUM, t[0].charAt(0) - 'A' + 1, n(t[1]), null);
			if (num(t[0]) && letter(t[1])) return new Pos(Kind.NUM_LETTER, n(t[0]), t[1].charAt(0) - 'A' + 1, null);
		}
		return null;
	}

	private static boolean num(String t) {
		return t.length() <= 4 && t.chars().allMatch(Character::isDigit);
	}

	private static int n(String t) {
		return Integer.parseInt(t);
	}

	private static boolean letter(String t) {
		return t.length() == 1 && t.charAt(0) >= 'A' && t.charAt(0) <= 'Z';
	}

	/** T / M / B and L / M / R, one or two of them ("T", "TL", "MR"). */
	private static boolean words(String t) {
		return !t.isEmpty() && t.matches("[TMB]?[LMR]?");
	}

	// ------------------------------------------------------------ grouping --

	/**
	 * Union-find over named tiles: two names belong together when, after their common prefix and suffix, both
	 * middles are positions of the same shape. Groups of one are left out (returned in {@code loose}).
	 */
	static List<NameGroup> groups(List<String> names, List<Integer> loose) {
		int n = names.size();
		int[] parent = new int[n];
		for (int i = 0; i < n; i++) parent[i] = i;
		for (int i = 0; i < n; i++) {
			if (names.get(i) == null || names.get(i).isBlank()) continue;
			for (int j = i + 1; j < n; j++) {
				if (names.get(j) == null || names.get(j).isBlank()) continue;
				if (related(names.get(i), names.get(j))) parent[find(parent, i)] = find(parent, j);
			}
		}
		Map<Integer, List<Integer>> byRoot = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			if (names.get(i) == null || names.get(i).isBlank()) {
				loose.add(i);
				continue;
			}
			byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
		}
		List<NameGroup> out = new ArrayList<>();
		for (List<Integer> members : byRoot.values()) {
			if (members.size() < 2) {
				loose.addAll(members);
				continue;
			}
			NameGroup g = group(names, members);
			if (g == null) loose.addAll(members);
			else out.add(g);
		}
		return out;
	}

	private static int find(int[] p, int i) {
		while (p[i] != i) {
			p[i] = p[p[i]];
			i = p[i];
		}
		return i;
	}

	static boolean related(String a, String b) {
		if (a.equals(b)) return false;
		int[] ps = prefixSuffix(List.of(a, b));
		Pos pa = parse(a.substring(ps[0], a.length() - ps[1]));
		Pos pb = parse(b.substring(ps[0], b.length() - ps[1]));
		return pa != null && pb != null && shape(pa) == shape(pb);
	}

	/** 1 for an index, 2 for any two-axis position. */
	private static int shape(Pos p) {
		return p.kind() == Kind.INDEX ? 1 : 2;
	}

	/** Common prefix / suffix lengths of all names, never cutting into a run of digits. */
	static int[] prefixSuffix(List<String> names) {
		String first = names.get(0);
		int p = first.length();
		int s = first.length();
		int minLen = first.length();
		for (String x : names) {
			p = Math.min(p, commonPrefix(first, x));
			s = Math.min(s, commonSuffix(first, x));
			minLen = Math.min(minLen, x.length());
		}
		int overlap = p + s - minLen;
		if (overlap > 0) {
			// EV: "a 11/x" + "a 111/x" share "a 11" and "11/x"; give both ends back the overlap.
			p = Math.max(0, p - overlap);
			s = Math.max(0, s - overlap);
		}
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String x : names) {
				if (p > 0 && p < x.length() && Character.isDigit(x.charAt(p)) && Character.isDigit(x.charAt(p - 1))) {
					p--;
					changed = true;
				}
				int e = x.length() - s;
				if (s > 0 && e > 0 && Character.isDigit(x.charAt(e)) && Character.isDigit(x.charAt(e - 1))) {
					s--;
					changed = true;
				}
			}
		}
		return new int[] { p, s };
	}

	private static int commonPrefix(String a, String b) {
		int i = 0;
		while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
		return i;
	}

	private static int commonSuffix(String a, String b) {
		int i = 0;
		while (i < a.length() && i < b.length() && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) i++;
		return i;
	}

	private static NameGroup group(List<String> names, List<Integer> members) {
		List<String> ns = new ArrayList<>();
		for (int i : members) ns.add(names.get(i));
		int[] ps = prefixSuffix(ns);
		List<Pos> positions = new ArrayList<>();
		for (String x : ns) {
			if (ps[0] + ps[1] > x.length()) return null;
			Pos pos = parse(x.substring(ps[0], x.length() - ps[1]));
			if (pos == null) return null;
			positions.add(pos);
		}
		String prefix = ns.get(0).substring(0, ps[0]).replaceAll("[\\s\\-_:#|,.(\\[{<]+$", "").strip();
		String suffix = ns.get(0).substring(ns.get(0).length() - ps[1]).replaceAll("^[\\s\\-_:|,.)\\]}>]+", "").strip();
		String title = prefix.isEmpty() ? suffix : suffix.isEmpty() ? prefix : prefix + " … " + suffix;
		return new NameGroup(title, members, positions);
	}

	// ----------------------------------------------------------- readings --

	/**
	 * Every way the group's positions can be read: one map tile → {row, column} per reading, or index readings
	 * (cell = index, row width left to the caller) marked by {@code index = true}. Empty when the kinds are mixed.
	 */
	record Reading(int[][] cells, boolean index, String label) {}

	static List<Reading> readings(List<Pos> ps) {
		List<Reading> out = new ArrayList<>();
		Kind k = ps.get(0).kind();
		boolean allOf = ps.stream().allMatch(p -> p.kind() == Kind.OF || p.kind() == Kind.INDEX);
		if (allOf && ps.stream().anyMatch(p -> p.kind() == Kind.OF)) {
			long totals = ps.stream().filter(p -> p.kind() == Kind.OF).mapToInt(Pos::b).distinct().count();
			if (totals == 1) return List.of(new Reading(cells(ps, true, false), true, "names #"));
			k = Kind.PAIR;
		}
		for (Pos p : ps) {
			Kind pk = p.kind() == Kind.OF ? Kind.PAIR : p.kind();
			if (pk != k) return out;
		}
		switch (k) {
			case INDEX -> out.add(new Reading(cells(ps, true, false), true, "names #"));
			case ROW_COL -> out.add(new Reading(cells(ps, false, false), false, "names"));
			case COL_ROW -> out.add(new Reading(cells(ps, false, true), false, "names"));
			case PAIR -> {
				out.add(new Reading(cells(ps, false, false), false, "names row,col"));
				out.add(new Reading(cells(ps, false, true), false, "names col,row"));
			}
			case LETTER_NUM, NUM_LETTER -> {
				boolean letterFirst = k == Kind.LETTER_NUM;
				out.add(new Reading(cells(ps, false, !letterFirst), false, "names letter=row"));
				out.add(new Reading(cells(ps, false, letterFirst), false, "names letter=col"));
			}
			case WORDS -> out.add(new Reading(words(ps), false, "names"));
			default -> { }
		}
		return out;
	}

	/** {row, col} per tile (or {index, 0}), shifted so the smallest is 0. */
	private static int[][] cells(List<Pos> ps, boolean index, boolean swap) {
		int[][] rc = new int[ps.size()][];
		int minA = Integer.MAX_VALUE, minB = Integer.MAX_VALUE;
		for (Pos p : ps) {
			minA = Math.min(minA, p.a());
			minB = Math.min(minB, p.b());
		}
		for (int i = 0; i < ps.size(); i++) {
			Pos p = ps.get(i);
			if (index) rc[i] = new int[] { p.a() - minA, 0 };
			else if (swap) rc[i] = new int[] { p.b() - minB, p.a() - minA };
			else rc[i] = new int[] { p.a() - minA, p.b() - minB };
		}
		return rc;
	}

	private static int[][] words(List<Pos> ps) {
		boolean vertical = ps.stream().anyMatch(p -> p.words().contains("T") || p.words().contains("B"));
		boolean horizontal = ps.stream().anyMatch(p -> p.words().contains("L") || p.words().contains("R"));
		int[][] rc = new int[ps.size()][];
		for (int i = 0; i < ps.size(); i++) {
			String w = ps.get(i).words();
			int r = !vertical ? 0 : w.contains("T") ? 0 : w.contains("B") ? 2 : 1;
			int c = !horizontal ? 0 : w.contains("L") ? 0 : w.contains("R") ? 2 : 1;
			rc[i] = new int[] { r, c };
		}
		// A layout of only T / B (or only L / R) has no middle row (column): close the gap.
		boolean anyMidRow = false, anyMidCol = false;
		for (int[] x : rc) {
			anyMidRow |= x[0] == 1;
			anyMidCol |= x[1] == 1;
		}
		for (int[] x : rc) {
			if (vertical && !anyMidRow && x[0] == 2) x[0] = 1;
			if (horizontal && !anyMidCol && x[1] == 2) x[1] = 1;
		}
		return rc;
	}
}
