package dev.greencorps.schedule;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code automations.json} schema_version 1 under {@code config/greencorps/scheduler/}. */
public final class AutomationStore {
	private static final Logger LOG = LoggerFactory.getLogger("GreenCorps");
	private static final int SCHEMA = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;
	private final List<Automation> automations = new ArrayList<>();
	private int revision;

	public AutomationStore(Path file) {
		this.file = file;
	}

	public static Path defaultFile() {
		return FabricLoader.getInstance().getConfigDir()
			.resolve("greencorps").resolve("scheduler").resolve("automations.json");
	}

	public static AutomationStore loadOrCreate() {
		return load(defaultFile());
	}

	public static AutomationStore load(Path file) {
		AutomationStore store = new AutomationStore(file);
		if (file != null && Files.isRegularFile(file)) {
			try {
				FileModel model = GSON.fromJson(Files.readString(file), FileModel.class);
				if (model != null && model.automations != null) {
					for (Automation a : model.automations) {
						if (a == null) continue;
						normalize(a);
						store.automations.add(a);
					}
				}
			} catch (Exception e) {
				LOG.error("timed commands store: {}", e.toString());
			}
		}
		return store;
	}

	private static void normalize(Automation a) {
		if (a.id == null || a.id.isBlank()) a.id = UUID.randomUUID().toString();
		if (a.name == null || a.name.isBlank()) a.name = "Untitled";
		if (a.trigger == null) a.trigger = Automation.Trigger.TIME_OF_DAY;
		if (a.clock == null) a.clock = DayCycle.formatClock(a.resolvedDayTick());
		if (a.commands == null) a.commands = new ArrayList<>();
		else {
			List<String> cleaned = new ArrayList<>();
			for (String line : a.commands) {
				if (line != null) cleaned.add(line);
			}
			a.commands = cleaned;
		}
		if (a.trigger == Automation.Trigger.EVERY_N_TICKS && a.offsetTicks < 1) {
			a.offsetTicks = 20;
		}
	}

	public Path file() {
		return file;
	}

	public synchronized int revision() {
		return revision;
	}

	public synchronized List<Automation> list() {
		return List.copyOf(automations);
	}

	public synchronized Optional<Automation> find(String id) {
		if (id == null) return Optional.empty();
		for (Automation a : automations) {
			if (id.equals(a.id)) return Optional.of(a);
		}
		return Optional.empty();
	}

	public synchronized Automation add(Automation a) {
		normalize(a);
		automations.add(a);
		save();
		return a;
	}

	/** Unsaved blank row — call {@link #add} after the editor saves. */
	public Automation newBlank() {
		Automation a = new Automation();
		a.name = "New automation";
		a.trigger = Automation.Trigger.TIME_OF_DAY;
		a.timeValue = 0;
		a.offsetTicks = 0;
		a.clock = "06:00";
		a.commands = new ArrayList<>();
		a.commands.add("/say hello");
		return a;
	}

	public synchronized void update(Automation a) {
		normalize(a);
		for (int i = 0; i < automations.size(); i++) {
			if (automations.get(i).id.equals(a.id)) {
				automations.set(i, a);
				save();
				return;
			}
		}
		automations.add(a);
		save();
	}

	public synchronized void remove(String id) {
		automations.removeIf(a -> a.id.equals(id));
		save();
	}

	public synchronized Automation duplicate(String id) {
		Automation src = find(id).orElse(null);
		if (src == null) return null;
		return add(src.copy());
	}

	public synchronized void setEnabled(String id, boolean enabled) {
		find(id).ifPresent(a -> {
			a.enabled = enabled;
			save();
		});
	}

	/** Persist fire bookkeeping without rewriting unrelated fields. */
	public synchronized void markFired(Automation a) {
		save();
	}

	public synchronized void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			FileModel model = new FileModel();
			model.schemaVersion = SCHEMA;
			model.automations = new ArrayList<>(automations);
			writeAtomic(file, GSON.toJson(model));
			revision++;
		} catch (IOException e) {
			LOG.error("timed commands save: {}", e.toString());
		}
	}

	private static void writeAtomic(Path path, String text) throws IOException {
		Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
		Files.writeString(tmp, text);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static final class FileModel {
		@SerializedName("schema_version")
		int schemaVersion;
		List<Automation> automations;
	}
}
