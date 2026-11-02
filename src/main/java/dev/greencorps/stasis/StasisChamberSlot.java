package dev.greencorps.stasis;

import java.util.UUID;

/** A detected viable stasis chamber seat (geometry), optionally bound to a pearl. */
public class StasisChamberSlot {
	public static final String KIND_WATER = "water";
	public static final String KIND_HONEY = "honey";

	public String id;
	public String homeId;
	public String dimension;
	/** Pearl seat block (water column cell, or air beside honey). */
	public int x;
	public int y;
	public int z;
	/** Column root: soul sand or upward piston. */
	public int rootX;
	public int rootY;
	public int rootZ;
	/** {@link #KIND_WATER} or {@link #KIND_HONEY}. */
	public String kind = KIND_WATER;
	/** First discovery epoch ms (set once). */
	public long firstSeenMs;
	public long lastSeenMs;
	/** Bound pearl id when a remembered pearl sits in this seat. */
	public String pearlId;
	/**
	 * Last geometry check while chunk was loaded:
	 * true = still valid, false = broken, null = unknown / not checked.
	 */
	public Boolean geometryOk;
	/** Geometric chamber index within home (#1…#N); 0 = unassigned. */
	public int number;

	public StasisChamberSlot() {}

	public StasisChamberSlot(String homeId, String dimension, int x, int y, int z,
							 int rootX, int rootY, int rootZ, String kind) {
		this.id = UUID.randomUUID().toString();
		this.homeId = homeId;
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.rootX = rootX;
		this.rootY = rootY;
		this.rootZ = rootZ;
		this.kind = kind == null ? KIND_WATER : kind;
		long now = System.currentTimeMillis();
		this.firstSeenMs = now;
		this.lastSeenMs = now;
		this.geometryOk = true;
	}

	public boolean sameDimension(String dim) {
		if (dimension == null || dim == null) return false;
		return dimension.equals(dim);
	}

	public boolean sameRoot(String dim, int rx, int ry, int rz) {
		return sameDimension(dim) && rootX == rx && rootY == ry && rootZ == rz;
	}

	/** Same chamber column (XZ + kind); root Y may drift after rebuilds. */
	public boolean sameColumn(String dim, String otherKind, int rx, int rz) {
		if (!sameDimension(dim) || rootX != rx || rootZ != rz) return false;
		String a = kind == null || kind.isBlank() ? KIND_WATER : kind;
		String b = otherKind == null || otherKind.isBlank() ? KIND_WATER : otherKind;
		return a.equals(b);
	}

	public boolean sameColumn(StasisChamberSlot other) {
		if (other == null) return false;
		return sameColumn(other.dimension, other.kind, other.rootX, other.rootZ);
	}

	/** Same water/honey seat column (seat XZ + kind). Catches orphan roots that share a seat. */
	public boolean sameSeatColumn(StasisChamberSlot other) {
		if (other == null || !sameDimension(other.dimension)) return false;
		if (x != other.x || z != other.z) return false;
		String a = kind == null || kind.isBlank() ? KIND_WATER : kind;
		String b = other.kind == null || other.kind.isBlank() ? KIND_WATER : other.kind;
		return a.equals(b);
	}

	/** Water seat must sit on the root column XZ. Honey seats sit beside the root. */
	public boolean waterSeatAligned() {
		String k = kind == null || kind.isBlank() ? KIND_WATER : kind;
		if (!KIND_WATER.equals(k)) return true;
		return x == rootX && z == rootZ;
	}

	public String kindLabel() {
		if (KIND_HONEY.equals(kind)) return "honey";
		if (KIND_WATER.equals(kind)) return "water";
		return kind == null ? "?" : kind;
	}

	/**
	 * Pearl occupies this cached chamber: near seat, or in water column above root,
	 * or beside honey column (root xz) within Y band.
	 */
	public boolean matchesPearl(int px, int py, int pz) {
		int dx = px - x;
		int dy = py - y;
		int dz = pz - z;
		if (dx * dx + dy * dy + dz * dz <= 4) return true; // seat ±2

		if (KIND_WATER.equals(kind)) {
			if (px == rootX && pz == rootZ && py >= rootY && py <= rootY + 12) return true;
		} else if (KIND_HONEY.equals(kind)) {
			// Beside honey column (root xz) or at seat column
			boolean besideRoot = Math.abs(px - rootX) + Math.abs(pz - rootZ) == 1
				&& py >= rootY && py <= rootY + 8;
			boolean atSeatCol = px == x && pz == z && py >= rootY && py <= rootY + 8;
			if (besideRoot || atSeatCol) return true;
		}
		return false;
	}
}
