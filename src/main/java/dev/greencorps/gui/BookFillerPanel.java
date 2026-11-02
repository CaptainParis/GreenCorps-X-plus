package dev.greencorps.gui;

import dev.greencorps.book.BookTemplates;
import dev.greencorps.modules.BookFiller;
import dev.greencorps.util.BookPages;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;
import net.minecraft.client.MinecraftClient;

/** Module screen: settings, page editor, fill actions, templates. */
public final class BookFillerPanel {
	public static final String SIGNER_TIP =
		"Stored on the template and shown here. Vanilla servers record your account as the author. This does not sign the book.";

	private BookFillerPanel() {
	}

	public static WWidget build(GuiTheme theme, BookFiller mod) {
		WVerticalList list = theme.verticalList();
		WLabel status = list.add(theme.label(mod.status())).expandX().widget();

		boolean[] editing = { false };
		checkRow(theme, list, "Auto-fill: ", mod.autoFill(), box -> mod.setAutoFill(box.checked));
		checkRow(theme, list, "Auto-sign: ", mod.autoSign(), box -> mod.setAutoSign(box.checked));
		checkRow(theme, list, "Auto-Offhand Only: ", mod.offhandOnly(), box -> mod.setOffhandOnly(box.checked));
		WCheckbox smartBox = checkRow(theme, list, "smart breaks: ", mod.smartBreaks(), box -> mod.setSmartBreaks(box.checked));

		WSection editorSection = list.add(theme.section("Editor")).expandX().widget();
		WTextBox title = labeledBox(theme, editorSection, "Title", mod.title(), "Title sent when the book is signed. 32 characters.");
		WTextBox signer = labeledBox(theme, editorSection, "Signer", mod.signer(), SIGNER_TIP);
		title.action = () -> {
			if (editing[0]) return;
			String next = BookPages.clampTitle(title.get());
			if (!next.equals(title.get())) {
				editing[0] = true;
				title.set(next);
				editing[0] = false;
			}
			mod.setTitle(next);
		};
		signer.action = () -> {
			if (editing[0]) return;
			String next = BookPages.clampSigner(signer.get());
			if (!next.equals(signer.get())) {
				editing[0] = true;
				signer.set(next);
				editing[0] = false;
			}
			mod.setSigner(next);
		};

		boolean[] pagesMode = { true };
		WBookAction normalTab = new WBookAction("Normal");
		WBookAction pagesTab = new WBookAction("Page View");
		EqualHalves tabRow = new EqualHalves();
		tabRow.theme = theme;
		tabRow.add(normalTab).expandX();
		tabRow.add(pagesTab).expandX();

		WBookText body = new WBookText(mod.text());
		WBookText leftPage = new WBookText("");
		WBookText rightPage = new WBookText("");
		EqualHalves spread = new EqualHalves();
		spread.theme = theme;
		spread.add(leftPage).expandX();
		spread.add(rightPage).expandX();

		WLabel counter = theme.label(counterText(mod.text(), mod.smartBreaks()));
		applyCounterColor(counter, mod.text(), mod.smartBreaks());
		body.action = () -> {
			mod.setText(body.get());
			refreshCounter(counter, body.get(), mod.smartBreaks());
		};

		int[] page = { 0 };
		boolean[] pageGuard = { false };
		WTextBox pageNumber = theme.textBox("1");
		pageNumber.minWidth = unscaled(theme, theme.textWidth("888") + theme.pad() * 2);
		WLabel pageCount = theme.label("");
		Runnable showPage = () -> {
			BookPages.Split split = BookPages.split(mod.text(), BookFillerPanel::width, mod.smartBreaks());
			int count = Math.max(1, split.pages().size());
			page[0] = BookPages.spreadLeft(Math.max(0, Math.min(page[0], count - 1)));
			String left = split.pages().get(page[0]);
			String right = page[0] + 1 < split.pages().size() ? split.pages().get(page[0] + 1) : "";
			pageGuard[0] = true;
			leftPage.set(left, Math.min(leftPage.cursor(), left.length()));
			rightPage.set(right, Math.min(rightPage.cursor(), right.length()));
			if (!pageNumber.isFocused()) pageNumber.set(Integer.toString(page[0] + 1));
			pageCount.set("/ " + count);
			pageGuard[0] = false;
		};
		leftPage.action = () -> editSpreadPage(mod, page, pageGuard, leftPage, page[0], showPage, counter);
		rightPage.action = () -> editSpreadPage(mod, page, pageGuard, rightPage, page[0] + 1, showPage, counter);

		Runnable commitPageNumber = () -> {
			if (pageGuard[0]) return;
			int count = Math.max(1, BookPages.split(mod.text(), BookFillerPanel::width, mod.smartBreaks()).pages().size());
			int typed = parse(pageNumber.get(), page[0] + 1);
			int clamped = BookPages.clampPageNumber(typed, count);
			page[0] = BookPages.spreadLeft(clamped - 1);
			showPage.run();
		};
		pageNumber.actionOnUnfocused = commitPageNumber;
		pageNumber.action = () -> {
			if (pageGuard[0]) return;
			int count = Math.max(1, BookPages.split(mod.text(), BookFillerPanel::width, mod.smartBreaks()).pages().size());
			int typed;
			try {
				typed = Integer.parseInt(pageNumber.get().trim());
			} catch (NumberFormatException e) {
				return;
			}
			if (typed < 1 || typed > count) return;
			page[0] = BookPages.spreadLeft(typed - 1);
			showPage.run();
		};

		double navGap = 8;
		WHorizontalList nav = theme.horizontalList();
		WBookAction first = nav.add(new WBookAction("First")).widget();
		WBookAction prev = nav.add(new WBookAction("Prev")).widget();
		nav.add(pageNumber).padLeft(navGap);
		nav.add(pageCount).widget();
		WBookAction next = nav.add(new WBookAction("Next")).padLeft(navGap).widget();
		WBookAction last = nav.add(new WBookAction("Last")).widget();
		first.action = () -> { page[0] = 0; showPage.run(); };
		prev.action = () -> { page[0] = Math.max(0, page[0] - 2); showPage.run(); };
		next.action = () -> { page[0] = page[0] + 2; showPage.run(); };
		last.action = () -> {
			int count = BookPages.split(mod.text(), BookFillerPanel::width, mod.smartBreaks()).pages().size();
			page[0] = BookPages.lastSpread(count);
			showPage.run();
		};

		WVerticalList editor = addFull(editorSection, theme.verticalList());
		Runnable showEditor = () -> {
			editor.clear();
			normalTab.setSelected(!pagesMode[0]);
			pagesTab.setSelected(pagesMode[0]);
			editor.add(tabRow).expandX();
			if (pagesMode[0]) {
				editor.add(spread).expandX();
				editor.add(nav).centerX();
				showPage.run();
			} else {
				editor.add(body).expandX();
				pageGuard[0] = true;
				body.set(mod.text(), body.cursor());
				pageGuard[0] = false;
			}
			editor.add(counter).expandX();
			refreshCounter(counter, mod.text(), mod.smartBreaks());
		};
		smartBox.action = () -> {
			mod.setSmartBreaks(smartBox.checked);
			showEditor.run();
		};
		normalTab.action = () -> { pagesMode[0] = false; showEditor.run(); };
		pagesTab.action = () -> { pagesMode[0] = true; showEditor.run(); };
		showEditor.run();

		WHorizontalList fills = addFull(editorSection, theme.horizontalList());
		WBookAction fill = fills.add(new WBookAction("Fill")).expandX().widget();
		WBookAction fillSign = fills.add(new WBookAction("Fill & Sign")).expandX().widget();
		WBookAction fillHotbar = fills.add(new WBookAction("Fill Hotbar")).expandX().widget();
		WBookAction fillAll = fills.add(new WBookAction("Fill All")).expandX().widget();
		Runnable counts = () -> {
			int one = mod.fillCount();
			int hot = mod.hotbarCount();
			int all = mod.inventoryCount();
			fill.setLabel("Fill (" + one + ")");
			fill.setEmpty(one == 0);
			fillSign.setLabel("Fill & Sign (" + one + ")");
			fillSign.setEmpty(one == 0);
			fillHotbar.setLabel("Fill Hotbar (" + hot + ")");
			fillHotbar.setEmpty(hot == 0);
			fillAll.setLabel("Fill All (" + all + ")");
			fillAll.setEmpty(all == 0);
		};
		counts.run();
		fill.action = () -> { status.set(mod.fill()); counts.run(); showEditor.run(); };
		fillSign.action = () -> { status.set(mod.fillAndSign()); counts.run(); showEditor.run(); };
		fillHotbar.action = () -> { status.set(mod.fillHotbar()); counts.run(); };
		fillAll.action = () -> { status.set(mod.fillAll()); counts.run(); };

		WSection templates = list.add(theme.section("Templates")).expandX().widget();
		WCheckbox autoSaveBox = checkRow(theme, templates, "Auto-save signed books as templates: ", mod.autoSaveTemplates(), box -> { });
		WVerticalList templateBody = addFull(templates, theme.verticalList());
		Runnable[] refresh = new Runnable[1];
		Runnable rebuildTemplates = () -> {
			templateBody.clear();
			if (!mod.autoSaveTemplates()) {
				WBookAction importHeld = templateBody.add(new WBookAction("Import Held Book As Template")).expandX().widget();
				importHeld.action = () -> confirmDraft(mod, mod::saveHeldBook, refresh[0]);
			}
			buildTemplateRows(theme, mod, templateBody, refresh[0]);
			WHorizontalList saveRow = templateBody.add(theme.horizontalList()).expandX().widget();
			WBookAction save = saveRow.add(new WBookAction("Save")).expandX().widget();
			WBookAction create = saveRow.add(new WBookAction("New")).expandX().widget();
			save.action = () -> {
				status.set(mod.saveDraft());
				refresh[0].run();
			};
			create.action = () -> confirmDraft(mod, mod::createTemplate, refresh[0]);
		};
		autoSaveBox.action = () -> {
			mod.setAutoSaveTemplates(autoSaveBox.checked);
			rebuildTemplates.run();
		};
		refresh[0] = () -> {
			title.set(mod.title());
			signer.set(mod.signer());
			status.set(mod.status());
			showEditor.run();
			rebuildTemplates.run();
		};
		rebuildTemplates.run();
		return list;
	}

