package dev.greencorps.gui.wing;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntConsumer;

/**
 * Small number box: click to type digits (Enter / click away to apply, Esc to cancel), scroll to step
 * (shift = ×8). While focused it owns the keyboard, so E does not close the screen.
 */
public final class WingNumberField {
	private final int min;
	private final int max;
	private final IntConsumer onChange;
	private int x;
	private int y;
	private int w;
	private int h;
	private int value;
	private boolean focused;
	private String text = "";

	public WingNumberField(int min, int max, IntConsumer onChange) {
		this.min = min;
		this.max = max;
		this.onChange = onChange;
	}

	public void place(int x, int y, int w, int h, int value) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		if (!focused) this.value = value;
	}

	public boolean focused() {
		return focused;
	}

	public boolean hit(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public void draw(DrawContext ctx) {
		ctx.fill(x, y, x + w, y + h, focused ? 0xFFFFFFFF : 0xFFA0A0A0);
		ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		String shown = focused ? text : Integer.toString(value);
		boolean caret = focused && (System.currentTimeMillis() / 500) % 2 == 0;
		int tw = tr.getWidth(shown);
		int tx = x + (w - tw) / 2;
		ctx.drawText(tr, shown, tx, y + (h - 8) / 2, 0xFFE0E0E0, true);
		if (caret) ctx.fill(tx + tw + 1, y + 3, tx + tw + 2, y + h - 3, 0xFFE0E0E0);
	}

	/** @return true if consumed */
	public boolean mouseClicked(double mx, double my) {
		if (hit(mx, my)) {
			if (!focused) {
				focused = true;
				text = "";
			}
			return true;
		}
		if (focused) commit();
		return false;
	}

	public boolean scroll(double mx, double my, double amount) {
		if (!hit(mx, my) || amount == 0) return false;
		int step = isShift() ? 8 : 1;
		set(value + (amount > 0 ? step : -step));
		return true;
	}

	/** @return true if consumed (every key while focused) */
	public boolean keyPressed(int key) {
		if (!focused) return false;
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			focused = false;
		} else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			commit();
		} else if (key == GLFW.GLFW_KEY_BACKSPACE) {
			if (!text.isEmpty()) text = text.substring(0, text.length() - 1);
		} else {
			int digit = digit(key);
			if (digit >= 0 && text.length() < 4) text += digit;
		}
		return true;
	}

	private void commit() {
		focused = false;
		if (!text.isEmpty()) set(Integer.parseInt(text));
	}

	private void set(int v) {
		value = Math.max(min, Math.min(max, v));
		onChange.accept(value);
	}

	private static int digit(int key) {
		if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return key - GLFW.GLFW_KEY_0;
		if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return key - GLFW.GLFW_KEY_KP_0;
		return -1;
	}

	private static boolean isShift() {
		long handle = MinecraftClient.getInstance().getWindow().getHandle();
		return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
			|| GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
	}
}
