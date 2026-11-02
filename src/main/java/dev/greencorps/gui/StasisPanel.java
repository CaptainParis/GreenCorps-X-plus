package dev.greencorps.gui;

import dev.greencorps.chat.StasisChat;
import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.StasisChamberSlot;
import dev.greencorps.stasis.StasisHome;
import dev.greencorps.stasis.StasisPearl;
import dev.greencorps.stasis.StasisStore;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stasis monitor tables and add-home screen. Module settings use a slim widget only. */
public final class StasisPanel {
	private static final Color NOW_GREEN = new Color(80, 220, 120);
	private static final Color VERIFIED_GREEN = new Color(80, 200, 120);
	private static final Color BROKEN_RED = new Color(200, 90, 90);
	private static final Color UNKNOWN_GREY = new Color(140, 140, 145);
	private static final Color ASSUMED_BLUE = new Color(90, 160, 255);
	private static final String BROKEN_TIP =
		"Chamber geometry failed at stored root - column no longer matches (soul sand / bubble / honey+piston). "
			+ "Pearls may still float nearby after edits; rebuild or wait for re-detect.";
	private static final String UNKNOWN_OWNER = "\u0000unknown";

	private StasisPanel() {}

	/** Slim settings widget: open the monitor. Homes and pearls live in {@link StasisOverviewScreen}. */
	public static WWidget build(GuiTheme theme, StasisChambers mod) {
		mod.ensureStore();
		WVerticalList list = theme.verticalList();
		list.add(theme.label(
			"Left click or the module keybind opens Chamber Slots Overview / Pearl Summary. "
				+ "Right click opens these settings. The module stays on; turn ESP off under Render."
		)).expandX();
		WButton open = list.add(theme.button("Open monitor")).expandX().widget();
		open.action = mod::openOverview;
		return list;
	}

