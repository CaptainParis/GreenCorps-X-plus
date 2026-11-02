package dev.greencorps.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Content-addressed gzip chunk bytes at {@code objects/aa/&lt;sha256&gt;}. */
public final class ObjectStore {
	private final Path root;

	public ObjectStore(Path root) throws IOException {
		this.root = root;
		Files.createDirectories(root);
	}

	public Path root() {
		return root;
	}

	public synchronized PutResult put(byte[] raw) throws IOException {
		if (raw == null) throw new IOException("null object");
		String hash = sha256(raw);
		Path path = path(hash);
		long added = 0;
		if (!Files.isRegularFile(path)) {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(hash + ".tmp");
			Files.write(tmp, raw);
			try {
				Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			added = raw.length;
		}
		return new PutResult(hash, added);
	}

	public synchronized byte[] getChunkObject(String hash) throws IOException {
		if (hash == null || hash.isBlank()) throw new IOException("blank hash");
		Path path = path(hash);
		if (!Files.isRegularFile(path)) throw new IOException("missing object " + hash);
		byte[] raw = Files.readAllBytes(path);
		String got = sha256(raw);
		if (!got.equals(hash)) throw new IOException("hash mismatch " + hash);
		return raw;
	}

	public synchronized List<Path> objectFiles() throws IOException {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(root)) return out;
		try (var dirs = Files.list(root)) {
			for (Path dir : dirs.toList()) {
				if (!Files.isDirectory(dir)) continue;
				try (var files = Files.list(dir)) {
					for (Path f : files.toList()) {
						if (Files.isRegularFile(f) && !f.getFileName().toString().endsWith(".tmp")) out.add(f);
					}
				}
			}
		}
		return out;
	}

	public synchronized long sizeBytes() throws IOException {
		long n = 0;
		for (Path p : objectFiles()) n += Files.size(p);
		return n;
	}

	public synchronized boolean delete(String hash) throws IOException {
		if (hash == null || hash.length() < 2) return false;
		Path path = path(hash);
		boolean gone = Files.deleteIfExists(path);
		Path parent = path.getParent();
		if (parent != null && Files.isDirectory(parent)) {
			try (var s = Files.list(parent)) {
				if (s.findAny().isEmpty()) Files.deleteIfExists(parent);
			}
		}
		return gone;
	}

	private Path path(String hash) {
		return root.resolve(hash.substring(0, 2)).resolve(hash);
	}

	public static String sha256(byte[] raw) {
		try {
			byte[] dig = MessageDigest.getInstance("SHA-256").digest(raw);
			return HexFormat.of().formatHex(dig);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public record PutResult(String hash, long bytesAdded) {}
}
