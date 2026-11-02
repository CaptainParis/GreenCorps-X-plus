package dev.greencorps.backup;

import java.util.UUID;

/** A geofenced home or one-shot snapshot. Fields are public because the GUI writes them in place. */
public class Home {
	public enum Kind { HOME, SNAPSHOT }

	public String id;
	public String name;
	public String dimension;
	public int centerChunkX;
	public int centerChunkZ;
	public int radiusChunks;
	public int spawnY;
	public boolean armed;
	public boolean autoCommit;
	public boolean keepPlayableSave;
	public Kind kind = Kind.HOME;

	public Home() {}

	public Home(String name, String dimension, int centerChunkX, int centerChunkZ, int radiusChunks) {
		this.id = UUID.randomUUID().toString();
		this.name = name;
		this.dimension = dimension;
		this.centerChunkX = centerChunkX;
		this.centerChunkZ = centerChunkZ;
		this.radiusChunks = Math.max(0, radiusChunks);
		this.kind = Kind.HOME;
	}

	public boolean isSnapshot() {
		return kind == Kind.SNAPSHOT;
	}

	/** Chebyshev distance in chunks from the center chunk. */
	public boolean containsBlock(double blockX, double blockZ) {
		int cx = floorChunk(blockX);
		int cz = floorChunk(blockZ);
		int r = Math.max(0, radiusChunks);
		return Math.max(Math.abs(cx - centerChunkX), Math.abs(cz - centerChunkZ)) <= r;
	}

	public int expectedChunks() {
		int side = Math.max(0, radiusChunks) * 2 + 1;
		return side * side;
	}

	public double centerBlockX() {
		return centerChunkX * 16.0 + 8.0;
	}

	public double centerBlockZ() {
		return centerChunkZ * 16.0 + 8.0;
	}

	/** Inclusive west/north edge of the chunk square. */
	public double minBlockX() {
		return (centerChunkX - Math.max(0, radiusChunks)) * 16.0;
	}

	public double minBlockZ() {
		return (centerChunkZ - Math.max(0, radiusChunks)) * 16.0;
	}

	/** Exclusive east/south edge, so the width is {@code (radius * 2 + 1) * 16}. */
	public double maxBlockX() {
		return (centerChunkX + Math.max(0, radiusChunks) + 1) * 16.0;
	}

	public double maxBlockZ() {
		return (centerChunkZ + Math.max(0, radiusChunks) + 1) * 16.0;
	}

	public boolean containsChunk(int chunkX, int chunkZ) {
		int r = Math.max(0, radiusChunks);
		return Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ)) <= r;
	}

	private static int floorChunk(double block) {
		return (int) Math.floor(block) >> 4;
	}
}
