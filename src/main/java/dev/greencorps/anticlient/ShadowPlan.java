package dev.greencorps.anticlient;

/** What a future actuator would do. In this build it is only logged and shown. */
public record ShadowPlan(Action action, int fireInTicks, String note) {
	public enum Action {
		NONE, WIND_CLUTCH, STRING_JAM, SNOWBALL_STEAL, PEARL_AWAY, SAFETY_LOG
	}

	public static final ShadowPlan NONE = new ShadowPlan(Action.NONE, 0, "no action");
}
