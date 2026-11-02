package dev.greencorps.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-shot: copy Meteor module settings from legacy {@code base-backup} key to {@code time-machine}.
 */
public final class TimeMachineConfigMigrate {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/time-machine-migrate");
	private static final String OLD = "base-backup";
	private static final String NEW = "time-machine";
	private static boolean attempted;

	private TimeMachineConfigMigrate() {}

	public static void maybeMigrate() {
		if (attempted) return;
		attempted = true;
		try {
			Path root = FabricLoader.getInstance().getGameDir().resolve("meteor-client");
			Path modulesNbt = root.resolve("modules.nbt");
			if (Files.isRegularFile(modulesNbt)) {
				migrateNbt(modulesNbt);
			}
			Path modulesDir = root.resolve("modules");
			if (Files.isDirectory(modulesDir)) {
				Path oldJson = modulesDir.resolve(OLD + ".json");
				Path newJson = modulesDir.resolve(NEW + ".json");
				if (Files.isRegularFile(oldJson) && !Files.exists(newJson)) {
					Files.copy(oldJson, newJson);
					LOG.info("Copied {} -> {}", oldJson.getFileName(), newJson.getFileName());
				}
			}
		} catch (Exception e) {
			LOG.warn("Time Machine config migrate skipped: {}", e.toString());
		}
	}

	private static void migrateNbt(Path modulesNbt) throws Exception {
		NbtCompound root = readModulesNbt(modulesNbt);
		if (root == null || !root.contains(OLD) || root.contains(NEW)) return;
		root.put(NEW, root.getCompound(OLD).copy());
		NbtIo.writeCompressed(root, modulesNbt);
		LOG.info("Migrated modules.nbt {} -> {}", OLD, NEW);
	}

	/** Meteor may store compressed or plain NBT. */
	private static NbtCompound readModulesNbt(Path path) throws Exception {
		try {
			return NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
		} catch (Exception compressedFail) {
			try (InputStream in = Files.newInputStream(path);
				 DataInputStream data = new DataInputStream(in)) {
				return NbtIo.readCompound(data, NbtSizeTracker.ofUnlimitedBytes());
			} catch (Exception plainFail) {
				LOG.debug("modules.nbt not readable as compressed ({}) or plain ({})",
					compressedFail.toString(), plainFail.toString());
				return null;
			}
		}
	}
}
