package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.backup.StoreStats;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.compat.XaeroBridge;
import dev.greencorps.modules.BaseBackup;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Optional;

/** Module.getWidget / Time Machine manage — Current Home + 3×2 action matrix + status. */
public final class BaseBackupPanel {
	private static final int DEFAULT_HOME_RADIUS = 32;

	private BaseBackupPanel() {}

	public static WWidget build(GuiTheme theme, BaseBackup mod) {
		return build(theme, mod, false);
	}

	/**
	 * @param onManageScreen when true, Manage becomes a no-op (already here) and Settings opens ModuleScreen.
	 */
	public static WWidget build(GuiTheme theme, BaseBackup mod, boolean onManageScreen) {
		mod.ensureStore();
		WVerticalList list = theme.verticalList();

		if (mod.homeStore() == null) {
			list.add(theme.label("Store failed to open.")).expandX();
			addStatusLines(list, theme, mod);
			return list;
		}

		addMatrix(list, theme, mod, onManageScreen);
		addStatusLines(list, theme, mod);

		if (mod.homeStore().homes().isEmpty()) {
			list.add(theme.label("No homes yet — use Add Home, coordinates, Xaero waypoint, or right-click a map waypoint."))
				.expandX();
		}

		return list;
	}

	/** Append action matrix into an existing list (Time Machine manage window). */
	public static void appendActions(WVerticalList list, GuiTheme theme, BaseBackup mod) {
		if (mod.homeStore() == null) return;
		addMatrix(list, theme, mod, true);
		addStatusLines(list, theme, mod);
	}

	/** Xaero + health sit under the action matrix. */
	private static void addStatusLines(WVerticalList list, GuiTheme theme, BaseBackup mod) {
		WLabel status = list.add(theme.label(XaeroBridge.statusLine())).expandX().widget();
		status.tooltip = "Xaero is optional. Map overlay auto-disables when absent.";

		WLabel health = list.add(theme.label(healthLine(mod))).expandX().widget();
		health.tooltip = "Store health. Run verify from Manage Homes & Backups. (.home help)";

		if (!mod.isActive()) {
			list.add(theme.label("Module off — browse/add OK; activate to capture & commit."))
				.expandX();
		}
	}

	/**
	 * Separator + coverage (if on site) + one outer table of nested button rows
	 * so vertical row gaps match horizontal button gaps.
	 */
	private static void addMatrix(WVerticalList list, GuiTheme theme, BaseBackup mod, boolean onManageScreen) {
		Optional<Home> cover = HomeWaypointFilter.coveringPlayer(mod);
		Home onSite = cover.orElse(null);

		if (onSite != null) {
			String shown = XaeroWaypoints.displayName(onSite.name);
			if (shown == null || shown.isBlank()) shown = onSite.name;
			list.add(theme.horizontalSeparator("Current Home: " + shown)).expandX();

			String covLine = coverageLine(mod, onSite);
			WLabel info = list.add(theme.label(covLine)).expandX()
				.marginTop(BbGrid.gap(theme)).widget();
			info.tooltip = onSite.isSnapshot()
				? onSite.name + " (SNAPSHOT) r=" + onSite.radiusChunks
				: onSite.name + " (HOME) @ " + onSite.centerChunkX + "," + onSite.centerChunkZ
					+ " r=" + onSite.radiusChunks;
		}

		WTable outer = list.add(theme.table()).expandX().widget();
		BbGrid.applySpacing(outer, theme);

		if (onSite != null) {
			WTable homeRow = theme.table();
			BbGrid.applySpacing(homeRow, theme);
			fillCurrentHomeRow(homeRow, theme, mod, onSite);
			outer.add(homeRow).expandX();
			outer.row();
		}

		boolean inside = HomeWaypointFilter.playerInsideAnyHome(mod);
		double cellW = BbGrid.cellW(theme);

		WTable action1 = theme.table();
		BbGrid.applySpacing(action1, theme);
		fillActionRow1(action1, theme, mod, cellW, inside);
		outer.add(action1).expandX();
		outer.row();

		WTable action2 = theme.table();
		BbGrid.applySpacing(action2, theme);
		fillActionRow2(action2, theme, mod, cellW, inside, onManageScreen);
		outer.add(action2).expandX();
		outer.row();
	}

	private static String coverageLine(BaseBackup mod, Home h) {
		if (h.isSnapshot()) {
			if (h.armed) {
				return mod.countLoadedChunks(h) + "/" + h.expectedChunks() + " loaded";
			}
			return "done - " + (int) mod.coverage(h) + "pct";
		}
		return (int) mod.coverage(h) + "pct - " + lastCommitAge(mod, h);
	}

