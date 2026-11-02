package dev.greencorps.gui;

import dev.greencorps.modules.TimedCommands;
import dev.greencorps.schedule.Automation;
import dev.greencorps.schedule.DayCycle;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Create / edit one timed-command automation. */
public class TimedCommandEditScreen extends WindowScreen {
	private final TimedCommands mod;
	private final Automation draft;
	private final boolean isNew;
	private final Automation.Trigger originalTrigger;
	private final long originalTimeValue;
	private final int originalOffset;
	private final List<String> commandLines = new ArrayList<>();

	private WTextBox nameBox;
	private WDropdown<Automation.Trigger> triggerDrop;
	private WCheckbox enabledBox;
	private WTextBox timeBox;
	private WIntEdit offsetEdit;
	private WLabel nowLabel;
	private WLabel previewLabel;
	private WTable commandsTable;

	public TimedCommandEditScreen(TimedCommands mod, Automation existing, Screen parent) {
		super(GuiThemes.get(), existing == null || isBlankNew(existing) ? "New automation" : "Edit " + existing.name);
		this.mod = mod;
		if (parent != null) this.parent = parent;
		this.draft = existing != null ? cloneOf(existing) : blank();
		this.isNew = existing == null || mod.store().find(this.draft.id).isEmpty();
		this.originalTrigger = draft.trigger;
		this.originalTimeValue = draft.timeValue;
		this.originalOffset = draft.offsetTicks;
		if (draft.commands != null) commandLines.addAll(draft.commands);
		if (commandLines.isEmpty()) commandLines.add("");
		window.setExpanded(true);
	}

	private static boolean isBlankNew(Automation a) {
		return a.name != null && a.name.startsWith("New automation");
	}

	private static Automation blank() {
		Automation a = new Automation();
		a.name = "New automation";
		a.commands.add("/say hello");
		return a;
	}

	private static Automation cloneOf(Automation src) {
		Automation a = new Automation();
		a.id = src.id;
		a.name = src.name;
		a.enabled = src.enabled;
		a.trigger = src.trigger;
		a.timeValue = src.timeValue;
		a.offsetTicks = src.offsetTicks;
		a.clock = src.clock;
		a.commands = new ArrayList<>(src.commands == null ? List.of() : src.commands);
		a.lastFiredDay = src.lastFiredDay;
		a.lastFiredResolvedTick = src.lastFiredResolvedTick;
		a.lastFiredWorldAge = src.lastFiredWorldAge;
		return a;
	}

