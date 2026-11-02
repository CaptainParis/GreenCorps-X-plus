package dev.greencorps.gui.map;

import dev.greencorps.backup.Home;
import dev.greencorps.backup.StoreHealth;
import dev.greencorps.backup.StoreStats;
import dev.greencorps.commands.TimeSelector;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Hover detail card — styled like godseye GodsEyePoiCard.
 * Scale matches GodsEye {@code poi_font_scale} default (0.7).
 */
public final class GcHomeCard {
	/** Same default as GodsEyeConfig.poiFontScale. */
	private static final float LABEL_SCALE = 0.7f;
	private static final int PAD = 6;
	private static final int LINE = 11;
	private static final int MARKER_GAP = 16;

	private GcHomeCard() {}

	public static void draw(DrawContext g, TextRenderer font, Home home,
							double coverage, int snapshots, long storeBytes,
							StoreHealth health, int degraded,
							boolean rehearsalPass, long rehearsalAt,
							int anchorX, int anchorY, int screenW, int screenH) {
		String title = home.name;
		String[] lines = {
			"@" + home.centerChunkX + "," + home.centerChunkZ + " r=" + home.radiusChunks,
			"cov " + (int) coverage + "pct | snaps " + snapshots,
			"store " + StoreStats.formatBytes(storeBytes),
			health.label(degraded),
			"rehearsal: " + (rehearsalAt == 0 ? "never"
				: ((rehearsalPass ? "PASS" : "FAIL") + " " + TimeSelector.format(rehearsalAt))),
			home.armed ? "armed" : "idle"
		};
		int contentW = font.getWidth(title) + PAD * 2;
		for (String l : lines) contentW = Math.max(contentW, font.getWidth(l) + PAD * 2);
		contentW = Math.min(contentW, 260);
		int contentH = PAD * 2 + LINE + lines.length * LINE + 4;

		int scaledW = Math.round(contentW * LABEL_SCALE);
		int scaledH = Math.round(contentH * LABEL_SCALE);

		int sx = anchorX + MARKER_GAP;
		int sy = anchorY + MARKER_GAP / 2;
		if (sx + scaledW > screenW - 4) sx = anchorX - MARKER_GAP - scaledW;
		if (sy + scaledH > screenH - 4) sy = screenH - scaledH - 4;
		if (sx < 4) sx = 4;
		if (sy < 4) sy = 4;

		MatrixStack matrices = g.getMatrices();
		matrices.push();
		matrices.translate(sx, sy, 0);
		matrices.scale(LABEL_SCALE, LABEL_SCALE, 1f);

		g.fill(-1, -1, contentW + 1, contentH + 1, 0xFF101010);
		g.fill(0, 0, contentW, contentH, 0xF2181818);
		g.fill(0, 0, contentW, 2, 0xFF28B450);

		int y = PAD;
		g.drawText(font, title, PAD, y, 0xFF70C0D0, false);
		y += LINE;
		for (String l : lines) {
			g.drawText(font, l, PAD, y, 0xFFE0E0E0, false);
			y += LINE;
		}

		matrices.pop();
	}
}
