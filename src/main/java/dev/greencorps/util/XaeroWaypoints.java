package dev.greencorps.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.module.ModuleSession;

/**
 * Soft-dep Xaero Minimap helpers. Safe to call when Xaero is absent (no-ops / false).
 */
public final class XaeroWaypoints {
	private XaeroWaypoints() {
	}

	public static boolean isAvailable() {
		return FabricLoader.getInstance().isModLoaded("xaerominimap");
	}

	/** Snapshot of a minimap waypoint for UI / home create. */
	public record NearbyWaypoint(String name, int x, int y, int z, boolean yIncluded, int colorHex, double distBlocks) {}

	/**
	 * Sanitize a Xaero waypoint title for Meteor's font atlas:
	 * strip section-sign codes + invisible/bidi chars, transliterate common punctuation,
	 * drop remaining codepoints above U+00FF.
	 */
	public static String displayName(String raw) {
		if (raw == null || raw.isEmpty()) return "";
		StringBuilder sb = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '§' && i + 1 < raw.length()) {
				i++;
				continue;
			}
			int cp = c;
			if (Character.isHighSurrogate(c) && i + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(i + 1))) {
				cp = Character.toCodePoint(c, raw.charAt(i + 1));
				i++;
			}
			// Invisible / bidi
			if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF
				|| (cp >= 0x202A && cp <= 0x202E)) {
				continue;
			}
			// Transliterate common punctuation Meteor cannot render
			if (cp == 0x2013 || cp == 0x2014 || cp == 0x2015) { sb.append('-'); continue; }
			if (cp == 0x2018 || cp == 0x2019) { sb.append('\''); continue; }
			if (cp == 0x201C || cp == 0x201D) { sb.append('"'); continue; }
			if (cp == 0x2026) { sb.append("..."); continue; }
			if (cp == 0x00B7 || cp == 0x2022 || cp == 0x2219) { sb.append('-'); continue; }
			if (cp == 0x00A0) { sb.append(' '); continue; }
			if (cp > 0x00FF) continue;
			sb.append((char) cp);
		}
		return sb.toString().trim();
	}

	/**
	 * Nearest waypoints in the current minimap world, sorted by horizontal distance to the player.
	 * Empty if Xaero/minimap/session unavailable.
	 */
	public static java.util.List<NearbyWaypoint> nearestToPlayer(int limit) {
		if (!isAvailable() || limit <= 0) return java.util.List.of();
		try {
			java.util.List<NearbyWaypoint> all = allSortedByDistanceImpl();
			if (all.size() <= limit) return all;
			return new java.util.ArrayList<>(all.subList(0, limit));
		} catch (Throwable t) {
			return java.util.List.of();
		}
	}

	/** All enabled waypoints sorted by ascending distance to the player (nearest first). */
	public static java.util.List<NearbyWaypoint> allSortedByDistance() {
		if (!isAvailable()) return java.util.List.of();
		try {
			return allSortedByDistanceImpl();
		} catch (Throwable t) {
			return java.util.List.of();
		}
	}

	/** True if this looks like a GreenCorps Base Backup snapshot pin. */
	public static boolean isBaseBackupSnapshotPin(String name) {
		if (name == null) return false;
		String n = name.trim();
		return n.startsWith("BB SNAP:") || n.equals("BB SNAP");
	}

	private static java.util.List<NearbyWaypoint> allSortedByDistanceImpl() {
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc == null || mc.player == null) return java.util.List.of();
		MinimapWorld world = currentWorld();
		if (world == null) return java.util.List.of();

		double px = mc.player.getX();
		double pz = mc.player.getZ();
		java.util.ArrayList<NearbyWaypoint> all = new java.util.ArrayList<>();
		for (WaypointSet set : iterableSets(world)) {
			for (Waypoint wp : set.getWaypoints()) {
				if (wp == null || wp.isDisabled()) continue;
				int x = wp.getX();
				int z = wp.getZ();
				double dist = Math.hypot(x - px, z - pz);
				String name = wp.getName();
				if (name == null || name.isBlank()) name = "wp-" + x + "-" + z;
				int hex = 0xFFFFFF;
				try {
					WaypointColor c = wp.getWaypointColor();
					if (c != null) hex = c.getHex() & 0xFFFFFF;
				} catch (Throwable ignored) {}
				boolean yInc = true;
				try {
					yInc = wp.isYIncluded();
				} catch (Throwable ignored) {}
				all.add(new NearbyWaypoint(name, x, wp.getY(), z, yInc, hex, dist));
			}
		}
		all.sort(java.util.Comparator.comparingDouble(NearbyWaypoint::distBlocks));
		return all;
	}

	public static String chamberName(int breezes, int vaults) {
		return "TRIAL CHAMBER (" + breezes + "xB/" + vaults + "xTC)";
	}

	public static String chamberShortcode(int breezes) {
		return "TC" + breezes;
	}

	/** Parsed breeze/vault counts from a TRIAL CHAMBER waypoint name/shortcode. */
	public static final class ChamberCounts {
		public final int breezes;
		public final int vaults;

		public ChamberCounts(int breezes, int vaults) {
			this.breezes = Math.max(0, breezes);
			this.vaults = Math.max(0, vaults);
		}
	}

	/**
	 * Reads counts from {@code TRIAL CHAMBER (NxB/MxTC)}, {@code TRIAL CHAMBER (NxB)}, or shortcode {@code TCN}.
	 */
	public static ChamberCounts parseChamberCounts(String name, String shortcode) {
		int breezes = 0;
		int vaults = 0;
		if (name != null) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)xB(?:/(\\d+)xTC)?").matcher(name);
			if (m.find()) {
				breezes = Integer.parseInt(m.group(1));
				if (m.group(2) != null) {
					vaults = Integer.parseInt(m.group(2));
				}
			}
		}
		if (breezes == 0 && shortcode != null) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^TC-?(\\d+)").matcher(shortcode);
			if (m.find()) {
				breezes = Integer.parseInt(m.group(1));
			} else {
				m = java.util.regex.Pattern.compile("(?i)^T(\\d+)$").matcher(shortcode);
				if (m.find()) breezes = Integer.parseInt(m.group(1));
			}
		}
		return new ChamberCounts(breezes, vaults);
	}

	/** @deprecated use {@link #chamberShortcode(int)} */
	@Deprecated
	public static String chamberShortcode(int breezes, int vaults) {
		return chamberShortcode(breezes);
	}

	public static String outpostName(int flatness) {
		return "OUTPOST (" + flatness + " FLT)";
	}

	public static String outpostShortcode(int flatness) {
		return "OP-" + flatness;
	}

	public static String solidPocketName(int density, int ore, boolean includeOre) {
		if (includeOre) {
			return "SOLID POCKET (" + density + " DEN/" + ore + " ORE)";
		}
		return "SOLID POCKET (" + density + " DEN)";
	}

	public static String solidPocketShortcode(int density, int ore, boolean includeOre) {
		if (includeOre) {
			return "SP-" + density + "/" + ore;
		}
		return "SP-" + density;
	}

	public static BlockPos upsertSolidPocketWaypoint(BlockPos previousPos, BlockPos centroid, int density, int ore, boolean includeOre) {
		return upsert(
			previousPos,
			centroid,
			solidPocketName(density, ore, includeOre),
			solidPocketShortcode(density, ore, includeOre),
			WaypointColor.GRAY
		);
	}

	public static String baseBackupSnapshotName(String homeName) {
		String base = homeName == null || homeName.isBlank() ? "snap" : homeName.trim();
		String full = "BB SNAP: " + base;
		// Keep Xaero name readable
		return full.length() > 48 ? full.substring(0, 45) + "..." : full;
	}

	/** Pin a Base Backup snapshot find on the minimap (aqua). Soft-dep no-op if Xaero absent. */
	public static BlockPos upsertBaseBackupSnapshot(BlockPos previousPos, BlockPos centroid, String homeName) {
		return upsert(
			previousPos,
			centroid,
			baseBackupSnapshotName(homeName),
			"BB",
			WaypointColor.AQUA
		);
	}

	public static String stasisChamberName(String homeOrOwner) {
		String base = homeOrOwner == null || homeOrOwner.isBlank() ? "pearl" : homeOrOwner.trim();
		String full = "STASIS CHAMBER: " + base;
		return full.length() > 48 ? full.substring(0, 45) + "..." : full;
	}

	public static String stasisHubName(String homeName) {
		String base = homeName == null || homeName.isBlank() ? "home" : homeName.trim();
		String full = "STASIS HUB: " + base;
		return full.length() > 48 ? full.substring(0, 45) + "..." : full;
	}

	/** Pin a stasis chamber on the minimap (green when loaded, gray when empty). Soft-dep no-op. */
	public static BlockPos upsertStasisChamber(BlockPos previousPos, BlockPos centroid, String label, boolean loaded) {
		return upsert(
			previousPos,
			centroid,
			stasisChamberName(label),
			"ST",
			loaded ? WaypointColor.GREEN : WaypointColor.GRAY
		);
	}

	/** One hub pin per home (green when any own pearl loaded, gray otherwise). Soft-dep no-op. */
	public static BlockPos upsertStasisHub(BlockPos previousPos, BlockPos centroid, String homeName, boolean loaded) {
		return upsert(
			previousPos,
			centroid,
			stasisHubName(homeName),
			"SH",
			loaded ? WaypointColor.GREEN : WaypointColor.GRAY
		);
	}

	/**
	 * Remove legacy {@code STASIS CHAMBER:} pins whose XZ sit inside the home geofence.
	 * @return number removed
	 */
	public static int removeLegacyStasisChamberPins(String dimension, int centerX, int centerZ, int radiusChunks) {
		if (!isAvailable()) return 0;
		try {
			return removeLegacyStasisChamberPinsImpl(dimension, centerX, centerZ, radiusChunks);
		} catch (Throwable t) {
			return 0;
		}
	}

	private static int removeLegacyStasisChamberPinsImpl(String dimension, int centerX, int centerZ, int radiusChunks) {
		MinimapWorld world = currentWorld();
		if (world == null) return 0;
		int r = Math.max(0, radiusChunks);
		int minCx = (centerX >> 4) - r;
		int maxCx = (centerX >> 4) + r;
		int minCz = (centerZ >> 4) - r;
		int maxCz = (centerZ >> 4) + r;
		int minX = minCx << 4;
		int maxX = (maxCx << 4) + 15;
		int minZ = minCz << 4;
		int maxZ = (maxCz << 4) + 15;
		int removed = 0;
		for (WaypointSet set : iterableSets(world)) {
			if (set == null) continue;
			java.util.ArrayList<Waypoint> drop = new java.util.ArrayList<>();
			for (Waypoint wp : set.getWaypoints()) {
				if (wp == null) continue;
				String name = wp.getName();
				if (name == null || !name.toUpperCase(java.util.Locale.ROOT).startsWith("STASIS CHAMBER")) continue;
				int x = wp.getX();
				int z = wp.getZ();
				if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
				drop.add(wp);
			}
			for (Waypoint wp : drop) {
				set.remove(wp);
				removed++;
			}
		}
		if (removed > 0) {
			save(world);
			refresh();
		}
		return removed;
	}

	/** Result of a chamber waypoint upsert (position + whether an existing nearby scout pin was adopted). */
	public static final class ChamberUpsert {
		public final BlockPos pos;
		public final boolean adopted;

		public ChamberUpsert(BlockPos pos, boolean adopted) {
			this.pos = pos;
			this.adopted = adopted;
		}
	}

	/**
	 * Creates a waypoint or updates an existing one previously written at {@code previousPos}.
	 *
	 * @return the position written, or null if Xaero was unavailable / failed
	 */
	public static BlockPos upsert(BlockPos previousPos, BlockPos centroid, String name, String shortcode, WaypointColor color) {
		if (!isAvailable()) return null;
		try {
			return upsertImpl(previousPos, centroid, name, shortcode, color);
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Upserts a trial-chamber waypoint. Looks for an existing TRIAL CHAMBER pin within
	 * {@code mergeBlocks} horizontally and adopts it (name/shortcode/color only; XYZ kept).
	 */
	public static ChamberUpsert upsertChamberWaypoint(BlockPos previousPos, BlockPos centroid, int breezes, int vaults, int mergeBlocks) {
		if (!isAvailable()) return null;
		try {
			return upsertChamberImpl(previousPos, centroid, chamberName(breezes, vaults), chamberShortcode(breezes), mergeBlocks);
		} catch (Throwable t) {
			return null;
		}
	}

	/** @deprecated use {@link #upsertChamberWaypoint(BlockPos, BlockPos, int, int, int)} */
	@Deprecated
	public static BlockPos upsertChamberWaypoint(BlockPos previousPos, BlockPos centroid, int breezes, int vaults) {
		ChamberUpsert r = upsertChamberWaypoint(previousPos, centroid, breezes, vaults, 96);
		return r == null ? null : r.pos;
	}

	public static BlockPos upsertOutpostWaypoint(BlockPos previousPos, BlockPos centroid, int flatness) {
		return upsert(previousPos, centroid, outpostName(flatness), outpostShortcode(flatness), WaypointColor.GOLD);
	}

	/** End Gateway pin — shortcode always {@code GW}; set resolved via title prefix. */
	public static BlockPos upsertGateway(BlockPos previousPos, BlockPos centroid, String title, WaypointColor color) {
		if (title == null || title.isEmpty()) return null;
		WaypointColor pin = color != null ? color : WaypointColor.PURPLE;
		return upsert(previousPos, centroid, title, "GW", pin);
	}

	/** Nearest Xaero palette color to an RGB triple (for shared GatewayESP picker). */
	public static WaypointColor nearestWaypointColor(int r, int g, int b) {
		WaypointColor best = WaypointColor.PURPLE;
		int bestDist = Integer.MAX_VALUE;
		for (WaypointColor c : WaypointColor.values()) {
			int hex = c.getHex();
			int cr = (hex >> 16) & 0xff;
			int cg = (hex >> 8) & 0xff;
			int cb = hex & 0xff;
			int dr = cr - r;
			int dg = cg - g;
			int db = cb - b;
			int dist = dr * dr + dg * dg + db * db;
			if (dist < bestDist) {
				bestDist = dist;
				best = c;
			}
		}
		return best;
	}

	/**
	 * Backfill existing TRIAL CHAMBER waypoints (manual + GreenCorps) to gold + TC{breezes} shortcode.
	 *
	 * @return number of waypoints updated
	 */
	public static int retintTrialChamberWaypoints() {
		if (!isAvailable()) return 0;
		try {
			return retintTrialChamberWaypointsImpl();
		} catch (Throwable t) {
			return 0;
		}
	}

	private static int retintTrialChamberWaypointsImpl() {
		MinimapWorld world = currentWorld();
		if (world == null) return 0;

		int updated = 0;
		for (WaypointSet set : iterableSets(world)) {
			for (Waypoint wp : set.getWaypoints()) {
				String name = wp.getName();
				if (name == null) continue;
				String upper = name.toUpperCase();
				if (!upper.startsWith("TRIAL CHAMBER")) continue;

				Integer breezes = parseBreezeCount(name, wp.getInitials());
				String newInitials = breezes != null ? chamberShortcode(breezes) : wp.getInitials();
				String newName = name;
				if (breezes != null) {
					// Keep vault count in title only if already present and looks like ominous-scale; strip inflated xTC from old matcher
					java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)xB(?:/(\\d+)xTC)?").matcher(name);
					if (m.find()) {
						int b = Integer.parseInt(m.group(1));
						String tcGroup = m.group(2);
						if (tcGroup != null) {
							int tc = Integer.parseInt(tcGroup);
							// Old bug counted regular vaults (often 10+); drop TC from title until rescored
							if (tc > 6) {
								newName = "TRIAL CHAMBER (" + b + "xB)";
							} else {
								newName = chamberName(b, tc);
							}
						} else {
							newName = "TRIAL CHAMBER (" + b + "xB)";
						}
					}
				}

				boolean changed = false;
				if (!newName.equals(name)) {
					wp.setName(newName);
					changed = true;
				}
				if (newInitials != null && !newInitials.equals(wp.getInitials())) {
					wp.setInitials(newInitials);
					changed = true;
				}
				try {
					wp.setWaypointColor(WaypointColor.GOLD);
					changed = true;
				} catch (Throwable ignored) {
					// Older Xaero may use setColor
					try {
						wp.getClass().getMethod("setColor", WaypointColor.class).invoke(wp, WaypointColor.GOLD);
						changed = true;
					} catch (Throwable ignored2) {
					}
				}
				if (changed) updated++;
			}
		}

		if (updated > 0) {
			save(world);
			refresh();
		}
		return updated;
	}

	private static Integer parseBreezeCount(String name, String initials) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)xB").matcher(name);
		if (m.find()) return Integer.parseInt(m.group(1));
		if (initials != null) {
			m = java.util.regex.Pattern.compile("(?i)^TC-?(\\d+)").matcher(initials);
			if (m.find()) return Integer.parseInt(m.group(1));
			m = java.util.regex.Pattern.compile("(?i)^T(\\d+)$").matcher(initials);
			if (m.find()) return Integer.parseInt(m.group(1));
		}
		return null;
	}

	public static boolean removeWaypointAt(BlockPos pos) {
		if (!isAvailable() || pos == null) return false;
		try {
			return removeWaypointAtImpl(pos);
		} catch (Throwable t) {
			return false;
		}
	}

	/** Named set for a GreenCorps pin type; creates via XaeroPlus when present. */
	private static WaypointSet resolveTargetSet(MinimapWorld world, String name) {
		if (world == null) return null;
		String setName = null;
		if (name != null) {
			String u = name.toUpperCase();
			if (u.startsWith("TRIAL CHAMBER")) setName = "Trial Chambers";
			else if (u.startsWith("OUTPOST")) setName = "Outposts";
			else if (u.startsWith("STASIS CHAMBER") || u.startsWith("STASIS HUB")) setName = "Stasis Chamber";
			else if (u.startsWith("GW (") || u.startsWith("GATEWAY STACK")) setName = "Gateways";
			else if (u.contains("STASH")) setName = "Stashes";
		}
		if (setName != null) {
			WaypointSet named = getOrCreateNamedSet(world, setName);
			if (named != null) return named;
		}
		return world.getCurrentWaypointSet();
	}

	private static WaypointSet getOrCreateNamedSet(MinimapWorld world, String setName) {
		if (FabricLoader.getInstance().isModLoaded("xaeroplus")) {
			try {
				Class<?> api = Class.forName("xaeroplus.feature.waypoint.WaypointAPI");
				Object set = api.getMethod("getOrCreateWaypointSetInWorld", MinimapWorld.class, String.class)
					.invoke(null, world, setName);
				if (set instanceof WaypointSet ws) return ws;
			} catch (Throwable ignored) {}
		}
		try {
			WaypointSet existing = world.getWaypointSet(setName);
			if (existing != null) return existing;
			world.addWaypointSet(setName);
			return world.getWaypointSet(setName);
		} catch (Throwable t) {
			return null;
		}
	}

	private static BlockPos upsertImpl(BlockPos previousPos, BlockPos centroid, String name, String shortcode, WaypointColor color) {
		MinimapWorld world = currentWorld();
		if (world == null) return null;
		WaypointSet set = resolveTargetSet(world, name);
		if (set == null) return null;

		Waypoint existing = null;
		if (previousPos != null) {
			existing = findAt(set, previousPos);
		}
		if (existing == null) {
			existing = findAt(set, centroid);
		}

		if (existing != null) {
			// Periodic syncs (stasis hubs every 5s): skip the full waypoint-file save when unchanged.
			if (matches(existing, centroid, name, shortcode, color)) return centroid.toImmutable();
			existing.setX(centroid.getX());
			existing.setY(centroid.getY());
			existing.setZ(centroid.getZ());
			existing.setName(name);
			existing.setInitials(shortcode);
			existing.setYIncluded(true);
			applyColor(existing, color);
			save(world);
			refresh();
			return centroid.toImmutable();
		}

		Waypoint wp = new Waypoint(
			centroid.getX(),
			centroid.getY(),
			centroid.getZ(),
			name,
			shortcode,
			color,
			WaypointPurpose.NORMAL,
			false
		);
		wp.setYIncluded(true);
		set.add(wp);
		save(world);
		refresh();
		return centroid.toImmutable();
	}

	private static ChamberUpsert upsertChamberImpl(BlockPos previousPos, BlockPos centroid, String name, String shortcode, int mergeBlocks) {
		MinimapWorld world = currentWorld();
		if (world == null) return null;
		WaypointSet set = resolveTargetSet(world, name);
		if (set == null) return null;

		Waypoint existing = null;
		boolean adopted = false;
		if (previousPos != null) {
			existing = findAt(set, previousPos);
		}
		if (existing == null) {
			existing = findAt(set, centroid);
		}
		if (existing == null && mergeBlocks > 0) {
			existing = findNearbyTrialChamber(set, centroid, mergeBlocks);
			if (existing != null) adopted = true;
		}

		ChamberCounts proposed = parseChamberCounts(name, shortcode);

		if (existing != null) {
			if (!adopted) {
				existing.setX(centroid.getX());
				existing.setY(centroid.getY());
				existing.setZ(centroid.getZ());
			}

			ChamberCounts pin = parseChamberCounts(existing.getName(), existing.getInitials());
			boolean rename = proposed.breezes > pin.breezes || proposed.vaults > pin.vaults;
			if (rename) {
				existing.setName(name);
				existing.setInitials(shortcode);
			}
			existing.setYIncluded(true);
			applyColor(existing, WaypointColor.GOLD);
			save(world);
			refresh();
			BlockPos pos = adopted
				? new BlockPos(existing.getX(), existing.getY(), existing.getZ())
				: centroid.toImmutable();
			return new ChamberUpsert(pos, adopted);
		}

		Waypoint wp = new Waypoint(
			centroid.getX(),
			centroid.getY(),
			centroid.getZ(),
			name,
			shortcode,
			WaypointColor.GOLD,
			WaypointPurpose.NORMAL,
			false
		);
		wp.setYIncluded(true);
		set.add(wp);
		save(world);
		refresh();
		return new ChamberUpsert(centroid.toImmutable(), false);
	}

	private static boolean matches(Waypoint wp, BlockPos pos, String name, String shortcode, WaypointColor color) {
		if (wp.getX() != pos.getX() || wp.getY() != pos.getY() || wp.getZ() != pos.getZ()) return false;
		if (!java.util.Objects.equals(wp.getName(), name)) return false;
		if (!java.util.Objects.equals(wp.getInitials(), shortcode)) return false;
		if (!wp.isYIncluded()) return false;
		try {
			return wp.getWaypointColor() == color;
		} catch (Throwable t) {
			return false;
		}
	}

	private static void applyColor(Waypoint wp, WaypointColor color) {
		try {
			wp.setWaypointColor(color);
		} catch (Throwable ignored) {
			try {
				wp.getClass().getMethod("setColor", WaypointColor.class).invoke(wp, color);
			} catch (Throwable ignored2) {
			}
		}
	}

	private static boolean removeWaypointAtImpl(BlockPos pos) {
		MinimapWorld world = currentWorld();
		if (world == null) return false;
		for (WaypointSet set : iterableSets(world)) {
			Waypoint existing = findAt(set, pos);
			if (existing == null) continue;
			set.remove(existing);
			save(world);
			refresh();
			return true;
		}
		return false;
	}

	/** Prefer named Trial Chambers set, then every set (upserts no longer stay on current only). */
	private static java.util.List<WaypointSet> iterableSets(MinimapWorld world) {
		java.util.ArrayList<WaypointSet> out = new java.util.ArrayList<>();
		if (world == null) return out;
		try {
			WaypointSet named = world.getWaypointSet("Trial Chambers");
			if (named != null) out.add(named);
		} catch (Throwable ignored) {}
		try {
			for (WaypointSet set : world.getIterableWaypointSets()) {
				if (set == null) continue;
				if (out.contains(set)) continue;
				out.add(set);
			}
		} catch (Throwable ignored) {
			try {
				WaypointSet cur = world.getCurrentWaypointSet();
				if (cur != null && !out.contains(cur)) out.add(cur);
			} catch (Throwable ignored2) {}
		}
		return out;
	}

	private static Waypoint findAt(WaypointSet set, BlockPos pos) {
		for (Waypoint wp : set.getWaypoints()) {
			if (wp.getX() == pos.getX() && wp.getY() == pos.getY() && wp.getZ() == pos.getZ()) {
				return wp;
			}
		}
		return null;
	}

	/** Nearest TRIAL CHAMBER-named waypoint within horizontal merge distance. */
	static Waypoint findNearbyTrialChamber(WaypointSet set, BlockPos center, int mergeBlocks) {
		if (set == null || center == null || mergeBlocks <= 0) return null;
		double mergeSq = (double) mergeBlocks * mergeBlocks;
		Waypoint best = null;
		double bestDist = Double.MAX_VALUE;
		for (Waypoint wp : set.getWaypoints()) {
			String name = wp.getName();
			if (name == null || !name.toUpperCase().startsWith("TRIAL CHAMBER")) continue;
			double dx = wp.getX() - center.getX();
			double dz = wp.getZ() - center.getZ();
			double distSq = dx * dx + dz * dz;
			if (distSq <= mergeSq && distSq < bestDist) {
				bestDist = distSq;
				best = wp;
			}
		}
		return best;
	}

	/**
	 * Peek breeze/vault counts from a pin at {@code previousPos} or the nearest TRIAL CHAMBER within merge.
	 */
	public static ChamberCounts peekNearbyChamberCounts(BlockPos around, int mergeBlocks) {
		if (!isAvailable() || around == null) return null;
		try {
			MinimapWorld world = currentWorld();
			if (world == null) return null;
			Waypoint wp = null;
			for (WaypointSet set : iterableSets(world)) {
				wp = findAt(set, around);
				if (wp == null) wp = findNearbyTrialChamber(set, around, mergeBlocks);
				if (wp != null) break;
			}
			if (wp == null) return null;
			return parseChamberCounts(wp.getName(), wp.getInitials());
		} catch (Throwable t) {
			return null;
		}
	}

	private static MinimapWorld currentWorld() {
		MinimapSession session = currentSession();
		if (session == null) return null;
		return session.getWorldManager().getCurrentWorld();
	}

	private static MinimapSession currentSession() {
		ModuleSession<?> raw = BuiltInHudModules.MINIMAP.getCurrentSession();
		if (raw instanceof MinimapSession session) return session;
		return null;
	}

	private static void save(MinimapWorld world) {
		MinimapSession session = currentSession();
		if (session == null || world == null) return;
		try {
			session.getWorldManagerIO().saveWorld(world);
		} catch (Exception ignored) {
			// Disk/session failures should not crash the finder.
		}
	}

	private static void refresh() {
		try {
			Class<?> supportMods = Class.forName("xaero.map.mods.SupportMods");
			Object minimap = supportMods.getField("xaeroMinimap").get(null);
			if (minimap != null) {
				minimap.getClass().getMethod("requestWaypointsRefresh").invoke(minimap);
			}
		} catch (Throwable ignored) {
			// World map optional.
		}
	}
}
