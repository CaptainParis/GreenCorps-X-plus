package dev.greencorps.export;

import dev.greencorps.backup.SnapshotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlayablePublishKeyTest {
	private static SnapshotIndex.Entry entry(long epochMs, String filename) {
		SnapshotIndex.Entry e = new SnapshotIndex.Entry();
		e.epochMs = epochMs;
		e.filename = filename;
		return e;
	}

	@Test
	void missingFolderIsNotPublished(@TempDir Path dir) {
		String key = PlayableSavePublisher.publishKey(entry(1000L, "a.json"), false);
		assertFalse(PlayableSavePublisher.isPublished(dir.resolve("Base"), key));
	}

	@Test
	void sameSnapshotMatchesNewOneDoesNot(@TempDir Path dir) throws Exception {
		Path folder = Files.createDirectories(dir.resolve("Base"));
		String key = PlayableSavePublisher.publishKey(entry(1000L, "a.json"), false);
		assertFalse(PlayableSavePublisher.isPublished(folder, key), "no marker yet");
		PlayableSavePublisher.writePublished(folder, key);
		assertTrue(PlayableSavePublisher.isPublished(folder, key));
		assertFalse(PlayableSavePublisher.isPublished(folder,
			PlayableSavePublisher.publishKey(entry(2000L, "b.json"), false)), "new commit");
		assertFalse(PlayableSavePublisher.isPublished(folder,
			PlayableSavePublisher.publishKey(entry(1000L, "a.json"), true)), "coord shift toggled");
	}
}
