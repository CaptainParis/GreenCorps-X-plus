package dev.greencorps.modules;

import dev.greencorps.book.BookTemplates;
import dev.greencorps.GreenCorps;
import dev.greencorps.gui.BookFillerPanel;
import dev.greencorps.mixin.BookEditScreenAccessor;
import dev.greencorps.util.BookPages;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WritableBookContentComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fills books from the active template. Signing uses Title only, and only from
 * Fill &amp; Sign, the book-screen Fill &amp; Sign button, or Auto-sign.
 */
public class BookFiller extends Module {
	private final SettingGroup sgGeneral = settings.getDefaultGroup();

	private final Setting<String> text = sgGeneral.add(new StringSetting.Builder()
		.name("text")
		.description("Preset text written into the book.")
		.defaultValue("")
		.visible(() -> false)
		.build()
	);

	private final Setting<String> title = sgGeneral.add(new StringSetting.Builder()
		.name("title")
		.description("Title sent when a book is signed.")
		.defaultValue("")
		.visible(() -> false)
		.build()
	);

	private final Setting<String> signer = sgGeneral.add(new StringSetting.Builder()
		.name("signer")
		.description(BookFillerPanel.SIGNER_TIP)
		.defaultValue("")
		.visible(() -> false)
		.build()
	);

	private final Setting<Boolean> autoFill = sgGeneral.add(new BoolSetting.Builder()
		.name("auto-fill")
		.description("Fill an empty book and quill when its screen opens.")
		.defaultValue(false)
		.visible(() -> false)
		.build()
	);

	private final Setting<Boolean> autoSign = sgGeneral.add(new BoolSetting.Builder()
		.name("auto-sign")
		.description("Sign the book Auto-fill writes, using Title.")
		.defaultValue(false)
		.visible(() -> false)
		.build()
	);

	private final Setting<Boolean> offhandOnly = sgGeneral.add(new BoolSetting.Builder()
		.name("offhand-only")
		.description("Auto-fill only an empty book in the off-hand.")
		.defaultValue(false)
		.visible(() -> false)
		.build()
	);

	private final Setting<Boolean> smartBreaks = sgGeneral.add(new BoolSetting.Builder()
		.name("smart-page-breaks")
		.description("Keep a short paragraph together, and don't leave a one-line heading at the bottom of a page.")
		.defaultValue(false)
		.visible(() -> false)
		.build()
	);

	private final Setting<Boolean> autoSaveTemplates = sgGeneral.add(new BoolSetting.Builder()
		.name("auto-save-templates")
		.description("Save a signed book as a template named after its title.")
		.defaultValue(true)
		.visible(() -> false)
		.build()
	);

	/** Turns the saved auto-save value on once for installs that stored the old default. */
	private final Setting<Boolean> autoSaveDefaultApplied = sgGeneral.add(new BoolSetting.Builder()
		.name("auto-save-default-applied")
		.description("Internal.")
		.defaultValue(false)
		.visible(() -> false)
		.build()
	);

	private BookTemplates templates;
	private String status = "Hold a book and quill, then press Fill.";
	/** Identity of an off-hand quill already written this hold, so an empty template is not sent every tick. */
	private int filledOffhand;
	private String loadedTitle = "";
	private String loadedSigner = "";
	private String loadedText = "";

	public BookFiller() {
		super(GreenCorps.CATEGORY, "book-filler", "Fill a book and quill from a template, with a separate title and signer.");
		if (!Boolean.TRUE.equals(autoSaveDefaultApplied.get())) {
			autoSaveTemplates.set(true);
			autoSaveDefaultApplied.set(true);
		}
		try {
			templates = BookTemplates.load(BookTemplates.defaultFile(), title(), signer(), text());
			pullActive();
		} catch (RuntimeException e) {
			templates = null;
		}
	}

	public static BookFiller get() {
		return meteordevelopment.meteorclient.systems.modules.Modules.get().get(BookFiller.class);
	}

	public String status() {
		return status;
	}

	public String text() {
		return text.get() == null ? "" : text.get();
	}

