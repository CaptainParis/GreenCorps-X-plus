package dev.greencorps.util;

import meteordevelopment.meteorclient.utils.world.TickRate;

import java.util.ArrayDeque;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Sliding-window count of inventory clicks sent to the server (yours, Meteor's, every mod's). 2b2t kicks for
 * more than 80 clicks within 80 <i>server</i> ticks, and its TPS is often well under 20, so the window is kept in
 * real time and stretched by the server's tick rate. When a job sees the server correct our inventory, the
 * limit halves for a while and then climbs back.
 */
public final class ClickBudget {
	/** 10 under 2b2t's 80, with the window following the server's TPS. */
	public static final int DEFAULT_LIMIT = 70;
	public static final int DEFAULT_WINDOW_TICKS = 80;
	public static final int MIN_LIMIT = 20;
	private static final long BACKOFF_MS = 30_000;
	private static final int RECOVER_STEP = 5;

	private static final ArrayDeque<Long> SENT = new ArrayDeque<>();
	private static int baseLimit = DEFAULT_LIMIT;
	private static int windowTicks = DEFAULT_WINDOW_TICKS;
	private static boolean adaptToTps = true;
	private static int limit = DEFAULT_LIMIT;
	private static long backoffUntil;
	private static long lastRecover;

	private static LongSupplier clock = System::currentTimeMillis;
	private static DoubleSupplier serverTps = () -> TickRate.INSTANCE.getTickRate();

	private ClickBudget() {}

	public static void configure(int clicks, int window, boolean tps) {
		int base = Math.max(MIN_LIMIT, clicks);
		if (base != baseLimit) {
			baseLimit = base;
			limit = Math.min(limit, base);
			if (clock.getAsLong() >= backoffUntil) limit = base;
		}
		windowTicks = Math.max(1, window);
		adaptToTps = tps;
	}

	/** Current limit (lower than configured while backed off). */
	public static int limit() {
		expire();
		return limit;
	}

	public static boolean backedOff() {
		expire();
		return limit < baseLimit;
	}

	/** Window length in ms: {@code windowTicks} server ticks at the server's current tick rate. */
	public static long windowMs() {
		double tps = 20.0;
		if (adaptToTps) {
			double t = serverTps.getAsDouble();
			if (t > 0) tps = Math.max(5.0, Math.min(20.0, t));
		}
		return Math.round(windowTicks * 50.0 * 20.0 / tps);
	}

	/** Called for every click packet sent (mixin on {@code ClientPlayerInteractionManager.clickSlot}). */
	public static void record() {
		SENT.addLast(clock.getAsLong());
	}

	/** The server corrected our inventory: halve the limit for a while. */
	public static void backOff() {
		long now = clock.getAsLong();
		limit = Math.max(MIN_LIMIT, limit / 2);
		backoffUntil = now + BACKOFF_MS;
		lastRecover = now;
	}

	public static int remaining() {
		expire();
		return Math.max(0, limit - SENT.size());
	}

	public static boolean canSend(int clicks) {
		return remaining() >= clicks;
	}

	/** Rough client ticks until {@code clicks} more clicks have all gone out at the limit. */
	public static int etaTicks(int clicks) {
		int now = remaining();
		if (clicks <= now) return 0;
		long window = windowMs();
		int over = clicks - now;
		int windows = (over + limit - 1) / limit;
		Long oldest = SENT.peekFirst();
		long firstFree = oldest == null ? 0 : Math.max(0, oldest + window - clock.getAsLong());
		return (int) ((firstFree + (windows - 1) * window + 49) / 50);
	}

	private static void expire() {
		long now = clock.getAsLong();
		long window = windowMs();
		while (!SENT.isEmpty() && SENT.peekFirst() <= now - window) SENT.removeFirst();
		if (limit < baseLimit && now >= backoffUntil && now - lastRecover >= window) {
			limit = Math.min(baseLimit, limit + RECOVER_STEP);
			lastRecover = now;
		}
	}

	/** Test hook: empty window, fixed clock and TPS. */
	static void resetForTest(LongSupplier testClock, DoubleSupplier testTps) {
		SENT.clear();
		clock = testClock;
		serverTps = testTps;
		baseLimit = DEFAULT_LIMIT;
		limit = DEFAULT_LIMIT;
		windowTicks = DEFAULT_WINDOW_TICKS;
		adaptToTps = true;
		backoffUntil = 0;
		lastRecover = 0;
	}
}
