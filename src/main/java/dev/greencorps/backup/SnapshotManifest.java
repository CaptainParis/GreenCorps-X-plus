package dev.greencorps.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** One commit. {@code chunks} maps {@code "cx,cz"} to an object-store hash. */
public class SnapshotManifest {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String homeId;
	public long epochMs;
	public Map<String, String> chunks = new LinkedHashMap<>();
	public int changedChunks;
	public int capturedChunks;
	public int expectedChunks;
	public long bytesAdded;
	public boolean sealed;
	public boolean pinned;

	public static SnapshotManifest read(Path path) throws IOException {
		if (path == null || !Files.isRegularFile(path)) throw new IOException("missing manifest " + path);
		SnapshotManifest m = GSON.fromJson(Files.readString(path), SnapshotManifest.class);
		if (m == null) throw new IOException("empty manifest " + path);
		if (m.chunks == null) m.chunks = new LinkedHashMap<>();
		return m;
	}

	public void writeAtomic(Path path) throws IOException {
		if (chunks == null) chunks = new LinkedHashMap<>();
		AtomicFiles.writeString(path, GSON.toJson(this));
	}

	public String getChunk(int cx, int cz) {
		if (chunks == null) return null;
		return chunks.get(cx + "," + cz);
	}

	public static String key(int cx, int cz) {
		return cx + "," + cz;
	}
}
