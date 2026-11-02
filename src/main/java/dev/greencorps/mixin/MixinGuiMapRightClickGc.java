package dev.greencorps.mixin;

import dev.greencorps.gui.map.HomeMapMenu;
import dev.greencorps.gui.map.StasisMapMenu;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/**
 * Injects Base Backup / Stasis Monitor into Xaero's native world-map right-click menu
 * (same hook XaeroPlus / godseye use). Gated by GcMixinPlugin.
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinGuiMapRightClickGc {
	@Shadow
	private int rightClickX;
	@Shadow
	private int rightClickZ;
	@Shadow
	private RegistryKey<World> rightClickDim;

	@Inject(method = "getRightClickOptions", at = @At("RETURN"))
	private void greencorps$appendHomeOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
		HomeMapMenu.append((IRightClickableElement) this, cir.getReturnValue(),
			rightClickX, rightClickZ, rightClickDim);
		StasisMapMenu.append((IRightClickableElement) this, cir.getReturnValue(),
			rightClickX, rightClickZ, rightClickDim);
	}
}
