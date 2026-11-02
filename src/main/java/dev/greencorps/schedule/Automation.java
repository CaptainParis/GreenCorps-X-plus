package dev.greencorps.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One saved timed-command automation. */
public final class Automation {
	public enum Trigger {
		TIME_OF_DAY("Time of day"),
		DAY_TICK("Exact day tick"),
		WORLD_TICK("World tick"),
		EVERY_N_TICKS("Every N ticks"),
		SUNRISE("Sunrise"),
		SUNSET("Sunset");

		private final String label;

		Trigger(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}

		public boolean isDayCycle() {
			return this == TIME_OF_DAY || this == DAY_TICK || this == SUNRISE || this == SUNSET;
		}
	}

	public String id = UUID.randomUUID().toString();
	public String name = "Untitled";
	public boolean enabled = true;
	public Trigger trigger = Trigger.TIME_OF_DAY;
	/** Day-cycle tick 0–23999, or world-age target for WORLD_TICK. */
	public long timeValue;
	/** Sunrise/sunset offset in ticks (signed). Also used as interval for EVERY_N_TICKS. */
	public int offsetTicks;
	/** Clock string when trigger is TIME_OF_DAY (optional display; timeValue is source of truth). */
	public String clock = "06:00";
	public List<String> commands = new ArrayList<>();
	/** Last Minecraft day index that fired a day-cycle trigger (-1 = never). */
	public long lastFiredDay = -1;
	/** Resolved day tick last used for day-cycle fire bookkeeping. */
	public int lastFiredResolvedTick = -1;
	/** World age at last WORLD_TICK / EVERY_N fire (-1 = never). */
	public long lastFiredWorldAge = -1;

	public Automation copy() {
		Automation a = new Automation();
		a.id = UUID.randomUUID().toString();
		a.name = name + " copy";
		a.enabled = enabled;
		a.trigger = trigger;
		a.timeValue = timeValue;
		a.offsetTicks = offsetTicks;
		a.clock = clock;
		a.commands = new ArrayList<>(commands);
		a.lastFiredDay = -1;
		a.lastFiredResolvedTick = -1;
		a.lastFiredWorldAge = -1;
		return a;
	}

	/** Resolved day-cycle tick for TIME_OF_DAY / DAY_TICK / SUNRISE / SUNSET. */
	public int resolvedDayTick() {
		return switch (trigger) {
			case SUNRISE -> DayCycle.resolveSunrise(offsetTicks);
			case SUNSET -> DayCycle.resolveSunset(offsetTicks);
			case TIME_OF_DAY, DAY_TICK -> DayCycle.wrap(timeValue);
			default -> DayCycle.wrap(timeValue);
		};
	}

	public void clearFireMarks() {
		lastFiredDay = -1;
		lastFiredResolvedTick = -1;
		lastFiredWorldAge = -1;
	}

	public String summaryLine() {
		String trig = switch (trigger) {
			case TIME_OF_DAY -> DayCycle.previewLabel(resolvedDayTick());
			case DAY_TICK -> "tick " + DayCycle.formatDayTick(resolvedDayTick())
				+ " (" + DayCycle.formatClock(resolvedDayTick()) + ")";
			case SUNRISE -> "sunrise " + signed(offsetTicks) + " → " + DayCycle.previewLabel(resolvedDayTick());
			case SUNSET -> "sunset " + signed(offsetTicks) + " → " + DayCycle.previewLabel(resolvedDayTick());
			case WORLD_TICK -> "age " + timeValue;
			case EVERY_N_TICKS -> "every " + Math.max(1, offsetTicks) + " ticks";
		};
		int n = commands == null ? 0 : commands.size();
		return (enabled ? "" : "[off] ") + name + " — " + trig + " (" + n + " cmd)";
	}

	private static String signed(int v) {
		return v >= 0 ? "+" + v : Integer.toString(v);
	}
}
