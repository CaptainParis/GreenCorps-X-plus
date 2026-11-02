package dev.greencorps.mixin;

import dev.greencorps.util.ClickBudget;
import dev.greencorps.util.InventoryClickGuard;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts every inventory click packet for {@link ClickBudget}; drops clicks {@link InventoryClickGuard} refuses. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class MixinClientPlayerInteractionManagerGc {
	@Inject(method = "clickSlot", at = @At("HEAD"), cancellable = true)
	private void greencorps$countClick(int syncId, int slotId, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
		if (InventoryClickGuard.blocks(actionType, player.currentScreenHandler.getCursorStack())) {
			ci.cancel();
			return;
		}
		ClickBudget.record();
	}
}
