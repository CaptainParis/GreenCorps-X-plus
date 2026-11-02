package dev.greencorps.backup;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class StoreStats {
	private StoreStats() {}

	public static String formatBytes(long bytes) {
		long n = Math.max(0, bytes);
		if (n < 1024) return n + " B";
		double v = n;
		String[] units = {"KB", "MB", "GB", "TB"};
		int i = -1;
		while (v >= 1024 && i < units.length - 1) {
			v /= 1024.0;
			i++;
		}
		return String.format(Locale.ROOT, "%.1f %s", v, units[i]);
	}

	public static Summary forHome(BackupEngine engine, String homeId) throws IOException {
		if (engine == null) return new Summary(0, 0, 0);
		SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), homeId);
		var entries = idx.entries();
		if (entries.isEmpty()) return new Summary(0, 0, 0);

		long added = 0;
		Set<String> hashes = new HashSet<>();
		for (SnapshotIndex.Entry e : entries) {
			added += Math.max(0, e.bytesAdded);
			try {
				SnapshotManifest m = SnapshotManifest.read(engine.manifestPath(homeId, e.filename));
				if (m.chunks != null) hashes.addAll(m.chunks.values());
			} catch (IOException ignored) {}
		}
		long store = 0;
		for (String hash : hashes) {
			try {
				store += engine.store().getChunkObject(hash).length;
			} catch (IOException ignored) {}
		}
		double mean = added / (double) entries.size();
		long project = project30d(entries, added, mean);
		return new Summary(store, mean, project);
	}

	private static long project30d(java.util.List<SnapshotIndex.Entry> entries, long added, double mean) {
		if (entries.size() < 2) return (long) (mean * 30);
		long span = entries.get(entries.size() - 1).epochMs - entries.get(0).epochMs;
		double days = Math.max(1.0 / 24.0, span / (double) Retention.D);
		return (long) ((added / days) * 30.0);
	}

	public record Summary(long storeBytes, double meanBytesPerCommit, long project30d) {}
}
