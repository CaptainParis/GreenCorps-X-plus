package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.backup.SnapshotIndex;
import dev.greencorps.export.EmbeddedExport;
import dev.greencorps.modules.BaseBackup;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import net.minecraft.client.MinecraftClient;

/** Confirm embedded export; checkboxes write module Embed settings. */
public class EmbeddedExportScreen extends WindowScreen {
	private final BaseBackup mod;
	private final Home home;
	private final SnapshotIndex.Entry entry;

	public EmbeddedExportScreen(BaseBackup mod, Home home, SnapshotIndex.Entry entry) {
		super(GuiThemes.get(), "Export Embedded");
		this.mod = mod;
		this.home = home;
		this.entry = entry;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null && mc.currentScreen != null) this.parent = mc.currentScreen;
	}

	@Override
	public void initWidgets() {
		WTable t = add(theme.table()).expandX().widget();
		BbGrid.applySpacing(t, theme);

		t.add(theme.label("Seed-blend a ring around the captured island.")).expandX();
		t.row();
		t.add(theme.label("Stem: bb-embed-<name>-<time> (not pruned with playable).")).expandX();
		t.row();
		if ("minecraft:the_nether".equals(home.dimension)) {
			t.add(theme.label("Nether homes are not supported.")).expandX();
			t.row();
		} else if ("minecraft:the_end".equals(home.dimension)) {
			t.add(theme.label("The End: end_stone ring (opens as overworld SP).")).expandX();
			t.row();
		}

		WCheckbox shift = t.add(theme.checkbox(mod.shiftCoordsTo0())).widget();
		t.add(theme.label("Shift Coords To 0")).expandX();
		shift.action = () -> mod.setShiftCoordsTo0(shift.checked);
		t.row();

		t.add(theme.label("Blend radius: " + mod.blendRadiusChunks() + " chunks")).expandX();
		t.row();
		t.add(theme.label("Seed search budget: " + mod.seedSearchBudgetMs() + " ms")).expandX();
		t.row();
		t.add(theme.label("(Change blend/budget in Time Machine → Embed settings.)")).expandX();
		t.row();

		WButton go = t.add(theme.button("Queue Export")).expandX().widget();
		go.action = () -> {
			if ("minecraft:the_nether".equals(home.dimension)) return;
			EmbeddedExport.runAsync(mod.engine(), home, entry,
				mod.shiftCoordsTo0(), mod.blendRadiusChunks(), mod.seedSearchBudgetMs());
			close();
		};
		WButton cancel = t.add(theme.button("Cancel")).expandX().widget();
		cancel.action = this::close;
		t.row();
	}
}