	private static void editSpreadPage(BookFiller mod, int[] page, boolean[] pageGuard, WBookText box,
									   int index, Runnable showPage, WLabel counter) {
		if (pageGuard[0]) return;
		BookPages.Split before = BookPages.split(mod.text(), BookFillerPanel::width, mod.smartBreaks());
		String next;
		int cursor = box.cursor();
		if (index >= before.pages().size()) {
			if (box.get().isEmpty() || before.spans().isEmpty()) return;
			int anchor = before.pages().size() - 1;
			BookPages.Span span = before.spans().get(anchor);
			String raw = mod.text();
			int end = Math.min(span.end(), raw.length());
			int start = Math.min(span.start(), end);
			next = raw.substring(0, end) + BookPages.breakAfter(raw.substring(start, end)) + box.get();
			cursor = next.length();
		} else {
			BookPages.Span span = before.spans().get(index);
			next = BookPages.replaceSpan(mod.text(), span.start(), span.end(), box.get());
		}
		if (next.length() > BookPages.CAPACITY) next = next.substring(0, BookPages.CAPACITY);
		mod.setText(next);
		BookPages.Split after = BookPages.split(next, BookFillerPanel::width, mod.smartBreaks());
		page[0] = BookPages.spreadLeft(Math.min(index, Math.max(0, after.pages().size() - 1)));
		refreshCounter(counter, next, mod.smartBreaks());
		showPage.run();
	}

