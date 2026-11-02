package dev.greencorps.mixin;

import dev.greencorps.util.ShulkerScreenTint;
import dev.greencorps.util.ShulkerTintedScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/** Tooltip+ colored-shulker-screen: the shulker box GUI multiplied by the box's dye colour. */
@Mixin(ShulkerBoxScreen.class)
public abstract class MixinShulkerBoxScreenGc extends HandledScreen<ShulkerBoxScreenHandler> implements ShulkerTintedScreen {
	@Unique
	private int greencorps$tint;

	protected MixinShulkerBoxScreenGc(ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void greencorps$pickTint(ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title, CallbackInfo ci) {
		greencorps$tint = ShulkerScreenTint.forOpeningScreen();
	}

	@Redirect(
		method = "drawBackground",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIFFIIII)V")
	)
	private void greencorps$tintBackground(DrawContext ctx, Function<Identifier, RenderLayer> layer, Identifier texture,
		int x, int y, float u, float v, int w, int h, int texW, int texH) {
		if (greencorps$tint == 0) ctx.drawTexture(layer, texture, x, y, u, v, w, h, texW, texH);
		else ctx.drawTexture(layer, texture, x, y, u, v, w, h, texW, texH, greencorps$tint);
	}

	@Override
	public int greencorps$shulkerTint() {
		return greencorps$tint;
	}
}
