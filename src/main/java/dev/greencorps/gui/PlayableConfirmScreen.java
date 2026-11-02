package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.backup.SnapshotIndex;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.export.PlayableSavePublisher;
import dev.greencorps.modules.BaseBackup;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

/**
 * Meteor Yes/No prompt after creating a home or snapshot — opt into playable SP saves.
 */
public final class PlayableConfirmScreen {
	private PlayableConfirmScreen() {}

	public static void promptAfterCreate(BaseBackup mod, Home home) {
		if (mod == null || home == null) return;
		String example = PlayableSavePublisher.autoFolderName(home);
		YesNoPrompt.create()
			.title("Keep playable Singleplayer save?")
			.message("Maintains one world: saves/%s (LevelName shows BACKUP timestamp).", example)
			.message("Needs master auto-playable-latest (enabled on Yes).")
			.message("Manual Export still creates a separate archive world when you ask.")
			.dontShowAgainCheckboxVisible(false)
			.onYes(() -> {
				try {
					mod.enablePlayableForHome(home);
					HomeChat.info("Playable on for " + home.name + " → saves/"
						+ PlayableSavePublisher.autoFolderName(home));
					maybeQueueExport(mod, home);
				} catch (Exception e) {
					HomeChat.warn("Playable enable failed: " + e);
				}
			})
			.onNo(() -> {})
			.show();
	}

	private static void maybeQueueExport(BaseBackup mod, Home home) {
		if (mod.engine() == null || !mod.shouldPublishPlayable(home)) return;
		try {
			SnapshotIndex idx = new SnapshotIndex(mod.engine().refsDir(), home.id);
			if (idx.latest().isEmpty()) return;
			Path saves = PlayableSavePublisher.savesRoot(MinecraftClient.getInstance());
			PlayableSavePublisher.publishLatestAsync(mod.engine(), home, saves, mod.shiftCoordsTo0());
		} catch (Exception e) {
			HomeChat.warn("Playable export queue failed: " + e);
		}
	}
}
