package dev.greencorps.anticlient;

import java.util.List;

/**
 * {@code marginTicks} is time-to-death minus the time we need to break the fight.
 * Negative margin under an active threat is DOOMED.
 */
public record SurvivalVerdict(
	Kind kind,
	int lives,
	int burstsToDeath,
	int timeToDeathTicks,
	int marginTicks,
	List<String> flags
) {
	public enum Kind { OK, DEGRADED, DOOMED }

	public boolean doomed() {
		return kind == Kind.DOOMED;
	}
}
