package dev.greencorps.book;

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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Book Filler templates in {@code config/greencorps/book-templates.json}.
 * The active template is the one the module fields edit.
 */
public final class BookTemplates {
	private static final Logger LOG = LoggerFactory.getLogger("GreenCorps");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HHmm");
	private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");

	private final Path file;
	private final List<Template> templates = new ArrayList<>();
	private String activeId = "";

	public BookTemplates(Path file) {
		this.file = file;
	}

	public static Path defaultFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("greencorps").resolve("book-templates.json");
	}

	public static BookTemplates load(Path file, String title, String signer, String text) {
		BookTemplates store = new BookTemplates(file);
		if (file != null && Files.isRegularFile(file)) {
			try {
				FileModel model = GSON.fromJson(Files.readString(file), FileModel.class);
				if (model != null && model.templates != null) {
					for (Template t : model.templates) {
						if (t == null) continue;
						if (t.id == null || t.id.isBlank()) t.id = UUID.randomUUID().toString();
						if (t.name == null || t.name.isBlank()) t.name = "Untitled";
						if (t.title == null) t.title = "";
						if (t.signer == null) t.signer = "";
						if (t.text == null) t.text = "";
						store.templates.add(t);
					}
					store.activeId = model.active == null ? "" : model.active;
				}
			} catch (Exception e) {
				LOG.error("book templates: {}", e.toString());
			}
		}
		if (store.templates.isEmpty()) {
			store.templates.add(template("Untitled", false, title, signer, text));
		}
		if (store.find(store.activeId) == null) store.activeId = store.templates.get(0).id;
		return store;
	}

	public List<Template> displayOrder() {
		List<Template> copy = new ArrayList<>(templates);
		copy.sort(Comparator.comparing((Template t) -> !t.pinned));
		return copy;
	}

	public Template active() {
		Template found = find(activeId);
		if (found != null) return found;
		activeId = templates.get(0).id;
		return templates.get(0);
	}

	public void select(String id) {
		if (find(id) == null) return;
		activeId = id;
		save();
	}

	public void writeActive(String title, String signer, String text) {
		Template t = active();
		t.title = title == null ? "" : title;
		t.signer = signer == null ? "" : signer;
		t.text = text == null ? "" : text;
		save();
	}

	public Template duplicate(String id) {
		Template src = find(id);
		if (src == null) return active();
		Template copy = template(copyName(names(), src.name), false, src.title, src.signer, src.text);
		templates.add(copy);
		activeId = copy.id;
		save();
		return copy;
	}

	public Template delete(String id) {
		int index = indexOf(id);
		if (index < 0) return active();
		templates.remove(index);
		if (templates.isEmpty()) {
			templates.add(template("Untitled", false, "", "", ""));
			activeId = templates.get(0).id;
			save();
			return templates.get(0);
		}
		if (id.equals(activeId)) {
			List<Template> order = displayOrder();
			int next = Math.min(index, order.size() - 1);
			activeId = order.get(Math.max(0, next)).id;
		}
		save();
		return active();
	}

	public void setPinned(String id, boolean pinned) {
		Template t = find(id);
		if (t == null) return;
		t.pinned = pinned;
		save();
	}

	public Template addSigned(String title, String text, String signer, LocalTime time) {
		String name = uniqueName(names(), title == null || title.isBlank() ? "Untitled" : title.trim(), time);
		Template t = template(name, false, title == null ? "" : title.trim(), signer, text);
		templates.add(t);
		activeId = t.id;
		save();
		return t;
	}

	public Template addHeld(String name, String title, String signer, String text) {
		String base = name == null || name.isBlank() ? "Untitled" : name.trim();
		Template t = template(uniqueName(names(), base, LocalTime.now()), false, title, signer, text);
		templates.add(t);
		activeId = t.id;
		save();
		return t;
	}

	public static String uniqueName(Set<String> names, String base, LocalTime time) {
		String root = base == null || base.isBlank() ? "Untitled" : base.trim();
		Set<String> have = names == null ? Set.of() : names;
		if (!have.contains(root)) return root;
		LocalTime when = time == null ? LocalTime.now() : time;
		String hm = root + " " + when.format(HM);
		if (!have.contains(hm)) return hm;
		return root + " " + when.format(HMS);
	}

	public static String copyName(Set<String> names, String base) {
		String root = base == null || base.isBlank() ? "Untitled" : base.trim();
		String first = root + " copy";
		if (names == null || !names.contains(first)) return first;
		int n = 2;
		while (names.contains(root + " copy " + n)) n++;
		return root + " copy " + n;
	}

	private Set<String> names() {
		Set<String> names = new HashSet<>();
		for (Template t : templates) names.add(t.name);
		return names;
	}

	private Template find(String id) {
		if (id == null) return null;
		for (Template t : templates) if (id.equals(t.id)) return t;
		return null;
	}

	private int indexOf(String id) {
		for (int i = 0; i < templates.size(); i++) {
			if (templates.get(i).id.equals(id)) return i;
		}
		return -1;
	}

	private static Template template(String name, boolean pinned, String title, String signer, String text) {
		Template t = new Template();
		t.id = UUID.randomUUID().toString();
		t.name = name;
		t.pinned = pinned;
		t.title = title == null ? "" : title;
		t.signer = signer == null ? "" : signer;
		t.text = text == null ? "" : text;
		return t;
	}

	private void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			FileModel model = new FileModel();
			model.active = activeId;
			model.templates = templates;
			Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
			Files.writeString(tmp, GSON.toJson(model));
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOG.error("book templates: {}", e.toString());
		}
	}

	public static final class Template {
		public String id;
		public String name;
		public boolean pinned;
		public String title;
		public String signer;
		public String text;
	}

	private static final class FileModel {
		@SerializedName("active")
		String active;
		@SerializedName("templates")
		List<Template> templates;
	}
}
