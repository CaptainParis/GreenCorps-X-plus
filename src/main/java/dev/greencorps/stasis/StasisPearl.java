package dev.greencorps.stasis;

import java.util.UUID;

/** A remembered stasis pearl installation (loaded or empty). */
public class StasisPearl {
	public String id;
	public String dimension;
	public int x;
	public int y;
	public int z;
	/** UUID string of owner when known; null when unknown. */
	public String ownerUuid;
	/** Last seen IGN for ownerUuid; null when unknown. */
	public String ownerIgn;
	/**
	 * Epoch ms when owner was confirmed from a live projectile owner / local throw window.
	 * 0 = never confirmed this install → display as Assumed when IGN known.
	 */
	public long ownerConfirmedMs;
	/** Linked stasis home id, or null. */
	public String homeId;
	/** True when a pearl entity was last seen at this chamber. */
	public boolean loaded = true;
	/** First detection epoch ms (set once). */
	public long firstSeenMs;
	public long lastSeenMs;
	/** Chamber hint for alerts: bubble_column, trapdoor, suspended, unknown. */
	public String chamberKind = "unknown";
	/** Last Xaero pin block for in-place edit. */
	public Integer waypointX;
	public Integer waypointY;
	public Integer waypointZ;

	public StasisPearl() {}

	public StasisPearl(String dimension, int x, int y, int z) {
		this.id = UUID.randomUUID().toString();
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		long now = System.currentTimeMillis();
		this.firstSeenMs = now;
		this.lastSeenMs = now;
	}

	public boolean ownedBy(String uuid) {
		return uuid != null && uuid.equals(ownerUuid);
	}

	public boolean isUnknownOwner() {
		return ownerUuid == null || ownerUuid.isBlank();
	}

	/** True when we have an IGN/UUID but it was not confirmed this session from a live owner. */
	public boolean isAssumedOwner(long sessionStartMs) {
		if ((ownerIgn == null || ownerIgn.isBlank()) && (ownerUuid == null || ownerUuid.isBlank())) {
			return false;
		}
		return ownerConfirmedMs <= 0 || ownerConfirmedMs < sessionStartMs;
	}

	/** Player-facing owner label; appends (Assumed) when not live-confirmed this session. */
	public String ownerDisplay(long sessionStartMs) {
		String who;
		if (ownerIgn != null && !ownerIgn.isBlank()) who = ownerIgn;
		else if (ownerUuid != null && ownerUuid.length() >= 8) who = ownerUuid.substring(0, 8);
		else return "unknown";
		if (isAssumedOwner(sessionStartMs)) return who + " (Assumed)";
		return who;
	}

	public boolean sameDimension(String dim) {
		if (dimension == null || dim == null) return false;
		return dimension.equals(dim);
	}

	public double distanceTo(double px, double py, double pz) {
		double dx = x + 0.5 - px;
		double dy = y + 0.5 - py;
		double dz = z + 0.5 - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
