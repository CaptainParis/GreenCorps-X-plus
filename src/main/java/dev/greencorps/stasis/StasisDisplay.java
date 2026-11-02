package dev.greencorps.stasis;

import java.text.Normalizer;

/**
 * Display-only sanitizers for Stasis UI (store keeps original names).
 * Handles font-missing glyphs like en-dash in home titles.
 */
public final class StasisDisplay {
	private StasisDisplay() {}

	/** Section title safe for Meteor fonts: NFC, ASCII dashes/spaces, strip bidi/ZW. */
	public static String sanitizeTitle(String raw) {
		if (raw == null || raw.isEmpty()) return "";
		String s = Normalizer.normalize(raw, Normalizer.Form.NFC);
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == 0x2013 || cp == 0x2014 || cp == 0x2212) { // en/em/minus
				sb.append(" - ");
				continue;
			}
			if (cp == 0x00A0 || cp == 0x202F || cp == 0x2007) { // NBSP variants
				sb.append(' ');
				continue;
			}
			if (cp >= 0x200B && cp <= 0x200D) continue;
			if (cp >= 0x202A && cp <= 0x202E) continue;
			if (cp == 0xFEFF) continue;
			sb.appendCodePoint(cp);
		}
		return sb.toString().replaceAll(" {2,}", " ").trim();
	}
}
