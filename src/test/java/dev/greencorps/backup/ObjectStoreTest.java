package dev.greencorps.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ObjectStoreTest {
	@TempDir Path tmp;

	@Test
	void roundTripAndDedup() throws Exception {
		ObjectStore store = new ObjectStore(tmp.resolve("store"));
		byte[] a = "hello-section".getBytes(StandardCharsets.UTF_8);
		String h1 = store.putObject(a);
		String h2 = store.putObject(a);
		assertEquals(h1, h2);
		assertArrayEquals(a, store.getObject(h1));
	}

	@Test
	void tamperDetected() throws Exception {
		ObjectStore store = new ObjectStore(tmp.resolve("store"));
		byte[] a = "payload".getBytes(StandardCharsets.UTF_8);
		String h = store.putObject(a);
		Path path = ObjectStore.pathFor(store.objectsDir(), h);
		byte[] compressed = Files.readAllBytes(path);
		compressed[compressed.length / 2] ^= 0x5A;
		Files.write(path, compressed);
		assertThrows(Exception.class, () -> store.getObject(h));
	}

	@Test
	void crashInjectionLeavesPreviousIntact() throws Exception {
		ObjectStore store = new ObjectStore(tmp.resolve("store"));
		String first = store.putObject("first".getBytes(StandardCharsets.UTF_8));
		store.crashAfterWrites = 1;
		store.writesCompleted = 0;
		// Reset counter: putObject increments; set so next write crashes
		// Access package field via reflection-like: we set crashAfterWrites=0 meaning crash immediately after 0? 
		// Our logic: if writesCompleted >= crashAfterWrites after a write. Set crashAfterWrites=0 and writesCompleted=0 → first write crashes.
		store.crashAfterWrites = 0;
		// Actually after put, writesCompleted becomes 1, and 1 >= 0 → crash. But first put already done.
		// Reset:
		store.writesCompleted = 0;
		store.crashAfterWrites = 1;
		assertThrows(ObjectStore.SimulatedCrashException.class,
			() -> store.putObject("second".getBytes(StandardCharsets.UTF_8)));
		assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), store.getObject(first));
	}
}
