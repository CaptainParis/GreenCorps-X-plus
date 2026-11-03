package dev.greencorps.hud;

import dev.greencorps.GreenCorps;
import dev.greencorps.anticlient.HudSnapshot;
import dev.greencorps.anticlient.SurvivalVerdict;
import dev.greencorps.modules.AntiClient;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.Locale;

public class AntiClientHud extends HudElement {
	public static final HudElementInfo<AntiClientHud> INFO = new HudElementInfo<>(
		GreenCorps.HUD_GROUP, "anti-client", "Anti-Client threat, survival and scorecard.", AntiClientHud::new);

	private static final Color RED = new Color(255, 70, 70);
	private static final Color ORANGE = new Color(255, 170, 40);
	private static final Color YELLOW = new Color(255, 230, 80);
	private static final Color GREEN = new Color(120, 230, 120);

	public AntiClientHud() {
		super(INFO);
	}

	@Override
	public void render(HudRenderer renderer) {
		AntiClient mod = Modules.get().get(AntiClient.class);
		HudSnapshot s = mod == null ? HudSnapshot.OFF : mod.snapshot();
		if (!s.active()) {
			String off = "AC: off";
			setSize(renderer.textWidth(off), renderer.textHeight());
			renderer.text(off, x, y, Color.GRAY, true);
			return;
		}
		String l1 = "AC [" + s.scenario() + "] " + s.level() + (s.topName().isEmpty() ? "" : " " + s.topName()
			+ " " + s.archetype() + " s" + s.score() + (s.etaTicks() >= 0 ? " eta " + s.etaTicks() + "t" : ""));
		String l2 = "Plan " + s.plan().action() + (s.plan().fireInTicks() > 0 ? " in " + s.plan().fireInTicks() + "t" : "")
			+ " | Surv " + s.survival() + " lives " + s.lives() + " margin " + s.marginTicks() + "t";
		String l3 = String.format(Locale.ROOT, "TP %d FP %d MISS %d | ping %dms lead %dt",
			s.tp(), s.fp(), s.miss(), s.pingMs(), s.leadTicks());
		String l4 = s.flags().isEmpty() ? "" : "Flags: " + s.flags();

		Color c1 = switch (s.level()) {
			case COMMIT -> RED;
			case ARMED -> ORANGE;
			case WATCH -> YELLOW;
			default -> GREEN;
		};
		Color c2 = switch (s.survival()) {
			case DOOMED -> RED;
			case DEGRADED -> ORANGE;
			default -> GREEN;
		};
		String[] lines = l4.isEmpty() ? new String[] {l1, l2, l3} : new String[] {l1, l2, l3, l4};
		double w = 0;
		for (String l : lines) w = Math.max(w, renderer.textWidth(l));
		double h = renderer.textHeight();
		setSize(w, (h + 1) * lines.length);
		double yy = y;
		renderer.text(l1, x, yy, c1, true);
		yy += h + 1;
		renderer.text(l2, x, yy, c2, true);
		yy += h + 1;
		renderer.text(l3, x, yy, Color.LIGHT_GRAY, true);
		if (!l4.isEmpty()) {
			yy += h + 1;
			renderer.text(l4, x, yy, s.survival() == SurvivalVerdict.Kind.OK ? Color.LIGHT_GRAY : ORANGE, true);
		}
	}
}
