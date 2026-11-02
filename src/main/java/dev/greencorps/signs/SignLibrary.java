package dev.greencorps.signs;

import dev.greencorps.GreenCorps;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Opens sign packs from {@code config/greencorps/sign-search/}. Files stay out of the jar. */
public final class SignLibrary {
	public enum Dim {
		OVERWORLD("overworld.gesign", "Overworld"),
		NETHER("the_nether.gesign", "Nether"),
		END("the_end.gesign", "End");

		public final String file;
		public final String label;

		Dim(String file, String label) {
			this.file = file;
			this.label = label;
		}
	}

	private static final SignLibrary INSTANCE = new SignLibrary();

	private final Map<Dim, Slot> slots = new EnumMap<>(Dim.class);

	private SignLibrary() {
	}

	public static SignLibrary get() {
		return INSTANCE;
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve("greencorps").resolve("sign-search");
	}

	public boolean has(Dim dim) {
		return Files.isRegularFile(directory().resolve(dim.file));
	}

	/** Opens once and keeps the map. {@code null} when that dimension file is absent. */
	public synchronized SignPack open(Dim dim) {
		Slot slot = slots.get(dim);
		if (slot != null) return slot.pack;
		Path path = directory().resolve(dim.file);
		if (!Files.isRegularFile(path)) {
			slots.put(dim, new Slot(null, null));
			return null;
		}
		try {
			SignPack pack = SignPack.open(path);
			slots.put(dim, new Slot(pack, null));
			GreenCorps.LOG.info("Opened sign pack {} ({} texts)", dim.file, pack.textCount());
			return pack;
		} catch (IOException e) {
			slots.put(dim, new Slot(null, e.toString()));
			GreenCorps.LOG.warn("Sign pack {} failed: {}", dim.file, e.toString());
			return null;
		}
	}

	public synchronized String failure(Dim dim) {
		Slot slot = slots.get(dim);
		return slot == null ? null : slot.error;
	}

	private static final class Slot {
		final SignPack pack;
		final String error;

		Slot(SignPack pack, String error) {
			this.pack = pack;
			this.error = error;
		}
	}
}
