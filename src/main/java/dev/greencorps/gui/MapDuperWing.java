package dev.greencorps.gui;

import dev.greencorps.gui.wing.WingButton;
import dev.greencorps.gui.wing.WingDraw;
import dev.greencorps.gui.wing.WingNumberField;
import dev.greencorps.mapdupe.CopyPlan;
import dev.greencorps.mapdupe.CopyPlanner;
import dev.greencorps.mapdupe.CopyRunner;
import dev.greencorps.mapdupe.InvSim;
import dev.greencorps.mapdupe.LiveInventory;
import dev.greencorps.mapdupe.SimStack;
import dev.greencorps.mapdupe.Station;
import dev.greencorps.mixin.HandledScreenAccessor;
import dev.greencorps.modules.MapDuperPlus;
import dev.greencorps.util.ClickBudget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Map Duper+ side panel:
 * <pre>
 * Map Duper · 3×3
 * Empty maps   37      Free slots 4      Clicks 61/78
 * Copies [-] [ 4 ] [+]
 * [ Click To Copy ]   blue while on
 * [ Copy All (24) ]
 * [ Copy Bundles (2) ]
 * [ Sort Maps (12) ]  lays each artwork out from its names, or from matching edges
 * [ Bundle Sort ]      mode: the next bundle clicked is sorted (maps in artwork reading order)
 * </pre>
 * Under the panel, right-aligned with it, a status box shows progress / ETA / Stop while a job runs, otherwise
 * the current message; it is hidden when there is nothing to say. It may reach under the inventory.
 * The plans behind Copy All / Copy Bundles are re-simulated when the inventory changes, so the buttons grey
 * out with the exact reason (missing empty maps, no room for the copies, no empty bundle...).
 */
public class MapDuperWing extends GcScreenWing {
	/** Keep in sync with MixinRecipeBookFindLeftGc.MAP_DUPER_WING_W. */
	public static final int PANEL_W = 124;
	/** Inventory / crafting / cartography screens are all 176 wide. */
	private static final int INVENTORY_W = 176;
	private static final int PANEL_H = 166;
	private static final int PAD = 8;
	private static final int OK = 0xFF2E7D32;
	private static final int BAD = 0xFFB71C1C;
	private static final int AMBER = 0xFFE0A030;
	private static final int BTN_H = 16;
	private static final int BTN_STEP = 18;
	private static final int STATUS_H = 34;

	private final HandledScreen<?> screen;
	private final WingNumberField copiesField;
	private final List<WingButton> buttons = new ArrayList<>();

	private int cacheRevision = Integer.MIN_VALUE;
	private int cacheCopies = -1;
	private int cacheAge;
	private InvSim sim;
	private CopyPlan allPlan;
	private CopyPlan bundlesPlan;
	/** Status box bounds from the last draw (outside the panel). */
	private int statusX;
	private int statusY;
	private int statusW;

	public MapDuperWing(HandledScreen<?> screen) {
		super(null, PANEL_W, PANEL_H);
		this.screen = screen;
		this.copiesField = new WingNumberField(1, MapDuperPlus.MAX_COPIES, n -> {
			MapDuperPlus mod = MapDuperPlus.get();
			if (mod != null) mod.setCopies(n);
		});
	}

	@Override
	public boolean appliesTo(HandledScreen<?> s) {
		return LiveInventory.stationFor(s) != null;
	}

	@Override
	public boolean isModuleActive() {
		MapDuperPlus mod = MapDuperPlus.get();
		return mod != null && mod.isActive();
	}

	private void refresh(MapDuperPlus mod) {
		int revision = screen.getScreenHandler().getRevision();
		if (revision == cacheRevision && mod.copies() == cacheCopies && ++cacheAge < 20) return;
		cacheRevision = revision;
		cacheCopies = mod.copies();
		cacheAge = 0;
		sim = mod.snapshot(screen);
		allPlan = sim == null ? null : CopyPlanner.copyAll(sim, mod.copies());
		bundlesPlan = sim == null ? null : CopyPlanner.allBundles(sim);
	}

