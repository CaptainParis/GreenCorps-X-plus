package dev.greencorps.anticlient;

import java.util.Map;

/**
 * Result of scoring one tracked player. {@code etaTicks} is the projected number of
 * ticks until a damaging strike from now, or -1 when none is projected.
 */
public record ThreatAssessment(
	int playerId,
	int score,
	ThreatLevel level,
	Archetype archetype,
	int etaTicks,
	double minDistance,
	double fallAtImpact,
	double maceDamage,
	double crystalDamage,
	int crystalsNear,
	Map<String, Integer> parts
) {
	public static ThreatAssessment idle(int playerId) {
		return new ThreatAssessment(playerId, 0, ThreatLevel.IDLE, Archetype.NONE, -1,
			Double.MAX_VALUE, 0, 0, 0, 0, Map.of());
	}

	/** Worst single-burst estimate for this assessment, after armor and protection. */
	public double burstDamage() {
		return Math.max(maceDamage, crystalDamage);
	}
}
