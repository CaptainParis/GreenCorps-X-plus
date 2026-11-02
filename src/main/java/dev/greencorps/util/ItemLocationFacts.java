package dev.greencorps.util;

import java.util.Locale;

/**
 * Plain-string formatting for location lines appended to existing item tooltips.
 */
public final class ItemLocationFacts {
	private ItemLocationFacts() {}

	public static String dimShort(String id) {
		if (id == null || id.isEmpty()) return "unknown";
		int colon = id.indexOf(':');
		String path = colon >= 0 ? id.substring(colon + 1) : id;
		return switch (path) {
			case "overworld" -> "overworld";
			case "the_nether" -> "nether";
			case "the_end" -> "end";
			default -> path.replace('_', ' ');
		};
	}

	public static String xyz(int x, int y, int z) {
		return x + " " + y + " " + z;
	}

	public static String xz(int x, int z) {
		return x + ", " + z;
	}

	public static String distance(int blocks) {
		int n = Math.max(0, blocks);
		if (n >= 10000) return (n / 1000) + "k";
		if (n >= 1000) return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
		return n + " blocks";
	}

	public static String lodestone(String dimension, int x, int y, int z, boolean tracked) {
		String line = "Lodestone: " + dimShort(dimension) + " " + xyz(x, y, z);
		return tracked ? line : line + " (untracked)";
	}

	public static String lodestoneSpinning() {
		return "Lodestone: no target (spinning)";
	}

	public static String mapDecoration(String typePath, int x, int z) {
		return "Map: " + decorationLabel(typePath) + " " + xz(x, z) + " — stored on the item";
	}

	/** Learned Map Duper view — not NBT on the stack; never used for slot tint. */
	public static String mapLearnedView(int mapId, int x, int z, String dimension) {
		return "Map #" + mapId + ": view (learned on-canvas) ~" + xz(x, z)
			+ " (" + dimShort(dimension) + ") — not stored on the item";
	}

	/** Filled map with no durable decoration and no learned center. */
	public static String mapCanvasOnly(int mapId) {
		return "Map #" + mapId + ": canvas only — no coords on the item";
	}

	public static String beesNone() {
		return "Bees: none — no coordinates";
	}

	public static String beesNoCoordinates(int count) {
		return "Bees: " + count + " — no coordinates";
	}

	public static String beeHive(int index, int x, int y, int z) {
		return "Bee " + index + " hive: " + xyz(x, y, z);
	}

	public static String beeFlower(int index, int x, int y, int z) {
		return "Bee " + index + " flower: " + xyz(x, y, z);
	}

	public static String origin(String dimension, int x, int y, int z) {
		if (dimension == null || dimension.isEmpty()) return "Origin: " + xyz(x, y, z);
		return "Origin: " + dimShort(dimension) + " " + xyz(x, y, z);
	}

	public static String exitPortal(int x, int y, int z) {
		return "Exit portal: " + xyz(x, y, z);
	}

	public static String more(int extra) {
		return "+" + extra + " more";
	}

	public static String prefixed(String prefix, String line) {
		if (prefix == null || prefix.isEmpty()) return line;
		return prefix + " · " + line;
	}

	public static boolean isTransientMapMark(String typePath) {
		if (typePath == null) return true;
		return switch (typePath) {
			case "player", "frame", "red_marker", "blue_marker", "target_x",
				"target_point", "player_off_map", "player_off_limits" -> true;
			default -> false;
		};
	}

	public static String decorationLabel(String typePath) {
		if (typePath == null || typePath.isEmpty()) return "Marker";
		String path = typePath;
		int colon = path.indexOf(':');
		if (colon >= 0) path = path.substring(colon + 1);
		return switch (path) {
			case "monument" -> "Ocean Monument";
			case "mansion" -> "Woodland Mansion";
			case "trial_chambers" -> "Trial Chambers";
			case "red_x" -> "Buried Treasure";
			case "jungle_temple" -> "Jungle Temple";
			case "swamp_hut" -> "Swamp Hut";
			case "village_desert" -> "Desert Village";
			case "village_plains" -> "Plains Village";
			case "village_savanna" -> "Savanna Village";
			case "village_snowy" -> "Snowy Village";
			case "village_taiga" -> "Taiga Village";
			default -> title(path);
		};
	}

	static String title(String path) {
		String[] parts = path.replace('-', '_').split("_");
		StringBuilder out = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
			if (part.length() > 1) out.append(part.substring(1));
		}
		return out.isEmpty() ? path : out.toString();
	}
}
