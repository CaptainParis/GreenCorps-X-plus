package dev.greencorps.util;

/**
 * Plain-language density bands for solid-pocket UX.
 * Matches the Loose (78) / Balanced (88) / Strict (94) presets.
 */
public final class DensityBand {
	private DensityBand() {
	}

	public static String label(int density) {
		if (density < 0) return "—";
		if (density < 78) return "sparse";
		if (density < 88) return "usable";
		if (density < 94) return "tight";
		return "solid";
	}

	public static String info(int density) {
		if (density < 0) return "—";
		return density + " " + label(density);
	}
}
