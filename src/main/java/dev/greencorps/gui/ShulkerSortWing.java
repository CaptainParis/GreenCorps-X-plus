package dev.greencorps.gui;

import dev.greencorps.gui.wing.WingButton;
import dev.greencorps.gui.wing.WingDraw;
import dev.greencorps.mapdupe.CopyRunner;
import dev.greencorps.mapdupe.InvSim;
import dev.greencorps.mixin.HandledScreenAccessor;
import dev.greencorps.modules.MapDuperPlus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Map Duper+ panel for an open shulker box: a small panel left of the box, top-aligned with it. Sort Maps sorts the
 * maps inside the box (Sort Maps' names-then-edges layout); Bundle Sort, as in the inventory panel, sorts the next
 * bundle you click, in the box or in your inventory. While a job runs, a line under the panel shows progress and
 * Stop, afterwards the status.
 */
public class ShulkerSortWing extends GcScreenWing {
	private static final int PANEL_W = 92;
	private static final int PANEL_H = 46;
	private static final int INFO_H = 16;

	private final HandledScreen<?> screen;
	private final List<WingButton> buttons = new ArrayList<>();
	private int cacheRevision = Integer.MIN_VALUE;
	private InvSim sim;
	/** Info line bounds from the last draw (outside the panel). */
	private int infoX;
	private int infoY;
	private int infoW;

	public ShulkerSortWing(HandledScreen<?> screen) {
		super(null, PANEL_W, PANEL_H);
		this.screen = screen;
	}

	@Override
	public boolean appliesTo(HandledScreen<?> s) {
		return s instanceof ShulkerBoxScreen;
	}

	@Override
	public boolean isModuleActive() {
		MapDuperPlus mod = MapDuperPlus.get();
		return mod != null && mod.isActive();
	}

	@Override
	public boolean leftSide() {
		return true;
	}

	@Override
	protected void drawContents(DrawContext context, int mouseX, int mouseY) {
		MapDuperPlus mod = MapDuperPlus.get();
		if (mod == null) return;
		int revision = screen.getScreenHandler().getRevision();
		if (revision != cacheRevision || sim == null) {
			cacheRevision = revision;
			sim = mod.snapshotForSort(screen);
		}
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		boolean running = mod.isRunning();
		int maps = mod.sortableMaps(sim);
		String reason = running ? "A job is running" : maps < 2 ? "Needs 2 or more maps in the shulker" : null;
		buttons.clear();
		buttons.add(new WingButton(left + 4, top + 4, PANEL_W - 8, 18, "Sort Maps" + (maps > 1 ? " (" + maps + ")" : ""),
			reason == null, false, reason, () -> mod.startSort(screen)).withHint("Lay the maps in this box out by artwork"));
		buttons.add(new WingButton(left + 4, top + 24, PANEL_W - 8, 18, "Bundle Sort", !running, mod.bundleSort(),
			running ? "A job is running" : null, mod::toggleBundleSort).withHint("Click a bundle to sort its contents"));
		drawInfo(context, tr, mod, mouseX, mouseY);
		for (WingButton b : buttons) b.draw(context, mouseX, mouseY);
		for (WingButton b : buttons) b.drawReason(context, mouseX, mouseY);
	}

	/** Under the panel, right-aligned with it: progress + Stop while sorting, else the status (if any). */
	private void drawInfo(DrawContext context, TextRenderer tr, MapDuperPlus mod, int mouseX, int mouseY) {
		CopyRunner r = mod.runner();
		String status = mod.status() == null ? "" : mod.status();
		if (r == null && status.isEmpty()) {
			infoW = 0;
			return;
		}
		String text = r != null ? r.clicksDone() + "/" + r.totalClicks() + "  ~" + r.etaSeconds() + "s" : status;
		int right = left + PANEL_W;
		int want = 8 + tr.getWidth(text) + (r != null ? 32 : 0);
		infoW = Math.max(PANEL_W, Math.min(right - 2, want));
		infoX = right - infoW;
		infoY = top + PANEL_H + 2;
		WingDraw.panel(context, infoX, infoY, infoW, INFO_H);
		int textW = infoW - 8 - (r != null ? 32 : 0);
		context.drawText(tr, tr.trimToWidth(text, textW), infoX + 4, infoY + 4, 0xFF303030, false);
		if (r != null) {
			buttons.add(WingButton.of(right - 32, infoY + 2, 28, 12, "Stop", () -> mod.stop("Stopped")));
		} else if (tr.getWidth(text) > textW && hit(mouseX, mouseY, infoX, infoY, infoW, INFO_H)) {
			context.drawTooltip(tr, Text.literal(text), mouseX, mouseY);
		}
	}

	@Override
	public boolean contains(double mx, double my) {
		return super.contains(mx, my) || (infoW > 0 && hit(mx, my, infoX, infoY, infoW, INFO_H));
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
		if (button != 0) return true;
		for (WingButton b : buttons) {
			if (b.click(mouseX, mouseY)) {
				cacheRevision = Integer.MIN_VALUE;
				return true;
			}
		}
		return true;
	}

	@Override
	public boolean interceptScreenClick(double mouseX, double mouseY, int button) {
		MapDuperPlus mod = MapDuperPlus.get();
		if (mod == null) return false;
		if (mod.isRunning()) return true; // your clicks would desync the sort
		if (!mod.bundleSort()) return false;
		Slot slot = ((HandledScreenAccessor) screen).greencorps$getFocusedSlot();
		if (button == 0 && !Screen.hasShiftDown() && slot != null && mod.startBundleSort(screen, slot.id)) return true;
		mod.endBundleSort(); // anything but a bundle ends the mode; the click goes through as usual
		return false;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		MapDuperPlus mod = MapDuperPlus.get();
		if (mod == null || !mod.isRunning()) return false;
		// Closing mid-sort would leave a map on the cursor: stop and put it back first.
		MinecraftClient mc = MinecraftClient.getInstance();
		if (keyCode == GLFW.GLFW_KEY_ESCAPE || mc.options.inventoryKey.matchesKey(keyCode, scanCode)) {
			mod.stop("Stopped; close again once everything is back");
		}
		return true;
	}

	@Override
	public void onScreenClosed() {
		MapDuperPlus mod = MapDuperPlus.get();
		if (mod != null) mod.onScreenClosed();
	}
}
