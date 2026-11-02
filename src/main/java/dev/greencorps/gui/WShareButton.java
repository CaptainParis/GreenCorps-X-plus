package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Square icon button: share glyph (node + two links). Height matches {@link WFrameButton}. */
public final class WShareButton extends WPressable {
	@Override
	protected void onCalculateSize() {
		double side = pad() + theme.textHeight() + pad();
		height = side;
		width = minWidth > 0 ? Math.max(side, minWidth) : side;
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		Color fill = pressed ? new Color(70, 70, 78, 230)
			: mouseOver ? new Color(60, 60, 68, 210) : new Color(48, 48, 54, 200);
		Color outline = new Color(100, 100, 110, 220);
		Color fg = new Color(230, 230, 235, 255);
		double radius = theme.scale(4);
		GuiRound.fill(renderer, x, y, width, height, radius, fill);
		GuiRound.outline(renderer, x, y, width, height, radius, outline, Math.max(1, theme.scale(1)));

		double cx = x + width / 2.0;
		double cy = y + height / 2.0;
		double s = Math.min(width, height) * 0.18;
		// Three dots: top-right, mid-left, bottom-right
		double x1 = cx + s * 1.2, y1 = cy - s * 1.4;
		double x2 = cx - s * 1.4, y2 = cy;
		double x3 = cx + s * 1.2, y3 = cy + s * 1.4;
		renderer.quad(x1 - s * 0.55, y1 - s * 0.55, s * 1.1, s * 1.1, fg);
		renderer.quad(x2 - s * 0.55, y2 - s * 0.55, s * 1.1, s * 1.1, fg);
		renderer.quad(x3 - s * 0.55, y3 - s * 0.55, s * 1.1, s * 1.1, fg);
		// Links
		double t = Math.max(1.0, theme.scale(1.2));
		drawLine(renderer, x2, y2, x1, y1, t, fg);
		drawLine(renderer, x2, y2, x3, y3, t, fg);
	}

	private static void drawLine(GuiRenderer r, double x0, double y0, double x1, double y1,
								 double thickness, Color color) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double len = Math.sqrt(dx * dx + dy * dy);
		if (len < 0.1) return;
		double px = -dy / len * thickness * 0.5;
		double py = dx / len * thickness * 0.5;
		// Approximate with thin quad via two triangles — Meteor quad is axis-aligned, use short segments.
		int steps = Math.max(2, (int) (len / 2));
		for (int i = 0; i < steps; i++) {
			double a = i / (double) steps;
			double b = (i + 1) / (double) steps;
			double sx = x0 + dx * a;
			double sy = y0 + dy * a;
			double ex = x0 + dx * b;
			double ey = y0 + dy * b;
			double mx = (sx + ex) / 2.0;
			double my = (sy + ey) / 2.0;
			r.quad(mx - thickness * 0.5, my - thickness * 0.5, thickness, thickness, color);
		}
	}
}