	@Override
	public void initWidgets() {
		WTable clock = add(theme.table()).expandX().widget();
		nowLabel = clock.add(theme.label(DayCycle.nowLine())).expandX().widget();
		WLabel leadLabel = clock.add(theme.label(mod.leadStatusLine())).expandX().widget();
		WButton refreshTime = clock.add(theme.button("Refresh time")).widget();
		refreshTime.action = () -> {
			nowLabel.set(DayCycle.nowLine());
			leadLabel.set(mod.leadStatusLine());
		};
		add(theme.horizontalSeparator()).expandX();

		WTable t = add(theme.table()).expandX().widget();

		t.add(theme.label("Name"));
		nameBox = t.add(theme.textBox(draft.name == null ? "" : draft.name)).expandX().widget();
		t.row();

		t.add(theme.label("Enabled"));
		enabledBox = t.add(theme.checkbox(draft.enabled)).widget();
		t.row();

		t.add(theme.label("Trigger"));
		triggerDrop = t.add(theme.dropdown(draft.trigger)).expandX().widget();
		triggerDrop.action = () -> {
			stashBeforeReload();
			draft.trigger = triggerDrop.get();
			reload();
		};
		t.row();

		Automation.Trigger trig = triggerDrop.get();
		switch (trig) {
			case TIME_OF_DAY -> {
				t.add(theme.label("Time (HH:MM or tick)"));
				String seed = draft.clock != null && !draft.clock.isBlank()
					? draft.clock
					: DayCycle.formatClock(draft.resolvedDayTick());
				timeBox = t.add(theme.textBox(seed)).expandX().widget();
				timeBox.action = this::refreshPreview;
				t.row();
			}
			case DAY_TICK -> {
				t.add(theme.label("Day tick (0–23999)"));
				timeBox = t.add(theme.textBox(DayCycle.formatDayTick(draft.resolvedDayTick()))).expandX().widget();
				timeBox.tooltip = "Exact day-cycle tick, e.g. 23460 or 23,460. Fires once per Minecraft day.";
				timeBox.action = this::refreshPreview;
				t.row();
			}
			case SUNRISE, SUNSET -> {
				t.add(theme.label("Offset (ticks)"));
				offsetEdit = t.add(theme.intEdit(draft.offsetTicks, -12_000, 12_000, true)).expandX().widget();
				offsetEdit.tooltip = "Default 0. Negative = before anchor. 20 ticks ≈ 1 real second.";
				offsetEdit.action = this::refreshPreview;
				t.row();
				t.add(theme.label("Seconds ≈"));
				t.add(theme.label(String.format("%.1f", draft.offsetTicks / 20.0))).expandX();
				t.row();
			}
			case WORLD_TICK -> {
				t.add(theme.label("World age"));
				timeBox = t.add(theme.textBox(Long.toString(draft.timeValue))).expandX().widget();
				timeBox.tooltip = "Fires once when world.getTime() reaches this age. Commas OK.";
				t.row();
			}
			case EVERY_N_TICKS -> {
				t.add(theme.label("Every N ticks"));
				int n = Math.max(1, draft.offsetTicks <= 0 ? 20 : draft.offsetTicks);
				offsetEdit = t.add(theme.intEdit(n, 1, 24000, true)).expandX().widget();
				offsetEdit.tooltip = "Repeats on world age; lag catches up by one fire.";
				t.row();
			}
		}

		previewLabel = t.add(theme.label(previewText())).expandX().widget();
		t.row();

		add(theme.horizontalSeparator()).expandX();
		add(theme.label("Commands (one per line; / = server, Meteor prefix = client)")).expandX();

		commandsTable = add(theme.table()).expandX().widget();
		rebuildCommandRows();

		WButton addLine = add(theme.button("Add command line")).expandX().widget();
		addLine.action = () -> {
			syncCommandLinesFromWidgets();
			commandLines.add("");
			rebuildCommandRows();
		};

		add(theme.horizontalSeparator()).expandX();

		WTable actions = add(theme.table()).expandX().widget();
		WButton save = actions.add(theme.button("Save")).expandX().widget();
		save.action = this::save;
		WButton run = actions.add(theme.button("Run now")).expandX().widget();
		run.action = () -> {
			if (!applyFields(false)) return;
			mod.runNow(draft);
		};
		WButton cancel = actions.add(theme.button("Cancel")).expandX().widget();
		cancel.action = this::close;
	}

	private void rebuildCommandRows() {
		commandsTable.clear();
		for (int i = 0; i < commandLines.size(); i++) {
			final int idx = i;
			WTextBox box = commandsTable.add(theme.textBox(commandLines.get(i))).expandX().widget();
			box.actionOnUnfocused = () -> commandLines.set(idx, box.get());
			WMinus rem = commandsTable.add(theme.minus()).widget();
			rem.action = () -> {
				syncCommandLinesFromWidgets();
				if (commandLines.size() <= 1) {
					commandLines.set(0, "");
				} else {
					commandLines.remove(idx);
				}
				rebuildCommandRows();
			};
			commandsTable.row();
		}
	}

	private void stashBeforeReload() {
		if (nameBox != null) draft.name = nameBox.get();
		if (enabledBox != null) draft.enabled = enabledBox.checked;
		pullTriggerFieldsIntoDraft(false);
		syncCommandLinesFromWidgets();
	}

	private void syncCommandLinesFromWidgets() {
		if (commandsTable == null) return;
		List<String> lines = new ArrayList<>();
		for (var cell : commandsTable.cells) {
			if (cell.widget() instanceof WTextBox box) {
				lines.add(box.get() == null ? "" : box.get());
			}
		}
		if (!lines.isEmpty()) {
			commandLines.clear();
			commandLines.addAll(lines);
		}
	}

	private void refreshPreview() {
		if (previewLabel == null) return;
		pullTriggerFieldsIntoDraft(false);
		previewLabel.set(previewText());
	}

