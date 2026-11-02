package dev.greencorps.gui;

import dev.greencorps.util.XaeroWaypoints;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Colored suggestion chip for nearest Xaero waypoints (flex width via expandX parent cell).
 * Draws a rounded/pill fill — Meteor has no rounded-quad API.
 */
public final class WWaypointChip extends WPressable {
	private final String displayName;
	private final int bgRgb;
	private String shown = "";
	private double shownWidth;

	public WWaypointChip(String fullName, int bgRgb) {
		this.displayName = XaeroWaypoints.displayName(fullName);
		this.bgRgb = bgRgb & 0xFFFFFF;
	}

	@Override
	protected void onCalculateSize() {
		double pad = pad();
		height = pad + theme.textHeight() + pad;
		if (minWidth > 0) {
			width = minWidth;
		} else {
			shown = ellipsize(displayName, 18);
			shownWidth = theme.textWidth(shown);
			width = pad + Math.max(theme.textWidth("..."), shownWidth) + pad;
		}
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double pad = pad();
		double maxText = Math.max(8, width - pad * 2);
		shown = fitText(displayName, maxText);
		shownWidth = theme.textWidth(shown);

		Color fill = bgColor(pressed, mouseOver);
		Color outline = darker(fill, 0.55f);
		double radius = Math.min(height / 2.0, theme.scale(6));

		GuiRound.fill(renderer, x, y, width, height, radius, fill);
		GuiRound.outline(renderer, x, y, width, height, radius, outline, Math.max(1, theme.scale(1)));

		Color fg = contrastText(fill);
		renderer.text(shown, x + (width - shownWidth) / 2.0, y + pad, fg, false);
	}

	private Color bgColor(boolean pressed, boolean hover) {
		int r = (bgRgb >> 16) & 0xFF;
		int g = (bgRgb >> 8) & 0xFF;
		int b = bgRgb & 0xFF;
		int a = pressed ? 230 : hover ? 210 : 190;
		return new Color(r, g, b, a);
	}

	private static Color darker(Color c, float factor) {
		return new Color(
			Math.max(0, (int) (c.r * factor)),
			Math.max(0, (int) (c.g * factor)),
			Math.max(0, (int) (c.b * factor)),
			Math.min(255, c.a + 30)
		);
	}

	private static Color contrastText(Color bg) {
		double lum = (0.299 * bg.r + 0.587 * bg.g + 0.114 * bg.b) / 255.0;
		return lum > 0.55 ? new Color(20, 20, 20, 255) : new Color(245, 245, 245, 255);
	}

	private String fitText(String text, double maxWidth) {
		if (text == null || text.isEmpty()) return "";
		if (theme.textWidth(text) <= maxWidth) return text;
		String ell = "...";
		double ellW = theme.textWidth(ell);
		if (ellW >= maxWidth) return ell;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			sb.append(text.charAt(i));
			if (theme.textWidth(sb + ell) > maxWidth) {
				sb.setLength(Math.max(0, sb.length() - 1));
				break;
			}
		}
		while (sb.length() > 0 && theme.textWidth(sb + ell) > maxWidth) {
			sb.setLength(sb.length() - 1);
		}
		return sb + ell;
	}

	private static String ellipsize(String text, int maxChars) {
		if (text == null) return "";
		if (text.length() <= maxChars) return text;
		if (maxChars <= 3) return "...";
		return text.substring(0, maxChars - 3) + "...";
	}
}
