package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.backup.HomeStore;
import dev.greencorps.modules.BaseBackup;
import dev.greencorps.util.XaeroWaypoints;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Shared geofence filter for waypoint chips / picker: omit WPs already inside a home
 * and GreenCorps snapshot pins. Remaining list stays distance-sorted (nearest first).
 */
public final class HomeWaypointFilter {
	private HomeWaypointFilter() {}

	public static boolean playerInsideAnyHome(BaseBackup mod) {
		return coveringPlayer(mod).isPresent();
	}

	/** Smallest-radius home covering the player in the current dimension. */
	public static Optional<Home> coveringPlayer(BaseBackup mod) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.player == null || mod == null || mod.homeStore() == null) {
			return Optional.empty();
		}
		String dim = mc.world != null
			? mc.world.getRegistryKey().getValue().toString()
			: null;
		return coveringAt(mod.homeStore(), dim, mc.player.getX(), mc.player.getZ());
	}

	/** Smallest-radius home covering block XZ (optional dimension filter). */
	public static Optional<Home> coveringAt(HomeStore store, String dimId, double blockX, double blockZ) {
		if (store == null) return Optional.empty();
		Home best = null;
		int bestR = Integer.MAX_VALUE;
		for (Home h : store.homes()) {
			if (dimId != null && h.dimension != null && !dimId.equals(h.dimension)) continue;
			if (!h.containsBlock(blockX, blockZ)) continue;
			if (h.radiusChunks < bestR) {
				bestR = h.radiusChunks;
				best = h;
			}
		}
		return Optional.ofNullable(best);
	}

	public static boolean coveredByHome(HomeStore store, double blockX, double blockZ) {
		return coveringAt(store, null, blockX, blockZ).isPresent();
	}

	/** Eligible waypoints: not a BB snapshot pin, not inside any home. Input already distance-sorted. */
	public static List<XaeroWaypoints.NearbyWaypoint> eligible(
		Collection<XaeroWaypoints.NearbyWaypoint> sorted,
		HomeStore store
	) {
		List<XaeroWaypoints.NearbyWaypoint> out = new ArrayList<>();
		if (sorted == null) return out;
		for (XaeroWaypoints.NearbyWaypoint wp : sorted) {
			if (wp == null) continue;
			if (XaeroWaypoints.isBaseBackupSnapshotPin(wp.name())) continue;
			if (coveredByHome(store, wp.x(), wp.z())) continue;
			out.add(wp);
		}
		return out;
	}

	public static List<XaeroWaypoints.NearbyWaypoint> eligibleSorted(BaseBackup mod) {
		HomeStore store = mod == null ? null : mod.homeStore();
		return eligible(XaeroWaypoints.allSortedByDistance(), store);
	}
}
