package dev.greencorps.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Per-home timeline. {@code entries()} is oldest first. {@code #n} in TimeSelector is 1-based. */
public final class SnapshotIndex {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;
	private final List<Entry> entries = new ArrayList<>();

	public SnapshotIndex(Path refsDir, String homeId) throws IOException {
		if (homeId == null || homeId.isBlank()) throw new IOException("blank home id");
		this.file = refsDir.resolve(safe(homeId) + ".json");
		Files.createDirectories(refsDir);
		if (Files.isRegularFile(file)) load();
	}

	/** Live entries, oldest first. Callers mutate {@code sealed} / {@code pinned} then {@link #update}. */
	public synchronized List<Entry> entries() {
		return entries;
	}

	public synchronized Optional<Entry> latest() {
		if (entries.isEmpty()) return Optional.empty();
		return Optional.of(entries.get(entries.size() - 1));
	}

	/** 1-based index matching {@code #n}. */
	public synchronized Optional<Entry> byIndex(int n) {
		if (n < 1 || n > entries.size()) return Optional.empty();
		return Optional.of(entries.get(n - 1));
	}

	/** Newest entry whose {@code epochMs} is at or before {@code epochMs}. */
	public synchronized Optional<Entry> atOrBefore(long epochMs) {
		Entry best = null;
		for (Entry e : entries) {
			if (e.epochMs <= epochMs && (best == null || e.epochMs >= best.epochMs)) best = e;
		}
		return Optional.ofNullable(best);
	}

	public synchronized void add(Entry entry) throws IOException {
		if (entry == null || entry.filename == null || entry.filename.isBlank()) {
			throw new IOException("snapshot entry needs a filename");
		}
		entries.removeIf(e -> entry.filename.equals(e.filename));
		entries.add(entry);
		sort();
		save();
	}

	public synchronized void update(Entry entry) throws IOException {
		if (entry == null || entry.filename == null) throw new IOException("null entry");
		boolean found = false;
		for (int i = 0; i < entries.size(); i++) {
			if (entry.filename.equals(entries.get(i).filename)) {
				entries.set(i, entry);
				found = true;
				break;
			}
		}
		if (!found) throw new IOException("unknown snapshot " + entry.filename);
		sort();
		save();
	}

	public synchronized void remove(Entry entry) throws IOException {
		if (entry == null || entry.filename == null) return;
		entries.removeIf(e -> entry.filename.equals(e.filename));
		save();
	}

	private void load() throws IOException {
		FileModel model = GSON.fromJson(Files.readString(file), FileModel.class);
		entries.clear();
		if (model != null && model.entries != null) {
			for (Entry e : model.entries) {
				if (e != null && e.filename != null) entries.add(e);
			}
		}
		sort();
	}

	private void sort() {
		entries.sort(Comparator.comparingLong(e -> e.epochMs));
	}

	private void save() throws IOException {
		FileModel model = new FileModel();
		model.entries = entries;
		AtomicFiles.writeString(file, GSON.toJson(model));
	}

	static String safe(String homeId) {
		String s = homeId.replaceAll("[^A-Za-z0-9._-]", "_");
		if (s.isBlank() || s.equals(".") || s.equals("..")) throw new IllegalArgumentException("bad home id");
		return s;
	}

	private static final class FileModel {
		List<Entry> entries = new ArrayList<>();
	}

	/** Public fields: the timeline screen assigns {@code pinned} and {@code sealed} directly. */
	public static class Entry {
		public long epochMs;
		public String filename;
		public long bytesAdded;
		public double coveragePct;
		public int capturedChunks;
		public boolean sealed;
		public boolean verified;
		public boolean pinned;
		public boolean degraded;
	}
}