	private static void fillCurrentHomeRow(WTable row, GuiTheme theme, BaseBackup mod, Home h) {
		double fifth = BbGrid.fifthW(theme);

		WToggleChip arm = row.add(new WToggleChip("Arm", h.armed)).expandX().minWidth(fifth).widget();
		arm.tooltip = h.isSnapshot()
			? "Snapshot stays armed until the area is fully loaded and exported."
			: "Armed homes auto-capture loaded chunks.";
		arm.action = () -> {
			h.armed = arm.isOn();
			try { mod.homeStore().update(h); } catch (Exception e) {
				HomeChat.warn(e.toString());
			}
		};

		WToggleChip play = row.add(new WToggleChip("Playable World Save", h.keepPlayableSave))
			.expandX().minWidth(fifth).widget();
		play.tooltip = "Keep one SP world under saves/<name> (latest overwrite) on disconnect + commit. Needs auto-playable-latest.";
		play.action = () -> {
			h.keepPlayableSave = play.isOn();
			try { mod.homeStore().update(h); } catch (Exception e) {
				HomeChat.warn(e.toString());
			}
		};

		if (h.isSnapshot()) {
			WDisabledChip commitNa = row.add(new WDisabledChip("Manual Commit")).expandX().minWidth(fifth).widget();
			commitNa.tooltip = "Snapshots auto-finalize when every chunk is loaded — manual commit is for homes.";
		} else {
			WFrameButton commit = row.add(new WFrameButton("Manual Commit")).expandX().minWidth(fifth).widget();
			commit.tooltip = "Force a commit now. Normally commits run automatically on the commit interval while you are here.";
			commit.action = () -> mod.doCommit(h);
		}

		WFrameButton manage = row.add(new WFrameButton("Manage Home")).expandX().minWidth(fifth).widget();
		manage.tooltip = "Open timeline / health for this home.";
		manage.action = () -> openManage(mod, h.id);

		WFrameButton backup = row.add(new WFrameButton("Create Backup World")).expandX().minWidth(fifth).widget();
		backup.tooltip = "Export latest: playable homes refresh the one SP world; otherwise creates a named archive under saves/.";
		backup.action = () -> {
			try {
				var path = mod.exportHome(h, "latest", null);
				if (mod.shouldPublishPlayable(h)) {
					HomeChat.info("Playable export queued → " + path.getFileName());
				} else {
					HomeChat.info("Backup world → " + path.getFileName());
				}
			} catch (Exception e) {
				HomeChat.warn("Backup world failed: " + e);
			}
		};
		row.row();
	}

	private static void fillActionRow1(WTable grid, GuiTheme theme, BaseBackup mod, double cellW, boolean inside) {
		String l1 = "Add Home (Player Position)";
		String l2 = "Add Home (Coordinates)";
		String l3 = "Add Home (Xaero Waypoint)";

		if (inside) {
			WDisabledChip chip = grid.add(new WDisabledChip(l1)).expandX().minWidth(cellW).widget();
			chip.tooltip = "You are inside a home — walk outside to add another at your feet.";
		} else {
			WFrameButton addHere = grid.add(new WFrameButton(l1)).expandX().minWidth(cellW).widget();
			addHere.tooltip = "Create a home at your position (default radius " + DEFAULT_HOME_RADIUS + " chunks).";
			addHere.action = () -> {
				try {
					if (!mod.ensureStore()) return;
					String name = "home-" + (mod.homeStore().homes().size() + 1);
					Home h = mod.addHomeHere(name, DEFAULT_HOME_RADIUS);
					HomeChat.info("Added " + name);
					PanelReload.reloadModuleScreen();
					PlayableConfirmScreen.promptAfterCreate(mod, h);
				} catch (Exception e) {
					HomeChat.warn(e.toString());
				}
			};
		}

		WFrameButton addCoords = grid.add(new WFrameButton(l2)).expandX().minWidth(cellW).widget();
		addCoords.tooltip = "Paste or type block coordinates. Create is blocked if those coords fall inside an existing home.";
		addCoords.action = () -> openChild(new CoordHomeScreen(mod));

		WFrameButton addWp = grid.add(new WFrameButton(l3)).expandX().minWidth(cellW).widget();
		addWp.tooltip = "Pick from Xaero waypoints outside existing homes (nearest first).";
		addWp.action = () -> openChild(new WaypointPickerScreen(mod));
		grid.row();
	}

