package dev.greencorps.gui;

import dev.greencorps.chat.StasisChat;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisHome;
import dev.greencorps.stasis.StasisPearl;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

/** Ask whether a newly installed pearl location should become a stasis home. */
public class StasisHomePromptScreen extends WindowScreen {
	private final StasisChambers mod;
	private final StasisPearl pearl;
	private final String suggestedName;
	private final int defaultPearls;
	private final int defaultRadius;
	private WTextBox nameBox;
	private WIntEdit pearlsEdit;
	private WIntEdit radiusEdit;

	public StasisHomePromptScreen(StasisChambers mod, StasisPearl pearl, String suggestedName,
								  int defaultPearls, int defaultRadius) {
		super(GuiThemes.get(), "Add Stasis Home?");
		this.mod = mod;
		this.pearl = pearl;
		this.suggestedName = suggestedName;
		this.defaultPearls = defaultPearls;
		this.defaultRadius = defaultRadius;
	}

	@Override
	public void initWidgets() {
		WTable t = add(theme.table()).expandX().widget();
		t.add(theme.label("Pearl @ " + pearl.x + " " + pearl.y + " " + pearl.z)).expandX();
		t.row();
		t.add(theme.label("Add this location as a stasis home?")).expandX();
		t.row();

		t.add(theme.label("Name")).expandX();
		t.row();
		nameBox = t.add(theme.textBox(suggestedName == null ? "" : suggestedName)).expandX().widget();
		t.row();

		t.add(theme.label("Pearls required")).expandX();
		pearlsEdit = t.add(theme.intEdit(Math.min(8, Math.max(1, defaultPearls)), 1, 8, true)).expandX().widget();
		t.row();

		t.add(theme.label("Radius (chunks)")).expandX();
		radiusEdit = t.add(theme.intEdit(Math.min(4, Math.max(1, defaultRadius)), 1, 4, true)).expandX().widget();
		t.row();

		WButton add = t.add(theme.button("Add Home")).expandX().widget();
		add.action = this::addHome;
		t.row();

		WButton skip = t.add(theme.button("Not Now")).expandX().widget();
		skip.action = this::close;
	}

	private void addHome() {
		try {
			String name = nameBox != null ? nameBox.get() : suggestedName;
			int pearls = pearlsEdit != null ? pearlsEdit.get() : defaultPearls;
			int radius = radiusEdit != null ? radiusEdit.get() : defaultRadius;
			StasisHome h = mod.addHomeAt(pearl.x, pearl.y, pearl.z, pearl.dimension, name, radius, pearls);
			pearl.homeId = h.id;
			if (mod.store() != null) mod.store().updatePearl(pearl);
			StasisChat.info("Stasis home " + h.name + " (" + h.pearlsRequired + " pearls)");
			close();
		} catch (Exception e) {
			StasisChat.warn("Add home failed: " + e.getClass().getSimpleName());
		}
	}
}
