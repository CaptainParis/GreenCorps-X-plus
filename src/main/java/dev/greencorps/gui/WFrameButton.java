package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Fixed-width framed action button (ellipsis + tooltip). Used for even grid matrices.
 */
public final class WFrameButton extends WPressable {
	private final String label;
	private String shown = "";
	private double shownWidth;

	public WFrameButton(String label) {
		this.label = label == null ? "" : label;
	}

	@Override
	protected void onCalculateSize() {
		double pad = pad();
		height = pad + theme.textHeight() + pad;
		if (minWidth > 0) {
			width = minWidth;
		} else {
			shown = ellipsize(label, 22);
			width = pad + theme.textWidth(shown) + pad;
		}
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double pad = pad();
		double maxText = Math.max(8, width - pad * 2);
		shown = fitText(label, maxText);
		shownWidth = theme.textWidth(shown);

		Color fill = pressed ? new Color(70, 70, 78, 230)
			: mouseOver ? new Color(60, 60, 68, 210) : new Color(48, 48, 54, 200);
		Color outline = new Color(100, 100, 110, 220);
		double radius = Math.min(height / 2.0, theme.scale(6));

		GuiRound.fill(renderer, x, y, width, height, radius, fill);
		GuiRound.outline(renderer, x, y, width, height, radius, outline, Math.max(1, theme.scale(1)));

		Color fg = new Color(230, 230, 235, 255);
		renderer.text(shown, x + (width - shownWidth) / 2.0, y + pad, fg, false);
	}

	private String fitText(String text, double maxWidth) {
		if (text == null || text.isEmpty()) return "";
		if (theme.textWidth(text) <= maxWidth) return text;
		String ell = "...";
		if (theme.textWidth(ell) >= maxWidth) return ell;
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