	private static void fillActionRow2(WTable grid, GuiTheme theme, BaseBackup mod, double cellW,
									   boolean inside, boolean onManageScreen) {
		String l4 = "Snapshot (Player Position)";

		if (inside) {
			WDisabledChip snapNa = grid.add(new WDisabledChip("Inside Home - Snapshot N/A"))
				.expandX().minWidth(cellW).widget();
			snapNa.tooltip = "Snapshots are for areas outside existing homes.";
		} else {
			WFrameButton snap = grid.add(new WFrameButton(l4)).expandX().minWidth(cellW).widget();
			snap.tooltip = "One-shot area snapshot. Name and radius (default "
				+ mod.snapshotRadiusChunks() + " chunks), then capture and export when chunks load.";
			snap.action = () -> openChild(new SnapshotNameScreen(mod));
		}

		addChipCell(grid, theme, mod, cellW, inside);

		if (onManageScreen) {
			WFrameButton settings = grid.add(new WFrameButton("Settings")).expandX().minWidth(cellW).widget();
			settings.tooltip = "Open Time Machine settings (commit, render, keybind). Right-click the module for the same screen.";
			settings.action = () -> {
				MinecraftClient mc = MinecraftClient.getInstance();
				if (mc == null) return;
				var screen = meteordevelopment.meteorclient.gui.GuiThemes.get().moduleScreen(mod);
				screen.parent = mc.currentScreen;
				mc.setScreen(screen);
			};
		} else {
			WFrameButton manage = grid.add(new WFrameButton("Manage Homes & Backups")).expandX().minWidth(cellW).widget();
			manage.tooltip = "Full home list, timeline, health panel, export. (.home)";
			manage.action = mod::openManageScreen;
		}
		grid.row();
	}

	private static void openManage(BaseBackup mod, String homeId) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		mc.setScreen(new BaseBackupScreen(mod, homeId));
	}

	private static String lastCommitAge(BaseBackup mod, Home h) {
		if (mod.engine() == null) return "never committed";
		long last = mod.engine().lastCommitAt(h.id);
		if (last <= 0) return "never committed";
		long mins = Math.max(0, (System.currentTimeMillis() - last) / 60_000L);
		if (mins < 1) return "committed just now";
		if (mins < 60) return "committed " + mins + "m ago";
		long hrs = mins / 60;
		if (hrs < 48) return "committed " + hrs + "h ago";
		return "committed " + (hrs / 24) + "d ago";
	}

	private static void addChipCell(WTable grid, GuiTheme theme, BaseBackup mod, double cellW, boolean inside) {
		String zero = "(0 Waypoints In Range To Add As Home)";
		if (inside || HomeWaypointFilter.eligibleSorted(mod).isEmpty()) {
			WFrameButton openList = grid.add(new WFrameButton(zero)).expandX().minWidth(cellW).widget();
			openList.tooltip = inside
				? "Open full Xaero waypoint list (geofenced). You are inside a home — only waypoints outside homes can become homes."
				: (XaeroWaypoints.isAvailable()
					? "No nearby eligible waypoints — click to open the full searchable list."
					: "Xaero Minimap not detected — list may be empty.");
			openList.action = () -> openChild(new WaypointPickerScreen(mod));
			return;
		}

		List<XaeroWaypoints.NearbyWaypoint> eligible = HomeWaypointFilter.eligibleSorted(mod);
		XaeroWaypoints.NearbyWaypoint wp = eligible.get(0);
		MinecraftClient mc = MinecraftClient.getInstance();
		String dim = mc != null && mc.world != null
			? mc.world.getRegistryKey().getValue().toString()
			: "minecraft:overworld";
		String shown = XaeroWaypoints.displayName(wp.name());
		WWaypointChip chip = grid.add(new WWaypointChip(wp.name(), wp.colorHex()))
			.expandX().minWidth(cellW).widget();
		chip.tooltip = shown + " - " + formatDist(wp.distBlocks())
			+ " @ " + wp.x() + "," + wp.z() + " (click = set as home; use Add Home Xaero Waypoint for full list)";
		chip.action = () -> {
			try {
				if (!mod.ensureStore()) return;
				Integer spawnY = wp.yIncluded() ? wp.y() : null;
				Home h = mod.addHomeFromWaypoint(wp.name(), wp.x(), spawnY, wp.z(), dim,
					mod.mapHomeRadiusChunks());
				HomeChat.info("Home " + h.name + " from waypoint");
				PanelReload.reloadModuleScreen();
				PlayableConfirmScreen.promptAfterCreate(mod, h);
			} catch (Exception e) {
				HomeChat.warn(e.toString());
			}
		};
	}

	private static void openChild(WindowScreen screen) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		screen.parent = mc.currentScreen;
		mc.setScreen(screen);
	}

	private static String formatDist(double blocks) {
		if (blocks < 1000) return (int) Math.round(blocks) + "m";
		return String.format("%.1fkm", blocks / 1000.0);
	}

	private static String healthLine(BaseBackup mod) {
		if (mod.engine() == null) return "Health: inactive";
		mod.maybeStorageAlert();
		String store = StoreStats.formatBytes(mod.engine().estimateStoreSize());
		String alert = mod.storageOverAlert()
			? " | ALERT >" + mod.storageAlertGb() + "GB"
			: " | alert @" + mod.storageAlertGb() + "GB";
		return "Health: " + mod.engine().health().label(mod.engine().degradedSections())
			+ " | store " + store + alert
			+ " | rehearsal " + (mod.engine().lastRehearsalAt() == 0 ? "never"
			: (mod.engine().lastRehearsalPass() ? "PASS" : "FAIL"));
	}
}
