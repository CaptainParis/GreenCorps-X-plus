package dev.greencorps.gui;

import dev.greencorps.modules.SignSearch;
import dev.greencorps.signs.SignFilter;
import dev.greencorps.signs.SignFlags;
import dev.greencorps.signs.SignHit;
import dev.greencorps.signs.SignLibrary;
import dev.greencorps.signs.SignPack;
import dev.greencorps.signs.SignSearchResult;
import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.math.BlockPos;
import xaero.hud.minimap.waypoint.WaypointColor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sign text search. Each row shows the writing and one location.
 * Coordinates use the same hash mask as Stasis Monitor.
 */
public class SignSearchScreen extends WindowScreen {
	private static final int MAX_GROUPS = 80;
	private static final int SPAM_AUTHOR_LIMIT = 10000;
	private static final String HINT_A = "Words combine. -word excludes. \"exact phrase\"";
	private static final String HINT_B = "author:name | flag:economy | year:2016..2019";

	private static final String[] CHIP_LABELS = {
		"Defense", "Abandoned", "Functional", "Economy", "Coords",
		"Dated", "Signed", "Colored", "Legacy", "No spam", "Hide offensive"
	};
	private static final int[] CHIP_FLAGS = {
		SignFlags.DEFENSE,
		SignFlags.ABANDONED,
		SignFlags.FUNCTIONAL,
		SignFlags.ECONOMY,
		SignFlags.COORD,
		SignFlags.DATED,
		SignFlags.SIGNATURE,
		SignFlags.COLORED,
		SignFlags.LEGACY,
		0,
		0
	};
	private static final int CHIP_NO_SPAM = 9;
	private static final int CHIP_HIDE_OFFENSIVE = 10;
	/** First filter row has 6 chips; second has the rest. */
	private static final int CHIP_ROW_SPLIT = 6;

	private final SignSearch mod;
	private final int[] chipState = new int[CHIP_LABELS.length];
	private final WAccentButton[] chipButtons = new WAccentButton[CHIP_LABELS.length];
	private final AtomicInteger generation = new AtomicInteger();

	private WTextBox search;
	private WStatusLine status;
	private WView listView;
	private WAccentButton[] dimButtons;
	private SignLibrary.Dim dimension = SignLibrary.Dim.OVERWORLD;
	private volatile long debounceUntil;

	public SignSearchScreen(SignSearch mod) {
		super(GuiThemes.get(), "SignSearch (WDL)");
		this.mod = mod;
		chipState[CHIP_HIDE_OFFENSIVE] = 1;
	}

	@Override
	public void initWidgets() {
		MinecraftClient mc = MinecraftClient.getInstance();
		double sw = mc != null ? mc.getWindow().getScaledWidth() : 480;
		double sh = mc != null ? mc.getWindow().getScaledHeight() : 320;
		double gap = BbGrid.gap(theme);
		// Wide enough for five equal chip columns to show "Hide offensive: on" fully.
		window.minWidth = Math.max(sw * 0.78, theme.scale(640));

		WLabel source = add(theme.label(SignSearch.SOURCE)).expandX().widget();
		source.tooltip = "Sign writing and coordinates from that world download. Not a live chunk scan.";

		add(new WSectionRule()).expandX();

		search = add(theme.textBox("", "Search signs")).expandX().widget();
		search.action = this::scheduleSearch;
		add(theme.label(HINT_A)).expandX();
		add(theme.label(HINT_B)).expandX();

		add(new WSectionRule()).expandX();

		WHorizontalList dims = add(theme.horizontalList()).expandX().widget();
		dims.spacing = gap;
		dimButtons = new WAccentButton[SignLibrary.Dim.values().length];
		for (SignLibrary.Dim dim : SignLibrary.Dim.values()) {
			WAccentButton button = dims.add(new WAccentButton(dimLabel(dim))).expandX().widget();
			button.action = () -> selectDim(dim);
			dimButtons[dim.ordinal()] = button;
		}

		add(new WSectionRule()).expandX();

		// Root rows (same expand path as dims) so chips fill the window width.
		WHorizontalList chipsA = add(theme.horizontalList()).expandX().widget();
		chipsA.spacing = gap;
		WHorizontalList chipsB = add(theme.horizontalList()).expandX().widget();
		chipsB.spacing = gap;
		for (int i = 0; i < CHIP_LABELS.length; i++) {
			WHorizontalList row = i < CHIP_ROW_SPLIT ? chipsA : chipsB;
			int index = i;
			WAccentButton chip = row.add(new WAccentButton(chipLabel(i))).expandX().widget();
			chip.action = () -> cycleChip(index);
			chipButtons[i] = chip;
		}
		refreshChipLabels();

		add(new WSectionRule()).expandX();

		status = add(new WStatusLine()).expandX().widget();
		status.set(idleStatus());

		add(new WSectionRule()).expandX();

		listView = add(theme.view()).expandX().widget();
		listView.maxHeight = Math.max(theme.scale(120), sh * 0.66);
		listView.scrollOnlyWhenMouseOver = true;
		showIdle();

		WButton close = add(theme.button("Close")).expandX().widget();
		close.action = this::close;
	}

