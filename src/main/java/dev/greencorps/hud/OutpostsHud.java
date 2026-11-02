package dev.greencorps.hud;

import dev.greencorps.GreenCorps;
import dev.greencorps.modules.OutpostFinder;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class OutpostsHud extends HudElement {
	public static final HudElementInfo<OutpostsHud> INFO = new HudElementInfo<>(
		GreenCorps.HUD_GROUP,
		"outposts",
		"Live count of confirmed pillager outposts in loaded chunks.",
		OutpostsHud::new
	);

	private static final Color LABEL = new Color(170, 170, 170);
	private static final Color VALUE = new Color(255, 215, 0);

	public OutpostsHud() {
		super(INFO);
	}

	@Override
	public void render(HudRenderer renderer) {
		int n = 0;
		OutpostFinder finder = OutpostFinder.get();
		if (finder != null) n = finder.liveCount();

		String text = "Outposts: " + n;
		setSize(renderer.textWidth(text, true), renderer.textHeight(true));
		renderer.text(text, x, y, n > 0 ? VALUE : LABEL, true);
	}
}
