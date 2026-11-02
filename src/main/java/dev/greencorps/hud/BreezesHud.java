package dev.greencorps.hud;

import dev.greencorps.GreenCorps;
import dev.greencorps.modules.BreezeFinder;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class BreezesHud extends HudElement {
	public static final HudElementInfo<BreezesHud> INFO = new HudElementInfo<>(
		GreenCorps.HUD_GROUP,
		"breezes",
		"Live count of breeze spawners and blue trial vaults in loaded chunks.",
		BreezesHud::new
	);

	private static final Color LABEL = new Color(170, 170, 170);
	private static final Color VALUE = new Color(85, 255, 255);

	public BreezesHud() {
		super(INFO);
	}

	@Override
	public void render(HudRenderer renderer) {
		int b = 0;
		int tc = 0;
		BreezeFinder finder = BreezeFinder.get();
		if (finder != null) {
			b = finder.liveBreezeCount();
			tc = finder.liveVaultCount();
		}

		String text = "Breezes: " + b + " | Vaults: " + tc;
		setSize(renderer.textWidth(text, true), renderer.textHeight(true));
		renderer.text(text, x, y, (b > 0 || tc > 0) ? VALUE : LABEL, true);
	}
}
