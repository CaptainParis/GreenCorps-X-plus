package dev.greencorps.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.item.BannerItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.DyeColor;

import java.util.Optional;

/**
 * /give command (1.21 component syntax) for a banner or shield's design, in the format
 * {@link BannerCommandParser} reads, so it can be pasted straight into Auto Loom. Shields give the
 * banner of their base colour.
 */
public final class BannerGiveCommand {
	private BannerGiveCommand() {}

	/** Null when {@code stack} is not a banner / shield or a layer has no registered pattern id. */
	public static String of(ItemStack stack) {
		DyeColor base;
		if (stack.getItem() instanceof BannerItem banner) {
			base = banner.getColor();
		} else if (stack.getItem() instanceof ShieldItem) {
			base = stack.getOrDefault(DataComponentTypes.BASE_COLOR, DyeColor.WHITE);
		} else {
			return null;
		}
		BannerPatternsComponent patterns = stack.getOrDefault(DataComponentTypes.BANNER_PATTERNS, BannerPatternsComponent.DEFAULT);
		StringBuilder sb = new StringBuilder("/give @s minecraft:").append(base.asString()).append("_banner");
		if (patterns.layers().isEmpty()) return sb.toString();
		sb.append("[minecraft:banner_patterns=[");
		boolean first = true;
		for (BannerPatternsComponent.Layer layer : patterns.layers()) {
			Optional<? extends RegistryKey<?>> key = layer.pattern().getKey();
			if (key.isEmpty()) return null;
			if (!first) sb.append(',');
			first = false;
			sb.append("{\"pattern\":\"").append(key.get().getValue())
				.append("\",\"color\":\"").append(layer.color().asString()).append("\"}");
		}
		return sb.append("]]").toString();
	}
}
