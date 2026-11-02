package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.modules.BaseBackup;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable Xaero waypoint list (geofenced, distance-sorted) to create a home. */
public class WaypointPickerScreen extends WindowScreen {
	private final BaseBackup mod;
	private WTextBox search;
	private WView listView;
	private List<XaeroWaypoints.NearbyWaypoint> eligible = List.of();

	public WaypointPickerScreen(BaseBackup mod) {
		super(GuiThemes.get(), "Add Home (Xaero Waypoint)");
		this.mod = mod;
	}

	@Override
	public void initWidgets() {
		eligible = HomeWaypointFilter.eligibleSorted(mod);

		MinecraftClient mc = MinecraftClient.getInstance();
		double sw = mc != null ? mc.getWindow().getScaledWidth() : 400;
		double sh = mc != null ? mc.getWindow().getScaledHeight() : 300;
		window.minWidth = sw * 0.33;

		// Keep list tall but still shorter than a typical Base Backup module window.
		double margin = theme.scale(48);
		double listMax = Math.min(sh * 0.65, Math.max(theme.scale(120), sh * 0.75 - margin));

		WTable top = add(theme.table()).expandX().widget();
		top.add(theme.label("Search")).expandX();
		search = top.add(theme.textBox("")).expandX().widget();
		search.action = this::rebuildList;
		search.actionOnUnfocused = this::rebuildList;
		top.row();

		listView = add(theme.view()).expandX().widget();
		listView.maxHeight = listMax;
		listView.scrollOnlyWhenMouseOver = true;
		rebuildList();

		WButton cancel = add(theme.button("Cancel")).expandX().widget();
		cancel.action = this::close;
	}

	/** Keep the module window visible underneath while this picker is open. */
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (parent != null) {
			parent.render(context, -1, -1, delta);
		}
		super.render(context, mouseX, mouseY, delta);
	}

	private void rebuildList() {
		listView.clear();
		String q = search != null && search.get() != null
			? search.get().trim().toLowerCase(Locale.ROOT) : "";

		List<XaeroWaypoints.NearbyWaypoint> shown = new ArrayList<>();
		for (XaeroWaypoints.NearbyWaypoint wp : eligible) {
			String shownName = XaeroWaypoints.displayName(wp.name()).toLowerCase(Locale.ROOT);
			if (!q.isEmpty() && !shownName.contains(q) && !wp.name().toLowerCase(Locale.ROOT).contains(q)) {
				continue;
			}
			shown.add(wp);
		}

		if (shown.isEmpty()) {
			WLabel none = listView.add(theme.label("(0 waypoints outside existing homes)")).expandX().widget();
			none.tooltip = XaeroWaypoints.isAvailable()
				? "Waypoints already inside a home (or BB snapshot pins) are hidden. Create a Xaero waypoint outside homes."
				: "Xaero Minimap not detected.";
			return;
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		String dim = mc != null && mc.world != null
			? mc.world.getRegistryKey().getValue().toString()
			: "minecraft:overworld";

		WTable table = listView.add(theme.table()).expandX().widget();
		for (XaeroWaypoints.NearbyWaypoint wp : shown) {
			String shownName = XaeroWaypoints.displayName(wp.name());
			WWaypointChip chip = table.add(new WWaypointChip(wp.name(), wp.colorHex())).expandX().widget();
			chip.tooltip = shownName + " — " + formatDist(wp.distBlocks())
				+ " @ " + wp.x() + "," + wp.z() + " (click = set as home)";
			chip.action = () -> select(wp, dim);
			table.add(theme.label(formatDist(wp.distBlocks())));
			table.row();
		}
	}

	private void select(XaeroWaypoints.NearbyWaypoint wp, String dim) {
		try {
			if (!mod.ensureStore()) return;
			Integer spawnY = wp.yIncluded() ? wp.y() : null;
			Home h = mod.addHomeFromWaypoint(wp.name(), wp.x(), spawnY, wp.z(), dim,
				mod.mapHomeRadiusChunks());
			HomeChat.info("Home " + h.name + " from waypoint");
			PanelReload.reloadModuleScreen();
			close();
			PlayableConfirmScreen.promptAfterCreate(mod, h);
		} catch (Exception e) {
			HomeChat.warn(e.toString());
		}
	}

	private static String formatDist(double blocks) {
		if (blocks < 1000) return (int) Math.round(blocks) + "m";
		return String.format("%.1fkm", blocks / 1000.0);
	}
}
