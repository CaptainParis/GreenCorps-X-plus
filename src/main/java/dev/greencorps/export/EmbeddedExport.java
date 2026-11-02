package dev.greencorps.export;

import dev.greencorps.backup.BackupEngine;
import dev.greencorps.backup.Home;
import dev.greencorps.backup.SnapshotIndex;
import dev.greencorps.chat.HomeChat;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manage Backup → Export Embedded… pipeline (bb-embed-* stem). */
public final class EmbeddedExport {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/embed");
	private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
		.withZone(ZoneId.systemDefault());

	private EmbeddedExport() {}

	public static boolean inFlight() {
		return IN_FLIGHT.get();
	}

	/** bb-embed-&lt;base&gt;-yyyy-MM-dd_HH-mm-ss */
	public static String worldStem(Home home, Instant when) {
		return "bb-embed-" + PlayableSavePublisher.nameBase(home) + "-" + TS.format(when);
	}

	public static void runAsync(BackupEngine engine, Home home, SnapshotIndex.Entry entry,
								boolean shiftCoordsTo0, int blendRadius, int budgetMs) {
		if (home == null || engine == null) {
			HomeChat.warn("Embedded export: store not open.");
			return;
		}
		if ("minecraft:the_nether".equals(home.dimension)) {
			HomeChat.warn("Embedded export: Nether homes are not supported.");
			return;
		}
		if (!IN_FLIGHT.compareAndSet(false, true)) {
			HomeChat.info("Embedded export already running…");
			return;
		}
		HomeChat.info("Embedded export running…");
		PlayableSavePublisher.submit(() -> {
			Path tmp = null;
			try {
				MinecraftClient mc = MinecraftClient.getInstance();
				if (mc == null) throw new IllegalStateException("no client");
				Path saves = PlayableSavePublisher.savesRoot(mc);
				Files.createDirectories(saves);
				SnapshotIndex idx = new SnapshotIndex(engine.refsDir(), home.id);
				SnapshotIndex.Entry use = entry;
				if (use == null) {
					Optional<SnapshotIndex.Entry> latest = idx.latest();
					if (latest.isEmpty()) throw new PlayableSavePublisher.NoBackupDataException(home.name);
					use = latest.get();
				}

				ExportTransform tx = ExportTransform.forHome(home, shiftCoordsTo0);
				// Lightweight profile defaults before restack; refined after export.
				EmbedProfile rough = new EmbedProfile(
					home.dimension,
					"minecraft:the_end".equals(home.dimension),
					"minecraft:the_end".equals(home.dimension) ? "minecraft:end_highlands" : "minecraft:plains",
					0, home.spawnY > 0 ? home.spawnY : 64, 0);
				SeedSearch.Match match = SeedSearch.search(rough, tx, budgetMs);

				String stem = worldStem(home, Instant.now());
				Path dest = saves.resolve(stem);
				tmp = saves.resolve(stem + ".tmp-" + System.currentTimeMillis());

				AnvilExporter exporter = new AnvilExporter(engine);
				AnvilExporter.ExportResult pass = exporter.exportEntry(
					home.id, use, tmp, stem, home, shiftCoordsTo0, idx, true, match.seed(), tx);

				EmbedProfile profile = EmbedProfile.fromRestacked(home, pass.destChunks());
				HeightRingSculpt.sculpt(tmp, stem, pass.transform(), profile,
					pass.destChunks(), blendRadius);

				if (Files.exists(dest)) PlayableSavePublisher.deleteRecursive(dest);
				Files.move(tmp, dest);
				tmp = null;
				final String biome = match.biomeId();
				final long seed = match.seed();
				final Path out = dest;
				chat(() -> HomeChat.info("ready: " + out.getFileName()
					+ " seed=" + seed + " match=" + biome));
			} catch (Exception e) {
				LOG.warn("embedded export failed: {}", e.toString());
				chat(() -> HomeChat.warn("Embedded export failed: " + e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "" : " — " + e.getMessage())));
				if (tmp != null) {
					try { PlayableSavePublisher.deleteRecursive(tmp); } catch (Exception ignored) {}
				}
			} finally {
				IN_FLIGHT.set(false);
			}
		});
	}

	private static void chat(Runnable r) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null) mc.execute(r);
		else r.run();
	}
}
