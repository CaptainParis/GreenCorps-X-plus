package dev.greencorps.gui.map;

import dev.greencorps.chat.StasisChat;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisHome;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.Waypoint;

import java.util.ArrayList;

/** Appends Stasis Monitor actions to Xaero world-map right-click menus. */
public final class StasisMapMenu {
	private StasisMapMenu() {}

	public static void append(
		IRightClickableElement target,
		ArrayList<RightClickOption> options,
		int worldX,
		int worldZ,
		RegistryKey<World> dim
	) {
		if (options == null) return;
		StasisChambers mod = StasisChambers.get();
		if (mod == null || !mod.isActive()) return;
		mod.ensureStore();
		if (mod.store() == null) return;

		String dimId = dim != null ? dim.getValue().toString() : "minecraft:overworld";

		options.add(option(target, "Add Stasis Home", () -> {
			try {
				MinecraftClient mc = MinecraftClient.getInstance();
				int y = mc != null && mc.player != null ? mc.player.getBlockY() : 64;
				String name = "stasis-" + (worldX >> 4) + "-" + (worldZ >> 4);
				StasisHome h = mod.addHomeAt(worldX, y, worldZ, dimId, name,
					mod.homeRadiusChunks(), mod.defaultPearls());
				StasisChat.info("Stasis home " + h.name + " @ " + (worldX >> 4) + "," + (worldZ >> 4));
			} catch (Exception e) {
				StasisChat.warn("Add stasis home failed: " + e.getClass().getSimpleName());
			}
		}));
	}

	public static void appendWaypoint(
		IRightClickableElement target,
		ArrayList<RightClickOption> options,
		Waypoint w,
		String dimId
	) {
		if (options == null || w == null) return;
		StasisChambers mod = StasisChambers.get();
		if (mod == null || !mod.isActive()) return;
		mod.ensureStore();
		if (mod.store() == null) return;

		String dim = dimId != null && !dimId.isBlank() ? dimId : "minecraft:overworld";
		int x = w.getX();
		int y = w.getY();
		int z = w.getZ();
		boolean yIncluded = w.isyIncluded();
		String rawName = w.getName();
		String name = (rawName == null || rawName.isBlank()) ? "stasis-" + x + "-" + z : rawName.trim();

		options.add(option(target, "Add Stasis Home", () -> {
			try {
				Integer spawnY = yIncluded ? y : null;
				StasisHome h = mod.addHomeFromWaypoint(name, x, spawnY, z, dim,
					mod.homeRadiusChunks(), mod.defaultPearls());
				StasisChat.info("Stasis home " + h.name + " from waypoint");
			} catch (Exception e) {
				StasisChat.warn("Add stasis home failed: " + e.getClass().getSimpleName());
			}
		}));
	}

	private static RightClickOption option(IRightClickableElement target, String label, Runnable action) {
		return new GcRightClickOption(label, target, action);
	}
}
