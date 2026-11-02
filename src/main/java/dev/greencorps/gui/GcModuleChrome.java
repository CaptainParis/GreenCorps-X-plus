package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Shared GreenCorps ModuleScreen chrome: one settings min-width + fixed label column
 * (Gateway-ESP-style left column, widgets start on the same x).
 */
public final class GcModuleChrome {
	/** Matches a comfortable Tooltip+ settings panel (~420 scaled px). */
	public static final double SETTINGS_MIN_W_UNSCALED = 420;

	private GcModuleChrome() {
	}

	public static double settingsMinW(GuiTheme theme) {
		return theme.scale(SETTINGS_MIN_W_UNSCALED);
	}

	public static double labelColW(GuiTheme theme, Module module) {
		double max = theme.scale(120);
		if (module == null || module.settings == null) return max;
		for (SettingGroup g : module.settings.groups) {
			for (Setting<?> s : g) {
				if (s.title == null || s.title.isBlank()) continue;
				max = Math.max(max, theme.textWidth(s.title) + theme.pad() * 2);
			}
		}
		return max;
	}
}
