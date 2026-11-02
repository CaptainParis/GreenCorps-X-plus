package dev.greencorps.stasis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StasisStoreTest {
	@Test
	void homeGeometry() {
		StasisHome home = new StasisHome("base", "minecraft:overworld", 8, 64, 8, 1, 2);
		assertTrue(home.containsBlock(16, 16));
		assertFalse(home.containsBlock(32, 0));
		assertTrue(home.containsChunk(1, -1));
		assertFalse(home.containsChunk(2, 0));
		assertEquals(2, home.pearlsRequired);
		assertEquals(0, home.centerChunkX());
		assertEquals(0, home.centerChunkZ());
	}

	@Test
	void chambersJsonRoundTrip(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		StasisHome home = new StasisHome("Alpha", "minecraft:overworld", 48, 70, 64, 2, 3);
		store.addHome(home);

		StasisPearl pearl = new StasisPearl("minecraft:overworld", 50, 70, 66);
		pearl.ownerUuid = UUID.randomUUID().toString();
		pearl.ownerIgn = "Tester";
		pearl.homeId = home.id;
		pearl.chamberKind = ChamberKind.BUBBLE_COLUMN;
		store.addPearl(pearl);

		StasisStore again = new StasisStore(dir);
		StasisHome loaded = again.findHomeById(home.id).orElseThrow();
		assertEquals("Alpha", loaded.name);
		assertEquals(3, loaded.pearlsRequired);
		assertEquals(48, loaded.centerX);
		assertEquals(1, again.pearls().size());
		StasisPearl p = again.pearls().get(0);
		assertEquals("Tester", p.ownerIgn);
		assertEquals(ChamberKind.BUBBLE_COLUMN, p.chamberKind);
		assertEquals(1, again.loadedOwnPearlCount(loaded, p.ownerUuid));
		assertEquals(2, again.missingOwnPearlCount(loaded, p.ownerUuid));
		assertTrue(p.firstSeenMs > 0);

		String json = Files.readString(dir.resolve("chambers.json"));
		assertTrue(json.contains("\"schema_version\": 3"));
	}

	@Test
	void friendPearlDoesNotFillOwnQuota(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		StasisHome home = new StasisHome("h", "minecraft:overworld", 0, 64, 0, 2, 1);
		store.addHome(home);
		String me = UUID.randomUUID().toString();
		String friend = UUID.randomUUID().toString();

		StasisPearl theirs = new StasisPearl("minecraft:overworld", 1, 64, 1);
		theirs.ownerUuid = friend;
		theirs.ownerIgn = "Friend";
		theirs.loaded = true;
		store.addPearl(theirs);

		assertEquals(0, store.loadedOwnPearlCount(home, me));
		assertEquals(1, store.missingOwnPearlCount(home, me));
		assertEquals(1, store.loadedPearlCount(home));
	}

	@Test
	void findCoveringAndNear(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		StasisHome home = new StasisHome("h", "minecraft:overworld", 0, 64, 0, 0, 1);
		store.addHome(home);
		assertTrue(store.findCoveringHome("minecraft:overworld", 8, 8).isPresent());
		assertTrue(store.findCoveringHome("minecraft:the_nether", 8, 8).isEmpty());

		StasisPearl pearl = new StasisPearl("minecraft:overworld", 10, 64, 10);
		store.addPearl(pearl);
		assertTrue(store.findPearlNear("minecraft:overworld", 11, 64, 11, 3).isPresent());
		assertTrue(store.findPearlNear("minecraft:overworld", 100, 64, 100, 3).isEmpty());
	}

	@Test
	void removeHomeClearsPearlLink(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		StasisHome home = new StasisHome("h", "minecraft:overworld", 0, 64, 0, 1, 1);
		store.addHome(home);
		StasisPearl pearl = new StasisPearl("minecraft:overworld", 1, 64, 1);
		pearl.homeId = home.id;
		store.addPearl(pearl);
		store.removeHome(home.id);
		assertNull(store.pearls().get(0).homeId);
	}

	@Test
	void seatColumnOrphansCollapse(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		StasisHome home = new StasisHome("beta", "minecraft:overworld", 0, 64, 0, 2, 1);
		store.addHome(home);

		StasisChamberSlot good = new StasisChamberSlot(
			home.id, "minecraft:overworld", 10, 40, 20, 10, 34, 20, StasisChamberSlot.KIND_WATER);
		good.geometryOk = true;
		store.upsertSlot(good);

		StasisChamberSlot orphan = new StasisChamberSlot(
			home.id, "minecraft:overworld", 10, 41, 20, 30, 30, 25, StasisChamberSlot.KIND_WATER);
		orphan.geometryOk = true;
		store.upsertSlot(orphan);

		assertEquals(1, store.slotsForHome(home.id).size());
		StasisChamberSlot kept = store.slotsForHome(home.id).get(0);
		assertTrue(kept.waterSeatAligned());
		assertEquals(10, kept.rootX);
		assertEquals(20, kept.rootZ);
	}

	@Test
	void waterSeatAlignedRequiresRootXz() {
		StasisChamberSlot aligned = new StasisChamberSlot(
			"h", "minecraft:overworld", 5, 40, 7, 5, 34, 7, StasisChamberSlot.KIND_WATER);
		assertTrue(aligned.waterSeatAligned());
		StasisChamberSlot off = new StasisChamberSlot(
			"h", "minecraft:overworld", 5, 40, 7, 9, 30, 11, StasisChamberSlot.KIND_WATER);
		assertFalse(off.waterSeatAligned());
	}

	@Test
	void deferredWritesCoalesceHeartbeats(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		store.setDeferredWrites(true);
		StasisHome home = new StasisHome("h", "minecraft:overworld", 0, 64, 0, 2, 1);
		store.addHome(home); // structural: written at once
		int afterHome = store.diskWrites();
		assertEquals(1, afterHome);

		StasisPearl pearl = new StasisPearl("minecraft:overworld", 1, 65, 1);
		pearl.homeId = home.id;
		store.addPearl(pearl);
		for (int i = 0; i < 100; i++) {
			pearl.lastSeenMs = i;
			pearl.ownerIgn = "Owner" + (i % 2);
			store.updatePearl(pearl);
		}
		assertEquals(afterHome, store.diskWrites(), "no writes before the debounce window");
		assertTrue(store.isDirty());

		long now = System.currentTimeMillis();
		store.flushIfDue(now + StasisStore.FLUSH_INTERVAL_MS);
		store.flushIfDue(now + StasisStore.FLUSH_INTERVAL_MS); // already clean: no second write
		assertEquals(afterHome + 1, store.diskWrites());
		store.flushNow(); // waits for the background write

		StasisStore again = new StasisStore(dir);
		assertEquals(1, again.pearls().size());
		assertEquals("Owner1", again.pearls().get(0).ownerIgn);
	}

	@Test
	void heartbeatFlushesOnSlowCadence(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		store.setDeferredWrites(true);
		store.addHome(new StasisHome("h", "minecraft:overworld", 0, 64, 0, 2, 1));
		int base = store.diskWrites();
		store.markHeartbeat();
		long now = System.currentTimeMillis();
		store.flushIfDue(now + StasisStore.FLUSH_INTERVAL_MS);
		assertEquals(base, store.diskWrites(), "heartbeat waits for the slow cadence");
		store.flushIfDue(now + StasisStore.HEARTBEAT_FLUSH_MS);
		assertEquals(base + 1, store.diskWrites());
		store.flushNow();
	}

	@Test
	void flushNowWritesImmediately(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		store.setDeferredWrites(true);
		StasisPearl pearl = new StasisPearl("minecraft:overworld", 1, 65, 1);
		store.addPearl(pearl);
		assertEquals(0, store.diskWrites());
		store.flushNow();
		assertEquals(1, store.diskWrites());
		assertFalse(store.isDirty());
		assertEquals(1, new StasisStore(dir).pearls().size());
	}

	@Test
	void legacyRadiusClampsOnLoad(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		StasisHome home = new StasisHome("wide", "minecraft:overworld", 0, 64, 0, 8, 1);
		store.addHome(home);
		StasisStore again = new StasisStore(dir);
		assertEquals(StasisHome.MAX_RADIUS_CHUNKS, again.findHomeById(home.id).orElseThrow().radiusChunks);
		String json = Files.readString(dir.resolve("chambers.json"));
		assertTrue(json.contains("\"radiusChunks\": 4"));
	}

	@Test
	void freePearlNearSkipsNeighbourChamberRecord(@TempDir Path dir) throws IOException {
		StasisStore store = new StasisStore(dir);
		String dim = "minecraft:overworld";
		// Chambers 2 blocks apart (real spawn row): seat A at x=0, seat B at x=2.
		StasisChamberSlot a = new StasisChamberSlot("h", dim, 0, 39, 0, 0, 33, 0, StasisChamberSlot.KIND_WATER);
		StasisChamberSlot b = new StasisChamberSlot("h", dim, 2, 39, 0, 2, 33, 0, StasisChamberSlot.KIND_WATER);
		store.upsertSlot(a);
		store.upsertSlot(b);
		StasisPearl inA = new StasisPearl(dim, 0, 39, 0);
		store.addPearl(inA);
		a.pearlId = inA.id;
		store.updateSlot(a);

		// Old lookup steals A's record for a pearl in B; the free lookup does not.
		assertEquals(inA.id, store.findPearlNear(dim, 2, 39, 0, 2).orElseThrow().id);
		assertTrue(store.findFreePearlNear(dim, 2, 39, 0, 2, b.id, java.util.Set.of()).isEmpty());
		// A's own slot may still reclaim it; an excluded (claimed this tick) id may not.
		assertEquals(inA.id, store.findFreePearlNear(dim, 0, 39, 0, 2, a.id, java.util.Set.of()).orElseThrow().id);
		assertTrue(store.findFreePearlNear(dim, 0, 39, 0, 2, a.id, java.util.Set.of(inA.id)).isEmpty());
	}

	@Test
	void loadRepairsSlotsAndPearlsWiredToFarHome(@TempDir Path dir) throws IOException {
		String dim = "minecraft:overworld";
		StasisStore store = new StasisStore(dir);
		StasisHome near = new StasisHome("Beta", dim, 0, 64, 0, 2, 2);
		StasisHome far = new StasisHome("Lush", dim, 1_000_000, 64, 1_000_000, 2, 2);
		store.addHome(near);
		store.addHome(far);
		// Slot + pearl physically inside Beta's fence but wired to Lush (the live bug).
		StasisChamberSlot wired = new StasisChamberSlot(far.id, dim, 3, 39, 5, 3, 34, 5, StasisChamberSlot.KIND_WATER);
		store.upsertSlot(wired);
		StasisPearl pearl = new StasisPearl(dim, 3, 39, 5);
		pearl.homeId = far.id;
		store.addPearl(pearl);
		// Orphan slot outside every fence.
		StasisChamberSlot orphan = new StasisChamberSlot(near.id, dim, 500, 39, 500, 500, 34, 500, StasisChamberSlot.KIND_WATER);
		store.upsertSlot(orphan);

		StasisStore again = new StasisStore(dir);
		StasisStore.HomeRepair r = again.lastLoadRepair();
		assertEquals(2, r.slotsMoved());
		assertEquals(1, r.pearlsMoved());
		assertEquals(near.id, again.findSlotById(wired.id).orElseThrow().homeId);
		assertNull(again.findSlotById(orphan.id).orElseThrow().homeId);
		assertEquals(near.id, again.findPearlById(pearl.id).orElseThrow().homeId);
		assertTrue(again.slotsForHome(far.id).isEmpty());
		assertTrue(Files.exists(dir.resolve("chambers.pre-home-repair.json")));

		// Consistent store: nothing to repair on the next load.
		assertFalse(new StasisStore(dir).lastLoadRepair().any());
	}
}
