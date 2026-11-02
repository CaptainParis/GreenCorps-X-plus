package dev.greencorps.gui;

import dev.greencorps.util.BookPages;
import meteordevelopment.meteorclient.gui.GuiKeyEvents;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Multiline preset box. Enter inserts a newline. Paste keeps line breaks.
 * Lines soft-wrap to the field width. Hard newlines stay only where Enter was pressed.
 * Length stops at {@link BookPages#CAPACITY}.
 */
public final class WBookText extends WWidget {
	private static final int VISIBLE_LINES = 6;

	public Runnable action;

	private String text;
	private int cursor;
	private int selOrigin;
	private int preferredCol;
	private int scrollLine;
	private boolean focused;
	private boolean dragging;
	private boolean suppress;

	public WBookText(String text) {
		this.text = text == null ? "" : text;
		this.cursor = this.text.length();
		this.selOrigin = this.cursor;
		this.preferredCol = 0;
	}

	public String get() {
		return text;
	}

	public int cursor() {
		return cursor;
	}

	/** Replaces the buffer without firing {@link #action}. */
	public void set(String value, int cursor) {
		suppress = true;
		this.text = value == null ? "" : value;
		if (this.text.length() > BookPages.CAPACITY) this.text = this.text.substring(0, BookPages.CAPACITY);
		this.cursor = clamp(cursor, 0, this.text.length());
		this.selOrigin = this.cursor;
		// Theme is assigned when the widget is added. Opening the module calls set first.
		this.preferredCol = theme == null ? rawColumn(this.cursor) : columnOf(this.cursor);
		suppress = false;
	}

	@Override
	protected void onCalculateSize() {
		double pad = pad();
		width = Math.max(theme.scale(220), pad * 2 + theme.scale(80));
		height = pad * 2 + theme.textHeight() * VISIBLE_LINES;
	}

	@Override
	public boolean render(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		if (focused) GuiKeyEvents.canUseKeys = false;
		return super.render(renderer, mouseX, mouseY, delta);
	}

	@Override
	protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
		double pad = pad();
		renderer.quad(x, y, width, height, new Color(20, 20, 24, focused ? 210 : 170));
		Color edge = focused ? new Color(120, 170, 160, 220) : new Color(70, 70, 78, 200);
		renderer.quad(x, y, width, 1, edge);
		renderer.quad(x, y + height - 1, width, 1, edge);
		renderer.quad(x, y, 1, height, edge);
		renderer.quad(x + width - 1, y, 1, height, edge);

		List<Vis> lines = visual();
		ensureCursorVisible(lines);
		double lineH = theme.textHeight();
		renderer.scissorStart(x + 1, y + 1, width - 2, height - 2);
		int last = Math.min(lines.size(), scrollLine + VISIBLE_LINES);
		for (int i = scrollLine; i < last; i++) {
			Vis line = lines.get(i);
			double ly = y + pad + (i - scrollLine) * lineH;
			int a = Math.max(selMin(), line.start);
			int b = Math.min(selMax(), line.start + line.shown.length());
			if (a < b) {
				int from = a - line.start;
				int to = b - line.start;
				double x0 = x + pad + theme.textWidth(line.shown.substring(0, from));
				double x1 = x + pad + theme.textWidth(line.shown.substring(0, to));
				renderer.quad(x0, ly, Math.max(1, x1 - x0), lineH, new Color(70, 120, 130, 140));
			}
			renderer.text(line.shown, x + pad, ly, new Color(230, 230, 235), false);
		}

		if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
			int line = visIndex(lines, cursor);
			if (line >= scrollLine && line < scrollLine + VISIBLE_LINES) {
				Vis ln = lines.get(line);
				int col = Math.min(columnOf(cursor), ln.shown.length());
				double cx = x + pad + theme.textWidth(ln.shown.substring(0, col));
				double cy = y + pad + (line - scrollLine) * lineH;
				renderer.quad(cx, cy, 1, lineH, new Color(230, 230, 235));
			}
		}
		renderer.scissorEnd();

		if (lines.size() > VISIBLE_LINES) {
			double track = height - pad * 2;
			double thumb = Math.max(theme.scale(8), track * VISIBLE_LINES / lines.size());
			double span = Math.max(1, lines.size() - VISIBLE_LINES);
			double ty = y + pad + (track - thumb) * (scrollLine / span);
			renderer.quad(x + width - theme.scale(3), ty, theme.scale(2), thumb, new Color(140, 140, 150, 180));
		}
	}

	@Override
	public boolean onMouseClicked(double mouseX, double mouseY, int button, boolean used) {
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
		if (!mouseOver || used) {
			if (!mouseOver) focused = false;
			return false;
		}
		focused = true;
		dragging = true;
		cursor = indexAt(mouseX, mouseY);
		selOrigin = cursor;
		preferredCol = columnOf(cursor);
		return true;
	}

	@Override
	public boolean onMouseReleased(double mouseX, double mouseY, int button) {
		dragging = false;
		return false;
	}

	@Override
	public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
		if (!dragging || !focused) return;
		cursor = indexAt(mouseX, mouseY);
		preferredCol = columnOf(cursor);
	}

	@Override
	public boolean onMouseScrolled(double amount) {
		if (!mouseOver) return false;
		int lines = visual().size();
		if (lines <= VISIBLE_LINES) return false;
		scrollLine -= (int) Math.signum(amount);
		scrollLine = clamp(scrollLine, 0, lines - VISIBLE_LINES);
		return true;
	}

	@Override
	public boolean onKeyPressed(int key, int mods) {
		return handleKey(key, mods);
	}

	@Override
	public boolean onKeyRepeated(int key, int mods) {
		return handleKey(key, mods);
	}

	@Override
	public boolean onCharTyped(char c) {
		if (!focused) return false;
		if (c < 32 || c == 127) return false;
		insert(String.valueOf(c));
		return true;
	}

	private boolean handleKey(int key, int mods) {
		if (!focused) return false;
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			focused = false;
			dragging = false;
			return false;
		}

		boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0 || (mods & GLFW.GLFW_MOD_SUPER) != 0;
		boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;

		if (ctrl && key == GLFW.GLFW_KEY_A) {
			selOrigin = 0;
			cursor = text.length();
			preferredCol = columnOf(cursor);
			ensureCursorVisible(visual());
			return true;
		}
		if (ctrl && key == GLFW.GLFW_KEY_C) {
			copySel();
			return true;
		}
		if (ctrl && key == GLFW.GLFW_KEY_X) {
			copySel();
			deleteSel();
			changed();
			return true;
		}
		if (ctrl && key == GLFW.GLFW_KEY_V) {
			String clip = MinecraftClient.getInstance().keyboard.getClipboard();
			if (clip != null) insert(clip);
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			insert("\n");
			return true;
		}
		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			if (hasSel()) deleteSel();
			else if (cursor > 0) {
				int from = ctrl ? jump(cursor, -1) : cursor - 1;
				text = text.substring(0, from) + text.substring(cursor);
				cursor = from;
				selOrigin = cursor;
			}
			preferredCol = columnOf(cursor);
			ensureCursorVisible(visual());
			changed();
			return true;
		}
		if (key == GLFW.GLFW_KEY_DELETE) {
			if (hasSel()) deleteSel();
			else if (cursor < text.length()) {
				int to = ctrl ? jump(cursor, 1) : cursor + 1;
				text = text.substring(0, cursor) + text.substring(to);
			}
			preferredCol = columnOf(cursor);
			changed();
			return true;
		}
		if (key == GLFW.GLFW_KEY_LEFT) {
			moveHoriz(ctrl ? -2 : -1, shift);
			return true;
		}
		if (key == GLFW.GLFW_KEY_RIGHT) {
			moveHoriz(ctrl ? 2 : 1, shift);
			return true;
		}
		if (key == GLFW.GLFW_KEY_UP) {
			moveVert(-1, shift);
			return true;
		}
		if (key == GLFW.GLFW_KEY_DOWN) {
			moveVert(1, shift);
			return true;
		}
		if (key == GLFW.GLFW_KEY_HOME) {
			cursor = ctrl ? 0 : visual().get(visIndex(visual(), cursor)).start;
			if (!shift) selOrigin = cursor;
			preferredCol = columnOf(cursor);
			ensureCursorVisible(visual());
			return true;
		}
		if (key == GLFW.GLFW_KEY_END) {
			if (ctrl) cursor = text.length();
			else {
				Vis line = visual().get(visIndex(visual(), cursor));
				cursor = line.start + line.shown.length();
			}
			if (!shift) selOrigin = cursor;
			preferredCol = columnOf(cursor);
			ensureCursorVisible(visual());
			return true;
		}
		return true;
	}

	private void moveHoriz(int dir, boolean shift) {
		if (!shift && hasSel()) {
			cursor = dir < 0 ? selMin() : selMax();
			selOrigin = cursor;
			preferredCol = columnOf(cursor);
			ensureCursorVisible(visual());
			return;
		}
		if (dir == -2 || dir == 2) cursor = jump(cursor, dir);
		else if (dir < 0 && cursor > 0) cursor--;
		else if (dir > 0 && cursor < text.length()) cursor++;
		if (!shift) selOrigin = cursor;
		preferredCol = columnOf(cursor);
		ensureCursorVisible(visual());
	}

	private void moveVert(int dir, boolean shift) {
		List<Vis> lines = visual();
		int line = visIndex(lines, cursor);
		int next = line + dir;
		if (next < 0 || next >= lines.size()) return;
		Vis dest = lines.get(next);
		int col = Math.min(preferredCol, dest.shown.length());
		cursor = dest.start + col;
		if (!shift) selOrigin = cursor;
		ensureCursorVisible(lines);
	}

	private void insert(String raw) {
		if (raw == null || raw.isEmpty()) return;
		String s = raw.replace("\r\n", "\n").replace('\r', '\n');
		deleteSel();
		int room = BookPages.CAPACITY - text.length();
		if (room <= 0 || s.isEmpty()) {
			changed();
			return;
		}
		if (s.length() > room) s = s.substring(0, room);
		text = text.substring(0, cursor) + s + text.substring(cursor);
		cursor += s.length();
		selOrigin = cursor;
		preferredCol = columnOf(cursor);
		ensureCursorVisible(visual());
		changed();
	}

	private void copySel() {
		if (!hasSel()) return;
		MinecraftClient.getInstance().keyboard.setClipboard(text.substring(selMin(), selMax()));
	}

	private void deleteSel() {
		if (!hasSel()) return;
		int a = selMin();
		int b = selMax();
		text = text.substring(0, a) + text.substring(b);
		cursor = a;
		selOrigin = cursor;
	}

	private void changed() {
		if (suppress) return;
		if (action != null) action.run();
	}

	private void ensureCursorVisible(List<Vis> lines) {
		int line = visIndex(lines, cursor);
		if (line < scrollLine) scrollLine = line;
		else if (line >= scrollLine + VISIBLE_LINES) scrollLine = line - VISIBLE_LINES + 1;
		scrollLine = clamp(scrollLine, 0, Math.max(0, lines.size() - VISIBLE_LINES));
	}

	private int indexAt(double mouseX, double mouseY) {
		List<Vis> lines = visual();
		double pad = pad();
		double lineH = theme.textHeight();
		int line = scrollLine + (int) Math.floor((mouseY - y - pad) / lineH);
		line = clamp(line, 0, lines.size() - 1);
		Vis ln = lines.get(line);
		double localX = mouseX - x - pad;
		int col = ln.shown.length();
		for (int i = 0; i < ln.shown.length(); i++) {
			double prev = theme.textWidth(ln.shown.substring(0, i));
			double next = theme.textWidth(ln.shown.substring(0, i + 1));
			if (localX < (prev + next) / 2.0) {
				col = i;
				break;
			}
		}
		return ln.start + col;
	}

	private List<Vis> visual() {
		List<Vis> out = new ArrayList<>();
		double max = theme == null ? Double.MAX_VALUE : Math.max(theme.scale(8), width - pad() * 2);
		int n = text.length();
		if (n == 0) {
			out.add(new Vis(0, 0, ""));
			return out;
		}
		int i = 0;
		while (i <= n) {
			if (i == n) break;
			if (text.charAt(i) == '\n') {
				out.add(new Vis(i, i, ""));
				i++;
				continue;
			}
			int start = i;
			int lastBreak = -1;
			int j = i;
			while (j < n && text.charAt(j) != '\n') {
				if (theme.textWidth(text.substring(start, j + 1)) > max && j > start) break;
				if (text.charAt(j) == ' ') lastBreak = j;
				j++;
			}
			if (j < n && text.charAt(j) != '\n' && j > start) {
				if (lastBreak >= start) {
					out.add(new Vis(start, lastBreak, text.substring(start, lastBreak)));
					i = lastBreak + 1;
					continue;
				}
				out.add(new Vis(start, j, text.substring(start, j)));
				i = j;
				continue;
			}
			out.add(new Vis(start, j, text.substring(start, j)));
			i = j;
			if (i < n && text.charAt(i) == '\n') {
				i++;
				if (i == n) out.add(new Vis(i, i, ""));
			}
		}
		if (out.isEmpty()) out.add(new Vis(0, 0, ""));
		return out;
	}

	private int visIndex(List<Vis> lines, int pos) {
		for (int i = 0; i < lines.size(); i++) {
			int next = i + 1 < lines.size() ? lines.get(i + 1).start : Integer.MAX_VALUE;
			if (pos < next) return i;
		}
		return Math.max(0, lines.size() - 1);
	}

	private int columnOf(int pos) {
		List<Vis> lines = visual();
		Vis line = lines.get(visIndex(lines, pos));
		return clamp(pos - line.start, 0, line.shown.length());
	}

	/** Column since the previous hard newline. Used before the widget has a theme. */
	private int rawColumn(int pos) {
		int end = clamp(pos, 0, text.length());
		int start = 0;
		for (int i = 0; i < end; i++) {
			if (text.charAt(i) == '\n') start = i + 1;
		}
		return end - start;
	}

	private int jump(int from, int dir) {
		int i = from;
		if (dir < 0) {
			while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
			while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
		} else {
			while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
			while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
		}
		return i;
	}

	private boolean hasSel() {
		return cursor != selOrigin;
	}

	private int selMin() {
		return Math.min(cursor, selOrigin);
	}

	private int selMax() {
		return Math.max(cursor, selOrigin);
	}

	private static int clamp(int v, int min, int max) {
		if (max < min) return min;
		return Math.max(min, Math.min(max, v));
	}

	private record Vis(int start, int end, String shown) {
	}
}
