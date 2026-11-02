package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Outline-only rounded card around a SignSearch result (no fill). */
public final class WSignCard extends WVerticalList {
	private static final Color BORDER = new Color(110, 110, 122, 200);

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double thick = Math.max(1, theme.scale(1));
		double radius = Math.min(height / 2.0, theme.scale(6));
		GuiRound.outline(renderer, x, y, width, height, radius, BORDER, thick);
		super.onRender(renderer, mouseX, mouseY, delta);
	}
}
