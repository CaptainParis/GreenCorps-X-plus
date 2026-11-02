package dev.greencorps.stasis;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks ender pearls that match stasis geometry. A pearl is "installed" when it stays
 * geometry-matched and roughly stationary for {@code settleTicks} consecutive observations.
 */
public final class PearlSettleTracker {
	public static final class Sample {
		public final UUID entityId;
		public final double x;
		public final double y;
		public final double z;
		public final String dimension;
		public final String ownerUuid;
		public final String ownerIgn;
		public final boolean geometryMatch;
		public final StasisGeometry.Match matchKind;

		public Sample(UUID entityId, double x, double y, double z, String dimension,
					  String ownerUuid, String ownerIgn, boolean geometryMatch,
					  StasisGeometry.Match matchKind) {
			this.entityId = entityId;
			this.x = x;
			this.y = y;
			this.z = z;
			this.dimension = dimension;
			this.ownerUuid = ownerUuid;
			this.ownerIgn = ownerIgn;
			this.geometryMatch = geometryMatch;
			this.matchKind = matchKind == null ? StasisGeometry.Match.NONE : matchKind;
		}
	}

	public static final class Settled {
		public final UUID entityId;
		public final double x;
		public final double y;
		public final double z;
		public final String dimension;
		public final String ownerUuid;
		public final String ownerIgn;
		public final StasisGeometry.Match matchKind;

		public Settled(UUID entityId, double x, double y, double z, String dimension,
					   String ownerUuid, String ownerIgn, StasisGeometry.Match matchKind) {
			this.entityId = entityId;
			this.x = x;
			this.y = y;
			this.z = z;
			this.dimension = dimension;
			this.ownerUuid = ownerUuid;
			this.ownerIgn = ownerIgn;
			this.matchKind = matchKind == null ? StasisGeometry.Match.NONE : matchKind;
		}

		public int blockX() { return (int) Math.floor(x); }
		public int blockY() { return (int) Math.floor(y); }
		public int blockZ() { return (int) Math.floor(z); }
	}

	private static final class Track {
		double anchorX;
		double anchorY;
		double anchorZ;
		int ticks;
		boolean announced;
		String dimension;
		String ownerUuid;
		String ownerIgn;
		StasisGeometry.Match matchKind = StasisGeometry.Match.NONE;
		int missingTicks;
	}

	private final Map<UUID, Track> tracks = new HashMap<>();
	private final double boxHalf;
	private final int settleTicks;
	private final int dropAfterMissing;

	public PearlSettleTracker(double boxHalf, int settleTicks) {
		this(boxHalf, settleTicks, 40);
	}

	public PearlSettleTracker(double boxHalf, int settleTicks, int dropAfterMissing) {
		this.boxHalf = Math.max(0.25, boxHalf);
		this.settleTicks = Math.max(1, settleTicks);
		this.dropAfterMissing = Math.max(1, dropAfterMissing);
	}

	/** Feed one pearl observation. Returns Settled once when the pearl first qualifies. */
	public Settled observe(Sample sample) {
		if (sample == null || sample.entityId == null) return null;
		if (!sample.geometryMatch) {
			tracks.remove(sample.entityId);
			return null;
		}

		Track t = tracks.get(sample.entityId);
		if (t == null) {
			t = new Track();
			t.anchorX = sample.x;
			t.anchorY = sample.y;
			t.anchorZ = sample.z;
			t.ticks = 1;
			t.dimension = sample.dimension;
			t.ownerUuid = sample.ownerUuid;
			t.ownerIgn = sample.ownerIgn;
			t.matchKind = sample.matchKind;
			tracks.put(sample.entityId, t);
			return null;
		}
		t.missingTicks = 0;
		if (sample.ownerUuid != null) t.ownerUuid = sample.ownerUuid;
		if (sample.ownerIgn != null) t.ownerIgn = sample.ownerIgn;
		t.dimension = sample.dimension;
		t.matchKind = sample.matchKind;

		if (Math.abs(sample.x - t.anchorX) > boxHalf
			|| Math.abs(sample.y - t.anchorY) > boxHalf
			|| Math.abs(sample.z - t.anchorZ) > boxHalf) {
			t.anchorX = sample.x;
			t.anchorY = sample.y;
			t.anchorZ = sample.z;
			t.ticks = 1;
			t.announced = false;
			return null;
		}

		t.ticks++;
		if (!t.announced && t.ticks >= settleTicks) {
			t.announced = true;
			return new Settled(sample.entityId, t.anchorX, t.anchorY, t.anchorZ,
				t.dimension, t.ownerUuid, t.ownerIgn, t.matchKind);
		}
		return null;
	}

	public void endTick(Iterable<UUID> seenThisTick) {
		java.util.HashSet<UUID> seen = new java.util.HashSet<>();
		if (seenThisTick != null) {
			for (UUID id : seenThisTick) seen.add(id);
		}
		Iterator<Map.Entry<UUID, Track>> it = tracks.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Track> e = it.next();
			if (seen.contains(e.getKey())) continue;
			e.getValue().missingTicks++;
			if (e.getValue().missingTicks >= dropAfterMissing) it.remove();
		}
	}

	public void clear() {
		tracks.clear();
	}

	public int trackedCount() {
		return tracks.size();
	}

	public boolean isAnnounced(UUID entityId) {
		Track t = tracks.get(entityId);
		return t != null && t.announced;
	}
}
