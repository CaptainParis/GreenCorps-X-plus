package dev.greencorps.anticlient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Everything measured in one session besides the commit scorecard. */
public final class SessionStats {
	public final Scorecard scorecard = new Scorecard();
	public final PingTracker ping = new PingTracker();
	public final Series crystalLifeTicks = new Series();
	public final Series totemRefillTicks = new Series();
	public final Series burstGapTicks = new Series();
	public final Series maceHitDamage = new Series();
	public final Series crystalHitDamage = new Series();
	public final Series crystalModelError = new Series();
	public final Series landingErrorBlocks = new Series();
	public final Series landingErrorTicks = new Series();
	public final Series selfKnockback = new Series();
	public final Series enemyPingMs = new Series();
	public int divesSeen;
	public int safetyDoomedTicks;
	public int safetyTriggers;
	public int shadowCommits;
	public long ticks;
	public int deaths;

	public void reset() {
		scorecard.reset();
		ping.clear();
		crystalLifeTicks.clear();
		totemRefillTicks.clear();
		burstGapTicks.clear();
		maceHitDamage.clear();
		crystalHitDamage.clear();
		crystalModelError.clear();
		landingErrorBlocks.clear();
		landingErrorTicks.clear();
		selfKnockback.clear();
		enemyPingMs.clear();
		divesSeen = 0;
		safetyDoomedTicks = 0;
		safetyTriggers = 0;
		shadowCommits = 0;
		ticks = 0;
		deaths = 0;
	}

	public List<String> lines(String scenario) {
		List<String> out = new ArrayList<>();
		Scorecard s = scorecard;
		out.add("AntiClient summary | scenario=" + scenario + " | ticks=" + ticks);
		out.add(String.format(Locale.ROOT, "Commits: TP=%d FP=%d MISS=%d | precision=%s recall=%s",
			s.tp(), s.fp(), s.miss(), pct(s.precision()), pct(s.recall())));
		out.add(String.format(Locale.ROOT, "  mace: TP=%d FP=%d MISS=%d | crystal: TP=%d FP=%d MISS=%d",
			s.tp(Archetype.MACE), s.fp(Archetype.MACE), s.miss(Archetype.MACE),
			s.tp(Archetype.CRYSTAL), s.fp(Archetype.CRYSTAL), s.miss(Archetype.CRYSTAL)));
		out.add("Lead ticks (commit to hit): " + s.leadTicks().describe());
		out.add("ETA error ticks (actual - predicted): " + s.etaError().describe());
		out.add("Dive landing error blocks: " + landingErrorBlocks.describe());
		out.add("Dive landing error ticks: " + landingErrorTicks.describe());
		out.add("Dives seen: " + divesSeen + " | shadow commits: " + shadowCommits);
		out.add("Crystal lifetime ticks (enemy CA speed): " + crystalLifeTicks.describe());
		out.add("Gap between hits on me, ticks: " + burstGapTicks.describe());
		out.add("Mace hit damage: " + maceHitDamage.describe());
		out.add("Crystal hit damage: " + crystalHitDamage.describe());
		out.add("Crystal model error (actual - raw estimate): " + crystalModelError.describe());
		out.add("Totem refill ticks: " + totemRefillTicks.describe() + " | pops=" + s.totemPops());
		out.add("Own ping ms p50=" + num(ping.p50()) + " p95=" + num(ping.p95())
			+ " | enemy ping ms: " + enemyPingMs.describe());
		out.add("Self knockback speed: " + selfKnockback.describe());
		out.add("Safety: doomed ticks=" + safetyDoomedTicks + " triggers=" + safetyTriggers + " deaths=" + deaths);
		return out;
	}

	private static String pct(double v) {
		return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.0f%%", v * 100.0);
	}

	private static String num(double v) {
		return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.0f", v);
	}
}
