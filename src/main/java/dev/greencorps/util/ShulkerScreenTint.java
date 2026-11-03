package dev.greencorps.util;

import dev.greencorps.modules.TooltipPlus;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;

/**
 * Remembers which shulker box block was right-clicked, so the shulker screen that opens next can be tinted with
 * its dye colour (Tooltip+ <i>colored-shulker-screen</i>). The screen itself only knows its title.
 */
public final class ShulkerScreenTint {
	/** A shulker screen opened this soon after the click belongs to that box. */
	private static final int CLICK_WINDOW_TICKS = 40;

	private static int ticks;
	private static int clickTick = Integer.MIN_VALUE / 2;
	private static DyeColor clickedDye;
	/** Set right before a screen for a shulker item (Safe Shulker's preview) is built; wins over a block click. */
	private static boolean itemExpected;
	private static DyeColor itemDye;

	private ShulkerScreenTint() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> ticks++);
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClient && world.getBlockState(hit.getBlockPos()).getBlock() instanceof ShulkerBoxBlock box) {
				clickTick = ticks;
				clickedDye = box.getColor();
			}
			return ActionResult.PASS;
		});
	}

	/** The next shulker screen shows a shulker item rather than a placed box: tint it with that box's dye (null = undyed). */
	public static void expectItem(DyeColor dye) {
		itemExpected = true;
		itemDye = dye;
	}

	/** Tint for a shulker screen opening now (0 = vanilla), consuming the remembered item or click. */
	public static int forOpeningScreen() {
		boolean item = itemExpected;
		DyeColor dye = item ? itemDye : clickedDye;
		boolean recent = item || ticks - clickTick <= CLICK_WINDOW_TICKS;
		itemExpected = false;
		itemDye = null;
		clickTick = Integer.MIN_VALUE / 2;
		TooltipPlus tip = TooltipPlus.get();
		if (!recent || tip == null) return 0;
		return tip.shulkerScreenTint(dye);
	}

	/** Label colour readable on the tinted body: vanilla dark grey, or white on dark dyes. */
	public static int labelColor(int tint, int vanilla) {
		int r = ((tint >>> 16) & 0xFF) * 0xC6 / 255;
		int g = ((tint >>> 8) & 0xFF) * 0xC6 / 255;
		int b = (tint & 0xFF) * 0xC6 / 255;
		return (r * 299 + g * 587 + b * 114) / 1000 > 140 ? vanilla : 0xFFFFFF;
	}
}
