package dev.greencorps.gui.map;

import dev.greencorps.backup.Home;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.gui.PlayableConfirmScreen;
import dev.greencorps.gui.SnapshotNameScreen;
import dev.greencorps.modules.BaseBackup;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.Waypoint;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Appends Time Machine actions to Xaero's world-map right-click menus.
 */
public final class HomeMapMenu {
	private HomeMapMenu() {}

	public static void append(
		IRightClickableElement target,
		ArrayList<RightClickOption> options,
		int worldX,
		int worldZ,
		RegistryKey<World> dim
	) {
		if (options == null) return;
		BaseBackup mod = BaseBackup.get();
		if (mod == null || !mod.isActive() || mod.homeStore() == null) return;

		String dimId = dim != null ? dim.getValue().toString() : "minecraft:overworld";
		int chunkX = worldX >> 4;
		int chunkZ = worldZ >> 4;

		Optional<Home> under = findCovering(mod, dimId, chunkX, chunkZ);
		if (under.isPresent()) {
			Home h = under.get();
			options.add(option(target, "Open/Edit Time Machine", () ->
				mod.mcSetScreenHome(h.id)));
		}

		options.add(option(target, "Download Map Snapshot", () -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc == null) return;
			var screen = new SnapshotNameScreen(mod, worldX, worldZ, dimId);
			screen.parent = mc.currentScreen;
			mc.setScreen(screen);
		}));

		// Empty-map RC: create a home centered on the click XZ.
		options.add(option(target, "Add Home (Coordinates)", () -> {
			try {
				String name = "home-" + (worldX >> 4) + "-" + (worldZ >> 4);
				Home h = mod.addHomeFromWaypoint(name, worldX, null, worldZ, dimId, mod.mapHomeRadiusChunks());
				HomeChat.info("Home " + h.name + " @ " + (worldX >> 4) + "," + (worldZ >> 4));
				PlayableConfirmScreen.promptAfterCreate(mod, h);
			} catch (Exception e) {
				HomeChat.warn("Add home failed: " + e);
			}
		}));
	}

	/** WaypointReader menu: create home named from the Xaero waypoint. */
	public static void appendWaypoint(
		IRightClickableElement target,
		ArrayList<RightClickOption> options,
		Waypoint w,
		String dimId
	) {
		if (options == null || w == null) return;
		BaseBackup mod = BaseBackup.get();
		if (mod == null || !mod.isActive() || mod.homeStore() == null) return;

		String dim = dimId != null && !dimId.isBlank() ? dimId : "minecraft:overworld";
		int x = w.getX();
		int y = w.getY();
		int z = w.getZ();
		boolean yIncluded = w.isyIncluded();
		String rawName = w.getName();
		String name = (rawName == null || rawName.isBlank()) ? "wp-" + x + "-" + z : rawName.trim();

		options.add(option(target, "Add Home (Time Machine)", () -> {
			try {
				Integer spawnY = yIncluded ? y : null;
				Home h = mod.addHomeFromWaypoint(name, x, spawnY, z, dim, mod.mapHomeRadiusChunks());
				HomeChat.info("Home " + h.name + " from waypoint @ " + (x >> 4) + "," + (z >> 4));
				PlayableConfirmScreen.promptAfterCreate(mod, h);
			} catch (Exception e) {
				HomeChat.warn("Set home failed: " + e);
			}
		}));
	}

	private static Optional<Home> findCovering(BaseBackup mod, String dimId, int chunkX, int chunkZ) {
		Home best = null;
		int bestR = Integer.MAX_VALUE;
		for (Home h : mod.homeStore().homes()) {
			if (!dimId.equals(h.dimension)) continue;
			if (!h.containsChunk(chunkX, chunkZ)) continue;
			if (h.radiusChunks < bestR) {
				bestR = h.radiusChunks;
				best = h;
			}
		}
		return Optional.ofNullable(best);
	}

	private static RightClickOption option(IRightClickableElement target, String label, Runnable action) {
		return new GcRightClickOption(label, target, action);
	}
}
