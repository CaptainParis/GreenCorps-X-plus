package dev.greencorps.util;

import net.minecraft.item.Item;
import net.minecraft.util.DyeColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses minecraftmaps.com /give banner commands (1.21 component syntax).
 */
public final class BannerCommandParser {
	private static final Pattern BASE = Pattern.compile(
		"minecraft:([a-z_]+)_banner",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern LAYER = Pattern.compile(
		"\"pattern\"\\s*:\\s*\"(?:minecraft:)?([a-z0-9_]+)\"\\s*,\\s*\"color\"\\s*:\\s*\"(?:minecraft:)?([a-z_]+)\"",
		Pattern.CASE_INSENSITIVE
	);
	private static final int MAX_LOOM_LAYERS = 6;

	private BannerCommandParser() {
	}

	public static final class Layer {
		public final String patternId;
		public final DyeColor color;
		public final Item patternItem; // null if dye-only

		public Layer(String patternId, DyeColor color, Item patternItem) {
			this.patternId = patternId;
			this.color = color;
			this.patternItem = patternItem;
		}
	}

	public static final class BannerPlan {
		public final DyeColor base;
		public final Item baseBanner;
		public final List<Layer> layers;
		public final Map<Item, Integer> dyeCounts;
		public final Set<Item> patternItems;
		public final List<String> warnings;
		public final boolean ok;

		public BannerPlan(
			DyeColor base,
			Item baseBanner,
			List<Layer> layers,
			Map<Item, Integer> dyeCounts,
			Set<Item> patternItems,
			List<String> warnings,
			boolean ok
		) {
			this.base = base;
			this.baseBanner = baseBanner;
			this.layers = layers;
			this.dyeCounts = dyeCounts;
			this.patternItems = patternItems;
			this.warnings = warnings;
			this.ok = ok;
		}

		public static BannerPlan empty(String warning) {
			List<String> w = new ArrayList<>();
			if (warning != null) w.add(warning);
			return new BannerPlan(null, null, List.of(), Map.of(), Set.of(), w, false);
		}
	}

	public static BannerPlan parse(String command) {
		if (command == null || command.isBlank()) {
			return BannerPlan.empty("Paste a give command first.");
		}

		List<String> warnings = new ArrayList<>();
		Matcher baseMatch = BASE.matcher(command);
		if (!baseMatch.find()) {
			return BannerPlan.empty("No minecraft:<color>_banner found.");
		}
		DyeColor base = BannerPatternItems.parseColor(baseMatch.group(1));
		if (base == null) {
			return BannerPlan.empty("Unknown base color: " + baseMatch.group(1));
		}

		List<Layer> layers = new ArrayList<>();
		Matcher layerMatch = LAYER.matcher(command);
		while (layerMatch.find()) {
			String patternId = layerMatch.group(1).toLowerCase(Locale.ROOT);
			DyeColor color = BannerPatternItems.parseColor(layerMatch.group(2));
			if (color == null) {
				warnings.add("Skip unknown color: " + layerMatch.group(2));
				continue;
			}
			layers.add(new Layer(patternId, color, BannerPatternItems.requiredItem(patternId)));
		}

		if (layers.isEmpty()) {
			warnings.add("No banner_patterns layers found.");
		}
		if (layers.size() > MAX_LOOM_LAYERS) {
			warnings.add("Loom max is " + MAX_LOOM_LAYERS + " layers; extras ignored.");
			layers = new ArrayList<>(layers.subList(0, MAX_LOOM_LAYERS));
		}

		Map<Item, Integer> dyes = new LinkedHashMap<>();
		Set<Item> patterns = new LinkedHashSet<>();
		for (Layer layer : layers) {
			Item dye = BannerPatternItems.dyeFor(layer.color);
			dyes.merge(dye, 1, Integer::sum);
			if (layer.patternItem != null) patterns.add(layer.patternItem);
		}

		return new BannerPlan(
			base,
			BannerPatternItems.bannerFor(base),
			List.copyOf(layers),
			Map.copyOf(dyes),
			Set.copyOf(patterns),
			List.copyOf(warnings),
			!layers.isEmpty()
		);
	}
}
