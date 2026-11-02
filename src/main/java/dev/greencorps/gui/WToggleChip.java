package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Button-sized bordered toggle chip (Arm / Playable). Same footprint as grid buttons.
 */
public final class WToggleChip extends WPressable {
	private final String baseLabel;
	private boolean on;
	private String shown = "";
	private double shownWidth;

	public WToggleChip(String baseLabel, boolean on) {
		this.baseLabel = baseLabel == null ? "" : baseLabel;
		this.on = on;
	}

	public boolean isOn() {
		return on;
	}

	public void setOn(boolean on) {
		this.on = on;
	}

	public String fullLabel() {
		return baseLabel + ": " + (on ? "ON" : "OFF");
	}

	@Override
	protected void onPressed(int button) {
		on = !on;
		super.onPressed(button);
	}

	@Override
	protected void onCalculateSize() {
		double pad = pad();
		height = pad + theme.textHeight() + pad;
		if (minWidth > 0) {
			width = minWidth;
		} else {
			shown = ellipsize(fullLabel(), 18);
			width = pad + theme.textWidth(shown) + pad;
		}
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double pad = pad();
		double maxText = Math.max(8, width - pad * 2);
		shown = fitText(fullLabel(), maxText);
		shownWidth = theme.textWidth(shown);

		double radius = Math.min(height / 2.0, theme.scale(6));
		Color fill;
		Color outline;
		if (on) {
			// Meteor-like blue (match Scrub / Active checkboxes), framed chip shape kept.
			fill = pressed ? new Color(45, 95, 180, 230)
				: mouseOver ? new Color(50, 105, 195, 215) : new Color(40, 90, 175, 200);
			outline = new Color(30, 70, 140, 230);
		} else {
			fill = pressed ? new Color(55, 55, 60, 180)
				: mouseOver ? new Color(50, 50, 55, 160) : new Color(40, 40, 44, 120);
			outline = new Color(90, 90, 96, 220);
		}

		GuiRound.fill(renderer, x, y, width, height, radius, fill);
		GuiRound.outline(renderer, x, y, width, height, radius, outline, Math.max(1, theme.scale(1)));

		Color fg = on ? new Color(235, 240, 255, 255) : new Color(200, 200, 205, 230);
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
