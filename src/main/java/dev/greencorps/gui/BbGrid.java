package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import net.minecraft.client.MinecraftClient;

/** Shared Base Backup module layout metrics (3-col grid + 5-col Current Home row). */
public final class BbGrid {
	/** Longest labels in the 3×2 action grid — drives readable cell width. */
	public static final String[] ACTION_LABELS = {
		"Add Home (Player Position)",
		"Add Home (Coordinates)",
		"Add Home (Xaero Waypoint)",
		"Snapshot (Player Position)",
		"(0 Waypoints In Range To Add As Home)",
		"Manage Homes & Backups",
		"Inside Home - Snapshot N/A"
	};

	private BbGrid() {}

	public static double gap(GuiTheme theme) {
		return theme.scale(6);
	}

	/**
	 * One cell of the 3×2 action grid — readable floor only.
	 * Tables use expandX so cells grow to fill the module window width.
	 * Cap keeps Time Machine narrower than a full-width Meteor module window.
	 */
	public static double cellW(GuiTheme theme) {
		MinecraftClient mc = MinecraftClient.getInstance();
		double sw = mc != null ? mc.getWindow().getScaledWidth() : 600;
		double labelBased = 0;
		for (String s : ACTION_LABELS) {
			labelBased = Math.max(labelBased, theme.textWidth(s));
		}
		labelBased += theme.pad() * 2;
		double lo = sw * 0.10;
		double raw = Math.max(lo, labelBased);
		double cap = Math.min(sw * 0.14, theme.scale(118));
		return Math.min(raw, Math.max(cap, theme.scale(96)));
	}

	/** Preferred max width for the Time Machine manage window. */
	public static double windowMaxW(GuiTheme theme) {
		MinecraftClient mc = MinecraftClient.getInstance();
		double sw = mc != null ? mc.getWindow().getScaledWidth() : 600;
		return Math.min(sw * 0.52, gridW(theme) + theme.scale(160));
	}

	public static double gridW(GuiTheme theme) {
		return 3 * cellW(theme) + 2 * gap(theme);
	}

	/** One cell of the Current Home 5-pack (sums to {@link #gridW}). */
	public static double fifthW(GuiTheme theme) {
		return (gridW(theme) - 4 * gap(theme)) / 5.0;
	}

	/** Settings label column: max title width across Base Backup settings (+ pad). */
	public static double labelColW(GuiTheme theme, Iterable<String> settingTitles) {
		double max = cellW(theme);
		if (settingTitles != null) {
			for (String t : settingTitles) {
				if (t == null) continue;
				max = Math.max(max, theme.textWidth(t) + theme.pad() * 2);
			}
		}
		return max;
	}

	/** Apply equal spacing to a table used as a button matrix. */
	public static void applySpacing(WTable table, GuiTheme theme) {
		double g = gap(theme);
		table.horizontalSpacing = g;
		table.verticalSpacing = g;
	}
}
