package dev.greencorps.anticlient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores one tracked player against us. Every score part is named so telemetry can
 * show which signal fired and so weights can be tuned from real fights.
 */
public final class ThreatModel {
	private ThreatModel() {}

	public static ThreatAssessment evaluate(PlayerTrack t, SelfView me, List<CrystalView> crystals,
											ThreatConfig c, int tick) {
		Observation o = t.latest();
		if (o == null) return ThreatAssessment.idle(t.id);

		Map<String, Integer> parts = new LinkedHashMap<>();
		int score = 0;

		double[][] path = t.project(c.horizonTicks);
		double minDist = Double.MAX_VALUE;
		int eta = -1;
		for (int k = 0; k <= c.horizonTicks; k++) {
			double dx = path[k][0] - me.x();
			double dy = path[k][1] - me.y();
			double dz = path[k][2] - me.z();
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < minDist) minDist = d;
			if (eta < 0 && d <= c.reach) eta = k;
		}

		boolean mace = t.maceInHand();
		boolean maceMem = !mace && t.maceRecent(tick, c.equipmentMemoryTicks);
		boolean airborne = t.airborne() && !o.gliding();
		boolean descending = airborne && t.descending();
		double fall = t.fallDistance();
		double fallAtImpact = fall;
		if (eta >= 0 && t.airborne()) {
			fallAtImpact = Math.max(0, t.peakY() - path[eta][1]);
		}

		if (mace) score += put(parts, "mace_in_hand", 30);
		else if (maceMem) score += put(parts, "mace_recent", 12);
		if (t.windRecent(tick, c.equipmentMemoryTicks)) score += put(parts, "wind_charge_seen", 8);
		if (t.airborne() && !o.gliding()) score += put(parts, "airborne", 6);
		if (descending && fall >= c.minFall) {
			score += put(parts, "diving", 18);
			if (fall >= c.minFall + 4.0) score += put(parts, "big_fall", 6);
		}
		if ((mace || maceMem) && eta >= 0 && descending) {
			score += put(parts, "closing", 20);
			if (eta <= 12) score += put(parts, "close_eta", 8);
		}
		if (t.airborne() && t.chestSwapRecent(tick, c.chestSirenTicks)) {
			score += put(parts, "chest_swap_siren", 15);
		}
		if (o.gliding() && (mace || maceMem)) score += put(parts, "glide_with_mace", 4);

		double maceRaw = 0;
		if ((mace || maceMem) && fallAtImpact >= Damage.MACE_MIN_FALL) {
			maceRaw = Damage.maceHit(fallAtImpact, c.assumedDensity);
		}
		double maceDmg = reduce(maceRaw, c);

		int near = 0;
		double crystalRaw = 0;
		for (CrystalView cv : crystals) {
			double cdx = cv.x() - me.x(), cdy = cv.y() - me.y(), cdz = cv.z() - me.z();
			double dMe = Math.sqrt(cdx * cdx + cdy * cdy + cdz * cdz);
			if (dMe > c.crystalNear) continue;
			double tx = cv.x() - o.x(), ty = cv.y() - o.y(), tz = cv.z() - o.z();
			double dThem = Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (dThem > c.crystalNear) continue;
			near++;
			crystalRaw += Damage.crystalRaw(dMe, 1.0, c.difficultyMul);
		}
		if (near > c.maxCrystalsCounted) {
			crystalRaw = crystalRaw / near * c.maxCrystalsCounted;
		}
		double crystalDmg = reduce(crystalRaw, c);

		boolean crystalHold = t.crystalInHand();
		if (crystalHold) score += put(parts, "crystal_in_hand", 15);
		else if (t.crystalRecent(tick, c.equipmentMemoryTicks)) score += put(parts, "crystal_recent", 6);
		if (near > 0) {
			score += put(parts, "crystals_near", Math.min(36, 12 * Math.min(near, 3)));
			if (crystalDmg >= c.crystalCommitDamage) score += put(parts, "crystal_damage", 10);
		}
		if (near > 0 && minDist <= 10.0) score += put(parts, "within_10", 6);

		score = Math.min(100, score);

		Archetype arch = Archetype.NONE;
		if (maceDmg > 0 && maceDmg >= crystalDmg) arch = Archetype.MACE;
		else if (near > 0 || crystalHold) arch = Archetype.CRYSTAL;
		else if (mace || maceMem) arch = Archetype.MACE;

		ThreatLevel level = ThreatLevel.IDLE;
		if (score >= c.watchScore) level = ThreatLevel.WATCH;
		if (score >= c.armedScore) level = ThreatLevel.ARMED;
		boolean maceReady = score >= c.commitScore && arch == Archetype.MACE && mace && descending
			&& fall >= c.minFall && eta >= 0 && eta <= c.commitWindowTicks + me.rttTicks();
		boolean crystalReady = score >= c.watchScore && arch == Archetype.CRYSTAL && near > 0
			&& crystalDmg >= c.crystalCommitDamage;
		if (maceReady || crystalReady) level = ThreatLevel.COMMIT;

		int etaOut = eta;
		if (arch == Archetype.CRYSTAL && near > 0) etaOut = 0;
		return new ThreatAssessment(t.id, score, level, arch, etaOut, minDist, fallAtImpact,
			maceDmg, crystalDmg, near, parts);
	}

	private static double reduce(double raw, ThreatConfig c) {
		if (raw <= 0) return 0;
		return Damage.afterProtection(Damage.afterArmor(raw, c.assumedArmor, c.assumedToughness), c.assumedEpf);
	}

	private static int put(Map<String, Integer> parts, String key, int value) {
		parts.put(key, value);
		return value;
	}
}
