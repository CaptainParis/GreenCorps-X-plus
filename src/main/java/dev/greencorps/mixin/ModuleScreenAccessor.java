package dev.greencorps.mixin;

import meteordevelopment.meteorclient.gui.screens.ModuleScreen;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ModuleScreen.class, remap = false)
public interface ModuleScreenAccessor {
	@Accessor("module")
	Module greencorps$module();
}
