package dev.greencorps.util;

/**
 * Rolling density histogram (bins of 10) with n / max / percentiles via CDF.
 */
public final class DensityStats {
	private final int[] bins = new int[10];
	private int n;
	private int max = -1;

	public void record(int density) {
		int d = Math.max(0, Math.min(100, density));
		bins[Math.min(9, d / 10)]++;
		n++;
		if (d > max) max = d;
	}

	public void clear() {
		for (int i = 0; i < bins.length; i++) bins[i] = 0;
		n = 0;
		max = -1;
	}

	public int n() {
		return n;
	}

	public int max() {
		return max;
	}

	/** Percentile 0–100 from bin CDF; returns -1 if empty. */
	public int percentile(int pct) {
		if (n <= 0) return -1;
		int target = (int) Math.ceil(n * (pct / 100.0));
		if (target < 1) target = 1;
		int cum = 0;
		for (int i = 0; i < bins.length; i++) {
			cum += bins[i];
			if (cum >= target) {
				// midpoint of bin; last bin is 90–100
				return i * 10 + 5;
			}
		}
		return 100;
	}

	public int bin(int index) {
		if (index < 0 || index >= bins.length) return 0;
		return bins[index];
	}

	/** Share of samples in bins starting at {@code fromBin} (0–9), as percent of n. */
	public int shareFromBinPct(int fromBin) {
		if (n <= 0) return 0;
		int sum = 0;
		for (int i = Math.max(0, fromBin); i < bins.length; i++) sum += bins[i];
		return (int) Math.round(100.0 * sum / n);
	}

	public String highBinsSummary() {
		if (n <= 0) return "—";
		// Avoid '%' — Module.info() runs String.format and treats % as format flags.
		return "70s:" + shareFromBinPct(7) + "pct 80s:" + shareBinPct(8) + "pct 90s:" + shareBinPct(9) + "pct";
	}

	private int shareBinPct(int bin) {
		if (n <= 0) return 0;
		return (int) Math.round(100.0 * bins[bin] / n);
	}
}
