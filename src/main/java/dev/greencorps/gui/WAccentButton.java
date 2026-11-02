package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Framed button that can use Meteor accent blue when selected. */
public final class WAccentButton extends WPressable {
	private String label;
	private boolean selected;
	private String shown = "";
	private double shownWidth;

	public WAccentButton(String label) {
		this.label = label == null ? "" : label;
	}

	public void set(String label) {
		this.label = label == null ? "" : label;
		invalidate();
	}

	public void setSelected(boolean selected) {
		this.selected = selected;
	}

	public boolean isSelected() {
		return selected;
	}

	@Override
	protected void onCalculateSize() {
		double pad = pad();
		height = pad + theme.textHeight() + pad;
		if (minWidth > 0) {
			width = minWidth;
		} else {
			shown = label;
			width = pad + theme.textWidth(shown) + pad;
		}
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double pad = pad();
		double maxText = Math.max(8, width - pad * 2);
		// Prefer full label when the expanded cell is wide enough; ellipsis only as last resort.
		if (theme.textWidth(label) <= maxText) {
			shown = label;
		} else {
			shown = fitText(label, maxText);
		}
		shownWidth = theme.textWidth(shown);

		Color accent = accentColor();
		Color fill;
		Color outline;
		Color fg;
		if (selected) {
			fill = new Color(accent.r, accent.g, accent.b, 200);
			outline = new Color(accent.r, accent.g, accent.b, 255);
			fg = new Color(255, 255, 255, 255);
		} else if (pressed) {
			fill = new Color(70, 70, 78, 230);
			outline = new Color(100, 100, 110, 220);
			fg = new Color(230, 230, 235, 255);
		} else if (mouseOver) {
			fill = new Color(60, 60, 68, 210);
			outline = new Color(100, 100, 110, 220);
			fg = new Color(230, 230, 235, 255);
		} else {
			fill = new Color(48, 48, 54, 200);
			outline = new Color(100, 100, 110, 220);
			fg = new Color(230, 230, 235, 255);
		}

		double radius = Math.min(height / 2.0, theme.scale(6));
		GuiRound.fill(renderer, x, y, width, height, radius, fill);
		GuiRound.outline(renderer, x, y, width, height, radius, outline, Math.max(1, theme.scale(1)));
		renderer.text(shown, x + (width - shownWidth) / 2.0, y + pad, fg, false);
	}

	private Color accentColor() {
		if (theme instanceof MeteorGuiTheme meteor) {
			return meteor.accentColor.get();
		}
		return new Color(80, 160, 255, 255);
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
}
