package dev.greencorps.gui.wing;

import net.minecraft.client.gui.DrawContext;

/** Vanilla container art drawn with fills, for wings without a texture. */
public final class WingDraw {
	public static final int BODY = 0xFFC6C6C6;
	public static final int LIGHT = 0xFFFFFFFF;
	public static final int DARK = 0xFF555555;
	public static final int SLOT_DARK = 0xFF373737;
	public static final int SLOT_FILL = 0xFF8B8B8B;

	private WingDraw() {}

	/** Rounded vanilla GUI panel: black outline, 2px bevel, grey body. */
	public static void panel(DrawContext ctx, int x, int y, int w, int h) {
		int outline = 0xFF000000;
		ctx.fill(x + 2, y, x + w - 2, y + 1, outline);
		ctx.fill(x + 2, y + h - 1, x + w - 2, y + h, outline);
		ctx.fill(x, y + 2, x + 1, y + h - 2, outline);
		ctx.fill(x + w - 1, y + 2, x + w, y + h - 2, outline);
		ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, BODY);
		ctx.fill(x + 2, y + 1, x + w - 3, y + 3, LIGHT);
		ctx.fill(x + 1, y + 2, x + 3, y + h - 3, LIGHT);
		ctx.fill(x + 3, y + h - 3, x + w - 2, y + h - 1, DARK);
		ctx.fill(x + w - 3, y + 3, x + w - 1, y + h - 2, DARK);
		ctx.fill(x + 1, y + 1, x + 2, y + 2, outline);
		ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, outline);
		ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, outline);
		ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, outline);
	}

	/** Dark header strip with a light title, as on vanilla's darker GUI bars. */
	public static void header(DrawContext ctx, int x, int y, int w, int h) {
		ctx.fill(x, y, x + w, y + h, 0xFF6B6B6B);
		ctx.fill(x, y, x + w, y + 1, 0xFF4A4A4A);
		ctx.fill(x, y + h - 1, x + w, y + h, 0xFF8F8F8F);
	}

	/** Recessed card: slot-style bevel around a slightly darker body. */
	public static void card(DrawContext ctx, int x, int y, int w, int h) {
		ctx.fill(x, y, x + w - 1, y + 1, SLOT_DARK);
		ctx.fill(x, y + 1, x + 1, y + h - 1, SLOT_DARK);
		ctx.fill(x + 1, y + h - 1, x + w, y + h, LIGHT);
		ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, LIGHT);
		ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFB4B4B4);
	}

	/** Small status dot. */
	public static void dot(DrawContext ctx, int x, int y, int color) {
		ctx.fill(x + 1, y, x + 4, y + 5, color);
		ctx.fill(x, y + 1, x + 5, y + 4, color);
	}

	/** Rounded chip behind a short label. */
	public static void chip(DrawContext ctx, int x, int y, int w, int h, int color) {
		ctx.fill(x + 1, y, x + w - 1, y + h, color);
		ctx.fill(x, y + 1, x + w, y + h - 1, color);
	}

	/** Inset well (slot style), e.g. behind a progress bar. */
	public static void inset(DrawContext ctx, int x, int y, int w, int h) {
		ctx.fill(x, y, x + w - 1, y + 1, SLOT_DARK);
		ctx.fill(x, y + 1, x + 1, y + h - 1, SLOT_DARK);
		ctx.fill(x + 1, y + h - 1, x + w, y + h, LIGHT);
		ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, LIGHT);
		ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, SLOT_FILL);
	}
}
