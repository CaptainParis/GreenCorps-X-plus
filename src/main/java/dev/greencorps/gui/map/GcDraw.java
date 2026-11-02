package dev.greencorps.gui.map;

import net.minecraft.client.gui.DrawContext;

/** Minimal drawing helpers for the Xaero overlay. */
public final class GcDraw {
	private GcDraw() {}

	public static int opaque(int argb) {
		return 0xFF000000 | (argb & 0x00FFFFFF);
	}

	public static int withAlpha(int argb, float frac) {
		int a = Math.min(255, Math.max(0, (int) (((argb >>> 24) & 0xFF) * frac)));
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	public static void fillCircle(DrawContext g, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.sqrt(r * r - dy * dy);
			g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	public static void ring(DrawContext g, int cx, int cy, int r, int color, int width) {
		for (int w = 0; w < width; w++) {
			int rr = r - w;
			if (rr <= 0) break;
			for (int a = 0; a < 360; a += 2) {
				double rad = Math.toRadians(a);
				int x = cx + (int) Math.round(Math.cos(rad) * rr);
				int y = cy + (int) Math.round(Math.sin(rad) * rr);
				g.fill(x, y, x + 1, y + 1, color);
			}
		}
	}

	public static void fillRect(DrawContext g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0, y0, x1, y1, color);
	}

	/** Axis-aligned rectangle outline (no fill), width in pixels. */
	public static void rectOutline(DrawContext g, int x0, int y0, int x1, int y1, int color, int width) {
		int left = Math.min(x0, x1);
		int right = Math.max(x0, x1);
		int top = Math.min(y0, y1);
		int bottom = Math.max(y0, y1);
		if (right - left < 1 || bottom - top < 1) return;
		for (int w = 0; w < width; w++) {
			int l = left + w;
			int r = right - w;
			int t = top + w;
			int b = bottom - w;
			if (r <= l || b <= t) break;
			g.fill(l, t, r, t + 1, color);
			g.fill(l, b - 1, r, b, color);
			g.fill(l, t, l + 1, b, color);
			g.fill(r - 1, t, r, b, color);
		}
	}
}
