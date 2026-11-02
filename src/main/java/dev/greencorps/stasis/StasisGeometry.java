package dev.greencorps.stasis;

/**
 * Pure predicates / kind mapping for stasis chamber geometry (no Minecraft types).
 * World scanning lives in {@link ChamberScanner}.
 */
public final class StasisGeometry {
	public enum Match {
		NONE,
		WATER,
		HONEY
	}

	private StasisGeometry() {}

	/**
	 * @param waterAtPearl true if pearl is in a valid soulsand + source-water column
	 * @param honeyBeside true if pearl is in a valid upward-piston honey seat
	 */
	public static Match match(boolean waterAtPearl, boolean honeyBeside) {
		if (waterAtPearl) return Match.WATER;
		if (honeyBeside) return Match.HONEY;
		return Match.NONE;
	}

	public static boolean isInstalled(Match m) {
		return m == Match.WATER || m == Match.HONEY;
	}

	public static String chamberKind(Match m) {
		return switch (m) {
			case WATER -> ChamberKind.BUBBLE_COLUMN;
			case HONEY -> ChamberKind.SUSPENDED;
			default -> ChamberKind.UNKNOWN;
		};
	}

	public static String slotKind(Match m) {
		return switch (m) {
			case WATER -> StasisChamberSlot.KIND_WATER;
			case HONEY -> StasisChamberSlot.KIND_HONEY;
			default -> null;
		};
	}
}
