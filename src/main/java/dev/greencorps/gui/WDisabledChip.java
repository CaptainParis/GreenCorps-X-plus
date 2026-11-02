package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Non-pressable greyed pill chip for N/A grid cells (same height language as WWaypointChip).
 */
public final class WDisabledChip extends WWidget {
	private final String label;
	private String shown = "";
	private double shownWidth;

	public WDisabledChip(String label) {
		this.label = label == null ? "" : label;
	}

	@Override
	protected void onCalculateSize() {
		double pad = pad();
		height = pad + theme.textHeight() + pad;
		if (minWidth > 0) {
			width = minWidth;
		} else {
			shown = ellipsize(label, 28);
			shownWidth = theme.textWidth(shown);
			width = pad + Math.max(theme.textWidth("..."), shownWidth) + pad;
		}
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double pad = pad();
		double maxText = Math.max(8, width - pad * 2);
		shown = fitText(label, maxText);
		shownWidth = theme.textWidth(shown);

		Color fill = new Color(70, 70, 74, 160);
		Color outline = new Color(45, 45, 48, 200);
		double radius = Math.min(height / 2.0, theme.scale(6));

		GuiRound.fill(renderer, x, y, width, height, radius, fill);
		GuiRound.outline(renderer, x, y, width, height, radius, outline, Math.max(1, theme.scale(1)));

		Color fg = new Color(160, 160, 165, 220);
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
