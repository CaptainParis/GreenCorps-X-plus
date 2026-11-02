package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.backup.SnapshotIndex;
import dev.greencorps.backup.StoreGc;
import dev.greencorps.backup.StoreStats;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.commands.TimeSelector;
import dev.greencorps.export.PlayableSavePublisher;
import dev.greencorps.modules.BaseBackup;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Full manage screen: action matrix, a home / snapshot picker (dropdown with ◀ ▶), then an overview column next
 * to the wide timeline / export column, and tools.
 */
public class BaseBackupScreen extends WindowScreen {
	private static final int TIMELINE_PAGE_SIZE = 10;

	private final BaseBackup mod;
	private String selectedHomeId;
	private SnapshotIndex.Entry selectedEntry;
	/** Survives widget reload so scrub/list stay on the same snapshot. */
	private String selectedEntryFilename;
	/** 0 = first successful + 9 most recent; 1+ = chronological middle pages. */
	private int timelinePage;

	public BaseBackupScreen(BaseBackup mod) {
		this(mod, null);
	}

	public BaseBackupScreen(BaseBackup mod, String homeId) {
		super(GuiThemes.get(), "Time Machine");
		this.mod = mod;
		this.selectedHomeId = homeId;
		// Return to ModuleScreen (or other WidgetScreen) on close — never clear parent.
		MinecraftClient mc = MinecraftClient.getInstance();
		Screen cur = mc != null ? mc.currentScreen : null;
		if (cur instanceof WidgetScreen) {
			this.parent = cur;
		}
	}

	/** One entry of the home / snapshot picker. */
	private record Choice(Home home) {
		@Override
		public String toString() {
			return (home.isSnapshot() ? "Snapshot: " : "Home: ") + displayName(home);
		}
	}

	@Override
	public void initWidgets() {
		MinecraftClient mc = MinecraftClient.getInstance();
		double sw = mc != null ? mc.getWindow().getScaledWidth() : 600;
		// Wide enough for overview + timeline side by side.
		window.minWidth = Math.min(sw * 0.86, Math.max(BbGrid.gridW(theme) + theme.scale(240), sw * 0.62));

		if (mod.homeStore() == null) {
			add(theme.label("Activate Time Machine first."));
			return;
		}

		WVerticalList shell = add(theme.verticalList()).expandX().widget();
		shell.spacing = BbGrid.gap(theme);
		BaseBackupPanel.appendActions(shell, theme, mod);

		Optional<Home> home = resolveSelected();
		shell.add(theme.horizontalSeparator("Homes & Snapshots")).expandX();
		if (home.isEmpty()) {
			shell.add(theme.label("No homes or snapshots yet.")).expandX();
			return;
		}
		Home h = home.get();
		selectedHomeId = h.id;
		shell.add(buildPicker(h)).expandX();

		if (h.isSnapshot()) {
			fillSnapshotDetail(shell, h);
		} else {
			fillHomeDetail(shell, h);
		}
	}

	/** Home / snapshot picker: one dropdown with ◀ ▶, so the details below get the full width. */
	private WTable buildPicker(Home selected) {
		List<Home> homes = new ArrayList<>();
		List<Home> snapshots = new ArrayList<>();
		for (Home h : mod.homeStore().homes()) (h.isSnapshot() ? snapshots : homes).add(h);
		homes.sort(Comparator.comparing(BaseBackupScreen::displayName, String.CASE_INSENSITIVE_ORDER));
		snapshots.sort(Comparator.comparing(BaseBackupScreen::displayName, String.CASE_INSENSITIVE_ORDER));
		List<Choice> all = new ArrayList<>();
		homes.forEach(h -> all.add(new Choice(h)));
		snapshots.forEach(h -> all.add(new Choice(h)));
		Choice[] choices = all.toArray(new Choice[0]);
		int at = 0;
		for (int i = 0; i < choices.length; i++) if (choices[i].home().id.equals(selected.id)) at = i;
		final int current = at;

		WTable bar = theme.table();
		BbGrid.applySpacing(bar, theme);
		WButton prev = bar.add(theme.button("◀")).widget();
		prev.tooltip = "Previous home / snapshot";
		prev.action = () -> select(choices[(current - 1 + choices.length) % choices.length].home());
		WDropdown<Choice> pick = bar.add(theme.dropdown(choices, choices[current])).expandX().widget();
		pick.tooltip = "Pick a home or snapshot";
		pick.action = () -> select(pick.get().home());
		WButton next = bar.add(theme.button("▶")).widget();
		next.tooltip = "Next home / snapshot";
		next.action = () -> select(choices[(current + 1) % choices.length].home());
		bar.add(theme.label(homes.size() + (homes.size() == 1 ? " home · " : " homes · ")
			+ snapshots.size() + (snapshots.size() == 1 ? " snapshot" : " snapshots")));
		bar.row();
		return bar;
	}

