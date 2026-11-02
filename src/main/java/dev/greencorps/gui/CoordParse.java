package dev.greencorps.gui;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure coordinate string parser for CoordHomeScreen (no Minecraft deps). */
public final class CoordParse {
	private static final Pattern LABELED = Pattern.compile(
		"(?i)(?:x\\s*[:=]?\\s*)?(-?\\d+(?:\\.\\d+)?)\\s*[,\\s]+(?:y\\s*[:=]?\\s*)?(-?\\d+(?:\\.\\d+)?)?(?:\\s*[,\\s]+(?:z\\s*[:=]?\\s*)?(-?\\d+(?:\\.\\d+)?))?"
	);

	private CoordParse() {}

	public record Parsed(int x, Integer y, int z) {}

	/** Parse flexible coordinate strings into block ints. */
	public static Parsed parse(String raw) {
		if (raw == null) return null;
		String s = raw.trim();
		if (s.isEmpty()) return null;
		if (s.regionMatches(true, 0, "/tp", 0, 3)) {
			s = s.substring(3).trim();
			if (s.startsWith("@")) {
				int sp = s.indexOf(' ');
				if (sp < 0) return null;
				s = s.substring(sp + 1).trim();
			}
		}
		s = s.replace(',', ' ').replaceAll("\\s+", " ").trim();

		Matcher m = LABELED.matcher(s);
		if (m.find()) {
			try {
				int x = (int) Math.floor(Double.parseDouble(m.group(1)));
				String g2 = m.group(2);
				String g3 = m.group(3);
				if (g3 != null && !g3.isBlank()) {
					int y = (int) Math.floor(Double.parseDouble(g2));
					int z = (int) Math.floor(Double.parseDouble(g3));
					return new Parsed(x, y, z);
				}
				if (g2 != null && !g2.isBlank()) {
					int z = (int) Math.floor(Double.parseDouble(g2));
					return new Parsed(x, null, z);
				}
			} catch (NumberFormatException ignored) {}
		}

		String nums = s.toLowerCase(Locale.ROOT)
			.replaceAll("[xyz]\\s*[:=]?", " ")
			.replaceAll("[^0-9.\\-\\s]", " ")
			.replaceAll("\\s+", " ")
			.trim();
		String[] parts = nums.split(" ");
		try {
			if (parts.length == 2) {
				return new Parsed(
					(int) Math.floor(Double.parseDouble(parts[0])),
					null,
					(int) Math.floor(Double.parseDouble(parts[1]))
				);
			}
			if (parts.length >= 3) {
				return new Parsed(
					(int) Math.floor(Double.parseDouble(parts[0])),
					(int) Math.floor(Double.parseDouble(parts[1])),
					(int) Math.floor(Double.parseDouble(parts[2]))
				);
			}
		} catch (NumberFormatException ignored) {}
		return null;
	}
}
