package dev.greencorps.util;

import dev.greencorps.GreenCorps;
import meteordevelopment.meteorclient.MeteorClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * God's Eye Companion moved to its own jar. Meteor only saves modules and HUD elements that are registered,
 * so running X+ without that jar would drop the Companion's settings from {@code modules.nbt} / {@code hud.nbt}
 * on the next save. Before Meteor loads, copy them to {@code config/greencorps/companion-settings.nbt}; the
 * Companion jar restores them when Meteor did not load any. Tokens and privacy zones have their own files.
 */
public final class CompanionSettingsBackup {
	public static final String COMPANION_MOD_ID = "gods-eye-companion";
	public static final String COMPANION_NAME = "god's-eye-companion";

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
		.resolve("greencorps").resolve("companion-settings.nbt");

	private CompanionSettingsBackup() {}

	public static boolean companionInstalled() {
		return FabricLoader.getInstance().isModLoaded(COMPANION_MOD_ID);
	}

	/** True when settings are waiting for the Companion jar. */
	public static boolean hasBackup() {
		return Files.isRegularFile(FILE);
	}

	/** Call from {@code onInitialize}, which runs before Meteor loads its systems. */
	public static void backupIfCompanionMissing() {
		if (companionInstalled()) return;
		try {
			NbtCompound backup = hasBackup() ? NbtIo.readCompressed(FILE, NbtSizeTracker.ofUnlimitedBytes()) : new NbtCompound();
			boolean changed = false;
			NbtCompound module = find(read("modules.nbt"), "modules");
			if (module != null) {
				backup.put("module", module);
				changed = true;
			}
			NbtList hud = findAll(read("hud.nbt"), "elements");
			if (!hud.isEmpty()) {
				backup.put("hud", hud);
				changed = true;
			}
			if (!changed) return;
			Files.createDirectories(FILE.getParent());
			NbtIo.writeCompressed(backup, FILE);
			GreenCorps.LOG.info("GreenCorps: God's Eye Companion settings kept in {} until its jar is installed", FILE);
		} catch (IOException | RuntimeException e) {
			GreenCorps.LOG.warn("GreenCorps: could not back up God's Eye Companion settings: {}", e.toString());
		}
	}

	private static NbtCompound read(String name) throws IOException {
		Path file = MeteorClient.FOLDER.toPath().resolve(name);
		return Files.isRegularFile(file) ? NbtIo.read(file) : null;
	}

	private static NbtCompound find(NbtCompound root, String listKey) {
		NbtList list = findAll(root, listKey);
		return list.isEmpty() ? null : list.getCompound(0);
	}

	private static NbtList findAll(NbtCompound root, String listKey) {
		NbtList out = new NbtList();
		if (root == null) return out;
		for (NbtElement e : root.getList(listKey, NbtElement.COMPOUND_TYPE)) {
			if (e instanceof NbtCompound c && COMPANION_NAME.equals(c.getString("name"))) out.add(c.copy());
		}
		return out;
	}
}