	private void select(Home h) {
		if (h.id.equals(selectedHomeId)) return;
		selectedHomeId = h.id;
		selectedEntry = null;
		selectedEntryFilename = null;
		timelinePage = 0;
		reload();
	}

	/** Two columns: overview (narrow) left, the main content (wide) right. */
	private WVerticalList[] columns(WVerticalList parent) {
		WTable cols = parent.add(theme.table()).expandX().widget();
		cols.horizontalSpacing = theme.scale(14);
		WVerticalList left = theme.verticalList();
		left.spacing = BbGrid.gap(theme);
		WVerticalList right = theme.verticalList();
		right.spacing = BbGrid.gap(theme);
		cols.add(left).top().minWidth(theme.scale(190));
		cols.add(right).expandX().top();
		cols.row();
		return new WVerticalList[] { left, right };
	}

	/** Label / value rows for the overview. */
	private WTable facts(WSection section) {
		WTable t = section.add(theme.table()).expandX().widget();
		t.horizontalSpacing = theme.scale(10);
		t.verticalSpacing = theme.scale(3);
		return t;
	}

	private void fact(WTable t, String label, String value) {
		t.add(theme.label(label));
		t.add(theme.label(value)).expandX();
		t.row();
	}

	private Optional<Home> resolveSelected() {
		if (selectedHomeId != null) {
			Optional<Home> byId = mod.homeStore().findById(selectedHomeId);
			if (byId.isPresent()) return byId;
		}
		return mod.homeStore().homes().stream()
			.sorted(Comparator.comparing((Home h) -> h.isSnapshot())
				.thenComparing(BaseBackupScreen::displayName, String.CASE_INSENSITIVE_ORDER))
			.findFirst();
	}

