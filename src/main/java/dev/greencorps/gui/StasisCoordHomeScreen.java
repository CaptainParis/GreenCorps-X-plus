package dev.greencorps.gui;

import dev.greencorps.chat.StasisChat;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisHome;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;

/** Paste/enter coords → create a stasis home. */
public class StasisCoordHomeScreen extends WindowScreen {
	private static final String[] DIMS = {
		"minecraft:overworld",
		"minecraft:the_nether",
		"minecraft:the_end"
	};

	private final StasisChambers mod;
	private WTextBox coordsBox;
	private WTextBox nameBox;
	private WDropdown<String> dimDrop;
	private WIntEdit radiusEdit;
	private WIntEdit pearlsEdit;
	private WLabel status;
	private WButton create;

	public StasisCoordHomeScreen(StasisChambers mod) {
		super(GuiThemes.get(), "Add Stasis Home (Coordinates)");
		this.mod = mod;
	}

	@Override
	public void initWidgets() {
		WTable t = add(theme.table()).expandX().widget();

		t.add(theme.label("Coordinates")).expandX();
		t.row();
		coordsBox = t.add(theme.textBox("")).expandX().widget();
		coordsBox.tooltip = "x z  |  x y z  |  commas OK";
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
		t.row();

		t.add(theme.label("Radius (chunks)")).expandX();
		radiusEdit = t.add(theme.intEdit(mod.homeRadiusChunks(), 1, 4, true)).expandX().widget();
		t.row();

		t.add(theme.label("Pearls required")).expandX();
		pearlsEdit = t.add(theme.intEdit(mod.defaultPearls(), 1, 8, true)).expandX().widget();
		t.row();

		status = t.add(theme.label("Paste coordinates above.")).expandX().widget();
		t.row();

		create = t.add(theme.button("Create home")).expandX().widget();
		create.action = this::createHome;
		create.visible = false;
		t.row();

		WButton cancel = t.add(theme.button("Cancel")).expandX().widget();
		cancel.action = this::close;
	}

	private void refreshParse() {
		CoordParse.Parsed p = CoordParse.parse(coordsBox != null ? coordsBox.get() : "");
		if (p == null) {
			if (status != null) status.set("Invalid coords — need x z or x y z.");
			if (create != null) create.visible = false;
			return;
		}
		String defName = "stasis-" + (p.x() >> 4) + "-" + (p.z() >> 4);
		if (nameBox != null && (nameBox.get() == null || nameBox.get().isBlank()
			|| nameBox.get().startsWith("stasis-"))) {
			nameBox.set(defName);
		}
		if (status != null) status.set("Ready: " + p.x() + " " + (p.y() != null ? p.y() + " " : "") + p.z());
		if (create != null) create.visible = true;
	}

	private void createHome() {
		CoordParse.Parsed p = CoordParse.parse(coordsBox.get());
		if (p == null) return;
		try {
			int y = p.y() != null ? p.y() : 64;
			MinecraftClient mc = MinecraftClient.getInstance();
			if (p.y() == null && mc != null && mc.player != null) y = mc.player.getBlockY();
			StasisHome h = mod.addHomeAt(
				p.x(), y, p.z(),
				dimDrop.get(),
				nameBox.get(),
				radiusEdit.get(),
				pearlsEdit.get());
			StasisChat.info("Stasis home " + h.name);
			close();
		} catch (Exception e) {
			StasisChat.warn("Create failed: " + e.getClass().getSimpleName());
		}
	}

	private static String currentDim() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null && mc.world != null) return mc.world.getRegistryKey().getValue().toString();
		return DIMS[0];
	}
}