	@Override
	protected void onClosed() {
		generation.incrementAndGet();
		mod.onSearchClosed();
	}

	private void selectDim(SignLibrary.Dim dim) {
		dimension = dim;
		refreshChipLabels();
		scheduleSearch();
	}

	private void cycleChip(int index) {
		if (CHIP_FLAGS[index] == 0) {
			chipState[index] = chipState[index] == 0 ? 1 : 0;
		} else if (Screen.hasShiftDown()) {
			chipState[index] = chipState[index] == 2 ? 0 : 2;
		} else {
			chipState[index] = chipState[index] == 1 ? 0 : 1;
		}
		refreshChipLabels();
		scheduleSearch();
	}

	private void refreshChipLabels() {
		for (int i = 0; i < chipButtons.length; i++) {
			if (chipButtons[i] == null) continue;
			chipButtons[i].set(chipLabel(i));
			chipButtons[i].setSelected(chipState[i] != 0);
		}
		if (dimButtons == null) return;
		for (SignLibrary.Dim dim : SignLibrary.Dim.values()) {
			WAccentButton button = dimButtons[dim.ordinal()];
			if (button == null) continue;
			button.set(dimLabel(dim));
			button.setSelected(dim == dimension);
		}
	}

	private String dimLabel(SignLibrary.Dim dim) {
		String have = SignLibrary.get().has(dim) ? "" : " (none)";
		return dim.label + have;
	}

	private String chipLabel(int index) {
		int state = chipState[index];
		if (CHIP_FLAGS[index] == 0) {
			return CHIP_LABELS[index] + (state == 0 ? "" : ": on");
		}
		if (state == 1) return "+ " + CHIP_LABELS[index];
		if (state == 2) return "- " + CHIP_LABELS[index];
		return CHIP_LABELS[index];
	}

	private SignFilter filter() {
		int require = 0;
		int exclude = 0;
		for (int i = 0; i < CHIP_FLAGS.length; i++) {
			if (CHIP_FLAGS[i] == 0) continue;
			if (chipState[i] == 1) require |= CHIP_FLAGS[i];
			else if (chipState[i] == 2) exclude |= CHIP_FLAGS[i];
		}
		return new SignFilter(
			require,
			exclude,
			chipState[CHIP_HIDE_OFFENSIVE] != 0,
			chipState[CHIP_NO_SPAM] != 0 ? SPAM_AUTHOR_LIMIT : 0,
			"",
			Integer.MIN_VALUE,
			Integer.MAX_VALUE
		);
	}

	private void scheduleSearch() {
		long due = System.currentTimeMillis() + 300L;
		debounceUntil = due;
		int gen = generation.incrementAndGet();
		setStatus("Searching...");
		String query = search == null || search.get() == null ? "" : search.get();
		SignFilter snapshot = filter();
		SignLibrary.Dim dim = dimension;
		mod.executor().execute(() -> {
			long wait;
			while ((wait = debounceUntil - System.currentTimeMillis()) > 0) {
				if (gen != generation.get()) return;
				try {
					Thread.sleep(Math.min(wait, 300L));
				} catch (InterruptedException e) {
					return;
				}
			}
			if (gen != generation.get()) return;
			runSearch(gen, query, snapshot, dim);
		});
	}

	private void runSearch(int gen, String query, SignFilter snapshot, SignLibrary.Dim dim) {
		SignSearchResult result;
		String fail = null;
		try {
			SignPack pack = SignLibrary.get().open(dim);
			if (pack == null) {
				fail = SignLibrary.get().failure(dim);
				result = SignSearchResult.empty();
			} else {
				result = pack.search(query, MAX_GROUPS, snapshot);
			}
		} catch (RuntimeException e) {
			result = new SignSearchResult(0, 0, java.util.List.of(), "Search failed.");
		}
		SignSearchResult published = result;
		String publishedFail = fail;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		client.execute(() -> {
			if (gen != generation.get()) return;
			show(published, publishedFail, dim);
		});
	}

