package dev.greencorps.gui;

import dev.greencorps.chat.StasisChat;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisHome;
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

/** Searchable Xaero waypoint list to create a stasis home (skips waypoints already inside one). */
public class StasisWaypointPickerScreen extends WindowScreen {
	private final StasisChambers mod;
	private WTextBox search;
	private WView listView;
	private List<XaeroWaypoints.NearbyWaypoint> eligible = List.of();

	public StasisWaypointPickerScreen(StasisChambers mod) {
		super(GuiThemes.get(), "Add Stasis Home (Xaero Waypoint)");
		this.mod = mod;
	}

	@Override
	public void initWidgets() {
		eligible = eligibleSorted();

		MinecraftClient mc = MinecraftClient.getInstance();
		double sw = mc != null ? mc.getWindow().getScaledWidth() : 400;
		double sh = mc != null ? mc.getWindow().getScaledHeight() : 300;
		window.minWidth = sw * 0.33;

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

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (parent != null) {
			parent.render(context, -1, -1, delta);
		}
		super.render(context, mouseX, mouseY, delta);
	}

	private List<XaeroWaypoints.NearbyWaypoint> eligibleSorted() {
		mod.ensureStore();
		List<XaeroWaypoints.NearbyWaypoint> all = XaeroWaypoints.allSortedByDistance();
		if (all == null || all.isEmpty()) return List.of();
		MinecraftClient mc = MinecraftClient.getInstance();
		String dim = mc != null && mc.world != null
			? mc.world.getRegistryKey().getValue().toString()
			: "minecraft:overworld";
		List<XaeroWaypoints.NearbyWaypoint> out = new ArrayList<>();
		for (XaeroWaypoints.NearbyWaypoint wp : all) {
			if (insideAnyStasisHome(dim, wp.x(), wp.z())) continue;
			out.add(wp);
		}
		return out;
	}

	private boolean insideAnyStasisHome(String dim, int x, int z) {
		if (mod.store() == null) return false;
		for (StasisHome h : mod.store().homes()) {
			if (h.sameDimension(dim) && h.containsBlock(x, z)) return true;
		}
		return false;
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
			WLabel none = listView.add(theme.label("(0 waypoints outside existing stasis homes)")).expandX().widget();
			none.tooltip = XaeroWaypoints.isAvailable()
				? "Waypoints already inside a stasis home are hidden."
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
				+ " @ " + wp.x() + "," + wp.z() + " (click = add stasis home)";
			chip.action = () -> select(wp, dim);
			table.add(theme.label(formatDist(wp.distBlocks())));
			table.row();
		}
	}

	private void select(XaeroWaypoints.NearbyWaypoint wp, String dim) {
		try {
			if (!mod.ensureStore()) return;
			Integer spawnY = wp.yIncluded() ? wp.y() : null;
			StasisHome h = mod.addHomeFromWaypoint(wp.name(), wp.x(), spawnY, wp.z(), dim,
				mod.homeRadiusChunks(), mod.defaultPearls());
			StasisChat.info("Stasis home " + h.name + " from waypoint");
			PanelReload.reloadModuleScreen();
			close();
		} catch (Exception e) {
			StasisChat.warn("Add stasis home failed: " + e.getClass().getSimpleName());
		}
	}

	private static String formatDist(double blocks) {
		if (blocks < 1000) return (int) Math.round(blocks) + "m";
		return String.format("%.1fkm", blocks / 1000.0);
	}
}