	private static void openChild(meteordevelopment.meteorclient.gui.WindowScreen screen) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		screen.parent = mc.currentScreen;
		mc.setScreen(screen);
	}

	/** Shared table builder for Overview screen / commands. */
	public static void fillHomeSection(GuiTheme theme, WSection section, StasisChambers mod,
									   StasisHome home) {
		fillHomeSection(theme, section, mod, home, ColWidths.measure(theme),
			mod.panelViewMode() == StasisChambers.PanelViewMode.PEARLS);
	}

	private static void fillHomeSection(GuiTheme theme, WSection section, StasisChambers mod,
										StasisHome home, ColWidths cols, boolean pearlsView) {
		List<StasisChamberSlot> slots = usableSlots(mod, home);
		StasisStore.HomeCapacity cap = mod.capacityFor(home);

		WHorizontalList homeActions = section.add(theme.horizontalList()).expandX().widget();
		homeActions.spacing = BbGrid.gap(theme);
		WButton editHome = homeActions.add(theme.button("Edit Home")).widget();
		editHome.tooltip = "Name, radius, and required pearls per account.";
		editHome.action = () -> openChild(new StasisHomeEditScreen(mod, home));
		WButton shareText = homeActions.add(theme.button("Share (Text)")).widget();
		shareText.tooltip = "Copy capacity summary to clipboard (no coordinates).";
		shareText.action = () -> StasisShareExport.copyTextSummary(mod, home);
		WButton sharePng = homeActions.add(theme.button("Share (.png)")).widget();
		sharePng.tooltip = "Copy unfolded home summary image (no coordinates).";
		sharePng.action = () -> StasisShareExport.shareHome(mod, home);
		addColoredSummary(theme, homeActions, cap);

		int brokenCount = 0;
		for (StasisChamberSlot s : mod.store().slotsForHome(home.id)) {
			if (Boolean.FALSE.equals(s.geometryOk)) brokenCount++;
		}
		if (brokenCount > 0) {
			section.add(theme.label("Broken: " + brokenCount + " (hidden - geometry failed)")).expandX();
		}

		if (slots.isEmpty()) {
			section.add(theme.label(brokenCount > 0
				? "No usable chamber slots - fix geometry or wait for re-detect."
				: "No chamber slots yet - load chunks in this home.")).expandX();
			return;
		}

		if (pearlsView) {
			fillPearlsSummary(theme, section, mod, home, slots, cols);
		} else {
			WTable table = section.add(theme.table()).expandX().widget();
			addHeaderRow(theme, table, cols);
			for (StasisChamberSlot s : slots) {
				addSlotRow(theme, table, mod, s, cols);
			}
		}
	}

	private static void addColoredSummary(GuiTheme theme, WHorizontalList row,
										  StasisStore.HomeCapacity cap) {
		Color shortGold = new Color(230, 190, 80);
		Color okMint = new Color(152, 255, 204);
		Color othersBlue = new Color(100, 140, 180);
		boolean shortfall = cap.anyAlertShort();
		WLabel accounts = row.add(theme.label(cap.quotasLine())).widget();
		accounts.color(shortfall ? shortGold : okMint);
		row.add(theme.label("    ")).expandX();
		if (!cap.slotsKnown()) {
			WLabel unk = row.add(theme.label("chambers unknown")).widget();
			unk.color(UNKNOWN_GREY);
			return;
		}
		WLabel free = row.add(theme.label("free " + cap.free() + "/" + cap.viable())).widget();
		free.color(cap.free() > 0 ? okMint : UNKNOWN_GREY);
		row.add(theme.label("    "));
		WLabel others = row.add(theme.label("others " + cap.othersOccupied())).widget();
		others.color(cap.othersOccupied() > 0 ? othersBlue : UNKNOWN_GREY);
		if (cap.chambersNeeded() > 0) {
			row.add(theme.label("    "));
			WLabel need = row.add(theme.label("need +" + cap.chambersNeeded())).widget();
			need.color(shortGold);
		}
	}

	private static void fillPearlsSummary(GuiTheme theme, WSection section, StasisChambers mod,
										  StasisHome home, List<StasisChamberSlot> slots, ColWidths cols) {
		Map<String, List<StasisChamberSlot>> byIgn = new HashMap<>();
		for (StasisChamberSlot s : slots) {
			if (s.pearlId == null || s.pearlId.isBlank()) continue; // unbound seats: Chamber Slots only
			String key = ownerGroupKey(mod, s);
			byIgn.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
		}
		if (byIgn.isEmpty()) {
			section.add(theme.label("No bound pearls in this home yet.")).expandX();
			return;
		}

		String myIgn = mod.localPlayerIgn();
		List<String> keys = new ArrayList<>(byIgn.keySet());
		keys.sort((a, b) -> {
			boolean aUnk = UNKNOWN_OWNER.equals(a);
			boolean bUnk = UNKNOWN_OWNER.equals(b);
			if (aUnk != bUnk) return aUnk ? 1 : -1;
			boolean aProf = groupIsProfileOwned(mod, byIgn.get(a));
			boolean bProf = groupIsProfileOwned(mod, byIgn.get(b));
			if (aProf != bProf) return aProf ? -1 : 1;
			boolean aMe = myIgn != null && !myIgn.isBlank() && a.equalsIgnoreCase(myIgn);
			boolean bMe = myIgn != null && !myIgn.isBlank() && b.equalsIgnoreCase(myIgn);
			if (aMe != bMe) return aMe ? -1 : 1;
			int cmp = Integer.compare(byIgn.get(b).size(), byIgn.get(a).size());
			if (cmp != 0) return cmp;
			return a.compareToIgnoreCase(b);
		});

		for (String key : keys) {
			List<StasisChamberSlot> group = byIgn.get(key);
			String label = UNKNOWN_OWNER.equals(key) ? "unknown" : key;
			String header = label + "  " + group.size() + (group.size() == 1 ? " pearl" : " pearls");
			boolean open = groupIsProfileOwned(mod, group)
				|| (myIgn != null && !UNKNOWN_OWNER.equals(key) && key.equalsIgnoreCase(myIgn));
			WSection ignSec = section.add(theme.section(header, open)).expandX().widget();
			WTable table = ignSec.add(theme.table()).expandX().widget();
			addHeaderRow(theme, table, cols);
			group.sort(Comparator
				.comparingInt((StasisChamberSlot s) -> s.number <= 0 ? Integer.MAX_VALUE : s.number)
				.thenComparingInt(s -> s.x)
				.thenComparingInt(s -> s.z));
			for (StasisChamberSlot s : group) {
				addSlotRow(theme, table, mod, s, cols);
			}
		}
	}

	private static boolean groupIsProfileOwned(StasisChambers mod, List<StasisChamberSlot> group) {
		if (group == null) return false;
		for (StasisChamberSlot s : group) {
			if (slotIsProfileOwned(mod, s)) return true;
		}
		return false;
	}

	private static boolean slotIsProfileOwned(StasisChambers mod, StasisChamberSlot s) {
		if (s == null || s.pearlId == null) return false;
		StasisPearl pearl = mod.store().findPearlById(s.pearlId).orElse(null);
		return pearl != null && mod.store().matchProfile(pearl).isPresent();
	}

	private static String ownerGroupKey(StasisChambers mod, StasisChamberSlot s) {
		StasisPearl pearl = s.pearlId != null ? mod.store().findPearlById(s.pearlId).orElse(null) : null;
		if (pearl == null) return UNKNOWN_OWNER;
		if (pearl.ownerIgn != null && !pearl.ownerIgn.isBlank()) return pearl.ownerIgn;
		if (pearl.ownerUuid != null && pearl.ownerUuid.length() >= 8) return pearl.ownerUuid.substring(0, 8);
		return UNKNOWN_OWNER;
	}

	private static List<StasisChamberSlot> usableSlots(StasisChambers mod, StasisHome home) {
		List<StasisChamberSlot> slots = new ArrayList<>();
		for (StasisChamberSlot s : mod.store().slotsForHome(home.id)) {
			if (Boolean.FALSE.equals(s.geometryOk)) continue;
			slots.add(s);
		}
		slots.sort(Comparator
			.comparingInt((StasisChamberSlot s) -> s.number <= 0 ? Integer.MAX_VALUE : s.number)
			.thenComparing((StasisChamberSlot s) -> slotStatusRank(mod, s))
			.thenComparingInt(s -> s.x)
			.thenComparingInt(s -> s.z));
		return slots;
	}

	private static void addHeaderRow(GuiTheme theme, WTable table, ColWidths cols) {
		addCell(table, theme.label("#"), cols.num);
		addCell(table, theme.label("State"), cols.state);
		addCell(table, theme.label("Owner"), cols.owner);
		addCell(table, theme.label("Type"), cols.type);
		addCell(table, theme.label("Coord"), cols.coord);
		addCell(table, theme.label("First Seen"), cols.age);
		addCell(table, theme.label(""), cols.seenGap);
		addCell(table, theme.label("Last Seen"), cols.age);
		table.row();
	}

	private static void addSlotRow(GuiTheme theme, WTable table, StasisChambers mod,
									StasisChamberSlot s, ColWidths cols) {
		String status = slotStatus(mod, s);
		addCell(table, theme.label(s.number > 0 ? "#" + s.number : "-"), cols.num);
		WLabel stateLbl = theme.label("* " + status);
		addCell(table, stateLbl, cols.state);
		stateLbl.color(stateColor(mod, status));
		if ("Broken".equals(status)) stateLbl.tooltip = BROKEN_TIP;
		addOwnerCell(theme, table, mod, s, cols.owner);
		addCell(table, theme.label(s.kindLabel()), cols.type);
		addCell(table, new WCoordReveal(s.x, s.y, s.z), cols.coord);
		addCell(table, theme.label(age(firstSeen(mod, s))), cols.age);
		addCell(table, theme.label(""), cols.seenGap);
		AgeLabel last = ageLabel(lastSeen(mod, s));
		WLabel lastLbl = theme.label(last.text());
		addCell(table, lastLbl, cols.age);
		if (last.liveNow()) lastLbl.color(NOW_GREEN);
		table.row();
	}

	private static void addCell(WTable table, WWidget w, double minW) {
		table.add(w).minWidth(minW);
	}

	private static void addOwnerCell(GuiTheme theme, WTable table, StasisChambers mod,
									 StasisChamberSlot s, double ownerW) {
		StasisPearl pearl = s.pearlId != null ? mod.store().findPearlById(s.pearlId).orElse(null) : null;
		if (pearl == null) {
			addCell(table, theme.label("-"), ownerW);
			return;
		}
		String ign;
		if (pearl.ownerIgn != null && !pearl.ownerIgn.isBlank()) ign = pearl.ownerIgn;
		else if (pearl.ownerUuid != null && pearl.ownerUuid.length() >= 8) ign = pearl.ownerUuid.substring(0, 8);
		else {
			addCell(table, theme.label("unknown"), ownerW);
			return;
		}
		boolean assumed = pearl.isAssumedOwner(mod.sessionStartMs());
		WHorizontalList row = theme.horizontalList();
		addCell(table, row, ownerW);
		row.add(new WOwnerChip(ign, WOwnerChip.colorFor(ign)));
		WLabel suf = row.add(theme.label(assumed ? " (assumed)" : " (verified)")).widget();
		suf.color(assumed ? ASSUMED_BLUE : VERIFIED_GREEN);
	}

	private static int slotStatusRank(StasisChambers mod, StasisChamberSlot s) {
		return switch (slotStatus(mod, s)) {
			case "Loaded" -> 0;
			case "Empty" -> 1;
			case "Broken" -> 2;
			default -> 3;
		};
	}

	static String slotStatus(StasisChambers mod, StasisChamberSlot s) {
		StasisPearl bound = s.pearlId != null ? mod.store().findPearlById(s.pearlId).orElse(null) : null;
		if (bound != null && bound.loaded) return "Loaded";
		if (Boolean.FALSE.equals(s.geometryOk)) return "Broken";
		MinecraftClient mc = MinecraftClient.getInstance();
		boolean chunkLoaded = mc != null && mc.world != null
			&& mc.world.getChunkManager().getWorldChunk(s.x >> 4, s.z >> 4) != null;
		if (!chunkLoaded) return "Unknown";
		return "Empty";
	}

	private static Color stateColor(StasisChambers mod, String status) {
		return switch (status) {
			case "Loaded" -> mod.loadedGuiColor();
			case "Empty" -> mod.emptyGuiColor();
			case "Broken" -> BROKEN_RED;
			default -> UNKNOWN_GREY;
		};
	}

	private static long firstSeen(StasisChambers mod, StasisChamberSlot s) {
		if (s.pearlId != null) {
			StasisPearl p = mod.store().findPearlById(s.pearlId).orElse(null);
			if (p != null && p.firstSeenMs > 0) return p.firstSeenMs;
		}
		return s.firstSeenMs;
	}

	private static long lastSeen(StasisChambers mod, StasisChamberSlot s) {
		if (s.pearlId != null) {
			StasisPearl p = mod.store().findPearlById(s.pearlId).orElse(null);
			if (p != null && p.lastSeenMs > 0) return p.lastSeenMs;
		}
		return s.lastSeenMs;
	}

	private record AgeLabel(String text, boolean liveNow) {}

	private static AgeLabel ageLabel(long epochMs) {
		if (epochMs <= 0) return new AgeLabel("-", false);
		long ago = System.currentTimeMillis() - epochMs;
		if (ago < 1000) return new AgeLabel("now", true);
		return new AgeLabel(age(epochMs), false);
	}

	private static String age(long epochMs) {
		if (epochMs <= 0) return "-";
		long ago = System.currentTimeMillis() - epochMs;
		if (ago < 1000) return "now";
		if (ago < 60_000) return (ago / 1000) + "s";
		if (ago < 3_600_000) return (ago / 60_000) + "m";
		if (ago < 86_400_000) return (ago / 3_600_000) + "h";
		return (ago / 86_400_000) + "d";
	}

	/** Rigid column widths shared across every home table. */
	private record ColWidths(
		double num, double state, double owner, double type, double coord, double age, double seenGap
	) {
		static ColWidths measure(GuiTheme theme) {
			double pad = theme.scale(6);
			double typePad = theme.scale(14);
			return new ColWidths(
				theme.textWidth("#99") + pad,
				theme.textWidth("* Loaded") + pad,
				theme.textWidth("WWWWWWWWWWWWWWWW (verified)") + pad,
				theme.textWidth("water") + pad + typePad,
				theme.textWidth(WCoordReveal.maskDigits("-30000000 99999 -30000000")) + pad,
				theme.textWidth("999d") + pad,
				theme.scale(18)
			);
		}
	}

	public static class StasisAddHomeScreen extends meteordevelopment.meteorclient.gui.WindowScreen {
		private final StasisChambers mod;
		private meteordevelopment.meteorclient.gui.widgets.input.WTextBox nameBox;
		private meteordevelopment.meteorclient.gui.widgets.input.WIntEdit pearlsEdit;
		private meteordevelopment.meteorclient.gui.widgets.input.WIntEdit radiusEdit;

		public StasisAddHomeScreen(StasisChambers mod) {
			super(GuiThemes.get(), "Add Stasis Home");
			this.mod = mod;
		}

		@Override
		public void initWidgets() {
			WTable t = add(theme.table()).expandX().widget();
			t.add(theme.label("Name")).expandX();
			nameBox = t.add(theme.textBox("")).expandX().widget();
			t.row();
			t.add(theme.label("Pearls required")).expandX();
			pearlsEdit = t.add(theme.intEdit(mod.defaultPearls(), 1, 8, true)).expandX().widget();
			t.row();
			t.add(theme.label("Radius (chunks)")).expandX();
			radiusEdit = t.add(theme.intEdit(mod.homeRadiusChunks(), 1, 4, true)).expandX().widget();
			t.row();
			WButton ok = t.add(theme.button("Create")).expandX().widget();
			ok.action = () -> {
				try {
					String name = nameBox != null ? nameBox.get() : "";
					int pearls = pearlsEdit != null ? pearlsEdit.get() : mod.defaultPearls();
					int radius = radiusEdit != null ? radiusEdit.get() : mod.homeRadiusChunks();
					StasisHome h = mod.addHomeHere(name, radius, pearls);
					StasisChat.info("Stasis home " + h.name + " (" + h.pearlsRequired + " pearls)");
					PanelReload.reloadModuleScreen();
					close();
				} catch (Exception e) {
					StasisChat.warn("Add home failed: " + e.getClass().getSimpleName());
				}
			};
		}
	}
}
