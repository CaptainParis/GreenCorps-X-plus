package dev.greencorps.gui;

import dev.greencorps.chat.StasisChat;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisHome;
import dev.greencorps.stasis.StasisPlayerProfile;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.util.HashMap;
import java.util.Map;

/** Edit home name/radius and per-account required pearls. */
public final class StasisHomeEditScreen extends WindowScreen {
	private final StasisChambers mod;
	private final StasisHome home;
	private WTextBox nameBox;
	private WIntEdit radiusEdit;
	private final Map<String, WIntEdit> quotaEdits = new HashMap<>();

	public StasisHomeEditScreen(StasisChambers mod, StasisHome home) {
		super(GuiThemes.get(), "Edit Stasis Home");
		this.mod = mod;
		this.home = home;
	}

	@Override
	public void initWidgets() {
		WTable t = add(theme.table()).expandX().widget();
		t.add(theme.label("Name")).expandX();
		nameBox = t.add(theme.textBox(home.name == null ? "" : home.name)).expandX().widget();
		t.row();
		t.add(theme.label("Radius (chunks)")).expandX();
		radiusEdit = t.add(theme.intEdit(Math.min(4, Math.max(1, home.radiusChunks)), 1, 4, true)).expandX().widget();
		t.row();
		t.add(theme.label("Required pearls per account")).expandX();
		t.row();
		if (mod.store() != null) {
			for (StasisPlayerProfile p : mod.store().profiles()) {
				t.add(theme.label(p.displayLabel() + (p.notify ? "" : " (silent)"))).expandX();
				int cur = home.requiredFor(p.id);
				WIntEdit edit = t.add(theme.intEdit(cur, 0, 64, true)).expandX().widget();
				quotaEdits.put(p.id, edit);
				t.row();
			}
		}
		WButton save = t.add(theme.button("Save")).expandX().widget();
		save.action = () -> {
			try {
				home.name = nameBox != null ? nameBox.get().trim() : home.name;
				if (radiusEdit != null) home.radiusChunks = Math.min(4, Math.max(1, radiusEdit.get()));
				for (Map.Entry<String, WIntEdit> e : quotaEdits.entrySet()) {
					home.setRequiredFor(e.getKey(), e.getValue() != null ? e.getValue().get() : 0);
				}
				mod.store().updateHome(home);
				PanelReload.reloadModuleScreen();
				close();
			} catch (Exception ex) {
				StasisChat.warn("Save home failed: " + ex.getClass().getSimpleName());
			}
		};
	}
}
