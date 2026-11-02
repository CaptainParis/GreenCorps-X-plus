package dev.greencorps.export;

import dev.greencorps.backup.BackupEngine;
import dev.greencorps.backup.Home;
import dev.greencorps.backup.SnapshotIndex;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.util.XaeroWaypoints;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * Auto-playable: one stable folder per home under {@code saves/} (overwrite in place).
 * Manual archive export: timestamped LevelName folder. Never blocks the render thread.
 */
public final class PlayableSavePublisher {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/playable");
	private static final int MAX_NAME_LEN = 48;
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
		.withZone(ZoneId.systemDefault());

	/** Marker in the auto-playable folder: snapshot key it was exported from. */
	static final String PUBLISHED_MARKER = ".greencorps-playable";

	/** Home id → publishes queued or running for it. */
	private static final ConcurrentHashMap<String, Integer> IN_FLIGHT = new ConcurrentHashMap<>();

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "greencorps-playable-export");
		t.setDaemon(true);
		return t;
	});

	/** Thrown / detected when a home has never been committed on-site. */
	public static final class NoBackupDataException extends IOException {
		public NoBackupDataException(String homeName) {
			super("no backup data yet for " + homeName);
		}
	}

	private PlayableSavePublisher() {}

	public static String sanitize(String name) {
		if (name == null || name.isBlank()) return "home";
		StringBuilder sb = new StringBuilder(name.length());
		char prev = 0;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
				|| (c >= '0' && c <= '9') || c == '-' || c == '_';
			char out = ok ? c : '-';
			if (out == '-' && prev == '-') continue;
			sb.append(out);
			prev = out;
			if (sb.length() >= MAX_NAME_LEN) break;
		}
		while (!sb.isEmpty() && sb.charAt(0) == '-') sb.deleteCharAt(0);
		while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
		return sb.isEmpty() ? "home" : sb.toString();
	}

	/** Filesystem-safe base from waypoint/home display name (no timestamp). */
	public static String nameBase(Home home) {
		String raw = home == null ? null : home.name;
		String shown = XaeroWaypoints.displayName(raw);
		if (shown == null || shown.isBlank()) shown = raw;
		return sanitize(shown);
	}

	public static String displayName(Home home) {
		if (home == null) return "home";
		String shown = XaeroWaypoints.displayName(home.name);
		if (shown == null || shown.isBlank()) shown = home.name;
		return shown == null || shown.isBlank() ? "home" : shown.trim();
	}

	/** Stable auto-playable folder name (one per home). */
	public static String autoFolderName(Home home) {
		return nameBase(home);
	}

	/** LevelName for auto / latest playable. */
	public static String backupLevelName(Home home, Instant when) {
		return displayName(home) + " [BACKUP: " + TS.format(when) + "]";
	}

	/** LevelName / archive folder for a manual Time Machine export. */
	public static String archiveLevelName(Home home, Instant when) {
		String tag = home != null && home.isSnapshot() ? "SNAPSHOT" : "BACKUP";
		return displayName(home) + " [" + tag + ": " + TS.format(when) + "]";
	}

	/** FS-safe archive folder (spaces kept; colon avoided). */
	public static String archiveFolderName(Home home, Instant when) {
		String tag = home != null && home.isSnapshot() ? "SNAPSHOT" : "BACKUP";
		String raw = displayName(home) + " [" + tag + " " + TS.format(when) + "]";
		return folderSafe(raw);
	}

	static String folderSafe(String raw) {
		if (raw == null || raw.isBlank()) return "home";
		StringBuilder sb = new StringBuilder(raw.length());
		char prev = 0;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == ':' || c == '/' || c == '\\' || c == '<' || c == '>' || c == '"'
				|| c == '|' || c == '?' || c == '*') {
				c = '-';
			}
			if (c < 32) continue;
			if (c == ' ' && prev == ' ') continue;
			sb.append(c);
			prev = c;
			if (sb.length() >= 96) break;
		}
		String out = sb.toString().trim();
		while (out.endsWith(".")) out = out.substring(0, out.length() - 1).trim();
		return out.isEmpty() ? "home" : out;
	}

	/** @deprecated use {@link #autoFolderName} or {@link #archiveFolderName} */
	@Deprecated
	public static String worldStem(Home home) {
		return autoFolderName(home);
	}

	/** @deprecated use {@link #archiveFolderName} */
	@Deprecated
	public static String worldStem(Home home, Instant when) {
		return archiveFolderName(home, when);
	}

	/** Legacy {@code bb-<base>-*} prefix + stable auto folder for prune. */
	public static String siblingPrefix(Home home) {
		return "bb-" + nameBase(home);
	}

	public static Optional<Path> latestFolder(Home home) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || home == null) return Optional.empty();
		return latestFolder(savesRoot(mc), home);
	}

	public static Optional<Path> latestFolder(Path savesRoot, Home home) {
		if (savesRoot == null || home == null || !Files.isDirectory(savesRoot)) return Optional.empty();
		Path stable = savesRoot.resolve(autoFolderName(home));
		if (Files.isDirectory(stable)) return Optional.of(stable);
		String prefix = siblingPrefix(home);
		Path best = null;
		long bestMtime = Long.MIN_VALUE;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesRoot)) {
			for (Path p : stream) {
				String n = p.getFileName().toString();
				if (!n.equals(prefix) && !n.startsWith(prefix + "-") && !n.startsWith(prefix + ".")) continue;
				if (n.contains(".tmp")) continue;
				if (!Files.isDirectory(p)) continue;
				long m = Files.getLastModifiedTime(p).toMillis();
				if (m >= bestMtime) {
					bestMtime = m;
					best = p;
				}
			}
		} catch (IOException e) {
			return Optional.empty();
		}
		return Optional.ofNullable(best);
	}

	@Deprecated
	public static Path stableDir(Path savesRoot, Home home) {
		return savesRoot.resolve(autoFolderName(home));
	}

	public static Path savesRoot(MinecraftClient mc) {
		return mc.runDirectory.toPath().resolve("saves");
	}

	public static boolean hasBackupData(BackupEngine engine, Home home) {
		if (engine == null || home == null) return false;
		try {
			return new SnapshotIndex(engine.refsDir(), home.id).latest().isPresent();
		} catch (IOException e) {
			return false;
		}
	}

	public static void submit(Runnable task) {
		WORKER.submit(task);
	}

	public static void publishLatestAsync(BackupEngine engine, Home home, Path savesRoot) {
		publishLatestAsync(engine, home, savesRoot, false);
	}

	/** Always publishes (manual export, confirm screen, post-commit refresh). */
	public static void publishLatestAsync(BackupEngine engine, Home home, Path savesRoot,
										  boolean shiftCoordsTo0) {
		claim(home);
		WORKER.submit(() -> {
			try {
				runPublishLatest(engine, home, savesRoot, shiftCoordsTo0);
			} finally {
				release(home);
			}
		});
	}

	private static void runPublishLatest(BackupEngine engine, Home home, Path savesRoot,
										 boolean shiftCoordsTo0) {
		try {
			if (!hasBackupData(engine, home)) {
				chatInfo("Playable: " + displayName(home)
					+ " — no backup data yet (visit the home to capture first).");
				return;
			}
			Path dest = publishLatest(engine, home, savesRoot, shiftCoordsTo0);
			chatInfo("Playable save ready: " + dest.getFileName());
		} catch (NoBackupDataException e) {
			chatInfo("Playable: " + displayName(home)
				+ " — no backup data yet (visit the home to capture first).");
		} catch (Exception e) {
			LOG.warn("playable publish {}: {}", home.name, e.toString());
			chatWarn("Playable save failed for " + displayName(home) + ": " + shortError(e));
		}
	}

	public static void publishEligibleAsync(BackupEngine engine, Collection<Home> homes,
										   Path savesRoot, Predicate<Home> eligible) {
		publishEligibleAsync(engine, homes, savesRoot, eligible, false);
	}

	public static void publishEligibleAsync(BackupEngine engine, Collection<Home> homes,
										   Path savesRoot, Predicate<Home> eligible,
										   boolean shiftCoordsTo0) {
		// Auto trigger (disconnect): skip homes already queued / running, and on the worker
		// skip homes whose playable folder already holds the latest snapshot.
		List<Home> list = new ArrayList<>();
		for (Home h : homes) {
			if (h == null || !eligible.test(h)) continue;
			if (isInFlight(h)) {
				LOG.debug("playable {}: publish already queued, skipping", h.name);
				continue;
			}
			claim(h);
			list.add(h);
		}
		if (list.isEmpty()) return;
		WORKER.submit(() -> {
			List<Home> stale = new ArrayList<>();
			for (Home h : list) {
				if (isUpToDate(engine, h, savesRoot, shiftCoordsTo0)) {
					release(h);
				} else {
					stale.add(h);
				}
			}
			int skipped = list.size() - stale.size();
			if (skipped > 0) {
				LOG.info("playable: {} save(s) already match latest snapshot, skipped", skipped);
			}
			if (stale.isEmpty()) return;
			chatInfo("Updating " + stale.size() + " playable save(s) in background…");
			for (Home h : stale) {
				WORKER.submit(() -> {
					try {
						runPublishLatest(engine, h, savesRoot, shiftCoordsTo0);
					} finally {
						release(h);
					}
				});
			}
		});
	}

	/** Snapshot identity a playable folder was exported from (coord shift changes the output). */
	static String publishKey(SnapshotIndex.Entry entry, boolean shiftCoordsTo0) {
		return entry.epochMs + "|" + entry.filename + "|" + (shiftCoordsTo0 ? "shift0" : "abs");
	}

	/** True when the stable auto folder exists and was last published from the latest snapshot. */
	static boolean isUpToDate(BackupEngine engine, Home home, Path savesRoot, boolean shiftCoordsTo0) {
		if (engine == null || home == null || home.id == null || savesRoot == null) return false;
		try {
			Optional<SnapshotIndex.Entry> latest = new SnapshotIndex(engine.refsDir(), home.id).latest();
			if (latest.isEmpty()) return false;
			return isPublished(savesRoot.resolve(autoFolderName(home)), publishKey(latest.get(), shiftCoordsTo0));
		} catch (IOException e) {
			return false;
		}
	}

	static boolean isPublished(Path folder, String key) {
		if (folder == null || key == null || !Files.isDirectory(folder)) return false;
		Path marker = folder.resolve(PUBLISHED_MARKER);
		try {
			return Files.isRegularFile(marker)
				&& key.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
		} catch (IOException e) {
			return false;
		}
	}

	static void writePublished(Path folder, String key) {
		try {
			Files.writeString(folder.resolve(PUBLISHED_MARKER), key, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.debug("playable marker skipped: {}", e.toString());
		}
	}

	private static boolean isInFlight(Home home) {
		return home != null && home.id != null && IN_FLIGHT.containsKey(home.id);
	}

	private static void claim(Home home) {
		if (home != null && home.id != null) IN_FLIGHT.merge(home.id, 1, Integer::sum);
	}

	private static void release(Home home) {
		if (home != null && home.id != null) {
			IN_FLIGHT.computeIfPresent(home.id, (k, n) -> n <= 1 ? null : n - 1);
		}
	}

	/**
	 * Overwrite the stable auto-playable folder for this home (latest snapshot).
	 * Prunes legacy {@code bb-<base>-*} siblings.
	 */
	public static Path publishLatest(BackupEngine engine, Home home, Path savesRoot) throws IOException {
		return publishLatest(engine, home, savesRoot, false);
	}

	public static Path publishLatest(BackupEngine engine, Home home, Path savesRoot,
									 boolean shiftCoordsTo0) throws IOException {
		SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), home.id);
		Optional<SnapshotIndex.Entry> latest = idx.latest();
		if (latest.isEmpty()) {
			throw new NoBackupDataException(home.name);
		}
		Instant when = Instant.ofEpochMilli(latest.get().epochMs);
		String levelName = backupLevelName(home, when);
		String folder = autoFolderName(home);
		Path dest = exportToFolder(engine, home, savesRoot, latest.get(), idx, folder, levelName, shiftCoordsTo0, true);
		writePublished(dest, publishKey(latest.get(), shiftCoordsTo0));
		return dest;
	}

	/** Manual archive export of a specific snapshot entry into a new timestamped folder. */
	public static Path publishArchive(BackupEngine engine, Home home, Path savesRoot,
									  SnapshotIndex.Entry entry, SnapshotIndex idx,
									  boolean shiftCoordsTo0) throws IOException {
		Instant when = Instant.ofEpochMilli(entry.epochMs);
		String levelName = archiveLevelName(home, when);
		String folder = archiveFolderName(home, when);
		return exportToFolder(engine, home, savesRoot, entry, idx, folder, levelName, shiftCoordsTo0, false);
	}

	private static Path exportToFolder(BackupEngine engine, Home home, Path savesRoot,
									   SnapshotIndex.Entry entry, SnapshotIndex idx,
									   String folder, String levelName, boolean shiftCoordsTo0,
									   boolean pruneLegacy) throws IOException {
		Files.createDirectories(savesRoot);
		Path dest = savesRoot.resolve(folder);
		Path tmp = savesRoot.resolve(folder + ".tmp-" + System.currentTimeMillis());
		String tmpName = tmp.getFileName().toString();

		try {
			AnvilExporter exporter = new AnvilExporter(engine);
			AnvilExporter.ExportResult result = exporter.exportEntry(
				home.id, entry, tmp, levelName, home, shiftCoordsTo0, idx);

			if (Files.exists(dest)) {
				deleteRecursive(dest);
			}
			Files.move(tmp, dest);
			try {
				SurfaceIconRenderer.writeIcon(dest, result.destChunks());
			} catch (Exception e) {
				LOG.debug("icon.png skipped: {}", e.toString());
			}
			if (pruneLegacy) {
				pruneAutoSiblings(savesRoot, home, folder, tmpName);
			}
			return dest;
		} catch (IOException e) {
			try {
				if (Files.exists(tmp)) deleteRecursive(tmp);
			} catch (IOException ignored) {}
			throw e;
		}
	}

	/** Delete legacy bb-* and prior auto folders for this home. */
	static void pruneAutoSiblings(Path savesRoot, Home home, String keepName, String alsoKeep)
		throws IOException {
		if (!Files.isDirectory(savesRoot)) return;
		String base = nameBase(home);
		String legacyPrefix = "bb-" + base;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesRoot)) {
			for (Path p : stream) {
				String n = p.getFileName().toString();
				if (n.equals(keepName)) continue;
				if (alsoKeep != null && n.equals(alsoKeep)) continue;
				boolean legacy = n.equals(legacyPrefix) || n.startsWith(legacyPrefix + "-")
					|| n.startsWith(legacyPrefix + ".");
				boolean sameStable = n.equals(base) || n.startsWith(base + ".tmp");
				if (!legacy && !sameStable) continue;
				deleteRecursive(p);
			}
		}
	}

	/** @deprecated */
	@Deprecated
	static void pruneSiblings(Path savesRoot, Home home, String keepName) throws IOException {
		pruneAutoSiblings(savesRoot, home, keepName, null);
	}

	/** @deprecated */
	@Deprecated
	static void pruneSiblings(Path savesRoot, Home home, String keepName, String alsoKeep)
		throws IOException {
		pruneAutoSiblings(savesRoot, home, keepName, alsoKeep);
	}

	public static void deleteRecursive(Path root) throws IOException {
		if (!Files.exists(root)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** User-facing error without absolute paths. */
	static String shortError(Throwable e) {
		if (e == null) return "unknown";
		String cls = e.getClass().getSimpleName();
		String msg = e.getMessage();
		if (msg == null || msg.isBlank()) return cls;
		msg = msg.replace('\n', ' ').trim();
		int slash = Math.max(msg.lastIndexOf('/'), msg.lastIndexOf('\\'));
		if (slash >= 0 && slash + 1 < msg.length()) {
			String tail = msg.substring(slash + 1);
			if (!tail.isBlank() && (msg.contains("/Users/") || msg.contains("/home/")
				|| msg.contains("Documents") || msg.contains(":\\"))) {
				return cls + ": saves/" + tail;
			}
		}
		if (msg.length() > 120) msg = msg.substring(0, 117) + "...";
		return cls + ": " + msg;
	}

	private static void chatInfo(String msg) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null) mc.execute(() -> HomeChat.info(msg));
	}

	private static void chatWarn(String msg) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null) mc.execute(() -> HomeChat.warn(msg));
	}
}
