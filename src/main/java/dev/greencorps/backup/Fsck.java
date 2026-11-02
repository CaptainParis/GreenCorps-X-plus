package dev.greencorps.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Checks every manifest hash against the bytes in the object store. */
public final class Fsck {
	private Fsck() {}

	public static Result run(BackupEngine engine) {
		if (engine == null) return new Result(false, List.of("no engine"));
		synchronized (engine) {
			List<String> errors = new ArrayList<>();
			Path snaps = engine.baseDir().resolve("snaps");
			if (!Files.isDirectory(snaps)) return new Result(true, List.of());
			try (var homes = Files.list(snaps)) {
				for (Path homeDir : homes.toList()) {
					if (!Files.isDirectory(homeDir)) continue;
					try (var files = Files.list(homeDir)) {
						for (Path snap : files.toList()) {
							if (!snap.getFileName().toString().endsWith(".gcsnap")) continue;
							check(engine, snap, errors);
						}
					}
				}
			} catch (IOException e) {
				errors.add("scan: " + e.getClass().getSimpleName());
			}
			return new Result(errors.isEmpty(), List.copyOf(errors));
		}
	}

	private static void check(BackupEngine engine, Path snap, List<String> errors) {
		SnapshotManifest m;
		try {
			m = SnapshotManifest.read(snap);
		} catch (IOException e) {
			errors.add(snap.getFileName() + ": unreadable");
			return;
		}
		if (m.chunks == null) return;
		for (var ce : m.chunks.entrySet()) {
			String hash = ce.getValue();
			try {
				byte[] raw = engine.store().getChunkObject(hash);
				if (!ObjectStore.sha256(raw).equals(hash)) {
					errors.add(ce.getKey() + ": hash mismatch");
				}
			} catch (IOException e) {
				errors.add(ce.getKey() + ": " + e.getMessage());
			}
		}
	}

	public record Result(boolean ok, List<String> errors) {}
}
