package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.modules.BaseBackup;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;
import net.minecraft.client.MinecraftClient;

/**
 * Meteor Yes/No before removing a home definition (snapshots remain until GC).
 */
public final class HomeRemoveConfirm {
	private HomeRemoveConfirm() {}

	public static void prompt(BaseBackup mod, Home home, Runnable afterRemoved) {
		if (mod == null || home == null) return;
		boolean snapshot = home.isSnapshot();
		YesNoPrompt.create()
			.title(snapshot ? "Remove snapshot?" : "Remove home?")
			.message("Remove \"%s\" from Time Machine?", home.name)
			.message(snapshot
				? "The snapshot definition is deleted. Captured data remains on disk until GC."
				: "The home definition is deleted. Snapshots remain on disk until GC.")
			.dontShowAgainCheckboxVisible(false)
			.onYes(() -> {
				try {
					mod.homeStore().remove(home.id);
					HomeChat.info("Removed " + home.name);
					if (afterRemoved != null) afterRemoved.run();
				} catch (Exception e) {
					HomeChat.warn("Remove failed: " + e);
				}
			})
			.onNo(() -> {})
			.show();
	}

	/** Edit-home flow: after remove, open manage without a selected home. */
	public static void promptFromEdit(BaseBackup mod, Home home) {
		prompt(mod, home, () ->
			MinecraftClient.getInstance().setScreen(new BaseBackupScreen(mod)));
	}
}
