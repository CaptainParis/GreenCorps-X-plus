package dev.greencorps.stasis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** {@code chambers.json} schema_version 3 under {@code config/greencorps/stasis/}. */
public final class StasisStore {
	private static final int SCHEMA = 3;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path base;
	private final Path file;
	private final List<StasisHome> homes = new ArrayList<>();
	private final List<StasisPearl> pearls = new ArrayList<>();
	private final List<StasisChamberSlot> slots = new ArrayList<>();
	private final List<StasisPlayerProfile> profiles = new ArrayList<>();
	/** Bumped on every mutation (deferred or written) — UI watches this for live refresh. */
	private int revision;
	/** Min gap between deferred writes; heartbeat churn coalesces into one file write. */
	public static final long FLUSH_INTERVAL_MS = 2_000L;
	/** Off by default (tests / tools see every save on disk); the live module turns it on. */
	private boolean deferredWrites;
	private boolean dirty;
	private long lastWriteMs;
	private int diskWrites;
	private ExecutorService writer;
	private Future<?> pendingWrite;
	/** Timestamp-only churn (lastSeenMs): persisted on a slow cadence, never per tick. */
	public static final long HEARTBEAT_FLUSH_MS = 30_000L;
	private boolean heartbeatDirty;
	/** Background write failed; retry on the next flush. */
	private volatile boolean writeFailed;

