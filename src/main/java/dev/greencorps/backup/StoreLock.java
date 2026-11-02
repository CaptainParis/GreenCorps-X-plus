package dev.greencorps.backup;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Exclusive lock so two Minecraft instances cannot commit into the same store. */
public final class StoreLock implements AutoCloseable {
	private final FileChannel channel;
	private final FileLock lock;
	private final boolean exclusive;

	private StoreLock(FileChannel channel, FileLock lock, boolean exclusive) {
		this.channel = channel;
		this.lock = lock;
		this.exclusive = exclusive;
	}

	public static StoreLock tryAcquire(Path storeRoot) throws IOException {
		Files.createDirectories(storeRoot);
		Path lockPath = storeRoot.resolve("lock");
		FileChannel ch = FileChannel.open(lockPath,
			StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
		FileLock exclusive = ch.tryLock();
		if (exclusive != null) {
			return new StoreLock(ch, exclusive, true);
		}
		// Shared/read-only: leave unlocked but keep channel open so we can still report.
		return new StoreLock(ch, null, false);
	}

	public boolean isExclusive() {
		return exclusive;
	}

	@Override
	public void close() throws IOException {
		if (lock != null) {
			try {
				lock.release();
			} catch (IOException ignored) {}
		}
		channel.close();
	}
}
