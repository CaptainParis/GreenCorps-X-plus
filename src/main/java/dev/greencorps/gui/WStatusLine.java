package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Status text where digit runs draw in accent blue at normal text size (not title/bold).
 */
public final class WStatusLine extends WWidget {
	private String text = "";
	private static final Color GREY = new Color(160, 160, 170, 255);

	public void set(String text) {
		this.text = text == null ? "" : text;
		invalidate();
	}

	public String get() {
		return text;
	}

	@Override
	protected void onCalculateSize() {
		height = theme.textHeight();
		width = theme.scale(40);
		if (!text.isEmpty()) {
			width = Math.max(width, measure(text));
		}
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		Color accent = accentColor();
		double cursor = x;
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (isCountChar(c)) {
				int start = i;
				while (i < text.length() && isCountChar(text.charAt(i))) i++;
				String token = text.substring(start, i);
				renderer.text(token, cursor, y, accent, false);
				cursor += theme.textWidth(token);
			} else {
				int start = i;
				while (i < text.length() && !isCountChar(text.charAt(i))) i++;
				String token = text.substring(start, i);
				renderer.text(token, cursor, y, GREY, false);
				cursor += theme.textWidth(token);
			}
		}
	}

	private double measure(String s) {
		double w = 0;
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (isCountChar(c)) {
				int start = i;
				while (i < s.length() && isCountChar(s.charAt(i))) i++;
				w += theme.textWidth(s.substring(start, i));
			} else {
				int start = i;
				while (i < s.length() && !isCountChar(s.charAt(i))) i++;
				w += theme.textWidth(s.substring(start, i));
			}
		}
		return w;
	}

	private static boolean isCountChar(char c) {
		return (c >= '0' && c <= '9') || c == '+' || c == ',';
	}

	private Color accentColor() {
		if (theme instanceof MeteorGuiTheme meteor) {
			return meteor.accentColor.get();
		}
		return new Color(80, 160, 255, 255);
	}
}
