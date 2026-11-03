package dev.greencorps.anticlient;

/** Immutable view of the module state for the HUD, published once per tick. */
public record HudSnapshot(
	boolean active,
	String scenario,
	ThreatLevel level,
	Archetype archetype,
	String topName,
	int score,
	int etaTicks,
	ShadowPlan plan,
	SurvivalVerdict.Kind survival,
	int lives,
	int marginTicks,
	String flags,
	int pingMs,
	int leadTicks,
	int tp,
	int fp,
	int miss
) {
	public static final HudSnapshot OFF = new HudSnapshot(false, "none", ThreatLevel.IDLE, Archetype.NONE, "",
		0, -1, ShadowPlan.NONE, SurvivalVerdict.Kind.OK, 0, 0, "", -1, 0, 0, 0, 0);
}
