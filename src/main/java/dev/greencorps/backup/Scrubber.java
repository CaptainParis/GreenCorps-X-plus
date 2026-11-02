package dev.greencorps.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Walks object files a few per tick and marks hashes that no longer match their name. */
public final class Scrubber {
	private final BackupEngine engine;
	private List<Path> files = List.of();
	private int cursor;

	public Scrubber(BackupEngine engine) throws IOException {
		if (engine == null) throw new IOException("null engine");
		this.engine = engine;
		this.files = engine.store().objectFiles();
	}

	/** @return how many bad objects this tick found */
	public int tick() {
		try {
			if (files.isEmpty() || cursor >= files.size()) {
				files = engine.store().objectFiles();
				cursor = 0;
				if (files.isEmpty()) return 0;
			}
		} catch (IOException e) {
			return 0;
		}
		Path file = files.get(cursor++);
		String name = file.getFileName().toString();
		try {
			byte[] raw = Files.readAllBytes(file);
			if (ObjectStore.sha256(raw).equals(name)) {
				engine.clearDegraded(name);
				return 0;
			}
		} catch (IOException ignored) {}
		engine.markDegraded(name);
		return 1;
	}
}
