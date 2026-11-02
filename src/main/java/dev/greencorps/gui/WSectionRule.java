package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Thicker horizontal rule between major SignSearch sections. */
public final class WSectionRule extends WWidget {
	private static final Color LINE = new Color(100, 100, 112, 200);

	@Override
	protected void onCalculateSize() {
		width = theme.scale(40);
		height = theme.scale(10);
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double yMid = y + height / 2.0;
		double thick = Math.max(2, theme.scale(2));
		renderer.quad(x, yMid - thick / 2.0, width, thick, LINE);
	}
}
