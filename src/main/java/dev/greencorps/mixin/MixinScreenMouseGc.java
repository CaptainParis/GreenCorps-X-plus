package dev.greencorps.mixin;

import dev.greencorps.gui.GcScreenWing;
import dev.greencorps.gui.GcWings;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla 1.21.4 {@link Screen} does not declare {@code mouseClicked}.
 * {@code require = 0} skips this inject when the target is missing so startup
 * does not fail. Container clicks stay on {@link HandledScreenMixin}.
 */
@Mixin(Screen.class)
public abstract class MixinScreenMouseGc {
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
	private void greencorps$screenMouseClicked(double mouseX, double mouseY, int button,
											   CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof HandledScreen<?> hs)) return;
		GcScreenWing wing = GcWings.attached(hs);
		if (wing != null && wing.mouseClicked(mouseX, mouseY, button)) {
			cir.setReturnValue(true);
		}
	}
}
