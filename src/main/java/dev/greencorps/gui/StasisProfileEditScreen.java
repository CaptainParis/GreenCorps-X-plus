package dev.greencorps.gui;

import dev.greencorps.chat.StasisChat;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisPlayerProfile;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;

import java.util.ArrayList;

/** Add / edit a stasis player profile (quota default + notify). */
public final class StasisProfileEditScreen extends WindowScreen {
	private final StasisChambers mod;
	private final StasisPlayerProfile existing;
	private WTextBox labelBox;
	private WTextBox ignsBox;
	private WTextBox uuidBox;
	private WIntEdit pearlsEdit;
	private WCheckbox notifyBox;

	public StasisProfileEditScreen(StasisChambers mod, StasisPlayerProfile existing) {
		super(GuiThemes.get(), existing == null ? "Add Player Profile" : "Edit Player Profile");
		this.mod = mod;
		this.existing = existing;
	}

	@Override
	public void initWidgets() {
		WTable t = add(theme.table()).expandX().widget();
		t.add(theme.label("Label")).expandX();
		labelBox = t.add(theme.textBox(existing != null ? existing.displayLabel() : "Alt")).expandX().widget();
		t.row();
		t.add(theme.label("IGNs (comma)")).expandX();
		ignsBox = t.add(theme.textBox(existing != null ? existing.ignsJoined() : "")).expandX().widget();
		t.row();
		t.add(theme.label("UUID (optional)")).expandX();
		String uuid = "";
		if (existing != null && existing.uuids != null && !existing.uuids.isEmpty()) {
			uuid = existing.uuids.get(0);
		}
		uuidBox = t.add(theme.textBox(uuid == null ? "" : uuid)).expandX().widget();
		t.row();
		t.add(theme.label("Required (default)")).expandX();
		int def = existing != null ? existing.defaultPearls : mod.defaultPearls();
		pearlsEdit = t.add(theme.intEdit(Math.min(8, Math.max(1, def)), 1, 8, true)).expandX().widget();
		t.row();
		t.add(theme.label("Notify when short")).expandX();
		notifyBox = t.add(theme.checkbox(existing == null || existing.notify)).widget();
		t.row();

		WButton save = t.add(theme.button(existing == null ? "Create" : "Save")).expandX().widget();
		save.action = this::save;
		if (existing != null) {
			t.row();
			WButton del = t.add(theme.button("Delete")).expandX().widget();
			del.action = () -> {
				try {
					mod.store().removeProfile(existing.id);
					PanelReload.reloadModuleScreen();
					close();
				} catch (Exception e) {
					StasisChat.warn("Delete profile failed: " + e.getClass().getSimpleName());
				}
			};
		}
	}

	private void save() {
		try {
			if (mod.store() == null) return;
			StasisPlayerProfile p = existing != null ? existing : new StasisPlayerProfile();
			if (p.id == null || p.id.isBlank()) p.ensureId();
			p.label = labelBox != null ? labelBox.get() : "Account";
			p.setIgnsFromCsv(ignsBox != null ? ignsBox.get() : "");
			p.uuids = new ArrayList<>();
			if (uuidBox != null && uuidBox.get() != null && !uuidBox.get().isBlank()) {
				p.uuids.add(uuidBox.get().trim());
			}
			p.defaultPearls = pearlsEdit != null ? Math.max(0, pearlsEdit.get()) : 0;
			p.notify = notifyBox == null || notifyBox.checked;
			if (existing == null) mod.store().addProfile(p);
			else mod.store().updateProfile(p);
			PanelReload.reloadModuleScreen();
			close();
		} catch (Exception e) {
			StasisChat.warn("Save profile failed: " + e.getClass().getSimpleName());
		}
	}
}
