package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Thin vertical rule between sidebar and detail columns. */
public final class WColRule extends WWidget {
	private static final Color LINE = new Color(90, 90, 100, 160);

	@Override
	protected void onCalculateSize() {
		width = theme.scale(8);
		height = theme.scale(40);
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double xMid = x + width / 2.0;
		double thick = Math.max(1, theme.scale(1));
		renderer.quad(xMid - thick / 2.0, y, thick, height, LINE);
	}
}
