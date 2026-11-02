package dev.greencorps.gui.tooltip;

/**
 * Bundle tooltip grid: rows of 8 (a full bundle is 8×8), narrower only while fewer than 8 stacks. Cells run in
 * reading order from the top-left, so the last row is left-aligned.
 */
public final class BundleGrid {
	public static final int MAX_COLS = 8;

	private BundleGrid() {}

	public static int columns(int stacks) {
		return Math.max(1, Math.min(MAX_COLS, stacks));
	}

	public static int rows(int stacks) {
		int c = columns(stacks);
		return Math.max(1, (stacks + c - 1) / c);
	}

	public static int col(int index, int stacks) {
		return index % columns(stacks);
	}

	public static int row(int index, int stacks) {
		return index / columns(stacks);
	}

	/** Bar label: total items (64 per bundle), percent and stack count, or Full. */
	public static String fillLabel(float occupancy, int stacks) {
		int items = Math.round(occupancy * 64);
		String st = stacks + (stacks == 1 ? " stack" : " stacks");
		if (occupancy >= 1f) return "Full · 64/64 · " + st;
		return items + "/64 (" + Math.round(occupancy * 100) + "%) · " + st;
	}
}
