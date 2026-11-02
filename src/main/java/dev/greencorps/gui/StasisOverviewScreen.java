package dev.greencorps.gui;

import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisDisplay;
import dev.greencorps.stasis.StasisHome;
import dev.greencorps.stasis.StasisPearl;
import dev.greencorps.stasis.StasisPlayerProfile;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Chamber Slots Overview / Pearl Summary. Settings stay on the module screen.
 * Toolbar stays stable; profiles/homes body refreshes when the store revises
 * (full WindowScreen.reload would swallow button clicks mid-release).
 */
public class StasisOverviewScreen extends WindowScreen {
	private final StasisChambers mod;
	/** Display fingerprint — ignores lastSeenMs churn from live pearl ticks. */
	private String lastBodySig = "";
	/** Defer body refresh while mouse is held (belt-and-suspenders). */
	private boolean bodyRefreshPending;
	private WVerticalList body;
	private WAccentButton chambersTab;
	private WAccentButton pearlsTab;

	public StasisOverviewScreen(StasisChambers mod) {
		super(GuiThemes.get(), windowTitle(mod));
		this.mod = mod;
	}

	private static String windowTitle(StasisChambers mod) {
		return mod.panelViewMode() == StasisChambers.PanelViewMode.PEARLS
			? "Pearl Summary"
			: "Chamber Slots Overview";
	}

	@Override
	public void initWidgets() {
		mod.ensureStore();
		if (mod.store() == null) {
			add(theme.label("Store not open.")).expandX();
			return;
		}
		bodyRefreshPending = false;
		lastBodySig = "";

		MinecraftClient mc = MinecraftClient.getInstance();
		double sw = mc != null ? mc.getWindow().getScaledWidth() : 480;
		window.minWidth = Math.min(sw * 0.55, theme.scale(420));

		WVerticalList list = add(theme.verticalList()).expandX().widget();
		double gap = BbGrid.gap(theme);
		list.spacing = gap;

		boolean pearls = mod.panelViewMode() == StasisChambers.PanelViewMode.PEARLS;

		WHorizontalList tabs = list.add(theme.horizontalList()).expandX().widget();
		tabs.spacing = gap;
		chambersTab = tabs.add(new WAccentButton("Chamber Slots Overview")).expandX().widget();
		chambersTab.setSelected(!pearls);
		chambersTab.action = () -> {
			if (mod.panelViewMode() != StasisChambers.PanelViewMode.CHAMBERS) {
				mod.setPanelViewMode(StasisChambers.PanelViewMode.CHAMBERS);
				reload();
			}
		};
		pearlsTab = tabs.add(new WAccentButton("Pearl Summary")).expandX().widget();
		pearlsTab.setSelected(pearls);
		pearlsTab.action = () -> {
			if (mod.panelViewMode() != StasisChambers.PanelViewMode.PEARLS) {
				mod.setPanelViewMode(StasisChambers.PanelViewMode.PEARLS);
				reload();
			}
		};

		WHorizontalList row1 = list.add(theme.horizontalList()).expandX().widget();
		row1.spacing = gap;
		WButton addHere = row1.add(theme.button("Add Home (Player Position)")).expandX().widget();
		addHere.tooltip = "Create a stasis home at your feet (default radius from settings).";
		addHere.action = () -> openChild(new StasisPanel.StasisAddHomeScreen(mod));
		WButton coords = row1.add(theme.button("Add Home (Coordinates)")).expandX().widget();
		coords.tooltip = "Paste or type block coordinates for a new stasis home.";
		coords.action = () -> openChild(new StasisCoordHomeScreen(mod));
		WButton wp = row1.add(theme.button("Add Home (Xaero Waypoint)")).expandX().widget();
		wp.tooltip = "Pick a Xaero waypoint outside existing stasis homes (nearest first).";
		wp.action = () -> openChild(new StasisWaypointPickerScreen(mod));

		WHorizontalList row2 = list.add(theme.horizontalList()).expandX().widget();
		row2.spacing = gap;
		WButton manual = row2.add(theme.button("Manual Scan")).expandX().widget();
		manual.tooltip = "Rescan loaded chunks for every stasis home in range and refresh chamber counts.";
		manual.action = mod::manualScanHomes;
		WButton addProfile = row2.add(theme.button("Add Profile")).expandX().widget();
		addProfile.action = () -> openChild(new StasisProfileEditScreen(mod, null));
		WButton settings = row2.add(theme.button("Settings")).expandX().widget();
		settings.tooltip = "Open module settings (ESP, alerts, keybind). Right-click Stasis Monitor in the list for the same screen.";
		settings.action = this::openSettings;

		body = list.add(theme.verticalList()).expandX().widget();
		body.spacing = gap;
		fillBody();

		WButton close = add(theme.button("Close")).expandX().widget();
		close.action = this::close;
	}

