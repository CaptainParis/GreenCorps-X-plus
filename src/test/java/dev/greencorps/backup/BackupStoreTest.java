package dev.greencorps.backup;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BackupStoreTest {
	@Test
	void homeGeometry() {
		Home home = new Home("base", "minecraft:overworld", 0, 0, 1);
		assertEquals(9, home.expectedChunks());
		assertTrue(home.containsBlock(16, 16));
		assertFalse(home.containsBlock(32, 0));
		assertEquals(8.0, home.centerBlockX(), 0);
		assertEquals(-16.0, home.minBlockX(), 0);
		assertEquals(32.0, home.maxBlockX(), 0);
		assertTrue(home.containsChunk(1, -1));
		assertFalse(home.containsChunk(2, 0));
		assertFalse(home.isSnapshot());

		Home neg = new Home("neg", "minecraft:overworld", -1, 0, 0);
		assertEquals(1, neg.expectedChunks());
		assertEquals(-8.0, neg.centerBlockX(), 0);
		assertTrue(neg.containsBlock(-8, 0));
		assertTrue(neg.containsBlock(-16, 0));
		assertFalse(neg.containsBlock(-17, 0));
	}

	@Test
	void homesJsonRoundTrip(@TempDir Path dir) throws IOException {
		HomeStore store = new HomeStore(dir);
		Home home = new Home("Alpha", "minecraft:overworld", 3, 4, 2);
		home.armed = true;
		home.autoCommit = true;
		home.spawnY = 64;
		home.keepPlayableSave = true;
		store.add(home);
		home.kind = Home.Kind.SNAPSHOT;
		store.update(home);

		HomeStore again = new HomeStore(dir);
		Home loaded = again.findById(home.id).orElseThrow();
		assertEquals("Alpha", loaded.name);
		assertEquals(3, loaded.centerChunkX);
		assertEquals(4, loaded.centerChunkZ);
		assertEquals(2, loaded.radiusChunks);
		assertEquals(64, loaded.spawnY);
		assertTrue(loaded.armed);
		assertTrue(loaded.autoCommit);
		assertTrue(loaded.keepPlayableSave);
		assertTrue(loaded.isSnapshot());
		assertEquals(1, again.homes().size());

		again.remove(home.id);
		assertTrue(new HomeStore(dir).homes().isEmpty());
		String json = Files.readString(dir.resolve("homes.json"));
		assertTrue(json.contains("\"schema_version\": 1"));
	}

	@Test
	void snapshotIndexLookup(@TempDir Path dir) throws IOException {
		SnapshotIndex idx = new SnapshotIndex(dir, "home-1");
		idx.add(entry(100, "100.gcsnap"));
		idx.add(entry(300, "300.gcsnap"));
		idx.add(entry(200, "200.gcsnap"));

		assertEquals(300, idx.latest().orElseThrow().epochMs);
		assertEquals(100, idx.byIndex(1).orElseThrow().epochMs);
		assertEquals(200, idx.byIndex(2).orElseThrow().epochMs);
		assertEquals(200, idx.atOrBefore(250).orElseThrow().epochMs);
		assertEquals(300, idx.atOrBefore(300).orElseThrow().epochMs);
		assertTrue(idx.atOrBefore(50).isEmpty());
		assertTrue(idx.byIndex(0).isEmpty());

		SnapshotIndex.Entry pinned = idx.byIndex(1).orElseThrow();
		pinned.pinned = true;
		idx.update(pinned);
		SnapshotIndex reloaded = new SnapshotIndex(dir, "home-1");
		assertTrue(reloaded.byIndex(1).orElseThrow().pinned);
		assertEquals(3, reloaded.entries().size());
	}

	@Test
	void manifestAtomicRoundTrip(@TempDir Path dir) throws IOException {
		SnapshotManifest manifest = new SnapshotManifest();
		manifest.homeId = "home-1";
		manifest.epochMs = 50;
		manifest.chunks.put("1,-2", "abc");
		manifest.changedChunks = 1;
		manifest.capturedChunks = 1;
		manifest.expectedChunks = 9;
		manifest.bytesAdded = 12;
		Path path = dir.resolve("50.gcsnap");
		manifest.writeAtomic(path);

		SnapshotManifest read = SnapshotManifest.read(path);
		assertEquals("abc", read.getChunk(1, -2));
		assertNull(read.getChunk(0, 0));
		assertEquals(1, read.changedChunks);
		assertEquals(12, read.bytesAdded);
		assertFalse(Files.exists(path.resolveSibling("50.gcsnap.tmp")));
	}

	@Test
	void objectStoreHashRoundTrip(@TempDir Path dir) throws IOException {
		ObjectStore store = new ObjectStore(dir);
		byte[] raw = new byte[] {1, 2, 3, 4, 5};
		ObjectStore.PutResult put = store.put(raw);
		assertEquals(raw.length, put.bytesAdded());
		assertArrayEquals(raw, store.getChunkObject(put.hash()));
		assertEquals(0, store.put(raw).bytesAdded());
		assertTrue(Files.isRegularFile(dir.resolve(put.hash().substring(0, 2)).resolve(put.hash())));
	}

	@Test
	void legacyJsonAndPlayableStatus() {
		assertTrue(ChunkCapture.isLegacyJsonBlob(" {\"a\":1}".getBytes()));
		assertFalse(ChunkCapture.isLegacyJsonBlob(new byte[] {0x1f, (byte) 0x8b, 0}));

		NbtCompound nbt = new NbtCompound();
		nbt.putString("Status", "minecraft:empty");
		ChunkCapture.ensurePlayableStatus(nbt);
		assertEquals("minecraft:full", nbt.getString("Status"));
		assertEquals(ChunkObject.MC_DATA_VERSION, nbt.getInt("DataVersion"));
		assertEquals(4189, ChunkObject.MC_DATA_VERSION);
	}

	@Test
	void boostAlphaKeepsRgb() {
		int packed = (136 << 24) | (255 << 16) | (69 << 8);
		int out = UnnaturalScore.boostAlpha(packed, 2);
		assertEquals(136 + 36, (out >>> 24) & 0xFF);
		assertEquals(255, (out >> 16) & 0xFF);
		assertEquals(69, (out >> 8) & 0xFF);
		assertEquals(packed, UnnaturalScore.boostAlpha(packed, 0));
	}

	@Test
	void fsckAcceptsMatchingObjects(@TempDir Path dir) throws IOException {
		BackupEngine engine = new BackupEngine(dir);
		byte[] raw = new byte[] {7, 7, 7, 7};
		String hash = engine.store().put(raw).hash();
		writeSnap(engine, "h", 10, hash, false);

		Fsck.Result ok = Fsck.run(engine);
		assertTrue(ok.ok(), ok.errors().toString());

		Path object = dir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
		Files.write(object, new byte[] {1});
		Fsck.Result bad = Fsck.run(engine);
		assertFalse(bad.ok());
		assertFalse(bad.errors().isEmpty());
	}

	@Test
	void gcDryRunDeletesNothingAndKeepsPinned(@TempDir Path dir) throws IOException {
		BackupEngine engine = new BackupEngine(dir);
		String keep = engine.store().put(new byte[] {1, 2, 3, 4, 5}).hash();
		String orphan = engine.store().put(new byte[] {9, 9, 9, 9}).hash();
		writeSnap(engine, "home-a", 1_000L, keep, true);

		StoreGc.Result dry = new StoreGc(engine, Retention.D).run(true);
		assertTrue(dry.wouldFreeBytes() >= 4, "free=" + dry.wouldFreeBytes() + " " + dry.message());
		assertArrayEquals(new byte[] {9, 9, 9, 9}, engine.store().getChunkObject(orphan));
		assertTrue(Files.isRegularFile(engine.manifestPath("home-a", "1000.gcsnap")));

		StoreGc.Result real = new StoreGc(engine, Retention.D).run(false);
		assertTrue(real.wouldFreeBytes() >= 4);
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, engine.store().getChunkObject(keep));
		assertThrows(IOException.class, () -> engine.store().getChunkObject(orphan));
		assertTrue(Files.isRegularFile(engine.manifestPath("home-a", "1000.gcsnap")));
	}

	private static SnapshotIndex.Entry entry(long epoch, String filename) {
		SnapshotIndex.Entry e = new SnapshotIndex.Entry();
		e.epochMs = epoch;
		e.filename = filename;
		return e;
	}

	private static void writeSnap(BackupEngine engine, String homeId, long epoch, String hash, boolean pinned)
		throws IOException {
		SnapshotManifest manifest = new SnapshotManifest();
		manifest.homeId = homeId;
		manifest.epochMs = epoch;
		manifest.pinned = pinned;
		manifest.chunks.put("0,0", hash);
		manifest.capturedChunks = 1;
		manifest.expectedChunks = 1;
		String filename = epoch + ".gcsnap";
		manifest.writeAtomic(engine.manifestPath(homeId, filename));
		SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), homeId);
		SnapshotIndex.Entry entry = entry(epoch, filename);
		entry.pinned = pinned;
		entry.capturedChunks = 1;
		idx.add(entry);
	}
}
