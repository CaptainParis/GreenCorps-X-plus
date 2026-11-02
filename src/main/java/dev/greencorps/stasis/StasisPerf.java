package dev.greencorps.stasis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in Stasis Monitor timing (setting {@code perf-log} / {@code .stasis perf}).
 * Off: {@link #start()} returns 0 and {@link #stop} is a single branch.
 */
public final class StasisPerf {
	private static final Logger LOG = LoggerFactory.getLogger("GreenCorps/StasisPerf");
	private static final long REPORT_MS = 5_000L;
	private static final long SPIKE_NS = 20_000_000L;
	private static final long SPIKE_LOG_COOLDOWN_MS = 10_000L;

	public enum Bucket {
		TICK("tick"),
		ENTITY_LOOP("tick.entities"),
		HEAL("tick.heal"),
		OWNER_RESOLVE("owner.resolve"),
		SIGN_PROBE("owner.signProbe"),
		STORE_SAVE("store.save"),
		/** Background thread: not frame time. */
		STORE_WRITE("store.write(bg)"),
		CHUNK_SCAN("chunk.scan"),
		DISCOVER("discover"),
		PERIODIC("tick.periodic"),
		HUB_SYNC("tick.hubSync"),
		FLUSH("tick.flush"),
		EMPTY_SCAN("tick.empty"),
		RENDER3D("render3d"),
		RENDER2D_LABELS("render2d.labels");

		final String label;

		Bucket(String label) {
			this.label = label;
		}
	}

	public enum Counter {
		SAVE_BYTES("saveBytes"),
		SCAN_BLOCKS("scanBlocks"),
		SCAN_QUEUE("scanQueue");

		final String label;

		Counter(String label) {
			this.label = label;
		}
	}

	private static volatile boolean enabled;

	private static final int NB = Bucket.values().length;
	private static final int NC = Counter.values().length;
	private static final long[] winNs = new long[NB];
	private static final long[] winCalls = new long[NB];
	private static final long[] winMaxNs = new long[NB];
	private static final long[] winCounters = new long[NC];
	private static final long[] tickNs = new long[NB];
	private static final java.util.HashMap<String, Long> winSaveCallers = new java.util.HashMap<>();
	private static final StackWalker WALKER = StackWalker.getInstance();
	private static long winStartMs;
	private static long lastSpikeLogMs;
	private static String lastReport = "no window yet";

	private StasisPerf() {}

	public static boolean enabled() {
		return enabled;
	}

	public static synchronized void setEnabled(boolean on) {
		if (on && !enabled) resetLocked(System.currentTimeMillis());
		enabled = on;
	}

	public static long start() {
		return enabled ? System.nanoTime() : 0L;
	}

	public static void stop(Bucket b, long t0) {
		if (t0 == 0L) return;
		record(b, System.nanoTime() - t0);
	}

	public static synchronized void record(Bucket b, long ns) {
		int i = b.ordinal();
		winNs[i] += ns;
		winCalls[i]++;
		if (ns > winMaxNs[i]) winMaxNs[i] = ns;
		tickNs[i] += ns;
	}

	/** Attribute a store mutation to the first caller outside the store (perf on only). */
	public static void countSaveCaller() {
		if (!enabled) return;
		String who = WALKER.walk(frames -> frames
			.filter(f -> !f.getClassName().startsWith("dev.greencorps.stasis.Stasis"))
			.findFirst()
			.map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
			.orElse("?"));
		synchronized (StasisPerf.class) {
			winSaveCallers.merge(who, 1L, Long::sum);
		}
	}

	public static void add(Counter c, long n) {
		if (!enabled) return;
		synchronized (StasisPerf.class) {
			winCounters[c.ordinal()] += n;
		}
	}

	/** Gauge-style counter (e.g. queue depth): keep the window max. */
	public static void gauge(Counter c, long n) {
		if (!enabled) return;
		synchronized (StasisPerf.class) {
			int i = c.ordinal();
			if (n > winCounters[i]) winCounters[i] = n;
		}
	}

	/** Close the tick: spike breakdown (rate-limited) and periodic window report. */
	public static synchronized void endTick(long tickT0) {
		if (tickT0 == 0L) return;
		long ns = System.nanoTime() - tickT0;
		record(Bucket.TICK, ns);
		long now = System.currentTimeMillis();
		// Frame cost: this tick (sub-buckets are nested inside it) plus render frames since
		// the previous tick. Background store writes are not frame time.
		long total = ns + tickNs[Bucket.RENDER3D.ordinal()] + tickNs[Bucket.RENDER2D_LABELS.ordinal()];
		if (total >= SPIKE_NS && now - lastSpikeLogMs >= SPIKE_LOG_COOLDOWN_MS) {
			lastSpikeLogMs = now;
			LOG.warn("[StasisPerf] spike {} ms: {}", fmtMs(total), tickBreakdown());
		}
		java.util.Arrays.fill(tickNs, 0L);
		if (now - winStartMs >= REPORT_MS) {
			lastReport = windowSummary(now);
			LOG.info("[StasisPerf] {}", lastReport);
			resetLocked(now);
		}
	}

	/** Last completed 5s window, or the live partial window if none closed yet. */
	public static synchronized String snapshot() {
		if (!enabled) return "perf off";
		long now = System.currentTimeMillis();
		if (now - winStartMs < 1000L) return lastReport;
		return windowSummary(now) + " (partial)";
	}

	public static synchronized void reset() {
		resetLocked(System.currentTimeMillis());
		lastReport = "no window yet";
	}

	private static void resetLocked(long now) {
		java.util.Arrays.fill(winNs, 0L);
		java.util.Arrays.fill(winCalls, 0L);
		java.util.Arrays.fill(winMaxNs, 0L);
		java.util.Arrays.fill(winCounters, 0L);
		winSaveCallers.clear();
		winStartMs = now;
	}

	private static String windowSummary(long now) {
		double secs = Math.max(0.001, (now - winStartMs) / 1000.0);
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(java.util.Locale.ROOT, "%.1fs:", secs));
		for (Bucket b : Bucket.values()) {
			long calls = winCalls[b.ordinal()];
			if (calls == 0) continue;
			double perSec = calls / secs;
			double msPerSec = winNs[b.ordinal()] / 1e6 / secs;
			sb.append(String.format(java.util.Locale.ROOT, " %s %.1f/s %.2fms/s max %sms;",
				b.label, perSec, msPerSec, fmtMs(winMaxNs[b.ordinal()])));
		}
		for (Counter c : Counter.values()) {
			long v = winCounters[c.ordinal()];
			if (v == 0) continue;
			sb.append(' ').append(c.label).append('=').append(v).append(';');
		}
		if (!winSaveCallers.isEmpty()) {
			sb.append(" saveCallers:");
			winSaveCallers.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.limit(4)
				.forEach(e -> sb.append(' ').append(e.getKey()).append('=')
					.append(String.format(java.util.Locale.ROOT, "%.0f/s", e.getValue() / secs)));
			sb.append(';');
		}
		return sb.toString();
	}

	private static String tickBreakdown() {
		StringBuilder sb = new StringBuilder();
		for (Bucket b : Bucket.values()) {
			long v = tickNs[b.ordinal()];
			if (v == 0) continue;
			if (sb.length() > 0) sb.append(", ");
			sb.append(b.label).append('=').append(fmtMs(v)).append("ms");
		}
		return sb.toString();
	}

	private static String fmtMs(long ns) {
		return String.format(java.util.Locale.ROOT, "%.2f", ns / 1e6);
	}
}