	private void fillHomeDetail(WVerticalList shell, Home h) {
		WVerticalList[] cols = columns(shell);
		WVerticalList detail = cols[0];
		WSection homeSec = detail.add(theme.section("Overview", true)).expandX().widget();
		WTable info = facts(homeSec);
		fact(info, "Home", displayName(h));
		fact(info, "Center chunk", h.centerChunkX + ", " + h.centerChunkZ);
		fact(info, "Radius", h.radiusChunks + " chunks");
		fact(info, "Coverage", (int) mod.coverage(h) + "%");
		fact(info, "Backups", Integer.toString(mod.snapshotCount(h)));
		fact(info, "Health", mod.engine().health().label(mod.engine().degradedSections()));
		mod.stats(h).ifPresent(s -> {
			fact(info, "Store", StoreStats.formatBytes(s.storeBytes()));
			fact(info, "Per commit", StoreStats.formatBytes((long) s.meanBytesPerCommit()));
			fact(info, "~30 days", StoreStats.formatBytes(s.project30d()));
		});

		WSection playableSec = detail.add(theme.section("Playable", true)).expandX().widget();
		WHorizontalList playRow = playableSec.add(theme.horizontalList()).expandX().widget();
		playRow.spacing = BbGrid.gap(theme);
		WCheckbox playable = playRow.add(theme.checkbox(h.keepPlayableSave)).widget();
		playable.tooltip = "One Singleplayer world under saves/ (needs auto-playable-latest). Overwrites latest only.";
		playRow.add(theme.label("Playable → saves/"
			+ PlayableSavePublisher.autoFolderName(h))).expandX();
		playable.action = () -> {
			h.keepPlayableSave = playable.checked;
			try { mod.homeStore().update(h); } catch (Exception e) {
				HomeChat.warn(e.toString());
			}
		};
		if (!mod.keepPlayableSaves()) {
			playableSec.add(theme.label("Master auto-playable-latest is OFF")).expandX();
		}

		WSection backups = cols[1].add(theme.section("Backups", true)).expandX().widget();
		try {
			SnapshotIndex idx = new SnapshotIndex(mod.engine().refsDir(), h.id);
			List<SnapshotIndex.Entry> entries = idx.entries();
			if (!entries.isEmpty()) {
				int selectedIdx = resolveSelectedIndex(entries);
				String selectedWhen = TimeSelector.format(selectedEntry.epochMs);

				backups.add(theme.label("Export / Pin / Embed use the highlighted snapshot below."))
					.expandX();
				backups.add(theme.label("Selected: " + selectedWhen
					+ "  (#" + (selectedIdx + 1) + " of " + entries.size() + ")")).expandX();

				List<Integer> visible = timelineVisibleIndices(entries, timelinePage);
				int pageCount = timelinePageCount(entries.size());
				if (timelinePage >= pageCount) timelinePage = Math.max(0, pageCount - 1);

				backups.add(theme.label(timelinePageLabel(timelinePage, pageCount, entries.size())))
					.expandX();

				WTable tl = theme.table();
				BbGrid.applySpacing(tl, theme);
				tl.add(theme.label("#"));
				tl.add(theme.label("When")).expandX();
				tl.add(theme.label("Delta"));
				tl.add(theme.label("Badge"));
				tl.row();
				for (int i : visible) {
					SnapshotIndex.Entry e = entries.get(i);
					final int fi = i;
					boolean sel = i == selectedIdx;
					String numLabel = sel ? "> #" + (i + 1) : "#" + (i + 1);
					WButton num = tl.add(theme.button(numLabel)).widget();
					num.tooltip = sel ? "Selected snapshot" : "Select snapshot #" + (i + 1);
					num.action = () -> selectEntry(entries, fi);
					WButton whenBtn = tl.add(theme.button(TimeSelector.format(e.epochMs))).expandX().widget();
					whenBtn.tooltip = num.tooltip;
					whenBtn.action = () -> selectEntry(entries, fi);
					tl.add(theme.label(StoreStats.formatBytes(e.bytesAdded)));
					String badge = (e.pinned ? "P" : "") + (e.degraded ? "D" : "")
						+ (e.sealed ? "S" : "") + (e.verified ? "V" : "");
					if (badge.isEmpty()) badge = "-";
					tl.add(theme.label(sel ? "[" + badge + "]" : badge));
					tl.row();
				}
				backups.add(tl).expandX();

				if (pageCount > 1) {
					WHorizontalList pages = backups.add(theme.horizontalList()).expandX().widget();
					pages.spacing = BbGrid.gap(theme);
					WButton prev = pages.add(theme.button("Prev")).expandX().widget();
					prev.tooltip = "Older / middle history";
					prev.action = () -> {
						if (timelinePage > 0) {
							timelinePage--;
							reload();
						}
					};
					pages.add(theme.label("Page " + (timelinePage + 1) + " / " + pageCount)).expandX();
					WButton next = pages.add(theme.button("Next")).expandX().widget();
					next.tooltip = "More history";
					next.action = () -> {
						if (timelinePage + 1 < pageCount) {
							timelinePage++;
							reload();
						}
					};
				}

				double half = BbGrid.cellW(theme) * 1.5;
				WTable row1 = backups.add(theme.table()).expandX().widget();
				BbGrid.applySpacing(row1, theme);
				WButton export = row1.add(theme.button("Export Selected")).expandX().minWidth(half).widget();
				export.tooltip = "Export " + selectedWhen + " to saves/. Latest + Playable → one stable folder; otherwise a named archive world.";
				export.action = () -> {
					try {
						boolean isLatest = selectedEntry == null
							|| idx.latest().map(e -> e.filename.equals(selectedEntry.filename)).orElse(false);
						String whenArg = isLatest || selectedEntry == null ? "latest"
							: java.time.Instant.ofEpochMilli(selectedEntry.epochMs).toString();
						var path = mod.exportHome(h, whenArg, null);
						if (mod.shouldPublishPlayable(h) && isLatest) {
							HomeChat.info("Playable export queued → " + path.getFileName());
						} else {
							HomeChat.info("Exported " + path.getFileName());
						}
					} catch (Exception ex) {
						HomeChat.warn("Export: " + ex);
					}
				};
				WButton pin = row1.add(theme.button("Pin/Unpin Selected")).expandX().minWidth(half).widget();
				pin.tooltip = "Pin/unpin " + selectedWhen
					+ ". Pinned snapshots are kept forever: retention pruning and GC never delete them.";
				pin.action = () -> {
					if (selectedEntry == null) return;
					try {
						selectedEntry.pinned = !selectedEntry.pinned;
						idx.update(selectedEntry);
						var m = dev.greencorps.backup.SnapshotManifest.read(
							mod.engine().manifestPath(h.id, selectedEntry.filename));
						m.pinned = selectedEntry.pinned;
						m.writeAtomic(mod.engine().manifestPath(h.id, selectedEntry.filename));
						reload();
					} catch (Exception ex) {
						HomeChat.warn(ex.toString());
					}
				};
				row1.row();
			} else {
				selectedEntry = null;
				selectedEntryFilename = null;
				backups.add(theme.label("No snapshots yet — stand in the home and wait for a commit."))
					.expandX();
			}
		} catch (Exception e) {
			backups.add(theme.label("Timeline error: " + e)).expandX();
		}

		WSection tools = shell.add(theme.section("Tools", true)).expandX().widget();
		double third = BbGrid.cellW(theme);
		WTable row2 = tools.add(theme.table()).expandX().widget();
		BbGrid.applySpacing(row2, theme);

		WButton verify = row2.add(theme.button("Verify")).expandX().minWidth(third).widget();
		verify.tooltip = "Checks every stored chunk blob against its hash and every manifest reference, then runs a test export in the background. Nothing is changed.";
		verify.action = () -> mod.runVerifyAsync(h);

		WButton gcdry = row2.add(theme.button("GC Dry-Run")).expandX().minWidth(third).widget();
		gcdry.tooltip = "Shows how much space unreferenced blobs would free. Deletes nothing.";
		gcdry.action = () -> {
			StoreGc.Result r = mod.runGc(true);
			HomeChat.info("GC dry-run: " + r.message() + " free ~"
				+ StoreStats.formatBytes(r.wouldFreeBytes()));
		};

		WButton edit = row2.add(theme.button("Edit Home")).expandX().minWidth(third).widget();
		edit.action = () -> MinecraftClient.getInstance().setScreen(new HomeEditScreen(mod, h));

		WButton embed = row2.add(theme.button("Export Embedded…")).expandX().minWidth(third).widget();
		String embedTip = selectedEntry != null
			? "Embed export of " + TimeSelector.format(selectedEntry.epochMs)
				+ " (yaw-scramble + seed height ring, bb-embed-*)."
			: "Yaw-scramble + seed height ring (bb-embed-*). Not used on disconnect / Create Backup World.";
		embed.tooltip = embedTip;
		embed.action = () -> MinecraftClient.getInstance().setScreen(
			new EmbeddedExportScreen(mod, h, selectedEntry));
		row2.row();
	}