	@Override
	protected void drawContents(DrawContext context, int mouseX, int mouseY) {
		MapDuperPlus mod = MapDuperPlus.get();
		if (mod == null) return;
		refresh(mod);
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		Station st = LiveInventory.stationFor(screen);
		boolean running = mod.isRunning();
		buttons.clear();

		int x = left + PAD;
		int w = PANEL_W - PAD * 2;

		// Header strip: title + station chip.
		int hx = left + 4;
		int hw = PANEL_W - 8;
		WingDraw.header(context, hx, top + 4, hw, 14);
		context.drawText(tr, "Map Duper", hx + 4, top + 7, 0xFFFFFFFF, true);
		if (st != null) {
			String tag = stationTag(st);
			int cw = tr.getWidth(tag) + 6;
			WingDraw.chip(context, hx + hw - cw - 3, top + 6, cw, 10, 0xFF3C3C3C);
			context.drawText(tr, tag, hx + hw - cw, top + 7, 0xFFE0E0E0, false);
		}

		// Stats card (the header names the panel, so the card needs no title of its own).
		int cy = top + 20;
		WingDraw.card(context, x - 2, cy, w + 4, 33);
		int empties = sim == null ? 0 : sim.countEmpties();
		int free = freeSlots();
		int needAll = allPlan != null && allPlan.ok() ? allPlan.emptiesUsed() : 0;
		int y = cy + 3;
		stat(context, tr, "Empty maps", Integer.toString(empties), empties > 0 && empties >= needAll ? OK : BAD, x + 1, y, w - 2);
		y += 10;
		stat(context, tr, "Free slots", Integer.toString(free), free > 0 ? OK : BAD, x + 1, y, w - 2);
		y += 10;
		int clicksLeft = ClickBudget.remaining();
		int clickColor = ClickBudget.backedOff() ? AMBER : clicksLeft > ClickBudget.limit() / 4 ? OK : BAD;
		stat(context, tr, "Clicks", clicksLeft + "/" + ClickBudget.limit(), clickColor, x + 1, y, w - 2);

		y = cy + 36;
		drawLabel(context, "Copies", x, y + 3);
		int bx = x + 44;
		buttons.add(new WingButton(bx, y, 14, 14, "-", !running && mod.copies() > 1, false, null,
			() -> mod.setCopies(mod.copies() - step())));
		copiesField.place(bx + 16, y, 32, 14, mod.copies());
		copiesField.draw(context);
		buttons.add(new WingButton(bx + 50, y, 14, 14, "+", !running && mod.copies() < MapDuperPlus.MAX_COPIES, false, null,
			() -> mod.setCopies(mod.copies() + step())));

		y += 16;
		buttons.add(new WingButton(x, y, w, BTN_H, "Click To Copy", !running && st != null, mod.clickToCopy(),
			st == null ? "Open the inventory, a crafting table or a cartography table" : null, mod::toggleClickToCopy));

		y += BTN_STEP;
		String allReason = reason(allPlan, running);
		int maps = allPlan != null && allPlan.ok() ? allPlan.copies() / Math.max(1, mod.copies()) : 0;
		boolean confirm = mod.copyAllNeedsConfirm();
		String allLabel = (confirm ? "Confirm" : "Copy All") + (maps > 0 ? " (" + maps + ")" : "");
		WingButton all = new WingButton(x, y, w, BTN_H, allLabel, allReason == null, false, allReason, () -> mod.startCopyAll(screen));
		buttons.add(confirm ? all.tinted(AMBER) : all);

		y += BTN_STEP;
		String bundleReason = reason(bundlesPlan, running);
		int bundles = countMapBundles();
		buttons.add(new WingButton(x, y, w, BTN_H, "Copy Bundles" + (bundles > 0 ? " (" + bundles + ")" : ""), bundleReason == null, false,
			bundleReason, () -> mod.startAllBundles(screen)));

		y += BTN_STEP;
		int sortable = mod.sortableMaps(sim);
		String sortReason = running ? "A copy job is running"
			: st == null ? "This screen is not supported"
			: sortable < 2 ? "Needs 2 or more maps in the main inventory rows" : null;
		WingButton sort = new WingButton(x, y, w, BTN_H, "Sort Maps" + (sortable > 1 ? " (" + sortable + ")" : ""), sortReason == null, false,
			sortReason, () -> mod.startSort(screen));
		buttons.add(sort);

		y += BTN_STEP;
		buttons.add(new WingButton(x, y, w, BTN_H, "Bundle Sort", !running && st != null, mod.bundleSort(),
			running ? "A copy job is running" : null, mod::toggleBundleSort).withHint("Click a bundle to sort its contents"));

		drawStatus(context, tr, mod, mouseX, mouseY);

		for (WingButton b : buttons) b.draw(context, mouseX, mouseY);
		for (WingButton b : buttons) b.drawReason(context, mouseX, mouseY);
	}