	public void setText(String value) {
		String next = value == null ? "" : value;
		if (next.length() > BookPages.CAPACITY) next = next.substring(0, BookPages.CAPACITY);
		text.set(next);
	}

	public String title() {
		return title.get() == null ? "" : title.get();
	}

	public void setTitle(String value) {
		title.set(BookPages.clampTitle(value));
	}

	public String signer() {
		return signer.get() == null ? "" : signer.get();
	}

	public void setSigner(String value) {
		signer.set(BookPages.clampSigner(value));
	}

	public boolean autoFill() {
		return Boolean.TRUE.equals(autoFill.get());
	}

	public void setAutoFill(boolean value) {
		autoFill.set(value);
	}

	public boolean autoSign() {
		return Boolean.TRUE.equals(autoSign.get());
	}

	public void setAutoSign(boolean value) {
		autoSign.set(value);
	}

	public boolean offhandOnly() {
		return Boolean.TRUE.equals(offhandOnly.get());
	}

	public void setOffhandOnly(boolean value) {
		offhandOnly.set(value);
	}

	public boolean smartBreaks() {
		return Boolean.TRUE.equals(smartBreaks.get());
	}

	public void setSmartBreaks(boolean value) {
		smartBreaks.set(value);
	}

	public boolean autoSaveTemplates() {
		return Boolean.TRUE.equals(autoSaveTemplates.get());
	}

	public void setAutoSaveTemplates(boolean value) {
		autoSaveTemplates.set(value);
	}

	public List<BookTemplates.Template> templateRows() {
		return templates().displayOrder();
	}

	public String activeTemplateId() {
		return templates().active().id;
	}

	public boolean draftDirty() {
		return !title().equals(loadedTitle) || !signer().equals(loadedSigner) || !text().equals(loadedText);
	}

	public String saveDraft() {
		BookTemplates.Template current = templates().active();
		if (untitledName(current.name)) {
			String titled = title().trim();
			if (!titled.isBlank()) {
				java.util.Set<String> names = new java.util.HashSet<>();
				for (BookTemplates.Template row : templates().displayOrder()) {
					if (!row.id.equals(current.id)) names.add(row.name);
				}
				current.name = BookTemplates.uniqueName(names, titled, LocalTime.now());
			}
		}
		templates().writeActive(title(), signer(), text());
		snapshot();
		return mark("Saved template.");
	}

	public String loadTemplate(String id) {
		templates().select(id);
		pullActive();
		return mark("Loaded template.");
	}

	public String createTemplate() {
		templates().addHeld("Untitled", "", "", "");
		pullActive();
		return mark("New template.");
	}

	public void selectTemplate(String id) {
		templates().select(id);
		pullActive();
	}

	public void duplicateTemplate(String id) {
		templates().duplicate(id);
		pullActive();
		mark("Duplicated template.");
	}

	public void deleteTemplate(String id) {
		boolean loaded = id != null && id.equals(templates().active().id);
		templates().delete(id);
		if (loaded) pullActive();
		mark("Deleted template.");
	}

	public void togglePin(String id) {
		BookTemplates.Template row = null;
		for (BookTemplates.Template t : templates().displayOrder()) {
			if (t.id.equals(id)) row = t;
		}
		if (row == null) return;
		templates().setPinned(id, !row.pinned);
	}

