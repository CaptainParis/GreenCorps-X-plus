package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * Writable-book icon with a small Fill button drawn on top of it.
 */
public final class WBookTrigger extends WWidget {
	private static final ItemStack BOOK = new ItemStack(Items.WRITABLE_BOOK);

	private final Runnable action;
	private boolean pressed;

	public WBookTrigger(Runnable action) {
		this.action = action;
		tooltip = "Write the preset into a book and quill.";
	}

	@Override
	protected void onCalculateSize() {
		double icon = theme.scale(32);
		double[] btn = buttonSize();
		width = icon + btn[0] * 0.45;
		height = Math.max(icon, btn[1] + theme.scale(4));
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		float scale = (float) theme.scale(2);
		int ix = (int) x;
		int iy = (int) y;
		double[] box = buttonBox();
		boolean over = hit(mouseX, mouseY, box);

		renderer.post(() -> {
			renderer.item(BOOK, ix, iy, scale, false);
			renderer.beginRender();
			Color fill = pressed ? new Color(70, 90, 86, 235)
				: over ? new Color(56, 78, 74, 230) : new Color(32, 40, 38, 220);
			Color outline = new Color(140, 190, 175, 230);
			double radius = Math.min(box[3] / 2.0, theme.scale(3));
			GuiRound.fill(renderer, box[0], box[1], box[2], box[3], radius, fill);
			GuiRound.outline(renderer, box[0], box[1], box[2], box[3], radius, outline, Math.max(1, theme.scale(1)));
			String label = "Fill";
			double tx = box[0] + (box[2] - theme.textWidth(label)) / 2.0;
			double ty = box[1] + (box[3] - theme.textHeight()) / 2.0;
			renderer.text(label, tx, ty, new Color(235, 240, 236), false);
			renderer.endRender();
		});
	}

	@Override
	public boolean onMouseClicked(double mouseX, double mouseY, int button, boolean used) {
		if (used || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
		if (!hit(mouseX, mouseY, buttonBox())) return false;
		pressed = true;
		return true;
	}

	@Override
	public boolean onMouseReleased(double mouseX, double mouseY, int button) {
		if (!pressed) return false;
		pressed = false;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hit(mouseX, mouseY, buttonBox()) && action != null) {
			action.run();
		}
		return false;
	}

	private double[] buttonSize() {
		double pad = Math.max(2, theme.scale(2));
		double w = pad + theme.textWidth("Fill") + pad;
		double h = pad + theme.textHeight() + pad;
		return new double[] { w, h };
	}

	/** x, y, w, h of the button sitting on the upper-right of the book icon. */
	private double[] buttonBox() {
		double[] size = buttonSize();
		double icon = theme.scale(32);
		double bx = x + icon - size[0] * 0.55;
		double by = y + theme.scale(2);
		return new double[] { bx, by, size[0], size[1] };
	}

	private static boolean hit(double mx, double my, double[] box) {
		return mx >= box[0] && mx < box[0] + box[2] && my >= box[1] && my < box[1] + box[3];
	}
}