	/** Status box under the panel, right-aligned with it; only while a job runs or there is something to say. */
	private void drawStatus(DrawContext context, TextRenderer tr, MapDuperPlus mod, int mouseX, int mouseY) {
		CopyRunner r = mod.runner();
		String status = mod.status() == null ? "" : mod.status();
		if (r == null && status.isEmpty()) {
			statusW = 0;
			return;
		}
		String progress = r == null ? "" : r.clicksDone() + "/" + r.totalClicks() + " clicks  ~" + r.etaSeconds() + "s";
		int right = left + PANEL_W;
		int maxW = PANEL_W + GAP + INVENTORY_W;
		int want = r != null ? 16 + 80 + 6 + tr.getWidth(progress) + 6 + 28 : 16 + tr.getWidth(status);
		statusW = Math.max(PANEL_W, Math.min(maxW, want));
		statusX = right - statusW;
		int screenH = MinecraftClient.getInstance().getWindow().getScaledHeight();
		statusY = Math.min(top + PANEL_H + GAP, screenH - STATUS_H - 2);

		WingDraw.panel(context, statusX, statusY, statusW, STATUS_H);
		int cx = statusX + 4;
		int cy = statusY + 4;
		int cw = statusW - 8;
		WingDraw.card(context, cx, cy, cw, STATUS_H - 8);
		context.drawText(tr, "Status", cx + 3, cy + 3, 0xFF555555, false);
		int rowY = cy + 13;
		int innerW = cw - 6;
		if (r != null) {
			context.drawText(tr, r.label(), cx + cw - 3 - tr.getWidth(r.label()), cy + 3, 0xFF555555, false);
			int barW = Math.max(40, innerW - tr.getWidth(progress) - 6 - 30);
			WingDraw.inset(context, cx + 3, rowY, barW, 9);
			int fill = r.totalClicks() == 0 ? 0 : (barW - 2) * r.clicksDone() / r.totalClicks();
			context.fill(cx + 4, rowY + 1, cx + 4 + fill, rowY + 8, 0xFF3FA34D);
			context.drawText(tr, progress, cx + 3 + barW + 6, rowY + 1, 0xFF303030, false);
			buttons.add(WingButton.of(cx + cw - 3 - 28, rowY - 2, 28, 12, "Stop", () -> mod.stop("Stopped")));
		} else {
			context.drawText(tr, tr.trimToWidth(status, innerW), cx + 3, rowY + 1, 0xFF303030, false);
			if (tr.getWidth(status) > innerW && hit(mouseX, mouseY, cx, rowY, cw, 10)) {
				context.drawTooltip(tr, Text.literal(status), mouseX, mouseY);
			}
		}
	}

	@Override
	public boolean contains(double mx, double my) {
		return super.contains(mx, my) || (statusW > 0 && hit(mx, my, statusX, statusY, statusW, STATUS_H));
	}

