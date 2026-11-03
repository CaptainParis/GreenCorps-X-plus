package dev.greencorps.gui;

import dev.greencorps.gui.wing.WingButton;
import dev.greencorps.modules.AutoLoom;
import dev.greencorps.util.BannerCommandParser;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.LoomScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Vanilla-styled wing flush against the native loom window. */
public class AutoLoomWing extends GcScreenWing {
	private static final Identifier TEXTURE = Identifier.of("greencorps", "textures/gui/banner_guide.png");
	private static final int PANEL_W = 148;
	private static final int PANEL_H = 166;

	private final HandledScreen<?> screen;
	private final List<WingButton> buttons = new ArrayList<>();

	public AutoLoomWing(HandledScreen<?> screen) {
		super(TEXTURE, PANEL_W, PANEL_H);
		this.screen = screen;
	}

	@Override
	public boolean appliesTo(HandledScreen<?> s) {
		return s instanceof LoomScreen;
	}

	@Override
	public boolean isModuleActive() {
		AutoLoom mod = AutoLoom.get();
		return mod != null && mod.showWing();
	}

	@Override
	protected void drawContents(DrawContext context, int mouseX, int mouseY) {
		AutoLoom mod = AutoLoom.get();
		if (mod == null) return;

		if (screen.getScreenHandler() instanceof LoomScreenHandler handler) {
			mod.tickProgress(handler);
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		int tx = left + 8;
		int ty = top + 6;
		drawLabel(context, "Auto Loom", tx, ty);
		ty += 12;

		BannerCommandParser.BannerPlan plan = mod.plan();
		if (plan.baseBanner != null) {
			drawItemRow(context, new ItemStack(plan.baseBanner), "base", 1, count(plan.baseBanner), tx, ty);
			ty += 18;
		}

		for (Map.Entry<Item, Integer> e : plan.dyeCounts.entrySet()) {
			drawItemRow(context, new ItemStack(e.getKey()), "dye", e.getValue(), count(e.getKey()), tx, ty);
			ty += 18;
			if (ty > top + PANEL_H - 70) break;
		}
		for (Item pattern : plan.patternItems) {
			drawItemRow(context, new ItemStack(pattern), "keep", 1, count(pattern), tx, ty);
			ty += 18;
			if (ty > top + PANEL_H - 70) break;
		}

		ty = Math.max(ty + 2, top + 78);
		String step = plan.ok
			? ("Step " + Math.min(mod.currentStep() + 1, Math.max(plan.layers.size(), 1)) + "/" + plan.layers.size())
			: "No plan";
		drawLabel(context, step, tx, ty);
		ty += 10;
		String status = mod.status();
		if (status != null && status.length() > 22) status = status.substring(0, 21) + "…";
		drawLabel(context, status == null ? "" : status, tx, ty);
		ty += 14;

		// Click targets are the drawn buttons (their y depends on the plan's length).
		buttons.clear();
		buttons.add(new WingButton(tx, ty, 44, 16, "Apply", !mod.waitingForTake() && plan.ok, false, null, () -> {
			if (screen.getScreenHandler() instanceof LoomScreenHandler handler) mod.applyCurrentStep(handler);
		}));
		buttons.add(WingButton.of(tx + 48, ty, 40, 16, "Reset", mod::resetSteps));
		buttons.add(WingButton.of(tx + 92, ty, 40, 16, "Paste", mod::loadClipboard));
		for (WingButton b : buttons) b.draw(context, mouseX, mouseY);
	}

	private void drawItemRow(DrawContext context, ItemStack stack, String tag, int need, int have, int x, int y) {
		context.drawItem(stack, x, y);
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null) context.drawStackOverlay(mc.textRenderer, stack, x, y);
		String line = tag + " " + have + "/" + need;
		drawLabel(context, line, x + 20, y + 4);
	}

	private static int count(Item item) {
		return InvUtils.find(item).count();
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
		if (button != 0) return true;
		for (WingButton b : buttons) {
			if (b.click(mouseX, mouseY)) return true;
		}
		return true;
	}
}
