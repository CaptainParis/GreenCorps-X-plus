package dev.greencorps.mixin;

import dev.greencorps.GreenCorps;
import dev.greencorps.util.CompanionSettingsBackup;
import dev.greencorps.gui.BbGrid;
import dev.greencorps.gui.GcModuleChrome;
import dev.greencorps.modules.BaseBackup;
import dev.greencorps.modules.SignSearch;
import dev.greencorps.modules.StasisChambers;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.screens.ModuleScreen;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

/**
 * GreenCorps ModuleScreen chrome: shared min width + Gateway-style label columns.
 * Own-width exceptions: BaseBackup (BbGrid), SignSearch, StasisChambers, God's Eye Companion (its own jar, matched by name).
 * Does not touch SignSearchScreen / StasisOverviewScreen / BaseBackupScreen.
 */
@Mixin(value = ModuleScreen.class, remap = false)
public abstract class MixinModuleScreenBb {
	@Shadow
	@Final
	private Module module;

	@Shadow
	private WContainer settingsContainer;

	@Inject(method = "initWidgets", at = @At("TAIL"), require = 0)
	private void greencorps$moduleChrome(CallbackInfo ci) {
		if (module == null || module.category != GreenCorps.CATEGORY) return;
		GuiTheme theme = GuiThemes.get();
		if (theme == null || settingsContainer == null) return;

		if (module instanceof BaseBackup bb) {
			List<String> titles = bb.settingTitles();
			double labelW = BbGrid.labelColW(theme, titles);
			double contentW = Math.max(BbGrid.gridW(theme), labelW + BbGrid.cellW(theme) * 2);
			walk(settingsContainer, labelW, contentW);
			return;
		}

		double labelW = GcModuleChrome.labelColW(theme, module);
		boolean ownWidth = module instanceof SignSearch || module instanceof StasisChambers
			|| CompanionSettingsBackup.COMPANION_NAME.equals(module.name);
		double contentW = ownWidth ? 0 : GcModuleChrome.settingsMinW(theme);
		walk(settingsContainer, labelW, contentW);

		if (!ownWidth) {
			WWindow window = resolveWindow();
			if (window != null && window.minWidth < contentW) {
				window.minWidth = contentW;
			}
		}
	}

	private WWindow resolveWindow() {
		try {
			Field f = WindowScreen.class.getDeclaredField("window");
			f.setAccessible(true);
			Object w = f.get(this);
			return w instanceof WWindow ww ? ww : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static void walk(WWidget w, double labelW, double contentW) {
		if (w instanceof WTable table) {
			alignTable(table, labelW);
			if (contentW > 0 && table.minWidth < contentW) table.minWidth = contentW;
		}
		if (w instanceof WContainer container) {
			for (Cell<?> cell : container.cells) {
				if (cell != null && cell.widget() != null) {
					walk(cell.widget(), labelW, contentW);
				}
			}
		}
	}

	private static void alignTable(WTable table, double labelW) {
		for (int r = 0; r < 256; r++) {
			List<Cell<?>> row;
			try {
				row = table.getRow(r);
			} catch (IndexOutOfBoundsException e) {
				break;
			}
			if (row == null || row.isEmpty()) break;
			if (row.size() >= 2) {
				Cell<?> labelCell = row.get(0);
				if (labelCell != null) labelCell.minWidth(labelW);
			}
		}
	}
}
