package dev.greencorps.mixin;

import dev.greencorps.modules.TooltipPlus;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * BundleItem overrides getTooltipData without calling Item's, so Meteor's TooltipDataEvent (fired from Item)
 * never runs for bundles and vanilla's 12-stack tooltip was always used. Hand Tooltip+ its turn here.
 */
@Mixin(BundleItem.class)
public abstract class MixinBundleItemTooltipGc {
	@Inject(method = "getTooltipData", at = @At("HEAD"), cancellable = true)
	private void greencorps$bundleTooltip(ItemStack stack, CallbackInfoReturnable<Optional<TooltipData>> cir) {
		TooltipPlus tip = TooltipPlus.get();
		TooltipData data = tip == null ? null : tip.bundleTooltip(stack);
		if (data != null) cir.setReturnValue(Optional.of(data));
	}
}
