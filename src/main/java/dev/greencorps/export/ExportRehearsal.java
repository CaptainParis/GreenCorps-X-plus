package dev.greencorps.export;

import dev.greencorps.backup.BackupEngine;
import dev.greencorps.backup.Home;
import dev.greencorps.backup.SnapshotIndex;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Periodically export newest snapshot and read it back with vanilla RegionFile.
 * Core trust feature: export path cannot rot silently.
 */
public final class ExportRehearsal {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/rehearsal");

	private final BackupEngine engine;
	private int commitsSinceRehearsal;
	private final int everyNCommits;
	private final long maxIntervalMs;
	private final boolean retainMonthly;
	private long lastRetainMonth = -1;
	private volatile String lastFailReason;

	public ExportRehearsal(BackupEngine engine, int everyNCommits, long maxIntervalMs, boolean retainMonthly) {
		this.engine = engine;
		this.everyNCommits = everyNCommits;
		this.maxIntervalMs = maxIntervalMs;
		this.retainMonthly = retainMonthly;
	}

	public String lastFailReason() {
		return lastFailReason;
	}

	public void clearFailReason() {
		lastFailReason = null;
	}

	public void onCommit(Home home) {
		commitsSinceRehearsal++;
		boolean due = commitsSinceRehearsal >= everyNCommits
			|| (engine.lastRehearsalAt() > 0
			&& System.currentTimeMillis() - engine.lastRehearsalAt() >= maxIntervalMs)
			|| engine.lastRehearsalAt() == 0;
		if (!due) return;
		commitsSinceRehearsal = 0;
		run(home);
	}

	public boolean run(Home home) {
		Path tmp = null;
		try {
			SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), home.id);
			Optional<SnapshotIndex.Entry> latest = idx.latest();
			if (latest.isEmpty()) {
				pass();
				return true;
			}
			if (latest.get().capturedChunks <= 0) {
				pass();
				return true;
			}

			tmp = engine.baseDir().resolve("rehearsal").resolve(home.id + "-" + System.currentTimeMillis());
			AnvilExporter exporter = new AnvilExporter(engine);
			AnvilExporter.ExportResult exported = exporter.exportEntry(
				home.id, latest.get(), tmp, "rehearsal-" + home.name,
				home, false, idx);

			ValidateResult vr = validate(tmp, exported.written());
			boolean ok = vr.ok();
			if (ok) {
				pass();
			} else {
				fail("validate read " + vr.count() + " readable / rejected " + vr.rejected()
					+ " (exported " + exported.written() + ")");
			}

			boolean keep = retainMonthly && shouldRetainThisMonth();
			if (ok && keep) {
				Path archive = engine.baseDir().resolve("archives").resolve(home.id);
				Files.createDirectories(archive);
				Path dest = archive.resolve("monthly-" + yearMonth() + "-" + home.name);
				if (Files.exists(dest)) deleteRecursive(dest);
				Files.move(tmp, dest);
				tmp = null; // ownership transferred
				lastRetainMonth = yearMonth();
			}
			return ok;
		} catch (Exception e) {
			String reason = shortReason(e);
			LOG.warn("rehearsal failed: {}", e.toString());
			fail(reason);
			return false;
		} finally {
			if (tmp != null) {
				try {
					deleteRecursive(tmp);
				} catch (IOException ignored) {}
			}
		}
	}

	private void pass() {
		lastFailReason = null;
		engine.markRehearsal(true);
	}

	private void fail(String reason) {
		lastFailReason = reason;
		engine.markRehearsal(false);
	}

	private static String shortReason(Exception e) {
		String msg = e.getMessage();
		if (msg == null || msg.isBlank()) msg = e.toString();
		msg = msg.replace('\n', ' ').trim();
		if (msg.length() > 160) msg = msg.substring(0, 157) + "...";
		return msg;
	}

	private boolean shouldRetainThisMonth() {
		return lastRetainMonth != yearMonth();
	}

	private static long yearMonth() {
		java.time.LocalDate d = java.time.LocalDate.now();
		return d.getYear() * 100L + d.getMonthValue();
	}

	record ValidateResult(boolean ok, int count, int rejected) {}

	static ValidateResult validate(Path worldDir, int expectedChunks) throws IOException {
		Path regionDir = worldDir.resolve("region");
		if (!Files.isDirectory(regionDir)) {
			LOG.info("validate: no region dir");
			return new ValidateResult(false, 0, 0);
		}
		Path levelDat = worldDir.resolve("level.dat");
		if (!Files.isRegularFile(levelDat)) {
			LOG.info("validate: no level.dat");
			return new ValidateResult(false, 0, 0);
		}

		NbtCompound level = NbtIo.readCompressed(levelDat, NbtSizeTracker.ofUnlimitedBytes());
		if (!level.contains("Data")) {
			LOG.info("validate: level.dat missing Data");
			return new ValidateResult(false, 0, 0);
		}
		int dataVersion = level.getCompound("Data").getInt("DataVersion");
		if (dataVersion <= 0) {
			LOG.info("validate: bad DataVersion {}", dataVersion);
			return new ValidateResult(false, 0, 0);
		}

		RegistryKey<World> dim = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
		StorageKey key = new StorageKey("rehearsal", dim, "region");
		int count = 0;
		int rejected = 0;
		String firstRejectedKeys = null;
		try (Stream<Path> files = Files.list(regionDir)) {
			for (Path regionPath : files.filter(p -> p.getFileName().toString().endsWith(".mca")).toList()) {
				try (RegionFile rf = new RegionFile(key, regionPath, regionDir, false)) {
					String name = regionPath.getFileName().toString(); // r.X.Z.mca
					String[] parts = name.substring(2, name.length() - 4).split("\\.");
					int rx = Integer.parseInt(parts[0]);
					int rz = Integer.parseInt(parts[1]);
					for (int x = 0; x < 32; x++) {
						for (int z = 0; z < 32; z++) {
							ChunkPos pos = new ChunkPos((rx << 5) + x, (rz << 5) + z);
							if (!rf.hasChunk(pos)) continue;
							try (DataInputStream in = rf.getChunkInputStream(pos)) {
								NbtCompound nbt = NbtIo.readCompound(in);
								if (nbt == null || !isReadableChunk(nbt)) {
									rejected++;
									if (firstRejectedKeys == null && nbt != null) {
										firstRejectedKeys = nbt.getKeys().toString();
									}
									continue;
								}
								count++;
							}
						}
					}
				}
			}
		}
		boolean ok = count > 0 && (expectedChunks <= 0 || count <= Math.max(expectedChunks, 1) * 2);
		if (!ok) {
			LOG.info("validate: read {} chunks (rejected {}, expected~{}) firstRejectedKeys={}",
				count, rejected, expectedChunks, firstRejectedKeys);
		}
		return new ValidateResult(ok, count, rejected);
	}

	/** Accept modern anvil chunks with sections, or Status+xPos+zPos stubs. */
	static boolean isReadableChunk(NbtCompound nbt) {
		if (nbt.contains("sections")) return true;
		return nbt.contains("Status") && nbt.contains("xPos") && nbt.contains("zPos");
	}

	private static void deleteRecursive(Path p) throws IOException {
		if (!Files.exists(p)) return;
		try (Stream<Path> walk = Files.walk(p)) {
			walk.sorted(Comparator.reverseOrder()).forEach(f -> {
				try { Files.deleteIfExists(f); } catch (IOException ignored) {}
			});
		}
	}
}
