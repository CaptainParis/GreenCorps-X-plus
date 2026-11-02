package dev.greencorps.backup;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Content-addressed home store. Capture runs on the client tick in {@link #CAPTURE_BUDGET_MS}
 * slices so a commit does not freeze the frame.
 */
public final class BackupEngine {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/store");

	public static final long CAPTURE_BUDGET_MS = 8L;

	private final Path base;
	private final Path refs;
	private final ObjectStore objects;
	private final FileChannel lockChannel;
	private FileLock lock;
	private boolean canCommit;
	private boolean lockWarned;
	private final Map<String, Map<Long, Dirty>> dirty = new HashMap<>();
	private final Map<String, Long> lastCommit = new HashMap<>();
	private final Set<String> degraded = ConcurrentHashMap.newKeySet();
	private final ArrayDeque<CommitJob> jobs = new ArrayDeque<>();

	private long diskReserveBytes;
	private int settleDelayTicks;
	private CommitJob active;
	private boolean rehearsalPass;
	private long rehearsalAt;
	private Runnable flushAfter;
	private int flushLeft;

	public BackupEngine(Path base) throws IOException {
		this.base = base;
		this.refs = base.resolve("refs");
		Files.createDirectories(base);
		Files.createDirectories(refs);
		Files.createDirectories(base.resolve("snaps"));
		this.objects = new ObjectStore(base.resolve("objects"));
		this.lockChannel = FileChannel.open(base.resolve("store.lock"),
			StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		ensureCommitLock();
	}

	public Path baseDir() {
		return base;
	}

	public Path refsDir() {
		return refs;
	}

	public ObjectStore store() {
		return objects;
	}

	public Path manifestPath(String homeId, String filename) {
		return base.resolve("snaps").resolve(SnapshotIndex.safe(homeId)).resolve(filename);
	}

	public synchronized boolean canCommit() {
		return canCommit && lock != null && lock.isValid();
	}

	/**
	 * Retry the exclusive store lock. Returns true when this process may commit.
	 * Does not steal a lock held by another live client.
	 */
	public synchronized boolean ensureCommitLock() {
		if (lock != null && lock.isValid()) {
			canCommit = true;
			return true;
		}
		lock = null;
		canCommit = false;
		try {
			FileLock held = lockChannel.tryLock();
			if (held != null) {
				lock = held;
				canCommit = true;
				lockWarned = false;
				return true;
			}
		} catch (OverlappingFileLockException | IOException e) {
			LOG.debug("store lock unavailable: {}", e.toString());
		}
		return false;
	}

	/** True once after a failed lock attempt, until the lock is acquired. */
	public synchronized boolean consumeLockWarn() {
		if (canCommit()) return false;
		if (lockWarned) return false;
		lockWarned = true;
		return true;
	}

	public synchronized void setDiskReserveBytes(long bytes) {
		this.diskReserveBytes = Math.max(0, bytes);
	}

	public synchronized void setSettleDelayTicks(int ticks) {
		this.settleDelayTicks = Math.max(0, ticks);
	}

	public synchronized void markDirty(Home home, int cx, int cz, int tick) {
		if (home == null || home.id == null) return;
		if (!inside(home, cx, cz)) return;
		dirty.computeIfAbsent(home.id, k -> new HashMap<>())
			.putIfAbsent(ChunkPos.toLong(cx, cz), new Dirty(cx, cz, tick));
	}

	public synchronized int markAllLoadedDirty(Home home) {
		if (home == null) return 0;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null) return 0;
		int tick = mc.player != null ? mc.player.age : 0;
		int n = 0;
		int r = Math.max(0, home.radiusChunks);
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				int cx = home.centerChunkX + dx;
				int cz = home.centerChunkZ + dz;
				if (mc.world.getChunkManager().getWorldChunk(cx, cz) == null) continue;
				markDirty(home, cx, cz, tick);
				n++;
			}
		}
		return n;
	}

	public synchronized int dirtyCount(String homeId) {
		Map<Long, Dirty> map = dirty.get(homeId);
		return map == null ? 0 : map.size();
	}

	/** Dirty chunk positions for a home (copy). */
	public synchronized List<ChunkPos> dirtyChunkPositions(String homeId) {
		Map<Long, Dirty> map = dirty.get(homeId);
		if (map == null || map.isEmpty()) return List.of();
		List<ChunkPos> out = new ArrayList<>(map.size());
		for (Dirty d : map.values()) {
			out.add(new ChunkPos(d.cx, d.cz));
		}
		return out;
	}

	public synchronized int totalDirty() {
		int n = 0;
		for (Map<Long, Dirty> map : dirty.values()) n += map.size();
		return n;
	}

	public synchronized boolean hasActiveCapture() {
		return active != null || !jobs.isEmpty();
	}

	public void commitAsync(Home home, Consumer<CaptureProgress> progress, Consumer<CommitResult> result) {
		enqueue(home, progress, result, false);
	}

	public void flushDirtyAsync(List<Home> homes, Consumer<CaptureProgress> progress,
								 Consumer<CommitResult> result, Runnable after) {
		if (homes == null || homes.isEmpty()) {
			if (after != null) after.run();
			return;
		}
		synchronized (this) {
			flushAfter = after;
			flushLeft += homes.size();
		}
		for (Home home : homes) enqueue(home, progress, result, true);
	}

	private void enqueue(Home home, Consumer<CaptureProgress> progress, Consumer<CommitResult> result, boolean flush) {
		if (!ensureCommitLock()) {
			if (result != null) result.accept(new CommitResult(false, null, "store locked"));
			noteFlushDone(flush);
			return;
		}
		if (home == null || home.id == null) {
			if (result != null) result.accept(new CommitResult(false, null, "null home"));
			noteFlushDone(flush);
			return;
		}
		synchronized (this) {
			jobs.add(new CommitJob(home, progress, result, flush));
		}
	}

	public void tickCapture(long budgetMs) {
		CommitJob job;
		synchronized (this) {
			if (active == null) active = jobs.poll();
			job = active;
		}
		if (job == null) return;
		if (!job.started) {
			if (!prepare(job)) return;
		}
		long deadline = System.nanoTime() + Math.max(1, budgetMs) * 1_000_000L;
		ClientWorld world = world();
		if (world == null) {
			finish(job, false, "world unloaded");
			return;
		}
		if (job.home.dimension != null
			&& !job.home.dimension.equals(world.getRegistryKey().getValue().toString())) {
			// Client world is a different dimension, so its chunks are not this home.
			// Keep the dirty set and record the previous hashes.
			finish(job, true, null);
			return;
		}
		int guard = job.queue.size();
		while (!job.queue.isEmpty() && System.nanoTime() < deadline && guard-- > 0) {
			long key = job.queue.pollFirst();
			Dirty d = job.pending.get(key);
			if (d == null) continue;
			if (!settled(d)) {
				job.queue.addLast(key);
				continue;
			}
			captureOne(job, world, d);
			emit(job, false);
		}
		if (job.queue.isEmpty()) finish(job, true, null);
	}

	private boolean prepare(CommitJob job) {
		String disk = diskProblem();
		if (disk != null) {
			finish(job, false, disk);
			return false;
		}
		try {
			SnapshotIndex idx = new SnapshotIndex(refs, job.home.id);
			idx.latest().ifPresent(e -> {
				try {
					SnapshotManifest prev = SnapshotManifest.read(manifestPath(job.home.id, e.filename));
					if (prev.chunks != null) job.chunks.putAll(prev.chunks);
				} catch (IOException ignored) {}
			});
		} catch (IOException e) {
			finish(job, false, e.getClass().getSimpleName());
			return false;
		}
		synchronized (this) {
			Map<Long, Dirty> map = dirty.getOrDefault(job.home.id, Map.of());
			for (Dirty d : map.values()) {
				if (!inside(job.home, d.cx, d.cz)) continue;
				long key = ChunkPos.toLong(d.cx, d.cz);
				job.pending.put(key, d);
				job.queue.add(key);
			}
		}
		job.total = job.queue.size();
		job.started = true;
		job.startedAt = System.nanoTime();
		emit(job, true);
		return true;
	}

	private void captureOne(CommitJob job, ClientWorld world, Dirty d) {
		WorldChunk chunk = world.getChunkManager().getWorldChunk(d.cx, d.cz);
		if (chunk == null) return;
		try {
			byte[] raw = ChunkCapture.gzipWorldChunk(chunk);
			ObjectStore.PutResult put = objects.put(raw);
			String prev = job.chunks.put(SnapshotManifest.key(d.cx, d.cz), put.hash());
			if (prev == null || !prev.equals(put.hash())) job.changed++;
			job.bytesAdded += put.bytesAdded();
			job.done++;
			synchronized (this) {
				Map<Long, Dirty> map = dirty.get(job.home.id);
				if (map != null) map.remove(ChunkPos.toLong(d.cx, d.cz));
			}
		} catch (Exception e) {
			LOG.warn("capture {},{}: {}", d.cx, d.cz, e.toString());
		}
	}

	private void finish(CommitJob job, boolean ok, String error) {
		synchronized (this) {
			if (active == job) active = null;
		}
		if (!ok) {
			if (job.result != null) job.result.accept(new CommitResult(false, null, error));
			noteFlushDone(job.flush);
			return;
		}
		try {
			long epoch = System.currentTimeMillis();
			String filename = epoch + ".gcsnap";
			SnapshotManifest manifest = new SnapshotManifest();
			manifest.homeId = job.home.id;
			manifest.epochMs = epoch;
			manifest.chunks = new LinkedHashMap<>(job.chunks);
			manifest.changedChunks = job.changed;
			manifest.capturedChunks = job.chunks.size();
			manifest.expectedChunks = job.home.expectedChunks();
			manifest.bytesAdded = job.bytesAdded;
			manifest.writeAtomic(manifestPath(job.home.id, filename));

			SnapshotIndex idx = new SnapshotIndex(refs, job.home.id);
			SnapshotIndex.Entry entry = new SnapshotIndex.Entry();
			entry.epochMs = epoch;
			entry.filename = filename;
			entry.bytesAdded = job.bytesAdded;
			entry.capturedChunks = manifest.capturedChunks;
			entry.coveragePct = coverage(manifest.capturedChunks, manifest.expectedChunks);
			idx.add(entry);
			synchronized (this) {
				lastCommit.put(job.home.id, epoch);
			}
			if (job.result != null) job.result.accept(new CommitResult(true, manifest, null));
		} catch (Exception e) {
			LOG.warn("commit {}: {}", job.home.id, e.toString());
			if (job.result != null) job.result.accept(new CommitResult(false, null, e.getClass().getSimpleName()));
		}
		noteFlushDone(job.flush);
	}

	private void noteFlushDone(boolean flush) {
		Runnable after = null;
		synchronized (this) {
			if (!flush || flushLeft <= 0) return;
			flushLeft--;
			if (flushLeft == 0) {
				after = flushAfter;
				flushAfter = null;
			}
		}
		if (after != null) after.run();
	}

	private void emit(CommitJob job, boolean starting) {
		if (job.progress == null) return;
		int eta = 0;
		if (!starting && job.done > 0 && job.done < job.total) {
			long elapsedMs = (System.nanoTime() - job.startedAt) / 1_000_000L;
			long per = Math.max(1, elapsedMs / job.done);
			eta = (int) Math.min(Integer.MAX_VALUE, per * (job.total - job.done) / 1000L);
		}
		job.progress.accept(new CaptureProgress(job.home, starting, job.done, job.total, eta));
	}

	private boolean settled(Dirty d) {
		if (settleDelayTicks <= 0) return true;
		MinecraftClient mc = MinecraftClient.getInstance();
		int age = mc != null && mc.player != null ? mc.player.age : Integer.MAX_VALUE;
		return age - d.tick >= settleDelayTicks;
	}

	private String diskProblem() {
		if (diskReserveBytes <= 0) return null;
		try {
			long usable = Files.getFileStore(base).getUsableSpace();
			if (usable < diskReserveBytes) return "low disk";
		} catch (IOException e) {
			return "disk check failed";
		}
		return null;
	}

	private static ClientWorld world() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc == null ? null : mc.world;
	}

	private static boolean inside(Home home, int cx, int cz) {
		int r = Math.max(0, home.radiusChunks);
		return Math.max(Math.abs(cx - home.centerChunkX), Math.abs(cz - home.centerChunkZ)) <= r;
	}

	private static double coverage(int captured, int expected) {
		if (expected <= 0) return captured > 0 ? 100.0 : 0.0;
		return 100.0 * captured / expected;
	}

	public long lastCommitAt(String homeId) {
		if (homeId == null) return 0L;
		synchronized (this) {
			Long cached = lastCommit.get(homeId);
			if (cached != null) return cached;
		}
		try {
			long v = new SnapshotIndex(refs, homeId).latest().map(e -> e.epochMs).orElse(0L);
			synchronized (this) {
				lastCommit.put(homeId, v);
			}
			return v;
		} catch (IOException e) {
			return 0L;
		}
	}

	/** Refresh gap for {@link #estimateStoreSize}; a walk lists + stats every object file. */
	private static final long STORE_SIZE_TTL_MS = 30_000L;
	private volatile long cachedStoreBytes = -1L;
	private volatile long cachedStoreAtMs;
	private final java.util.concurrent.atomic.AtomicBoolean storeSizeRefreshing =
		new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * Cached object-store size (HUD, map, alerts, privacy fence refresh call this often).
	 * Never walks on the caller's thread: stale values refresh in the background; 0 until
	 * the first walk lands.
	 */
	public long estimateStoreSize() {
		long cached = cachedStoreBytes;
		if (System.currentTimeMillis() - cachedStoreAtMs >= STORE_SIZE_TTL_MS
			&& storeSizeRefreshing.compareAndSet(false, true)) {
			Thread t = new Thread(() -> {
				try {
					storeSizeNow();
				} finally {
					storeSizeRefreshing.set(false);
				}
			}, "greencorps-store-size");
			t.setDaemon(true);
			t.start();
		}
		return Math.max(0L, cached);
	}

	/** Exact size now (walks the store); also refreshes the cache. For commit summaries. */
	public long storeSizeNow() {
		long n;
		try {
			n = objects.sizeBytes();
		} catch (IOException e) {
			return Math.max(0L, cachedStoreBytes);
		}
		cachedStoreBytes = n;
		cachedStoreAtMs = System.currentTimeMillis();
		return n;
	}

	public synchronized StoreHealth health() {
		return degraded.isEmpty() ? StoreHealth.HEALTHY : StoreHealth.DEGRADED;
	}

	public int degradedSections() {
		return degraded.size();
	}

	public void markDegraded(String hash) {
		if (hash != null) degraded.add(hash);
	}

	public void clearDegraded(String hash) {
		if (hash != null) degraded.remove(hash);
	}

	public synchronized boolean lastRehearsalPass() {
		return rehearsalPass;
	}

	public synchronized long lastRehearsalAt() {
		return rehearsalAt;
	}

	public synchronized void markRehearsal(boolean pass) {
		this.rehearsalPass = pass;
		this.rehearsalAt = System.currentTimeMillis();
	}

	public synchronized void clearRehearsalStatus() {
		this.rehearsalPass = false;
		this.rehearsalAt = 0L;
	}

	public record CaptureProgress(Home home, boolean starting, int done, int total, int etaSeconds) {}

	public record CommitResult(boolean ok, SnapshotManifest manifest, String error) {}

	private static final class Dirty {
		final int cx;
		final int cz;
		final int tick;

		Dirty(int cx, int cz, int tick) {
			this.cx = cx;
			this.cz = cz;
			this.tick = tick;
		}
	}

	private static final class CommitJob {
		final Home home;
		final Consumer<CaptureProgress> progress;
		final Consumer<CommitResult> result;
		final boolean flush;
		final ArrayDeque<Long> queue = new ArrayDeque<>();
		final Map<Long, Dirty> pending = new HashMap<>();
		final Map<String, String> chunks = new LinkedHashMap<>();
		boolean started;
		int done;
		int total;
		int changed;
		long bytesAdded;
		long startedAt;

		CommitJob(Home home, Consumer<CaptureProgress> progress, Consumer<CommitResult> result, boolean flush) {
			this.home = home;
			this.progress = progress;
			this.result = result;
			this.flush = flush;
		}
	}
}