	/** Pick timeline entry and rebuild so scrub, label, and list highlight stay in sync. */
	private void selectEntry(List<SnapshotIndex.Entry> entries, int index) {
		if (entries == null || entries.isEmpty()) return;
		int i = Math.max(0, Math.min(index, entries.size() - 1));
		SnapshotIndex.Entry e = entries.get(i);
		if (selectedEntryFilename != null && selectedEntryFilename.equals(e.filename)) {
			selectedEntry = e;
			return;
		}
		selectedEntry = e;
		selectedEntryFilename = e.filename;
		reload();
	}

	/** Restore selection from filename, or default to latest. Returns 0-based index. */
	private int resolveSelectedIndex(List<SnapshotIndex.Entry> entries) {
		if (selectedEntryFilename != null) {
			for (int i = 0; i < entries.size(); i++) {
				if (selectedEntryFilename.equals(entries.get(i).filename)) {
					selectedEntry = entries.get(i);
					return i;
				}
			}
		}
		int latest = entries.size() - 1;
		selectedEntry = entries.get(latest);
		selectedEntryFilename = selectedEntry.filename;
		return latest;
	}

	/**
	 * Page 0: first successful save + up to 9 most recent (deduped).
	 * Later pages: chronological middle chunks of {@link #TIMELINE_PAGE_SIZE}.
	 */
	private static List<Integer> timelineVisibleIndices(List<SnapshotIndex.Entry> entries, int page) {
		int n = entries.size();
		List<Integer> out = new ArrayList<>();
		if (n == 0) return out;
		int firstOk = firstSuccessfulIndex(entries);
		if (page <= 0 || n <= TIMELINE_PAGE_SIZE) {
			Set<Integer> seen = new LinkedHashSet<>();
			seen.add(firstOk);
			int recent = Math.min(9, n - 1);
			for (int i = n - recent; i < n; i++) {
				if (i >= 0) seen.add(i);
			}
			if (n <= TIMELINE_PAGE_SIZE) {
				seen.clear();
				for (int i = 0; i < n; i++) seen.add(i);
			}
			out.addAll(seen);
			out.sort(Integer::compareTo);
			return out;
		}
		// Middle history excluding the always-shown first + last-9 window.
		List<Integer> middle = new ArrayList<>();
		int recentStart = Math.max(0, n - 9);
		for (int i = 0; i < n; i++) {
			if (i == firstOk) continue;
			if (i >= recentStart) continue;
			middle.add(i);
		}
		int midPage = page - 1;
		int from = midPage * TIMELINE_PAGE_SIZE;
		if (from >= middle.size()) {
			from = Math.max(0, middle.size() - TIMELINE_PAGE_SIZE);
		}
		int to = Math.min(middle.size(), from + TIMELINE_PAGE_SIZE);
		for (int i = from; i < to; i++) out.add(middle.get(i));
		return out;
	}

