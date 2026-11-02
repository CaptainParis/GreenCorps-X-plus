package dev.greencorps.mixin;

import dev.greencorps.GreenCorps;
import dev.greencorps.modules.BaseBackup;
import dev.greencorps.modules.SignSearch;
import dev.greencorps.modules.StasisChambers;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * GreenCorps category: Modules (helpers) then X+ Tools (Stasis, Time Machine, SignSearch).
 */
@Mixin(value = ModulesScreen.class, remap = false)
public abstract class MixinModulesScreenGc {
	@Inject(method = "createCategory", at = @At("HEAD"), cancellable = true, require = 1)
	private void greencorps$splitCategory(
		WContainer c,
		Category category,
		List<Module> moduleList,
		CallbackInfoReturnable<WWindow> cir
	) {
		// Name match: Category.equals is by name; != would silently miss a duplicate instance.
		if (category == null || !"GreenCorps".equals(category.name) || moduleList == null) return;

		GuiTheme theme = GuiThemes.get();
		if (theme == null) return;

		WWindow w = theme.window(category.name);
		w.id = category.name;
		w.padding = 0;
		w.spacing = 0;

		if (theme.categoryIcons()) {
			w.beforeHeaderInit = wContainer -> wContainer.add(theme.item(category.icon)).pad(2);
		}

		c.add(w);
		w.view.scrollOnlyWhenMouseOver = true;
		w.view.hasScrollBar = false;
		w.view.spacing = 0;

		List<Module> modules = new ArrayList<>();
		Module stasis = null;
		Module timeMachine = null;
		Module signSearch = null;
		for (Module module : moduleList) {
			if (module instanceof StasisChambers) stasis = module;
			else if (module instanceof BaseBackup) timeMachine = module;
			else if (module instanceof SignSearch) signSearch = module;
			else modules.add(module);
		}

		WSection modulesSec = w.add(theme.section("Modules", true)).expandX().widget();
		modulesSec.spacing = 0;
		for (Module module : modules) {
			modulesSec.add(theme.module(module)).expandX();
		}

		WSection toolsSec = w.add(theme.section("X+ Tools", true)).expandX().widget();
		toolsSec.spacing = 0;
		if (stasis != null) toolsSec.add(theme.module(stasis)).expandX();
		if (timeMachine != null) toolsSec.add(theme.module(timeMachine)).expandX();
		if (signSearch != null) toolsSec.add(theme.module(signSearch)).expandX();

		cir.setReturnValue(w);
	}
}
