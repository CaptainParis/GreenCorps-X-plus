package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.modules.BaseBackup;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;

import java.util.Optional;

/** Paste/enter remote coords → create an armed home. */
public class CoordHomeScreen extends WindowScreen {
	private static final String[] DIMS = {
		"minecraft:overworld",
		"minecraft:the_nether",
		"minecraft:the_end"
	};

	private final BaseBackup mod;
	private WTextBox coordsBox;
	private WTextBox nameBox;
	private WDropdown<String> dimDrop;
	private WIntEdit radiusEdit;
	private WLabel status;
	private WButton create;
	private Home blockedBy;

	public CoordHomeScreen(BaseBackup mod) {
		super(GuiThemes.get(), "Add Home (Coordinates)");
		this.mod = mod;
	}

	@Override
	public void initWidgets() {
		WTable t = add(theme.table()).expandX().widget();

		t.add(theme.label("Coordinates")).expandX();
		t.row();
		coordsBox = t.add(theme.textBox("")).expandX().widget();
		coordsBox.tooltip = "x z  |  x y z  |  commas OK  |  optional x:/y:/z:  |  leading /tp ok";
		coordsBox.action = this::refreshParse;
		coordsBox.actionOnUnfocused = this::refreshParse;
		t.row();

		t.add(theme.label("Name")).expandX();
		t.row();
		nameBox = t.add(theme.textBox("")).expandX().widget();
		t.row();

		t.add(theme.label("Dimension")).expandX();
		String cur = currentDim();
		boolean known = false;
		for (String d : DIMS) if (d.equals(cur)) { known = true; break; }
		if (!known) cur = DIMS[0];
		dimDrop = t.add(theme.dropdown(DIMS, cur)).expandX().widget();
		dimDrop.action = this::refreshParse;
		t.row();

		t.add(theme.label("Radius (chunks)")).expandX();
		radiusEdit = t.add(theme.intEdit(mod.mapHomeRadiusChunks(), 1, mod.maxRadiusChunks(), true))
			.expandX().widget();
		t.row();

		status = t.add(theme.label("Paste coordinates above.")).expandX().widget();
		t.row();

		create = t.add(theme.button("Create home")).expandX().widget();
		create.action = this::createHome;
		t.row();

		WButton cancel = t.add(theme.button("Cancel")).expandX().widget();
		cancel.action = this::close;
		t.row();

		refreshParse();
	}

	private void refreshParse() {
		blockedBy = null;
		CoordParse.Parsed p = CoordParse.parse(coordsBox != null ? coordsBox.get() : "");
		if (p == null) {
			if (status != null) status.set("Invalid coords — need x z or x y z.");
			if (create != null) {
				create.visible = false;
			}
			return;
		}
		String defName = "home-" + (p.x() >> 4) + "-" + (p.z() >> 4);
		if (nameBox != null && (nameBox.get() == null || nameBox.get().isBlank()
			|| nameBox.get().startsWith("home-"))) {
			nameBox.set(defName);
		}

		String dim = dimDrop != null ? dimDrop.get() : currentDim();
		Optional<Home> cover = HomeWaypointFilter.coveringAt(
			mod.homeStore(), dim, p.x() + 0.5, p.z() + 0.5);
		if (cover.isPresent()) {
			blockedBy = cover.get();
			String shown = XaeroWaypoints.displayName(blockedBy.name);
			if (shown == null || shown.isBlank()) shown = blockedBy.name;
			if (status != null) {
				status.set("Inside existing home " + shown + " — pick coords outside.");
			}
			if (create != null) {
				create.visible = false;
			}
			return;
		}

		if (create != null) {
			create.visible = true;
			create.tooltip = "Create home at these coordinates.";
		}
		if (status != null) {
			status.set("→ " + p.x() + (p.y() != null ? " " + p.y() : "") + " " + p.z()
				+ "  chunk " + (p.x() >> 4) + "," + (p.z() >> 4));
		}
	}

	private void createHome() {
		CoordParse.Parsed p = CoordParse.parse(coordsBox.get());
		if (p == null) {
			HomeChat.warn("Invalid coordinates");
			return;
		}
		String dim = dimDrop.get();
		Optional<Home> cover = HomeWaypointFilter.coveringAt(
			mod.homeStore(), dim, p.x() + 0.5, p.z() + 0.5);
		if (cover.isPresent()) {
			String shown = XaeroWaypoints.displayName(cover.get().name);
			if (shown == null || shown.isBlank()) shown = cover.get().name;
			HomeChat.warn("Inside existing home " + shown);
			refreshParse();
			return;
		}
		try {
			if (!mod.ensureStore()) return;
			String name = nameBox.get();
			if (name == null || name.isBlank()) name = "home-" + (p.x() >> 4) + "-" + (p.z() >> 4);
			int radius = Math.min(Math.max(1, radiusEdit.get()), mod.maxRadiusChunks());
			Home h = mod.addHomeAt(p.x(), p.z(), dim, radius, name.trim());
			if (p.y() != null) {
				h.spawnY = p.y();
				mod.homeStore().update(h);
			}
			HomeChat.info("Home " + h.name + " @ " + p.x() + "," + p.z());
			PanelReload.reloadModuleScreen();
			close();
			PlayableConfirmScreen.promptAfterCreate(mod, h);
		} catch (Exception e) {
			HomeChat.warn(e.toString());
		}
	}

	private static String currentDim() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null && mc.world != null) {
			return mc.world.getRegistryKey().getValue().toString();
		}
		return DIMS[0];
	}
}
