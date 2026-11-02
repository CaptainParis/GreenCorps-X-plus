package dev.greencorps.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code homes.json} schema_version 1 under {@code config/greencorps/basebackup/}. */
public final class HomeStore {
	private static final int SCHEMA = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path base;
	private final Path file;
	private final List<Home> homes = new ArrayList<>();

	public static Path defaultBaseDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("greencorps").resolve("basebackup");
	}

	public HomeStore(Path base) throws IOException {
		this.base = base;
		this.file = base.resolve("homes.json");
		Files.createDirectories(base);
		if (Files.isRegularFile(file)) load();
	}

	public Path baseDir() {
		return base;
	}

	public synchronized List<Home> homes() {
		return List.copyOf(homes);
	}

	public synchronized Optional<Home> findByName(String name) {
		if (name == null) return Optional.empty();
		Home folded = null;
		for (Home h : homes) {
			if (name.equals(h.name)) return Optional.of(h);
			if (folded == null && h.name != null && name.equalsIgnoreCase(h.name)) folded = h;
		}
		return Optional.ofNullable(folded);
	}

	public synchronized Optional<Home> findById(String id) {
		if (id == null) return Optional.empty();
		for (Home h : homes) {
			if (id.equals(h.id)) return Optional.of(h);
		}
		return Optional.empty();
	}

	public synchronized void add(Home home) throws IOException {
		if (home == null) throw new IOException("null home");
		if (home.id == null || home.id.isBlank()) home.id = UUID.randomUUID().toString();
		if (home.kind == null) home.kind = Home.Kind.HOME;
		if (findById(home.id).isPresent()) throw new IOException("duplicate home " + home.id);
		homes.add(home);
		save();
	}

	public synchronized void update(Home home) throws IOException {
		if (home == null || home.id == null) throw new IOException("null home");
		for (int i = 0; i < homes.size(); i++) {
			if (home.id.equals(homes.get(i).id)) {
				homes.set(i, home);
				save();
				return;
			}
		}
		throw new IOException("unknown home " + home.id);
	}

	public synchronized void remove(String id) throws IOException {
		boolean removed = homes.removeIf(h -> h.id != null && h.id.equals(id));
		if (!removed) throw new IOException("unknown home " + id);
		save();
	}

	private void load() throws IOException {
		FileModel model = GSON.fromJson(Files.readString(file), FileModel.class);
		if (model == null) return;
		if (model.schemaVersion != SCHEMA) {
			throw new IOException("homes.json schema_version " + model.schemaVersion + " != " + SCHEMA);
		}
		homes.clear();
		if (model.homes == null) return;
		for (Home h : model.homes) {
			if (h == null || h.id == null || h.id.isBlank()) continue;
			if (h.kind == null) h.kind = Home.Kind.HOME;
			homes.add(h);
		}
	}

	private void save() throws IOException {
		FileModel model = new FileModel();
		model.schemaVersion = SCHEMA;
		model.homes = homes;
		AtomicFiles.writeString(file, GSON.toJson(model));
	}

	private static final class FileModel {
		@SerializedName("schema_version")
		int schemaVersion = SCHEMA;
		List<Home> homes = new ArrayList<>();
	}
}
