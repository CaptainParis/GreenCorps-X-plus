package dev.greencorps.mixin;

import dev.greencorps.modules.MapSlotPreview;
import dev.greencorps.modules.TooltipPlus;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Early Map Slot Preview: cancel parchment and draw our art first, at the item sprite layer.
 * Companion {@link MixinDrawContextGcPreviewTail} re-blits at RETURN over Rusher.
 */
@Mixin(value = DrawContext.class, priority = 2000)
public abstract class MixinDrawContextGc {
	@Shadow
	public abstract void fill(int x1, int y1, int x2, int y2, int color);

	@Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;IIII)V", at = @At("HEAD"), cancellable = true)
	private void greencorps$previewItemHead(LivingEntity entity, World world, ItemStack stack, int x, int y, int seed, int z, CallbackInfo ci) {
		greencorps$tint(stack, x, y);
		if (MapSlotPreview.tryDrawPreview((DrawContext) (Object) this, stack, x, y)) {
			ci.cancel();
		}
	}

	@Inject(method = "drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
	private void greencorps$previewNoEntitySeedHead(ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
		greencorps$tint(stack, x, y);
		if (MapSlotPreview.tryDrawPreview((DrawContext) (Object) this, stack, x, y)) {
			ci.cancel();
		}
	}

	@Inject(method = "drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
	private void greencorps$previewNoEntityHead(ItemStack stack, int x, int y, CallbackInfo ci) {
		greencorps$tint(stack, x, y);
		if (MapSlotPreview.tryDrawPreview((DrawContext) (Object) this, stack, x, y)) {
			ci.cancel();
		}
	}

	@Unique
	private void greencorps$tint(ItemStack stack, int x, int y) {
		TooltipPlus mod = TooltipPlus.get();
		if (mod == null || !mod.isActive()) return;
		int color = mod.highlightColor(stack);
		if (color == 0) return;
		this.fill(x, y, x + 16, y + 16, color);
	}
}
