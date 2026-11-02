package dev.greencorps.gui.map;

import net.minecraft.client.gui.screen.Screen;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/** Xaero native right-click menu entry (same pattern as godseye / XaeroPlus). */
public final class GcRightClickOption extends RightClickOption {
	private final Runnable action;

	public GcRightClickOption(String label, IRightClickableElement target, Runnable action) {
		super(label, 0, target);
		this.action = action;
	}

	@Override
	public void onAction(Screen screen) {
		action.run();
	}
}