	public String saveHeldBook() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return mark("Hold a written book or a book and quill.");
		ItemStack stack = mc.player.getMainHandStack();
		if (stack.isOf(Items.WRITABLE_BOOK)) {
			String body = joinPages(writablePages(stack));
			templates().addHeld("Quill", "", signer(), body);
			pullActive();
			return mark("Imported the held quill as a template.");
		}
		if (stack.isOf(Items.WRITTEN_BOOK)) {
			WrittenBookContentComponent book = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
			String bookTitle = book == null || book.title() == null ? "" : book.title().raw();
			String body = joinPages(writtenPages(book));
			templates().addHeld(bookTitle.isBlank() ? "Untitled" : bookTitle.trim(), bookTitle, "", body);
			pullActive();
			return mark("Imported the held book as a template.");
		}
		return mark("Hold a written book or a book and quill.");
	}

	public int fillCount() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return 0;
		if (mc.currentScreen instanceof BookEditScreen) return 1;
		return findWritableSlot(mc.player) == null ? 0 : 1;
	}

	public int hotbarCount() {
		return countRange(0, 9);
	}

	public int inventoryCount() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return 0;
		int n = countRange(0, 36);
		if (mc.player.getInventory().getStack(PlayerInventory.OFF_HAND_SLOT).isOf(Items.WRITABLE_BOOK)) n++;
		return n;
	}

	@Override
	public WWidget getWidget(GuiTheme theme) {
		return BookFillerPanel.build(theme, this);
	}

	/** One book, always left as a quill. */
	public String fill() {
		return fillOne(false);
	}

	/** One book, signed with Title. Empty Title does not write. */
	public String fillAndSign() {
		if (BookPages.signTitle(title()).isEmpty()) return note("Title is empty.");
		return fillOne(true);
	}

	public String fillHotbar() {
		List<Integer> slots = quillSlots(0, 9);
		if (slots.isEmpty()) return note("No book and quill in the hotbar.");
		return pour(slots);
	}

	public String fillAll() {
		List<Integer> slots = quillSlots(0, 36);
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null && mc.player.getInventory().getStack(PlayerInventory.OFF_HAND_SLOT).isOf(Items.WRITABLE_BOOK)) {
			slots.add(PlayerInventory.OFF_HAND_SLOT);
		}
		if (slots.isEmpty()) return note("No book and quill in the inventory.");
		return pour(slots);
	}

	/** Runs only while this module is enabled. Writes a blank off-hand quill without opening it. */
	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (!autoFill()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		ItemStack stack = mc.player.getInventory().getStack(PlayerInventory.OFF_HAND_SLOT);
		if (!blankQuill(stack)) {
			filledOffhand = 0;
			return;
		}
		int id = System.identityHashCode(stack);
		if (id == filledOffhand) return;
		boolean sign = autoSign() && !BookPages.signTitle(title()).isEmpty();
		List<String> pages = writeSlot(mc, PlayerInventory.OFF_HAND_SLOT, sign);
		filledOffhand = System.identityHashCode(mc.player.getInventory().getStack(PlayerInventory.OFF_HAND_SLOT));
		if (pages == null) return;
		if (autoSign() && !sign) {
			note("Title is empty. Left the book unsigned.");
			return;
		}
		if (sign) onBookSigned(BookPages.signTitle(title()), pages);
		note((sign ? "Signed " : "Wrote ") + pages.size() + (pages.size() == 1 ? " page." : " pages."));
	}

	/** Auto-fill path when a book screen opens. Does not touch manual buttons. */
	public void autoFillScreen(BookEditScreen screen) {
		if (!autoFill() || screen == null) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		BookEditScreenAccessor acc = (BookEditScreenAccessor) screen;
		if (offhandOnly() && acc.greencorps$getHand() != Hand.OFF_HAND) return;
		if (mc.player != null) {
			ItemStack held = mc.player.getStackInHand(acc.greencorps$getHand());
			if (!held.isEmpty() && !blankQuill(held)) return;
		}
		if (!blank(acc.greencorps$getPages())) return;
		boolean sign = autoSign();
		if (sign && BookPages.signTitle(title()).isEmpty()) {
			writeOpen(screen, false);
			note("Title is empty. Left the book unsigned.");
			return;
		}
		writeOpen(screen, sign);
	}

	public void overlayFill(BookEditScreen screen, boolean sign) {
		if (sign && BookPages.signTitle(title()).isEmpty()) {
			note("Title is empty.");
			return;
		}
		writeOpen(screen, sign);
	}

	public void overlayCopyPage(BookEditScreen screen) {
		BookEditScreenAccessor acc = (BookEditScreenAccessor) screen;
		List<String> pages = acc.greencorps$getPages();
		int page = Math.max(0, Math.min(acc.greencorps$getCurrentPage(), pages.size() - 1));
		String body = pages.isEmpty() ? "" : pages.get(page);
		MinecraftClient.getInstance().keyboard.setClipboard(body == null ? "" : body);
		note("Copied this page.");
	}

	public void overlayCopyAll(BookEditScreen screen) {
		BookEditScreenAccessor acc = (BookEditScreenAccessor) screen;
		MinecraftClient.getInstance().keyboard.setClipboard(String.join("\n", acc.greencorps$getPages()));
		note("Copied all pages.");
	}

	public void overlayClearPage(BookEditScreen screen) {
		BookEditScreenAccessor acc = (BookEditScreenAccessor) screen;
		List<String> pages = acc.greencorps$getPages();
		if (pages.isEmpty()) pages.add("");
		int page = Math.max(0, Math.min(acc.greencorps$getCurrentPage(), pages.size() - 1));
		pages.set(page, "");
		acc.greencorps$setDirty(true);
		acc.greencorps$invalidatePageContent();
		note("Cleared this page.");
	}

	public void overlayClearAll(BookEditScreen screen) {
		BookEditScreenAccessor acc = (BookEditScreenAccessor) screen;
		List<String> pages = acc.greencorps$getPages();
		pages.clear();
		pages.add("");
		acc.greencorps$setCurrentPage(0);
		acc.greencorps$setDirty(true);
		acc.greencorps$invalidatePageContent();
		acc.greencorps$updateButtons();
		note("Cleared the book.");
	}

	/** Called at the end of a real sign. */
	public void onBookSigned(String signedTitle, List<String> pages) {
		if (!autoSaveTemplates()) return;
		String body = pages == null ? "" : String.join("\n", pages);
		templates().addSigned(signedTitle, body, signer(), LocalTime.now());
		pullActive();
		note("Saved template " + templates().active().name + ".");
	}

	private String fillOne(boolean sign) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return note("Hold a book and quill.");
		BookPages.Split split = BookPages.split(text(), width(), smartBreaks());
		List<String> pages = new ArrayList<>(split.pages());
		if (pages.isEmpty()) pages.add("");
		String signed = BookPages.signTitle(title());
		String done = (sign ? "Signed " : "Wrote ") + pages.size() + (pages.size() == 1 ? " page." : " pages.");
		if (split.overflow()) done += " Extra text was left out.";

		if (mc.currentScreen instanceof BookEditScreen edit) {
			return writeOpen(edit, sign);
		}
		Integer slot = findWritableSlot(mc.player);
		if (slot == null) return note("Hold a book and quill.");
		send(mc, slot, pages, sign ? Optional.of(signed) : Optional.empty());
		if (sign) applySigned(mc.player, slot, signed, pages);
		else applyWritable(mc.player, slot, pages);
		return note(done);
	}

	/** @return null when the open book was written */
	private String writeOpen(BookEditScreen edit, boolean sign) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || edit == null) return note("Hold a book and quill.");
		BookPages.Split split = BookPages.split(text(), width(), smartBreaks());
		List<String> pages = new ArrayList<>(split.pages());
		if (pages.isEmpty()) pages.add("");
		String signed = BookPages.signTitle(title());
		BookEditScreenAccessor acc = (BookEditScreenAccessor) edit;
		int slot = slotForHand(acc.greencorps$getHand(), mc.player);
		List<String> live = acc.greencorps$getPages();
		live.clear();
		live.addAll(pages);
		acc.greencorps$setCurrentPage(0);
		acc.greencorps$setDirty(true);
		acc.greencorps$invalidatePageContent();
		acc.greencorps$updateButtons();
		String done = (sign ? "Signed " : "Wrote ") + pages.size() + (pages.size() == 1 ? " page." : " pages.");
		if (split.overflow()) done += " Extra text was left out.";
		if (sign) {
			acc.greencorps$setTitle(signed);
			send(mc, slot, pages, Optional.of(signed));
			applySigned(mc.player, slot, signed, pages);
			mc.execute(() -> {
				if (mc.currentScreen == edit) mc.setScreen(null);
			});
			onBookSigned(signed, pages);
			return note(done);
		}
		send(mc, slot, pages, Optional.empty());
		applyWritable(mc.player, slot, pages);
		return note(done);
	}

	/** Writes the active template into one slot. Returns the pages written. */
	private List<String> writeSlot(MinecraftClient mc, int slot, boolean sign) {
		if (mc.player == null) return null;
		BookPages.Split split = BookPages.split(text(), width(), smartBreaks());
		List<String> pages = new ArrayList<>(split.pages());
		if (pages.isEmpty()) pages.add("");
		String signed = BookPages.signTitle(title());
		send(mc, slot, pages, sign ? Optional.of(signed) : Optional.empty());
		if (sign) applySigned(mc.player, slot, signed, pages);
		else applyWritable(mc.player, slot, pages);
		if (mc.currentScreen instanceof BookEditScreen edit) syncOpen(mc, slot, pages);
		return pages;
	}

	private String pour(List<Integer> slots) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return note("Hold a book and quill.");
		String remaining = text();
		int wrote = 0;
		boolean first = true;
		boolean leftover = false;
		for (int slot : slots) {
			if (remaining.isEmpty()) break;
			ItemStack stack = mc.player.getInventory().getStack(slot);
			if (!stack.isOf(Items.WRITABLE_BOOK)) continue;
			if (!first && !blankQuill(stack)) continue;
			BookPages.Split split = BookPages.split(remaining, width(), smartBreaks());
			List<String> pages = new ArrayList<>(split.pages());
			if (pages.isEmpty()) pages.add("");
			send(mc, slot, pages, Optional.empty());
			applyWritable(mc.player, slot, pages);
			syncOpen(mc, slot, pages);
			wrote++;
			first = false;
			if (split.overflow()) {
				remaining = BookPages.leftover(split, remaining);
				leftover = !remaining.isEmpty();
			} else {
				remaining = "";
				leftover = false;
			}
		}
		if (wrote == 0) return note("No book and quill.");
		String done = "Wrote " + wrote + (wrote == 1 ? " book." : " books.");
		if (leftover || !remaining.isEmpty()) done += " Extra text was left out.";
		return note(done);
	}

	private void syncOpen(MinecraftClient mc, int slot, List<String> pages) {
		if (!(mc.currentScreen instanceof BookEditScreen edit) || mc.player == null) return;
		BookEditScreenAccessor acc = (BookEditScreenAccessor) edit;
		if (slotForHand(acc.greencorps$getHand(), mc.player) != slot) return;
		List<String> live = acc.greencorps$getPages();
		live.clear();
		live.addAll(pages);
		acc.greencorps$setCurrentPage(0);
		acc.greencorps$setDirty(true);
		acc.greencorps$invalidatePageContent();
		acc.greencorps$updateButtons();
	}

	private BookTemplates templates() {
		if (templates == null) {
			templates = BookTemplates.load(BookTemplates.defaultFile(), title(), signer(), text());
			pullActive();
		}
		return templates;
	}

	private void pullActive() {
		BookTemplates.Template t = templates.active();
		text.set(t.text == null ? "" : t.text);
		title.set(BookPages.clampTitle(t.title));
		signer.set(BookPages.clampSigner(t.signer));
		snapshot();
	}

	private void snapshot() {
		loadedTitle = title();
		loadedSigner = signer();
		loadedText = text();
	}

	private static boolean untitledName(String name) {
		if (name == null || name.isBlank()) return true;
		return name.equals("Untitled") || name.startsWith("Untitled ");
	}

	private String mark(String line) {
		status = line;
		return line;
	}

	private String note(String line) {
		status = line;
		info(line);
		return line;
	}

	private BookPages.Width width() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.textRenderer == null) return ignored -> 0;
		return mc.textRenderer::getWidth;
	}

	private int countRange(int from, int to) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return 0;
		PlayerInventory inv = mc.player.getInventory();
		int n = 0;
		for (int i = from; i < to && i < inv.size(); i++) {
			if (inv.getStack(i).isOf(Items.WRITABLE_BOOK)) n++;
		}
		return n;
	}

	private List<Integer> quillSlots(int from, int to) {
		List<Integer> slots = new ArrayList<>();
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return slots;
		PlayerInventory inv = mc.player.getInventory();
		for (int i = from; i < to && i < inv.size(); i++) {
			if (inv.getStack(i).isOf(Items.WRITABLE_BOOK)) slots.add(i);
		}
		return slots;
	}

	private static int slotForHand(Hand hand, PlayerEntity player) {
		if (hand == Hand.OFF_HAND) return PlayerInventory.OFF_HAND_SLOT;
		return player.getInventory().selectedSlot;
	}

	private static Integer findWritableSlot(PlayerEntity player) {
		PlayerInventory inv = player.getInventory();
		int selected = inv.selectedSlot;
		if (inv.getStack(selected).isOf(Items.WRITABLE_BOOK)) return selected;
		if (slotHasQuill(inv, PlayerInventory.OFF_HAND_SLOT)) return PlayerInventory.OFF_HAND_SLOT;
		int size = inv.size();
		for (int i = 0; i < size; i++) {
			if (i == selected || i == PlayerInventory.OFF_HAND_SLOT) continue;
			if (inv.getStack(i).isOf(Items.WRITABLE_BOOK)) return i;
		}
		return null;
	}

	private static boolean slotHasQuill(PlayerInventory inv, int slot) {
		if (slot < 0 || slot >= inv.size()) return false;
		return inv.getStack(slot).isOf(Items.WRITABLE_BOOK);
	}

	private static boolean blank(List<String> pages) {
		if (pages == null || pages.isEmpty()) return true;
		for (String page : pages) {
			if (page != null && !page.isEmpty()) return false;
		}
		return true;
	}

	private static boolean blankQuill(ItemStack stack) {
		if (!stack.isOf(Items.WRITABLE_BOOK)) return false;
		return blank(writablePages(stack));
	}

	private static List<String> writablePages(ItemStack stack) {
		WritableBookContentComponent content = stack.get(DataComponentTypes.WRITABLE_BOOK_CONTENT);
		List<String> pages = new ArrayList<>();
		if (content == null) return pages;
		for (RawFilteredPair<String> page : content.pages()) {
			pages.add(page == null || page.raw() == null ? "" : page.raw());
		}
		return pages;
	}

	private static List<String> writtenPages(WrittenBookContentComponent book) {
		List<String> pages = new ArrayList<>();
		if (book == null) return pages;
		for (Text page : book.getPages(false)) {
			pages.add(page == null ? "" : page.getString());
		}
		return pages;
	}

	private static String joinPages(List<String> pages) {
		if (pages == null || pages.isEmpty()) return "";
		return String.join("\n", pages);
	}

	private static void send(MinecraftClient mc, int slot, List<String> pages, Optional<String> title) {
		if (mc.player == null || mc.player.networkHandler == null) return;
		mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(slot, pages, title));
	}

	private static void applyWritable(PlayerEntity player, int slot, List<String> pages) {
		ItemStack stack = player.getInventory().getStack(slot);
		if (!stack.isOf(Items.WRITABLE_BOOK)) return;
		List<RawFilteredPair<String>> pairs = new ArrayList<>(pages.size());
		for (String page : pages) pairs.add(RawFilteredPair.of(page));
		stack.set(DataComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContentComponent(pairs));
	}

	private static void applySigned(PlayerEntity player, int slot, String title, List<String> pages) {
		ItemStack stack = player.getInventory().getStack(slot);
		int count = stack.isEmpty() ? 1 : stack.getCount();
		ItemStack written = new ItemStack(Items.WRITTEN_BOOK, count);
		List<RawFilteredPair<Text>> pageTexts = new ArrayList<>(pages.size());
		for (String page : pages) pageTexts.add(RawFilteredPair.of(Text.literal(page)));
		written.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(
			RawFilteredPair.of(title),
			title,
			0,
			pageTexts,
			true
		));
		player.getInventory().setStack(slot, written);
	}
}
