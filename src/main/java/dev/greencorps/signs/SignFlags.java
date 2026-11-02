package dev.greencorps.signs;

/**
 * Flag bits stored on each sign in the pack. Names match the search chips and {@code flag:} queries.
 */
public final class SignFlags {
	public static final int SIGNATURE = 1 << 0;
	public static final int DATED = 1 << 1;
	public static final int DEFENSE = 1 << 2;
	public static final int ABANDONED = 1 << 3;
	public static final int FUNCTIONAL = 1 << 4;
	public static final int ECONOMY = 1 << 5;
	public static final int COORD = 1 << 6;
	public static final int OFFENSIVE = 1 << 7;
	public static final int COLORED = 1 << 8;
	public static final int LEGACY = 1 << 9;

	private SignFlags() {
	}

	public static boolean has(int flags, int bit) {
		return (flags & bit) != 0;
	}

	/** Flag bit for a chip or {@code flag:} value; 0 if unknown. */
	public static int byName(String name) {
		if (name == null) return 0;
		return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "signature", "signed", "author" -> SIGNATURE;
			case "dated", "date" -> DATED;
			case "defense", "defence", "warning" -> DEFENSE;
			case "abandoned", "looted", "griefed" -> ABANDONED;
			case "functional", "function", "base" -> FUNCTIONAL;
			case "economy", "shop", "trade" -> ECONOMY;
			case "coord", "coords", "tp" -> COORD;
			case "offensive", "slur" -> OFFENSIVE;
			case "colored", "coloured", "dyed" -> COLORED;
			case "legacy", "1.12", "old" -> LEGACY;
			default -> 0;
		};
	}

	public static String label(int bit) {
		return switch (bit) {
			case SIGNATURE -> "Signed";
			case DATED -> "Dated";
			case DEFENSE -> "Defense";
			case ABANDONED -> "Abandoned";
			case FUNCTIONAL -> "Functional";
			case ECONOMY -> "Economy";
			case COORD -> "Coords";
			case OFFENSIVE -> "Offensive";
			case COLORED -> "Colored";
			case LEGACY -> "Legacy";
			default -> "?";
		};
	}
}
