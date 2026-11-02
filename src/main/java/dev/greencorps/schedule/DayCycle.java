package dev.greencorps.schedule;

import meteordevelopment.meteorclient.gui.widgets.WLabel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

import java.util.Locale;
import java.util.Optional;

/**
 * Minecraft day-cycle helpers. Tick 0 = 06:00, 1000 ticks = 1 in-game hour,
 * cycle length 24000. Sunrise/sunset anchors match vanilla dawn/dusk starts.
 */
public final class DayCycle {
	public static final int CYCLE = 24_000;
	/** Dawn starts (~05:00). */
	public static final int SUNRISE = 23_000;
	/** Dusk starts (18:00). */
	public static final int SUNSET = 12_000;
	public static final int DEFAULT_CATCH_UP = 40;

	private DayCycle() {}

	public static int wrap(long tick) {
		long m = tick % CYCLE;
		if (m < 0) m += CYCLE;
		return (int) m;
	}

	/** Absolute day index from {@code World#getTimeOfDay()} (not modulo). */
	public static long dayIndex(long timeOfDay) {
		return Math.floorDiv(timeOfDay, CYCLE);
	}

	public static int resolveSunrise(int offsetTicks) {
		return wrap((long) SUNRISE + offsetTicks);
	}

	public static int resolveSunset(int offsetTicks) {
		return wrap((long) SUNSET + offsetTicks);
	}

	/**
	 * Format day-cycle tick as Minecraft clock {@code HH:MM}
	 * (tick 0 → 06:00).
	 */
	public static String formatClock(int dayTick) {
		int t = wrap(dayTick);
		int totalMinutes = (t * 60) / 1000 + 6 * 60;
		int h = (totalMinutes / 60) % 24;
		int m = totalMinutes % 60;
		return String.format(Locale.ROOT, "%02d:%02d", h, m);
	}

	/** Strip grouping commas / spaces / underscores from a numeric tick string. */
	public static String stripGroupedDigits(String raw) {
		if (raw == null) return "";
		return raw.trim().replace(",", "").replace("_", "").replace(" ", "");
	}

	/**
	 * Format a day-cycle tick with a thousands separator ({@code 23460} → {@code 23,460}).
	 */
	public static String formatDayTick(int dayTick) {
		int t = wrap(dayTick);
		return String.format(Locale.ROOT, "%,d", t);
	}

	/**
	 * Parse an exact day-cycle tick 0–23999 ({@code 23460}, {@code 23,460}).
	 */
	public static Optional<Integer> parseDayTick(String raw) {
		String s = stripGroupedDigits(raw);
		if (s.isEmpty() || s.indexOf(':') >= 0) return Optional.empty();
		try {
			long v = Long.parseLong(s);
			if (v < 0 || v >= CYCLE) return Optional.empty();
			return Optional.of((int) v);
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/**
	 * Parse {@code HH:MM} (tick 0 = 06:00) or a day-cycle tick 0–23999 (commas OK).
	 */
	public static Optional<Integer> parseTime(String raw) {
		if (raw == null) return Optional.empty();
		String s = raw.trim();
		if (s.isEmpty()) return Optional.empty();
		if (s.indexOf(':') >= 0) {
			String[] parts = s.split(":");
			if (parts.length != 2) return Optional.empty();
			try {
				int h = Integer.parseInt(parts[0].trim());
				int m = Integer.parseInt(parts[1].trim());
				if (h < 0 || h > 23 || m < 0 || m > 59) return Optional.empty();
				// Minutes since 06:00, then to ticks.
				int minutesFromSix = ((h - 6 + 24) % 24) * 60 + m;
				return Optional.of((minutesFromSix * 1000) / 60);
			} catch (NumberFormatException e) {
				return Optional.empty();
			}
		}
		return parseDayTick(s);
	}

	/** Parse a non-negative long (world age), allowing commas. */
	public static Optional<Long> parseLongTick(String raw) {
		String s = stripGroupedDigits(raw);
		if (s.isEmpty()) return Optional.empty();
		try {
			long v = Long.parseLong(s);
			if (v < 0) return Optional.empty();
			return Optional.of(v);
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/**
	 * True when day-time moved forward from {@code prevTod} to {@code currTod}
	 * and crossed {@code target} within at most {@code catchUp} ticks.
	 * Sleep / {@code /time} jumps larger than {@code catchUp} do not fire.
	 * Does not fire when already sitting on the target at {@code prevTod}.
	 */
	public static boolean crossedDayTick(long prevTod, long currTod, int target, int catchUp) {
		if (catchUp < 1) return false;
		int prev = wrap(prevTod);
		int curr = wrap(currTod);
		int tgt = wrap(target);
		int delta = wrap((long) curr - prev);
		if (delta == 0 || delta > catchUp) return false;
		int distToTarget = wrap((long) tgt - prev);
		return distToTarget > 0 && distToTarget <= delta;
	}

	/**
	 * Once-shot world-age trigger: fire when age advances across {@code target}
	 * by at most {@code catchUp} ticks.
	 */
	public static boolean crossedWorldAge(long prevAge, long currAge, long target, int catchUp) {
		if (catchUp < 1) return false;
		if (currAge <= prevAge) return false;
		long delta = currAge - prevAge;
		if (delta > catchUp) return false;
		return prevAge < target && currAge >= target;
	}

	/**
	 * Interval trigger on world age: one fire when the period bucket advances,
	 * even after lag (no burst).
	 */
	public static boolean crossedEveryN(long prevAge, long currAge, int everyN) {
		if (everyN < 1) return false;
		if (currAge <= prevAge) return false;
		return Math.floorDiv(currAge, everyN) > Math.floorDiv(prevAge, everyN);
	}

	public static String previewLabel(int dayTick) {
		return "tick " + wrap(dayTick) + " (" + formatClock(dayTick) + ")";
	}

	/**
	 * Day-cycle tick to fire at when sending early by {@code leadTicks}
	 * (so the packet is in flight when the logical target is reached).
	 * Lead 0 returns the wrapped target.
	 */
	public static int leadTarget(int target, int leadTicks) {
		int lead = Math.max(0, leadTicks);
		return wrap((long) target - lead);
	}

	/** World-age target shifted earlier by lead (clamped so result is ≥ 0). */
	public static long leadWorldAge(long target, int leadTicks) {
		int lead = Math.max(0, leadTicks);
		return Math.max(0L, target - lead);
	}

	/** Tab-list latency → tick lead ({@code round(ms / 50)}, clamped). */
	public static int leadFromPingMs(int latencyMs, int maxLead) {
		if (latencyMs <= 0 || maxLead <= 0) return 0;
		int ticks = (int) Math.round(latencyMs / 50.0);
		return Math.min(maxLead, Math.max(0, ticks));
	}

	/** Live line for UI: {@code Now: 05:23  tick 23,460}. */
	public static String nowLine() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return nowLine(mc != null ? mc.world : null);
	}

	public static String nowLine(World world) {
		if (world == null) return "Now: not in a world";
		int dayTick = wrap(world.getTimeOfDay());
		String line = "Now: " + formatClock(dayTick) + "  tick " + formatDayTick(dayTick);
		if (world.getDimension().hasFixedTime()) line += "  (fixed time)";
		return line;
	}

	/** Update a label only when the text changes (avoids per-tick layout thrash that eats button clicks). */
	public static void updateNowLabel(WLabel label) {
		if (label == null) return;
		String line = nowLine();
		if (!line.equals(label.get())) label.set(line);
	}
}