	private static int timelinePageCount(int n) {
		if (n <= TIMELINE_PAGE_SIZE) return 1;
		int middle = Math.max(0, n - Math.min(n, 10));
		if (middle <= 0) return 1;
		return 1 + (middle + TIMELINE_PAGE_SIZE - 1) / TIMELINE_PAGE_SIZE;
	}

	private static String timelinePageLabel(int page, int pageCount, int total) {
		if (page <= 0) {
			return "Showing first save + recent (" + total + " total"
				+ (pageCount > 1 ? "; more via Next" : "") + ")";
		}
		return "Showing middle history (page " + (page + 1) + " of " + pageCount + ", " + total + " total)";
	}

	/** Oldest non-degraded entry, else index 0. */
	private static int firstSuccessfulIndex(List<SnapshotIndex.Entry> entries) {
		for (int i = 0; i < entries.size(); i++) {
			SnapshotIndex.Entry e = entries.get(i);
			if (e != null && !e.degraded) return i;
		}
		return 0;
	}

	private void fillSnapshotDetail(WVerticalList shell, Home h) {
		WVerticalList[] cols = columns(shell);
		WSection snapSec = cols[0].add(theme.section("Overview", true)).expandX().widget();
		WTable info = facts(snapSec);
		fact(info, "Snapshot", displayName(h));
		fact(info, "Center chunk", h.centerChunkX + ", " + h.centerChunkZ);
		fact(info, "Radius", h.radiusChunks + " chunks");

		int loaded = mod.countLoadedChunks(h);
		int expected = h.expectedChunks();
		String state;
		if (h.armed && loaded < expected) {
			state = "waiting for chunks (" + loaded + "/" + expected + ")";
		} else if (h.armed) {
			state = "ready — finalizing";
		} else {
			state = "done";
		}
		fact(info, "State", state);
		fact(info, "Coverage", (int) mod.coverage(h) + "%");
		fact(info, "Commits", Integer.toString(mod.snapshotCount(h)));

		long capturedAt = 0;
		if (mod.engine() != null) {
			capturedAt = mod.engine().lastCommitAt(h.id);
		}
		fact(info, "Captured", capturedAt <= 0 ? "never" : TimeSelector.format(capturedAt));
		mod.stats(h).ifPresent(s -> fact(info, "Store", StoreStats.formatBytes(s.storeBytes())));
		Optional<Path> latest = PlayableSavePublisher.latestFolder(h);
		fact(info, "Last export", latest.map(p -> p.getFileName().toString()).orElse("(none)"));

		WSection exportSec = cols[1].add(theme.section("Export", true)).expandX().widget();

		double half = BbGrid.cellW(theme) * 1.5;
		WTable actions = exportSec.add(theme.table()).expandX().widget();
		BbGrid.applySpacing(actions, theme);

		WButton edit = actions.add(theme.button("Edit Snapshot")).expandX().minWidth(half).widget();
		edit.tooltip = "Name, radius, armed, and playable save — same fields as a home.";
		edit.action = () -> MinecraftClient.getInstance().setScreen(new HomeEditScreen(mod, h));
		actions.row();

		WButton open = actions.add(theme.button("Open In Folder")).expandX().minWidth(half).widget();
		open.tooltip = "Reveal this home's playable/archive folder under saves/ in the system file manager.";
		open.action = () -> openInFolder(h);

		WButton reexport = actions.add(theme.button("Re-Export")).expandX().minWidth(half).widget();
		reexport.tooltip = "Export the latest captured snapshot again to a new timestamped folder.";
		reexport.action = () -> {
			try {
				var path = mod.exportHome(h, "latest", null);
				HomeChat.info("Re-exported → " + path.getFileName());
				reload();
			} catch (Exception ex) {
				HomeChat.warn("Re-Export: " + ex);
			}
		};
		actions.row();

		WSection tools = cols[1].add(theme.section("Tools", true)).expandX().widget();
		WTable embedRow = tools.add(theme.table()).expandX().widget();
		BbGrid.applySpacing(embedRow, theme);
		WButton embed = embedRow.add(theme.button("Export Embedded…")).expandX().widget();
		embed.tooltip = "Yaw-scramble + seed height ring (bb-embed-*).";
		embed.action = () -> MinecraftClient.getInstance().setScreen(
			new EmbeddedExportScreen(mod, h, null));
		embedRow.row();
	}

	private void openInFolder(Home h) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		Path saves = PlayableSavePublisher.savesRoot(mc);
		Path target = PlayableSavePublisher.latestFolder(h).orElse(saves);
		try {
			if (!Files.exists(target)) {
				Files.createDirectories(saves);
				target = saves;
			}
			Util.getOperatingSystem().open(target.toFile());
		} catch (Exception e) {
			HomeChat.warn("Open folder failed: " + e);
		}
	}

	private static String displayName(Home h) {
		String shown = XaeroWaypoints.displayName(h.name);
		if (shown == null || shown.isBlank()) return h.name;
		return shown;
	}
}
