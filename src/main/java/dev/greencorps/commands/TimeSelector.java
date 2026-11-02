package dev.greencorps.commands;

import dev.greencorps.backup.SnapshotIndex;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses latest / #12 / -3d / ISO timestamps to a snapshot entry. */
public final class TimeSelector {
	private static final Pattern RELATIVE = Pattern.compile("^-(\\d+)([smhdw])$", Pattern.CASE_INSENSITIVE);
	private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	private TimeSelector() {}

	public static Optional<SnapshotIndex.Entry> resolve(SnapshotIndex idx, String when) {
		if (when == null || when.isBlank() || when.equalsIgnoreCase("latest")) {
			return idx.latest();
		}
		String w = when.trim();
		if (w.startsWith("#")) {
			try {
				int n = Integer.parseInt(w.substring(1));
				return idx.byIndex(n);
			} catch (NumberFormatException e) {
				return Optional.empty();
			}
		}
		Matcher m = RELATIVE.matcher(w);
		if (m.matches()) {
			long amount = Long.parseLong(m.group(1));
			char unit = Character.toLowerCase(m.group(2).charAt(0));
			long ms = switch (unit) {
				case 's' -> amount * 1000L;
				case 'm' -> amount * 60_000L;
				case 'h' -> amount * 3_600_000L;
				case 'd' -> amount * 86_400_000L;
				case 'w' -> amount * 604_800_000L;
				default -> amount;
			};
			return idx.atOrBefore(System.currentTimeMillis() - ms);
		}
		try {
			Instant instant = Instant.parse(w);
			return idx.atOrBefore(instant.toEpochMilli());
		} catch (DateTimeParseException ignored) {}
		try {
			LocalDateTime ldt = LocalDateTime.parse(w, ISO_LOCAL);
			long epoch = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
			return idx.atOrBefore(epoch);
		} catch (DateTimeParseException ignored) {}
		return Optional.empty();
	}

	public static String format(long epochMs) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
			.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT));
	}

	public static String describe(List<SnapshotIndex.Entry> entries, SnapshotIndex.Entry e) {
		int idx = entries.indexOf(e) + 1;
		return "#" + idx + " " + format(e.epochMs);
	}
}
