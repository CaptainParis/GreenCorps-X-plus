package dev.greencorps.mixin;

import dev.greencorps.modules.SafeShulker;
import dev.greencorps.gui.SafeShulkerScreen;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
* Hooks middle-click / the open bind on any inventory screen and hands the hovered slot to SafeShulker.
*/
@Mixin(HandledScreen.class)
public abstract class MixinHandledScreenSafeShulkerGc {
	@Shadow @Nullable protected Slot focusedSlot;

	@Unique
	private boolean greencorps$skip() {
		Object self = this;
		return self instanceof SafeShulkerScreen || self instanceof CreativeInventoryScreen;
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void greencorps$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (greencorps$skip()) return;
		SafeShulker module = Modules.get().get(SafeShulker.class);
		if (module != null && module.tryOpenFromMouse(focusedSlot, button)) cir.setReturnValue(true);
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void greencorps$onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (greencorps$skip()) return;
		SafeShulker module = Modules.get().get(SafeShulker.class);
		if (module != null && module.tryOpenFromKey(focusedSlot, keyCode, modifiers)) cir.setReturnValue(true);
	}
}
