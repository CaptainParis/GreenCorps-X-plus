package dev.greencorps.gui;

import dev.greencorps.modules.TimedCommands;
import dev.greencorps.schedule.Automation;
import dev.greencorps.schedule.DayCycle;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/** List of saved timed-command automations. */
public class TimedCommandsScreen extends WindowScreen {
	private final TimedCommands mod;
	private WLabel nowLabel;

	public TimedCommandsScreen(TimedCommands mod, Screen returnTo) {
		super(GuiThemes.get(), "Timed Commands");
		this.mod = mod;
		if (returnTo instanceof WidgetScreen) this.parent = returnTo;
		window.setExpanded(true);
	}

	@Override
	public void initWidgets() {
		WTable clock = add(theme.table()).expandX().widget();
		nowLabel = clock.add(theme.label(DayCycle.nowLine())).expandX().widget();
		WLabel leadLabel = clock.add(theme.label(mod.leadStatusLine())).expandX().widget();
		WButton refresh = clock.add(theme.button("Refresh time")).widget();
		refresh.action = () -> {
			nowLabel.set(DayCycle.nowLine());
			leadLabel.set(mod.leadStatusLine());
		};
		add(theme.horizontalSeparator()).expandX();

		if (!mod.store().list().isEmpty()) {
			WTable table = add(theme.table()).expandX().widget();
			for (Automation a : mod.store().list()) {
				WCheckbox on = table.add(theme.checkbox(a.enabled)).widget();
				on.action = () -> mod.store().setEnabled(a.id, on.checked);

				table.add(theme.label(a.summaryLine())).expandX();

				WButton edit = table.add(theme.button("Edit")).widget();
				edit.action = () -> openLater(new TimedCommandEditScreen(mod, a, this));

				WButton run = table.add(theme.button("Run")).widget();
				run.tooltip = "Send commands now (does not wait for the schedule).";
				run.action = () -> mod.runNow(a);

				WButton dup = table.add(theme.button("Dup")).widget();
				dup.action = () -> {
					mod.store().duplicate(a.id);
					reload();
				};

				WMinus remove = table.add(theme.minus()).widget();
				remove.action = () -> {
					mod.store().remove(a.id);
					reload();
				};

				table.row();
			}
			add(theme.horizontalSeparator()).expandX();
		}

		WButton create = add(theme.button("Add automation")).expandX().widget();
		create.action = () -> openLater(new TimedCommandEditScreen(mod, mod.store().newBlank(), this));
	}

	/** Defer so Meteor finishes mouse release before replacing the screen. */
	static void openLater(Screen next) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || next == null) return;
		mc.execute(() -> mc.setScreen(next));
	}
}
