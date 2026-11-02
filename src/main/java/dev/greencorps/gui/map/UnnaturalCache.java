package dev.greencorps.gui.map;

import dev.greencorps.backup.UnnaturalScore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unnatural-score cache for the Xaero map overlay.
 * Scores at most {@link #MAX_SCORE_PER_FRAME} chunks per frame on the client thread;
 * the renderer never rescans the whole home in one frame.
 */
public final class UnnaturalCache {
	private static final long TTL_MS = 30_000L;
	private static final int MAX_SCORE_PER_FRAME = 8;

	private record Entry(int hits, long atMs) {}

	private static final Map<String, Map<Long, Entry>> BY_DIM = new ConcurrentHashMap<>();
	private static final AtomicInteger scoredThisFrame = new AtomicInteger();

	private UnnaturalCache() {}

	public static void beginFrame() {
		scoredThisFrame.set(0);
	}

	/** Seed from a commit capture (free — no world rescan). */
	public static void seed(String dimension, int cx, int cz, int hits) {
		if (dimension == null) return;
		dimMap(dimension).put(ChunkPos.toLong(cx, cz), new Entry(Math.max(0, hits), System.currentTimeMillis()));
	}

	/**
	 * @return hits if known (cached or freshly scored), or -1 if unknown / budget exhausted
	 */
	public static int getOrEnqueue(String dimension, int cx, int cz) {
		if (dimension == null) return -1;
		long key = ChunkPos.toLong(cx, cz);
		Map<Long, Entry> map = dimMap(dimension);
		Entry e = map.get(key);
		long now = System.currentTimeMillis();
		if (e != null && now - e.atMs <= TTL_MS) return e.hits;

		if (scoredThisFrame.get() >= MAX_SCORE_PER_FRAME) return e != null ? e.hits : -1;

		MinecraftClient mc = MinecraftClient.getInstance();
		ClientWorld world = mc != null ? mc.world : null;
		if (world == null) return e != null ? e.hits : -1;
		String cur = world.getRegistryKey().getValue().toString();
		if (!dimension.equals(cur)) return e != null ? e.hits : -1;

		WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
		if (chunk == null) return e != null ? e.hits : -1;

		scoredThisFrame.incrementAndGet();
		int hits = UnnaturalScore.scoreChunk(chunk);
		map.put(key, new Entry(hits, now));
		return hits;
	}

	public static void invalidateChunk(String dimension, int cx, int cz) {
		if (dimension == null) return;
		dimMap(dimension).remove(ChunkPos.toLong(cx, cz));
	}

	public static void clearDimension(String dimension) {
		if (dimension == null) return;
		BY_DIM.remove(dimension);
	}

	private static Map<Long, Entry> dimMap(String dimension) {
		return BY_DIM.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
	}
}