	private static void confirmDraft(BookFiller mod, Runnable action, Runnable refresh) {
		if (!mod.draftDirty()) {
			action.run();
			refresh.run();
			return;
		}
		YesNoPrompt.create()
			.title("Save this draft?")
			.message("Save this draft before opening another template?")
			.dontShowAgainCheckboxVisible(false)
			.onYes(() -> {
				mod.saveDraft();
				action.run();
			})
			.onNo(() -> { })
			.show();
	}

	/** Label on the left half, checkbox at the start of the right half. */
	private static WCheckbox checkRow(GuiTheme theme, WVerticalList list, String label, boolean on, java.util.function.Consumer<WCheckbox> action) {
		EqualHalves row = addFull(list, new EqualHalves());
		row.add(theme.label(label));
		WCheckbox box = row.add(theme.checkbox(on)).widget();
		box.action = () -> action.accept(box);
		return box;
	}

	private static void buildTemplateRows(GuiTheme theme, BookFiller mod, WVerticalList rows, Runnable refresh) {
		double pinWidth = theme.textWidth("Unpin") + theme.pad() * 2 + 2;
		for (BookTemplates.Template row : mod.templateRows()) {
			WHorizontalList line = rows.add(theme.horizontalList()).expandX().widget();
			boolean active = row.id.equals(mod.activeTemplateId());
			WBookAction name = line.add(new WBookAction(row.name)).expandX().widget();
			name.setSelected(active);
			WBookAction pin = line.add(new WBookAction(row.pinned ? "Unpin" : "Pin")).widget();
			pin.minWidth = pinWidth;
			WBookAction delete = line.add(new WBookAction("X")).widget();
			String id = row.id;
			name.action = () -> confirmDraft(mod, () -> mod.loadTemplate(id), refresh);
			pin.action = () -> {
				mod.togglePin(id);
				refresh.run();
			};
			delete.action = () -> {
				mod.deleteTemplate(id);
				refresh.run();
			};
		}
	}

