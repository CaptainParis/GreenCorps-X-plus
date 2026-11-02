package dev.greencorps.hud;

import dev.greencorps.GreenCorps;
import dev.greencorps.backup.StoreStats;
import dev.greencorps.modules.BaseBackup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class BaseBackupHud extends HudElement {
	public static final HudElementInfo<BaseBackupHud> INFO = new HudElementInfo<>(
		GreenCorps.HUD_GROUP, "time-machine", "Time Machine status.", BaseBackupHud::new);

	public BaseBackupHud() {
		super(INFO);
	}

	@Override
	public void render(HudRenderer renderer) {
		BaseBackup mod = Modules.get().get(BaseBackup.class);
		String line1 = "TM: off";
		String line2 = "";
		if (mod != null && mod.isActive() && mod.engine() != null) {
			line1 = "TM: " + mod.engine().health().label(mod.engine().degradedSections())
				+ " dirty=" + mod.engine().totalDirty();
			line2 = "store " + StoreStats.formatBytes(mod.engine().estimateStoreSize())
				+ " | reh " + (mod.engine().lastRehearsalAt() == 0 ? "-"
				: (mod.engine().lastRehearsalPass() ? "PASS" : "FAIL"));
		}
		setSize(renderer.textWidth(line1.length() > line2.length() ? line1 : line2),
			renderer.textHeight() * (line2.isEmpty() ? 1 : 2) + 2);
		renderer.text(line1, x, y, meteordevelopment.meteorclient.utils.render.color.Color.WHITE, true);
		if (!line2.isEmpty()) {
			renderer.text(line2, x, y + renderer.textHeight() + 1,
				meteordevelopment.meteorclient.utils.render.color.Color.LIGHT_GRAY, true);
		}
	}
}
