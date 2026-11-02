package dev.greencorps.gui;

import meteordevelopment.meteorclient.addons.AddonManager;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Title-screen branding for QOLARR mods in Meteor's top-right credit stack.
 * <p>
 * GreenCorps itself appears via its {@link MeteorAddon} entrypoint.
 * When God's Eye (MODUS) is present, injects a versionless
 * {@code MODUS Elytra Autopilot & HUD by QOLARR} credit sized to sort
 * between Bep Hax and GreenCorps X+. Bottom-center tags are intentionally unused.
 */
public final class GreenCorpsTitleTag {
	/** Soft gold — matches former GodsEye bottom-center MODUS tag. */
	private static final int GOLD_R = 0xE0;
	private static final int GOLD_G = 0xC0;
	private static final int GOLD_B = 0x56;

	private static boolean registered;

	private GreenCorpsTitleTag() {}

	public static void register() {
		if (registered) return;
		registered = true;
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> injectModusCredit());
	}

	/**
	 * Insert MODUS into {@link AddonManager#ADDONS} before the first title-screen
	 * credits build (which copies ADDONS and sorts Meteor first, then by -width).
	 */
	private static void injectModusCredit() {
		if (!FabricLoader.getInstance().isModLoaded("godseye")) return;
		for (MeteorAddon existing : AddonManager.ADDONS) {
			if (existing != null && "MODUS Elytra Autopilot & HUD".equals(existing.name)) {
				return;
			}
		}
		MeteorAddon modus = new MeteorAddon() {
			@Override
			public void onInitialize() {}

			@Override
			public String getPackage() {
				return "dev.godseye.credits";
			}
		};
		modus.name = "MODUS Elytra Autopilot & HUD";
		modus.authors = new String[]{"QOLARR"};
		modus.color.set(GOLD_R, GOLD_G, GOLD_B, 255);
		AddonManager.ADDONS.add(modus);
	}
}
