package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Drag grip under a flex panel. Vertical drag reports delta via {@link #onDrag}.
 */
public final class WResizeHandle extends WWidget {
	private static final Color IDLE = new Color(90, 90, 100, 180);
	private static final Color HOT = new Color(140, 140, 155, 220);

	public DragHandler onDrag;

	private boolean dragging;
	private double lastY;

	@FunctionalInterface
	public interface DragHandler {
		void onDrag(double deltaY);
	}

	@Override
	protected void onCalculateSize() {
		width = theme.scale(80);
		height = theme.scale(10);
	}

	@Override
	public boolean onMouseClicked(double mouseX, double mouseY, int button, boolean used) {
		if (mouseOver && !used && button == 0) {
			dragging = true;
			lastY = mouseY;
			return true;
		}
		return false;
	}

	@Override
	public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
		if (!dragging || onDrag == null) return;
		double dy = mouseY - lastY;
		if (dy != 0) {
			onDrag.onDrag(dy);
			lastY = mouseY;
		}
	}

	@Override
	public boolean onMouseReleased(double mouseX, double mouseY, int button) {
		if (dragging && button == 0) {
			dragging = false;
			return true;
		}
		return false;
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		Color c = dragging || mouseOver ? HOT : IDLE;
		double barH = Math.max(2, theme.scale(2));
		double barW = Math.min(width * 0.45, theme.scale(48));
		double bx = x + (width - barW) / 2.0;
		double by = y + (height - barH) / 2.0;
		renderer.quad(bx, by, barW, barH, c);
	}
}