	/** Profiles + homes only — safe to clear without killing toolbar buttons. */
	private void fillBody() {
		if (body == null) return;
		body.clear();
		if (mod.store() == null) {
			body.add(theme.label("Store not open.")).expandX();
			lastBodySig = "";
			return;
		}
		lastBodySig = bodySignature();
		fillProfilesOverview(body);

		List<StasisHome> homes = new ArrayList<>(mod.store().homes());
		homes.sort(homeOrder());

		if (homes.isEmpty()) {
			body.add(theme.label("No stasis homes yet.")).expandX();
		}
		for (StasisHome home : homes) {
			boolean expand = shouldExpandHome(home);
			String title = StasisDisplay.sanitizeTitle(home.name) + "  "
				+ mod.capacityFor(home).titleSuffix();
			WSection section = body.add(theme.section(title, expand)).expandX().widget();
			StasisPanel.fillHomeSection(theme, section, mod, home);
		}

		if (chambersTab != null && pearlsTab != null) {
			boolean pearls = mod.panelViewMode() == StasisChambers.PanelViewMode.PEARLS;
			chambersTab.setSelected(!pearls);
			pearlsTab.setSelected(pearls);
		}
	}

	/**
	 * UI-relevant store snapshot. Omits lastSeenMs / revision so live pearl heartbeats
	 * do not rebuild the body every tick.
	 */
	private String bodySignature() {
		if (mod.store() == null) return "";
		StringBuilder sb = new StringBuilder(256);
		sb.append(mod.panelViewMode().name()).append('|');
		for (StasisPlayerProfile p : mod.store().profiles()) {
			int[] counts = profilePearlLocationCounts(p);
			sb.append(p.id).append(':').append(p.displayLabel()).append(':')
				.append(p.igns).append(':').append(counts[0]).append('/').append(counts[1])
				.append(';');
		}
		sb.append('#');
		List<StasisHome> homes = new ArrayList<>(mod.store().homes());
		homes.sort(homeOrder());
		for (StasisHome home : homes) {
			sb.append(home.id).append(':')
				.append(home.name).append(':')
				.append(mod.capacityFor(home).titleSuffix()).append(';');
			for (var slot : mod.store().slotsForHome(home.id)) {
				sb.append(slot.id).append(',')
					.append(slot.number).append(',')
					.append(slot.pearlId).append(',')
					.append(slot.geometryOk).append(';');
			}
		}
		return sb.toString();
	}

