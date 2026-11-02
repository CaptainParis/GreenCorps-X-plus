package dev.greencorps.mixin;

import dev.greencorps.modules.MapSlotPreview;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * High priority RETURN blit so Map Slot Preview paints after Rusher ExtraTooltips maps.slot when possible.
 */
@Mixin(value = DrawContext.class, priority = 10000)
public abstract class MixinDrawContextGcPreviewTail {
	@Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;IIII)V", at = @At("RETURN"))
	private void greencorps$previewItemReturn(LivingEntity entity, World world, ItemStack stack, int x, int y, int seed, int z, CallbackInfo ci) {
		MapSlotPreview.tryDrawPreview((DrawContext) (Object) this, stack, x, y);
	}

	@Inject(method = "drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;III)V", at = @At("RETURN"))
	private void greencorps$previewNoEntitySeedReturn(ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
		MapSlotPreview.tryDrawPreview((DrawContext) (Object) this, stack, x, y);
	}

	@Inject(method = "drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;II)V", at = @At("RETURN"))
	private void greencorps$previewNoEntityReturn(ItemStack stack, int x, int y, CallbackInfo ci) {
		MapSlotPreview.tryDrawPreview((DrawContext) (Object) this, stack, x, y);
	}
}
