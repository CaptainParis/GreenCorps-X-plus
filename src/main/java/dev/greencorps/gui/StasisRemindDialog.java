package dev.greencorps.gui;

import dev.greencorps.modules.StasisChambers;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/** Post-pull reminder: set a pearl again. Understood acknowledges. */
public final class StasisRemindDialog {
	private StasisRemindDialog() {}

	/**
	 * Opens on the client thread. Safe to call from packet/net handlers.
	 * If opening is skipped (another screen is up), {@code onUnderstood} still runs so callers can clear locks.
	 */
	public static void show(StasisChambers mod, int x, int y, int z, int xzBlocks, Runnable onUnderstood) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mod == null) {
			if (onUnderstood != null) onUnderstood.run();
			return;
		}
		mc.execute(() -> {
			if (mc.currentScreen != null) {
				if (onUnderstood != null) onUnderstood.run();
				return;
			}
			var screen = new Screen(mod, x, y, z, xzBlocks, onUnderstood);
			screen.parent = null;
			mc.setScreen(screen);
		});
	}

	private static final class Screen extends WindowScreen {
		private final StasisChambers mod;
		private final int x;
		private final int y;
		private final int z;
		private final int xzBlocks;
		private final Runnable onUnderstood;
		private boolean acknowledged;

		Screen(StasisChambers mod, int x, int y, int z, int xzBlocks, Runnable onUnderstood) {
			super(GuiThemes.get(), "Set a Pearl");
			this.mod = mod;
			this.x = x;
			this.y = y;
			this.z = z;
			this.xzBlocks = xzBlocks;
			this.onUnderstood = onUnderstood;
		}

		@Override
		public void initWidgets() {
			WTable t = add(theme.table()).expandX().widget();
			t.add(theme.label("You were pulled ~" + xzBlocks + " blocks (not your own pearl throw).")).expandX();
			t.row();
			t.add(theme.label("Arrival: " + x + " " + y + " " + z)).expandX();
			t.row();
			t.add(theme.label("Place a pearl in a water/honey chamber so you can return.")).expandX();
			t.row();
			t.add(theme.label("Self-throws and small/phasing teleports are ignored.")).expandX();
			t.row();
			t.add(theme.label("Enter / Space = Understood (this dialog only)")).expandX();
			t.row();

			WButton ok = t.add(theme.button("Understood")).expandX().widget();
			ok.action = this::acknowledge;
		}

		private void acknowledge() {
			if (acknowledged) return;
			acknowledged = true;
			mod.acknowledgePull();
			if (onUnderstood != null) onUnderstood.run();
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
				mod.acknowledgePull();
			}
			super.close();
		}
	}
}
