package dev.greencorps.anticlient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded list of measurements with simple percentiles. */
public final class Series {
	private static final int CAP = 2000;
	private final List<Double> values = new ArrayList<>();
	private long total;

	public void add(double v) {
		if (!Double.isFinite(v)) return;
		if (values.size() >= CAP) values.remove(0);
		values.add(v);
		total++;
	}

	public int count() {
		return values.size();
	}

	public long totalAdded() {
		return total;
	}

	public double mean() {
		if (values.isEmpty()) return Double.NaN;
		double s = 0;
		for (double v : values) s += v;
		return s / values.size();
	}

	public double min() {
		return values.isEmpty() ? Double.NaN : Collections.min(values);
	}

	public double max() {
		return values.isEmpty() ? Double.NaN : Collections.max(values);
	}

	/** Nearest-rank percentile, p in 0..100. */
	public double percentile(double p) {
		if (values.isEmpty()) return Double.NaN;
		List<Double> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		int rank = (int) Math.ceil(p / 100.0 * sorted.size());
		return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
	}

	public void clear() {
		values.clear();
		total = 0;
	}

	public String describe() {
		if (values.isEmpty()) return "n=0";
		return String.format(java.util.Locale.ROOT, "n=%d min=%.1f p50=%.1f p95=%.1f max=%.1f",
			values.size(), min(), percentile(50), percentile(95), max());
	}
}