	private void show(SignSearchResult result, String openFailure, SignLibrary.Dim dim) {
		listView.clear();
		if (openFailure != null) {
			setStatus("Could not read " + dim.file + ".");
			listView.add(theme.label("The sign pack in config/greencorps/sign-search/ could not be opened.")).expandX();
			return;
		}
		if (!SignLibrary.get().has(dim)) {
			setStatus("No " + dim.file + ".");
			listView.add(theme.label(
				"Add " + dim.file + " under config/greencorps/sign-search/. Source: " + SignSearch.SOURCE + "."
			)).expandX();
			return;
		}
		if (result.error() != null) {
			setStatus(result.error());
			return;
		}
		if (search == null || search.get() == null || search.get().isBlank()) {
			setStatus(idleStatus());
			showIdle();
			return;
		}
		if (result.groups().isEmpty()) {
			setStatus("No matches.");
			listView.add(theme.label("Nothing in this pack matched.")).expandX();
			return;
		}
		int shown = result.groups().size();
		String more = result.uniqueGroups() > shown
			? " | showing " + shown + " of " + result.uniqueGroups()
			: "";
		setStatus(result.uniqueGroups() + " unique | " + result.totalHits() + " signs" + more);
		WVerticalList rows = listView.add(theme.verticalList()).expandX().widget();
		rows.spacing = BbGrid.gap(theme);
		for (SignHit hit : result.groups()) {
			addCard(rows, hit);
		}
	}

	private void showIdle() {
		listView.clear();
		listView.add(theme.label("Type a word or author:name. A blank search is not run.")).expandX();
	}

	private void addCard(WVerticalList rows, SignHit hit) {
		double pad = theme.scale(8);
		double gap = BbGrid.gap(theme);
		WSignCard card = rows.add(new WSignCard()).expandX().widget();
		card.spacing = 0;

		String shown = oneLine(hit.text());
		WLabel text = card.add(theme.label(shown)).expandX()
			.padHorizontal(pad).padTop(pad).padBottom(gap / 2).widget();
		text.tooltip = scrub(hit.text());

		card.add(new WRowRule()).expandX()
			.padHorizontal(pad).padTop(gap / 2).padBottom(gap / 2);

		WHorizontalList meta = card.add(theme.horizontalList()).expandX()
			.padHorizontal(pad).padTop(gap / 2).padBottom(pad).widget();
		meta.spacing = gap;

		WCoordReveal coords = meta.add(new WCoordReveal(hit.x(), hit.y(), hit.z())).widget();

		String date = hit.dateLabel();
		if (date != null && !date.isBlank()) {
			meta.add(theme.label("| " + date));
		}
		String author = hit.author();
		if (author != null && !author.isBlank()) {
			meta.add(theme.label("| " + scrub(author)));
		}
		if (hit.distinctAuthors() > 1) {
			meta.add(theme.label("| " + hit.distinctAuthors() + " authors"));
		}

		WStatusLine copies = meta.add(new WStatusLine()).widget();
		copies.set(hit.occurrences() + " x");

		meta.add(theme.label("")).expandX();
		WButton copy = meta.add(theme.button("Copy")).widget();
		copy.tooltip = "Copy coordinates to clipboard";
		copy.action = () -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client != null && client.keyboard != null) {
				client.keyboard.setClipboard(coords.plain());
			}
		};
		WButton pin = meta.add(theme.button("Waypoint")).widget();
		pin.tooltip = "Save an Xaero waypoint at these coordinates";
		pin.action = () -> saveWaypoint(hit);
	}

	private void saveWaypoint(SignHit hit) {
		String body = XaeroWaypoints.displayName(oneLine(hit.text()));
		if (body.length() > 32) body = body.substring(0, 31);
		String name = body.isEmpty() ? "SIGN" : "SIGN " + body;
		BlockPos written = XaeroWaypoints.upsert(null, new BlockPos(hit.x(), hit.y(), hit.z()), name, "SG", WaypointColor.GREEN);
		if (written == null) {
			mod.warning("Xaero is not available.");
			return;
		}
		mod.info("Waypoint %s", hit.coords());
	}

	private static String scrub(String text) {
		if (text == null || text.isEmpty()) return "";
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF'
				|| (c >= '\u202A' && c <= '\u202E')) {
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}

	private static String oneLine(String text) {
		if (text == null || text.isEmpty()) return "(blank sign)";
		String flat = scrub(text).replace('\n', ' ').replaceAll("\\s+", " ").trim();
		if (flat.length() > 96) return flat.substring(0, 95) + "...";
		return flat;
	}

	private String idleStatus() {
		return SignLibrary.get().has(dimension)
			? "Pack ready | " + dimension.label
			: "Pack not installed | " + dimension.file;
	}

	private void setStatus(String text) {
		if (status != null) status.set(text);
	}
}