	private static WTextBox labeledBox(GuiTheme theme, WVerticalList list, String name, String value, String tip) {
		WHorizontalList row = addFull(list, theme.horizontalList());
		WLabel label = row.add(theme.label(name)).widget();
		label.tooltip = tip;
		WTextBox box = row.add(theme.textBox(value == null ? "" : value)).expandX().widget();
		box.tooltip = tip;
		return box;
	}

	/** Section content is inset by default. Drop that inset so columns share the settings width. */
	private static <T extends WWidget> T addFull(WVerticalList list, T widget) {
		Cell<T> cell = list.add(widget).expandX();
		cell.padHorizontal(0);
		return cell.widget();
	}

	private static double unscaled(GuiTheme theme, double pixels) {
		double scale = theme.scale(1);
		if (scale < 0.001) return pixels;
		return pixels / scale;
	}

	private static void refreshCounter(WLabel counter, String text, boolean smart) {
		counter.set(counterText(text, smart));
		applyCounterColor(counter, text, smart);
	}

	private static String counterText(String text, boolean smart) {
		BookPages.Split split = BookPages.split(text, BookFillerPanel::width, smart);
		return split.used() + " / " + split.available();
	}

	private static void applyCounterColor(WLabel counter, String text, boolean smart) {
		BookPages.Split split = BookPages.split(text, BookFillerPanel::width, smart);
		counter.color(split.overflow() ? new Color(220, 90, 90) : new Color(210, 210, 215));
	}

	private static int parse(String raw, int fallback) {
		if (raw == null) return fallback;
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int width(String text) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.textRenderer == null || text == null || text.isEmpty()) return 0;
		return mc.textRenderer.getWidth(text);
	}

	/**
	 * Two cells that each receive half the row. {@code expandX} on a child stretches that child
	 * across its half. A child without it stays at the start of its half.
	 */
	private static final class EqualHalves extends WHorizontalList {
		@Override
		protected void onCalculateWidgetPositions() {
			if (cells.size() != 2) {
				super.onCalculateWidgetPositions();
				return;
			}
			double gap = spacing();
			double half = Math.max(0, (width - gap) / 2.0);
			place(cells.get(0), x, half);
			place(cells.get(1), x + half + gap, half);
		}

		private void place(Cell<?> cell, double cellX, double half) {
			cell.x = cellX + cell.padLeft();
			cell.y = y + cell.padTop();
			cell.width = Math.max(0, half - cell.padLeft() - cell.padRight());
			cell.height = Math.max(0, height - cell.padTop() - cell.padBottom());
			cell.alignWidget();
		}
	}
}
