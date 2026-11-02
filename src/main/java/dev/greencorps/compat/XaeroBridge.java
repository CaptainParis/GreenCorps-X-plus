package dev.greencorps.compat;

import net.fabricmc.loader.api.FabricLoader;

/** Single gate for optional Xaero World Map presence. */
public final class XaeroBridge {
	private static final boolean WORLD_MAP = FabricLoader.getInstance().isModLoaded("xaeroworldmap");
	private static final boolean MINIMAP = FabricLoader.getInstance().isModLoaded("xaerominimap");

	private XaeroBridge() {}

	public static boolean isWorldMapAvailable() {
		return WORLD_MAP;
	}

	public static boolean isMinimapAvailable() {
		return MINIMAP;
	}

	public static String statusLine() {
		if (WORLD_MAP) {
			return "Xaero World Map detected — map overlay on.";
		}
		return "Xaero World Map not detected — map overlay off. Everything else works normally.";
	}
}
