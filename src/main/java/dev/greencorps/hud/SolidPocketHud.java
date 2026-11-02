package dev.greencorps.hud;

import dev.greencorps.GreenCorps;
import dev.greencorps.modules.SolidPocketFinder;
import dev.greencorps.util.DensityBand;
import dev.greencorps.util.DensityStats;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class SolidPocketHud extends HudElement {
	public static final HudElementInfo<SolidPocketHud> INFO = new HudElementInfo<>(
		GreenCorps.HUD_GROUP,
		"solid-pocket",
		"Near solid-pocket density and compact score history.",
		SolidPocketHud::new
	);

	private static final Color LABEL = new Color(170, 170, 170);
	private static final Color VALUE = new Color(180, 220, 120);

	public SolidPocketHud() {
		super(INFO);
	}

	@Override
	public void render(HudRenderer renderer) {
		String text = "Solid: —";
		boolean hot = false;

		SolidPocketFinder finder = SolidPocketFinder.get();
		if (finder != null && finder.isActive()) {
			int near = finder.nearDensity();
			DensityStats h = finder.history();
			String nearStr = near >= 0 ? DensityBand.info(near) : "?";
			if (h.n() <= 0) {
				text = "Solid " + nearStr;
			} else {
				text = "Solid " + nearStr
					+ "  max=" + h.max()
					+ "  p90=" + h.percentile(90);
			}
			hot = near >= 0;
		}

		setSize(renderer.textWidth(text, true), renderer.textHeight(true));
		renderer.text(text, x, y, hot ? VALUE : LABEL, true);
	}
}
