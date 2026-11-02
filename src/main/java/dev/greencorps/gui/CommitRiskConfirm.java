package dev.greencorps.gui;

import dev.greencorps.backup.CommitRiskScanner;
import dev.greencorps.backup.Home;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.modules.BaseBackup;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;
import net.minecraft.client.MinecraftClient;

/**
 * Yes/No before sealing a commit that looks severely destructive.
 * Reject pauses {@link Home#autoCommit} until the user re-arms it.
 */
public final class CommitRiskConfirm {
	private CommitRiskConfirm() {}

	public static void prompt(BaseBackup mod, Home home, CommitRiskScanner.Report report, boolean manual) {
		if (mod == null || home == null || report == null) return;
		YesNoPrompt prompt = YesNoPrompt.create()
			.title("Severe change detected")
			.message("Home: %s", home.name)
			.message("Accept seals this snapshot. Reject pauses auto-backups for this home.");
		for (String line : report.reasons()) {
			prompt.message("%s", line);
		}
		prompt.dontShowAgainCheckboxVisible(false)
			.onYes(() -> mod.sealCommitAfterRiskConfirm(home, manual))
			.onNo(() -> {
				try {
					mod.pauseAutoCommitAfterRiskReject(home);
				} catch (Exception e) {
					HomeChat.warn("Could not pause auto-backups: " + e.getClass().getSimpleName());
				}
			})
			.show();
	}

	/** True when a Yes/No prompt would steal focus unsafely. */
	public static boolean busyForDialog() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.player == null) return true;
		if (mc.currentScreen != null) return true;
		if (mc.player.isGliding()) return true;
		return mc.player.hurtTime > 0;
	}
}