	private String previewText() {
		return switch (draft.trigger) {
			case TIME_OF_DAY -> "Fires at " + DayCycle.previewLabel(draft.resolvedDayTick()) + " each Minecraft day";
			case DAY_TICK -> "Fires at tick " + DayCycle.formatDayTick(draft.resolvedDayTick())
				+ " (" + DayCycle.formatClock(draft.resolvedDayTick()) + ") each Minecraft day";
			case SUNRISE -> "Sunrise " + signed(draft.offsetTicks) + " → " + DayCycle.previewLabel(draft.resolvedDayTick());
			case SUNSET -> "Sunset " + signed(draft.offsetTicks) + " → " + DayCycle.previewLabel(draft.resolvedDayTick());
			case WORLD_TICK -> "Once at world age " + draft.timeValue;
			case EVERY_N_TICKS -> "Every " + Math.max(1, draft.offsetTicks) + " world ticks";
		};
	}

	private static String signed(int v) {
		return v >= 0 ? "+" + v : Integer.toString(v);
	}

	private void pullTriggerFieldsIntoDraft(boolean strict) {
		draft.trigger = triggerDrop != null ? triggerDrop.get() : draft.trigger;
		if (offsetEdit != null) draft.offsetTicks = offsetEdit.get();
		if (timeBox == null) return;
		String raw = timeBox.get() == null ? "" : timeBox.get().trim();
		switch (draft.trigger) {
			case TIME_OF_DAY -> {
				Optional<Integer> parsed = DayCycle.parseTime(raw);
				if (parsed.isPresent()) {
					draft.timeValue = parsed.get();
					draft.clock = DayCycle.formatClock(parsed.get());
				} else if (strict) {
					draft.timeValue = -1;
				}
			}
			case DAY_TICK -> {
				Optional<Integer> parsed = DayCycle.parseDayTick(raw);
				if (parsed.isPresent()) {
					draft.timeValue = parsed.get();
					draft.clock = DayCycle.formatClock(parsed.get());
				} else if (strict) {
					draft.timeValue = -1;
				}
			}
			case WORLD_TICK -> {
				Optional<Long> parsed = DayCycle.parseLongTick(raw);
				if (parsed.isPresent()) {
					draft.timeValue = parsed.get();
				} else if (strict) {
					draft.timeValue = -1;
				}
			}
			default -> {}
		}
	}

	private boolean applyFields(boolean persist) {
		draft.name = nameBox.get() == null || nameBox.get().isBlank() ? "Untitled" : nameBox.get().trim();
		draft.enabled = enabledBox.checked;
		draft.trigger = triggerDrop.get();
		pullTriggerFieldsIntoDraft(true);

		if (draft.trigger == Automation.Trigger.TIME_OF_DAY && draft.timeValue < 0) {
			mod.warning("Invalid time — use HH:MM or a tick 0–23999");
			return false;
		}
		if (draft.trigger == Automation.Trigger.DAY_TICK && draft.timeValue < 0) {
			mod.warning("Invalid day tick — use 0–23999 (commas OK, e.g. 23,460)");
			return false;
		}
		if (draft.trigger == Automation.Trigger.WORLD_TICK && draft.timeValue < 0) {
			mod.warning("Invalid world age");
			return false;
		}
		if (draft.trigger == Automation.Trigger.EVERY_N_TICKS) {
			draft.offsetTicks = Math.max(1, offsetEdit != null ? offsetEdit.get() : draft.offsetTicks);
		}
		if (draft.trigger == Automation.Trigger.SUNRISE || draft.trigger == Automation.Trigger.SUNSET) {
			draft.offsetTicks = offsetEdit != null ? offsetEdit.get() : draft.offsetTicks;
		}

		syncCommandLinesFromWidgets();
		List<String> cmds = new ArrayList<>();
		for (String line : commandLines) {
			if (line != null && !line.isBlank()) cmds.add(line.trim());
		}
		if (cmds.isEmpty()) {
			mod.warning("Add at least one command line");
			return false;
		}
		draft.commands = cmds;

		boolean triggerChanged = draft.trigger != originalTrigger
			|| draft.timeValue != originalTimeValue
			|| draft.offsetTicks != originalOffset;
		if (triggerChanged) draft.clearFireMarks();

		if (persist) {
			if (isNew) mod.store().add(draft);
			else mod.store().update(draft);
		}
		return true;
	}

	private void save() {
		if (!applyFields(true)) return;
		if (parent instanceof TimedCommandsScreen list) list.reload();
		close();
	}
}
