package dev.greencorps.modules;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.ItemStack;

/**
 * Deprecated shim: location tooltip/highlight now lives under {@link TooltipPlus}.
 * Kept so existing highlight call sites keep compiling.
 */
public class LocationTooltip extends Module {
	public LocationTooltip() {
		super(dev.greencorps.GreenCorps.CATEGORY, "location-tooltip",
			"Moved into Tooltip+ → Location. Enable Tooltip+ instead.");
	}

	public static LocationTooltip get() {
		return Modules.get().get(LocationTooltip.class);
	}

	/** Packed ARGB, or 0 — forwards to Tooltip+. */
	public int highlightColor(ItemStack stack) {
		TooltipPlus tip = TooltipPlus.get();
		if (tip != null && tip.isActive()) return tip.highlightColor(stack);
		return 0;
	}

	@Override
	public void onActivate() {
		TooltipPlus tip = TooltipPlus.get();
		if (tip != null && !tip.isActive()) tip.toggle();
		if (isActive()) toggle();
		info("Location Tooltip is now part of Tooltip+. Use that module.");
	}
}
