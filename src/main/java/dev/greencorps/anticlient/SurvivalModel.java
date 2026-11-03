package dev.greencorps.anticlient;

import java.util.ArrayList;
import java.util.List;

/**
 * The hopelessness test: can we still outlast the incoming bursts long enough to
 * break the fight? Totems only count when the offhand can be refilled between bursts.
 */
public final class SurvivalModel {
	private SurvivalModel() {}

	public static SurvivalVerdict evaluate(SurvivalInput in, SurvivalConfig c) {
		List<String> flags = new ArrayList<>();
		double pool = in.hp() + in.absorption();
		int interval = in.archetype() == Archetype.CRYSTAL ? c.crystalIntervalTicks : c.maceIntervalTicks;

		boolean refillFast = in.autoTotemOn() && in.refillTicks() + in.rttTicks() < interval;
		int lives = in.offhandTotem() ? 1 : 0;
		if (in.inventoryTotems() > 0) {
			if (!in.autoTotemOn()) flags.add("AUTOTOTEM_OFF_WITH_SPARES");
			else if (!refillFast && in.archetype() != Archetype.NONE) flags.add("REFILL_SLOWER_THAN_BURSTS");
			if (refillFast || in.archetype() == Archetype.NONE) lives += in.inventoryTotems();
		}
		if (!in.offhandTotem()) flags.add("OFFHAND_EMPTY");
		if (in.offhandTotem() && in.inventoryTotems() == 0) flags.add("LAST_TOTEM");
		if (lives == 0) flags.add("NO_TOTEMS");
		if (pool <= c.lowHp) flags.add("LOW_HP");

		double burst = Math.max(1.0, in.burstDamage() * c.safetyFactor);
		int bursts0 = (int) Math.ceil(pool / burst);
		int perLife = (int) Math.ceil(Damage.POST_POP_HP / burst);
		int burstsToDeath = bursts0 + lives * perLife;

		boolean threat = in.level().atLeast(ThreatLevel.ARMED) && in.burstDamage() > 0;
		int firstBurst = Math.max(0, in.etaTicks());
		int timeToDeath = firstBurst + Math.max(0, burstsToDeath - 1) * interval;
		int needed = c.neutralizeTicks + in.rttTicks();
		int margin = timeToDeath - needed;

		SurvivalVerdict.Kind kind = SurvivalVerdict.Kind.OK;
		if (!threat) {
			if (lives == 0 || !flags.isEmpty()) kind = SurvivalVerdict.Kind.DEGRADED;
			return new SurvivalVerdict(kind, lives, burstsToDeath, timeToDeath, margin, flags);
		}
		if (margin < 0) kind = SurvivalVerdict.Kind.DOOMED;
		else if (margin < c.okMarginTicks || lives == 0) kind = SurvivalVerdict.Kind.DEGRADED;
		return new SurvivalVerdict(kind, lives, burstsToDeath, timeToDeath, margin, flags);
	}
}