	public static Path defaultBaseDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("greencorps").resolve("stasis");
	}

	public StasisStore(Path base) throws IOException {
		this.base = base;
		this.file = base.resolve("chambers.json");
		Files.createDirectories(base);
		if (Files.isRegularFile(file)) load();
	}

	public Path baseDir() {
		return base;
	}

	/**
	 * Coalesce saves: mutations mark dirty and {@link #flushIfDue} writes at most every
	 * {@link #FLUSH_INTERVAL_MS} on a background thread. Home / profile edits still write at once.
	 */
	public synchronized void setDeferredWrites(boolean on) {
		if (on && writer == null) {
			writer = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "greencorps-stasis-store");
				t.setDaemon(true);
				return t;
			});
			Runtime.getRuntime().addShutdownHook(new Thread(this::flushOnShutdown, "greencorps-stasis-flush"));
		}
		deferredWrites = on;
		if (!on && dirty) {
			try {
				flushNow();
			} catch (IOException ignored) {}
		}
	}

	public synchronized boolean isDirty() {
		return dirty;
	}

	/** Completed + queued file writes since open (tests / perf). */
	public synchronized int diskWrites() {
		return diskWrites;
	}

	/** Live objects already hold the new timestamps; just schedule a slow write. */
	public synchronized void markHeartbeat() {
		if (deferredWrites) heartbeatDirty = true;
	}

	/** Deferred mode: write when dirty and the interval elapsed. Cheap no-op otherwise. */
	public synchronized void flushIfDue(long nowMs) {
		if (writeFailed) {
			writeFailed = false;
			dirty = true;
		}
		if (!deferredWrites) return;
		long since = nowMs - lastWriteMs;
		boolean due = dirty ? since >= FLUSH_INTERVAL_MS : heartbeatDirty && since >= HEARTBEAT_FLUSH_MS;
		if (!due) return;
		if (pendingWrite != null && !pendingWrite.isDone()) return;
		String json = serialize();
		dirty = false;
		heartbeatDirty = false;
		lastWriteMs = nowMs;
		diskWrites++;
		pendingWrite = writer.submit(() -> {
			long t0 = StasisPerf.start();
			try {
				StasisAtomicFiles.writeString(file, json);
			} catch (IOException e) {
				// No store lock here: flushNow() may hold it while awaiting this write.
				writeFailed = true;
			}
			StasisPerf.stop(StasisPerf.Bucket.STORE_WRITE, t0);
		});
	}

	/** Write now (waits for any in-flight background write so order is kept). */
	public synchronized void flushNow() throws IOException {
		awaitPendingWrite();
		if (writeFailed) {
			writeFailed = false;
			dirty = true;
		}
		if (!dirty && !heartbeatDirty) return;
		writeSync();
	}

	private void saveNow() throws IOException {
		revision++;
		awaitPendingWrite();
		writeSync();
	}

	private void awaitPendingWrite() {
		Future<?> f = pendingWrite;
		if (f == null) return;
		try {
			f.get(5, TimeUnit.SECONDS);
		} catch (Exception ignored) {}
		pendingWrite = null;
	}

	private void writeSync() throws IOException {
		long t0 = StasisPerf.start();
		StasisAtomicFiles.writeString(file, serialize());
		dirty = false;
		heartbeatDirty = false;
		lastWriteMs = System.currentTimeMillis();
		diskWrites++;
		StasisPerf.stop(StasisPerf.Bucket.STORE_WRITE, t0);
	}

	private void flushOnShutdown() {
		synchronized (this) {
			if (!dirty && !heartbeatDirty) return;
			try {
				StasisAtomicFiles.writeString(file, serialize());
				dirty = false;
			} catch (IOException ignored) {}
		}
	}

	public synchronized int revision() {
		return revision;
	}

	public synchronized List<StasisHome> homes() {
		return List.copyOf(homes);
	}

	public synchronized List<StasisPearl> pearls() {
		return List.copyOf(pearls);
	}

	public synchronized List<StasisChamberSlot> slots() {
		return List.copyOf(slots);
	}

	public synchronized List<StasisPlayerProfile> profiles() {
		return List.copyOf(profiles);
	}

	public synchronized Optional<StasisPlayerProfile> findProfileById(String id) {
		if (id == null) return Optional.empty();
		for (StasisPlayerProfile p : profiles) {
			if (id.equals(p.id)) return Optional.of(p);
		}
		return Optional.empty();
	}

	/** First profile matching UUID then IGN (store order). */
	public synchronized Optional<StasisPlayerProfile> matchProfile(String uuid, String ign) {
		for (StasisPlayerProfile p : profiles) {
			if (p.matchesUuid(uuid)) return Optional.of(p);
		}
		for (StasisPlayerProfile p : profiles) {
			if (p.matchesIgn(ign)) return Optional.of(p);
		}
		return Optional.empty();
	}

	public synchronized Optional<StasisPlayerProfile> matchProfile(StasisPearl pearl) {
		if (pearl == null) return Optional.empty();
		return matchProfile(pearl.ownerUuid, pearl.ownerIgn);
	}

	public synchronized void addProfile(StasisPlayerProfile profile) throws IOException {
		if (profile == null) throw new IOException("null profile");
		profile.ensureId();
		if (profile.label == null || profile.label.isBlank()) profile.label = "Account";
		if (profile.igns == null) profile.igns = new ArrayList<>();
		if (profile.uuids == null) profile.uuids = new ArrayList<>();
		profile.defaultPearls = Math.max(0, profile.defaultPearls);
		if (findProfileById(profile.id).isPresent()) throw new IOException("duplicate profile " + profile.id);
		profiles.add(profile);
		saveNow();
	}

	public synchronized void updateProfile(StasisPlayerProfile profile) throws IOException {
		if (profile == null || profile.id == null) throw new IOException("null profile");
		for (int i = 0; i < profiles.size(); i++) {
			if (profile.id.equals(profiles.get(i).id)) {
				profiles.set(i, profile);
				saveNow();
				return;
			}
		}
		throw new IOException("unknown profile " + profile.id);
	}

	public synchronized void removeProfile(String profileId) throws IOException {
		if (profileId == null) return;
		boolean removed = profiles.removeIf(p -> profileId.equals(p.id));
		if (!removed) return;
		for (StasisHome h : homes) {
			if (h.quotaByProfile != null) h.quotaByProfile.remove(profileId);
		}
		saveNow();
	}

	/**
	 * Ensure at least one Main profile exists. Returns true if a profile was created.
	 */
	public synchronized boolean ensureBootstrapProfile(String uuid, String ign, int defaultPearls)
		throws IOException {
		if (!profiles.isEmpty()) return false;
		StasisPlayerProfile main = new StasisPlayerProfile("Main", Math.max(0, defaultPearls), true);
		if (uuid != null && !uuid.isBlank()) main.uuids.add(uuid.trim());
		if (ign != null && !ign.isBlank()) main.igns.add(ign.trim());
		profiles.add(main);
		migrateLegacyQuotas(main.id);
		saveNow();
		return true;
	}

	/** Seed home quotas from each profile's defaultPearls (overwrite empty map). */
	public synchronized void seedHomeQuotas(StasisHome home) {
		if (home == null) return;
		if (home.quotaByProfile == null) home.quotaByProfile = new HashMap<>();
		if (!home.quotaByProfile.isEmpty()) return;
		for (StasisPlayerProfile p : profiles) {
			if (p.defaultPearls > 0) home.setRequiredFor(p.id, p.defaultPearls);
		}
		if (home.quotaByProfile.isEmpty() && home.pearlsRequired > 0 && !profiles.isEmpty()) {
			home.setRequiredFor(profiles.get(0).id, home.pearlsRequired);
		}
		syncLegacyPearlsRequired(home);
	}

	private void migrateLegacyQuotas(String mainProfileId) {
		for (StasisHome h : homes) {
			if (h.quotaByProfile == null) h.quotaByProfile = new HashMap<>();
			if (h.quotaByProfile.isEmpty() && h.pearlsRequired > 0 && mainProfileId != null) {
				h.setRequiredFor(mainProfileId, h.pearlsRequired);
			}
			syncLegacyPearlsRequired(h);
		}
	}

	private static void syncLegacyPearlsRequired(StasisHome home) {
		if (home == null) return;
		int sum = 0;
		if (home.quotaByProfile != null) {
			for (Integer v : home.quotaByProfile.values()) {
				if (v != null) sum += Math.max(0, v);
			}
		}
		home.pearlsRequired = Math.max(0, sum > 0 ? sum : home.pearlsRequired);
	}

	public synchronized List<StasisChamberSlot> slotsForHome(String homeId) {
		List<StasisChamberSlot> out = new ArrayList<>();
		if (homeId == null) return out;
		for (StasisChamberSlot s : slots) {
			if (homeId.equals(s.homeId)) out.add(s);
		}
		return out;
	}

	public synchronized Optional<StasisHome> findHomeById(String id) {
		if (id == null) return Optional.empty();
		for (StasisHome h : homes) {
			if (id.equals(h.id)) return Optional.of(h);
		}
		return Optional.empty();
	}

	public synchronized Optional<StasisHome> findHomeByName(String name) {
		if (name == null) return Optional.empty();
		StasisHome folded = null;
		for (StasisHome h : homes) {
			if (name.equals(h.name)) return Optional.of(h);
			if (folded == null && h.name != null && name.equalsIgnoreCase(h.name)) folded = h;
		}
		return Optional.ofNullable(folded);
	}

	public synchronized Optional<StasisHome> findCoveringHome(String dimension, double blockX, double blockZ) {
		for (StasisHome h : homes) {
			if (h.sameDimension(dimension) && h.containsBlock(blockX, blockZ)) return Optional.of(h);
		}
		return Optional.empty();
	}

	/** True when {@code homeId} names a home in {@code dimension} whose fence holds x/z. */
	public synchronized boolean homeCovers(String homeId, String dimension, double blockX, double blockZ) {
		if (homeId == null) return false;
		for (StasisHome h : homes) {
			if (homeId.equals(h.id)) return h.sameDimension(dimension) && h.containsBlock(blockX, blockZ);
		}
		return false;
	}

	/** Covering home whose centre is nearest (overlapping fences); empty when none covers. */
	public synchronized Optional<StasisHome> nearestCoveringHome(String dimension, double blockX, double blockZ) {
		StasisHome best = null;
		double bestD = Double.MAX_VALUE;
		for (StasisHome h : homes) {
			if (!h.sameDimension(dimension) || !h.containsBlock(blockX, blockZ)) continue;
			double dx = h.centerX - blockX;
			double dz = h.centerZ - blockZ;
			double d = dx * dx + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = h;
			}
		}
		return Optional.ofNullable(best);
	}

	/** Result of {@link #repairHomeAssignments}; {@code lines} describe each move. */
	public record HomeRepair(int slotsMoved, int pearlsMoved, List<String> lines) {
		public boolean any() {
			return slotsMoved > 0 || pearlsMoved > 0;
		}
	}

	private HomeRepair lastLoadRepair = new HomeRepair(0, 0, List.of());

	/** What {@link #load} repaired (empty when the store was consistent). */
	public synchronized HomeRepair lastLoadRepair() {
		return lastLoadRepair;
	}

	/**
	 * Homes own exactly what lies inside their fence. A slot (root XZ) or pearl whose home is
	 * missing, in another dimension, or does not cover it moves to the nearest covering home,
	 * or to no home. Correct links are left alone even when fences overlap.
	 * {@code apply=false} is a dry run (audit).
	 */
	public synchronized HomeRepair repairHomeAssignments(boolean apply) throws IOException {
		List<String> lines = new ArrayList<>();
		java.util.Set<String> touchedHomes = new java.util.HashSet<>();
		int slotMoves = 0;
		for (StasisChamberSlot s : slots) {
			if (homeCovers(s.homeId, s.dimension, s.rootX, s.rootZ)) continue;
			String want = nearestCoveringHome(s.dimension, s.rootX, s.rootZ).map(h -> h.id).orElse(null);
			if (java.util.Objects.equals(want, s.homeId)) continue;
			slotMoves++;
			lines.add("slot " + s.x + " " + s.y + " " + s.z + ": " + homeName(s.homeId) + " -> " + homeName(want));
			if (apply) {
				if (s.homeId != null) touchedHomes.add(s.homeId);
				if (want != null) touchedHomes.add(want);
				s.homeId = want;
			}
		}
		int pearlMoves = 0;
		for (StasisPearl p : pearls) {
			if (homeCovers(p.homeId, p.dimension, p.x, p.z)) continue;
			String want = nearestCoveringHome(p.dimension, p.x, p.z).map(h -> h.id).orElse(null);
			if (java.util.Objects.equals(want, p.homeId)) continue;
			pearlMoves++;
			lines.add("pearl " + p.x + " " + p.y + " " + p.z
				+ (p.ownerIgn != null ? " (" + p.ownerIgn + ")" : "")
				+ ": " + homeName(p.homeId) + " -> " + homeName(want));
			if (apply) p.homeId = want;
		}
		if (apply && (slotMoves > 0 || pearlMoves > 0)) {
			save();
			for (String id : touchedHomes) renumberSlots(id);
		}
		return new HomeRepair(slotMoves, pearlMoves, List.copyOf(lines));
	}

	private String homeName(String homeId) {
		if (homeId == null) return "(no home)";
		for (StasisHome h : homes) {
			if (homeId.equals(h.id)) return h.name;
		}
		return "(missing home)";
	}

	/** Move one slot to {@code homeId} (scan found it inside that fence); renumbers both homes. */
	public synchronized void reassignSlotHome(StasisChamberSlot slot, String homeId) throws IOException {
		if (slot == null || java.util.Objects.equals(slot.homeId, homeId)) return;
		String old = slot.homeId;
		slot.homeId = homeId;
		save();
		if (old != null) renumberSlots(old);
		if (homeId != null) renumberSlots(homeId);
	}

	public synchronized Optional<StasisPearl> findPearlById(String id) {
		if (id == null) return Optional.empty();
		for (StasisPearl p : pearls) {
			if (id.equals(p.id)) return Optional.of(p);
		}
		return Optional.empty();
	}

	public synchronized Optional<StasisChamberSlot> findSlotById(String id) {
		if (id == null) return Optional.empty();
		for (StasisChamberSlot s : slots) {
			if (id.equals(s.id)) return Optional.of(s);
		}
		return Optional.empty();
	}

	public synchronized Optional<StasisChamberSlot> findSlotByRoot(String dimension, int rx, int ry, int rz) {
		for (StasisChamberSlot s : slots) {
			if (s.sameRoot(dimension, rx, ry, rz)) return Optional.of(s);
		}
		return Optional.empty();
	}

	/** Column match ignoring root Y (soul-sand rebuilds). */
	public synchronized Optional<StasisChamberSlot> findSlotByColumn(String dimension, String kind,
																	int rx, int rz) {
		for (StasisChamberSlot s : slots) {
			if (s.sameColumn(dimension, kind, rx, rz)) return Optional.of(s);
		}
		return Optional.empty();
	}

	/** Nearest slot seat within mergeBlocks. */
	public synchronized Optional<StasisChamberSlot> findSlotNear(String dimension, int x, int y, int z, int mergeBlocks) {
		StasisChamberSlot best = null;
		double bestDist = Double.MAX_VALUE;
		int mergeSq = mergeBlocks * mergeBlocks;
		for (StasisChamberSlot s : slots) {
			if (!s.sameDimension(dimension)) continue;
			int dx = s.x - x;
			int dy = s.y - y;
			int dz = s.z - z;
			int d2 = dx * dx + dy * dy + dz * dz;
			if (d2 > mergeSq) continue;
			if (d2 < bestDist) {
				bestDist = d2;
				best = s;
			}
		}
		return Optional.ofNullable(best);
	}

	/** Nearest remembered pearl within mergeBlocks (XZ), same dimension. */
	/**
	 * Like {@link #findPearlNear} but skips pearls bound to a slot other than {@code slotId}
	 * and ids in {@code exclude}. Chambers 2 blocks apart must not trade pearl records.
	 */
	public synchronized Optional<StasisPearl> findFreePearlNear(String dimension, int x, int y, int z,
															  int mergeBlocks, String slotId,
															  java.util.Set<String> exclude) {
		java.util.Set<String> boundElsewhere = new java.util.HashSet<>();
		for (StasisChamberSlot s : slots) {
			if (s.pearlId != null && (slotId == null || !slotId.equals(s.id))) boundElsewhere.add(s.pearlId);
		}
		StasisPearl best = null;
		int bestDist = Integer.MAX_VALUE;
		int mergeSq = mergeBlocks * mergeBlocks;
		for (StasisPearl p : pearls) {
			if (!p.sameDimension(dimension)) continue;
			if (boundElsewhere.contains(p.id)) continue;
			if (exclude != null && exclude.contains(p.id)) continue;
			int dx = p.x - x;
			int dy = p.y - y;
			int dz = p.z - z;
			int d2 = dx * dx + dy * dy + dz * dz;
			if (d2 > mergeSq) continue;
			if (d2 < bestDist) {
				bestDist = d2;
				best = p;
			}
		}
		return Optional.ofNullable(best);
	}

	public synchronized Optional<StasisPearl> findPearlNear(String dimension, int x, int y, int z, int mergeBlocks) {
		StasisPearl best = null;
		double bestDist = Double.MAX_VALUE;
		int mergeSq = mergeBlocks * mergeBlocks;
		for (StasisPearl p : pearls) {
			if (!p.sameDimension(dimension)) continue;
			int dx = p.x - x;
			int dy = p.y - y;
			int dz = p.z - z;
			int d2 = dx * dx + dy * dy + dz * dz;
			if (d2 > mergeSq) continue;
			if (d2 < bestDist) {
				bestDist = d2;
				best = p;
			}
		}
		return Optional.ofNullable(best);
	}

	public synchronized void addHome(StasisHome home) throws IOException {
		if (home == null) throw new IOException("null home");
		if (home.id == null || home.id.isBlank()) home.id = UUID.randomUUID().toString();
		if (home.pearlsRequired < 0) home.pearlsRequired = 0;
		if (home.quotaByProfile == null) home.quotaByProfile = new HashMap<>();
		seedHomeQuotas(home);
		if (findHomeById(home.id).isPresent()) throw new IOException("duplicate home " + home.id);
		homes.add(home);
		saveNow();
	}

	public synchronized void updateHome(StasisHome home) throws IOException {
		if (home == null || home.id == null) throw new IOException("null home");
		if (home.quotaByProfile == null) home.quotaByProfile = new HashMap<>();
		syncLegacyPearlsRequired(home);
		for (int i = 0; i < homes.size(); i++) {
			if (home.id.equals(homes.get(i).id)) {
				homes.set(i, home);
				saveNow();
				return;
			}
		}
		throw new IOException("unknown home " + home.id);
	}

	public synchronized void removeHome(String id) throws IOException {
		boolean removed = homes.removeIf(h -> h.id != null && h.id.equals(id));
		if (!removed) throw new IOException("unknown home " + id);
		for (StasisPearl p : pearls) {
			if (id.equals(p.homeId)) p.homeId = null;
		}
		slots.removeIf(s -> id.equals(s.homeId));
		saveNow();
	}

	public synchronized void addPearl(StasisPearl pearl) throws IOException {
		if (pearl == null) throw new IOException("null pearl");
		if (pearl.id == null || pearl.id.isBlank()) pearl.id = UUID.randomUUID().toString();
		if (findPearlById(pearl.id).isPresent()) throw new IOException("duplicate pearl " + pearl.id);
		pearls.add(pearl);
		save();
	}

	public synchronized void updatePearl(StasisPearl pearl) throws IOException {
		if (pearl == null || pearl.id == null) throw new IOException("null pearl");
		for (int i = 0; i < pearls.size(); i++) {
			if (pearl.id.equals(pearls.get(i).id)) {
				pearls.set(i, pearl);
				save();
				return;
			}
		}
		throw new IOException("unknown pearl " + pearl.id);
	}

	public synchronized void removePearl(String id) throws IOException {
		boolean removed = pearls.removeIf(p -> p.id != null && p.id.equals(id));
		if (!removed) throw new IOException("unknown pearl " + id);
		for (StasisChamberSlot s : slots) {
			if (id.equals(s.pearlId)) s.pearlId = null;
		}
		save();
	}

	/**
	 * Insert or merge by root, column (XZ+kind), or water seat column.
	 * Soul-sand Y drift and orphan roots that share a seat merge into one slot.
	 */
	public synchronized void upsertSlot(StasisChamberSlot slot) throws IOException {
		if (slot == null) throw new IOException("null slot");
		if (slot.id == null || slot.id.isBlank()) slot.id = UUID.randomUUID().toString();
		if (slot.firstSeenMs <= 0) slot.firstSeenMs = System.currentTimeMillis();
		for (int i = 0; i < slots.size(); i++) {
			StasisChamberSlot existing = slots.get(i);
			boolean sameId = existing.id.equals(slot.id);
			boolean sameRoot = existing.sameRoot(slot.dimension, slot.rootX, slot.rootY, slot.rootZ);
			boolean sameCol = !sameRoot && existing.sameColumn(slot);
			boolean sameSeat = !sameId && !sameRoot && !sameCol && existing.sameSeatColumn(slot);
			if (!sameId && !sameRoot && !sameCol && !sameSeat) continue;

			boolean changed = false;
			boolean needRenumber = false;
			if ((sameCol || sameSeat) && (existing.rootY != slot.rootY
				|| existing.rootX != slot.rootX || existing.rootZ != slot.rootZ)) {
				// Prefer aligned water root (seat XZ == root XZ) over elevator orphans.
				boolean incomingAligned = slot.waterSeatAligned();
				boolean existingAligned = existing.waterSeatAligned();
				if (incomingAligned || !existingAligned) {
					existing.rootX = slot.rootX;
					existing.rootY = slot.rootY;
					existing.rootZ = slot.rootZ;
					changed = true;
				}
			}
			if (slot.geometryOk != null && !slot.geometryOk.equals(existing.geometryOk)) {
				existing.geometryOk = slot.geometryOk;
				changed = true;
				needRenumber = true;
			}
			if (slot.pearlId != null && !slot.pearlId.equals(existing.pearlId)) {
				existing.pearlId = slot.pearlId;
				changed = true;
			}
			if (slot.x != existing.x || slot.y != existing.y || slot.z != existing.z) {
				if (slot.geometryOk == null || Boolean.TRUE.equals(slot.geometryOk)
					|| Boolean.TRUE.equals(existing.geometryOk)) {
					existing.x = slot.x;
					existing.y = slot.y;
					existing.z = slot.z;
					changed = true;
				}
			}
			if (existing.firstSeenMs <= 0) {
				existing.firstSeenMs = slot.firstSeenMs > 0 ? slot.firstSeenMs : System.currentTimeMillis();
				changed = true;
			}
			if (existing.homeId == null && slot.homeId != null) {
				existing.homeId = slot.homeId;
				changed = true;
				needRenumber = true;
			}
			// Drop any other column / seat duplicates left over from older stores.
			if (purgeColumnDuplicates(existing) || purgeSeatDuplicates(existing)) {
				changed = true;
				needRenumber = true;
			}
			if (changed) save();
			if (needRenumber && existing.homeId != null) renumberSlots(existing.homeId);
			return;
		}
		slots.add(slot);
		boolean purged = purgeColumnDuplicates(slot) || purgeSeatDuplicates(slot);
		save();
		if (slot.homeId != null) renumberSlots(slot.homeId);
		else if (purged) { /* no home */ }
	}

	/**
	 * Collapse slots that share dimension+kind+rootXZ or the same seat XZ into one winner.
	 * Run on load so rebuilt soul-sand Y / orphan roots no longer double the panel.
	 */
	public synchronized boolean collapseDuplicateColumns() throws IOException {
		boolean byRoot = collapseByKey(true);
		boolean bySeat = collapseByKey(false);
		return byRoot || bySeat;
	}

	private boolean collapseByKey(boolean rootKey) throws IOException {
		java.util.LinkedHashMap<String, StasisChamberSlot> winners = new java.util.LinkedHashMap<>();
		List<StasisChamberSlot> losers = new ArrayList<>();
		for (StasisChamberSlot s : slots) {
			if (s == null || s.dimension == null) continue;
			String kind = s.kind == null || s.kind.isBlank() ? StasisChamberSlot.KIND_WATER : s.kind;
			String key = rootKey
				? s.dimension + "|" + kind + "|" + s.rootX + "|" + s.rootZ
				: s.dimension + "|" + kind + "|seat|" + s.x + "|" + s.z;
			StasisChamberSlot prev = winners.get(key);
			if (prev == null) {
				winners.put(key, s);
				continue;
			}
			StasisChamberSlot keep = preferColumnSlot(prev, s);
			StasisChamberSlot drop = keep == prev ? s : prev;
			winners.put(key, keep);
			losers.add(drop);
			if (drop.pearlId != null && (keep.pearlId == null || keep.pearlId.isBlank())) {
				keep.pearlId = drop.pearlId;
			}
			if (drop.firstSeenMs > 0 && (keep.firstSeenMs <= 0 || drop.firstSeenMs < keep.firstSeenMs)) {
				keep.firstSeenMs = drop.firstSeenMs;
			}
			if (Boolean.TRUE.equals(drop.geometryOk) && !Boolean.TRUE.equals(keep.geometryOk)) {
				keep.geometryOk = true;
			}
			if (keep.waterSeatAligned()) {
				// keep root
			} else if (drop.waterSeatAligned()) {
				keep.rootX = drop.rootX;
				keep.rootY = drop.rootY;
				keep.rootZ = drop.rootZ;
				keep.x = drop.x;
				keep.y = drop.y;
				keep.z = drop.z;
			} else if (drop.rootY > keep.rootY && !Boolean.FALSE.equals(drop.geometryOk)) {
				keep.rootX = drop.rootX;
				keep.rootY = drop.rootY;
				keep.rootZ = drop.rootZ;
				keep.x = drop.x;
				keep.y = drop.y;
				keep.z = drop.z;
			}
		}
		if (losers.isEmpty()) return false;
		java.util.HashSet<String> dropIds = new java.util.HashSet<>();
		for (StasisChamberSlot d : losers) {
			if (d.id != null) dropIds.add(d.id);
		}
		boolean changed = slots.removeIf(s -> s != null && s.id != null && dropIds.contains(s.id));
		if (changed) {
			save();
			java.util.HashSet<String> homesTouched = new java.util.HashSet<>();
			for (StasisChamberSlot s : winners.values()) {
				if (s.homeId != null) homesTouched.add(s.homeId);
			}
			for (String hid : homesTouched) renumberSlots(hid);
		}
		return changed;
	}

	/** Prefer aligned water root, then geometry-ok, then pearl-bound, then newer root Y. */
	private static StasisChamberSlot preferColumnSlot(StasisChamberSlot a, StasisChamberSlot b) {
		boolean aAligned = a.waterSeatAligned();
		boolean bAligned = b.waterSeatAligned();
		if (aAligned != bAligned) return bAligned ? b : a;
		int scoreA = columnScore(a);
		int scoreB = columnScore(b);
		if (scoreB != scoreA) return scoreB > scoreA ? b : a;
		if (b.rootY != a.rootY) return b.rootY > a.rootY ? b : a;
		return b.firstSeenMs >= a.firstSeenMs ? b : a;
	}

	private static int columnScore(StasisChamberSlot s) {
		int n = 0;
		if (Boolean.TRUE.equals(s.geometryOk)) n += 4;
		else if (s.geometryOk == null) n += 1;
		if (s.pearlId != null && !s.pearlId.isBlank()) n += 2;
		return n;
	}

	/** Remove other slots sharing this column; keep {@code keep}. */
	private boolean purgeColumnDuplicates(StasisChamberSlot keep) {
		if (keep == null || keep.id == null) return false;
		return slots.removeIf(s -> s != null && !keep.id.equals(s.id) && keep.sameColumn(s));
	}

	/** Remove other slots sharing this seat XZ; keep {@code keep}. */
	private boolean purgeSeatDuplicates(StasisChamberSlot keep) {
		if (keep == null || keep.id == null) return false;
		return slots.removeIf(s -> s != null && !keep.id.equals(s.id) && keep.sameSeatColumn(s));
	}

	/**
	 * Assign #1…#N clockwise from the positive end of the dominant XZ axis around the seat centroid.
	 * Broken (geometryOk=false) slots get number 0 so they never duplicate viable #N.
	 */
	public synchronized void renumberSlots(String homeId) throws IOException {
		if (homeId == null) return;
		List<StasisChamberSlot> viable = new ArrayList<>();
		boolean changed = false;
		for (StasisChamberSlot s : slots) {
			if (!homeId.equals(s.homeId)) continue;
			if (Boolean.FALSE.equals(s.geometryOk)) {
				if (s.number != 0) {
					s.number = 0;
					changed = true;
				}
				continue;
			}
			viable.add(s);
		}
		if (viable.isEmpty()) {
			if (changed) save();
			return;
		}

		double cx = 0;
		double cz = 0;
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (StasisChamberSlot s : viable) {
			cx += s.x;
			cz += s.z;
			minX = Math.min(minX, s.x);
			maxX = Math.max(maxX, s.x);
			minZ = Math.min(minZ, s.z);
			maxZ = Math.max(maxZ, s.z);
		}
		cx /= viable.size();
		cz /= viable.size();
		boolean xDominant = (maxX - minX) >= (maxZ - minZ);

		StasisChamberSlot start = viable.get(0);
		double bestProj = Double.NEGATIVE_INFINITY;
		for (StasisChamberSlot s : viable) {
			double proj = xDominant ? s.x : s.z;
			if (proj > bestProj) {
				bestProj = proj;
				start = s;
			}
		}
		final double startAng = Math.atan2(start.z - cz, start.x - cx);
		final double fcx = cx;
		final double fcz = cz;

		viable.sort(Comparator.comparingDouble((StasisChamberSlot s) -> {
			double ang = Math.atan2(s.z - fcz, s.x - fcx);
			double d = startAng - ang; // clockwise from start
			while (d < 0) d += Math.PI * 2;
			while (d >= Math.PI * 2) d -= Math.PI * 2;
			return d;
		}).thenComparingInt(s -> s.x).thenComparingInt(s -> s.z));

		for (int i = 0; i < viable.size(); i++) {
			int n = i + 1;
			if (viable.get(i).number != n) {
				viable.get(i).number = n;
				changed = true;
			}
		}
		if (changed) save();
	}

	/** Update geometryOk only when it changes (no seat rewrite). Renumbers home on flip. */
	public synchronized boolean setSlotGeometryOk(StasisChamberSlot slot, boolean ok) throws IOException {
		if (slot == null || slot.id == null) return false;
		if (slot.geometryOk != null && slot.geometryOk == ok) return false;
		slot.geometryOk = ok;
		updateSlot(slot);
		if (slot.homeId != null) renumberSlots(slot.homeId);
		return true;
	}

	public synchronized void updateSlot(StasisChamberSlot slot) throws IOException {
		if (slot == null || slot.id == null) throw new IOException("null slot");
		for (int i = 0; i < slots.size(); i++) {
			if (slot.id.equals(slots.get(i).id)) {
				slots.set(i, slot);
				save();
				return;
			}
		}
		throw new IOException("unknown slot " + slot.id);
	}

	/** Bind nearest slot within mergeBlocks to pearl; clears other slots pointing at this pearl. */
	public synchronized void bindPearlToNearestSlot(StasisPearl pearl, int mergeBlocks) throws IOException {
		if (pearl == null) return;
		boolean changed = false;
		String nearId = findSlotNear(pearl.dimension, pearl.x, pearl.y, pearl.z, mergeBlocks)
			.map(s -> s.id).orElse(null);
		for (StasisChamberSlot s : slots) {
			boolean shouldBind = nearId != null && nearId.equals(s.id);
			boolean isBound = pearl.id.equals(s.pearlId);
			if (shouldBind && !isBound) {
				s.pearlId = pearl.id;
				changed = true;
			} else if (!shouldBind && isBound) {
				s.pearlId = null;
				changed = true;
			}
		}
		if (changed) save();
	}

	/** Live loaded pearl count for a home (any owner). Prefer {@link #loadedOwnPearlCount}. */
	public synchronized int loadedPearlCount(StasisHome home) {
		if (home == null) return 0;
		int n = 0;
		for (StasisPearl p : pearls) {
			if (!p.loaded) continue;
			if (!home.sameDimension(p.dimension)) continue;
			if (!home.containsBlock(p.x, p.z)) continue;
			n++;
		}
		return n;
	}

	/** Loaded pearls owned by {@code localUuid} inside the home geofence (legacy). */
	public synchronized int loadedOwnPearlCount(StasisHome home, String localUuid) {
		if (home == null || localUuid == null || localUuid.isBlank()) return 0;
		int n = 0;
		for (StasisPearl p : pearls) {
			if (!p.loaded) continue;
			if (!p.ownedBy(localUuid)) continue;
			if (!home.sameDimension(p.dimension)) continue;
			if (!home.containsBlock(p.x, p.z)) continue;
			n++;
		}
		return n;
	}

	public synchronized int loadedProfilePearlCount(StasisHome home, StasisPlayerProfile profile) {
		if (home == null || profile == null) return 0;
		int n = 0;
		for (StasisPearl p : pearls) {
			if (!p.loaded) continue;
			if (!profile.matchesPearl(p)) continue;
			if (!home.sameDimension(p.dimension)) continue;
			if (!home.containsBlock(p.x, p.z)) continue;
			n++;
		}
		return n;
	}

	public synchronized int missingPearlCount(StasisHome home) {
		return homeCapacity(home, null, null).alertMissing();
	}

	/** Missing for notify-gated profiles (preferred for alerts). */
	public synchronized int missingOwnPearlCount(StasisHome home, String localUuid) {
		return homeCapacity(home, localUuid, null).alertMissing();
	}

	public record ProfileQuota(
		String profileId,
		String label,
		int loaded,
		int required,
		boolean notifyEnabled,
		boolean active
	) {
		public int missing() {
			return Math.max(0, required - loaded);
		}

		public boolean shortfall() {
			return required > 0 && loaded < required;
		}

		public boolean alertShort() {
			return notifyEnabled && shortfall();
		}

		public String compact() {
			String base = label + " " + loaded + "/" + required;
			if (!notifyEnabled && required > 0) return base + " (silent)";
			return base;
		}
	}

	/**
	 * Quota vs chamber capacity for a home (multi-profile).
	 * Broken slots are excluded from viable/free. Capacity is unknown when {@code viable == 0}.
	 */
	public record HomeCapacity(
		List<ProfileQuota> quotas,
		int ownLoaded,
		int required,
		int missing,
		int alertMissing,
		int viable,
		int free,
		int othersOccupied,
		int chambersNeeded,
		boolean slotsKnown
	) {
		public String summaryLine() {
			String accounts = quotasLine();
			if (!slotsKnown) {
				return accounts + "    chambers unknown - load home chunks";
			}
			String base = accounts + "    free " + free + "/" + viable + "    others " + othersOccupied;
			if (chambersNeeded > 0) {
				return base + "    need +" + chambersNeeded + " chamber"
					+ (chambersNeeded == 1 ? "" : "s");
			}
			return base;
		}

		public String quotasLine() {
			if (quotas == null || quotas.isEmpty()) {
				return "Yours " + ownLoaded + "/" + required;
			}
			StringBuilder sb = new StringBuilder();
			for (ProfileQuota q : quotas) {
				if (q.required() <= 0) continue;
				if (!sb.isEmpty()) sb.append("  ");
				if (q.active()) sb.append('*');
				sb.append(q.compact());
			}
			return sb.isEmpty() ? "Yours " + ownLoaded + "/" + required : sb.toString();
		}

		public String titleSuffix() {
			if (quotas == null || quotas.isEmpty()) return ownLoaded + "/" + required;
			for (ProfileQuota q : quotas) {
				if (q.active() && q.required() > 0) return q.loaded() + "/" + q.required();
			}
			for (ProfileQuota q : quotas) {
				if (q.required() > 0) return q.loaded() + "/" + q.required();
			}
			return ownLoaded + "/" + required;
		}

		public String capacityAlertLine() {
			if (!slotsKnown) {
				return "Chambers: unknown - load home chunks";
			}
			String base = "Chambers: " + viable + " viable, " + free + " free, "
				+ othersOccupied + " others";
			if (chambersNeeded > 0) {
				return base + " | need +" + chambersNeeded + " chamber"
					+ (chambersNeeded == 1 ? "" : "s") + " to hit quota";
			}
			return base;
		}

		public boolean anyAlertShort() {
			return alertMissing > 0;
		}
	}

	/**
	 * @param sessionUuid / sessionIgn identify the active profile (marked with *)
	 */
	public synchronized HomeCapacity homeCapacity(StasisHome home, String sessionUuid, String sessionIgn) {
		if (home == null) {
			return new HomeCapacity(List.of(), 0, 0, 0, 0, 0, 0, 0, 0, false);
		}
		Optional<StasisPlayerProfile> active = matchProfile(sessionUuid, sessionIgn);

		List<ProfileQuota> quotas = new ArrayList<>();
		int totalLoaded = 0;
		int totalRequired = 0;
		int totalMissing = 0;
		int alertMissing = 0;
		for (StasisPlayerProfile profile : profiles) {
			int req = home.requiredFor(profile.id);
			if (req <= 0 && profile.defaultPearls <= 0) {
				// still show if home has explicit 0? skip zero-required
				continue;
			}
			if (req <= 0) continue;
			int loaded = loadedProfilePearlCount(home, profile);
			boolean isActive = active.isPresent() && profile.id.equals(active.get().id);
			ProfileQuota q = new ProfileQuota(
				profile.id, profile.displayLabel(), loaded, req, profile.notify, isActive);
			quotas.add(q);
			totalLoaded += loaded;
			totalRequired += req;
			totalMissing += q.missing();
			if (q.alertShort()) alertMissing += q.missing();
		}

		// Legacy fallback: no profile quotas configured
		if (quotas.isEmpty()) {
			int ownLoaded = sessionUuid == null || sessionUuid.isBlank()
				? 0 : loadedOwnPearlCount(home, sessionUuid);
			int required = Math.max(0, home.pearlsRequired);
			int missing = Math.max(0, required - ownLoaded);
			quotas.add(new ProfileQuota(
				"_legacy", "Yours", ownLoaded, required, true, true));
			totalLoaded = ownLoaded;
			totalRequired = required;
			totalMissing = missing;
			alertMissing = missing;
		}

		int viable = 0;
		int free = 0;
		int othersOccupied = 0;
		for (StasisChamberSlot s : slots) {
			if (!home.id.equals(s.homeId)) continue;
			if (Boolean.FALSE.equals(s.geometryOk)) continue;
			viable++;
			StasisPearl bound = s.pearlId != null ? findPearlById(s.pearlId).orElse(null) : null;
			boolean loaded = bound != null && bound.loaded;
			if (!loaded) {
				free++;
				continue;
			}
			boolean tracked = matchProfile(bound).isPresent();
			if (!tracked) othersOccupied++;
		}

		boolean slotsKnown = viable > 0;
		int chambersNeeded = slotsKnown ? Math.max(0, alertMissing - free) : 0;
		return new HomeCapacity(
			List.copyOf(quotas),
			totalLoaded, totalRequired, totalMissing, alertMissing,
			viable, free, othersOccupied, chambersNeeded, slotsKnown);
	}

	/** Back-compat: session UUID only. */
	public synchronized HomeCapacity homeCapacity(StasisHome home, String localUuid) {
		return homeCapacity(home, localUuid, null);
	}

	private void load() throws IOException {
		FileModel model = GSON.fromJson(Files.readString(file), FileModel.class);
		if (model == null) return;
		if (model.schemaVersion < 1 || model.schemaVersion > SCHEMA) {
			throw new IOException("chambers.json schema_version " + model.schemaVersion + " != " + SCHEMA);
		}
		homes.clear();
		pearls.clear();
		slots.clear();
		profiles.clear();
		if (model.profiles != null) {
			for (StasisPlayerProfile p : model.profiles) {
				if (p == null) continue;
				p.ensureId();
				if (p.label == null || p.label.isBlank()) p.label = "Account";
				if (p.igns == null) p.igns = new ArrayList<>();
				if (p.uuids == null) p.uuids = new ArrayList<>();
				p.defaultPearls = Math.max(0, p.defaultPearls);
				profiles.add(p);
			}
		}
		boolean clamped = false;
		if (model.homes != null) {
			for (StasisHome h : model.homes) {
				if (h == null || h.id == null || h.id.isBlank()) continue;
				if (h.pearlsRequired < 0) h.pearlsRequired = 0;
				// Edit UI caps 1-4; older stores kept 8 (17x17 chunk fence at runtime).
				int r = Math.min(StasisHome.MAX_RADIUS_CHUNKS, Math.max(1, h.radiusChunks));
				if (r != h.radiusChunks) {
					h.radiusChunks = r;
					clamped = true;
				}
				if (h.quotaByProfile == null) h.quotaByProfile = new HashMap<>();
				homes.add(h);
			}
		}
		if (model.pearls != null) {
			for (StasisPearl p : model.pearls) {
				if (p == null || p.id == null || p.id.isBlank()) continue;
				if (p.chamberKind == null || p.chamberKind.isBlank()) p.chamberKind = "unknown";
				if (p.firstSeenMs <= 0) p.firstSeenMs = p.lastSeenMs > 0 ? p.lastSeenMs : System.currentTimeMillis();
				pearls.add(p);
			}
		}
		if (model.slots != null) {
			for (StasisChamberSlot s : model.slots) {
				if (s == null || s.id == null || s.id.isBlank()) continue;
				if (s.kind == null || s.kind.isBlank()) s.kind = StasisChamberSlot.KIND_WATER;
				if (s.firstSeenMs <= 0) s.firstSeenMs = s.lastSeenMs > 0 ? s.lastSeenMs : System.currentTimeMillis();
				slots.add(s);
			}
		}
		if (!profiles.isEmpty()) {
			migrateLegacyQuotas(profiles.get(0).id);
		}
		if (model.schemaVersion < SCHEMA || clamped) save();
		collapseDuplicateColumns();
		// Ensure #N for homes that predate numbering
		for (StasisHome h : homes) {
			boolean needs = false;
			for (StasisChamberSlot s : slots) {
				if (h.id.equals(s.homeId) && s.number <= 0 && !Boolean.FALSE.equals(s.geometryOk)) {
					needs = true;
					break;
				}
			}
			if (needs) renumberSlots(h.id);
		}
		HomeRepair dry = repairHomeAssignments(false);
		if (dry.any()) {
			// One-time safety copy of the pre-repair store next to it.
			Path backup = base.resolve("chambers.pre-home-repair.json");
			if (!Files.exists(backup)) Files.copy(file, backup);
			lastLoadRepair = repairHomeAssignments(true);
		}
	}

	/** Mutation hook: deferred mode marks dirty; otherwise writes immediately. */
	private void save() throws IOException {
		long t0 = StasisPerf.start();
		StasisPerf.countSaveCaller();
		revision++;
		if (deferredWrites) {
			dirty = true;
		} else {
			writeSync();
		}
		StasisPerf.stop(StasisPerf.Bucket.STORE_SAVE, t0);
	}

	private String serialize() {
		FileModel model = new FileModel();
		model.schemaVersion = SCHEMA;
		model.homes = homes;
		model.pearls = pearls;
		model.slots = slots;
		model.profiles = profiles;
		String json = GSON.toJson(model);
		StasisPerf.add(StasisPerf.Counter.SAVE_BYTES, json.length());
		return json;
	}

	private static final class FileModel {
		@SerializedName("schema_version")
		int schemaVersion = SCHEMA;
		List<StasisHome> homes = new ArrayList<>();
		List<StasisPearl> pearls = new ArrayList<>();
		List<StasisChamberSlot> slots = new ArrayList<>();
		List<StasisPlayerProfile> profiles = new ArrayList<>();
	}
}
