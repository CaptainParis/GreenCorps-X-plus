package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Action button. A zero count is grey, and the click still reports a status line. */
public final class WBookAction extends WPressable {
	private String label;
	private boolean empty;
	private boolean selected;
	private String shown = "";
	private double shownWidth;

	public WBookAction(String label) {
		this.label = label == null ? "" : label;
	}

	public void setLabel(String label) {
		this.label = label == null ? "" : label;
	}

	public void setEmpty(boolean empty) {
		this.empty = empty;
	}

	public void setSelected(boolean selected) {
		this.selected = selected;
	}

	@Override
	protected void onCalculateSize() {
		double pad = pad();
		height = pad + theme.textHeight() + pad;
		if (minWidth > 0) width = minWidth;
		else width = pad + theme.textWidth(label) + pad;
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double pad = pad();
		shown = fit(label, Math.max(8, width - pad * 2));
		shownWidth = theme.textWidth(shown);
		Color fill;
		Color outline;
		Color fg;
		if (empty) {
			fill = new Color(36, 36, 40, 140);
			outline = new Color(70, 70, 76, 160);
			fg = new Color(150, 150, 155, 200);
		} else if (selected) {
			fill = pressed ? new Color(45, 95, 180, 230)
				: mouseOver ? new Color(50, 105, 195, 215) : new Color(40, 90, 175, 200);
			outline = new Color(30, 70, 140, 230);
			fg = new Color(235, 240, 255, 255);
		} else {
			fill = pressed ? new Color(70, 70, 78, 230)
				: mouseOver ? new Color(60, 60, 68, 210) : new Color(48, 48, 54, 200);
			outline = new Color(100, 100, 110, 220);
			fg = new Color(230, 230, 235, 255);
		}
		double radius = Math.min(height / 2.0, theme.scale(6));
		GuiRound.fill(renderer, x, y, width, height, radius, fill);
		GuiRound.outline(renderer, x, y, width, height, radius, outline, Math.max(1, theme.scale(1)));
		renderer.text(shown, x + (width - shownWidth) / 2.0, y + pad, fg, false);
	}

	private String fit(String text, double maxWidth) {
		if (text == null || text.isEmpty()) return "";
		if (theme.textWidth(text) <= maxWidth) return text;
		String ell = "...";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			sb.append(text.charAt(i));
			if (theme.textWidth(sb + ell) > maxWidth) {
				sb.setLength(Math.max(0, sb.length() - 1));
				return sb + ell;
			}
		}
		return text;
	}
}
