package dev.greencorps.util;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.DyeColor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Maps banner pattern ids that require a loom pattern item. */
public final class BannerPatternItems {
	private static final Map<String, Item> BY_ID = new HashMap<>();

	static {
		BY_ID.put("flower", Items.FLOWER_BANNER_PATTERN);
		BY_ID.put("creeper", Items.CREEPER_BANNER_PATTERN);
		BY_ID.put("skull", Items.SKULL_BANNER_PATTERN);
		BY_ID.put("mojang", Items.MOJANG_BANNER_PATTERN);
		BY_ID.put("globe", Items.GLOBE_BANNER_PATTERN);
		BY_ID.put("piglin", Items.PIGLIN_BANNER_PATTERN);
		BY_ID.put("flow", Items.FLOW_BANNER_PATTERN);
		BY_ID.put("guster", Items.GUSTER_BANNER_PATTERN);
		BY_ID.put("field_masoned", Items.FIELD_MASONED_BANNER_PATTERN);
		BY_ID.put("bordure_indented", Items.BORDURE_INDENTED_BANNER_PATTERN);
	}

	private BannerPatternItems() {
	}

	public static Item requiredItem(String patternId) {
		if (patternId == null) return null;
		String id = patternId.toLowerCase(Locale.ROOT);
		if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
		return BY_ID.get(id);
	}

	public static Item bannerFor(DyeColor color) {
		return switch (color) {
			case WHITE -> Items.WHITE_BANNER;
			case ORANGE -> Items.ORANGE_BANNER;
			case MAGENTA -> Items.MAGENTA_BANNER;
			case LIGHT_BLUE -> Items.LIGHT_BLUE_BANNER;
			case YELLOW -> Items.YELLOW_BANNER;
			case LIME -> Items.LIME_BANNER;
			case PINK -> Items.PINK_BANNER;
			case GRAY -> Items.GRAY_BANNER;
			case LIGHT_GRAY -> Items.LIGHT_GRAY_BANNER;
			case CYAN -> Items.CYAN_BANNER;
			case PURPLE -> Items.PURPLE_BANNER;
			case BLUE -> Items.BLUE_BANNER;
			case BROWN -> Items.BROWN_BANNER;
			case GREEN -> Items.GREEN_BANNER;
			case RED -> Items.RED_BANNER;
			case BLACK -> Items.BLACK_BANNER;
		};
	}

	public static Item dyeFor(DyeColor color) {
		return switch (color) {
			case WHITE -> Items.WHITE_DYE;
			case ORANGE -> Items.ORANGE_DYE;
			case MAGENTA -> Items.MAGENTA_DYE;
			case LIGHT_BLUE -> Items.LIGHT_BLUE_DYE;
			case YELLOW -> Items.YELLOW_DYE;
			case LIME -> Items.LIME_DYE;
			case PINK -> Items.PINK_DYE;
			case GRAY -> Items.GRAY_DYE;
			case LIGHT_GRAY -> Items.LIGHT_GRAY_DYE;
			case CYAN -> Items.CYAN_DYE;
			case PURPLE -> Items.PURPLE_DYE;
			case BLUE -> Items.BLUE_DYE;
			case BROWN -> Items.BROWN_DYE;
			case GREEN -> Items.GREEN_DYE;
			case RED -> Items.RED_DYE;
			case BLACK -> Items.BLACK_DYE;
		};
	}

	public static DyeColor parseColor(String raw) {
		if (raw == null) return null;
		String id = raw.toLowerCase(Locale.ROOT);
		if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
		try {
			return DyeColor.valueOf(id.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
