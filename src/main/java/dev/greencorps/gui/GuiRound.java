package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Rounded-rect helpers for Meteor GuiRenderer (no native round API). */
final class GuiRound {
	private GuiRound() {}

	static void fill(GuiRenderer r, double x, double y, double w, double h, double radius, Color color) {
		if (w <= 0 || h <= 0) return;
		double rad = Math.max(0, Math.min(radius, Math.min(w, h) / 2.0));
		int rows = Math.max(1, (int) Math.ceil(h));
		for (int row = 0; row < rows; row++) {
			double ly = row + 0.5;
			double left;
			double right;
			if (ly < rad) {
				double dy = rad - ly;
				double dx = Math.sqrt(Math.max(0, rad * rad - dy * dy));
				left = x + rad - dx;
				right = x + w - rad + dx;
			} else if (ly > h - rad) {
				double dy = ly - (h - rad);
				double dx = Math.sqrt(Math.max(0, rad * rad - dy * dy));
				left = x + rad - dx;
				right = x + w - rad + dx;
			} else {
				left = x;
				right = x + w;
			}
			double span = right - left;
			if (span > 0) r.quad(left, y + row, span, 1, color);
		}
	}

	static void outline(GuiRenderer r, double x, double y, double w, double h, double radius, Color color, double thickness) {
		if (w <= 0 || h <= 0 || thickness <= 0) return;
		double t = Math.max(1, thickness);
		double rad = Math.max(0, Math.min(radius, Math.min(w, h) / 2.0));
		int rows = Math.max(1, (int) Math.ceil(h));
		for (int row = 0; row < rows; row++) {
			double ly = row + 0.5;
			double outerLeft;
			double outerRight;
			if (ly < rad) {
				double dy = rad - ly;
				double dx = Math.sqrt(Math.max(0, rad * rad - dy * dy));
				outerLeft = x + rad - dx;
				outerRight = x + w - rad + dx;
			} else if (ly > h - rad) {
				double dy = ly - (h - rad);
				double dx = Math.sqrt(Math.max(0, rad * rad - dy * dy));
				outerLeft = x + rad - dx;
				outerRight = x + w - rad + dx;
			} else {
				outerLeft = x;
				outerRight = x + w;
			}

			boolean edgeRow = ly <= t || ly >= h - t;
			if (edgeRow) {
				double span = outerRight - outerLeft;
				if (span > 0) r.quad(outerLeft, y + row, span, 1, color);
				continue;
			}

			double inner = Math.max(0, rad - t);
			double innerLeft;
			double innerRight;
			if (ly < rad) {
				double dy = rad - ly;
				double dx = dy < inner ? Math.sqrt(Math.max(0, inner * inner - dy * dy)) : 0;
				innerLeft = x + rad - dx;
				innerRight = x + w - rad + dx;
			} else if (ly > h - rad) {
				double dy = ly - (h - rad);
				double dx = dy < inner ? Math.sqrt(Math.max(0, inner * inner - dy * dy)) : 0;
				innerLeft = x + rad - dx;
				innerRight = x + w - rad + dx;
			} else {
				innerLeft = x + t;
				innerRight = x + w - t;
			}
			double leftSpan = Math.max(0, Math.min(t, innerLeft - outerLeft));
			double rightSpan = Math.max(0, Math.min(t, outerRight - innerRight));
			if (leftSpan > 0) r.quad(outerLeft, y + row, leftSpan, 1, color);
			if (rightSpan > 0) r.quad(outerRight - rightSpan, y + row, rightSpan, 1, color);
		}
	}
}