	private static String stationTag(Station st) {
		return switch (st) {
			case INVENTORY -> "2×2";
			case CRAFTING -> "3×3";
			case CARTOGRAPHY -> "table";
			case SHULKER -> "box";
		};
	}

	/** Label left, value + status dot right. */
	private void stat(DrawContext ctx, TextRenderer tr, String label, String value, int color, int x, int y, int w) {
		drawLabel(ctx, label, x, y);
		ctx.drawText(tr, value, x + w - 8 - tr.getWidth(value), y, 0xFF303030, false);
		WingDraw.dot(ctx, x + w - 5, y + 1, color);
	}

	private static String reason(CopyPlan plan, boolean running) {
		if (running) return "A copy job is running";
		if (plan == null) return "This screen is not supported";
		return plan.ok() ? null : plan.error();
	}

	private int freeSlots() {
		if (sim == null) return 0;
		int n = 0;
		for (int id = sim.station.invFirst; id <= sim.station.invLast; id++) {
			if (sim.slot(id).isNone()) n++;
		}
		return n;
	}

	private int countMapBundles() {
		if (sim == null) return 0;
		int n = 0;
		for (int id = sim.station.invFirst; id <= sim.station.invLast; id++) {
			SimStack s = sim.slot(id);
			if (s.isMapBundle()) n++;
		}
		return n;
	}

	private static int step() {
		long handle = MinecraftClient.getInstance().getWindow().getHandle();
		boolean shift = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
			|| GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
		return shift ? 8 : 1;
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
		if (copiesField.mouseClicked(mouseX, mouseY)) return true;
		if (button != 0) return true;
		MapDuperPlus mod = MapDuperPlus.get();
		boolean confirming = mod != null && mod.copyAllNeedsConfirm();
		for (WingButton b : buttons) {
			if (b.hit(mouseX, mouseY)) {
				if (confirming && !b.labelStartsWith("Confirm")) mod.cancelConfirm();
				b.click(mouseX, mouseY);
				cacheAge = 20;
				return true;
			}
		}
		return true;
	}

	@Override
	public void onScreenClosed() {
		MapDuperPlus mod = MapDuperPlus.get();
		if (mod != null) mod.onScreenClosed();
	}

	@Override
	public boolean interceptScreenClick(double mouseX, double mouseY, int button) {
		copiesField.mouseClicked(mouseX, mouseY);
		MapDuperPlus mod = MapDuperPlus.get();
		if (mod == null) return false;
		if (mod.isRunning()) return true; // your clicks would desync the job
		mod.cancelConfirm();
		Slot slot = ((HandledScreenAccessor) screen).greencorps$getFocusedSlot();
		boolean plain = button == 0 && !hasShiftDown() && slot != null;
		if (mod.bundleSort()) {
			if (plain && mod.startBundleSort(screen, slot.id)) return true;
			mod.endBundleSort(); // anything but a bundle ends the mode; the click goes through as usual
			return false;
		}
		if (!mod.clickToCopy()) return false;
		if (plain && mod.startSlot(screen, slot.id)) return true;
		// Anything but a map (or map bundle) ends Click To Copy; the click itself goes through as usual.
		mod.endClickToCopy();
		return false;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (copiesField.keyPressed(keyCode)) return true;
		MapDuperPlus mod = MapDuperPlus.get();
		if (mod == null || !mod.isRunning()) return false;
		// Closing mid-job would leave maps on the cursor (the server drops them): stop and put them back first.
		MinecraftClient mc = MinecraftClient.getInstance();
		if (keyCode == GLFW.GLFW_KEY_ESCAPE || mc.options.inventoryKey.matchesKey(keyCode, scanCode)) {
			mod.stop("Stopped; close again once everything is back");
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return copiesField.scroll(mouseX, mouseY, amount);
	}

	private static boolean hasShiftDown() {
		return net.minecraft.client.gui.screen.Screen.hasShiftDown();
	}
}
