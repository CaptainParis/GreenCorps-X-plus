package dev.greencorps.anticlient;

/**
 * Picks the counter a future actuator would use. Pure and deterministic so the shadow
 * decisions can be compared with what really happened.
 */
public final class ShadowPlanner {
	private ShadowPlanner() {}

	public static ShadowPlan plan(ThreatAssessment a, Inventory inv, int rttTicks, SurvivalVerdict v) {
		if (!a.level().atLeast(ThreatLevel.COMMIT)) return ShadowPlan.NONE;
		if (v != null && v.doomed() && inv.pearls() == 0) {
			return new ShadowPlan(ShadowPlan.Action.SAFETY_LOG, 0, "doomed, no escape tool");
		}
		int fire = Math.max(0, a.etaTicks() - rttTicks - 1);
		if (a.archetype() == Archetype.MACE) {
			if (inv.windCharges() > 0) {
				return new ShadowPlan(ShadowPlan.Action.WIND_CLUTCH, fire, "wind charge before smash");
			}
			if (inv.pearls() > 0) return new ShadowPlan(ShadowPlan.Action.PEARL_AWAY, fire, "pearl out of reach");
			return new ShadowPlan(ShadowPlan.Action.NONE, 0, "mace dive, no tools");
		}
		if (a.archetype() == Archetype.CRYSTAL) {
			if (inv.snowballs() > 0 && a.crystalsNear() > 0) {
				return new ShadowPlan(ShadowPlan.Action.SNOWBALL_STEAL, 0, "pop crystal on their side");
			}
			if (inv.string() > 0) return new ShadowPlan(ShadowPlan.Action.STRING_JAM, 0, "deny placement");
			if (inv.pearls() > 0) return new ShadowPlan(ShadowPlan.Action.PEARL_AWAY, 0, "pearl out of crystal range");
		}
		return new ShadowPlan(ShadowPlan.Action.NONE, 0, "no tools");
	}
}
