package dev.greencorps.stasis;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Geofenced stasis home. Fields are public because the GUI writes them in place. */
public class StasisHome {
	public String id;
	public String name;
	public String dimension;
	public int centerX;
	public int centerY;
	public int centerZ;
	/** Fence radius cap shared by the edit UI and store load. */
	public static final int MAX_RADIUS_CHUNKS = 4;

	public int radiusChunks;
	/** Legacy single-quota; migrated into {@link #quotaByProfile} when empty. */
	public int pearlsRequired = 1;
	/** profileId → required pearls on this home (0 / omit = no quota for that account). */
	public Map<String, Integer> quotaByProfile = new HashMap<>();
	/** Last written Stasis Hub Xaero pin (nullable until first sync). */
	public Integer hubWaypointX;
	public Integer hubWaypointY;
	public Integer hubWaypointZ;

	public StasisHome() {}

	public StasisHome(String name, String dimension, int centerX, int centerY, int centerZ, int radiusChunks, int pearlsRequired) {
		this.id = UUID.randomUUID().toString();
		this.name = name;
		this.dimension = dimension;
		this.centerX = centerX;
		this.centerY = centerY;
		this.centerZ = centerZ;
		this.radiusChunks = Math.max(0, radiusChunks);
		this.pearlsRequired = Math.max(0, pearlsRequired);
		this.quotaByProfile = new HashMap<>();
	}

	public int requiredFor(String profileId) {
		if (profileId == null || quotaByProfile == null) return 0;
		Integer v = quotaByProfile.get(profileId);
		return v == null ? 0 : Math.max(0, v);
	}

	public void setRequiredFor(String profileId, int required) {
		if (profileId == null || profileId.isBlank()) return;
		if (quotaByProfile == null) quotaByProfile = new HashMap<>();
		if (required <= 0) quotaByProfile.remove(profileId);
		else quotaByProfile.put(profileId, required);
	}

	public int centerChunkX() {
		return centerX >> 4;
	}

	public int centerChunkZ() {
		return centerZ >> 4;
	}

	/** Chebyshev distance in chunks from the center chunk. */
	public boolean containsBlock(double blockX, double blockZ) {
		int cx = floorChunk(blockX);
		int cz = floorChunk(blockZ);
		int r = Math.max(0, radiusChunks);
		return Math.max(Math.abs(cx - centerChunkX()), Math.abs(cz - centerChunkZ())) <= r;
	}

	public boolean containsChunk(int chunkX, int chunkZ) {
		int r = Math.max(0, radiusChunks);
		return Math.max(Math.abs(chunkX - centerChunkX()), Math.abs(chunkZ - centerChunkZ())) <= r;
	}

	/**
	 * Chebyshev blocks outside the inclusive chunk AABB of this home (0 if inside).
	 * 16 ≈ one chunk past the geofence edge.
	 */
	public int blocksBeyond(double blockX, double blockZ) {
		int r = Math.max(0, radiusChunks);
		int minCx = centerChunkX() - r;
		int maxCx = centerChunkX() + r;
		int minCz = centerChunkZ() - r;
		int maxCz = centerChunkZ() + r;
		int minX = minCx << 4;
		int maxX = (maxCx << 4) + 15;
		int minZ = minCz << 4;
		int maxZ = (maxCz << 4) + 15;
		int px = (int) Math.floor(blockX);
		int pz = (int) Math.floor(blockZ);
		int dx = px < minX ? minX - px : (px > maxX ? px - maxX : 0);
		int dz = pz < minZ ? minZ - pz : (pz > maxZ ? pz - maxZ : 0);
		return Math.max(dx, dz);
	}

	public boolean sameDimension(String dim) {
		if (dimension == null || dim == null) return false;
		return dimension.equals(dim);
	}

	private static int floorChunk(double block) {
		return (int) Math.floor(block) >> 4;
	}
}
