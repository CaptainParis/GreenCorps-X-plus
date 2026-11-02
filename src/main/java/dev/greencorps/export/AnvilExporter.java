package dev.greencorps.export;

import dev.greencorps.backup.BackupEngine;
import dev.greencorps.backup.ChunkCapture;
import dev.greencorps.backup.ChunkObject;
import dev.greencorps.backup.Home;
import dev.greencorps.backup.ObjectStore;
import dev.greencorps.backup.SnapshotIndex;
import dev.greencorps.backup.SnapshotManifest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes a snapshot into Anvil region files via vanilla RegionFile.
 * Always applies {@link ExportTransform} (yaw scramble + optional Shift Coords To 0).
 */
public final class AnvilExporter {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/anvil");

	private final BackupEngine engine;

	public AnvilExporter(BackupEngine engine) {
		this.engine = engine;
	}

	public ExportResult export(String homeId, long epochMs, Path destWorld, String levelName,
							   Home home, boolean shiftCoordsTo0) throws IOException {
		SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), homeId);
		Optional<SnapshotIndex.Entry> entry = idx.atOrBefore(epochMs);
		if (entry.isEmpty()) {
			entry = idx.latest();
		}
		if (entry.isEmpty()) {
			throw new IOException("no snapshots for home");
		}
		return exportEntry(homeId, entry.get(), destWorld, levelName, home, shiftCoordsTo0, idx, false, 0L, null);
	}

	public ExportResult exportEntry(String homeId, SnapshotIndex.Entry entry, Path destWorld, String levelName,
									Home home, boolean shiftCoordsTo0, SnapshotIndex idx) throws IOException {
		return exportEntry(homeId, entry, destWorld, levelName, home, shiftCoordsTo0, idx, false, 0L, null);
	}

	/**
	 * @param embedded when true, write noise level.dat with {@code worldSeed}; caller may sculpt ring after.
	 * @param transformReuse when non-null, use this transform (same yaw) instead of picking a new one.
	 */
	public ExportResult exportEntry(String homeId, SnapshotIndex.Entry entry, Path destWorld, String levelName,
									Home home, boolean shiftCoordsTo0, SnapshotIndex idx,
									boolean embedded, long worldSeed, ExportTransform transformReuse) throws IOException {
		Path snapPath = engine.manifestPath(homeId, entry.filename);
		SnapshotManifest manifest = SnapshotManifest.read(snapPath);
		ExportReport report = new ExportReport();
		ObjectStore store = engine.store();

		ExportTransform tx = transformReuse != null ? transformReuse
			: ExportTransform.forHome(home, shiftCoordsTo0);
		report.note("transform yaw=" + tx.yawDegrees
			+ " shiftTo0=" + tx.shiftToOrigin
			+ " spawn=" + tx.destSpawnX + "," + tx.destSpawnY + "," + tx.destSpawnZ
			+ " (post-transform cx,cz only)");

		Files.createDirectories(destWorld);
		Path regionDir = destWorld.resolve("region");
		Files.createDirectories(regionDir);

		RegistryKey<World> dim = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
		StorageKey storageKey = new StorageKey(levelName, dim, "region");

		Map<Long, NbtCompound> source = new HashMap<>();
		Set<Integer> mapIds = new HashSet<>();
		int dataVersion = ChunkObject.MC_DATA_VERSION;
		int legacySkipped = 0;
		int total = manifest.chunks.size();

		for (Map.Entry<String, String> ce : manifest.chunks.entrySet()) {
			String[] parts = ce.getKey().split(",");
			int cx = Integer.parseInt(parts[0]);
			int cz = Integer.parseInt(parts[1]);
			String chunkHash = ce.getValue();

			byte[] raw;
			try {
				raw = store.getChunkObject(chunkHash);
			} catch (IOException e) {
				raw = recoverRawChunk(homeId, cx, cz, entry.epochMs, idx, report);
				if (raw == null) continue;
			}

			if (ChunkCapture.isLegacyJsonBlob(raw)) {
				legacySkipped++;
				continue;
			}

			NbtCompound chunkNbt = ChunkCapture.readNbt(raw);
			ChunkCapture.ensurePlayableStatus(chunkNbt);
			ChunkCapture.collectFilledMapIds(chunkNbt, mapIds);
			if (!chunkNbt.contains("xPos")) chunkNbt.putInt("xPos", cx);
			if (!chunkNbt.contains("zPos")) chunkNbt.putInt("zPos", cz);
			if (chunkNbt.contains("DataVersion")) {
				dataVersion = chunkNbt.getInt("DataVersion");
			}
			source.put(ChunkPos.toLong(cx, cz), chunkNbt);
		}

		ChunkRestacker.RestackResult restacked = ChunkRestacker.restack(source, tx);
		int written = 0;
		Map<Long, RegionFile> regions = new HashMap<>();
		try {
			for (Map.Entry<Long, NbtCompound> ce : restacked.chunksByPos().entrySet()) {
				ChunkPos pos = new ChunkPos(ChunkPos.getPackedX(ce.getKey()), ChunkPos.getPackedZ(ce.getKey()));
				NbtCompound chunkNbt = ce.getValue();
				chunkNbt.putInt("xPos", pos.x);
				chunkNbt.putInt("zPos", pos.z);
				ChunkCapture.collectFilledMapIds(chunkNbt, mapIds);

				int rx = pos.getRegionX();
				int rz = pos.getRegionZ();
				long rkey = ChunkPos.toLong(rx, rz);
				RegionFile rf = regions.get(rkey);
				if (rf == null) {
					Path regionPath = regionDir.resolve("r." + rx + "." + rz + ".mca");
					rf = new RegionFile(storageKey, regionPath, regionDir, false);
					regions.put(rkey, rf);
				}
				try (DataOutputStream out = rf.getChunkOutputStream(pos)) {
					NbtIo.writeCompound(chunkNbt, out);
				}
				written++;
			}
		} finally {
			for (RegionFile rf : regions.values()) {
				try {
					rf.close();
				} catch (Exception ignored) {}
			}
		}

		if (embedded) {
			LevelDatWriter.writeNoise(destWorld, levelName, tx.destSpawnX, tx.destSpawnY, tx.destSpawnZ,
				dataVersion, worldSeed);
		} else {
			LevelDatWriter.writeVoid(destWorld, levelName, tx.destSpawnX, tx.destSpawnY, tx.destSpawnZ,
				dataVersion, 0L);
		}
		PlayerExport.writeIntoWorld(destWorld, tx.destSpawnX, tx.destSpawnY, tx.destSpawnZ, tx.destYaw);
		MapDataExport.collectFromLocalPlayer(mapIds);
		MapDataExport.writeMaps(destWorld, mapIds, tx);
		report.write(destWorld);

		if (written == 0) {
			LOG.warn("export wrote 0 v2 chunks home={} legacySkipped={} total={}", homeId, legacySkipped, total);
			throw new IOException(
				"0 of " + total + " chunks use the new format — fly around the base once with Time Machine armed, commit, then Export again");
		}
		if (legacySkipped > 0) {
			LOG.info("export wrote {}/{} v2 chunks ({} legacy skipped) for {}", written, total, legacySkipped, homeId);
		}
		return new ExportResult(destWorld, written, legacySkipped, total, tx, restacked.chunksByPos());
	}

	public record ExportResult(Path dest, int written, int legacySkipped, int total,
							   ExportTransform transform, Map<Long, NbtCompound> destChunks) {}

	private byte[] recoverRawChunk(String homeId, int cx, int cz, long preferEpoch,
								   SnapshotIndex idx, ExportReport report) {
		List<SnapshotIndex.Entry> entries = idx.entries();
		long bestDist = Long.MAX_VALUE;
		byte[] best = null;
		SnapshotIndex.Entry bestEntry = null;
		for (SnapshotIndex.Entry e : entries) {
			long dist = Math.abs(e.epochMs - preferEpoch);
			if (dist >= bestDist) continue;
			try {
				SnapshotManifest m = SnapshotManifest.read(engine.manifestPath(homeId, e.filename));
				String hash = m.getChunk(cx, cz);
				if (hash == null) continue;
				byte[] raw = engine.store().getChunkObject(hash);
				best = raw;
				bestDist = dist;
				bestEntry = e;
			} catch (IOException ignored) {}
		}
		if (best != null && bestEntry != null) {
			report.substituted(cx, cz, 0, bestEntry.epochMs, "chunk-object");
		}
		return best;
	}
}
