package dev.greencorps.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drops unpinned snapshots older than the retain window, then blobs no kept snapshot still names.
 * A dry-run only reports {@link Result#wouldFreeBytes()}.
 */
public final class StoreGc {
	private final BackupEngine engine;
	private final long retainMs;

	public StoreGc(BackupEngine engine, long retainMs) {
		this.engine = engine;
		this.retainMs = Math.max(0, retainMs);
	}

	public Result run(boolean dryRun) {
		if (engine == null) return new Result("no engine", 0);
		synchronized (engine) {
			try {
				return runLocked(dryRun);
			} catch (IOException e) {
				return new Result("gc error " + e.getClass().getSimpleName(), 0);
			}
		}
	}

	private Result runLocked(boolean dryRun) throws IOException {
		long now = System.currentTimeMillis();
		long cutoff = now - retainMs;
		Map<String, List<Snap>> byHome = loadSnaps();
		Set<String> keepHashes = new HashSet<>();
		int dropSnaps = 0;
		List<Snap> dropping = new ArrayList<>();

		for (var e : byHome.entrySet()) {
			for (Snap snap : e.getValue()) {
				if (keep(snap, cutoff)) {
					if (snap.manifest.chunks != null) keepHashes.addAll(snap.manifest.chunks.values());
				} else {
					dropSnaps++;
					dropping.add(snap);
				}
			}
		}

		long free = 0;
		List<String> orphanHashes = new ArrayList<>();
		for (Path file : engine.store().objectFiles()) {
			String hash = file.getFileName().toString();
			if (keepHashes.contains(hash)) continue;
			free += Files.size(file);
			orphanHashes.add(hash);
		}

		if (!dryRun) {
			for (Snap snap : dropping) {
				Files.deleteIfExists(snap.path);
				try {
					SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), snap.homeId);
					SnapshotIndex.Entry entry = new SnapshotIndex.Entry();
					entry.filename = snap.path.getFileName().toString();
					idx.remove(entry);
				} catch (IOException ignored) {}
			}
			for (String hash : orphanHashes) engine.store().delete(hash);
		}

		String message = dropSnaps == 0 && orphanHashes.isEmpty()
			? "nothing to free"
			: "drop " + dropSnaps + " snapshots, " + orphanHashes.size() + " blobs";
		return new Result(message, free);
	}

	private boolean keep(Snap snap, long cutoff) {
		if (snap.pinned) return true;
		return snap.manifest.epochMs >= cutoff;
	}

	private Map<String, List<Snap>> loadSnaps() throws IOException {
		Map<String, List<Snap>> out = new HashMap<>();
		Path snaps = engine.baseDir().resolve("snaps");
		if (!Files.isDirectory(snaps)) return out;
		try (var homes = Files.list(snaps)) {
			for (Path homeDir : homes.toList()) {
				if (!Files.isDirectory(homeDir)) continue;
				String homeId = homeDir.getFileName().toString();
				Set<String> pinnedNames = pinnedNames(homeId);
				try (var files = Files.list(homeDir)) {
					for (Path snap : files.toList()) {
						String name = snap.getFileName().toString();
						if (!name.endsWith(".gcsnap")) continue;
						try {
							SnapshotManifest m = SnapshotManifest.read(snap);
							boolean pinned = m.pinned || pinnedNames.contains(name);
							out.computeIfAbsent(homeId, k -> new ArrayList<>()).add(new Snap(homeId, snap, m, pinned));
						} catch (IOException ignored) {}
					}
				}
			}
		}
		return out;
	}

	private Set<String> pinnedNames(String homeId) {
		Set<String> names = new HashSet<>();
		try {
			SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), homeId);
			for (SnapshotIndex.Entry e : idx.entries()) {
				if (e.pinned && e.filename != null) names.add(e.filename);
			}
		} catch (IOException ignored) {}
		return names;
	}

	private record Snap(String homeId, Path path, SnapshotManifest manifest, boolean pinned) {}

	public record Result(String message, long wouldFreeBytes) {}
}
