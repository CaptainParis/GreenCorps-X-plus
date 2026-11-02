package dev.greencorps.gui;

import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.screens.ModuleScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/**
 * Reload the open Meteor module screen so embedded panels refresh after create.
 * Never reloads {@link StasisOverviewScreen} — that screen owns its own body refresh
 * (a full WindowScreen.reload mid-session swallows clicks and flickers).
 */
public final class PanelReload {
	private PanelReload() {}

	public static void reloadModuleScreen() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		if (mc.currentScreen instanceof StasisOverviewScreen) return;

		Screen s = mc.currentScreen;
		for (int i = 0; i < 6 && s != null; i++) {
			if (s instanceof StasisOverviewScreen) return;
			if (s instanceof ModuleScreen ms) {
				ms.reload();
				return;
			}
			if (s instanceof WidgetScreen ws && ws.parent != null) {
				s = ws.parent;
				continue;
			}
			break;
		}
		// Do not fall back to reloading an arbitrary WidgetScreen (e.g. overview).
	}
}
