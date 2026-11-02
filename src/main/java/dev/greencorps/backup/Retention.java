package dev.greencorps.backup;

/** One day, in milliseconds. Callers scale this for 7-day and 30-day windows. */
public final class Retention {
	public static final long D = 86_400_000L;

	private Retention() {}
}
