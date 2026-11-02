package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Thin horizontal rule between list rows. */
public final class WRowRule extends WWidget {
	private static final Color LINE = new Color(90, 90, 100, 160);

	@Override
	protected void onCalculateSize() {
		width = theme.scale(40);
		height = theme.scale(5);
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double yMid = y + height / 2.0;
		double thick = Math.max(1, theme.scale(1));
		renderer.quad(x, yMid, width, thick, LINE);
	}
}
