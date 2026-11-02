package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Compact colored name chip for stasis owner column. */
public final class WOwnerChip extends WWidget {
	private final String name;
	private final int bgRgb;
	private String shown = "";
	private double shownWidth;

	public WOwnerChip(String name, int bgRgb) {
		this.name = name == null || name.isBlank() ? "?" : name;
		this.bgRgb = bgRgb & 0xFFFFFF;
	}

	@Override
	protected void onCalculateSize() {
		double pad = theme.scale(3);
		height = pad + theme.textHeight() + pad;
		shown = name.length() > 16 ? name.substring(0, 15) + "..." : name;
		shownWidth = theme.textWidth(shown);
		width = pad * 2 + shownWidth;
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double pad = theme.scale(3);
		Color fill = new Color((bgRgb >> 16) & 0xFF, (bgRgb >> 8) & 0xFF, bgRgb & 0xFF, 200);
		double radius = Math.min(height / 2.0, theme.scale(5));
		GuiRound.fill(renderer, x, y, width, height, radius, fill);
		Color fg = contrast(fill);
		renderer.text(shown, x + pad, y + pad, fg, false);
	}

	/** Stable pastel hash from IGN. */
	public static int colorFor(String ign) {
		if (ign == null) return 0x5A6A7A;
		int h = ign.toLowerCase().hashCode();
		int r = 90 + ((h >>> 16) & 0x7F);
		int g = 90 + ((h >>> 8) & 0x7F);
		int b = 90 + (h & 0x7F);
		return (r << 16) | (g << 8) | b;
	}

	private static Color contrast(Color bg) {
		double lum = (0.299 * bg.r + 0.587 * bg.g + 0.114 * bg.b) / 255.0;
		return lum > 0.55 ? new Color(20, 20, 20, 255) : new Color(245, 245, 245, 255);
	}
}
