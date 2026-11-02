package dev.greencorps.gui.tooltip;

import meteordevelopment.meteorclient.utils.tooltip.MeteorTooltipData;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Shulker / ender chest tooltip: a shulker-GUI panel (bevel, title strip, inset body, footer) in the box's
 * colours (see {@link Palette}). It is {@link #standalone() standalone}: {@code MixinDrawContextTooltipGc} draws it instead of the
 * whole vanilla tooltip, so there is no background, item list or duplicate title around it.
 * <p>
 * Preview style shows the 9×3 slot grid; List style shows the contents as compact vanilla-height lines
 * (small icon, name, total) with identical items merged, in two columns past {@link #LIST_ONE_COLUMN} entries.
 */
public final class GcStorageTooltipComponent implements TooltipComponent, MeteorTooltipData {
	private static final int SLOT = 18;
	private static final int COLS = 9;
	private static final int ROWS = 3;
	/** Vanilla container metrics: 176 wide, slots from x 7, 7px bottom margin; the title strip grows with wrapping. */
	private static final int FRAME_W = 176;
	private static final int GRID_X = 7;
	/** Title strip starts at TITLE_TOP − 1: one pixel below the 2px top bevel. */
	private static final int TITLE_TOP = 5;
	private static final int LINE_H = 10;
	/** Title text inset inside the strip. */
	private static final int TITLE_PAD = 3;
	/** A full container: 27 slots × 64. Fill counts each item as 64 / its stack size, so 27 totems are full. */
	private static final int CAPACITY = 27 * 64;
	private static final int BOTTOM = 7;
	private static final int FOOTER_H = 11;

	/** List style: one column up to this many entries, then two. */
	private static final int LIST_ONE_COLUMN = 14;
	private static final int LIST_PAD = 3;
	private static final int LIST_ICON = 8;
	private static final int LIST_COL_GAP = 8;
	private static final int LIST_MAX_COL_W = 150;

	// Vanilla GUI greys, multiplied by the tint.
	private static final int BODY = 0xC6;
	private static final int BEVEL_LIGHT = 0xFF;
	private static final int BEVEL_DARK = 0x55;
	private static final int SLOT_FILL = 0x8B;
	private static final int SLOT_DARK = 0x37;

	/**
	 * Frame colours. {@link #tinted} multiplies vanilla's GUI greys by a dye colour, with a lighter dye accent line
	 * under the title; {@link #ENDER} is drawn from the ender chest texture (dark teal body, ender-eye green accent).
	 */
	public record Palette(int body, int light, int dark, int slotFill, int slotDark, int slotLight, int accent) {
		public static final Palette ENDER = new Palette(
			0xFF1C2E2C, 0xFF35574F, 0xFF0B1413, 0xFF132220, 0xFF070D0C, 0xFF2C4A43, 0xFF56C7A6);

		public static Palette tinted(int tintArgb) {
			return new Palette(mul(tintArgb, BODY), mul(tintArgb, BEVEL_LIGHT), mul(tintArgb, BEVEL_DARK),
				mul(tintArgb, SLOT_FILL), mul(tintArgb, SLOT_DARK), mul(tintArgb, BEVEL_LIGHT), brighten(tintArgb, 0.35f));
		}

		/** The dye colour mixed towards white: reads as an accent on the tinted body. */
		private static int brighten(int tint, float towardsWhite) {
			int r = (tint >>> 16) & 0xFF, g = (tint >>> 8) & 0xFF, b = tint & 0xFF;
			r += (int) ((255 - r) * towardsWhite);
			g += (int) ((255 - g) * towardsWhite);
			b += (int) ((255 - b) * towardsWhite);
			return 0xFF000000 | (r << 16) | (g << 8) | b;
		}

		/** Vanilla grey level {@code g} multiplied by the tint, like a GUI texture tinted with the dye colour. */
		private static int mul(int tint, int g) {
			int r = ((tint >>> 16) & 0xFF) * g / 255;
			int gr = ((tint >>> 8) & 0xFF) * g / 255;
			int b = (tint & 0xFF) * g / 255;
			return 0xFF000000 | (r << 16) | (gr << 8) | b;
		}
	}

	private final ItemStack[] items;
	private final Palette palette;
	private final String title;
	private final String sizeLine;
	private final String emptyHint;
	private final boolean listMode;
	private final List<ItemStack> entries;
	/** Empty shulker: title strip and size line only. */
	private boolean empty;

	/**
	 * @param items27  contents, or null when unknown (then {@code emptyHint} is shown in the body)
	 * @param palette  frame colours ({@link Palette#tinted} with white = plain vanilla grey)
	 * @param sizeLine footer text on the left (the item size), or empty
	 */
	public GcStorageTooltipComponent(
		ItemStack[] items27,
		Palette palette,
		String title,
		String sizeLine,
		String emptyHint,
		boolean listMode
	) {
		this.items = items27;
		this.palette = palette;
		this.title = title == null ? "" : title;
		this.sizeLine = sizeLine == null ? "" : sizeLine;
		this.emptyHint = emptyHint == null ? "" : emptyHint;
		this.listMode = listMode;
		this.entries = listMode ? merged() : List.of();
	}

	/** Frame for an empty shulker: the tinted panel with its title strip and the size line, nothing else. */
	public static GcStorageTooltipComponent empty(Palette palette, String title, String sizeLine) {
		GcStorageTooltipComponent c = new GcStorageTooltipComponent(new ItemStack[0], palette, title, sizeLine, "", false);
		c.empty = true;
		return c;
	}

	@Override
	public TooltipComponent getComponent() {
		return this;
	}

	/** Both styles replace the whole tooltip. */
	public boolean standalone() {
		return true;
	}

	private ItemStack slot(int i) {
		if (items == null || i >= items.length || items[i] == null) return ItemStack.EMPTY;
		return items[i];
	}

	/** Contents with identical items (same item and components) summed, in first-seen order. */
	private List<ItemStack> merged() {
		List<ItemStack> out = new ArrayList<>();
		for (int i = 0; i < COLS * ROWS; i++) {
			ItemStack s = slot(i);
			if (s.isEmpty()) continue;
			ItemStack same = null;
			for (ItemStack o : out) {
				if (ItemStack.areItemsAndComponentsEqual(o, s)) {
					same = o;
					break;
				}
			}
			if (same == null) out.add(s.copy());
			else same.setCount(same.getCount() + s.getCount());
		}
		return out;
	}

	@Override
	public int getHeight(TextRenderer textRenderer) {
		if (empty) {
			// Compact: strip, accent line, then the size line (if any) right under it.
			int strip = stripBottom(textRenderer) + 1;
			return hasFooter() ? strip + 2 + 14 : strip + 6;
		}
		return bodyY(textRenderer) + bodyH() + BOTTOM + (hasFooter() ? FOOTER_H : 0);
	}

	@Override
	public int getWidth(TextRenderer textRenderer) {
		if (empty) {
			int content = Math.max(textRenderer.getWidth(title), textRenderer.getWidth(sizeLine)) + (GRID_X + TITLE_PAD) * 2;
			return Math.min(FRAME_W, Math.max(64, content));
		}
		if (!listMode) return FRAME_W;
		return Math.max(FRAME_W, GRID_X * 2 + listW(textRenderer));
	}

	@Override
	public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext context) {
		drawFrame(textRenderer, x, y, context);
	}

	// ------------------------------------------------------------- frame --

	/** Fill weighted by stack size (a 16-stack item counts as 4), out of {@link #CAPACITY}. */
	private int fill() {
		double f = 0;
		for (int i = 0; i < COLS * ROWS; i++) {
			ItemStack s = slot(i);
			if (!s.isEmpty()) f += s.getCount() * 64.0 / Math.max(1, s.getMaxCount());
		}
		return (int) Math.round(Math.min(CAPACITY, f));
	}

	private String fillText() {
		if (items == null || empty) return "";
		int f = fill();
		return f + "/" + CAPACITY + " (" + Math.round(f * 100.0 / CAPACITY) + "%)";
	}

	/** Footer row under the body: size left, fill right. */
	private boolean hasFooter() {
		return !sizeLine.isEmpty() || !fillText().isEmpty();
	}

	private List<OrderedText> titleLines(TextRenderer tr) {
		List<OrderedText> lines = tr.wrapLines(Text.literal(title), getWidth(tr) - GRID_X * 2 - TITLE_PAD * 2);
		return lines.isEmpty() ? List.of(OrderedText.EMPTY) : lines;
	}

	private int bodyY(TextRenderer tr) {
		return TITLE_TOP + titleLines(tr).size() * LINE_H + 5;
	}

	/** Offset of the first row below the title strip (where the accent line goes). */
	private int stripBottom(TextRenderer tr) {
		return TITLE_TOP + titleLines(tr).size() * LINE_H + 1;
	}

	private int bodyH() {
		if (empty) return 0;
		if (!listMode) return ROWS * SLOT;
		return Math.max(1, listRows()) * LINE_H + LIST_PAD * 2;
	}

	private void drawFrame(TextRenderer tr, int x, int y, DrawContext ctx) {
		int w = getWidth(tr);
		int h = getHeight(tr);
		int body = palette.body();
		int light = palette.light();
		int dark = palette.dark();
		int outline = 0xFF000000;

		// Rounded black outline around the body.
		ctx.fill(x + 2, y, x + w - 2, y + 1, outline);
		ctx.fill(x + 2, y + h - 1, x + w - 2, y + h, outline);
		ctx.fill(x, y + 2, x + 1, y + h - 2, outline);
		ctx.fill(x + w - 1, y + 2, x + w, y + h - 2, outline);
		ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, body);
		// 2px bevel: light top-left, dark bottom-right.
		ctx.fill(x + 2, y + 1, x + w - 3, y + 3, light);
		ctx.fill(x + 1, y + 2, x + 3, y + h - 3, light);
		ctx.fill(x + 3, y + h - 3, x + w - 2, y + h - 1, dark);
		ctx.fill(x + w - 3, y + 3, x + w - 1, y + h - 2, dark);
		// Corner pixels close the rounding.
		ctx.fill(x + 1, y + 1, x + 2, y + 2, outline);
		ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, outline);
		ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, outline);
		ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, outline);

		// Title strip: muted black, exactly as wide as the body below it, white text; long names wrap.
		List<OrderedText> lines = titleLines(tr);
		int stripBottom = y + stripBottom(tr);
		ctx.fill(x + GRID_X, y + TITLE_TOP - 1, x + w - GRID_X, stripBottom, 0x33000000);
		if (palette.accent() != 0) ctx.fill(x + GRID_X, stripBottom, x + w - GRID_X, stripBottom + 1, palette.accent());
		int ly = y + TITLE_TOP + 1;
		for (OrderedText line : lines) {
			ctx.drawText(tr, line, x + GRID_X + TITLE_PAD, ly, 0xFFFFFFFF, true);
			ly += LINE_H;
		}

		int gx = x + GRID_X;
		int gy = y + bodyY(tr);
		if (empty) {
			// title + size only
		} else if (listMode) {
			drawList(tr, ctx, gx, gy, w - GRID_X * 2);
		} else {
			drawGrid(tr, ctx, gx, gy);
		}

		int textColor = luminance(body) > 140 ? 0xFF404040 : 0xFFFFFFFF;
		boolean shadow = textColor == 0xFFFFFFFF;
		int footerY = empty ? stripBottom + 3 : gy + bodyH() + 3;
		String fill = fillText();
		if (!fill.isEmpty()) ctx.drawText(tr, fill, x + w - GRID_X - TITLE_PAD - tr.getWidth(fill), footerY, textColor, shadow);
		if (!sizeLine.isEmpty()) ctx.drawText(tr, sizeLine, x + GRID_X + TITLE_PAD, footerY, textColor, shadow);
	}

	private void drawGrid(TextRenderer tr, DrawContext ctx, int gx, int gy) {
		for (int i = 0; i < COLS * ROWS; i++) {
			int sx = gx + (i % COLS) * SLOT;
			int sy = gy + (i / COLS) * SLOT;
			drawSlot(ctx, sx, sy);
			ItemStack stack = slot(i);
			if (!stack.isEmpty()) {
				ctx.drawItem(stack, sx + 1, sy + 1);
				ctx.drawStackOverlay(tr, stack, sx + 1, sy + 1);
			}
		}
		if (items == null && !emptyHint.isEmpty()) {
			int hintW = tr.getWidth(emptyHint);
			int hx = gx + (COLS * SLOT - hintW) / 2;
			int hy = gy + (ROWS * SLOT - tr.fontHeight) / 2;
			ctx.fill(hx - 3, hy - 2, hx + hintW + 3, hy + tr.fontHeight + 1, 0xB0000000);
			ctx.drawText(tr, emptyHint, hx, hy, 0xFFDDDDDD, true);
		}
	}

	private void drawSlot(DrawContext ctx, int sx, int sy) {
		int fill = palette.slotFill();
		ctx.fill(sx, sy, sx + 17, sy + 1, palette.slotDark());
		ctx.fill(sx, sy + 1, sx + 1, sy + 17, palette.slotDark());
		ctx.fill(sx + 1, sy + 17, sx + 18, sy + 18, palette.slotLight());
		ctx.fill(sx + 17, sy + 1, sx + 18, sy + 17, palette.slotLight());
		ctx.fill(sx + 1, sy + 1, sx + 17, sy + 17, fill);
		ctx.fill(sx + 17, sy, sx + 18, sy + 1, fill);
		ctx.fill(sx, sy + 17, sx + 1, sy + 18, fill);
	}

	/** Recessed well, like one big slot. */
	private void drawWell(DrawContext ctx, int x, int y, int w, int h) {
		ctx.fill(x, y, x + w - 1, y + 1, palette.slotDark());
		ctx.fill(x, y + 1, x + 1, y + h - 1, palette.slotDark());
		ctx.fill(x + 1, y + h - 1, x + w, y + h, palette.slotLight());
		ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, palette.slotLight());
		ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, palette.slotFill());
	}

	// -------------------------------------------------------------- list --

	private int listCols() {
		return entries.size() > LIST_ONE_COLUMN ? 2 : 1;
	}

	private int listRows() {
		return (entries.size() + listCols() - 1) / listCols();
	}

	private static String entryText(ItemStack s) {
		return s.getName().getString() + " x" + s.getCount();
	}

	private int colW(TextRenderer tr) {
		int max = 0;
		for (ItemStack s : entries) max = Math.max(max, tr.getWidth(entryText(s)));
		return Math.min(LIST_MAX_COL_W, LIST_ICON + 2 + max);
	}

	private int listW(TextRenderer tr) {
		int cols = listCols();
		return LIST_PAD * 2 + cols * colW(tr) + (cols - 1) * LIST_COL_GAP;
	}

	private void drawList(TextRenderer tr, DrawContext ctx, int x, int y, int w) {
		drawWell(ctx, x, y, w, bodyH());
		int text = luminance(palette.slotFill()) > 150 ? 0xFF303030 : 0xFFFFFFFF;
		boolean shadow = text == 0xFFFFFFFF;
		if (entries.isEmpty()) {
			String msg = items == null && !emptyHint.isEmpty() ? emptyHint : "Empty";
			ctx.drawText(tr, msg, x + LIST_PAD + 1, y + LIST_PAD + 1, text, shadow);
			return;
		}
		int cw = colW(tr);
		int rows = listRows();
		for (int i = 0; i < entries.size(); i++) {
			int col = i / rows;
			int row = i % rows;
			int ex = x + LIST_PAD + col * (cw + LIST_COL_GAP);
			int ey = y + LIST_PAD + row * LINE_H;
			ItemStack s = entries.get(i);
			MatrixStack m = ctx.getMatrices();
			m.push();
			m.translate(ex, ey + 1, 0);
			m.scale(0.5f, 0.5f, 1f);
			ctx.drawItem(s, 0, 0);
			m.pop();
			String line = tr.trimToWidth(entryText(s), cw - LIST_ICON - 2);
			ctx.drawText(tr, line, ex + LIST_ICON + 2, ey + 1, text, shadow);
		}
	}

	private static int luminance(int argb) {
		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		return (r * 299 + g * 587 + b * 114) / 1000;
	}
}
