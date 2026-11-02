package dev.greencorps.stasis;

/** Human-readable chamber style for alerts. Detection uses nearby blocks only as wording. */
public final class ChamberKind {
	public static final String BUBBLE_COLUMN = "bubble_column";
	public static final String TRAPDOOR = "trapdoor";
	public static final String SUSPENDED = "suspended";
	public static final String UNKNOWN = "unknown";

	private ChamberKind() {}

	public static String label(String kind) {
		if (kind == null) return "chamber";
		return switch (kind) {
			case BUBBLE_COLUMN -> "bubble column";
			case TRAPDOOR -> "trapdoor";
			case SUSPENDED -> "suspended";
			default -> "chamber";
		};
	}
}
