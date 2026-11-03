package dev.greencorps.anticlient;

/** Own latency history. The lead used for prediction is the p95, not the average. */
public final class PingTracker {
	private final Series samples = new Series();
	private int last = -1;

	public void add(int ms) {
		if (ms < 0) return;
		last = ms;
		samples.add(ms);
	}

	public int lastMs() {
		return last;
	}

	public double p50() {
		return samples.count() == 0 ? Double.NaN : samples.percentile(50);
	}

	public double p95() {
		return samples.count() == 0 ? Double.NaN : samples.percentile(95);
	}

	/** Round-trip lead in game ticks, clamped to 1..10. Falls back to 2 with no samples. */
	public int leadTicks(int extra) {
		double p = p95();
		int base = Double.isNaN(p) ? 2 : (int) Math.ceil(p / 50.0);
		return Math.max(1, Math.min(10, base + extra));
	}

	public void clear() {
		samples.clear();
		last = -1;
	}
}
