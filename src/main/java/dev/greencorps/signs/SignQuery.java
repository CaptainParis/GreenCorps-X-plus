package dev.greencorps.signs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sign search query. Terms AND together, {@code -term} excludes, quotes keep a phrase,
 * and {@code key:value} narrows metadata:
 *
 * <pre>
 *   stash "free shulkers" -grief author:name flag:economy year:2016..2019
 * </pre>
 *
 * Unknown keys stay plain text so a stray colon does not swallow the query.
 */
public final class SignQuery {
	private static final int DAYS_PER_YEAR = 365;

	private final List<String> include = new ArrayList<>();
	private final List<String> exclude = new ArrayList<>();
	private String authorContains = "";
	private int requireFlags;
	private int excludeFlags;
	private int minDateDays = Integer.MIN_VALUE;
	private int maxDateDays = Integer.MAX_VALUE;
	private boolean bad;

	private SignQuery() {
	}

	public static SignQuery parse(String raw) {
		SignQuery q = new SignQuery();
		if (raw == null) return q;
		for (String token : tokenize(raw)) {
			q.consume(token);
		}
		return q;
	}

	private void consume(String token) {
		boolean negate = false;
		String t = token;
		if (t.startsWith("-") && t.length() > 1) {
			negate = true;
			t = t.substring(1);
		}
		int colon = t.indexOf(':');
		if (colon > 0 && colon < t.length() - 1) {
			String key = t.substring(0, colon).toLowerCase(Locale.ROOT);
			String value = t.substring(colon + 1);
			if (applyKey(key, value, negate)) return;
		}
		String term = t.toLowerCase(Locale.ROOT);
		if (term.isEmpty()) return;
		if (negate) exclude.add(term);
		else include.add(term);
	}

	private boolean applyKey(String key, String value, boolean negate) {
		switch (key) {
			case "author", "by" -> {
				if (!negate) authorContains = value.toLowerCase(Locale.ROOT);
				return true;
			}
			case "flag", "is" -> {
				int bit = SignFlags.byName(value);
				if (bit == 0) return false;
				if (negate) excludeFlags |= bit;
				else requireFlags |= bit;
				return true;
			}
			case "year" -> {
				return applyYear(value);
			}
			default -> {
				return false;
			}
		}
	}

	private boolean applyYear(String value) {
		try {
			int from;
			int to;
			int range = value.indexOf("..");
			if (range > 0) {
				from = Integer.parseInt(value.substring(0, range).trim());
				to = Integer.parseInt(value.substring(range + 2).trim());
			} else {
				from = Integer.parseInt(value.trim());
				to = from;
			}
			if (from > to) {
				int tmp = from;
				from = to;
				to = tmp;
			}
			minDateDays = Math.max(0, (from - 2000) * DAYS_PER_YEAR);
			maxDateDays = (to - 2000 + 1) * DAYS_PER_YEAR - 1;
			return true;
		} catch (NumberFormatException e) {
			bad = true;
			return false;
		}
	}

	private static List<String> tokenize(String raw) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '"') {
				quoted = !quoted;
				continue;
			}
			if (!quoted && Character.isWhitespace(c)) {
				if (cur.length() > 0) {
					out.add(cur.toString());
					cur.setLength(0);
				}
				continue;
			}
			cur.append(c);
		}
		if (cur.length() > 0) out.add(cur.toString());
		return out;
	}

	public List<String> include() {
		return include;
	}

	public List<String> exclude() {
		return exclude;
	}

	public String authorContains() {
		return authorContains;
	}

	public int requireFlags() {
		return requireFlags;
	}

	public int excludeFlags() {
		return excludeFlags;
	}

	public int minDateDays() {
		return minDateDays;
	}

	public int maxDateDays() {
		return maxDateDays;
	}

	/** True when nothing selects signs. A blank box is not a search. */
	public boolean isEmpty() {
		return include.isEmpty() && authorContains.isEmpty();
	}

	public boolean hasSyntaxError() {
		return bad;
	}
}
