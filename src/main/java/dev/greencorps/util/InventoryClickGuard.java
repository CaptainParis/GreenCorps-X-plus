package dev.greencorps.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BannerItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Click filters set by modules and applied by the {@code clickSlot} mixin. Drag-splitting maps, bundles and
 * banners leaves ghost items on 2b2t (EV mod's "fix2b2tGhostItems"); Map Duper+ turns this guard on.
 */
public final class InventoryClickGuard {
	private static volatile boolean noDragMapsBundlesBanners;

	private InventoryClickGuard() {}

	public static void setNoDragMapsBundlesBanners(boolean value) {
		noDragMapsBundlesBanners = value;
	}

	/** @return true when this click must not be sent */
	public static boolean blocks(SlotActionType action, ItemStack cursor) {
		if (!noDragMapsBundlesBanners || action != SlotActionType.QUICK_CRAFT || cursor == null) return false;
		return cursor.isOf(Items.FILLED_MAP) || cursor.isOf(Items.MAP)
			|| cursor.contains(DataComponentTypes.BUNDLE_CONTENTS) || cursor.getItem() instanceof BannerItem;
	}
}
