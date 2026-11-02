package dev.greencorps.backup;

/** Store health. {@link #HEALTHY} is the green chat/HUD case. */
public enum StoreHealth {
	HEALTHY,
	DEGRADED;

	public String label(int degraded) {
		if (this == HEALTHY && degraded <= 0) return "Healthy";
		if (degraded > 0) return "Degraded (" + degraded + ")";
		return "Degraded";
	}
}
