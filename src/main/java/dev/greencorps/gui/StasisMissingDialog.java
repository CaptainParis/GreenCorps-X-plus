package dev.greencorps.gui;

import dev.greencorps.modules.StasisChambers;
import dev.greencorps.stasis.ChamberKind;
import dev.greencorps.stasis.StasisHome;
import dev.greencorps.stasis.StasisPearl;
import dev.greencorps.stasis.StasisStore;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/** Situation-aware missing-pearl dialog with a single Understood button. */
public final class StasisMissingDialog {
	private StasisMissingDialog() {}

	/**
	 * Opens on the client thread. Safe if ever called off-thread.
	 * {@code onOpened} runs only when the dialog is shown; {@code onSkipped} when another
	 * screen blocks it (so callers do not arm cooldown for an unseen dialog).
	 */
	public static void show(StasisChambers mod, StasisHome home, StasisStore.HomeCapacity cap,
							Runnable onOpened, Runnable onSkipped) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || home == null || cap == null) {
			if (onSkipped != null) onSkipped.run();
			return;
		}
		mc.execute(() -> {
			if (mc.currentScreen != null) {
				if (onSkipped != null) onSkipped.run();
				return;
			}
			var screen = new Screen(mod, home, cap);
			screen.parent = null;
			mc.setScreen(screen);
			if (onOpened != null) onOpened.run();
		});
	}

	private static final class Screen extends WindowScreen {
		private final StasisChambers mod;
		private final StasisHome home;
		private final StasisStore.HomeCapacity cap;
		private boolean acknowledged;

		Screen(StasisChambers mod, StasisHome home, StasisStore.HomeCapacity cap) {
			super(GuiThemes.get(), "Pearl Missing");
			this.mod = mod;
			this.home = home;
			this.cap = cap;
		}

		@Override
		public void initWidgets() {
			MinecraftClient mc = MinecraftClient.getInstance();
			WTable t = add(theme.table()).expandX().widget();

			t.add(theme.label("Home: " + home.name)).expandX();
			t.row();
			t.add(theme.label("Pearls: " + cap.quotasLine()
				+ " (" + cap.alertMissing() + " alert-missing)")).expandX();
			t.row();
			t.add(theme.label(cap.capacityAlertLine())).expandX();
			t.row();
			t.add(theme.label("Coords:")).expandX();
			t.add(new WCoordReveal(home.centerX, home.centerY, home.centerZ)).expandX();
			t.row();
			t.add(theme.label("Dimension: " + home.dimension)).expandX();
			t.row();

			String kind = ChamberKind.UNKNOWN;
			String owner = "unknown";
			if (mod.store() != null) {
				for (StasisPearl p : mod.store().pearls()) {
					if (home.sameDimension(p.dimension) && home.containsBlock(p.x, p.z)) {
						kind = p.chamberKind;
						owner = p.ownerDisplay(mod.sessionStartMs());
						break;
					}
				}
			}
			t.add(theme.label("Chamber: " + ChamberKind.label(kind))).expandX();
			t.row();
			t.add(theme.label("Owner hint: " + owner)).expandX();
			t.row();

			String dist = "n/a";
			if (mc != null && mc.player != null && home.sameDimension(
				mc.world != null ? mc.world.getRegistryKey().getValue().toString() : "")) {
				double d = Math.sqrt(
					Math.pow(mc.player.getX() - home.centerX, 2)
						+ Math.pow(mc.player.getY() - home.centerY, 2)
						+ Math.pow(mc.player.getZ() - home.centerZ, 2));
				dist = (int) d + " blocks";
			}
			t.add(theme.label("Distance: " + dist)).expandX();
			t.row();
			t.add(theme.label("Chunks covering this home are loaded; pearl count is below quota.")).expandX();
			t.row();
			t.add(theme.label("Enter / Space = Understood (this dialog only)")).expandX();
			t.row();

			WButton ok = t.add(theme.button("Understood")).expandX().widget();
			ok.action = this::acknowledge;
		}

		private void acknowledge() {
			if (acknowledged) return;
			acknowledged = true;
			mod.acknowledgeMissing(home);
			close();
		}

		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
				|| keyCode == GLFW.GLFW_KEY_SPACE) {
				acknowledge();
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers);
		}

		@Override
		public void close() {
			if (!acknowledged) {
				acknowledged = true;
				mod.acknowledgeMissing(home);
			}
			super.close();
		}
	}
}
