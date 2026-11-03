package dev.greencorps.anticlient;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches commits (the model said "strike is coming") against real hits on us.
 * A commit answered by a matching hit is a true positive; one that expires is a false
 * positive; a hit with no open commit is a miss. This is the core auto-measurement.
 */
public final class Scorecard {
	public static final int EXPIRE_SLACK_TICKS = 30;

	public record Commit(int playerId, Archetype archetype, int tick, int etaTicks, int score) {}

	public record Resolved(Commit commit, boolean hit, int leadTicks, int etaErrorTicks) {}

	private final Map<Integer, Commit> open = new LinkedHashMap<>();
	private final Map<Archetype, int[]> counts = new LinkedHashMap<>();
	private final Series leadTicks = new Series();
	private final Series etaError = new Series();
	private int totemPops;
	private int hitsOther;

	public Scorecard() {
		for (Archetype a : new Archetype[] {Archetype.MACE, Archetype.CRYSTAL}) counts.put(a, new int[3]);
	}

	/** Opens a commit unless the player already has one open. Returns true when opened. */
	public boolean openCommit(int playerId, Archetype a, int tick, int eta, int score) {
		if (a == Archetype.NONE || open.containsKey(playerId)) return false;
		open.put(playerId, new Commit(playerId, a, tick, Math.max(0, eta), score));
		return true;
	}

	public boolean hasOpen(int playerId) {
		return open.containsKey(playerId);
	}

	public int openCount() {
		return open.size();
	}

	/** A hit of this archetype landed on us. Returns the commit it resolved, or null on a miss. */
	public Resolved onHit(Archetype a, int tick, int attackerId) {
		if (a == Archetype.NONE) {
			hitsOther++;
			return null;
		}
		Commit best = null;
		Commit exact = open.get(attackerId);
		if (exact != null && exact.archetype() == a) best = exact;
		if (best == null) {
			for (Commit c : open.values()) {
				if (c.archetype() == a && (best == null || c.tick() < best.tick())) best = c;
			}
		}
		if (best == null) {
			counts.get(a)[2]++;
			return null;
		}
		open.remove(best.playerId());
		counts.get(a)[0]++;
		int lead = tick - best.tick();
		int err = lead - best.etaTicks();
		leadTicks.add(lead);
		etaError.add(err);
		return new Resolved(best, true, lead, err);
	}

	/** Expires stale commits. Returns the ones that turned out to be false positives. */
	public List<Resolved> tick(int now) {
		List<Resolved> out = new ArrayList<>();
		Iterator<Commit> it = open.values().iterator();
		while (it.hasNext()) {
			Commit c = it.next();
			if (now - c.tick() > c.etaTicks() + EXPIRE_SLACK_TICKS) {
				it.remove();
				counts.get(c.archetype())[1]++;
				out.add(new Resolved(c, false, now - c.tick(), 0));
			}
		}
		return out;
	}

	public void onTotemPop() {
		totemPops++;
	}

	public int tp(Archetype a) {
		return counts.getOrDefault(a, new int[3])[0];
	}

	public int fp(Archetype a) {
		return counts.getOrDefault(a, new int[3])[1];
	}

	public int miss(Archetype a) {
		return counts.getOrDefault(a, new int[3])[2];
	}

	public int tp() {
		return sum(0);
	}

	public int fp() {
		return sum(1);
	}

	public int miss() {
		return sum(2);
	}

	private int sum(int i) {
		int s = 0;
		for (int[] c : counts.values()) s += c[i];
		return s;
	}

	public int totemPops() {
		return totemPops;
	}

	public int hitsOther() {
		return hitsOther;
	}

	public double precision() {
		int d = tp() + fp();
		return d == 0 ? Double.NaN : (double) tp() / d;
	}

	public double recall() {
		int d = tp() + miss();
		return d == 0 ? Double.NaN : (double) tp() / d;
	}

	public Series leadTicks() {
		return leadTicks;
	}

	public Series etaError() {
		return etaError;
	}

	public void reset() {
		open.clear();
		for (int[] c : counts.values()) java.util.Arrays.fill(c, 0);
		leadTicks.clear();
		etaError.clear();
		totemPops = 0;
		hitsOther = 0;
	}
}
