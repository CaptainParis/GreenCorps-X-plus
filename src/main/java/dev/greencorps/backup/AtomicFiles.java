package dev.greencorps.backup;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Write a file by replacing a sibling temp, so a crash cannot leave a half-written JSON. */
final class AtomicFiles {
	private AtomicFiles() {}

	static void writeString(Path path, String text) throws IOException {
		Path parent = path.getParent();
		if (parent != null) Files.createDirectories(parent);
		Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
		Files.writeString(tmp, text);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (NoSuchFileException e) {
			// macOS can throw this after REPLACE_EXISTING already landed (com.apple.macl).
			if (Files.exists(tmp)) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			} else if (!Files.exists(path)) {
				throw e;
			}
		}
	}
}