	private void fillProfilesOverview(WVerticalList list) {
		WSection section = list.add(theme.section("Profiles", true)).expandX().widget();
		var matched = mod.store().matchProfile(mod.localUuidString(), mod.localPlayerIgn());
		if (matched.isPresent()) {
			section.add(theme.label("Playing as: "
				+ (mod.localPlayerIgn() != null ? mod.localPlayerIgn() : "?")
				+ " -> " + matched.get().displayLabel())).expandX();
		} else {
			section.add(theme.label("Playing as: unlinked - Add Profile for this account.")).expandX();
		}
		List<StasisPlayerProfile> profiles = mod.store().profiles();
		if (profiles.isEmpty()) {
			section.add(theme.label("No profiles yet.")).expandX();
			return;
		}

		double labelW = theme.scale(120);
		double numW = theme.scale(56);
		double gap = BbGrid.gap(theme);

		WHorizontalList header = section.add(theme.horizontalList()).expandX().widget();
		header.spacing = gap;
		header.add(theme.label("Label")).minWidth(labelW);
		header.add(theme.label("IGNs")).expandX();
		header.add(theme.label("Pearls")).minWidth(numW);
		header.add(theme.label("Locations")).minWidth(numW);
		header.add(theme.label("")).minWidth(theme.scale(48));

		for (StasisPlayerProfile p : profiles) {
			WHorizontalList row = section.add(theme.horizontalList()).expandX().widget();
			row.spacing = gap;

			WVerticalList labelCol = row.add(theme.verticalList()).minWidth(labelW).widget();
			labelCol.add(new WOwnerChip(p.displayLabel(), WOwnerChip.colorFor(p.displayLabel())));

			WHorizontalList igns = row.add(theme.horizontalList()).expandX().widget();
			igns.spacing = theme.scale(4);
			if (p.igns == null || p.igns.isEmpty()) {
				igns.add(theme.label("-"));
			} else {
				boolean any = false;
				for (String ign : p.igns) {
					if (ign == null || ign.isBlank()) continue;
					igns.add(new WOwnerChip(ign.trim(), WOwnerChip.colorFor(ign.trim())));
					any = true;
				}
				if (!any) igns.add(theme.label("-"));
			}

			int[] counts = profilePearlLocationCounts(p);
			row.add(theme.label(String.valueOf(counts[0]))).minWidth(numW);
			row.add(theme.label(String.valueOf(counts[1]))).minWidth(numW);

			WButton edit = row.add(theme.button("Edit")).minWidth(theme.scale(48)).widget();
			edit.action = () -> openChild(new StasisProfileEditScreen(mod, p));
		}
	}

	/** Loaded pearl count and distinct homes with ≥1 loaded pearl for this profile. */
	private int[] profilePearlLocationCounts(StasisPlayerProfile profile) {
		int pearls = 0;
		int locations = 0;
		if (mod.store() == null || profile == null) return new int[]{0, 0};
		for (StasisHome home : mod.store().homes()) {
			int n = mod.store().loadedProfilePearlCount(home, profile);
			if (n <= 0) continue;
			pearls += n;
			locations++;
		}
		return new int[]{pearls, locations};
	}

	private Comparator<StasisHome> homeOrder() {
		return (a, b) -> {
			boolean aMine = homeHasProfilePearl(a);
			boolean bMine = homeHasProfilePearl(b);
			if (aMine != bMine) return aMine ? -1 : 1;
			String an = a.name == null ? "" : a.name.toLowerCase(Locale.ROOT);
			String bn = b.name == null ? "" : b.name.toLowerCase(Locale.ROOT);
			return an.compareTo(bn);
		};
	}

	private boolean shouldExpandHome(StasisHome home) {
		if (homeHasProfilePearl(home)) return true;
		StasisHome nearest = mod.nearestHome();
		return nearest != null && nearest.id != null && nearest.id.equals(home.id);
	}

	private boolean homeHasProfilePearl(StasisHome home) {
		if (home == null || home.id == null) return false;
		for (var slot : mod.store().slotsForHome(home.id)) {
			if (slot.pearlId == null || slot.pearlId.isBlank()) continue;
			StasisPearl pearl = mod.store().findPearlById(slot.pearlId).orElse(null);
			if (pearl != null && mod.store().matchProfile(pearl).isPresent()) return true;
		}
		return false;
	}

	private void openSettings() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		var screen = GuiThemes.get().moduleScreen(mod);
		screen.parent = this;
		client.setScreen(screen);
	}

	private void openChild(WindowScreen screen) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		screen.parent = this;
		mc.setScreen(screen);
	}

	@Override
	public void tick() {
		super.tick();
		if (mod.store() == null || body == null) return;
		if (!bodySignature().equals(lastBodySig)) {
			bodyRefreshPending = true;
		}
		if (!bodyRefreshPending) return;
		if (mouseButtonHeld()) return;
		bodyRefreshPending = false;
		if (bodySignature().equals(lastBodySig)) return;
		fillBody();
	}

	private static boolean mouseButtonHeld() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.getWindow() == null) return false;
		long handle = mc.getWindow().getHandle();
		return GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
			|| GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
	}
}
