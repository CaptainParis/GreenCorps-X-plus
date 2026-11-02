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
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;

public class HomeEditScreen extends WindowScreen {
	private final BaseBackup mod;
	private final Home home;

	public HomeEditScreen(BaseBackup mod, Home home) {
		super(GuiThemes.get(), (home.isSnapshot() ? "Edit snapshot " : "Edit ") + home.name);
		this.mod = mod;
		this.home = home;
		this.parent = new BaseBackupScreen(mod, home.id);
	}

	@Override
	public void initWidgets() {
		WTable t = add(theme.table()).expandX().widget();

		t.add(theme.label("Name"));
		WTextBox name = t.add(theme.textBox(home.name)).expandX().widget();
		t.row();

		t.add(theme.label("Radius (chunks)"));
		WIntEdit radius = t.add(theme.intEdit(home.radiusChunks, 1, mod.maxRadiusChunks(), true)).expandX().widget();
		radius.tooltip = home.isSnapshot()
			? "Chebyshev radius in chunks. Re-arm a finished snapshot to capture the new size."
			: "Chebyshev radius in chunks from center.";
		t.row();

		t.add(theme.label("Armed"));
		WCheckbox armed = t.add(theme.checkbox(home.armed)).widget();
		t.row();

		t.add(theme.label("Auto-commit"));
		WCheckbox auto = t.add(theme.checkbox(home.autoCommit)).widget();
		if (home.isSnapshot()) {
			auto.tooltip = "Snapshots capture once when the area is loaded, then disarm. Auto-commit is for homes.";
		}
		t.row();

		t.add(theme.label("Playable save"));
		WCheckbox playable = t.add(theme.checkbox(home.keepPlayableSave)).widget();
		playable.tooltip = "Maintain one saves/<name> Singleplayer world (latest overwrite). Needs auto-playable-latest.";
		t.row();

		WButton save = t.add(theme.button("Save")).expandX().widget();
		save.action = () -> {
			home.name = name.get();
			home.radiusChunks = Math.min(radius.get(), mod.maxRadiusChunks());
			home.armed = armed.checked;
			home.autoCommit = auto.checked;
			home.keepPlayableSave = playable.checked;
			try {
				mod.homeStore().update(home);
				HomeChat.info("Saved " + home.name);
				close();
			} catch (Exception e) {
				HomeChat.warn(e.toString());
			}
		};
		t.row();

		WButton remove = t.add(theme.button(home.isSnapshot() ? "Remove snapshot" : "Remove home")).expandX().widget();
		remove.tooltip = home.isSnapshot()
			? "Removes the snapshot definition. Captured data remains on disk until GC. Asks for confirmation."
			: "Removes the home definition (snapshots remain until GC). Asks for confirmation.";
		remove.action = () -> HomeRemoveConfirm.promptFromEdit(mod, home);
	}
}
