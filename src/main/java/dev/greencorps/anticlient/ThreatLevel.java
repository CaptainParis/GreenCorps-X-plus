package dev.greencorps.anticlient;

/** How worried the model is about one tracked player. Ordered low to high. */
public enum ThreatLevel {
	IDLE, WATCH, ARMED, COMMIT;

	public boolean atLeast(ThreatLevel other) {
		return ordinal() >= other.ordinal();
	}
}
