package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.modules.BaseBackup;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;

/**
 * Name prompt for snapshots not linked to a Xaero waypoint (grid / map).
 */
public class SnapshotNameScreen extends WindowScreen {
	private final BaseBackup mod;
	private final Integer blockX;
	private final Integer blockZ;
	private final String dimension;
	private WTextBox nameBox;
	private WIntEdit radiusEdit;

	/** Snapshot at the player's position. */
	public SnapshotNameScreen(BaseBackup mod) {
		super(GuiThemes.get(), "Snapshot (Player Position)");
		this.mod = mod;
		this.blockX = null;
		this.blockZ = null;
		this.dimension = null;
	}

	/** Snapshot centered on map / remote coords. */
	public SnapshotNameScreen(BaseBackup mod, int blockX, int blockZ, String dimension) {
		super(GuiThemes.get(), "Snapshot");
		this.mod = mod;
		this.blockX = blockX;
		this.blockZ = blockZ;
		this.dimension = dimension;
	}

	@Override
	public void initWidgets() {
		int cx;
		int cz;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (blockX != null) {
			cx = blockX >> 4;
			cz = blockZ >> 4;
		} else if (mc != null && mc.player != null) {
			cx = mc.player.getBlockX() >> 4;
			cz = mc.player.getBlockZ() >> 4;
		} else {
			cx = 0;
			cz = 0;
		}

		WTable t = add(theme.table()).expandX().widget();
		t.add(theme.label("Snapshot name")).expandX();
		t.row();
		nameBox = t.add(theme.textBox("snap-" + cx + "-" + cz)).expandX().widget();
		t.row();

		t.add(theme.label("Radius (chunks)")).expandX();
		t.row();
		radiusEdit = t.add(theme.intEdit(mod.snapshotRadiusChunks(), 1, mod.maxRadiusChunks(), true))
			.expandX().widget();
		radiusEdit.tooltip = "Chebyshev radius in chunks. Starts from the snapshot-radius setting.";
		t.row();

		WButton create = t.add(theme.button("Create snapshot")).expandX().widget();
		create.action = this::create;
		t.row();

		WButton cancel = t.add(theme.button("Cancel")).expandX().widget();
		cancel.action = this::close;
	}

	private void create() {
		String name = nameBox != null ? nameBox.get() : null;
		if (name == null || name.isBlank()) {
			HomeChat.warn("Enter a snapshot name");
			return;
		}
		name = name.trim();
		int radius = radiusEdit != null ? radiusEdit.get() : mod.snapshotRadiusChunks();
		radius = Math.min(Math.max(1, radius), mod.maxRadiusChunks());
		try {
			if (!mod.ensureStore()) return;
			Home h;
			if (blockX != null) {
				String dim = dimension != null ? dimension : "minecraft:overworld";
				h = mod.addSnapshotAt(blockX, blockZ, dim, name, radius);
			} else {
				h = mod.addSnapshotHere(name, radius);
			}
			PanelReload.reloadModuleScreen();
			close();
			PlayableConfirmScreen.promptAfterCreate(mod, h);
		} catch (Exception e) {
			HomeChat.warn(e.toString());
		}
	}
}
